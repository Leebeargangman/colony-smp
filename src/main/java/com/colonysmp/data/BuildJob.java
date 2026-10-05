package com.colonysmp.data;

import com.colonysmp.util.BlockPos;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

/** A blueprint the Builders have been ordered to construct. */
public final class BuildJob {

    public final String id;
    /** A standard plan, or null for a copied structure ({@link #custom}). */
    public final BuildingType type;
    /** Name of the colony's copied blueprint being built, or null. */
    public final String custom;
    public final BlockPos origin;
    public final BlockFace facing;
    public int step;
    public final long createdAt;
    /** What the builder is waiting for (transient display). */
    public transient String waitingFor;
    public transient long lastWarn;

    public BuildJob(String id, BuildingType type, BlockPos origin, BlockFace facing, long createdAt) {
        this(id, type, null, origin, facing, createdAt);
    }

    public BuildJob(String id, BuildingType type, String custom, BlockPos origin, BlockFace facing, long createdAt) {
        this.id = id;
        this.type = type;
        this.custom = custom;
        this.origin = origin;
        this.facing = facing;
        this.createdAt = createdAt;
    }

    public String label() {
        return custom != null ? custom : type.display;
    }

    public void save(ConfigurationSection s) {
        s.set("type", type == null ? null : type.name());
        s.set("custom", custom);
        s.set("origin", origin.toString());
        s.set("facing", facing.name());
        s.set("step", step);
        s.set("created", createdAt);
    }

    public static BuildJob load(String id, ConfigurationSection s) {
        BuildingType t = BuildingType.parse(s.getString("type"));
        String custom = s.getString("custom");
        BlockPos o = BlockPos.parse(s.getString("origin"));
        if ((t == null && custom == null) || o == null) return null;
        BlockFace f;
        try {
            f = BlockFace.valueOf(s.getString("facing", "NORTH"));
        } catch (IllegalArgumentException e) {
            f = BlockFace.NORTH;
        }
        BuildJob j = new BuildJob(id, custom != null ? null : t, custom, o, f, s.getLong("created"));
        j.step = s.getInt("step");
        return j;
    }
}
