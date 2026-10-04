package com.colonysmp.colony;

import com.colonysmp.data.BuildingType;
import com.colonysmp.util.BlockPos;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.Bisected;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Builder's plans. Coordinates are local: lx to the right, lz forward (away from whoever ordered it,
 * so the door faces them), ly up from the first layer above the ground.
 */
public final class Blueprint {

    public enum Spec {
        AIR("open space", false), WOOD_WALL("wall blocks", false), STONE_WALL("stone wall blocks", false),
        ROOF("roof blocks", false), WINDOW("windows", false), DOOR("wooden door", false), IRON_DOOR("iron door", false),
        BED("bed", false), LADDER("ladder", false), TORCH("torch", true), FENCE("wooden fence", false),
        BARS("iron bars", false), BUTTON("button", true), FOUNDATION("foundation blocks", false),
        FARMLAND("farmland", false), WATER("water bucket", false);

        public final String label;
        public final boolean optional;

        Spec(String label, boolean optional) {
            this.label = label;
            this.optional = optional;
        }
    }

    public enum Role { NORMAL, BED_FOOT, DOOR_LOWER, ATTACHED }

    /** face: 0 forward, 1 right, 2 back (toward the door side), 3 left. */
    public record Step(int lx, int ly, int lz, Spec spec, Role role, int face) {}

    public final BuildingType type;
    public final List<Step> steps;
    public final int minLx, maxLx, minLz, maxLz, maxLy;
    /** Where registration starts once it's built (room interior, tower top, farm tile). */
    public final int ax, ay, az;

    private Blueprint(BuildingType type, List<Step> steps, int ax, int ay, int az) {
        this.type = type;
        this.steps = List.copyOf(steps);
        int a = Integer.MAX_VALUE, b = Integer.MIN_VALUE, c = Integer.MAX_VALUE, d = Integer.MIN_VALUE, e = 0;
        for (Step s : steps) {
            a = Math.min(a, s.lx);
            b = Math.max(b, s.lx);
            c = Math.min(c, s.lz);
            d = Math.max(d, s.lz);
            e = Math.max(e, s.ly);
        }
        minLx = a;
        maxLx = b;
        minLz = c;
        maxLz = d;
        maxLy = e;
        this.ax = ax;
        this.ay = ay;
        this.az = az;
    }

    private static final Map<BuildingType, Blueprint> ALL = new EnumMap<>(BuildingType.class);

    static {
        ALL.put(BuildingType.HOUSE, house());
        ALL.put(BuildingType.PRISON, prison());
        ALL.put(BuildingType.TOWER, tower());
        ALL.put(BuildingType.FARM, farm());
    }

    /** The plan for a type, or null (mines have no plan). */
    public static Blueprint of(BuildingType t) {
        return ALL.get(t);
    }

    // ───────────── the plans ─────────────

    private static Blueprint house() {
        List<Step> s = new ArrayList<>();
        for (int ly = 0; ly <= 2; ly++)
            for (int lx = -1; lx <= 1; lx++)
                for (int lz = 1; lz <= 3; lz++) s.add(new Step(lx, ly, lz, Spec.AIR, Role.NORMAL, 0));
        for (int ly = 0; ly <= 2; ly++) {
            for (int lx = -2; lx <= 2; lx++) {
                for (int lz = 0; lz <= 4; lz++) {
                    if (lx > -2 && lx < 2 && lz > 0 && lz < 4) continue;
                    if (lx == 0 && lz == 0 && ly <= 1) continue; // doorway
                    Spec sp = (ly == 1 && lz == 2 && (lx == -2 || lx == 2)) ? Spec.WINDOW : Spec.WOOD_WALL;
                    s.add(new Step(lx, ly, lz, sp, Role.NORMAL, 0));
                }
            }
        }
        for (int lx = -2; lx <= 2; lx++) for (int lz = 0; lz <= 4; lz++) s.add(new Step(lx, 3, lz, Spec.ROOF, Role.NORMAL, 0));
        s.add(new Step(0, 0, 0, Spec.DOOR, Role.DOOR_LOWER, 0));
        s.add(new Step(-1, 0, 2, Spec.BED, Role.BED_FOOT, 0));
        s.add(new Step(1, 0, 2, Spec.BED, Role.BED_FOOT, 0));
        s.add(new Step(0, 2, 3, Spec.TORCH, Role.ATTACHED, 2));
        return new Blueprint(BuildingType.HOUSE, s, 0, 1, 1);
    }

