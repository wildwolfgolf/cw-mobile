package org.cwmobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProtocolChecks {
    private static final Rig.Log QUIET = (direction, bytes) -> {};

    public static void main(String[] args) throws Exception {
        testDecoder();
        testCiv(RigProfile.G90);
        testCiv(RigProfile.IC7300);
        testYaesu();
        testKenwood();
        testWolf();
        System.out.println("Passed: 50 decoder cases and 5 CAT profiles, including TX preflight and Wolf 2 line cleanup.");
    }

    private static void testDecoder() {
        for (boolean russian : new boolean[]{false, true}) {
            String phrase = russian ? "ВНИМАНИЕ ТЕСТ 73" : "CQ CQ DE TEST 5NN";
            for (int filter : new int[]{0, 100, 200, 300, 500}) {
                for (int wpm : new int[]{10, 18, 26, 34, 40}) {
                    short[] signal = Morse.waveform(phrase, 8000, wpm, 700, 28);
                    Morse.Decoder decoder = new Morse.Decoder(700, wpm, russian, true, filter);
                    StringBuilder output = new StringBuilder();
                    Random noise = new Random(42);
                    for (int i = 0; i < signal.length + 4000; i++) {
                        decoder.push((i < signal.length ? signal[i] / 32768.0 : 0) +
                                noise.nextGaussian() * .025, -34, output);
                    }
                    equal(phrase, output.toString().trim());
                }
            }
        }
        equal("G90 DE TEST 599", Morse.expand("{OTHER} DE {CALL} {RST}", " test ", "g90", "599"));
    }

    private static void testCiv(RigProfile profile) throws Exception {
        DemoCiv wire = new DemoCiv(profile.address);
        Rig rig = profile.create(profile.address, wire, QUIET);
        rig.identify();
        equal(7_030_000L, rig.frequency());
        equal(profile.txMode, rig.mode());
        rig.frequency(14_060_000);
        equal(14_060_000L, rig.frequency());
        rig.mode("CW");
        equal("CW", rig.mode());
        rejects(rig::verifyAudioTx);
        rig.mode(profile.txMode);
        rig.split(true);
        rejects(rig::verifyAudioTx);
        rig.split(false);
        if (profile == RigProfile.IC7300) {
            wire.source = 0;
            rejects(rig::verifyAudioTx);
            wire.source = 3;
        }
        rig.verifyAudioTx();
        rig.vfo(Rig.Vfo.SWAP);
        rig.ptt(true);
        equal(true, rig.transmitting());
        rig.ptt(false);
        equal(false, rig.transmitting());
        equal(128, rig.meter(Rig.Meter.S, 120));
    }

    private static void testYaesu() throws Exception {
        DemoAscii wire = new DemoAscii(RigProfile.FT991A);
        Rig rig = RigProfile.FT991A.create(0, wire, QUIET);
        rig.identify();
        equal(7_030_000L, rig.frequency());
        rig.frequency(144_050_000L);
        equal(144_050_000L, rig.frequency());
        equal("DATA-USB", rig.mode());
        rig.mode("CW");
        equal("CW", rig.mode());
        rig.mode("DATA-USB");
        wire.usb = false;
        rejects(rig::verifyAudioTx);
        wire.usb = true;
        rig.split(true);
        rejects(rig::verifyAudioTx);
        rig.split(false);
        rig.verifyAudioTx();
        rig.vfo(Rig.Vfo.SWAP);
        rig.ptt(true);
        equal("TX1", wire.lastWrite);
        equal(true, rig.transmitting());
        rig.ptt(false);
        equal("TX0", wire.lastWrite);
        equal(false, rig.transmitting());
        equal(46, rig.meter(Rig.Meter.SWR, 120));
    }

    private static void testKenwood() throws Exception {
        DemoAscii wire = new DemoAscii(RigProfile.TS590SG);
        Rig rig = RigProfile.TS590SG.create(0, wire, QUIET);
        rig.identify();
        Rig wrong = RigProfile.FT991A.create(0, wire, QUIET);
        rejects(wrong::identify);
        equal("USB-D", rig.mode());
        rig.mode("LSB");
        equal("LSB", rig.mode());
        rig.mode("USB-D");
        rig.vfo(Rig.Vfo.B);
        rig.frequency(21_060_000L);
        equal("FB00021060000", wire.lastWrite);
        equal(21_060_000L, rig.frequency());
        rig.split(true);
        equal(true, rig.split());
        rig.split(false);
        equal(false, rig.split());
        wire.usb = false;
        rejects(rig::verifyAudioTx);
        wire.usb = true;
        rig.verifyAudioTx();
        rig.ptt(true);
        equal("TX1", wire.lastWrite);
        equal(true, rig.transmitting());
        rig.ptt(false);
        equal("RX", wire.lastWrite);
        equal(false, rig.transmitting());
        equal(10, rig.meter(Rig.Meter.SWR, 120));
    }

    private static void testWolf() throws Exception {
        DemoAscii wire = new DemoAscii(RigProfile.WOLF2);
        AsciiRig rig = (AsciiRig)RigProfile.WOLF2.create(0, wire, QUIET);
        rig.identify();
        rig.mode("CW");
        rig.frequency(2_450_000_000L);
        equal(2_450_000_000L, rig.frequency());
        rig.split(true);
        rejects(() -> WolfKeyer.transmit(rig, "E", 40, 0, new AtomicBoolean(), () -> {}));
        equal(false, wire.tx);
        rig.split(false);
        wire.xit = true;
        rejects(() -> WolfKeyer.transmit(rig, "E", 40, 0, new AtomicBoolean(), () -> {}));
        wire.xit = false;
        WolfKeyer.transmit(rig, "E", 40, 0, new AtomicBoolean(), () -> equal(true, wire.tx));
        equal(1, wire.marks);
        equal(false, wire.tx);
        equal(false, wire.key);
        AtomicBoolean cancel = new AtomicBoolean();
        WolfKeyer.transmit(rig, "E", 40, 0, cancel, () -> cancel.set(true));
        equal(1, wire.marks);
        equal(false, wire.tx);
        wire.failKey = true;
        rejects(() -> WolfKeyer.transmit(rig, "E", 40, 0, new AtomicBoolean(), () -> {}));
        equal(false, wire.tx);
        equal(false, wire.key);
        rejects(() -> AsciiRig.parseStatus("00000000000000", true));
    }

    private interface Checked { void run() throws IOException; }
    private static void rejects(Checked task) throws Exception {
        try { task.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private abstract static class DemoWire implements Rig.Wire {
        private final Queue<Byte> bytes = new ArrayDeque<>();
        void add(int... input) { for (int item : input) bytes.add((byte)item); }
        void add(byte[] input) { for (byte item : input) bytes.add(item); }
        @Override public int read(byte[] out) {
            int count = Math.min(3, bytes.size());
            for (int i = 0; i < count; i++) out[i] = bytes.remove();
            return count;
        }
    }

    private static final class DemoCiv extends DemoWire {
        final int address;
        byte[] frequency = {0, 0, 3, 7, 0};
        int mode = 1, data = 1, ptt, split, source = 3;

        DemoCiv(int address) { this.address = address; }

        @Override public void write(byte[] packet) {
            add(packet);
            add(0xFD, 0xFE, 0xFE, 0xE0, 0x99, 0xFA, 0xFD); // a frame from a different rig
            switch (packet[4] & 255) {
                case 0x03:
                    add(0xFE, 0xFE, 0xE0, address, 3);
                    add(frequency);
                    add(0xFD);
                    return;
                case 0x05: frequency = Arrays.copyOfRange(packet, 5, 10); break;
                case 0x19: reply(0x19, 0, 0x94); return;
                case 0x26:
                    if (packet.length == 7) { reply(0x26, 0, mode, data, 1); return; }
                    mode = packet[6]; data = packet[7]; break;
                case 0x0F:
                    if (packet.length == 6) { reply(0x0F, split); return; }
                    split = packet[5]; break;
                case 0x1C:
                    if (packet.length == 7) { reply(0x1C, 0, ptt); return; }
                    ptt = packet[6]; break;
                case 0x1A: reply(0x1A, 5, 0, 0x67, source); return;
                case 0x15: reply(0x15, 2, 1, 0x28); return;
                case 0x07: equal(0xB0, packet[5] & 255); break;
                default: throw new AssertionError("Unexpected CI-V command");
            }
            reply(0xFB);
        }

        private void reply(int... data) {
            add(0xFE, 0xFE, 0xE0, address);
            add(data);
            add(0xFD);
        }
    }

    private static final class DemoAscii extends DemoWire {
        final RigProfile profile;
        long hz = 7_030_000L;
        char mode;
        int vfo, marks;
        boolean data = true, split, tx, key, xit, failKey, usb = true;
        String lastWrite;

        DemoAscii(RigProfile profile) {
            this.profile = profile;
            mode = yaesu() ? 'C' : wolf() ? '3' : '2';
        }

        private boolean yaesu() { return profile == RigProfile.FT991A; }
        private boolean wolf() { return profile == RigProfile.WOLF2; }

        @Override public boolean canKey() { return wolf(); }
        @Override public void pttLine(boolean high) { tx = high; }
        @Override public void key(boolean high) throws IOException {
            if (high && failKey) throw new IOException("Injected DTR failure");
            equal(true, !high || tx);
            key = high;
            if (high) marks++;
        }

        private String field(int digits) {
            return String.format(Locale.ROOT, "%0" + digits + "d", hz);
        }

        private void reply(String frame) { add((frame + ";").getBytes(StandardCharsets.US_ASCII)); }

        @Override public void write(byte[] bytes) {
            String packet = new String(bytes, StandardCharsets.US_ASCII);
            String cmd = packet.substring(0, packet.length() - 1);
            switch (cmd) {
                case "ID":
                    reply("ID"); // Echo of the query cannot satisfy the reply.
                    reply("ID" + (yaesu() ? "0670" : wolf() ? "019" : "023")); return;
                case "FA": reply("FA" + field(9)); return;
                case "FB": reply("FB" + field(9)); return;
                case "IF":
                    String offset = wolf() ? "0000000000" : "     +0000";
                    reply("IF" + field(11) + offset + (xit ? "01" : "00") + "000" + (tx ? 1 : 0)
                            + mode + vfo + "0" + (split ? 1 : 0) + "0000"); return;
                case "MD": reply("MD" + mode); return;
                case "MD0": reply("MD0" + mode); return;
                case "DA": reply(data ? "DA1" : "DA0"); return;
                case "FT": reply(split ? "FT1" : "FT0"); return;
                case "TX": reply(tx ? "TX1" : "TX0"); return;
                case "RM6": reply("RM6046010"); return;
                case "RM": reply("RM20013"); reply("RM10010"); reply("RM30012"); return;
                case "EX0690000": reply(cmd + (usb ? "1" : "0")); return;
                case "EX070": reply("EX0701"); return;
                case "EX072": reply(usb ? "EX0722" : "EX0721"); return;
            }
            lastWrite = cmd;
            if (cmd.startsWith("FA") || cmd.startsWith("FB")) hz = Long.parseLong(cmd.substring(2));
            else if (cmd.startsWith("MD")) mode = cmd.charAt(cmd.length() - 1);
            else if (cmd.startsWith("DA")) data = cmd.endsWith("1");
            else if (cmd.startsWith("FT")) split = wolf() || yaesu() ? cmd.endsWith("1") : true;
            else if (cmd.startsWith("FR") || cmd.startsWith("VS")) { vfo = cmd.charAt(2) - '0'; split = false; }
            else if (cmd.equals("TX1") || cmd.equals("TX0") || cmd.equals("RX")) tx = cmd.equals("TX1");
            else if (!cmd.equals("SV")) throw new AssertionError("Unexpected ASCII CAT " + cmd);
        }
    }
}
