package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
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

/** Farmers harvest ripe crops on registered farms and sow empty farmland from the State Chest's seeds. */
public final class Farming {

    private record Plan(long at, List<BlockPos> jobs) {}

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Plan> plans = new HashMap<>();

    Farming(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
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
        if (n.target != null && !actionable(w, col, n.target)) {
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
        Block b = n.target.block(w);
        if (ripe(b)) harvest(n, col, b);
        else if (b.getType().isAir()) plant(n, col, b);
        claimed.remove(n.target);
        n.target = null;
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
            if (!actionable(w, col, t)) {
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
        boolean seeds = hasSeeds(col, false), wart = hasSeeds(col, true);
        Set<BlockPos> seen = new HashSet<>();
        for (Building f : farms) {
            for (BlockPos soil : f.tiles) {
                if (!soil.loaded(w)) continue;
                BlockPos up = soil.add(0, 1, 0);
                Block a = up.block(w);
                Material sm = soil.block(w).getType();
                if (ripe(a)) {
                    if (seen.add(up)) out.add(up);
                } else if (a.getType().isAir()) {
                    if ((sm == Material.FARMLAND && seeds) || (sm == Material.SOUL_SAND && wart)) if (seen.add(up)) out.add(up);
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

    private boolean actionable(World w, Colony col, BlockPos t) {
        if (!t.loaded(w)) return false;
        Block b = t.block(w);
        if (ripe(b)) return true;
        if (!b.getType().isAir()) return false;
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        if (soil == Material.FARMLAND) return hasSeeds(col, false);
        if (soil == Material.SOUL_SAND) return hasSeeds(col, true);
        return false;
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

    private boolean hasSeeds(Colony col, boolean wart) {
        if (wart) return col.storage.count(Material.NETHER_WART) > 0;
        return col.storage.count(Material.WHEAT_SEEDS) > 0 || col.storage.count(Material.CARROT) > 0
                || col.storage.count(Material.POTATO) > 0 || col.storage.count(Material.BEETROOT_SEEDS) > 0;
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
            boolean replant = seed != null && (takeOne(drops, seed) || col.storage.remove(it -> it.getType() == seed && com.colonysmp.store.Storage.plain(it), 1) == 1);
            if (replant && b.getBlockData() instanceof Ageable a) {
                a.setAge(0);
                b.setBlockData(a);
            } else {
                b.setType(Material.AIR);
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

    private void plant(Npc n, Colony col, Block b) {
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        Material item, crop;
        if (soil == Material.SOUL_SAND) {
            item = Material.NETHER_WART;
            crop = Material.NETHER_WART;
        } else if (soil == Material.FARMLAND) {
            Material[] seeds = {Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS};
            Material[] crops = {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};
            int best = -1, count = 0;
            for (int i = 0; i < seeds.length; i++) {
                int c = col.storage.count(seeds[i]);
                // keep a little food back: don't plant the last carrots and potatoes
                if (i == 1 || i == 2) c -= 4;
                if (c > count) {
                    count = c;
                    best = i;
                }
            }
            if (best < 0) return;
            item = seeds[best];
            crop = crops[best];
        } else {
            return;
        }
        final Material it = item;
        if (col.storage.remove(s -> s.getType() == it && com.colonysmp.store.Storage.plain(s), 1) != 1) return;
        b.setType(crop);
        Fx.sound(b.getLocation(), "minecraft:item.crop.plant", 0.8f, 1f);
        n.activity = "Sowing " + Text.nice(crop.name());
    }
}
