package com.colonysmp.npc;

import com.colonysmp.colony.RoomScanner;
import com.colonysmp.data.Colony;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workstations in a colony (furnaces, smokers, campfires, anvils...), found automatically and cached for a
 * minute. Block-entity stations are read from the claim's chunks; anvils and tables are looked for around
 * them and around the Town Hall.
 */
final class Stations {

    record Found(long at, List<BlockPos> furnaces, List<BlockPos> smokers, List<BlockPos> blast, List<BlockPos> campfires,
                 List<BlockPos> anvils, List<BlockPos> tables, List<BlockPos> brewing) {}

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final Map<String, Found> cache = new HashMap<>();

    Found get(Colony col, long now) {
        Found f = cache.get(col.id);
        if (f != null && now - f.at < 1200 && now >= f.at) return f;
        f = scan(col, now);
        cache.put(col.id, f);
        return f;
    }

    void forget(Colony col) {
        cache.remove(col.id);
    }

    private static Found scan(Colony col, long now) {
        List<BlockPos> furnaces = new ArrayList<>(), smokers = new ArrayList<>(), blast = new ArrayList<>(), camp = new ArrayList<>(),
                anvils = new ArrayList<>(), tables = new ArrayList<>(), brewing = new ArrayList<>();
        World w = col.world();
        if (w == null) return new Found(now, furnaces, smokers, blast, camp, anvils, tables, brewing);
        Set<BlockPos> around = new LinkedHashSet<>();
        if (col.core != null) around.add(col.core);
        for (int cx = col.region.minX() >> 4; cx <= col.region.maxX() >> 4; cx++) {
            for (int cz = col.region.minZ() >> 4; cz <= col.region.maxZ() >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                for (BlockState st : w.getChunkAt(cx, cz).getTileEntities(b -> station(b.getType()), false)) {
                    BlockPos p = new BlockPos(st.getX(), st.getY(), st.getZ());
                    if (!col.region.contains(p)) continue;
                    Material m = st.getType();
                    switch (m) {
                        case FURNACE -> furnaces.add(p);
                        case SMOKER -> smokers.add(p);
                        case BLAST_FURNACE -> blast.add(p);
                        case BREWING_STAND -> brewing.add(p);
                        default -> camp.add(p);
                    }
                    if (around.size() < 12) around.add(p);
                }
            }
        }
        // anvils and tables near the Town Hall and the furnaces
        Set<BlockPos> seen = new java.util.HashSet<>();
        for (BlockPos c : around) {
            int r = c.equals(col.core) ? 12 : 6;
            for (int x = c.x() - r; x <= c.x() + r; x++) {
                for (int z = c.z() - r; z <= c.z() + r; z++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int y = c.y() - 4; y <= c.y() + 4; y++) {
                        Material m = w.getBlockAt(x, y, z).getType();
                        boolean anvil = Tag.ANVIL.isTagged(m);
                        boolean table = m == Material.SMITHING_TABLE || m == Material.CRAFTING_TABLE;
                        if (!anvil && !table) continue;
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seen.add(p) || !col.region.contains(p)) continue;
                        if (anvil) anvils.add(p);
                        else tables.add(p);
                    }
                }
            }
        }
        return new Found(now, furnaces, smokers, blast, camp, anvils, tables, brewing);
    }

    private static boolean station(Material m) {
        return m == Material.FURNACE || m == Material.SMOKER || m == Material.BLAST_FURNACE || m == Material.CAMPFIRE
                || m == Material.SOUL_CAMPFIRE || m == Material.BREWING_STAND;
    }

    /** The closest of some stations that still exists. */
    static BlockPos nearest(World w, List<BlockPos> list, Location from, Set<Material> ok) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : list) {
            if (!p.loaded(w)) continue;
            Material m = p.block(w).getType();
            if (!ok.contains(m) && !(ok.contains(Material.ANVIL) && Tag.ANVIL.isTagged(m))) continue;
            double d = p.distSq(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** A standing spot next to a station. */
    static Location standBy(World w, BlockPos st) {
        for (int dy = 0; dy >= -1; dy--) {
            for (BlockFace f : SIDES) {
                Block b = st.relative(f).block(w).getRelative(0, dy, 0);
                if (RoomScanner.standable(w, b.getX(), b.getY(), b.getZ())) return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        for (BlockFace f : SIDES) {
            Block b = st.relative(f).block(w).getRelative(0, 1, 0);
            if (RoomScanner.standable(w, b.getX(), b.getY(), b.getZ())) return b.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }
}
