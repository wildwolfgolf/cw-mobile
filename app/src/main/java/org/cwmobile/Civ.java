package org.cwmobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

final class Civ implements Rig {
    private static final int[] MODE_CODES = {1, 1, 0, 0, 3, 7, 2, 5};
    private final RigProfile profile;
    private final int address;
    private final Wire wire;
    private final Log log;
    private final byte[] buffer = new byte[512];
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private int sync, cursor, count;

    Civ(RigProfile profile, int address, Wire wire, Log log) {
        this.profile = profile;
        this.address = address;
        this.wire = wire;
        this.log = log;
    }

    byte[] query(int timeout, int... command) throws IOException {
        return exchange(timeout, command, 1, command);
    }

    private void set(int... command) throws IOException {
        exchange(450, new int[]{0xFB}, 0, command);
    }

    private byte[] exchange(int timeout, int[] prefix, int size, int[] command) throws IOException {
        // Finish consuming an earlier echo or late reply before issuing a command.
        cursor = count;
        wire.read(buffer);
        pending.reset();
        sync = 0;
        byte[] packet = new byte[5 + command.length];
        packet[0] = packet[1] = (byte)0xFE;
        packet[2] = (byte)address;
        packet[3] = (byte)0xE0;
        for (int i = 0; i < command.length; i++) packet[4 + i] = (byte)command[i];
        packet[packet.length - 1] = (byte)0xFD;
        wire.write(packet);
        log.frame("→", packet);
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cursor == count) {
                count = wire.read(buffer);
                cursor = 0;
                if (count < 0) throw new IOException("Порт CAT закрыт.");
            }
            while (cursor < count) {
                byte[] reply = accept(buffer[cursor++] & 255);
                if (reply == null || (reply[2] & 255) != 0xE0 ||
                        (reply[3] & 255) != address) continue;
                if ((reply[4] & 255) == 0xFA && reply.length == 6) {
                    throw new IOException("Трансивер отклонил команду CAT.");
                }
                int payloadSize = reply.length - 5;
                if (payloadSize < prefix.length + size) continue;
                boolean matches = true;
                for (int i = 0; i < prefix.length; i++) {
                    if ((reply[4 + i] & 255) != prefix[i]) matches = false;
                }
                if (matches) return Arrays.copyOfRange(reply, 4 + prefix.length, reply.length - 1);
            }
        }
        throw new IOException("Нет ответа на CI-V " + hex(Arrays.copyOfRange(packet, 4, packet.length - 1)));
    }

    private byte[] accept(int value) {
        if (value == 0xFE) {
            if (++sync >= 2) {
                pending.reset();
                pending.write(0xFE);
                pending.write(0xFE);
            }
            return null;
        }
        sync = 0;
        if (pending.size() == 0) return null;
        pending.write(value);
        if (value == 0xFD || pending.size() > 128) {
            byte[] result = pending.toByteArray();
            pending.reset();
            if (value != 0xFD || result.length < 6) return null;
            if ((result[3] & 255) != 0xE0) log.frame("←", result);
            return result;
        }
        return null;
    }

    @Override public void identify() throws IOException {
        if (profile == RigProfile.IC7300) {
            byte[] id;
            try { id = query(350, 0x19, 0x00); }
            catch (IOException unsupported) { id = query(350, 0x19); }
            if (id.length == 2 && id[0] == 0) id = Arrays.copyOfRange(id, 1, 2);
            if (id.length != 1 || (id[0] & 255) != 0x94) throw new IOException("Ответ CI-V не соответствует IC-7300.");
        }
        // Older G90 firmware can reply to 03 without implementing a model ID.
    }

    @Override public long frequency() throws IOException { return parseFrequency(query(320, 0x03)); }

    @Override public void frequency(long hz) throws IOException {
        profile.validateFrequency(hz);
        int[] command = {0x05, 0, 0, 0, 0, 0};
        for (int i = 1; i < command.length; i++) {
            command[i] = (int)((hz / 10 % 10) * 16 + hz % 10);
            hz /= 100;
        }
        set(command);
    }

    static long parseFrequency(byte[] data) throws IOException {
        if (data.length != 5) throw new IOException("Некорректный ответ о частоте.");
        long hz = 0;
        for (int i = 4; i >= 0; i--) hz = hz * 100 + bcd(data[i]);
        return hz;
    }

    @Override public boolean transmitting() throws IOException { return flag(query(250, 0x1C, 0x00)); }
    @Override public boolean split() throws IOException { return flag(query(250, 0x0F)); }
    @Override public void split(boolean enabled) throws IOException { set(0x0F, enabled ? 1 : 0); }
    @Override public void vfo(Vfo vfo) throws IOException {
        set(0x07, vfo == Vfo.SWAP ? 0xB0 : vfo.ordinal());
    }
    @Override public void ptt(boolean enabled) throws IOException { set(0x1C, 0x00, enabled ? 1 : 0); }

    static boolean flag(byte[] bytes) throws IOException {
        if (bytes.length != 1 || (bytes[0] != 0 && bytes[0] != 1)) throw new IOException("Некорректный ответ CI-V.");
        return bytes[0] == 1;
    }

    @Override public String mode() throws IOException {
        try {
            return describeMode(query(180, 0x26, 0x00));
        } catch (IOException e) {
            byte[] basic = query(240, 0x04);
            String mode = modeName(basic[0] & 255, false);
            if (mode.equals("USB") || mode.equals("LSB")) {
                try {
                    byte[] data = query(150, 0x1A, 0x06);
                    if (data.length > 2 || (data[0] != 0 && data[0] != 1)) throw new IOException("Флаг DATA неизвестен.");
                    if (data[0] == 1) return profile.modes[mode.equals("USB") ? 0 : 2];
                } catch (IOException ignored) {
                    return mode + " · DATA ?";
                }
            }
            return mode;
        }
    }

    String describeMode(byte[] data) throws IOException {
        if (data.length != 3 || (data[1] != 0 && data[1] != 1)) {
            throw new IOException("Некорректный ответ о режиме DATA.");
        }
        return modeName(data[0] & 255, data[1] != 0);
    }

    private String modeName(int code, boolean data) {
        for (int i = 0; i < profile.modes.length; i++) {
            if (MODE_CODES[i] == code && data == (i == 0 || i == 2)) return profile.modes[i];
        }
        return String.format(Locale.ROOT, "0x%02X", code);
    }

    @Override public void mode(String name) throws IOException {
        int index = Arrays.asList(profile.modes).indexOf(name);
        if (index < 0) throw new IOException("Режим отсутствует в профиле.");
        boolean data = index == 0 || index == 2;
        int group;
        try {
            byte[] current = query(210, 0x26, 0x00);
            describeMode(current);
            group = Math.max(1, Math.min(3, current[2]));
        } catch (IOException e) {
            if (data) throw new IOException("Выберите нужный режим DATA на панели трансивера.");
            set(0x06, MODE_CODES[index], 1);
            return;
        }
        set(0x26, 0x00, MODE_CODES[index], data ? 1 : 0, group);
    }

    @Override public int meter(Meter type, int timeout) throws IOException {
        final int[] command = {0x02, 0x11, 0x12, 0x13};
        return level(query(timeout, 0x15, command[type.ordinal()]));
    }

    @Override public void verifyAudioTx() throws IOException {
        Rig.super.verifyAudioTx();
        if (!profile.txMode.equals(mode())) throw new IOException("Для TX выберите " + profile.txMode + ".");
        if (profile == RigProfile.IC7300) {
            byte[] source = query(300, 0x1A, 0x05, 0x00, 0x67);
            if (source.length != 1 || source[0] != 3) {
                throw new IOException("На IC-7300 установите DATA MOD = USB.");
            }
        }
    }

    static int level(byte[] field) throws IOException {
        if (field.length < 1 || field.length > 2) throw new IOException("Некорректный уровень.");
        int value = 0;
        for (byte item : field) value = value * 100 + bcd(item);
        if (value > 255) throw new IOException("Уровень вне шкалы.");
        return value;
    }

    private static int bcd(byte field) throws IOException {
        int hi = (field & 255) >> 4, lo = field & 15;
        if (hi > 9 || lo > 9) throw new IOException("Некорректный BCD.");
        return hi * 10 + lo;
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(Locale.ROOT, "%02X", item & 255));
        }
        return result.toString();
    }
}
