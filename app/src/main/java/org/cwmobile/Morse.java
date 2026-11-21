package org.cwmobile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Morse {
    static final int RX_RATE = 8000;
    private static final Map<String, String> ENCODE = new HashMap<>();
    private static final Map<String, String> LAT = new HashMap<>();
    private static final Map<String, String> RUS = new HashMap<>();
    private static final Pattern TOKENS = Pattern.compile("<[^<>]+>|.");
    private static final Pattern FIELDS = Pattern.compile("\\{([^{}]+)\\}");

    static {
        alphabet(LAT, "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                ".- -... -.-. -.. . ..-. --. .... .. .--- -.- .-.. -- -. --- .--. --.- .-. ... - ..- ...- .-- -..- -.-- --..");
        alphabet(RUS, "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ",
                ".- -... .-- --. -.. . ...- --.. .. .--- -.- .-.. -- -. --- .--. .-. ... - ..- ..-. .... -.-. ---. ---- --.- --.-- -.-- -..- ..-.. ..-- .-.-");
        String common = "0123456789/?=+.,:-'()@";
        String codes = "----- .---- ..--- ...-- ....- ..... -.... --... ---.. ----. -..-. ..--.. -...- .-.-. .-.-.- --..-- ---... -....- .----. -.--. -.--.- .--.-.";
        alphabet(LAT, common, codes);
        alphabet(RUS, common, codes);
        ENCODE.put("Ё", ".");
        ENCODE.put("<SK>", "...-.-");
        ENCODE.put("<AR>", ".-.-.");
        ENCODE.put("<BT>", "-...-");
        ENCODE.put("<KN>", "-.--.");
        LAT.put("...-.-", "<SK>");
        RUS.put("...-.-", "<SK>");
    }

    private static void alphabet(Map<String, String> decode, String chars, String codes) {
        String[] marks = codes.split(" ");
        for (int i = 0; i < chars.length(); i++) {
            String letter = chars.substring(i, i + 1);
            ENCODE.put(letter, marks[i]);
            decode.put(marks[i], letter);
        }
    }

    static String expand(String template, String call, String other, String rst) {
        Matcher matcher = FIELDS.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value;
            switch (key) {
                case "CALL": value = call; break;
                case "OTHER": value = other; break;
                case "RST": value = rst; break;
                default: throw new IllegalArgumentException("Неизвестная подстановка {" + key + "}.");
            }
            if (value.trim().isEmpty()) throw new IllegalArgumentException("Заполните поле {" + key + "}.");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.trim().toUpperCase(Locale.ROOT)));
        }
        return matcher.appendTail(result).toString();
    }

    static List<Integer> segments(String text, int wpm) {
        String trimmed = text.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Введите текст для передачи.");
        if (trimmed.length() > 2000) throw new IllegalArgumentException("Слишком длинное сообщение.");
        List<Integer> segments = new ArrayList<>();
        for (String word : trimmed.split("\\s+")) {
            if (!segments.isEmpty()) segments.add(-7);
            Matcher tokens = TOKENS.matcher(word);
            boolean first = true;
            while (tokens.find()) {
                String pattern = ENCODE.get(tokens.group());
                if (pattern == null) throw new IllegalArgumentException("Нет кода Морзе для " + tokens.group());
                if (!first) segments.add(-3);
                first = false;
                for (int i = 0; i < pattern.length(); i++) {
                    if (i > 0) segments.add(-1);
                    segments.add(pattern.charAt(i) == '.' ? 1 : 3);
                }
            }
        }
        int units = 0;
        for (int segment : segments) units += Math.abs(segment);
        if (1.2 * units > 60.0 * wpm) throw new IllegalArgumentException("Сообщение длиннее 60 секунд.");
        return segments;
    }

    static short[] waveform(String text, int rate, int wpm, int pitch, int gain) {
        List<Integer> segments = segments(text, wpm);
        int units = 0;
        for (int segment : segments) units += Math.abs(segment);
        double unit = 1.2 * rate / wpm;
        short[] pcm = new short[(int)Math.round(unit * units + 0.09 * rate)];
        int elapsed = 0;
        for (int segment : segments) {
            int start = (int)Math.round(elapsed * unit);
            elapsed += Math.abs(segment);
            if (segment < 0) continue;
            int end = (int)Math.round(elapsed * unit);
            int ramp = Math.min(rate / 250, (end - start) / 2);
            for (int i = start; i < end; i++) {
                int edge = Math.min(i - start, end - 1 - i);
                double envelope = edge < ramp ? Math.sin(Math.PI * edge / (2 * ramp)) : 1;
                pcm[i] = (short)Math.round(32767 * gain / 100.0 * envelope * envelope *
                        Math.sin(2 * Math.PI * pitch * (i - start) / rate));
            }
        }
        return pcm;
    }

    static final class Decoder {
        private static final int HOP = 40;
        private final Map<String, String> book;
        private final boolean adaptive;
        private final double[][] cos, sin;
        private final double[] frame;
        private final double[] taps, history;
        private int cursor, hop, position, streak, clock, changed;
        private double recentPeak;
        private boolean keyDown, previous, wordGap = true;
        private final StringBuilder marks = new StringBuilder();
        double dot, level = -100;

        Decoder(int pitch, int wpm, boolean russian, boolean adaptive, int bandwidth) {
            book = russian ? RUS : LAT;
            this.adaptive = adaptive;
            dot = 1.2 / wpm;
            int[] offsets = {-210, -150, -25, 0, 25, 150, 210};
            int size = HOP * (wpm >= 34 || bandwidth == 100 ? 3 : 4);
            frame = new double[size];
            cos = new double[offsets.length][size];
            sin = new double[offsets.length][size];
            for (int k = 0; k < offsets.length; k++) {
                for (int i = 0; i < size; i++) {
                    double window = 2 * (1 - Math.cos(2 * Math.PI * i / (size - 1))) / (size - 1);
                    double angle = 2 * Math.PI * (pitch + offsets[k]) * i / RX_RATE;
                    cos[k][i] = window * Math.cos(angle);
                    sin[k][i] = window * Math.sin(angle);
                }
            }
            if (bandwidth == 0) {
                taps = new double[]{1};
            } else {
                double sigma = RX_RATE * Math.sqrt(Math.log(2)) / (Math.PI * bandwidth);
                int radius = (int)Math.ceil(Math.max(4 * sigma, RX_RATE * 0.006));
                taps = new double[2 * radius + 1];
                double gain = 0;
                for (int i = -radius; i <= radius; i++) {
                    double carrier = Math.cos(2 * Math.PI * pitch * i / RX_RATE);
                    taps[i + radius] = Math.exp(-0.5 * i * i / (sigma * sigma)) * carrier;
                    gain += taps[i + radius] * carrier;
                }
                for (int i = 0; i < taps.length; i++) taps[i] /= gain;
            }
            history = new double[taps.length];
        }

        boolean keyed() { return keyDown; }

        void push(double sample, int sensitivity, StringBuilder result) {
            history[position] = sample;
            double filtered = 0;
            int index = position;
            for (double tap : taps) {
                filtered += history[index] * tap;
                if (--index < 0) index = history.length - 1;
            }
            if (++position == history.length) position = 0;
            frame[cursor] = filtered;
            if (++cursor == frame.length) cursor = 0;
            if (++hop < HOP) return;
            hop = 0;
            double signal = 0, guard = 0;
            for (int k = 0; k < cos.length; k++) {
                double real = 0, imag = 0;
                index = cursor;
                for (int i = 0; i < frame.length; i++) {
                    real += frame[index] * cos[k][i];
                    imag += frame[index] * sin[k][i];
                    if (++index == frame.length) index = 0;
                }
                double amplitude = Math.hypot(real, imag);
                if (k >= 2 && k <= 4) signal = Math.max(signal, amplitude);
                else guard = Math.max(guard, amplitude);
            }
            level = 20 * Math.log10(Math.max(signal, 1e-5));
            // Suppress the filter tail after a strong mark without raising the user's noise floor.
            recentPeak = Math.max(signal, recentPeak * 0.975);
            double threshold = Math.max(Math.pow(10, sensitivity / 20.0), 0.23 * recentPeak);
            boolean tone = signal >= Math.max(threshold * (keyDown ? 0.83 : 1),
                    guard * (keyDown ? 1.9 : 2.9));
            streak = tone == keyDown ? 0 : streak + 1;
            if (streak >= 2) {
                keyDown = tone;
                streak = 0;
            }
            clock += 5;
            double age = (clock - changed) / 1000.0;
            if (previous && !keyDown && age >= 0.36 * dot && age <= 5.4 * dot) {
                boolean dash = age >= 1.9 * dot;
                if (marks.length() < 12) marks.append(dash ? '-' : '.');
                double candidate = (age - 0.005) / (dash ? 3 : 1);
                if (adaptive && 0.65 * dot < candidate && candidate < 1.5 * dot) {
                    dot = Math.min(0.20, Math.max(0.028, 0.84 * dot + 0.16 * candidate));
                }
                wordGap = false;
            }
            if (!previous && age >= 2.35 * dot && marks.length() > 0) {
                result.append(book.getOrDefault(marks.toString(), "�"));
                marks.setLength(0);
            }
            if (!previous && age >= 5.2 * dot && !wordGap) {
                result.append(' ');
                wordGap = true;
            }
            if (previous != keyDown) {
                changed = clock;
                previous = keyDown;
            }
        }
    }
}
