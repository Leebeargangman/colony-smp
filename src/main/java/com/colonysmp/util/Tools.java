package com.colonysmp.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Tool and weapon classification, quality, durability and damage. */
public final class Tools {

    public enum Kind { HOE, AXE, PICKAXE, SHOVEL, SWORD, BOW, CROSSBOW, TRIDENT, MACE, HELMET, CHESTPLATE, LEGGINGS, BOOTS, SHIELD, FISHING_ROD, SHEARS, OTHER }

    private Tools() {}

    public static Kind kind(ItemStack it) {
        return it == null ? Kind.OTHER : kind(it.getType());
    }

    public static Kind kind(Material m) {
        String n = m.name();
        if (n.endsWith("_HOE")) return Kind.HOE;
        if (n.endsWith("_AXE")) return Kind.AXE;
        if (n.endsWith("_PICKAXE")) return Kind.PICKAXE;
        if (n.endsWith("_SHOVEL")) return Kind.SHOVEL;
        if (n.endsWith("_SWORD")) return Kind.SWORD;
        if (n.equals("BOW")) return Kind.BOW;
        if (n.equals("CROSSBOW")) return Kind.CROSSBOW;
        if (n.equals("TRIDENT")) return Kind.TRIDENT;
        if (n.equals("MACE")) return Kind.MACE;
        if (n.equals("SHIELD")) return Kind.SHIELD;
        if (n.equals("FISHING_ROD")) return Kind.FISHING_ROD;
        if (n.equals("SHEARS")) return Kind.SHEARS;
        if (n.endsWith("_HELMET") || n.equals("TURTLE_HELMET")) return Kind.HELMET;
        if (n.endsWith("_CHESTPLATE")) return Kind.CHESTPLATE;
        if (n.endsWith("_LEGGINGS")) return Kind.LEGGINGS;
        if (n.endsWith("_BOOTS")) return Kind.BOOTS;
        return Kind.OTHER;
    }

    public static boolean isArmor(Kind k) {
        return k == Kind.HELMET || k == Kind.CHESTPLATE || k == Kind.LEGGINGS || k == Kind.BOOTS;
    }

    public static boolean isMelee(Kind k) {
        return k == Kind.SWORD || k == Kind.AXE || k == Kind.TRIDENT || k == Kind.MACE;
    }

    public static int tier(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE_")) return 6;
        if (n.startsWith("DIAMOND_")) return 5;
        if (n.startsWith("IRON_")) return 4;
        if (n.startsWith("CHAINMAIL_") || n.equals("TURTLE_HELMET")) return 3;
        if (n.startsWith("STONE_")) return 3;
        if (n.startsWith("GOLDEN_")) return 2;
        if (n.startsWith("WOODEN_") || n.startsWith("LEATHER_")) return 1;
        if (n.equals("TRIDENT") || n.equals("MACE")) return 5;
        if (n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD") || n.equals("FISHING_ROD") || n.equals("SHEARS")) return 3;
        return 0;
    }

    /** Quality used to pick the best item for a job: tier first, then enchantments, then wear. */
    public static double score(ItemStack it) {
        if (it == null || it.getType().isAir()) return -1;
        double s = tier(it.getType()) * 100;
        for (Map.Entry<Enchantment, Integer> e : it.getEnchantments().entrySet()) s += e.getValue() * 6;
        int max = it.getType().getMaxDurability();
        if (max > 0 && it.getItemMeta() instanceof Damageable d) s -= 10.0 * d.getDamage() / max;
        Kind k = kind(it);
        if (isMelee(k)) s = meleeDamage(it) * (k == Kind.SWORD ? 1.6 : 0.95) * 10 + s * 0.01;
        return s;
    }

    /** Base melee damage when an NPC hits with this (2 for bare hands). */
    public static double meleeDamage(ItemStack it) {
        if (it == null || it.getType().isAir()) return 2;
        Material m = it.getType();
        int t = tier(m);
        double base;
        switch (kind(m)) {
            case SWORD -> base = switch (t) { case 6 -> 8; case 5 -> 7; case 4 -> 6; case 3 -> 5; default -> 4; };
            case AXE -> base = switch (t) { case 6 -> 10; case 5, 4, 3 -> 9; default -> 7; };
            case TRIDENT -> base = 9;
            case MACE -> base = 7;
            case PICKAXE -> base = 1 + t;
            case SHOVEL -> base = 1.5 + t * 0.8;
            case HOE -> base = 1;
            default -> base = 1;
        }
        int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) base += 0.5 * sharp + 0.5;
        return base;
    }

    /**
     * Wears an item by one use, respecting Unbreaking. Returns true when the item broke
     * (the caller must then drop its reference to it).
     */
    public static boolean wear(ItemStack it, int amount) {
        if (it == null || it.getType().isAir()) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        ItemMeta meta = it.getItemMeta();
        if (!(meta instanceof Damageable d) || meta.isUnbreakable()) return false;
        int unbreaking = it.getEnchantmentLevel(Enchantment.UNBREAKING);
        int dealt = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) continue;
            dealt++;
        }
        if (dealt == 0) return false;
        d.setDamage(d.getDamage() + dealt);
        it.setItemMeta(meta);
        return d.getDamage() >= max;
    }

    public static double wornFraction(ItemStack it) {
        if (it == null) return 0;
        int max = it.getType().getMaxDurability();
        if (max <= 0 || !(it.getItemMeta() instanceof Damageable d)) return 0;
        return (double) d.getDamage() / max;
    }

    /** Speed multiplier for work with this tool tier (bare hands 0.4). */
    public static double speed(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0.4;
        int t = tier(it.getType());
        double s = switch (t) { case 6 -> 1.6; case 5 -> 1.45; case 4 -> 1.25; case 3 -> 1.05; case 2 -> 1.3; default -> 0.85; };
        int eff = it.getEnchantmentLevel(Enchantment.EFFICIENCY);
        return s + eff * 0.1;
    }
}
