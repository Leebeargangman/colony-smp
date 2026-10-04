package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Blueprint;
import com.colonysmp.colony.MineableBlocks;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Miners dig a staircase down from a registered Mine Entrance to the configured depth, then a branch mine:
 * a main tunnel with side tunnels every four blocks. Ore showing in the walls is dug out as a vein.
 * Everything goes straight into the State Chest.
 */
public final class Mining {

    /** One block to dig, where to stand to dig it, and extras. */
    record Cell(BlockPos pos, BlockPos stand, boolean torch, boolean floor) {}

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, List<Cell>> plans = new HashMap<>();

    Mining(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    public void think(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        Building mine = pick(n, col);
        if (mine == null) {
            boolean any = !col.buildings(BuildingType.MINE).isEmpty();
            m.brain().idle(n, col, now, any ? "All mines exhausted - register a new Mine Entrance" : "No mine registered (use the Blueprint Book)");
            return;
        }
        if (!m.ensureTool(n, col, Tools.Kind.PICKAXE, now)) return;
        if (n.c.tool == null) {
            m.brain().idle(n, col, now, "Needs a pickaxe from the State Chest");
            return;
        }
        // ore showing in the walls first
        while (!n.veins.isEmpty()) {
            BlockPos v = n.veins.get(0);
            Block vb = v.block(w);
            if (!v.loaded(w) || !MineableBlocks.isOre(vb.getType()) || v.distSq(n.body.getLocation()) > 30) {
                n.veins.remove(0);
                continue;
            }
            if (now < n.actionAt) return;
            CitizenBrain.face(n, v.center(w));
            dig(n, col, vb, now);
            n.veins.remove(0);
            vein(w, v, n.veins);
            n.activity = "Mining out an ore vein";
            return;
        }
        List<Cell> plan = plan(col, mine, w);
        int guard = 0;
        while (mine.progress < plan.size() && guard++ < 24) {
            Cell c = plan.get(mine.progress);
            if (!c.pos.loaded(w)) return;
            Block b = c.pos.block(w);
            if (b.getType().isAir() || (!MineableBlocks.mineable(b.getType()) && !b.isLiquid())) {
                mine.progress++;
                continue;
            }
            break;
        }
        if (mine.progress >= plan.size()) {
            mine.exhausted = true;
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<yellow>" + mine.label() + " is exhausted. Register a new Mine Entrance with the Blueprint Book.");
            }
            return;
        }
        Cell cell = plan.get(mine.progress);
        Location st = cell.stand.center(w);
        if (!n.mover.near(st, 1.4)) {
            n.mover.moveTo(st, plugin.settings().walkSpeed, 1.0);
            n.activity = "Walking down the mine";
            return;
        }
        n.mover.stop();
        if (now < n.actionAt) return;
        Block b = cell.pos.block(w);
        CitizenBrain.face(n, cell.pos.center(w));
        if (b.isLiquid()) {
            b.setType(Material.COBBLESTONE);
            n.actionAt = now + 10;
            return;
        }
        dig(n, col, b, now);
        plug(w, cell.pos);
        if (cell.floor) floor(w, cell.pos.add(0, -1, 0));
        if (cell.torch) torch(col, w, cell.pos, mine.facing);
        if (b.getType().isAir()) mine.progress++;
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockPos np = cell.pos.relative(f);
            if (np.loaded(w) && MineableBlocks.isOre(np.block(w).getType()) && !n.veins.contains(np)) n.veins.add(np);
        }
        n.activity = cell.pos.y() < mine.anchor.y() - 3 ? "Digging at Y " + cell.pos.y() : "Digging the mine shaft";
    }

    private void dig(Npc n, Colony col, Block b, long now) {
        World w = b.getWorld();
        List<ItemStack> drops = new ArrayList<>(b.getDrops(n.c.tool));
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getType());
        b.setType(Material.AIR);
        m.deposit(col, drops);
        m.wearTool(n, col);
        double eff = m.efficiency(col, n.c, Job.MINER);
        n.actionAt = now + m.interval(plugin.settings().mineInterval, eff, n.c.tool);
    }

    private static void vein(World w, BlockPos from, List<BlockPos> veins) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockPos np = from.relative(f);
            if (veins.size() < 16 && np.loaded(w) && MineableBlocks.isOre(np.block(w).getType()) && !veins.contains(np)) veins.add(np);
        }
    }

    /** Seals lava and water next to a freshly dug block. */
    private static void plug(World w, BlockPos p) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
            Block nb = p.relative(f).block(w);
            if (nb.isLiquid()) nb.setType(Material.COBBLESTONE);
        }
    }

    private static void floor(World w, BlockPos p) {
        Block b = p.block(w);
        if (b.isPassable() || b.isLiquid()) b.setType(Material.COBBLESTONE);
    }

    private void torch(Colony col, World w, BlockPos head, BlockFace facing) {
        Block at = head.block(w);
        if (!at.getType().isAir()) return;
        BlockFace left = Blueprint.right(facing).getOppositeFace();
        Block wall = at.getRelative(left);
        if (!wall.getType().isOccluding()) return;
        if (col.storage.remove(it -> it.getType() == Material.TORCH && Storage.plain(it), 1) != 1) return;
        var data = Material.WALL_TORCH.createBlockData();
        if (data instanceof Directional d) d.setFacing(left.getOppositeFace());
        at.setBlockData(data, false);
    }

    private Building pick(Npc n, Colony col) {
        List<Building> mines = new ArrayList<>();
        for (Building b : col.buildings(BuildingType.MINE)) if (!b.exhausted && b.anchor != null) mines.add(b);
        if (mines.isEmpty()) return null;
        if (n.jobKey != null) {
            for (Building b : mines) if (("mine:" + b.id).equals(n.jobKey)) return b;
        }
        Building b = mines.get((int) (Math.abs(n.c.id.getMostSignificantBits()) % mines.size()));
        n.jobKey = "mine:" + b.id;
        return b;
    }

    /** The full dig plan of a mine (cached; it only depends on the entrance, its facing and the claim). */
    List<Cell> plan(Colony col, Building mine, World w) {
        String key = col.id + ":" + mine.id + ":" + mine.anchor + ":" + mine.facing;
        List<Cell> cached = plans.get(key);
        if (cached != null) return cached;
        List<Cell> out = new ArrayList<>();
        BlockPos o = mine.anchor;
        BlockFace f = mine.facing;
        BlockFace r = Blueprint.right(f);
        int target = Math.max(w.getMinHeight() + 6, plugin.settings().mineTargetY);
        // staircase: each step one forward and one down, dug three high
        BlockPos prevStand = new BlockPos(o.x() - f.getModX(), o.y() + 1, o.z() - f.getModZ());
        int i = 0;
        BlockPos lastFeet = null;
        while (o.y() - i > target && i < 160) {
            int x = o.x() + f.getModX() * i, z = o.z() + f.getModZ() * i, y = o.y() - i;
            if (!col.region.contains(x, z)) break;
            BlockPos feet = new BlockPos(x, y, z);
            out.add(new Cell(feet.add(0, 2, 0), prevStand, false, false));
            out.add(new Cell(feet.add(0, 1, 0), prevStand, i % 6 == 3, false));
            out.add(new Cell(feet, prevStand, false, true));
            prevStand = feet;
            lastFeet = feet;
            i++;
        }
        if (lastFeet == null) {
            plans.put(key, out);
            return out;
        }
        // branch mine at the bottom
        int len = plugin.settings().mineTunnelLength, branch = plugin.settings().mineBranchLength;
        BlockPos stand = lastFeet;
        for (int j = 1; j <= len; j++) {
            BlockPos feet = lastFeet.add(f.getModX() * j, 0, f.getModZ() * j);
            if (!col.region.contains(feet)) break;
            out.add(new Cell(feet.add(0, 1, 0), stand, j % 8 == 0, false));
            out.add(new Cell(feet, stand, false, true));
            stand = feet;
            if (j % 4 == 0) {
                for (int side : new int[]{1, -1}) {
                    BlockPos bs = feet;
                    for (int k = 1; k <= branch; k++) {
                        BlockPos bf = feet.add(r.getModX() * k * side, 0, r.getModZ() * k * side);
                        if (!col.region.contains(bf)) break;
                        out.add(new Cell(bf.add(0, 1, 0), bs, false, false));
                        out.add(new Cell(bf, bs, false, true));
                        bs = bf;
                    }
                }
            }
        }
        plans.put(key, out);
        return out;
    }
}
