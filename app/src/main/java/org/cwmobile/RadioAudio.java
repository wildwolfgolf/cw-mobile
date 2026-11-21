package org.cwmobile;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

final class RadioAudio {
    private static final int RATE = 48000;
    private static final int MONO = AudioFormat.CHANNEL_OUT_MONO;
    private static final int PCM = AudioFormat.ENCODING_PCM_16BIT;

    interface ReceiveListener {
        int bandwidth();
        boolean muted();
        void update(String text, double db, double wpm, boolean tone);
    }

    interface TransmitListener {
        void phase(String message);
        void meters(boolean rf, Integer power, Integer swr, Integer alc);
    }

    static boolean usb(AudioDeviceInfo device) {
        if (device == null) return false;
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static void receive(AudioDeviceInfo source, AtomicBoolean stop, int pitch, int wpm,
                        boolean russian, boolean adaptive, int threshold, ReceiveListener listener)
            throws IOException {
        AudioRecord record = openInput(source);
        try {
            record.startRecording();
            int ratio = record.getSampleRate() / Morse.RX_RATE;
            short[] block = new short[ratio * 200];
            int bandwidth = listener.bandwidth();
            Morse.Decoder decoder = new Morse.Decoder(pitch, wpm, russian, adaptive, bandwidth);
            int samples = 0, sum = 0;
            boolean wasMuted = false;
            long last = 0;
            while (!stop.get()) {
                int count = record.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (stop.get()) return;
                if (count < 0) throw new IOException("Ошибка аудиовхода Android (" + count + ").");
                AudioDeviceInfo routed = record.getRoutedDevice();
                if (routed == null || routed.getId() != source.getId()) {
                    throw new IOException("Android не направил приём на выбранный аудиовход.");
                }
                if (listener.muted()) {
                    wasMuted = true;
                    continue;
                }
                int next = listener.bandwidth();
                if (wasMuted || next != bandwidth) {
                    bandwidth = next;
                    decoder = new Morse.Decoder(pitch, wpm, russian, adaptive, bandwidth);
                    wasMuted = false;
                    samples = sum = 0;
                }
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    sum += block[i];
                    if (++samples == ratio) {
                        decoder.push(sum / (32768.0 * ratio), threshold, result);
                        samples = sum = 0;
                    }
                }
                long now = SystemClock.elapsedRealtime();
                if (result.length() > 0 || now - last >= 100) {
                    listener.update(result.toString(), decoder.level, 1.2 / decoder.dot, decoder.keyed());
                    last = now;
                }
            }
        } finally {
            record.release();
        }
    }

