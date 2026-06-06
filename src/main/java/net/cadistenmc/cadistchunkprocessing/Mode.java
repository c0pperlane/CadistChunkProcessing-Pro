package net.cadistenmc.cadistchunkprocessing;

import java.util.Locale;

/** The three shipped modes. Numeric knobs live per-mode in config.yml. */
public enum Mode {
    BALANCED("Balanced"),
    MAX_SAVINGS("Max Savings"),
    GENEROUS("Generous");

    public final String display;

    Mode(String display) {
        this.display = display;
    }

    public Mode next() {
        Mode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static Mode fromString(String s, Mode fallback) {
        if (s == null) return fallback;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
