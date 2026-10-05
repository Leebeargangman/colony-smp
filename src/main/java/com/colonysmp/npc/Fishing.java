package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Fishers stand on the shore of water in the colony with a rod from the State Chest and land fish. */
public final class Fishing {

    /** Where to stand, and the water to cast into. */
    record Spot(BlockPos stand, BlockPos water) {}

    private record Shore(long at, List<Spot> spots) {}

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Shore> shores = new HashMap<>();

    Fishing(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        List<Spot> spots = shore(col, w, now);
        if (spots.isEmpty()) {
            m.brain().idle(n, col, now, "No water to fish in the colony (dig a pond, 3x3 or bigger)");
            return;
        }
        if (!m.ensureTool(n, col, Tools.Kind.FISHING_ROD, now)) return;
        if (n.c.tool == null) {
            m.brain().idle(n, col, now, "Needs a fishing rod in the State Chest");
            return;
        }
        Spot spot = spots.get(Math.floorMod(n.c.id.hashCode(), spots.size()));
        Location stand = spot.stand.center(w).subtract(0, 0.5, 0);
        if (!n.mover.near(stand, 1.2)) {
            n.mover.moveTo(stand, plugin.settings().walkSpeed, 0.9);
            n.activity = "Walking to the water";
            return;
        }
        n.mover.stop();
        Location water = spot.water.center(w).add(0, 0.4, 0);
        CitizenBrain.face(n, water);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextInt(3) == 0) w.spawnParticle(Particle.FISHING, water, 2, 0.15, 0, 0.15, 0.01);
        n.activity = "Fishing";
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c, Job.FISHER);
        n.actionAt = now + m.interval(plugin.settings().fishInterval * (0.7 + r.nextDouble() * 0.6), eff, n.c.tool);
        if (n.actionAt - now > 20 * 60 * 10) return;
        ItemStack catchOf = loot(r);
        w.spawnParticle(Particle.SPLASH, water, 12, 0.3, 0.1, 0.3, 0.1);
        Fx.sound(water, "minecraft:entity.fishing_bobber.splash", 0.7f, 1f);
        m.deposit(col, List.of(catchOf));
        m.wearTool(n, col);
        n.activity = "Caught " + (catchOf.getAmount() > 1 ? catchOf.getAmount() + " " : "a ") + Text.nice(catchOf.getType().name()).toLowerCase();
    }

    private static ItemStack loot(ThreadLocalRandom r) {
        double x = r.nextDouble();
        if (x < 0.55) return new ItemStack(Material.COD);
        if (x < 0.80) return new ItemStack(Material.SALMON);
        if (x < 0.83) return new ItemStack(Material.TROPICAL_FISH);
        if (x < 0.85) return new ItemStack(Material.PUFFERFISH);
        if (x < 0.97) {
            Material[] junk = {Material.STICK, Material.STRING, Material.BONE, Material.LEATHER, Material.BOWL, Material.LILY_PAD, Material.INK_SAC, Material.KELP};
            return new ItemStack(junk[r.nextInt(junk.length)]);
        }
        Material[] treasure = {Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL};
        return new ItemStack(treasure[r.nextInt(treasure.length)]);
    }

    /** Shore spots in the claim (water surface next to dry standing ground), refreshed every 5 minutes. */
    private List<Spot> shore(Colony col, World w, long now) {
        Shore s = shores.get(col.id);
        if (s != null && now - s.at < 6000 && now >= s.at) return s.spots;
        List<Spot> out = new ArrayList<>();
        int step = col.region.width() > 64 ? 2 : 1;
        outer:
        for (int x = col.region.minX(); x <= col.region.maxX(); x += step) {
            for (int z = col.region.minZ(); z <= col.region.maxZ(); z += step) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block top = w.getHighestBlockAt(x, z);
                if (top.getType() != Material.WATER) continue;
                // open water: at least two more water blocks around
                int wet = 0;
                for (BlockFace f : SIDES) if (top.getRelative(f).getType() == Material.WATER) wet++;
                if (wet < 2) continue;
                for (BlockFace f : SIDES) {
                    Block land = top.getRelative(f);
                    Block feet = land.getRelative(BlockFace.UP);
                    if (land.getType().isSolid() && feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                            && col.region.contains(land.getX(), land.getZ())) {
                        out.add(new Spot(BlockPos.of(feet), BlockPos.of(top)));
                        if (out.size() >= 24) break outer;
                        break;
                    }
                }
            }
        }
        shores.put(col.id, new Shore(now, out));
        return out;
    }
}
