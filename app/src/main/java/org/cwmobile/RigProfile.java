package org.cwmobile;

enum RigProfile {
    G90("Xiegu G90", 19200, 1, 0x70, 500_000, 30_000_000L, "U-D",
            "U-D USB L-D LSB CW CWR AM NFM"),
    IC7300("Icom IC-7300", 19200, 1, 0x94, 30_000, 74_800_000L, "USB-D",
            "USB-D USB LSB-D LSB CW CWR AM FM"),
    FT991A("Yaesu FT-991A", 38400, 2, 0, 30_000, 470_000_000L, "DATA-USB",
            "DATA-USB USB DATA-LSB LSB CW CWR AM FM"),
    FT891("Yaesu FT-891", 4800, 2, 0, 30_000, 56_000_000L, "DATA-USB",
            "DATA-USB USB DATA-LSB LSB CW CWR AM FM"),
    TS590SG("Kenwood TS-590SG", 115200, 1, 0, 30_000, 60_000_000L, "USB-D",
            "USB-D USB LSB-D LSB CW CWR AM FM"),
    WOLF2("Wolf 2 · TS-2000", 115200, 1, 0, 1, 2_700_000_000L, "CW",
            "CW CWR USB LSB DIG-U DIG-L AM FM"),
    AUDIO("Только аудио (RX)", 0, 1, 0, 0, 0, "", "");

    final String label, txMode;
    final int baud, stopBits, address;
    final long minHz, maxHz;
    final String[] modes;

    RigProfile(String label, int baud, int stopBits, int address, long minHz, long maxHz,
               String txMode, String modes) {
        this.label = label;
        this.baud = baud;
        this.stopBits = stopBits;
        this.address = address;
        this.minHz = minHz;
        this.maxHz = maxHz;
        this.txMode = txMode;
        this.modes = modes.isEmpty() ? new String[]{"—"} : modes.split(" ");
    }

    Rig create(int address, Rig.Wire wire, Rig.Log log) {
        switch (this) {
            case G90:
            case IC7300: return new Civ(this, address, wire, log);
            case FT991A:
            case FT891:
            case TS590SG:
            case WOLF2: return new AsciiRig(this, wire, log);
            default: throw new IllegalStateException("Профиль работает без CAT.");
        }
    }

    boolean supportsVfo(Rig.Vfo vfo) {
        switch (this) {
            case FT991A:
            case FT891: return vfo == Rig.Vfo.SWAP;
            case TS590SG:
            case WOLF2: return vfo != Rig.Vfo.SWAP;
            case AUDIO: return false;
            default: return true;
        }
    }

    boolean isYaesu() { return this == FT991A || this == FT891; }

    void validateFrequency(long hz) {
        if (hz < minHz || hz > maxHz) {
            throw new IllegalArgumentException("Частота для " + label + ": " + minHz + "–" + maxHz + " Гц.");
        }
    }

    @Override public String toString() { return label; }
}
