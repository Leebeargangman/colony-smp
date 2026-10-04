package com.colonysmp.data;

import java.util.Locale;

/** How the commune treats a prisoner. */
public enum Policy {
    INDOCTRINATE("Indoctrination", "A Warden visits daily to feed and educate them until they join as an equal citizen."),
    ENSLAVE("Forced Labour", "Shackled and put to work mining or logging. Eats half rations but must always be guarded.");

    public final String display, description;

    Policy(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public static Policy parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("INDOC") || u.equals("EDUCATE")) return INDOCTRINATE;
        if (u.startsWith("ENSLAV") || u.equals("LABOUR") || u.equals("LABOR") || u.equals("SLAVE")) return ENSLAVE;
        return null;
    }
}