    private static Blueprint prison() {
        List<Step> s = new ArrayList<>();
        for (int ly = 0; ly <= 2; ly++)
            for (int lx = -1; lx <= 1; lx++)
                for (int lz = 1; lz <= 3; lz++) s.add(new Step(lx, ly, lz, Spec.AIR, Role.NORMAL, 0));
        for (int ly = 0; ly <= 2; ly++) {
            for (int lx = -2; lx <= 2; lx++) {
                for (int lz = 0; lz <= 4; lz++) {
                    if (lx > -2 && lx < 2 && lz > 0 && lz < 4) continue;
                    if (lx == 0 && lz == 0 && ly <= 1) continue;
                    Spec sp = (ly == 1 && lz == 2 && (lx == -2 || lx == 2)) ? Spec.BARS : Spec.STONE_WALL;
                    s.add(new Step(lx, ly, lz, sp, Role.NORMAL, 0));
                }
            }
        }
        for (int lx = -2; lx <= 2; lx++) for (int lz = 0; lz <= 4; lz++) s.add(new Step(lx, 3, lz, Spec.STONE_WALL, Role.NORMAL, 0));
        s.add(new Step(0, 0, 0, Spec.IRON_DOOR, Role.DOOR_LOWER, 0));
        s.add(new Step(0, 0, 2, Spec.BED, Role.BED_FOOT, 0));
        s.add(new Step(-1, 2, 3, Spec.TORCH, Role.ATTACHED, 2));
        s.add(new Step(1, 1, -1, Spec.BUTTON, Role.ATTACHED, 2));
        return new Blueprint(BuildingType.PRISON, s, 0, 1, 1);
    }

    private static Blueprint tower() {
        List<Step> s = new ArrayList<>();
        for (int ly = 0; ly <= 7; ly++) s.add(new Step(0, ly, 1, Spec.AIR, Role.NORMAL, 0));
        for (int ly = 0; ly <= 4; ly++) {
            for (int lx = -1; lx <= 1; lx++) {
                for (int lz = 0; lz <= 2; lz++) {
                    if (lx == 0 && lz == 1) continue;
                    if (lx == 0 && lz == 0 && ly <= 1) continue;
                    s.add(new Step(lx, ly, lz, Spec.STONE_WALL, Role.NORMAL, 0));
                }
            }
        }
        for (int lx = -2; lx <= 2; lx++) {
            for (int lz = -1; lz <= 3; lz++) {
                if (lx == 0 && lz == 1) continue;
                s.add(new Step(lx, 5, lz, Spec.WOOD_WALL, Role.NORMAL, 0));
            }
        }
        for (int ly = 6; ly <= 7; ly++)
            for (int lx = -1; lx <= 1; lx++)
                for (int lz = 0; lz <= 2; lz++) if (!(lx == 0 && lz == 1)) s.add(new Step(lx, ly, lz, Spec.AIR, Role.NORMAL, 0));
        for (int lx = -2; lx <= 2; lx++) {
            for (int lz = -1; lz <= 3; lz++) {
                if (lx == -2 || lx == 2 || lz == -1 || lz == 3) s.add(new Step(lx, 6, lz, Spec.FENCE, Role.NORMAL, 0));
            }
        }
        s.add(new Step(0, 0, 0, Spec.DOOR, Role.DOOR_LOWER, 0));
        for (int ly = 0; ly <= 5; ly++) s.add(new Step(0, ly, 1, Spec.LADDER, Role.ATTACHED, 2));
        s.add(new Step(1, 6, 2, Spec.TORCH, Role.NORMAL, 0));
        return new Blueprint(BuildingType.TOWER, s, -1, 6, 1);
    }

    private static Blueprint farm() {
        List<Step> s = new ArrayList<>();
        for (int lx = -4; lx <= 4; lx++) for (int lz = 0; lz <= 8; lz++) s.add(new Step(lx, 0, lz, Spec.AIR, Role.NORMAL, 0));
        for (int lx = -4; lx <= 4; lx++) {
            for (int lz = 0; lz <= 8; lz++) {
                if (lx == 0 && lz == 4) continue;
                s.add(new Step(lx, -1, lz, Spec.FARMLAND, Role.NORMAL, 0));
            }
        }
        s.add(new Step(0, -1, 4, Spec.WATER, Role.NORMAL, 0));
        return new Blueprint(BuildingType.FARM, s, 1, -1, 4);
    }

    // ───────────── geometry ─────────────

