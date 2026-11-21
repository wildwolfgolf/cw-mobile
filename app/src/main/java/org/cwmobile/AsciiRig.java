package org.cwmobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

final class AsciiRig implements Rig {
    private static final Pattern KENWOOD_IF = Pattern.compile(
            "[0-9]{11} {5}[+-][0-9]{4}[01]{2}[ 0-9]{3}[01][1-9][012][0-9][01][0-9]{4}");
    private static final Pattern WOLF_IF = Pattern.compile(
            "[0-9]{11}[ 0-9]{4}[+\\- 0-9][0-9]{5}[01]{2}[ 0-9]{3}[01][0-9A-Z][01][0-9][01][ 0-9]{4}");
    private final RigProfile profile;
    private final Wire wire;
    private final Log log;
    private final byte[] buffer = new byte[512];
    private final StringBuilder frame = new StringBuilder();
    private int count, cursor;
    private boolean badFrame;

    AsciiRig(RigProfile profile, Wire wire, Log log) {
        this.profile = profile;
        this.wire = wire;
        this.log = log;
    }

    Wire wire() { return wire; }

    private boolean yaesu() { return profile == RigProfile.FT991A; }
    private boolean wolf() { return profile == RigProfile.WOLF2; }

    @Override public void identify() throws IOException {
        String expected = yaesu() ? "0670" : wolf() ? "019" : "023";
        if (!query("ID", 450).equals(expected)) throw new IOException("Ответ CAT не соответствует " + profile.label + ".");
        if (wolf()) status();
    }

    @Override public long frequency() throws IOException {
        // The Yaesu FT-991A uses A as its receive VFO. Kenwood IF reports the displayed frequency.
        return yaesu() ? decimal(query("FA", 330), 9) : status().frequency;
    }

    @Override public void frequency(long hz) throws IOException {
        profile.validateFrequency(hz);
        int vfo = yaesu() ? 0 : status().vfo;
        if (vfo > 1) throw new IOException("Выберите VFO A или B на трансивере.");
        String target = vfo == 0 ? "FA" : "FB";
        write(target + String.format(Locale.ROOT, yaesu() ? "%09d" : "%011d", hz));
    }

    @Override public String mode() throws IOException {
        if (yaesu()) return fromCode(query("MD0", 280), "C2813754");
        if (wolf()) return fromCode(String.valueOf(status().mode), "37219654");
        String code = query("MD", 270);
        if (code.equals("2") || code.equals("1")) {
            boolean data = flag(query("DA", 250));
            return code.equals("2") ? (data ? "USB-D" : "USB") : (data ? "LSB-D" : "LSB");
        }
        switch (code) {
            case "3": return "CW";
            case "7": return "CWR";
            case "5": return "AM";
            case "4": return "FM";
            case "6": return "FSK";
            case "9": return "FSKR";
            default: throw invalid("MD");
        }
    }

    private String fromCode(String code, String codes) throws IOException {
        if (code.length() != 1) throw invalid("MD");
        int index = codes.indexOf(code.charAt(0));
        return index >= 0 ? profile.modes[index] : "CAT " + code;
    }

    @Override public void mode(String name) throws IOException {
        int index = Arrays.asList(profile.modes).indexOf(name);
        if (index < 0) throw new IOException("Режим отсутствует в профиле.");
        if (yaesu()) write("MD0" + "C2813754".charAt(index));
        else if (wolf()) write("MD" + "37219654".charAt(index));
        else {
            char code = "22113754".charAt(index);
            write("MD" + code);
            if (code != '3' && code != '7') write("DA" + (index == 0 || index == 2 ? "1" : "0"));
        }
    }

    @Override public boolean split() throws IOException {
        return yaesu() ? flag(query("FT", 250)) : status().split;
    }

    @Override public void split(boolean enabled) throws IOException {
        if (yaesu() || wolf()) {
            write("FT" + (enabled ? "1" : "0"));
        } else {
            int vfo = status().vfo;
            if (vfo > 1) throw new IOException("Выберите VFO A или B.");
            write(enabled ? "FT" + (1 - vfo) : "FR" + vfo);
        }
        if (split() != enabled) throw new IOException("Трансивер не подтвердил SPLIT.");
    }

    @Override public void vfo(Vfo action) throws IOException {
        if (!profile.supportsVfo(action)) throw new IOException("Профиль не поддерживает эту команду VFO.");
        if (yaesu()) {
            write("SV");
            return;
        }
        int index = action.ordinal();
        write((wolf() ? "VS" : "FR") + index);
        if (wolf() && status().vfo != index) write("FR" + index);
        if (status().vfo != index) throw new IOException("Трансивер не подтвердил VFO.");
    }

    @Override public boolean transmitting() throws IOException {
        if (!yaesu()) return status().tx;
        String answer = query("TX", 270);
        if (!answer.matches("[012]")) throw invalid("TX");
        return !answer.equals("0");
    }

    @Override public void ptt(boolean enabled) throws IOException {
        if (wolf()) throw new IOException("Для Wolf 2 используется линия RTS.");
        write(enabled ? "TX1" : yaesu() ? "TX0" : "RX");
    }

    @Override public void verifyAudioTx() throws IOException {
        Rig.super.verifyAudioTx();
        if (!profile.txMode.equals(mode())) throw new IOException("Для TX выберите " + profile.txMode + ".");
        if (yaesu()) {
            if (!query("EX070", 260).equals("1") || !query("EX072", 260).equals("2")) {
                throw new IOException("На FT-991A установите DATA IN SELECT = REAR и DATA PORT SELECT = USB.");
            }
        } else if (!query("EX0690000", 260).equals("1")) {
            throw new IOException("На TS-590SG установите меню 69 DATA Modulation Line = USB.");
        }
    }

