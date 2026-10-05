package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Farmers harvest ripe crops on registered farms, re-till tiles that turned back to dirt, and sow empty
 * farmland with seeds they carry from the State Chest (keeping some of each harvest's seeds to replant).
 */
public final class Farming {

    private record Plan(long at, List<BlockPos> jobs) {}

    private static final Material[] SEEDS = {Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS};
    private static final Material[] CROPS = {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Plan> plans = new HashMap<>();

    Farming(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    static boolean seed(ItemStack it) {
        Material t = it.getType();
        return (t == Material.WHEAT_SEEDS || t == Material.CARROT || t == Material.POTATO || t == Material.BEETROOT_SEEDS) && Storage.plain(it);
    }

    static boolean wart(ItemStack it) {
        return it.getType() == Material.NETHER_WART && Storage.plain(it);
    }

    void think(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        List<Building> farms = col.buildings(BuildingType.FARM);
        if (farms.isEmpty()) {
            m.brain().idle(n, col, now, "No farm registered (use the Blueprint Book)");
            return;
        }
        if (!m.ensureTool(n, col, Tools.Kind.HOE, now)) return;
        Set<BlockPos> claimed = m.claimed.computeIfAbsent(col.id, k -> new HashSet<>());
        if (n.target != null && !actionable(w, n, col, n.target)) {
            claimed.remove(n.target);
            n.target = null;
        }
        if (n.target == null) {
            n.target = pick(n, col, w, farms, now, claimed);
            if (n.target == null) {
                Building f = farms.get((int) (Math.abs(n.c.id.getLeastSignificantBits()) % farms.size()));
                Location c = f.anchor != null ? f.anchor.center(w).add(0, 1, 0) : col.coreLocation();
                m.brain().wander(n, c, 5, now, "Watching the crops grow");
                return;
            }
            claimed.add(n.target);
        }
        Block b = n.target.block(w);
        // sowing needs seeds in the satchel: fetch them from the State Chest first
        if (b.getType().isAir()) {
            boolean soulSand = b.getRelative(BlockFace.DOWN).getType() == Material.SOUL_SAND;
            Predicate<ItemStack> need = soulSand ? Farming::wart : Farming::seed;
            if (n.c.carried(need) == 0) {
                NpcManager.Fetch f = m.fetch(n, col, soulSand ? Farming::wart : bestSeed(col), 32, soulSand ? "nether wart" : "seeds");
                if (f == NpcManager.Fetch.FETCHING) return;
                if (f == NpcManager.Fetch.NONE) {
                    claimed.remove(n.target);
                    n.target = null;
                    return;
                }
            }
        }
        Location tl = n.target.center(w);
        if (!n.mover.near(tl, 1.9)) {
            n.mover.moveTo(tl, plugin.settings().walkSpeed, 1.4);
            n.activity = "Walking to the fields";
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, tl);
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c, Job.FARMER);
        n.actionAt = now + m.interval(plugin.settings().farmInterval, eff, n.c.tool);
        if (ripe(b)) harvest(n, col, b);
        else if (tillable(b)) till(n, col, b);
        else if (b.getType().isAir()) plant(n, b);
        claimed.remove(n.target);
        n.target = null;
    }

    /** The seed type there's most of in the chest (keeping a few carrots and potatoes back as food). */
    private Predicate<ItemStack> bestSeed(Colony col) {
        Material best = null;
        int count = 0;
        for (int i = 0; i < SEEDS.length; i++) {
            int c = col.storage.count(SEEDS[i]);
            if (i == 1 || i == 2) c -= 4;
            if (c > count) {
                count = c;
                best = SEEDS[i];
            }
        }
        final Material pick = best;
        return pick == null ? it -> false : it -> it.getType() == pick && Storage.plain(it);
    }

    private BlockPos pick(Npc n, Colony col, World w, List<Building> farms, long now, Set<BlockPos> claimed) {
        Plan p = plans.get(col.id);
        if (p == null || now - p.at > 60) {
            p = new Plan(now, scan(w, col, farms));
            plans.put(col.id, p);
        }
        Location here = n.body.getLocation();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Iterator<BlockPos> it = p.jobs.iterator();
        while (it.hasNext()) {
            BlockPos t = it.next();
            if (claimed.contains(t)) continue;
            if (!actionable(w, n, col, t)) {
                it.remove();
                continue;
            }
            double d = t.distSq(here);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    private List<BlockPos> scan(World w, Colony col, List<Building> farms) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (Building f : farms) {
            for (BlockPos soil : f.tiles) {
                if (!soil.loaded(w)) continue;
                Block sb = soil.block(w);
                if (tillable(sb)) {
                    if (seen.add(soil)) out.add(soil);
                    continue;
                }
                BlockPos up = soil.add(0, 1, 0);
                Block a = up.block(w);
                if (ripe(a) || a.getType().isAir()) {
                    if (seen.add(up)) out.add(up);
                } else if (a.getType() == Material.ATTACHED_MELON_STEM || a.getType() == Material.ATTACHED_PUMPKIN_STEM) {
                    if (a.getBlockData() instanceof Directional d) {
                        BlockPos fruit = up.relative(d.getFacing());
                        if (ripe(fruit.block(w)) && seen.add(fruit)) out.add(fruit);
                    }
                }
            }
        }
        return out;
    }

    private boolean actionable(World w, Npc n, Colony col, BlockPos t) {
        if (!t.loaded(w)) return false;
        Block b = t.block(w);
        if (ripe(b)) return true;
        if (tillable(b)) return true;
        if (!b.getType().isAir()) return false;
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        if (soil == Material.FARMLAND) return n.c.carried(Farming::seed) > 0 || col.storage.has(Farming::seed);
        if (soil == Material.SOUL_SAND) return n.c.carried(Farming::wart) > 0 || col.storage.has(Farming::wart);
        return false;
    }

    /** A farm tile that went back to dirt (dug, trampled before the colony existed...) with room above it. */
    public static boolean tillable(Block b) {
        Material m = b.getType();
        if (m != Material.DIRT && m != Material.GRASS_BLOCK && m != Material.DIRT_PATH) return false;
        Block up = b.getRelative(BlockFace.UP);
        return up.getType().isAir() || up.isReplaceable() && !up.isLiquid();
    }

    static boolean ripe(Block b) {
        Material m = b.getType();
        if (m == Material.MELON || m == Material.PUMPKIN) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block s = b.getRelative(f);
                if ((s.getType() == Material.ATTACHED_MELON_STEM || s.getType() == Material.ATTACHED_PUMPKIN_STEM)
                        && s.getBlockData() instanceof Directional d && d.getFacing() == f.getOppositeFace()) return true;
            }
            return false;
        }
        if (m != Material.WHEAT && m != Material.CARROTS && m != Material.POTATOES && m != Material.BEETROOTS && m != Material.NETHER_WART) return false;
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    static Material seedOf(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    private void harvest(Npc n, Colony col, Block b) {
        World w = b.getWorld();
        Material type = b.getType();
        List<ItemStack> drops = new ArrayList<>(b.getDrops(n.c.tool));
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, type);
        if (type == Material.MELON || type == Material.PUMPKIN) {
            b.setType(Material.AIR);
        } else {
            Material seed = seedOf(type);
            boolean replant = seed != null && (takeOne(drops, seed) || n.c.useCarried(it -> it.getType() == seed, 1) == 1);
            if (replant && b.getBlockData() instanceof Ageable a) {
                a.setAge(0);
                b.setBlockData(a);
            } else {
                b.setType(Material.AIR);
            }
            // keep a pocketful of the seeds for sowing empty tiles; the rest goes to the State
            if (seed != null) {
                for (ItemStack d : drops) {
                    if (d.getType() != seed || n.c.carried(it -> it.getType() == seed) >= 16) continue;
                    int keep = Math.min(d.getAmount(), 16 - n.c.carried(it -> it.getType() == seed));
                    ItemStack k = d.clone();
                    k.setAmount(keep);
                    ItemStack left = n.c.stow(k);
                    int kept = keep - (left == null ? 0 : left.getAmount());
                    d.setAmount(d.getAmount() - kept);
                }
                drops.removeIf(d -> d.getAmount() <= 0);
            }
        }
        m.deposit(col, drops);
        m.wearTool(n, col);
        n.activity = "Harvesting " + Text.nice(type.name());
    }

    private static boolean takeOne(List<ItemStack> drops, Material m) {
        for (ItemStack it : drops) {
            if (it.getType() == m && it.getAmount() > 0) {
                it.setAmount(it.getAmount() - 1);
                if (it.getAmount() <= 0) drops.remove(it);
                return true;
            }
        }
        return false;
    }

    private void till(Npc n, Colony col, Block b) {
        Block up = b.getRelative(BlockFace.UP);
        if (!up.getType().isAir()) up.setType(Material.AIR);
        b.setType(Material.FARMLAND);
        Fx.sound(b.getLocation(), "minecraft:item.hoe.till", 0.8f, 1f);
        m.wearTool(n, col);
        n.activity = "Re-tilling the fields";
    }

    private void plant(Npc n, Block b) {
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        Material crop = null;
        if (soil == Material.SOUL_SAND) {
            if (n.c.useCarried(Farming::wart, 1) == 1) crop = Material.NETHER_WART;
        } else if (soil == Material.FARMLAND) {
            for (int i = 0; i < SEEDS.length && crop == null; i++) {
                final Material s = SEEDS[i];
                if (n.c.useCarried(it -> it.getType() == s && Storage.plain(it), 1) == 1) crop = CROPS[i];
            }
        }
        if (crop == null) return;
        b.setType(crop);
        Fx.sound(b.getLocation(), "minecraft:item.crop.plant", 0.8f, 1f);
        n.activity = "Sowing " + Text.nice(crop.name());
    }
}
