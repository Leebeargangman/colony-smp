package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Blueprint;
import com.colonysmp.colony.BlueprintManager;
import com.colonysmp.data.BuildJob;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Builders construct the blueprints players order with the Blueprint Book, using State Chest materials. */
public final class Construction {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final ColonySMP plugin;
    private final NpcManager m;

    Construction(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Returns false when there's nothing to build (the builder cuts wood instead). */
    boolean think(Npc n, Colony col, long now) {
        if (col.buildQueue.isEmpty()) return false;
        BuildJob job = col.buildQueue.get(0);
        Blueprint bp = Blueprint.of(job.type);
        World w = col.world();
        if (bp == null || w == null) {
            col.buildQueue.remove(0);
            return true;
        }
        if (job.step >= bp.steps.size()) {
            complete(col, job, bp, w);
            return true;
        }
        Blueprint.Step step = bp.steps.get(job.step);
        BlockPos at = bp.pos(job.origin, job.facing, step);
        if (!at.loaded(w)) {
            n.activity = "Waiting for the site to load";
            return true;
        }
        Location target = at.center(w);
        // builders work from outside the footprint so they never wall themselves in
        Location spot = workSpot(job, bp, w, at);
        if (spot != null && !n.mover.near(spot, 2.2)) {
            BlockPos[] bb = bp.bounds(job.origin, job.facing);
            if (insideBounds(bb, n.body.getLocation())) {
                n.body.teleport(spot);
                n.mover.sync();
            } else {
                n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.6);
                n.activity = "Walking to the " + job.type.display + " site";
                return true;
            }
        }
        n.mover.stop();
        CitizenBrain.face(n, target);
        if (now < n.actionAt) return true;
        double eff = m.efficiency(col, n.c, Job.BUILDER);
        n.actionAt = now + (eff <= 0.01 ? 200 : Math.max(4, Math.round(plugin.settings().buildInterval * 20 / eff)));
        work(n, col, job, bp, w, now);
        return true;
    }