    @Override public int meter(Meter meter, int timeout) throws IOException {
        if (wolf()) {
            if (meter == Meter.S) {
                String value = query("SM2", timeout).trim();
                if (!value.matches("[+-]?[0-9]{1,3}")) throw invalid("SM2");
                return -Math.abs(Integer.parseInt(value));
            }
            String[] commands = {"", "RM4", "RM1", "RM3"};
            return decimal(query(commands[meter.ordinal()], timeout), 4);
        }
        if (yaesu()) {
            if (meter == Meter.S) return decimal(query("SM0", timeout), 3);
            String[] commands = {"", "RM5", "RM6", "RM4"};
            String response = query(commands[meter.ordinal()], timeout);
            // These meters append a second three-digit display field.
            if (!response.matches("[0-9]{3}([0-9]{3})?")) throw invalid("RM");
            return decimal(response.substring(0, 3), 3);
        }
        if (meter == Meter.S || meter == Meter.POWER) return decimal(query("SM0", timeout), 4);
        return decimal(query("RM", meter == Meter.SWR ? "RM1" : "RM3", timeout), 4);
    }

    @Override public String extraTelemetry() throws IOException {
        if (yaesu()) {
            return "VFO B · " + decimal(query("FB", 200), 9) + " Гц";
        }
        Status state = status();
        return "VFO · " + (state.vfo == 0 ? "A" : state.vfo == 1 ? "B" : "Память")
                + "\nRIT · " + (state.rit ? "Вкл." : "Выкл.")
                + "\nXIT · " + (state.xit ? "Вкл." : "Выкл.");
    }

    Status status() throws IOException {
        return parseStatus(query("IF", 310), wolf());
    }

    static Status parseStatus(String value, boolean wolf) throws IOException {
        if (!(wolf ? WOLF_IF : KENWOOD_IF).matcher(value).matches()) throw invalid("IF");
        long frequency = number(value.substring(0, 11), 11);
        if (frequency < 1 || frequency > (wolf ? 2_700_000_000L : 60_000_000L)) throw invalid("IF");
        return new Status(frequency, value.charAt(27), value.charAt(28) - '0',
                value.charAt(26) == '1', value.charAt(30) == '1',
                value.charAt(21) == '1', value.charAt(22) == '1');
    }

    static final class Status {
        final long frequency;
        final char mode;
        final int vfo;
        final boolean tx, split, rit, xit;

        Status(long frequency, char mode, int vfo, boolean tx, boolean split, boolean rit, boolean xit) {
            this.frequency = frequency;
            this.mode = mode;
            this.vfo = vfo;
            this.tx = tx;
            this.split = split;
            this.rit = rit;
            this.xit = xit;
        }
    }

    private String query(String command, int timeout) throws IOException {
        return query(command, command, timeout);
    }

    private String query(String command, String prefix, int timeout) throws IOException {
        // The stream can contain echo, fragmented frames, and unsolicited model status.
        while (cursor < count) accept(buffer[cursor++] & 255);
        count = wire.read(buffer);
        for (int i = 0; i < count; i++) accept(buffer[i] & 255);
        cursor = count;
        frame.setLength(0);
        badFrame = false;
        write(command);
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cursor == count) {
                count = wire.read(buffer);
                cursor = 0;
                if (count < 0) throw new IOException("Порт CAT закрыт.");
            }
            while (cursor < count) {
                String reply = accept(buffer[cursor++] & 255);
                if (reply == null) continue;
                if (reply.equals("?") || reply.equals("E") || reply.equals("O")) {
                    throw new IOException("Трансивер отклонил CAT " + command + ".");
                }
                if (reply.startsWith(prefix) && reply.length() > prefix.length()) {
                    return reply.substring(prefix.length());
                }
            }
        }
        throw new IOException("Нет ответа на CAT " + command + ".");
    }

    private void write(String command) throws IOException {
        byte[] data = (command + ";").getBytes(StandardCharsets.US_ASCII);
        wire.write(data);
        log.frame("→", data);
    }

    private String accept(int value) {
        if (value == ';') {
            String result = !badFrame && frame.length() > 0 ? frame.toString() : null;
            frame.setLength(0);
            badFrame = false;
            if (result != null) log.frame("←", (result + ";").getBytes(StandardCharsets.US_ASCII));
            return result;
        }
        if (value == '\r' || value == '\n') return null;
        if (value < 32 || value > 126 || frame.length() >= 90) badFrame = true;
        if (!badFrame) frame.append((char)value);
        return null;
    }

    private static boolean flag(String value) throws IOException {
        if (!value.equals("0") && !value.equals("1")) throw invalid("флаг");
        return value.equals("1");
    }

    private static int decimal(String value, int digits) throws IOException {
        long result = number(value, digits);
        if (result > Integer.MAX_VALUE) throw invalid("число");
        return (int)result;
    }

    private static long number(String value, int digits) throws IOException {
        if (value.length() != digits) throw invalid("число");
        long result = 0;
        for (int i = 0; i < digits; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') throw invalid("число");
            result = result * 10 + ch - '0';
        }
        return result;
    }

    private static IOException invalid(String command) {
        return new IOException("Неожиданный ответ CAT " + command + ".");
    }
}
