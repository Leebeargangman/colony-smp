package com.colonysmp.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Hunger points of everything the commune can eat. Wheat and hay count too: the State Bakery turns
 * three wheat into a loaf (5 points) when rations are shared out.
 */
public final class Food {

    private static final Map<Material, Double> POINTS = new EnumMap<>(Material.class);

    static {
        put("APPLE", 4);
        put("BAKED_POTATO", 5);
        put("BEETROOT", 1);
        put("BEETROOT_SOUP", 6);
        put("BREAD", 5);
        put("CARROT", 3);
        put("CHORUS_FRUIT", 4);
        put("COOKED_CHICKEN", 6);
        put("COOKED_COD", 5);
        put("COOKED_MUTTON", 6);
        put("COOKED_PORKCHOP", 8);
        put("COOKED_RABBIT", 5);
        put("COOKED_SALMON", 6);
        put("COOKED_BEEF", 8);
        put("COOKIE", 2);
        put("DRIED_KELP", 1);
        put("ENCHANTED_GOLDEN_APPLE", 4);
        put("GOLDEN_APPLE", 4);
        put("GLOW_BERRIES", 2);
        put("GOLDEN_CARROT", 6);
        put("HONEY_BOTTLE", 6);
        put("MELON_SLICE", 2);
        put("MUSHROOM_STEW", 6);
        put("POISONOUS_POTATO", 2);
        put("POTATO", 1);
        put("PUFFERFISH", 1);
        put("PUMPKIN_PIE", 8);
        put("RABBIT_STEW", 10);
        put("BEEF", 3);
        put("CHICKEN", 2);
        put("COD", 2);
        put("MUTTON", 2);
        put("PORKCHOP", 3);
        put("RABBIT", 3);
        put("SALMON", 2);
        put("ROTTEN_FLESH", 4);
        put("SPIDER_EYE", 2);
        put("SUSPICIOUS_STEW", 6);
        put("SWEET_BERRIES", 2);
        put("TROPICAL_FISH", 1);
        put("WHEAT", 5.0 / 3.0);
        put("HAY_BLOCK", 15);
        put("DRIED_KELP_BLOCK", 9);
    }

    private static void put(String name, double pts) {
        Material m = Material.matchMaterial(name);
        if (m != null) POINTS.put(m, pts);
    }

    private Food() {}

    /** Hunger points of one of these, or 0 if the commune won't eat it. */
    public static double points(Material m, Set<Material> never) {
        if (never.contains(m)) return 0;
        Double d = POINTS.get(m);
        return d == null ? 0 : d;
    }

    public static boolean isFood(ItemStack it, Set<Material> never) {
        return it != null && !it.getType().isAir() && plain(it) && points(it.getType(), never) > 0;
    }

    /** No custom name, so named or special items are never eaten. */
    private static boolean plain(ItemStack it) {
        return !it.hasItemMeta() || !it.getItemMeta().hasDisplayName();
    }
}
