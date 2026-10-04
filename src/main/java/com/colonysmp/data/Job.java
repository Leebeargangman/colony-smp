package com.colonysmp.data;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.Locale;

/** What a citizen does all day. */
public enum Job {
    FARMER("Farmer", Material.WHEAT, "Harvests and replants registered farms."),
    BUILDER("Builder", Material.BRICKS, "Builds blueprints you order; cuts wood when idle."),
    LUMBERJACK("Lumberjack", Material.IRON_AXE, "Fells trees and replants the saplings."),
    MINER("Miner", Material.IRON_PICKAXE, "Digs your registered mines for stone and ore."),
    GUARD("Guard", Material.IRON_SWORD, "Patrols, fights invaders, escorts and watches prisoners."),
    WARDEN("Warden", Material.WRITABLE_BOOK, "Feeds and indoctrinates prisoners; gives speeches."),
    NONE("Unassigned", Material.BARRIER, "Idles around the Town Hall.");

    public final String display;
    public final Material icon;
    public final String description;

    Job(String display, Material icon, String description) {
        this.display = display;
        this.icon = icon;
        this.description = description;
    }

    public Villager.Profession profession() {
        return switch (this) {
            case FARMER -> Villager.Profession.FARMER;
            case BUILDER -> Villager.Profession.MASON;
            case LUMBERJACK -> Villager.Profession.FLETCHER;
            case MINER -> Villager.Profession.TOOLSMITH;
            case GUARD -> Villager.Profession.WEAPONSMITH;
            case WARDEN -> Villager.Profession.CLERIC;
            case NONE -> Villager.Profession.NONE;
        };
    }

    /** Jobs that produce goods (they become militia when mobilized). */
    public boolean worker() {
        return this == FARMER || this == BUILDER || this == LUMBERJACK || this == MINER || this == NONE;
    }

    public static Job parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
