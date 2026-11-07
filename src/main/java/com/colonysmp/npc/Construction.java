package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.Blueprint;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.building.RoomScanner;
import com.colonysmp.core.Skill;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.CustomBlueprint;
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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builders construct what players order with the Blueprint Book: the standard plans and structures copied with
 * the wand. They carry the materials from storage a batch at a time (the plainest blocks the colony has most
 * of), clear what's in the way (into storage), put up scaffolding to reach high parts, and register the
 * building when it's done. A builder never stays stuck: one who can't find a way to the site climbs over, and
 * anyone standing where a block goes is moved aside.
 */
public final class Construction {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int LOOKAHEAD = 400;
    /** Ticks a builder keeps trying to walk somewhere before climbing over to it. */
    private static final long STUCK_TICKS = 500;
    /** Ticks someone may stand where a block goes before they're moved aside. */
    private static final long IN_THE_WAY_TICKS = 100;

    /** What a step needs from storage: any of these items, best first. */
    private record Need(String key, String label, List<Material> items, boolean optional) {
        boolean accepts(String good) {
            for (Material m : items) if (NpcManager.good(m).equals(good)) return true;
            return false;
        }
    }

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Set<BlockPos>> planned = new HashMap<>();

    Construction(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** The order builders work on: the first one that isn't paused. */
    public static BuildOrder current(Colony col) {
        for (BuildOrder o : col.buildQueue) if (o.state != BuildOrder.State.PAUSED) return o;
        return null;
    }

    /** Returns false when there's nothing to build. */
    boolean think(Npc n, Colony col, Building hut, long now) {
        BuildOrder job = current(col);
        if (job == null) return false;
        Blueprint bp = Blueprint.of(col, job);
        World w = col.world();
        if (bp == null || w == null) {
            col.buildQueue.remove(job);
            if (bp == null) {
                col.log(ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(col), "The " + job.label() + " order was dropped: its blueprint is gone.");
                for (Player p : col.onlineMembers()) Text.send(p, "<yellow>The " + Text.esc(job.label()) + " order was dropped: its blueprint is gone.");
            }
            return true;
        }
        skipDone(job, bp, w, 64);
        if (job.step >= bp.steps.size()) {
            complete(col, job, bp, w);
            return true;
        }
        Blueprint.Step step = bp.steps.get(job.step);
        BlockPos at = bp.pos(job.origin, job.facing, step);
        if (!at.loaded(w)) {
            n.doing("Waiting for the " + job.label() + " site to load");
            return true;
        }
        Need need = need(step, at.block(w));
        if (need != null && carriedChoice(n, need) == null) {
            if (chestChoice(col, need) == null) {
                if (need.optional || need.items.isEmpty()) {
                    job.step++;
                    return true;
                }
                waiting(n, col, job, bp, w, step, need.label);
                if (onScaffold(n)) climbDown(n);
                m.returnCarried(n, col);
                return true;
            }
            if (onScaffold(n)) climbDown(n);
            Location store = m.storagePoint(col, n.loc());
            if (!m.goToStorage(n, col, "Fetching " + need.label + " for the " + job.label())) {
                unstick(n, store, now);
                return true;
            }
            n.walkGoal = null;
            load(n, col, job, bp, w, need);
            return true;
        }
        Location target = at.center(w);
        Location spot = workSpot(n, job, bp, w, at);
        if (spot != null && !n.mover.near(spot, 1.6)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.2);
            n.doing("Walking to the " + job.label() + " site");
            unstick(n, spot, now);
            return true;
        }
        n.walkGoal = null;
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, target);
        if (now < n.actionAt) return true;
        double eff = m.efficiency(col, n.c);
        n.actionAt = now + (eff <= 0.01 ? 200 : Math.max(4, Math.round(plugin.settings().buildInterval * 20 / eff)));
        if (job.state == BuildOrder.State.PLANNED) {
            job.state = BuildOrder.State.UNDER_CONSTRUCTION;
            col.log(ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(col), n.c.name + " started building the " + job.label() + ".");
        }
        work(n, col, job, bp, w, need);
        n.c.skills.gain(Skill.CONSTRUCTION, 0.4 * n.c.trait.learning());
        return true;
    }

    /** Moves past every step that's already in place. */
    private static void skipDone(BuildOrder job, Blueprint bp, World w, int max) {
        int guard = 0;
        while (job.step < bp.steps.size() && guard++ < max) {
            Blueprint.Step s = bp.steps.get(job.step);
            BlockPos p = bp.pos(job.origin, job.facing, s);
            if (!p.loaded(w)) break;
            if (!done(s, p.block(w))) break;
            job.step++;
        }
    }

    private static boolean done(Blueprint.Step s, Block b) {
        if (s.spec() == Blueprint.Spec.EXACT) return Blueprint.satisfied(s.data(), b);
        return Blueprint.satisfied(s.spec(), b);
    }

    // ───────────── supplies ─────────────

    private Need need(Blueprint.Step s, Block b) {
        switch (s.spec()) {
            case AIR -> {
                return null;
            }
            case FARMLAND -> {
                if (Blueprint.tillable(b.getType()) || untouchable(b)) return null;
                return new Need("dirt", "dirt", List.of(Material.DIRT), false);
            }
            case EXACT -> {
                List<Material> items = Blueprint.items(s.data());
                return new Need("x:" + items.get(0).name(), Text.nice(items.get(0).name()).toLowerCase(), items, Blueprint.optional(s.data()));
            }
            default -> {
                if (s.ly() == 0 && needsSupport(s.spec())) {
                    Block below = b.getRelative(BlockFace.DOWN);
                    if (below.isPassable() || below.isLiquid()) {
                        return new Need("foundation", "foundation blocks (cobblestone or dirt)", Blueprint.candidates(Blueprint.Spec.FOUNDATION), false);
                    }
                }
                return new Need(s.spec().name(), s.spec().label, Blueprint.candidates(s.spec()), s.spec().optional);
            }
        }
    }

    /**
     * At storage: puts back what the next part of the build won't use and loads a batch of what it will, the
     * current step's material first.
     */
    private void load(Npc n, Colony col, BuildOrder job, Blueprint bp, World w, Need first) {
        Map<String, Need> needs = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        needs.put(first.key, first);
        for (int i = job.step; i < bp.steps.size() && i < job.step + LOOKAHEAD; i++) {
            Blueprint.Step s = bp.steps.get(i);
            BlockPos p = bp.pos(job.origin, job.facing, s);
            if (!p.loaded(w)) break;
            Block b = p.block(w);
            if (done(s, b)) continue;
            Need nd = need(s, b);
            if (nd == null) continue;
            if (!needs.containsKey(nd.key)) {
                if (needs.size() >= Citizen.CARRY_SLOTS) continue;
                needs.put(nd.key, nd);
            }
            counts.merge(nd.key, 1, Integer::sum);
        }
        // put back what isn't needed
        for (Map.Entry<String, Long> e : n.c.carried.snapshot().entrySet()) {
            boolean used = false;
            for (Need nd : needs.values()) if (nd.accepts(e.getKey())) used = true;
            if (!used) col.storage.force(e.getKey(), n.c.carried.take(e.getKey(), e.getValue()));
        }
        // the step at hand comes first: make room for it if the satchel is full of later things
        Material firstPick = chestChoice(col, first);
        if (firstPick != null && n.c.carried.room(NpcManager.good(firstPick)) <= 0) {
            for (Map.Entry<String, Long> e : n.c.carried.snapshot().entrySet()) {
                if (first.accepts(e.getKey())) continue;
                col.storage.force(e.getKey(), n.c.carried.take(e.getKey(), e.getValue()));
                if (n.c.carried.room(NpcManager.good(firstPick)) > 0) break;
            }
        }
        for (Need nd : needs.values()) {
            Material pick = carriedChoice(n, nd);
            if (pick == null || col.storage.available(NpcManager.good(pick)) <= 0) pick = chestChoice(col, nd);
            if (pick == null) continue;
            String key = NpcManager.good(pick);
            long want = Math.min(64, Math.max(1, counts.getOrDefault(nd.key, 1))) - n.c.carried.get(key);
            want = Math.min(want, n.c.carried.room(key));
            if (want <= 0) continue;
            long got = col.storage.take(key, want);
            long added = n.c.carried.add(key, got);
            if (added < got) col.storage.force(key, got - added);
            if (n.c.carried.freeSlots() == 0) break;
        }
        n.c.carryTask = "build";
        Fx.sound(n.loc(), "minecraft:block.chest.open", 0.5f, 1.1f);
        n.doing("Loading materials for the " + job.label());
    }

    /** The first of a need's items (best first) the builder already carries. */
    private static Material carriedChoice(Npc n, Need nd) {
        for (Material mat : nd.items) if (n.c.carried.get(NpcManager.good(mat)) > 0) return mat;
        return null;
    }

    /**
     * What to take from storage for a need: the first kind it has (planks before stone, panes before glass...),
     * and of that kind the block storage has most of, so a wall comes out of one plain material.
     */
    private static Material chestChoice(Colony col, Need nd) {
        Material first = null;
        for (Material mat : nd.items) {
            if (col.storage.available(NpcManager.good(mat)) > 0) {
                first = mat;
                break;
            }
        }
        if (first == null) return null;
        Material best = first;
        long most = col.storage.available(NpcManager.good(first));
        for (Material mat : nd.items) {
            if (mat == first || kind(mat) != kind(first) || kind(mat) == 0) continue;
            long have = col.storage.available(NpcManager.good(mat));
            if (have > most) {
                most = have;
                best = mat;
            }
        }
        return best;
    }

    /** Blocks that are interchangeable in a plan: any planks, any logs, any plain stone, any bed... (0: only itself). */
    private static int kind(Material m) {
        if (Tag.PLANKS.isTagged(m)) return 1;
        if (Tag.LOGS.isTagged(m)) return 2;
        if (Blueprint.STONE.contains(m)) return 3;
        if (Tag.BEDS.isTagged(m)) return 4;
        if (Tag.WOODEN_DOORS.isTagged(m)) return 5;
        if (Tag.WOODEN_FENCES.isTagged(m)) return 6;
        if (m.name().endsWith("GLASS_PANE")) return 7;
        if (m.name().endsWith("GLASS")) return 8;
        if (Tag.BUTTONS.isTagged(m)) return 9;
        return 0;
    }

    /** Takes one of the need's items out of the satchel; returns which, or null. */
    private static Material use(Npc n, Need nd) {
        Material mat = carriedChoice(n, nd);
        if (mat == null) return null;
        return n.c.carried.take(NpcManager.good(mat), 1) == 1 ? mat : null;
    }

    // ───────────── building ─────────────

    private void work(Npc n, Colony col, BuildOrder job, Blueprint bp, World w, Need need) {
        Blueprint.Step s = bp.steps.get(job.step);
        BlockPos p = bp.pos(job.origin, job.facing, s);
        Block b = p.block(w);
        n.doing("Building the " + job.label() + " (" + (job.step * 100 / bp.steps.size()) + "%)");
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
            if (need == null || use(n, need) == null) return;
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
        if (need == null) {
            job.step++;
            return;
        }
        // walls and floors need ground under them
        if (need.key.equals("foundation")) {
            Material f = use(n, need);
            if (f == null) return;
            Block below = b.getRelative(BlockFace.DOWN);
            below.setType(f);
            placed(below);
            return;
        }
        if (s.spec() == Blueprint.Spec.EXACT) {
            exact(n, col, job, bp, s, b, need);
            return;
        }
        if (s.spec() == Blueprint.Spec.WATER) {
            if (use(n, need) == null) return;
            returnBucket(n, col);
            b.setType(Material.WATER);
            Fx.sound(b.getLocation(), "minecraft:item.bucket.empty", 0.8f, 1f);
            job.step++;
            return;
        }
        if (s.spec() != Blueprint.Spec.TORCH && s.spec() != Blueprint.Spec.BUTTON && s.spec() != Blueprint.Spec.LADDER && occupied(b, n, job, bp)) {
            n.doing("Waiting for someone to move out of the way");
            return;
        }
        Material item = carriedChoice(n, need);
        if (item == null) return;
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
                use(n, need);
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
                use(n, need);
                b.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), false), false);
                up.setBlockData(Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), true), false);
            }
            default -> {
                BlockData data = Blueprint.data(s.spec(), s.role(), item, job.facing, s.face(), false);
                if (data == null) {
                    job.step++;
                    return;
                }
                use(n, need);
                b.setBlockData(data, s.role() != Blueprint.Role.ATTACHED);
                if (data instanceof MultipleFacing) connect(b);
            }
        }
        placed(b);
        job.step++;
    }

    /** Places a copied block, turned to the order's facing (or its stand-in: dirt for grass and so on). */
    private void exact(Npc n, Colony col, BuildOrder job, Blueprint bp, Blueprint.Step s, Block b, Need need) {
        CustomBlueprint cb = bp.source;
        BlockData want = Blueprint.turned(s.data(), cb.facing, job.facing);
        Material wm = want.getMaterial();
        if (wm == Material.WATER || wm == Material.LAVA) {
            if (use(n, need) == null) return;
            returnBucket(n, col);
            b.setBlockData(want, true);
            Fx.sound(b.getLocation(), wm == Material.WATER ? "minecraft:item.bucket.empty" : "minecraft:item.bucket.empty_lava", 0.8f, 1f);
            job.step++;
            return;
        }
        boolean passable = !wm.isSolid() || Tag.DOORS.isTagged(wm) || Tag.TRAPDOORS.isTagged(wm) || Tag.FENCE_GATES.isTagged(wm);
        if (!passable && occupied(b, n, job, bp)) {
            n.doing("Waiting for someone to move out of the way");
            return;
        }
        Material item = carriedChoice(n, need);
        if (item == null) return;
        BlockData place = item == need.items.get(0) ? want : item.createBlockData();
        if (place instanceof Waterlogged wl) wl.setWaterlogged(false);
        if (s.role() == Blueprint.Role.BED_FOOT && place instanceof Bed bed) {
            Block head = b.getRelative(bed.getFacing());
            if (!head.getType().isAir() && !head.isReplaceable()) {
                if (untouchable(head)) {
                    job.step++;
                    return;
                }
                clear(col, head);
                return;
            }
            Bed hd = (Bed) bed.clone();
            hd.setPart(Bed.Part.HEAD);
            use(n, need);
            head.setBlockData(hd, false);
            b.setBlockData(bed, false);
        } else if (s.role() == Blueprint.Role.DOOR_LOWER && place instanceof Bisected bi) {
            Block up = b.getRelative(BlockFace.UP);
            if (!up.getType().isAir() && !up.isReplaceable()) {
                if (untouchable(up)) {
                    job.step++;
                    return;
                }
                clear(col, up);
                return;
            }
            Bisected top = (Bisected) bi.clone();
            top.setHalf(Bisected.Half.TOP);
            use(n, need);
            b.setBlockData(bi, false);
            up.setBlockData(top, false);
        } else {
            use(n, need);
            b.setBlockData(place, false);
        }
        placed(b);
        job.step++;
    }

    private void returnBucket(Npc n, Colony col) {
        if (n.c.carried.add("bucket", 1) < 1) col.storage.force("bucket", 1);
    }

    // ───────────── where the builder stands ─────────────

    /**
     * Where the builder works from: right where they are if the block is within reach, otherwise a free spot
     * near it that no part of the plan will fill (so they never wall themselves in), otherwise from outside.
     */
    private Location workSpot(Npc n, BuildOrder job, Blueprint bp, World w, BlockPos at) {
        Location here = n.loc();
        if (reach(here, at) && free(job, bp, here) && !inColumn(here, at)) return here;
        // down from the scaffolding before walking anywhere else
        if (onScaffold(n)) {
            Location sc = scaffold(n, job, bp, w, at, false);
            if (sc != null) return sc;
            climbDown(n);
            here = n.loc();
        }
        Location cur = n.idleSpot;
        if (cur != null && cur.getWorld() == w && reach(cur, at) && free(job, bp, cur) && !inColumn(cur, at)) return cur;
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                for (int dy = -4; dy <= 2; dy++) {
                    int x = at.x() + dx, y = at.y() + dy, z = at.z() + dz;
                    if (!w.isChunkLoaded(x >> 4, z >> 4) || !RoomScanner.standable(w, x, y, z)) continue;
                    Location l = new Location(w, x + 0.5, y, z + 0.5);
                    if (!reach(l, at) || !free(job, bp, l)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 2 + here.distanceSquared(l) * 0.15;
                    if (d < bd) {
                        bd = d;
                        best = l;
                    }
                }
            }
        }
        if (best != null) {
            n.idleSpot = best;
            return best;
        }
        // too high to reach from the ground: scaffolding
        Location sc = scaffold(n, job, bp, w, at, true);
        if (sc != null) return sc;
        return outsideSpot(job, w, at, bp.bounds(job.origin, job.facing));
    }

    // ───────────── scaffolding ─────────────

    /**
     * A scaffolding column beside the plan to stand on for a block out of reach from the ground. Returns where
     * to go: the foot of the column (walking there), or its top once the builder has put it up and climbed it.
     * With {@code build} false, only a column already standing under the builder counts. Null if there's no
     * room for one.
     */
    private Location scaffold(Npc n, BuildOrder job, Blueprint bp, World w, BlockPos at, boolean build) {
        int standY = at.y() - 2;
        Location best = null;
        int bestGround = 0;
        double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = at.x() + dx, z = at.z() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Integer ground = groundBelow(w, x, standY, z);
                if (ground == null || ground >= standY) continue;
                Location top = new Location(w, x + 0.5, standY, z + 0.5);
                if (!reach(top, at)) continue;
                boolean ok = true;
                for (int y = ground; y <= standY + 1 && ok; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material mt = b.getType();
                    if (!(mt.isAir() || mt == Material.SCAFFOLDING || b.isReplaceable() && !b.isLiquid())) ok = false;
                    else if (!free(job, bp, new Location(w, x + 0.5, y, z + 0.5))) ok = false;
                }
                if (!ok) continue;
                double d = dx * dx + dz * dz + (standY - ground) * 0.5;
                if (d < bd) {
                    bd = d;
                    best = top;
                    bestGround = ground;
                }
            }
        }
        if (best == null) return null;
        Location here = n.loc();
        if (here.getWorld() == w && Math.abs(here.getX() - best.getX()) < 0.8 && Math.abs(here.getZ() - best.getZ()) < 0.8 && Math.abs(here.getY() - standY) < 0.6) return best;
        if (!build) return null;
        Location foot = Mover.safeSpot(new Location(w, best.getX(), bestGround, best.getZ()));
        if (foot == null) foot = new Location(w, best.getX(), bestGround, best.getZ());
        if (!n.mover.near(foot, 2.2)) return foot;
        // put the column up and climb it
        for (int y = bestGround; y < standY; y++) {
            Block b = w.getBlockAt(best.getBlockX(), y, best.getBlockZ());
            if (b.getType() == Material.SCAFFOLDING) continue;
            BlockData d = Material.SCAFFOLDING.createBlockData();
            if (d instanceof org.bukkit.block.data.type.Scaffolding sd) {
                sd.setDistance(0);
                sd.setBottom(false);
            }
            b.setBlockData(d, false);
            BlockPos p = BlockPos.of(b);
            if (!job.scaffold.contains(p)) job.scaffold.add(p);
        }
        Fx.sound(foot, "minecraft:block.scaffolding.place", 0.9f, 1f);
        n.mover.stop();
        n.body.teleport(best);
        n.mover.sync();
        n.doing("Climbing the scaffolding at the " + job.label());
        return best;
    }

    /** The feet level of the first solid ground under a height, or null if there's none close. */
    private static Integer groundBelow(World w, int x, int fromY, int z) {
        for (int y = fromY; y > fromY - 24 && y > w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y - 1, z);
            if (b.getType() == Material.SCAFFOLDING) continue;
            if (b.isLiquid()) return null;
            if (b.getType().isSolid()) return y;
        }
        return null;
    }

    /** Standing on (or in) scaffolding. */
    static boolean onScaffold(Npc n) {
        Block feet = n.loc().getBlock();
        return feet.getType() == Material.SCAFFOLDING || feet.getRelative(BlockFace.DOWN).getType() == Material.SCAFFOLDING;
    }

    /** Climbs down the scaffolding column under the builder to the ground beside it. */
    static void climbDown(Npc n) {
        Block b = n.loc().getBlock();
        while (b.getRelative(BlockFace.DOWN).getType() == Material.SCAFFOLDING && b.getY() > b.getWorld().getMinHeight()) b = b.getRelative(BlockFace.DOWN);
        Location down = null;
        for (BlockFace f : SIDES) {
            Block s = b.getRelative(f);
            if (RoomScanner.standable(s.getWorld(), s.getX(), s.getY(), s.getZ())) {
                down = s.getLocation().add(0.5, 0, 0.5);
                break;
            }
        }
        if (down == null) down = Mover.safeSpot(b.getLocation().add(0.5, 0, 0.5));
        if (down == null) return;
        n.mover.stop();
        n.body.teleport(down);
        n.mover.sync();
        n.idleSpot = null;
    }

    /** Takes down an order's scaffolding (it's the builders' own: nothing goes to storage). */
    public static void removeScaffold(World w, BuildOrder job) {
        if (w == null) return;
        for (int i = job.scaffold.size() - 1; i >= 0; i--) {
            BlockPos p = job.scaffold.get(i);
            if (p.loaded(w) && p.block(w).getType() == Material.SCAFFOLDING) p.block(w).setType(Material.AIR, false);
        }
        job.scaffold.clear();
    }

    // ───────────── never stuck ─────────────

    /**
     * Keeps a walking builder from being stuck for good: if they've got no closer to where they're going for too
     * long (no way through, a gap, a fence), they climb over to it.
     */
    private void unstick(Npc n, Location goal, long now) {
        if (goal == null || goal.getWorld() != n.loc().getWorld()) return;
        double dist = n.loc().distance(goal);
        if (n.walkGoal == null || n.walkGoal.getWorld() != goal.getWorld() || n.walkGoal.distanceSquared(goal) > 4) {
            n.walkGoal = goal.clone();
            n.walkSince = now;
            n.walkBest = dist;
            return;
        }
        if (dist < n.walkBest - 1) {
            n.walkBest = dist;
            n.walkSince = now;
            return;
        }
        if (now - n.walkSince < STUCK_TICKS && !(n.mover.blocked() && now - n.walkSince > STUCK_TICKS / 3)) return;
        Location to = Mover.safeSpot(goal);
        if (to == null) to = goal;
        n.mover.stop();
        n.body.teleport(to);
        n.mover.sync();
        n.walkGoal = null;
        Fx.sound(to, "minecraft:entity.villager.ambient", 0.6f, 1f);
    }

    private static boolean reach(Location from, BlockPos at) {
        double dx = at.x() + 0.5 - from.getX(), dz = at.z() + 0.5 - from.getZ();
        double dy = at.y() - from.getY();
        return dx * dx + dz * dz <= 4.8 * 4.8 && dy >= -4 && dy <= 4.5;
    }

    private static boolean inColumn(Location l, BlockPos at) {
        return l.getBlockX() == at.x() && l.getBlockZ() == at.z() && at.y() - l.getBlockY() >= -1 && at.y() - l.getBlockY() <= 2;
    }

    /** No block of the plan will go where someone stands here (feet and head). */
    private boolean free(BuildOrder job, Blueprint bp, Location l) {
        CustomBlueprint cb = bp.source;
        if (cb == null) {
            Set<BlockPos> cells = planned.computeIfAbsent(job.id + "@" + job.origin, k -> {
                Set<BlockPos> out = new HashSet<>();
                for (Blueprint.Step s : bp.steps) if (s.spec() != Blueprint.Spec.AIR) out.add(bp.pos(job.origin, job.facing, s));
                return out;
            });
            BlockPos feet = new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            return !cells.contains(feet) && !cells.contains(feet.add(0, 1, 0));
        }
        BlockFace f = job.facing, r = Blueprint.right(f);
        int dx = l.getBlockX() - job.origin.x(), dz = l.getBlockZ() - job.origin.z();
        int lx = dx * r.getModX() + dz * r.getModZ(), lz = dx * f.getModX() + dz * f.getModZ();
        int x = lx + cb.sx / 2, z = lz;
        if (x < 0 || z < 0 || x >= cb.sx || z >= cb.sz) return true;
        for (int up = 0; up <= 1; up++) {
            int y = l.getBlockY() + up - job.origin.y() + cb.sink;
            if (y < 0 || y >= cb.sy) continue;
            if (cb.at(x, y, z) != 0) return false;
        }
        return true;
    }

    private static Location outsideSpot(BuildOrder job, World w, BlockPos at, BlockPos[] bb) {
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

    /**
     * Someone is standing where the block would go: wait rather than bury them. The builder steps aside
     * themselves; animals and other citizens still in the way after a few seconds are moved out of the plan,
     * and a player is asked to step aside.
     */
    private boolean occupied(Block b, Npc self, BuildOrder job, Blueprint bp) {
        var in = b.getWorld().getNearbyEntities(b.getBoundingBox().expand(-0.05), e -> e instanceof org.bukkit.entity.LivingEntity);
        if (in.isEmpty()) {
            self.blockedSince = 0;
            return false;
        }
        if (in.size() == 1 && in.iterator().next().equals(self.body)) {
            self.idleSpot = null;
            Location away = Mover.safeSpot(b.getLocation().add(2.5, 0, 0.5));
            if (away != null) self.mover.moveTo(away, 0.2, 0.8);
            return true;
        }
        long now = plugin.tick();
        if (self.blockedSince == 0) self.blockedSince = now;
        if (now - self.blockedSince < IN_THE_WAY_TICKS) return true;
        Location out = outsideSpot(job, b.getWorld(), BlockPos.of(b), bp.bounds(job.origin, job.facing));
        for (org.bukkit.entity.Entity e : in) {
            if (e.equals(self.body)) continue;
            if (e instanceof Player p) {
                Text.bar(p, "<yellow>Step aside: " + Text.esc(self.c.name) + " needs to build where you're standing.");
                continue;
            }
            if (out != null) e.teleport(out);
        }
        self.blockedSince = 0;
        return true;
    }

    private static boolean needsSupport(Blueprint.Spec s) {
        return switch (s) {
            case WOOD_WALL, STONE_WALL, DOOR, IRON_DOOR, BED, WINDOW, BARS -> true;
            default -> false;
        };
    }

    /** Never knocked down by a builder: bedrock, containers, the Town Hall core, unbreakable blocks. */
    private static boolean untouchable(Block b) {
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
        b.setType(Material.AIR, Tag.DOORS.isTagged(b.getType()) || Tag.BEDS.isTagged(b.getType()));
        m.deposit(col, null, drops, b.getLocation().add(0.5, 0.5, 0.5));
    }

    private static void placed(Block b) {
        BlockData d = b.getBlockData();
        b.getWorld().playSound(b.getLocation(), d.getSoundGroup().getPlaceSound(), 0.8f, 0.9f);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 2, 0.3, 0.3, 0.3, 0);
    }

    private void waiting(Npc n, Colony col, BuildOrder job, Blueprint bp, World w, Blueprint.Step step, String what) {
        job.waitingFor = what;
        n.doing("Waiting for " + what + " in storage");
        long now = System.currentTimeMillis();
        if (now - job.lastWarn > 180_000) {
            job.lastWarn = now;
            // how many of them the rest of the plan takes
            int more = 0;
            for (int i = job.step; i < bp.steps.size(); i++) {
                Blueprint.Step s = bp.steps.get(i);
                boolean same = step.spec() == Blueprint.Spec.EXACT ? s.spec() == Blueprint.Spec.EXACT && s.data().getMaterial() == step.data().getMaterial() : s.spec() == step.spec();
                if (!same) continue;
                BlockPos p = bp.pos(job.origin, job.facing, s);
                if (!p.loaded(w) || !done(s, p.block(w))) more++;
            }
            String count = more > 1 ? "about <white>" + more + "</white> " : "";
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<yellow>The builders need " + count + "<white>" + what + "</white> in storage to finish the " + Text.esc(job.label())
                        + ". Put them in the storage chest beside the Town Hall.");
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

    // ───────────── finishing ─────────────

    private void complete(Colony col, BuildOrder job, Blueprint bp, World w) {
        col.buildQueue.remove(job);
        removeScaffold(w, job);
        planned.remove(job.id + "@" + job.origin);
        long day = plugin.sim().day(col);
        if (job.custom != null) {
            List<String> got = registerCopy(col, job, bp, w);
            col.log(ColonyEvent.Type.CONSTRUCTION, day, "The builders finished " + job.custom + (got.isEmpty() ? "." : " (registered: " + String.join(", ", got) + ")."));
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<green>The builders finished <white>" + Text.esc(job.custom) + "</white>!"
                        + (got.isEmpty() ? "" : " Registered: <white>" + Text.esc(String.join(", ", got)) + "</white>."));
                Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
            }
        } else {
            BlockPos anchor = bp.anchor(job.origin, job.facing);
            Block at = anchor.block(w);
            if (job.type.shape == BuildingType.Shape.TOWER) at = at.getRelative(BlockFace.DOWN);
            BuildingManager.Result r = plugin.buildings().register(null, col, job.type, at, job.facing);
            if (r.error() != null) {
                col.log(ColonyEvent.Type.CONSTRUCTION, day, "The builders finished a " + job.label() + ", but it couldn't be registered: " + r.error());
            } else {
                col.log(ColonyEvent.Type.CONSTRUCTION, day, "The builders finished a " + job.label() + ": " + r.building().label() + ".");
            }
            for (Player p : col.onlineMembers()) {
                if (r.error() != null) {
                    Text.send(p, "<yellow>The builders finished " + Text.a("<white>" + job.label()) + "</white>, but it couldn't be registered: <red>"
                            + r.error() + "</red> Fix it and register it with the Blueprint Book.");
                } else {
                    Text.send(p, "<green>The builders finished " + Text.a("<white>" + job.label()) + "</white>! Registered as <white>" + r.building().label() + "</white>.");
                    Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
                }
            }
        }
        plugin.sim().housing().assign(col);
        plugin.quests().check(col);
        plugin.requestSave();
    }

    /** Registers the rooms (beds, lecterns) and fields (farmland) of a finished copy. */
    private List<String> registerCopy(Colony col, BuildOrder job, Blueprint bp, World w) {
        BuildingManager bm = plugin.buildings();
        List<String> out = new ArrayList<>();
        Set<BlockPos> farmTiles = new HashSet<>();
        for (Building b : col.buildings(BuildingType.FARM)) farmTiles.addAll(b.tiles);
        for (Blueprint.Step s : bp.steps) {
            if (s.spec() != Blueprint.Spec.EXACT) continue;
            Material mat = s.data().getMaterial();
            BlockPos p = bp.pos(job.origin, job.facing, s);
            if (!p.loaded(w)) continue;
            Block b = p.block(w);
            Block start = null;
            BuildingType type = BuildingType.HOUSE;
            if (Tag.BEDS.isTagged(mat) && b.getBlockData() instanceof Bed bed) {
                if (registered(col, p) || registered(col, p.relative(bed.getFacing()))) continue;
                start = b.getRelative(BlockFace.UP);
            } else if (mat == Material.LECTERN) {
                if (bm.at(col, p) != null) continue;
                type = BuildingType.SCHOOL;
                for (BlockFace f : SIDES) {
                    Block nb = b.getRelative(f);
                    if (RoomScanner.open(nb)) {
                        start = nb;
                        break;
                    }
                }
            } else if (mat == Material.FARMLAND && !farmTiles.contains(p) && b.getType() == Material.FARMLAND) {
                BuildingManager.Result r = bm.register(null, col, BuildingType.FARM, b, job.facing);
                if (r.error() == null) {
                    farmTiles.addAll(r.building().tiles);
                    if (!out.contains(r.building().label())) out.add(r.building().label());
                }
                continue;
            }
            if (start == null) continue;
            BuildingManager.Result r = bm.register(null, col, type, start, job.facing);
            if (r.error() == null && !out.contains(r.building().label())) out.add(r.building().label());
        }
        return out;
    }

    private static boolean registered(Colony col, BlockPos bed) {
        for (Building b : col.buildings.values()) if (b.beds.contains(bed)) return true;
        return false;
    }
}
