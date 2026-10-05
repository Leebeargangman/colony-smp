package com.colonysmp.store;

import com.colonysmp.util.Food;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The Central State Chest: the single master inventory of a colony. It is virtual (saved in the database)
 * so workers can deposit into it and rations can be drawn from it even when the chest's chunk isn't loaded.
 * Each page is a 54-slot inventory: 45 storage slots and a navigation bar.
 */
public final class Storage {

    public static final int SLOTS = 45;
    public static final int PREV = 45, INFO = 49, NEXT = 53;

    public record Entry(int page, int slot, ItemStack item) {}

    private final String colonyId;
    private Inventory[] pages;

    public Storage(String colonyId, int pageCount) {
        this.colonyId = colonyId;
        pages = new Inventory[0];
        resize(pageCount);
    }

    public int pageCount() {
        return pages.length;
    }

    public Inventory page(int i) {
        return pages[Math.max(0, Math.min(pages.length - 1, i))];
    }

    /** Changes the page count, keeping every item (overflow is returned so it can be dropped or kept). */
    public List<ItemStack> resize(int count) {
        List<ItemStack> keep = new ArrayList<>();
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it != null && !it.getType().isAir()) keep.add(it);
            }
        }
        pages = new Inventory[count];
        for (int i = 0; i < count; i++) {
            StorageHolder h = new StorageHolder(colonyId, i);
            Inventory inv = Bukkit.createInventory(h, 54, Text.mm("<dark_red>☭ Central State Chest <dark_gray>(" + (i + 1) + "/" + count + ")"));
            h.inventory = inv;
            pages[i] = inv;
            nav(i);
        }
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack it : keep) {
            ItemStack left = add(it);
            if (left != null) overflow.add(left);
        }
        return overflow;
    }

    /** Draws the navigation bar of a page. */
    public void nav(int i) {
        Inventory inv = pages[i];
        for (int s = SLOTS; s < 54; s++) inv.setItem(s, navItem(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> ", "filler"));
        if (i > 0) inv.setItem(PREV, navItem(Material.ARROW, "<yellow>← Previous page", "prev"));
        if (i < pages.length - 1) inv.setItem(NEXT, navItem(Material.ARROW, "<yellow>Next page →", "next"));
        inv.setItem(INFO, navItem(Material.PAPER, "<gold>Central State Chest", "info",
                "<gray>Property of the whole commune.",
                "<gray>Workers deliver everything here and",
                "<gray>take tools, food and arms from it.",
                "",
                "<gray>Page <white>" + (i + 1) + "/" + pages.length));
    }

    public void refreshNav() {
        for (int i = 0; i < pages.length; i++) nav(i);
    }

    private static ItemStack navItem(Material m, String name, String id, String... lore) {
        ItemStack it = Items.icon(m, name, lore);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.NAV, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    public static String navId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.NAV, PersistentDataType.STRING);
    }

    // ───────────── item operations (slots 0..44 of each page) ─────────────

    /** Adds an item; returns what didn't fit (or null). */
    public ItemStack add(ItemStack in) {
        if (in == null || in.getType().isAir() || in.getAmount() <= 0) return null;
        ItemStack item = in.clone();
        int max = item.getMaxStackSize();
        // merge into similar stacks first
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS && item.getAmount() > 0; s++) {
                ItemStack cur = inv.getItem(s);
                if (cur == null || cur.getAmount() >= cur.getMaxStackSize() || !cur.isSimilar(item)) continue;
                int move = Math.min(item.getAmount(), cur.getMaxStackSize() - cur.getAmount());
                cur.setAmount(cur.getAmount() + move);
                item.setAmount(item.getAmount() - move);
            }
        }
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS && item.getAmount() > 0; s++) {
                ItemStack cur = inv.getItem(s);
                if (cur != null && !cur.getType().isAir()) continue;
                ItemStack put = item.clone();
                put.setAmount(Math.min(max, item.getAmount()));
                inv.setItem(s, put);
                item.setAmount(item.getAmount() - put.getAmount());
            }
        }
        return item.getAmount() > 0 ? item : null;
    }

    /** Adds every item; returns the leftovers. */
    public List<ItemStack> addAll(Collection<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack it : items) {
            ItemStack l = add(it);
            if (l != null) left.add(l);
        }
        return left;
    }

    public int count(Predicate<ItemStack> filter) {
        int n = 0;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it != null && !it.getType().isAir() && filter.test(it)) n += it.getAmount();
            }
        }
        return n;
    }

    public int count(Material m) {
        return count(it -> it.getType() == m && plain(it));
    }

    /** Removes up to amount matching items; returns how many were removed. */
    public int remove(Predicate<ItemStack> filter, int amount) {
        int left = amount;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS && left > 0; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir() || !filter.test(it)) continue;
                int take = Math.min(left, it.getAmount());
                left -= take;
                if (take >= it.getAmount()) inv.setItem(s, null);
                else it.setAmount(it.getAmount() - take);
            }
        }
        return amount - left;
    }

    /** Removes up to amount matching items and returns them (keeping their data). */
    public List<ItemStack> take(Predicate<ItemStack> filter, int amount) {
        List<ItemStack> out = new ArrayList<>();
        int left = amount;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS && left > 0; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir() || !filter.test(it)) continue;
                int n = Math.min(left, it.getAmount());
                ItemStack got = it.clone();
                got.setAmount(n);
                out.add(got);
                left -= n;
                if (n >= it.getAmount()) inv.setItem(s, null);
                else it.setAmount(it.getAmount() - n);
            }
        }
        return out;
    }

    /** Removes and returns a single item (amount 1) that scores best, or null. */
    public ItemStack takeBest(Predicate<ItemStack> filter, ToDoubleFunction<ItemStack> score) {
        Inventory bestInv = null;
        int bestSlot = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir() || !filter.test(it)) continue;
                double sc = score.applyAsDouble(it);
                if (sc > best) {
                    best = sc;
                    bestInv = inv;
                    bestSlot = s;
                }
            }
        }
        if (bestInv == null) return null;
        ItemStack it = bestInv.getItem(bestSlot);
        ItemStack one = it.clone();
        one.setAmount(1);
        if (it.getAmount() <= 1) bestInv.setItem(bestSlot, null);
        else it.setAmount(it.getAmount() - 1);
        return one;
    }

    /** Best score of a matching item without removing it (NEGATIVE_INFINITY if none). */
    public double bestScore(Predicate<ItemStack> filter, ToDoubleFunction<ItemStack> score) {
        double best = Double.NEGATIVE_INFINITY;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir() || !filter.test(it)) continue;
                best = Math.max(best, score.applyAsDouble(it));
            }
        }
        return best;
    }

    public boolean has(Predicate<ItemStack> filter) {
        return count(filter) > 0;
    }

    public int freeSlots() {
        int n = 0;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir()) n++;
            }
        }
        return n;
    }

    // ───────────── food ─────────────

    public double foodPoints(Set<Material> never) {
        double pts = 0;
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (Food.isFood(it, never)) pts += Food.points(it.getType(), never) * it.getAmount();
            }
        }
        return pts;
    }

    public int foodItems(Set<Material> never) {
        return count(it -> Food.isFood(it, never));
    }

    /**
     * Eats up to the given number of hunger points out of the chest, cheapest items first (so steak is kept
     * for when it's needed). Returns the points actually eaten.
     */
    public double consumeFood(double points, Set<Material> never) {
        if (points <= 0) return 0;
        record Slot(Inventory inv, int slot, double each) {}
        List<Slot> food = new ArrayList<>();
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (Food.isFood(it, never)) food.add(new Slot(inv, s, Food.points(it.getType(), never)));
            }
        }
        food.sort(Comparator.comparingDouble(Slot::each));
        double eaten = 0;
        for (Slot sl : food) {
            if (eaten >= points - 1e-6) break;
            ItemStack it = sl.inv.getItem(sl.slot);
            if (it == null) continue;
            int need = (int) Math.ceil((points - eaten) / sl.each - 1e-9);
            int take = Math.min(need, it.getAmount());
            eaten += take * sl.each;
            if (take >= it.getAmount()) sl.inv.setItem(sl.slot, null);
            else it.setAmount(it.getAmount() - take);
        }
        return Math.min(eaten, points);
    }

    // ───────────── snapshot ─────────────

    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (Inventory inv : pages) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = inv.getItem(s);
                if (it != null && !it.getType().isAir()) out.add(it.clone());
            }
        }
        return out;
    }

    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (int p = 0; p < pages.length; p++) {
            for (int s = 0; s < SLOTS; s++) {
                ItemStack it = pages[p].getItem(s);
                if (it != null && !it.getType().isAir()) out.add(new Entry(p, s, it.clone()));
            }
        }
        return out;
    }

    /** Loads saved items; anything on a page that no longer exists is merged into the remaining pages. */
    public List<ItemStack> load(List<Entry> entries) {
        List<ItemStack> late = new ArrayList<>();
        for (Entry e : entries) {
            if (e.page() < pages.length && e.slot() >= 0 && e.slot() < SLOTS && pages[e.page()].getItem(e.slot()) == null) {
                pages[e.page()].setItem(e.slot(), e.item());
            } else {
                late.add(e.item());
            }
        }
        return addAll(late);
    }

    public void clear() {
        for (Inventory inv : pages) for (int s = 0; s < SLOTS; s++) inv.setItem(s, null);
    }

    public static boolean plain(ItemStack it) {
        if (!it.hasItemMeta()) return true;
        ItemMeta m = it.getItemMeta();
        return !m.hasDisplayName() && Items.id(it) == null;
    }
}
