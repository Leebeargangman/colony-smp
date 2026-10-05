package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Blueprint;
import com.colonysmp.colony.BlueprintManager;
import com.colonysmp.colony.RoomScanner;
import com.colonysmp.data.BuildJob;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.CustomBlueprint;
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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builders construct what players order with the Blueprint Book: the standard plans and structures copied
 * with the wand. They carry the materials from the State Chest in their satchel, a batch at a time.
 */
public final class Construction {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int LOOKAHEAD = 400;

    /** What a step needs from the State Chest. */
    private record Need(String key, String label, List<Material> items, boolean optional) {
        boolean accepts(ItemStack it) {
            return it != null && items.contains(it.getType()) && Storage.plain(it);
        }
    }

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
        Blueprint bp = Blueprint.of(col, job);
        World w = col.world();
        if (bp == null || w == null) {
            col.buildQueue.remove(0);
            if (bp == null) for (Player p : col.onlineMembers()) Text.send(p, "<yellow>The " + job.label() + " order was dropped: its blueprint is gone.");
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
            n.activity = "Waiting for the site to load";
            return true;
        }
        // materials come from the State Chest in the satchel
        Need need = need(step, at.block(w));
        if (need != null && n.c.carried(need::accepts) == 0) {
            if (!col.storage.has(need::accepts)) {
                if (need.optional || need.items.isEmpty()) {
                    job.step++;
                    return true;
                }
                waiting(n, col, job, need.label);
                if (!n.c.carry.isEmpty()) m.returnLeftovers(n, col);
                return true;
            }
            if (!m.atChest(n, col)) {
                n.mover.moveTo(col.chestLocation(), plugin.settings().walkSpeed, 2.2);
                n.activity = "Fetching " + need.label + " for the " + job.label();
                return true;
            }
            n.mover.stop();
            load(n, col, job, bp, w, need);
            return true;
        }
        Location target = at.center(w);
        Location spot = workSpot(n, job, bp, w, at);
        if (spot != null && !n.mover.near(spot, 1.6)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.2);
            n.activity = "Walking to the " + job.label() + " site";
            return true;
        }
        n.mover.stop();
        CitizenBrain.face(n, target);
        if (now < n.actionAt) return true;
        double eff = m.efficiency(col, n.c, Job.BUILDER);
        n.actionAt = now + (eff <= 0.01 ? 200 : Math.max(4, Math.round(plugin.settings().buildInterval * 20 / eff)));
        work(n, col, job, bp, w, need);
        return true;
    }

    /** Moves past every step that's already in place. */
    private static void skipDone(BuildJob job, Blueprint bp, World w, int max) {
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
                return new Need("DIRT", "dirt", List.of(Material.DIRT), false);
            }
            case EXACT -> {
                List<Material> items = Blueprint.items(s.data());
                return new Need("X:" + items.get(0).name(), Text.nice(items.get(0).name()).toLowerCase(), items, Blueprint.optional(s.data()));
            }
            default -> {
                if (s.ly() == 0 && needsSupport(s.spec())) {
                    Block below = b.getRelative(BlockFace.DOWN);
                    if (below.isPassable() || below.isLiquid()) {
                        return new Need("FOUNDATION", "foundation blocks (cobblestone or dirt)", Blueprint.candidates(Blueprint.Spec.FOUNDATION), false);
                    }
                }
                return new Need(s.spec().name(), s.spec().label, Blueprint.candidates(s.spec()), s.spec().optional);
            }
        }
    }

    /**
     * At the State Chest: puts back what the next part of the build won't use and loads a batch of what it
     * will, the current step's material first.
     */
    private void load(Npc n, Colony col, BuildJob job, Blueprint bp, World w, Need first) {
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
        m.unload(n, col, it -> needs.values().stream().noneMatch(nd -> nd.accepts(it)));
        for (Need nd : needs.values()) {
            int want = Math.min(64, Math.max(1, counts.getOrDefault(nd.key, 1))) - n.c.carried(nd::accepts);
            if (want <= 0) continue;
            Material pick = carriedChoice(n, nd);
            if (pick == null || col.storage.count(pick) <= 0) pick = chestChoice(col, nd);
            if (pick == null) continue;
            final Material mat = pick;
            for (ItemStack it : col.storage.take(x -> x.getType() == mat && Storage.plain(x), want)) {
                ItemStack left = n.c.stow(it);
                if (left != null) col.storage.add(left);
            }
            if (n.c.freeCarrySlots() == 0) break;
        }
        Fx.sound(col.chestLocation(), "minecraft:block.chest.open", 0.5f, 1.1f);
        n.activity = "Loading materials for the " + job.label();
    }

    /** The first of a need's items (best first) the builder already carries. */
    private static Material carriedChoice(Npc n, Need nd) {
        for (Material mat : nd.items) if (n.c.carried(it -> it.getType() == mat && Storage.plain(it)) > 0) return mat;
        return null;
    }

    private static Material chestChoice(Colony col, Need nd) {
        for (Material mat : nd.items) if (col.storage.count(mat) > 0) return mat;
        return null;
    }

    /** Takes one of the need's items out of the satchel; returns which, or null. */
    private static Material use(Npc n, Need nd) {
        Material mat = carriedChoice(n, nd);
        if (mat == null) return null;
        return n.c.useCarried(it -> it.getType() == mat && Storage.plain(it), 1) == 1 ? mat : null;
    }

    // ───────────── building ─────────────

    private void work(Npc n, Colony col, BuildJob job, Blueprint bp, World w, Need need) {
        Blueprint.Step s = bp.steps.get(job.step);
        BlockPos p = bp.pos(job.origin, job.facing, s);
        Block b = p.block(w);
        n.activity = "Building " + (job.custom != null ? job.custom : "a " + job.label()) + " (" + (job.step * 100 / bp.steps.size()) + "%)";
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
        // walls and floors need ground under them (the foundation block is the need right now)
        if (need.key.equals("FOUNDATION")) {
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
        if (s.spec() != Blueprint.Spec.TORCH && s.spec() != Blueprint.Spec.BUTTON && s.spec() != Blueprint.Spec.LADDER && occupied(b, n)) {
            n.activity = "Waiting for someone to move out of the way";
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
    private void exact(Npc n, Colony col, BuildJob job, Blueprint bp, Blueprint.Step s, Block b, Need need) {
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
        if (!passable && occupied(b, n)) {
            n.activity = "Waiting for someone to move out of the way";
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
        ItemStack left = n.c.stow(new ItemStack(Material.BUCKET));
        if (left != null) m.deposit(col, List.of(left));
    }

    // ───────────── where the builder stands ─────────────

    /**
     * Where the builder works from: right where they are if the block is within reach, otherwise a free spot
     * near it that no part of the plan will fill (so they never wall themselves in), otherwise from outside.
     */
    private Location workSpot(Npc n, BuildJob job, Blueprint bp, World w, BlockPos at) {
        Location here = n.body.getLocation();
        if (reach(here, at) && free(job, bp, here) && !inColumn(here, at)) return here;
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
        return outsideSpot(job, w, at, bp.bounds(job.origin, job.facing));
    }

    /** Close enough to place a block there. */
    private static boolean reach(Location from, BlockPos at) {
        double dx = at.x() + 0.5 - from.getX(), dz = at.z() + 0.5 - from.getZ();
        double dy = at.y() - from.getY();
        return dx * dx + dz * dz <= 4.8 * 4.8 && dy >= -4 && dy <= 4.5;
    }

    /** Standing in (or right under) the block's own column would put them in the way. */
    private static boolean inColumn(Location l, BlockPos at) {
        return l.getBlockX() == at.x() && l.getBlockZ() == at.z() && at.y() - l.getBlockY() >= -1 && at.y() - l.getBlockY() <= 2;
    }

    private final Map<String, Set<BlockPos>> planned = new java.util.HashMap<>();

    /** No block of the plan will go where someone stands here (feet and head). */
    private boolean free(BuildJob job, Blueprint bp, Location l) {
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

    private static Location outsideSpot(BuildJob job, World w, BlockPos at, BlockPos[] bb) {
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

    /** Someone is standing where the block would go: wait rather than bury them (the builder steps aside). */
    private static boolean occupied(Block b, Npc self) {
        var in = b.getWorld().getNearbyEntities(b.getBoundingBox().expand(-0.05), e -> e instanceof org.bukkit.entity.LivingEntity);
        if (in.isEmpty()) return false;
        if (in.size() == 1 && in.iterator().next().equals(self.body)) {
            self.idleSpot = null;
            Location away = Mover.safeSpot(b.getLocation().add(2.5, 0, 0.5));
            if (away != null) self.mover.moveTo(away, 0.2, 0.8);
        }
        return true;
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
                Text.send(p, "<yellow>The Builders need <white>" + what + "</white> in the Central State Chest to continue the " + job.label() + ".");
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

    private void complete(Colony col, BuildJob job, Blueprint bp, World w) {
        col.buildQueue.remove(job);
        planned.remove(job.id + "@" + job.origin);
        if (job.custom != null) {
            List<String> got = registerCopy(col, job, bp, w);
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<green>The Builders finished <white>" + Text.esc(job.custom) + "</white>!"
                        + (got.isEmpty() ? "" : " Registered: <white>" + String.join(", ", got) + "</white>."));
                Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
            }
        } else {
            BlockPos anchor = bp.anchor(job.origin, job.facing);
            BlueprintManager bm = plugin.blueprints();
            BlueprintManager.Result r = switch (job.type) {
                case HOUSE, PRISON, SCHOOL -> bm.registerRoom(col, anchor.block(w), job.type);
                case TOWER -> bm.registerTower(col, anchor.block(w).getRelative(BlockFace.DOWN));
                case FARM -> bm.registerFarm(col, anchor.block(w));
                default -> null;
            };
            for (Player p : col.onlineMembers()) {
                if (r == null || r.error() != null) {
                    Text.send(p, "<yellow>The Builders finished a <white>" + job.label() + "</white>, but it couldn't be registered: <red>"
                            + (r == null ? "unknown" : r.error()) + "</red> Fix it and register it with the Blueprint Book.");
                } else {
                    Text.send(p, "<green>The Builders finished a <white>" + job.label() + "</white>! Registered as <white>" + r.building().label() + "</white>.");
                    Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
                }
            }
        }
        plugin.sim().assignBeds(col);
        plugin.quests().check(col);
        plugin.requestSave();
    }

    /** Registers the rooms (beds, lecterns) and fields (farmland) of a finished copy. */
    private List<String> registerCopy(Colony col, BuildJob job, Blueprint bp, World w) {
        BlueprintManager bm = plugin.blueprints();
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
            if (Tag.BEDS.isTagged(mat) && b.getBlockData() instanceof Bed bed) {
                if (registered(col, p) || registered(col, p.relative(bed.getFacing()))) continue;
                start = b.getRelative(BlockFace.UP);
            } else if (mat == Material.LECTERN) {
                if (inSchool(col, p)) continue;
                for (BlockFace f : SIDES) {
                    Block nb = b.getRelative(f);
                    if (RoomScanner.open(nb)) {
                        start = nb;
                        break;
                    }
                }
            } else if (mat == Material.FARMLAND && !farmTiles.contains(p) && b.getType() == Material.FARMLAND) {
                BlueprintManager.Result r = bm.registerFarm(col, b);
                if (r.error() == null) {
                    farmTiles.addAll(r.building().tiles);
                    if (!out.contains(r.building().label())) out.add(r.building().label());
                }
                continue;
            }
            if (start == null) continue;
            BlueprintManager.Result r = bm.registerRoom(col, start, BuildingType.HOUSE);
            if (r.error() == null && !out.contains(r.building().label())) out.add(r.building().label());
        }
        return out;
    }

    private static boolean registered(Colony col, BlockPos bed) {
        for (Building b : col.buildings.values()) if (b.beds.contains(bed)) return true;
        return false;
    }

    private static boolean inSchool(Colony col, BlockPos p) {
        for (Building b : col.buildings.values()) {
            if (b.type == BuildingType.SCHOOL && b.tiles.contains(p)) return true;
        }
        return false;
    }
}