    public static BlockFace right(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public static BlockFace face(BlockFace forward, int code) {
        return switch (code & 3) {
            case 0 -> forward;
            case 1 -> right(forward);
            case 2 -> forward.getOppositeFace();
            default -> right(forward).getOppositeFace();
        };
    }

    public static BlockPos world(BlockPos origin, BlockFace f, int lx, int ly, int lz) {
        BlockFace r = right(f);
        return new BlockPos(origin.x() + r.getModX() * lx + f.getModX() * lz, origin.y() + ly, origin.z() + r.getModZ() * lx + f.getModZ() * lz);
    }

    public BlockPos pos(BlockPos origin, BlockFace f, Step s) {
        return world(origin, f, s.lx, s.ly, s.lz);
    }

    public BlockPos anchor(BlockPos origin, BlockFace f) {
        return world(origin, f, ax, ay, az);
    }

    /** World bounds of the footprint (min corner, max corner) including every layer. */
    public BlockPos[] bounds(BlockPos origin, BlockFace f) {
        BlockPos a = world(origin, f, minLx, 0, minLz), b = world(origin, f, maxLx, maxLy, maxLz);
        int minY = origin.y() + Math.min(0, minLy()), maxY = origin.y() + maxLy;
        return new BlockPos[]{
                new BlockPos(Math.min(a.x(), b.x()), minY, Math.min(a.z(), b.z())),
                new BlockPos(Math.max(a.x(), b.x()), maxY, Math.max(a.z(), b.z()))};
    }

    private int minLy() {
        int m = 0;
        for (Step s : steps) m = Math.min(m, s.ly);
        return m;
    }

    /** Material needs, for the order message: label -> count. */
    public Map<String, Integer> needs() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step s : steps) {
            if (s.spec == Spec.AIR) continue;
            if (s.spec == Spec.FARMLAND) {
                out.merge("farmland (dirt where the ground isn't soil)", 1, Integer::sum);
                continue;
            }
            out.merge(s.spec.label + (s.spec.optional ? " (optional)" : ""), 1, Integer::sum);
        }
        return out;
    }

    // ───────────── materials ─────────────

    private static final Map<Spec, List<Material>> CANDIDATES = new EnumMap<>(Spec.class);

    static {
        List<Material> planks = new ArrayList<>(Tag.PLANKS.getValues());
        List<Material> logs = new ArrayList<>();
        for (Material m : Tag.LOGS.getValues()) if (m.name().endsWith("_LOG") && !m.name().startsWith("STRIPPED")) logs.add(m);
        List<Material> stone = new ArrayList<>();
        for (String n : new String[]{"COBBLESTONE", "STONE_BRICKS", "STONE", "COBBLED_DEEPSLATE", "DEEPSLATE_BRICKS", "BRICKS",
                "MUD_BRICKS", "ANDESITE", "DIORITE", "GRANITE", "TUFF", "POLISHED_ANDESITE", "POLISHED_DIORITE", "POLISHED_GRANITE",
                "SANDSTONE", "RED_SANDSTONE", "MOSSY_COBBLESTONE", "BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "DEEPSLATE_TILES"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) stone.add(m);
        }
        List<Material> wood = new ArrayList<>(planks);
        wood.addAll(logs);
        wood.addAll(stone);
        List<Material> stoneFirst = new ArrayList<>(stone);
        stoneFirst.addAll(planks);
        stoneFirst.addAll(logs);
        CANDIDATES.put(Spec.WOOD_WALL, wood);
        CANDIDATES.put(Spec.ROOF, wood);
        CANDIDATES.put(Spec.STONE_WALL, stoneFirst);
        List<Material> glass = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isBlock()) continue;
            if (m == Material.GLASS_PANE || m == Material.GLASS || m.name().endsWith("_STAINED_GLASS_PANE") || m.name().endsWith("_STAINED_GLASS")) glass.add(m);
        }
        glass.sort((a, b) -> Boolean.compare(!a.name().endsWith("PANE"), !b.name().endsWith("PANE")));
        List<Material> window = new ArrayList<>(glass);
        window.addAll(wood);
        CANDIDATES.put(Spec.WINDOW, window);
        CANDIDATES.put(Spec.DOOR, new ArrayList<>(Tag.WOODEN_DOORS.getValues()));
        CANDIDATES.put(Spec.IRON_DOOR, List.of(Material.IRON_DOOR));
        CANDIDATES.put(Spec.BED, new ArrayList<>(Tag.BEDS.getValues()));
        CANDIDATES.put(Spec.LADDER, List.of(Material.LADDER));
        CANDIDATES.put(Spec.TORCH, List.of(Material.TORCH));
        CANDIDATES.put(Spec.FENCE, new ArrayList<>(Tag.WOODEN_FENCES.getValues()));
        List<Material> bars = new ArrayList<>(List.of(Material.IRON_BARS));
        bars.addAll(glass);
        bars.addAll(stoneFirst);
        CANDIDATES.put(Spec.BARS, bars);
        CANDIDATES.put(Spec.BUTTON, new ArrayList<>(Tag.BUTTONS.getValues()));
        List<Material> found = new ArrayList<>();
        for (String n : new String[]{"COBBLESTONE", "DIRT", "COBBLED_DEEPSLATE", "STONE"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) found.add(m);
        }
        Set<Material> f = new LinkedHashSet<>(found);
        f.addAll(stoneFirst);
        CANDIDATES.put(Spec.FOUNDATION, new ArrayList<>(f));
        CANDIDATES.put(Spec.FARMLAND, List.of(Material.DIRT));
        CANDIDATES.put(Spec.WATER, List.of(Material.WATER_BUCKET));
        CANDIDATES.put(Spec.AIR, List.of());
    }

    /** Items that can fill a spec, best first. */
    public static List<Material> candidates(Spec s) {
        return CANDIDATES.getOrDefault(s, List.of());
    }

    /** Is the block already what the plan wants here? */
    public static boolean satisfied(Spec s, Block b) {
        Material m = b.getType();
        return switch (s) {
            case AIR -> b.isPassable() && !b.isLiquid() || Tag.BEDS.isTagged(m) || Tag.DOORS.isTagged(m) || m == Material.LADDER;
            case WOOD_WALL, STONE_WALL, ROOF, FOUNDATION -> m.isOccluding() || m.isSolid() && !Tag.BEDS.isTagged(m) && !Tag.DOORS.isTagged(m);
            case WINDOW, BARS -> m.isSolid() || m == Material.IRON_BARS || m.name().contains("GLASS");
            case DOOR -> Tag.WOODEN_DOORS.isTagged(m);
            case IRON_DOOR -> m == Material.IRON_DOOR;
            case BED -> Tag.BEDS.isTagged(m);
            case LADDER -> m == Material.LADDER;
            case TORCH -> m == Material.TORCH || m == Material.WALL_TORCH || m.name().contains("LANTERN");
            case FENCE -> Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m);
            case BUTTON -> Tag.BUTTONS.isTagged(m);
            case FARMLAND -> m == Material.FARMLAND;
            case WATER -> m == Material.WATER;
        };
    }

    /** Ground the farm plan can till without needing dirt. */
    public static boolean tillable(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.DIRT_PATH || m == Material.COARSE_DIRT
                || m == Material.ROOTED_DIRT || m == Material.FARMLAND || m == Material.MYCELIUM || m == Material.PODZOL;
    }

    /** Block data to place for an item filling a step (null if the item can't be placed there). */
    public static BlockData data(Spec spec, Role role, Material item, BlockFace forward, int faceCode, boolean upperOrHead) {
        BlockFace face = face(forward, faceCode);
        switch (spec) {
            case DOOR, IRON_DOOR -> {
                BlockData d = item.createBlockData();
                if (d instanceof Door door) {
                    door.setFacing(forward);
                    door.setHalf(upperOrHead ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
                    door.setHinge(Door.Hinge.LEFT);
                    door.setOpen(false);
                }
                return d;
            }
            case BED -> {
                BlockData d = item.createBlockData();
                if (d instanceof Bed bed) {
                    bed.setPart(upperOrHead ? Bed.Part.HEAD : Bed.Part.FOOT);
                    bed.setFacing(forward);
                }
                return d;
            }
            case LADDER -> {
                BlockData d = Material.LADDER.createBlockData();
                if (d instanceof Directional dir) dir.setFacing(face);
                return d;
            }
            case TORCH -> {
                if (role == Role.ATTACHED) {
                    BlockData d = Material.WALL_TORCH.createBlockData();
                    if (d instanceof Directional dir) dir.setFacing(face);
                    return d;
                }
                return Material.TORCH.createBlockData();
            }
            case BUTTON -> {
                BlockData d = item.createBlockData();
                if (d instanceof FaceAttachable fa) fa.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                if (d instanceof Directional dir) dir.setFacing(face);
                return d;
            }
            case WATER -> {
                return Material.WATER.createBlockData();
            }
            case FARMLAND -> {
                return Material.FARMLAND.createBlockData();
            }
            default -> {
                if (!item.isBlock()) return null;
                return item.createBlockData();
            }
        }
    }
}
