package com.colonysmp.data;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.Locale;

/** What a citizen does all day. */
public enum Job {
    FARMER("Farmer", Material.WHEAT, 0, "Harvests, replants and re-tills registered farms."),
    BUILDER("Builder", Material.BRICKS, 0, "Builds blueprints you order; cuts wood when idle."),
    LUMBERJACK("Lumberjack", Material.IRON_AXE, 0, "Fells trees and replants the saplings."),
    MINER("Miner", Material.IRON_PICKAXE, 0, "Digs your registered mines for stone and ore."),
    FISHER("Fisher", Material.FISHING_ROD, 0, "Fishes from the shore of water in the colony."),
    HERDER("Herder", Material.SHEARS, 0, "Breeds, shears and culls the colony's animals."),
    COOK("Cook", Material.BREAD, 0, "Cooks raw food from the State Chest at a furnace, smoker or campfire."),
    SMITH("Smith", Material.ANVIL, 25, "Smelts ore, makes and repairs tools and armour at an anvil."),
    DOCTOR("Doctor", Material.GLISTERING_MELON_SLICE, 40, "Heals the injured and revives the downed."),
    TEACHER("Teacher", Material.BOOK, 30, "Teaches children and students at a School."),
    STUDENT("Student", Material.PAPER, 0, "Studies at a School all day to raise their education."),
    GUARD("Guard", Material.IRON_SWORD, 0, "Patrols, fights invaders, escorts and watches prisoners."),
    WARDEN("Warden", Material.WRITABLE_BOOK, 0, "Feeds and indoctrinates prisoners; gives speeches."),
    NONE("Unassigned", Material.BARRIER, 0, "Idles around the Town Hall.");

    public final String display;
    public final Material icon;
    /** Education (0-100) needed to take the job. */
    public final int education;
    public final String description;

    Job(String display, Material icon, int education, String description) {
        this.display = display;
        this.icon = icon;
        this.education = education;
        this.description = description;
    }

    public Villager.Profession profession() {
        return switch (this) {
            case FARMER -> Villager.Profession.FARMER;
            case BUILDER -> Villager.Profession.MASON;
            case LUMBERJACK -> Villager.Profession.FLETCHER;
            case MINER -> Villager.Profession.TOOLSMITH;
            case FISHER -> Villager.Profession.FISHERMAN;
            case HERDER -> Villager.Profession.SHEPHERD;
            case COOK -> Villager.Profession.BUTCHER;
            case SMITH -> Villager.Profession.ARMORER;
            case DOCTOR -> Villager.Profession.CLERIC;
            case TEACHER -> Villager.Profession.LIBRARIAN;
            case STUDENT -> Villager.Profession.NONE;
            case GUARD -> Villager.Profession.WEAPONSMITH;
            case WARDEN -> Villager.Profession.CARTOGRAPHER;
            case NONE -> Villager.Profession.NONE;
        };
    }

    /** Jobs that produce goods (they become militia when mobilized). */
    public boolean worker() {
        return switch (this) {
            case FARMER, BUILDER, LUMBERJACK, MINER, FISHER, HERDER, COOK, SMITH, STUDENT, NONE -> true;
            default -> false;
        };
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
