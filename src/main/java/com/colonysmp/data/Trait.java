package com.colonysmp.data;

import java.util.concurrent.ThreadLocalRandom;

/** A personal quirk. Travelers advertise theirs, which makes some worth recruiting (or capturing). */
public enum Trait {
    ORDINARY("Ordinary", "No special talents."),
    HARDWORKING("Hardworking", "+20% work speed."),
    LAZY("Lazy", "-20% work speed."),
    FRUGAL("Frugal", "Eats 25% less."),
    GLUTTON("Glutton", "Eats 50% more."),
    GREEN_THUMB("Green Thumb", "+35% farming speed."),
    WOODSMAN("Woodsman", "+35% logging speed."),
    PROSPECTOR("Prospector", "+35% mining speed."),
    BRAVE("Brave", "+30% combat damage."),
    STUBBORN("Stubborn", "+25 Resistance when imprisoned."),
    DOCILE("Docile", "-25 Resistance when imprisoned.");

    public final String display, description;

    Trait(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public double work(Job job) {
        return switch (this) {
            case HARDWORKING -> 1.2;
            case LAZY -> 0.8;
            case GREEN_THUMB -> job == Job.FARMER ? 1.35 : 1;
            case WOODSMAN -> job == Job.LUMBERJACK || job == Job.BUILDER ? 1.35 : 1;
            case PROSPECTOR -> job == Job.MINER ? 1.35 : 1;
            default -> 1;
        };
    }

    public double food() {
        return switch (this) {
            case FRUGAL -> 0.75;
            case GLUTTON -> 1.5;
            default -> 1;
        };
    }

    public double combat() {
        return this == BRAVE ? 1.3 : 1;
    }

    public int resistance() {
        return switch (this) {
            case STUBBORN -> 25;
            case DOCILE -> -25;
            default -> 0;
        };
    }

    public static Trait random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < 0.35) return ORDINARY;
        Trait[] all = values();
        return all[1 + r.nextInt(all.length - 1)];
    }

    public static Trait parse(String s) {
        try {
            return s == null ? ORDINARY : valueOf(s);
        } catch (IllegalArgumentException e) {
            return ORDINARY;
        }
    }
}
