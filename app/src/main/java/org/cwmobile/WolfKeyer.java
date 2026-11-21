package org.cwmobile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class WolfKeyer {
    static void transmit(AsciiRig rig, String text, int wpm, int lead, AtomicBoolean cancel,
                         Runnable sending) throws IOException {
        List<Integer> segments = Morse.segments(text, wpm);
        Rig.Wire wire = rig.wire();
        if (!wire.canKey()) throw new IOException("USB-порт Wolf 2 не предоставляет DTR и RTS.");
        if (cancel.get()) return;
        AsciiRig.Status before = rig.status();
        if (before.tx || before.split || before.xit || !cw(before)) {
            throw new IOException("Для Wolf 2 выберите CW/CWR, RX, SPLIT выкл. и XIT выкл.");
        }
        if (cancel.get()) return;
        try {
            wire.key(false);
            wire.pttLine(true);
            waitUntil(System.nanoTime() + lead * 1_000_000L, cancel);
            long confirmBy = System.nanoTime() + 1_200_000_000L;
            while (!cancel.get()) {
                AsciiRig.Status state = rig.status();
                if (!cw(state) || state.mode != before.mode || state.split || state.xit
                        || state.frequency != before.frequency || state.vfo != before.vfo) {
                    throw new IOException("Настройки Wolf 2 изменились при входе в TX.");
                }
                if (state.tx) break;
                if (System.nanoTime() >= confirmBy) {
                    throw new IOException("Wolf 2 остался в RX. Проверьте RTS = PTT.");
                }
                waitUntil(System.nanoTime() + 25_000_000L, cancel);
            }
            if (cancel.get()) return;
            sending.run();
            long unit = Math.round(1_200_000_000.0 / wpm);
            for (int segment : segments) {
                if (cancel.get()) return;
                long edge = System.nanoTime();
                wire.key(segment > 0);
                waitUntil(edge + unit * Math.abs(segment), cancel);
            }
        } finally {
            release(rig, wire);
        }
    }

    private static boolean cw(AsciiRig.Status status) { return status.mode == '3' || status.mode == '7'; }

    private static void release(AsciiRig rig, Rig.Wire wire) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try { wire.key(false); }
                finally { wire.pttLine(false); }
                if (!rig.transmitting()) return;
            } catch (IOException error) {
                last = error;
            }
            waitUntil(System.nanoTime() + 70_000_000L, new AtomicBoolean());
        }
        throw new IOException("Возврат Wolf 2 в RX не подтверждён. Проверьте панель трансивера.", last);
    }

    private static void waitUntil(long deadline, AtomicBoolean cancel) throws IOException {
        while (!cancel.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                Thread.sleep(Math.min(9, Math.max(1, remaining / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Ключевание прервано.", e);
            }
        }
    }
}
