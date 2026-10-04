package com.colonysmp.npc;

import com.colonysmp.colony.RoomScanner;
import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Mob;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Walks an NPC body along a path from Paper's pathfinder. The body has no brain of its own (it is "unaware"),
 * so it never wanders off: it goes exactly where the State sends it. Paths that can't be walked (fence gates,
 * ladders, half-dug tunnels) fall back to stepping straight to the destination after a few tries.
 */
public final class Mover {

    private static final Vector ZERO = new Vector();

    private final Mob body;
    private Location dest;
    private double speed, arrive;
    private List<Location> path;
    private int idx;
    private Location pos;
    private long nextPathAt, pausedUntil, lastProgressAt;
    private int fails;
    private boolean arrived;
    private boolean ironKeys;
    private final List<long[]> doors = new ArrayList<>();
    private final List<Block> doorBlocks = new ArrayList<>();

    public Mover(Mob body) {
        this.body = body;
    }

    /** Wardens and guards carry the keys: they open iron doors too. */
    public void setIronKeys(boolean keys) {
        ironKeys = keys;
    }

    public boolean moving() {
        return dest != null;
    }

    public boolean arrived() {
        return arrived;
    }

    public Location dest() {
        return dest;
    }

    public void moveTo(Location to, double speed, double arrive) {
        if (to == null || to.getWorld() == null) return;
        if (dest != null && dest.getWorld() == to.getWorld() && dest.distanceSquared(to) < 1.0) {
            this.speed = speed;
            this.arrive = arrive;
            return;
        }
        if (dest == null) arrived = false;
        boolean keep = path != null && dest != null && dest.getWorld() == to.getWorld() && dest.distanceSquared(to) < 9;
        dest = to.clone();
        this.speed = speed;
        this.arrive = arrive;
        arrived = false;
        if (!keep) {
            path = null;
            fails = 0;
        }
    }

    /** True if within the given distance of a point (horizontally, and not far above or below). */
    public boolean near(Location l, double dist) {
        Location c = body.getLocation();
        if (l == null || c.getWorld() != l.getWorld()) return false;
        double dx = c.getX() - l.getX(), dz = c.getZ() - l.getZ();
        return dx * dx + dz * dz <= dist * dist && Math.abs(c.getY() - l.getY()) < 3;
    }

    public void stop() {
        dest = null;
        path = null;
    }

    /** Lets physics move the body (knockback) before walking on. */
    public void pause(int ticks, long now) {
        pausedUntil = now + ticks;
        path = null;
    }

    public void tick(NpcManager mgr, long now) {
        closeDoors(now);
        if (dest == null || !body.isValid()) return;
        if (now < pausedUntil) return;
        if (body.getWorld() != dest.getWorld()) {
            stop();
            return;
        }
        Location cur = body.getLocation();
        if (near(dest, arrive)) {
            arrived = true;
            stop();
            return;
        }
        if (pos != null && path != null && pos.getWorld() == cur.getWorld() && pos.distanceSquared(cur) > 2.25) {
            // pushed or knocked off the path: plan again from here
            path = null;
        }
        if (path == null) {
            if (now < nextPathAt || !mgr.pathBudget()) return;
            plan(now, cur);
            if (path == null) return;
        }
        follow(now);
    }

    private void plan(long now, Location cur) {
        nextPathAt = now + 20;
        Pathfinder.PathResult r = null;
        try {
            r = body.getPathfinder().findPath(dest);
        } catch (RuntimeException ignored) {
            // the pathfinder can throw for unloaded regions; treat as no path
        }
        if (r == null || r.getPoints().size() < 1) {
            fail(now);
            return;
        }
        List<Location> pts = new ArrayList<>();
        for (Location l : r.getPoints()) pts.add(stand(l));
        path = pts;
        idx = 0;
        pos = cur.clone();
        lastProgressAt = now;
        // skip a first node we're already standing on
        if (!path.isEmpty() && path.get(0).distanceSquared(cur) < 0.36) idx = 1;
    }

    private void follow(long now) {
        double remaining = speed;
        Location p = pos.clone();
        Location before = pos.clone();
        while (remaining > 1e-4 && idx < path.size()) {
            Location n = path.get(idx);
            double d = p.distance(n);
            if (d <= remaining) {
                p = n.clone();
                remaining -= d;
                idx++;
            } else {
                Vector v = n.toVector().subtract(p.toVector()).multiply(remaining / d);
                p.add(v);
                remaining = 0;
            }
        }
        World w = p.getWorld();
        if (w == null || !w.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
            stop();
            return;
        }
        openDoors(p, now);
        if (idx < path.size()) openDoors(path.get(idx), now);
        double dx = p.getX() - before.getX(), dz = p.getZ() - before.getZ();
        if (dx * dx + dz * dz > 1e-4) {
            p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            lastProgressAt = now;
        } else {
            p.setYaw(body.getLocation().getYaw());
        }
        p.setPitch(0);
        body.setVelocity(ZERO);
        body.teleport(p);
        pos = p;
        if (idx >= path.size()) {
            path = null;
            if (!near(dest, arrive + 0.75)) fail(now);
        } else if (now - lastProgressAt > 60) {
            path = null;
            fail(now);
        }
    }

