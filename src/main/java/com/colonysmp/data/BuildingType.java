package com.colonysmp.data;

import org.bukkit.Material;

/** Structures the Blueprint Book knows. */
public enum BuildingType {
    HOUSE("Starter House", Material.RED_BED, "5x5 enclosed room (walls included) with a door and 2 beds."),
    FARM("Farm", Material.WHEAT_SEEDS, "9x9 field of farmland around a water source."),
    TOWER("Guard Tower", Material.STONE_BRICKS, "3x3 tower, 5 blocks tall, with a fenced platform on top."),
    PRISON("Prison Cell", Material.IRON_BARS, "5x5 enclosed room with an iron door and a bed."),
    SCHOOL("School", Material.LECTERN, "7x7 enclosed room with a door and a lectern (bookshelves help)."),
    MINE("Mine Entrance", Material.IRON_PICKAXE, "Where your Miners start digging. Faces the way you look.");

    public final String display;
    public final Material icon;
    public final String description;

    BuildingType(String display, Material icon, String description) {
        this.display = display;
        this.icon = icon;
        this.description = description;
    }

    public BuildingType next() {
        BuildingType[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static BuildingType parse(String s) {
        try {
            return s == null ? null : valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
