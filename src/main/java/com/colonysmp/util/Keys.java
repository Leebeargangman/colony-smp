package com.colonysmp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PersistentDataContainer keys used to tag ColonySMP items and entities. */
public final class Keys {
    public static NamespacedKey ITEM;      // string: wand | core | book | rope | shackles | war_banner
    public static NamespacedKey COLONY;    // string: colony id (Town Hall Core item)
    public static NamespacedKey CITIZEN;   // string: citizen id (NPC bodies)
    public static NamespacedKey TRAVELER;  // string: traveler id (traveler bodies)
    public static NamespacedKey DOWNED;    // long: epoch ms a downed vanilla mob bleeds out
    public static NamespacedKey NAV;       // string: storage navigation button id
    public static NamespacedKey UNIQUE;    // string: random id so menu icons never stack

    public static NamespacedKey RECIPE_WAND, RECIPE_ROPE, RECIPE_SHACKLES, RECIPE_BANNER, RECIPE_BOOK;

    private Keys() {}

    public static void init(Plugin plugin) {
        ITEM = new NamespacedKey(plugin, "item");
        COLONY = new NamespacedKey(plugin, "colony");
        CITIZEN = new NamespacedKey(plugin, "citizen");
        TRAVELER = new NamespacedKey(plugin, "traveler");
        DOWNED = new NamespacedKey(plugin, "downed");
        NAV = new NamespacedKey(plugin, "nav");
        UNIQUE = new NamespacedKey(plugin, "unique");
        RECIPE_WAND = new NamespacedKey(plugin, "colony_wand");
        RECIPE_ROPE = new NamespacedKey(plugin, "rope");
        RECIPE_SHACKLES = new NamespacedKey(plugin, "iron_shackles");
        RECIPE_BANNER = new NamespacedKey(plugin, "war_banner");
        RECIPE_BOOK = new NamespacedKey(plugin, "blueprint_book");
    }
}
