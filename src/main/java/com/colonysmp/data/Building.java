package com.colonysmp.data;

import com.colonysmp.util.BlockPos;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** A registered structure: a house, cell, farm, guard tower or mine. */
public final class Building {

    public final String id;
    public final BuildingType type;
    /** Bounding box: room interior, farm tiles, tower column, or mine origin. */
    public BlockPos min, max;
    /** Room: a point inside. Tower: where a guard stands. Mine: the entrance block. Farm: the first tile. */
    public BlockPos anchor;
    /** Bed heads (houses and cells). */
    public final List<BlockPos> beds = new ArrayList<>();
    /** Doors (wooden for houses, iron for cells), lower half. */
    public final List<BlockPos> doors = new ArrayList<>();
    /** Farmland / soul sand tiles (farms). */
    public final List<BlockPos> tiles = new ArrayList<>();
    /** Mine: direction of the shaft. */
    public BlockFace facing = BlockFace.NORTH;
    /** Mine: dig steps done so far. */
    public int progress;
    public boolean exhausted;

    public Building(String id, BuildingType type) {
        this.id = id;
        this.type = type;
    }

    public boolean contains(BlockPos p) {
        return min != null && max != null && p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public boolean containsXZ(double x, double z, int pad) {
        return min != null && max != null && x >= min.x() - pad && x < max.x() + 1 + pad && z >= min.z() - pad && z < max.z() + 1 + pad;
    }

    public String label() {
        return type.display + " #" + id;
    }

    public void save(ConfigurationSection s) {
        s.set("type", type.name());
        s.set("min", str(min));
        s.set("max", str(max));
        s.set("anchor", str(anchor));
        s.set("beds", strs(beds));
        s.set("doors", strs(doors));
        s.set("tiles", strs(tiles));
        s.set("facing", facing.name());
        s.set("progress", progress);
        s.set("exhausted", exhausted);
    }

    public static Building load(String id, ConfigurationSection s) {
        BuildingType t = BuildingType.parse(s.getString("type"));
        if (t == null) return null;
        Building b = new Building(id, t);
        b.min = BlockPos.parse(s.getString("min"));
        b.max = BlockPos.parse(s.getString("max"));
        b.anchor = BlockPos.parse(s.getString("anchor"));
        parse(s.getStringList("beds"), b.beds);
        parse(s.getStringList("doors"), b.doors);
        parse(s.getStringList("tiles"), b.tiles);
        try {
            b.facing = BlockFace.valueOf(s.getString("facing", "NORTH"));
        } catch (IllegalArgumentException e) {
            b.facing = BlockFace.NORTH;
        }
        b.progress = s.getInt("progress");
        b.exhausted = s.getBoolean("exhausted");
        return b;
    }

    static String str(BlockPos p) {
        return p == null ? null : p.toString();
    }

    static List<String> strs(List<BlockPos> l) {
        List<String> out = new ArrayList<>(l.size());
        for (BlockPos p : l) out.add(p.toString());
        return out;
    }

    static void parse(List<String> in, List<BlockPos> out) {
        for (String s : in) {
            BlockPos p = BlockPos.parse(s);
            if (p != null) out.add(p);
        }
    }
}