    private void fail(long now) {
        fails++;
        nextPathAt = now + 10L * fails;
        if (fails >= 3) {
            // can't walk there: step straight to it (gates, ladders, fresh tunnels)
            Location safe = safeSpot(dest);
            if (safe != null) {
                safe.setYaw(body.getLocation().getYaw());
                body.setVelocity(ZERO);
                body.teleport(safe);
                pos = safe;
            }
            arrived = true;
            fails = 0;
            stop();
        }
    }

    /** The standing point on a path node (on top of slabs, farmland, carpets...). */
    static Location stand(Location node) {
        World w = node.getWorld();
        Block b = node.getBlock();
        double y = node.getBlockY();
        Material m = b.getType();
        if (!b.isPassable() && !Tag.DOORS.isTagged(m) && !Tag.FENCE_GATES.isTagged(m) && !Tag.TRAPDOORS.isTagged(m)) {
            BoundingBox bb = b.getBoundingBox();
            double top = bb.getMaxY();
            if (top > y && top - y <= 0.6) y = top;
        }
        return new Location(w, node.getBlockX() + 0.5, y, node.getBlockZ() + 0.5);
    }

    /** A free spot at or near a location (for stepping straight there). */
    public static Location safeSpot(Location l) {
        if (l == null || l.getWorld() == null) return null;
        World w = l.getWorld();
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        if (RoomScanner.standable(w, bx, by, bz)) return new Location(w, l.getX(), floorAt(w, bx, by, bz), l.getZ());
        for (int r = 1; r <= 3; r++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                        if (RoomScanner.standable(w, x, y, z)) return new Location(w, x + 0.5, floorAt(w, x, y, z), z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private static double floorAt(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        if (!b.isPassable()) {
            double top = b.getBoundingBox().getMaxY();
            if (top > y && top - y <= 0.6) return top;
        }
        return y;
    }

    // ───────────── doors ─────────────

    private void openDoors(Location at, long now) {
        Block feet = at.getBlock();
        tryOpen(feet, now);
    }

    private void tryOpen(Block b, long now) {
        Material m = b.getType();
        boolean wooden = Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
        boolean iron = m == Material.IRON_DOOR;
        if (!wooden && !(iron && ironKeys)) return;
        BlockData d = b.getBlockData();
        if (!(d instanceof Openable o) || o.isOpen()) return;
        o.setOpen(true);
        b.setBlockData(o, true);
        b.getWorld().playSound(b.getLocation(), iron ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 1f);
        doorBlocks.add(b);
        doors.add(new long[]{now + 50});
    }

    private void closeDoors(long now) {
        if (doors.isEmpty()) return;
        Iterator<long[]> it = doors.iterator();
        Iterator<Block> bit = doorBlocks.iterator();
        while (it.hasNext()) {
            long[] t = it.next();
            Block b = bit.next();
            if (now < t[0]) continue;
            // wait until nobody is standing in the doorway
            if (!b.getWorld().getNearbyEntities(b.getBoundingBox().expand(0.2, 1, 0.2), e -> e instanceof org.bukkit.entity.LivingEntity).isEmpty()) {
                t[0] = now + 20;
                continue;
            }
            BlockData d = b.getBlockData();
            if (d instanceof Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o, true);
                b.getWorld().playSound(b.getLocation(), b.getType() == Material.IRON_DOOR ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.7f, 1f);
            }
            it.remove();
            bit.remove();
        }
    }

    /** Opens a door for someone with keys (wardens) and closes it again after a while. */
    public void openFor(Block door, long now, long closeAfter) {
        BlockData d = door.getBlockData();
        if (!(d instanceof Openable o)) return;
        if (!o.isOpen()) {
            o.setOpen(true);
            door.setBlockData(o, true);
            door.getWorld().playSound(door.getLocation(), door.getType() == Material.IRON_DOOR ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 1f);
        }
        doorBlocks.add(door);
        doors.add(new long[]{now + closeAfter});
    }

    /** Closes every door this NPC opened (despawn). */
    public void closeAll() {
        for (Block b : doorBlocks) {
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            BlockData d = b.getBlockData();
            if (d instanceof Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o, true);
            }
        }
        doors.clear();
        doorBlocks.clear();
    }

    public void sync() {
        pos = body.getLocation();
    }
}
