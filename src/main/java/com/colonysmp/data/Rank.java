package com.colonysmp.data;

/** A player's rank in a colony. */
public enum Rank {
    FOUNDER("Chairman"), OFFICER("Commissar"), MEMBER("Comrade");

    public final String display;

    Rank(String display) {
        this.display = display;
    }

    /** Can run the colony: jobs, policies, war, mobilization. */
    public boolean leads() {
        return this == FOUNDER || this == OFFICER;
    }
}