    private void work(Npc n, Colony col, BuildJob job, Blueprint bp, World w, long now) {
        // skip everything already in place
        int guard = 0;
        while (job.step < bp.steps.size() && guard++ < 16) {
            Blueprint.Step s = bp.steps.get(job.step);
            Block b = bp.pos(job.origin, job.facing, s).block(w);
            if (!Blueprint.satisfied(s.spec(), b)) break;
            job.step++;
        }
        if (job.step >= bp.steps.size()) return;
        Blueprint.Step s = bp.steps.get(job.step);
        BlockPos p = bp.pos(job.origin, job.facing, s);
        Block b = p.block(w);
        n.activity = "Building a " + job.type.display + " (" + (job.step * 100 / bp.steps.size()) + "%)";
        job.waitingFor = null;

        if (s.spec() == Blueprint.Spec.AIR) {
            if (!untouchable(b)) clear(col, b);
            job.step++;
            return;
        }
        if (s.spec() == Blueprint.Spec.FARMLAND) {
            if (Blueprint.tillable(b.getType())) {
                b.setType(Material.FARMLAND);
                Fx.sound(b.getLocation(), "minecraft:item.hoe.till", 0.8f, 1f);
                job.step++;
                return;
            }
            if (untouchable(b)) {
                job.step++;
                return;
            }
            if (!take(col, List.of(Material.DIRT))) {
                waiting(n, col, job, "dirt");
                return;
            }
            clear(col, b);
            b.setType(Material.FARMLAND);
            job.step++;
            return;
        }
        // something in the way
        if (!b.getType().isAir() && !b.isReplaceable() && !b.isLiquid()) {
            if (untouchable(b)) {
                job.step++;
                return;
            }
            clear(col, b);
            return;
        }
        // walls and floors need ground under them
        if (s.ly() == 0 && needsSupport(s.spec())) {
            Block below = b.getRelative(BlockFace.DOWN);
            if (below.isPassable() || below.isLiquid()) {
                Material f = first(col, Blueprint.candidates(Blueprint.Spec.FOUNDATION));
                if (f == null) {
                    waiting(n, col, job, "foundation blocks (cobblestone or dirt)");
                    return;
                }
                take(col, List.of(f));
                below.setType(f);
                placed(below);
                return;
            }
        }
        if (s.spec() == Blueprint.Spec.WATER) {
            if (col.storage.remove(it -> it.getType() == Material.WATER_BUCKET && Storage.plain(it), 1) != 1) {
                waiting(n, col, job, "a water bucket");
                return;
            }
            m.deposit(col, List.of(new ItemStack(Material.BUCKET)));
            b.setType(Material.WATER);
            Fx.sound(b.getLocation(), "minecraft:item.bucket.empty", 0.8f, 1f);
            job.step++;
            return;
        }
        if (s.spec() != Blueprint.Spec.TORCH && s.spec() != Blueprint.Spec.BUTTON && s.spec() != Blueprint.Spec.LADDER && occupied(b)) {
            n.activity = "Waiting for someone to move out of the way";
            return;
        }
        Material item = first(col, Blueprint.candidates(s.spec()));
        if (item == null) {
            if (s.spec().optional) {
                job.step++;
                return;
            }
            waiting(n, col, job, s.spec().label);
            return;
        }
        switch (s.role()) {
            case BED_FOOT -> {
                Block head = p.relative(job.facing).block(w);
                if (!head.getType().isAir() && !head.isReplaceable()) {
                    if (untouchable(head)) {
                        job.step++;
                        return;
                    }
                    clear(col, head);
                    return;
                }
                take(col, List.of(item));
                head.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), true), false);
                b.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), false), false);
            }
            case DOOR_LOWER -> {
                Block up = b.getRelative(BlockFace.UP);
                if (!up.getType().isAir() && !up.isReplaceable()) {
                    if (untouchable(up)) {
                        job.step++;
                        return;
                    }
                    clear(col, up);
                    return;
                }
                take(col, List.of(item));
                b.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), false), false);
                up.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), true), false);
            }
            default -> {
                BlockData data = Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), false);
                if (data == null) {
                    job.step++;
                    return;
                }
                take(col, List.of(item));
                b.setBlockData(data, s.role() != Blueprint.Role.ATTACHED);
                if (data instanceof MultipleFacing) connect(b);
            }
        }
        placed(b);
        job.step++;
    }

    /** A standing spot two blocks outside the footprint, on the side nearest the block being worked on. */
    private Location workSpot(BuildJob job, Blueprint bp, World w, BlockPos at) {
        BlockPos[] bb = bp.bounds(job.origin, job.facing);
        int y = job.origin.y();
        int cx = Math.max(bb[0].x(), Math.min(bb[1].x(), at.x())), cz = Math.max(bb[0].z(), Math.min(bb[1].z(), at.z()));
        int[][] spots = {{cx, bb[0].z() - 2}, {cx, bb[1].z() + 2}, {bb[0].x() - 2, cz}, {bb[1].x() + 2, cz}};
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int[] sp : spots) {
            Location l = Mover.safeSpot(new Location(w, sp[0] + 0.5, y, sp[1] + 0.5));
            if (l == null || insideBounds(bb, l)) continue;
            double d = at.distSq(l);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    private static boolean insideBounds(BlockPos[] bb, Location l) {
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= bb[0].x() && x <= bb[1].x() && z >= bb[0].z() && z <= bb[1].z() && y >= bb[0].y() - 1 && y <= bb[1].y() + 1;
    }

    /** Someone is standing where the block would go: wait rather than bury them. */
    private static boolean occupied(Block b) {
        return !b.getWorld().getNearbyEntities(b.getBoundingBox().expand(-0.05), e -> e instanceof org.bukkit.entity.LivingEntity).isEmpty();
    }

    private static boolean needsSupport(Blueprint.Spec s) {
        return switch (s) {
            case WOOD_WALL, STONE_WALL, DOOR, IRON_DOOR, BED, WINDOW, BARS -> true;
            default -> false;
        };
    }

    /** Never knocked down by a builder: bedrock, containers, the Town Hall, other plugins' blocks it can't break. */
    private boolean untouchable(Block b) {
        Material m = b.getType();
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME || m == Material.LODESTONE || m == Material.SPAWNER) return true;
        if (b.getState(false) instanceof Container) return true;
        return m.getHardness() < 0;
    }

    private void clear(Colony col, Block b) {
        if (b.getType().isAir()) return;
        if (b.isLiquid()) {
            b.setType(Material.AIR);
            return;
        }
        List<ItemStack> drops = new ArrayList<>(b.getDrops());
        b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, b.getType());
        if (Tag.DOORS.isTagged(b.getType()) || Tag.BEDS.isTagged(b.getType())) b.setType(Material.AIR, true);
        else b.setType(Material.AIR, false);
        m.deposit(col, drops);
    }

    private static Material first(Colony col, List<Material> choices) {
        for (Material m : choices) if (col.storage.count(m) > 0) return m;
        return null;
    }

    private static boolean take(Colony col, List<Material> choices) {
        for (Material m : choices) {
            if (col.storage.remove(it -> it.getType() == m && Storage.plain(it), 1) == 1) return true;
        }
        return false;
    }

    private void placed(Block b) {
        BlockData d = b.getBlockData();
        b.getWorld().playSound(b.getLocation(), d.getSoundGroup().getPlaceSound(), 0.8f, 0.9f);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 2, 0.3, 0.3, 0.3, 0);
    }

    private void waiting(Npc n, Colony col, BuildJob job, String what) {
        job.waitingFor = what;
        n.activity = "Waiting for " + what + " in the State Chest";
        long now = System.currentTimeMillis();
        if (now - col.lastMaterialWarn > 180_000) {
            col.lastMaterialWarn = now;
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<yellow>The Builders need <white>" + what + "</white> in the Central State Chest to continue the " + job.type.display + ".");
            }
        }
    }

    /** Joins fences, panes and bars to their neighbours. */
    private static void connect(Block b) {
        fix(b);
        for (BlockFace f : SIDES) {
            Block nb = b.getRelative(f);
            if (nb.getBlockData() instanceof MultipleFacing) fix(nb);
        }
    }

    private static void fix(Block b) {
        if (!(b.getBlockData() instanceof MultipleFacing mf)) return;
        boolean fence = Tag.FENCES.isTagged(b.getType());
        for (BlockFace f : SIDES) {
            if (!mf.getAllowedFaces().contains(f)) continue;
            Block nb = b.getRelative(f);
            Material m = nb.getType();
            boolean join = m.isOccluding()
                    || (fence ? Tag.FENCES.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                    : m == Material.IRON_BARS || m.name().contains("GLASS_PANE") || Tag.WALLS.isTagged(m));
            mf.setFace(f, join);
        }
        b.setBlockData(mf, false);
    }

    private void complete(Colony col, BuildJob job, Blueprint bp, World w) {
        col.buildQueue.remove(job);
        BlockPos anchor = bp.anchor(job.origin, job.facing);
        BlueprintManager bm = plugin.blueprints();
        BlueprintManager.Result r = switch (job.type) {
            case HOUSE, PRISON -> bm.registerRoom(col, anchor.block(w), job.type);
            case TOWER -> bm.registerTower(col, anchor.block(w).getRelative(BlockFace.DOWN));
            case FARM -> bm.registerFarm(col, anchor.block(w));
            default -> null;
        };
        for (Player p : col.onlineMembers()) {
            if (r == null || r.error() != null) {
                Text.send(p, "<yellow>The Builders finished a <white>" + job.type.display + "</white>, but it couldn't be registered: <red>"
                        + (r == null ? "unknown" : r.error()) + "</red> Fix it and register it with the Blueprint Book.");
            } else {
                Text.send(p, "<green>The Builders finished a <white>" + job.type.display + "</white>! Registered as <white>" + r.building().label() + "</white>.");
                Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
            }
        }
        plugin.sim().assignBeds(col);
        plugin.quests().check(col);
        plugin.requestSave();
    }
}