    private static AudioRecord openInput(AudioDeviceInfo source) throws IOException {
        int channels = AudioFormat.CHANNEL_IN_MONO;
        for (int rate : new int[]{48000, 32000, 16000, 8000}) {
            int minimum = AudioRecord.getMinBufferSize(rate, channels, PCM);
            if (minimum < 0) continue;
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    channels, PCM, Math.max(minimum, rate / 5));
            if (record.getState() == AudioRecord.STATE_INITIALIZED && record.setPreferredDevice(source)) {
                return record;
            }
            record.release();
        }
        throw new IOException("Не удалось открыть выбранный аудиовход.");
    }

    static void transmit(Rig rig, AudioDeviceInfo output, short[] pcm, int lead, AtomicBoolean cancel,
                         TransmitListener listener) throws IOException {
        int min = AudioTrack.getMinBufferSize(RATE, MONO, PCM);
        if (min < 0 || !usb(output)) throw new IOException("Выберите USB-аудиовыход трансивера.");
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(PCM).setSampleRate(RATE)
                        .setChannelMask(MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        boolean attempted = false;
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(output)) {
                throw new IOException("Не удалось открыть USB-аудиовыход.");
            }
            if (cancel.get()) return;
            if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(RATE / 50);
            short[] silence = new short[track.getBufferSizeInFrames()];
            int queued = track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            if (queued <= 0) throw new IOException("USB-аудиовыход не принимает данные.");
            track.play();
            long readyBy = SystemClock.elapsedRealtime() + 700;
            while (!cancel.get() && (!isRoute(track, output) || track.getPlaybackHeadPosition() <= 0)) {
                if (SystemClock.elapsedRealtime() >= readyBy) {
                    throw new IOException("Android не подтвердил USB-аудиовыход трансивера.");
                }
                SystemClock.sleep(10);
            }
            track.pause();
            track.flush();
            if (cancel.get()) return;
            rig.verifyAudioTx();
            if (cancel.get()) return;
            attempted = true;
            rig.ptt(true);
            listener.phase("TX · передача");
            long until = SystemClock.elapsedRealtime() + lead;
            while (!cancel.get() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10);
            if (cancel.get()) return;
            long confirmBy = SystemClock.elapsedRealtime() + 700;
            while (!cancel.get() && !rig.transmitting()) {
                if (SystemClock.elapsedRealtime() >= confirmBy) throw new IOException("Трансивер не подтвердил TX.");
                SystemClock.sleep(30);
            }
            if (cancel.get()) return;

            // Older Android versions can wait for a full buffer on startup.
            int capacity = track.getBufferSizeInFrames();
            if (pcm.length < capacity) pcm = Arrays.copyOf(pcm, capacity);
            int sent = track.write(pcm, 0, capacity, AudioTrack.WRITE_NON_BLOCKING);
            if (sent <= 0) throw new IOException("Ошибка аудиовыхода TX.");
            track.play();
            long start = SystemClock.elapsedRealtime();
            long deadline = start + pcm.length * 1000L / RATE + 1800;
            long nextMeter = start + 250;
            Integer power = null, swr = null, alc = null;
            int meter = 0;
            while (!cancel.get()) {
                long now = SystemClock.elapsedRealtime();
                if (now - start > 110 && !isRoute(track, output)) {
                    throw new IOException("Потерян USB-аудиовыход трансивера.");
                }
                int played = track.getPlaybackHeadPosition();
                if (played >= pcm.length) break;
                if (now >= deadline) throw new IOException("USB-аудиовыход перестал отвечать.");
                if (sent < pcm.length) {
                    int accepted = track.write(pcm, sent, Math.min(pcm.length - sent, RATE / 12),
                            AudioTrack.WRITE_NON_BLOCKING);
                    if (accepted < 0) throw new IOException("Ошибка аудиовыхода TX (" + accepted + ").");
                    sent += accepted;
                }
                if (now >= nextMeter && (sent == pcm.length || sent - played >= RATE / 12)) {
                    switch (meter++ % 3) {
                        case 0: power = meter(rig, Rig.Meter.POWER); break;
                        case 1: swr = power != null && power > 0 ? meter(rig, Rig.Meter.SWR) : null; break;
                        default: alc = meter(rig, Rig.Meter.ALC); break;
                    }
                    listener.meters(power != null && power > 0, power, swr, alc);
                    nextMeter = SystemClock.elapsedRealtime() + 180;
                }
                SystemClock.sleep(8);
            }
        } finally {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                track.flush();
            } finally {
                try {
                    if (attempted) releasePtt(rig);
                } finally {
                    track.release();
                }
            }
        }
    }

    static void releasePtt(Rig rig) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                rig.ptt(false);
                if (!rig.transmitting()) return;
            } catch (IOException ignored) {
                // Retry after a transient USB error.
            }
        }
        throw new IOException("Возврат в RX не подтверждён. Проверьте TX на панели G90.");
    }

    static short[] transmitWave(String text, int wpm, int tone, int gain) {
        return Morse.waveform(text, RATE, wpm, tone, gain);
    }

    static Integer meter(Rig rig, Rig.Meter type) {
        try {
            return rig.meter(type, 100);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isRoute(AudioTrack track, AudioDeviceInfo output) {
        AudioDeviceInfo routed = track.getRoutedDevice();
        return routed != null && routed.getId() == output.getId();
    }
}
