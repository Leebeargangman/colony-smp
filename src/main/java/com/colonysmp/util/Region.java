package com.colonysmp.util;

import org.bukkit.Location;

/** A claim: an axis-aligned rectangle over the full height of the world. Bounds are inclusive. */
public record Region(int minX, int minZ, int maxX, int maxZ) {

    public static Region of(int x1, int z1, int x2, int z2) {
        return new Region(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public static Region parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            return new Region(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int length() {
        return maxZ - minZ + 1;
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location l) {
        return contains(l.getBlockX(), l.getBlockZ());
    }

    public boolean contains(BlockPos p) {
        return contains(p.x(), p.z());
    }

    /** True if the two rectangles come closer than gap blocks. */
    public boolean overlaps(Region o, int gap) {
        return minX - gap <= o.maxX && maxX + gap >= o.minX && minZ - gap <= o.maxZ && maxZ + gap >= o.minZ;
    }

    public Region grow(int by) {
        return new Region(minX - by, minZ - by, maxX + by, maxZ + by);
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** Horizontal distance from a point to the nearest edge (0 inside). */
    public double distanceTo(double x, double z) {
        double dx = Math.max(Math.max(minX - x, 0), x - (maxX + 1));
        double dz = Math.max(Math.max(minZ - z, 0), z - (maxZ + 1));
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return minX + "," + minZ + "," + maxX + "," + maxZ;
    }
}
