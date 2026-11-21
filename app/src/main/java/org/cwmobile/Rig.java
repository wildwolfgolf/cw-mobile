package org.cwmobile;

import java.io.IOException;

interface Rig {
    interface Wire {
        void write(byte[] bytes) throws IOException;
        int read(byte[] buffer) throws IOException;
        default void key(boolean down) throws IOException { throw new IOException("DTR недоступен."); }
        default void pttLine(boolean down) throws IOException { throw new IOException("RTS недоступен."); }
        default boolean canKey() { return false; }
    }

    interface Log { void frame(String direction, byte[] bytes); }
    enum Meter { S, POWER, SWR, ALC }
    enum Vfo { A, B, SWAP }

    void identify() throws IOException;
    long frequency() throws IOException;
    void frequency(long hz) throws IOException;
    String mode() throws IOException;
    void mode(String name) throws IOException;
    boolean split() throws IOException;
    void split(boolean enabled) throws IOException;
    void vfo(Vfo action) throws IOException;
    boolean transmitting() throws IOException;
    void ptt(boolean down) throws IOException;
    int meter(Meter type, int timeout) throws IOException;

    default void verifyAudioTx() throws IOException {
        if (transmitting()) throw new IOException("Трансивер уже передаёт.");
        if (split()) throw new IOException("Выключите SPLIT перед передачей.");
    }

    default String extraTelemetry() throws IOException { return ""; }
}
