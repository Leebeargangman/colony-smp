package com.colonysmp.data;

/** A citizen's place in the commune. */
public enum Status {
    /** A free, equal citizen. */
    CITIZEN("Citizen", "<green>"),
    /** Too young to work. */
    CHILD("Child", "<aqua>"),
    /** Bound with rope, waiting to be taken to a cell. */
    CAPTIVE("Captive", "<gold>"),
    /** In a cell, under the Indoctrination policy. */
    PRISONER("Prisoner", "<yellow>"),
    /** Shackled and put to forced labour. */
    SLAVE("Forced Labourer", "<gray>"),
    /** Broke their shackles in an uprising. */
    REBEL("Rebel", "<red>");

    public final String display, color;

    Status(String display, String color) {
        this.display = display;
        this.color = color;
    }

    public boolean free() {
        return this == CITIZEN || this == CHILD;
    }

    public boolean confined() {
        return this == CAPTIVE || this == PRISONER || this == SLAVE;
    }
}
