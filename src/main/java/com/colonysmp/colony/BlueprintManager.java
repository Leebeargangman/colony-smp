package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.BuildJob;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.data.CustomBlueprint;
import com.colonysmp.npc.Farming;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The Colony Blueprint Book: outlines, registering buildings and ordering construction. */
public final class BlueprintManager implements Listener {

    public record Result(Building building, String error) {
        static Result ok(Building b) {
            return new Result(b, null);
        }

        static Result fail(String e) {
            return new Result(null, e);
        }
    }

    private static final Color GOOD = Color.fromRGB(90, 255, 120), BAD = Color.fromRGB(255, 70, 70),
            DOOR = Color.fromRGB(255, 220, 60), BED = Color.fromRGB(230, 40, 60), WATER = Color.fromRGB(60, 120, 255),
            LADDER = Color.fromRGB(150, 100, 50), KNOWN = Color.fromRGB(80, 220, 255), QUEUED = Color.fromRGB(255, 170, 60);

    private final ColonySMP plugin;
    private final Map<UUID, BuildingType> mode = new HashMap<>();
    /** A copied structure picked in the book (its key), instead of a standard plan. */
    private final Map<UUID, String> custom = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public BlueprintManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public BuildingType mode(Player p) {
        return mode.getOrDefault(p.getUniqueId(), BuildingType.HOUSE);
    }

    /** The copied structure selected in the book, or null for a standard plan. */
    public CustomBlueprint customMode(Player p) {
        String k = custom.get(p.getUniqueId());
        if (k == null) return null;
        Colony c = plugin.colonies().of(p);
        CustomBlueprint cb = c == null ? null : c.blueprints.get(k);
        if (cb == null) custom.remove(p.getUniqueId());
        return cb;
    }

    /** Selects a copied structure in the player's book. */
    public void selectCustom(Player p, CustomBlueprint cb) {
        custom.put(p.getUniqueId(), CustomBlueprint.key(cb.name));
    }

    /** Right-click: the next plan - the standard ones, then the colony's copies. */
    private String nextMode(Player p) {
        Colony c = plugin.colonies().of(p);
        List<String> keys = c == null ? List.of() : new ArrayList<>(c.blueprints.keySet());
        CustomBlueprint cur = customMode(p);
        if (cur != null) {
            int i = keys.indexOf(CustomBlueprint.key(cur.name));
            if (i >= 0 && i + 1 < keys.size()) {
                CustomBlueprint nx = c.blueprints.get(keys.get(i + 1));
                selectCustom(p, nx);
                return "<white>" + Text.esc(nx.name) + "</white> <dark_gray>- <gray>your copy, " + nx.size() + ", " + nx.solid() + " blocks";
            }
            custom.remove(p.getUniqueId());
            mode.put(p.getUniqueId(), BuildingType.HOUSE);
            return "<white>" + BuildingType.HOUSE.display + "</white> <dark_gray>- <gray>" + BuildingType.HOUSE.description;
        }
        BuildingType t = mode(p);
        if (t.ordinal() == BuildingType.values().length - 1 && !keys.isEmpty()) {
            CustomBlueprint nx = c.blueprints.get(keys.get(0));
            selectCustom(p, nx);
            return "<white>" + Text.esc(nx.name) + "</white> <dark_gray>- <gray>your copy, " + nx.size() + ", " + nx.solid() + " blocks";
        }
        BuildingType next = t.next();
        mode.put(p.getUniqueId(), next);
        return "<white>" + next.display + "</white> <dark_gray>- <gray>" + next.description;
    }

    public static BlockFace cardinal(float yaw) {
        int i = Math.round(((yaw % 360) + 360) % 360 / 90f) & 3;
        return switch (i) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // ───────────── using the book ─────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.BOOK)) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 200) {
            e.setCancelled(true);
            return;
        }
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            lastClick.put(p.getUniqueId(), now);
            if (p.isSneaking()) {
                if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                    register(p, e.getClickedBlock(), e.getBlockFace());
                } else {
                    Text.send(p, "<gray>Shift + Right-Click a <white>block</white> to register the building there.");
                }
            } else {
                String what = nextMode(p);
                Fx.sound(p, "minecraft:item.book.page_turn", 1f, 1.1f);
                Text.send(p, "<yellow>Blueprint:</yellow> " + what);
            }
        } else if ((a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) && p.isSneaking()) {
            e.setCancelled(true);
            lastClick.put(p.getUniqueId(), now);
            order(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mode.remove(e.getPlayer().getUniqueId());
        custom.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    // ───────────── registering existing buildings ─────────────

    public void register(Player p, Block clicked, BlockFace face) {
        Colony c = plugin.colonies().at(clicked);
        if (c == null || !c.isMember(p.getUniqueId())) {
            Text.send(p, "<red>You can only register buildings inside your own colony.");
            return;
        }
        if (customMode(p) != null) {
            Text.send(p, "<yellow>Copies aren't registered: right-click to turn the book back to a standard plan (House, Farm...), then register.");
            return;
        }
        BuildingType t = mode(p);
        Result r = switch (t) {
            case HOUSE, PRISON, SCHOOL -> {
                Block start = clicked.getRelative(face);
                if (!RoomScanner.open(start)) start = clicked.getRelative(BlockFace.UP);
                yield registerRoom(c, start, t);
            }
            case FARM -> registerFarm(c, clicked);
            case TOWER -> registerTower(c, clicked);
            case MINE -> registerMine(c, clicked, cardinal(p.getLocation().getYaw()));
        };
        if (r.error != null) {
            Text.send(p, "<red>" + r.error);
            Fx.sound(p, "minecraft:block.note_block.bass", 1f, 0.6f);
            return;
        }
        Building b = r.building;
        Fx.sound(p, "minecraft:entity.villager.work_cartographer", 1f, 1f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, clicked.getLocation().add(0.5, 1.2, 0.5), 30, 0.6, 0.4, 0.6, 0);
        String extra = switch (b.type) {
            case HOUSE -> " with <white>" + b.beds.size() + "</white> bed" + (b.beds.size() == 1 ? "" : "s");
            case PRISON -> " holding <white>" + b.beds.size() + "</white> prisoner" + (b.beds.size() == 1 ? "" : "s");
            case FARM -> " with <white>" + b.tiles.size() + "</white> tiles";
            case SCHOOL -> " with <white>" + b.tiles.size() + "</white> lectern" + (b.tiles.size() == 1 ? "" : "s") + " and <white>" + b.progress
                    + "</white> bookshel" + (b.progress == 1 ? "f" : "ves") + ". Assign a <white>Teacher</white>; children and <white>Students</white> come here to learn";
            case MINE -> " heading <white>" + b.facing.name().toLowerCase() + "</white>";
            default -> "";
        };
        Text.send(p, "<green>Registered <white>" + b.label() + "</white>" + extra + ".");
        plugin.sim().assignBeds(c);
        plugin.quests().check(c);
        plugin.requestSave();
    }

    /** What kind of room this is: an iron door makes a prison cell, a lectern a school, otherwise a house. */
    public static BuildingType roomType(RoomScanner.Room r) {
        if (!r.ironDoors().isEmpty()) return BuildingType.PRISON;
        if (!r.lecterns().isEmpty()) return BuildingType.SCHOOL;
        return BuildingType.HOUSE;
    }

    private static boolean isRoom(BuildingType t) {
        return t == BuildingType.HOUSE || t == BuildingType.PRISON || t == BuildingType.SCHOOL;
    }

    public Result registerRoom(Colony c, Block start, BuildingType wanted) {
        RoomScanner.Room r = RoomScanner.scan(start);
        if (!r.enclosed()) return Result.fail(r.error());
        for (BlockPos p : r.interior()) {
            if (!c.region.contains(p)) return Result.fail("The room crosses your claim border.");
        }
        BuildingType t = roomType(r);
        switch (t) {
            case PRISON -> {
                if (r.beds().isEmpty()) return Result.fail("A prison cell needs at least one bed inside.");
                if (r.smallSide() < 2) return Result.fail("Too small: prison cells need at least 2x2 of floor inside.");
            }
            case SCHOOL -> {
                if (r.doors().isEmpty()) return Result.fail("A school needs a wooden door.");
                if (r.smallSide() < 3) return Result.fail("Too small: schools need at least 3x3 of floor inside (5x5 is better).");
            }
            default -> {
                if (r.beds().isEmpty()) return Result.fail("A house needs at least one bed inside (a lectern makes it a school, an iron door a prison cell).");
                if (r.doors().isEmpty()) return Result.fail("A house needs a wooden door (an iron door makes it a prison cell).");
                if (r.smallSide() < 3) return Result.fail("Too small: houses need at least 3x3 of floor inside (5x5 with the walls).");
            }
        }
        // re-registering a room updates it instead of adding a copy
        Building existing = null;
        for (Building b : c.buildings.values()) {
            if (!isRoom(b.type)) continue;
            if ((b.anchor != null && r.interior().contains(b.anchor)) || overlapsBeds(b, r.beds())) {
                existing = b;
                break;
            }
        }
        Building b;
        if (existing != null && existing.type == t) b = existing;
        else {
            if (existing != null) c.buildings.remove(existing.id);
            b = new Building(c.newBuildingId(), t);
        }
        b.min = r.min();
        b.max = r.max();
        b.anchor = BlockPos.of(start);
        b.beds.clear();
        b.tiles.clear();
        if (t != BuildingType.SCHOOL) {
            // a bed can only belong to one building
            for (BlockPos bed : r.beds()) {
                boolean taken = false;
                for (Building o : c.buildings.values()) if (o != b && o.beds.contains(bed)) taken = true;
                if (!taken) b.beds.add(bed);
            }
            if (b.beds.isEmpty()) return Result.fail("Those beds already belong to another registered building.");
        } else {
            b.tiles.addAll(r.lecterns()); // a school remembers its lecterns (where the teacher stands)
            b.progress = r.bookshelves();
        }
        b.doors.clear();
        b.doors.addAll(t == BuildingType.PRISON ? r.ironDoors() : r.doors());
        c.buildings.put(b.id, b);
        return Result.ok(b);
    }

    private static boolean overlapsBeds(Building b, List<BlockPos> beds) {
        for (BlockPos p : beds) if (b.beds.contains(p)) return true;
        return false;
    }

    public Result registerFarm(Colony c, Block clicked) {
        return registerFarm(c, clicked, Set.of());
    }

    /** {@code keep}: tiles of the farm being re-scanned, which still count while they're trampled back to dirt. */
    private Result registerFarm(Colony c, Block clicked, Set<BlockPos> keep) {
        Block tile = clicked;
        if (!isSoil(tile.getType()) && !keep.contains(BlockPos.of(tile))) tile = clicked.getRelative(BlockFace.DOWN);
        if (!isSoil(tile.getType()) && !keep.contains(BlockPos.of(tile))) return Result.fail("Shift + Right-Click farmland (or a crop growing on it) to register a farm.");
        World w = clicked.getWorld();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> tiles = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        BlockPos s = BlockPos.of(tile);
        q.add(s);
        seen.add(s);
        BlockFace[] dirs = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        while (!q.isEmpty() && seen.size() < 900) {
            BlockPos p = q.poll();
            Block b = p.block(w);
            if ((isSoil(b.getType()) || keep.contains(p) && Farming.tillable(b)) && c.region.contains(p)) tiles.add(p);
            for (BlockFace f : dirs) {
                BlockPos n = p.relative(f);
                if (seen.contains(n) || !n.loaded(w) || !c.region.contains(n)) continue;
                Block nb = n.block(w);
                Material m = nb.getType();
                if (isSoil(m) || m == Material.WATER || keep.contains(n) && Farming.tillable(nb)) {
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        if (tiles.size() < 9) return Result.fail("Farms need at least 9 connected farmland tiles (this one has " + tiles.size() + "). A 9x9 field around a water block is ideal.");
        if (tiles.size() > 600) tiles = tiles.subList(0, 600);
        Building existing = null;
        Set<BlockPos> set = new HashSet<>(tiles);
        for (Building b : c.buildings(BuildingType.FARM)) {
            for (BlockPos t : b.tiles) {
                if (set.contains(t)) {
                    existing = b;
                    break;
                }
            }
            if (existing != null) break;
        }
        Building b = existing != null ? existing : new Building(c.newBuildingId(), BuildingType.FARM);
        b.tiles.clear();
        b.tiles.addAll(tiles);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos t : tiles) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
            minY = Math.min(minY, t.y());
            maxY = Math.max(maxY, t.y());
        }
        b.min = new BlockPos(minX, minY, minZ);
        b.max = new BlockPos(maxX, maxY + 1, maxZ);
        b.anchor = tiles.get(0);
        c.buildings.put(b.id, b);
        return Result.ok(b);
    }

    public static boolean isSoil(Material m) {
        return m == Material.FARMLAND || m == Material.SOUL_SAND;
    }

    public Result registerTower(Colony c, Block clicked) {
        Block stand = clicked.getRelative(BlockFace.UP);
        if (!stand.isPassable() || !stand.getRelative(BlockFace.UP).isPassable()) return Result.fail("A guard needs two blocks of space to stand on top of the tower.");
        if (!c.region.contains(clicked.getX(), clicked.getZ())) return Result.fail("The tower must be inside your claim.");
        int h = column(clicked);
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            h = Math.max(h, column(clicked.getRelative(f)));
        }
        if (h < 5) return Result.fail("Too low: Shift + Right-Click the top of a tower at least 5 blocks tall (this is " + h + ").");
        BlockPos sp = BlockPos.of(stand);
        for (Building b : c.buildings(BuildingType.TOWER)) {
            if (b.anchor != null && b.anchor.distSq(sp) < 9) {
                b.anchor = sp;
                return Result.ok(b);
            }
        }
        Building b = new Building(c.newBuildingId(), BuildingType.TOWER);
        b.anchor = sp;
        b.min = new BlockPos(sp.x() - 2, sp.y() - h, sp.z() - 2);
        b.max = new BlockPos(sp.x() + 2, sp.y() + 1, sp.z() + 2);
        c.buildings.put(b.id, b);
        return Result.ok(b);
    }

    private static int column(Block top) {
        int h = 0;
        Block b = top;
        while (h < 16 && !b.getType().isAir() && !b.isLiquid()) {
            h++;
            b = b.getRelative(BlockFace.DOWN);
        }
        return h;
    }

    public Result registerMine(Colony c, Block clicked, BlockFace facing) {
        if (!MineableBlocks.natural(clicked.getType())) return Result.fail("Mines must start in natural ground: stone, dirt, grass, sand or gravel.");
        if (!c.region.contains(clicked.getX(), clicked.getZ())) return Result.fail("The mine must be inside your claim.");
        BlockPos at = BlockPos.of(clicked);
        for (Building b : c.buildings(BuildingType.MINE)) {
            if (b.anchor != null && b.anchor.distSq(at) < 4) {
                if (b.facing != facing) b.progress = 0;
                b.facing = facing;
                b.exhausted = false;
                return Result.ok(b);
            }
        }
        if (c.buildings(BuildingType.MINE).size() >= plugin.settings().maxMines) {
            return Result.fail("Your colony already has " + plugin.settings().maxMines + " mines. Remove one with /colony buildings first.");
        }
        Building b = new Building(c.newBuildingId(), BuildingType.MINE);
        b.anchor = at;
        b.min = at;
        b.max = at;
        b.facing = facing;
        c.buildings.put(b.id, b);
        return Result.ok(b);
    }

    // ───────────── ordering construction ─────────────

    /** Where the preview sits for this player: origin and facing, or null. */
    private Placement placement(Player p) {
        BuildingType t = mode(p);
        Block target = p.getTargetBlockExact(32);
        if (target == null) return null;
        BlockFace f = cardinal(p.getLocation().getYaw());
        BlockPos origin = BlockPos.of(target.getRelative(BlockFace.UP));
        CustomBlueprint cb = customMode(p);
        if (cb != null) return new Placement(null, cb, origin, f, Blueprint.custom(cb));
        if (t == BuildingType.MINE) return new Placement(t, null, BlockPos.of(target), f, null);
        return new Placement(t, null, origin, f, Blueprint.of(t));
    }

    private record Placement(BuildingType type, CustomBlueprint copy, BlockPos origin, BlockFace facing, Blueprint bp) {
        String label() {
            return copy != null ? copy.name : type.display;
        }
    }

    /** Why this plan can't go here, or null. */
    private String check(Colony c, Placement pl, World w) {
        if (c == null) return "Outside your colony";
        if (pl.bp == null) return null;
        BlockPos[] bb = pl.bp.bounds(pl.origin, pl.facing);
        for (int x = bb[0].x(); x <= bb[1].x(); x++) {
            for (int z = bb[0].z(); z <= bb[1].z(); z++) {
                if (!c.region.contains(x, z)) return "Doesn't fit inside your claim";
            }
        }
        if (inside(bb, c.core) || inside(bb, c.chest)) return "Overlaps the Town Hall";
        for (Building b : c.buildings.values()) {
            if (b.min == null || b.max == null || b.type == BuildingType.MINE) continue;
            if (intersects(bb[0], bb[1], b.min, b.max)) return "Overlaps " + b.label();
        }
        for (BuildJob j : c.buildQueue) {
            Blueprint o = Blueprint.of(c, j);
            if (o == null) continue;
            BlockPos[] ob = o.bounds(j.origin, j.facing);
            if (intersects(bb[0], bb[1], ob[0], ob[1])) return "Overlaps a building already ordered";
        }
        return null;
    }

    private static boolean inside(BlockPos[] bb, BlockPos p) {
        return p != null && p.x() >= bb[0].x() && p.x() <= bb[1].x() && p.y() >= bb[0].y() - 1 && p.y() <= bb[1].y() && p.z() >= bb[0].z() && p.z() <= bb[1].z();
    }

    private static boolean intersects(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
        return a1.x() <= b2.x() && a2.x() >= b1.x() && a1.y() <= b2.y() && a2.y() >= b1.y() && a1.z() <= b2.z() && a2.z() >= b1.z();
    }

    public void order(Player p) {
        Placement pl = placement(p);
        if (pl == null) {
            Text.send(p, "<red>Look at the ground where it should be built.");
            return;
        }
        if (pl.copy == null && pl.type == BuildingType.MINE) {
            Text.send(p, "<yellow>Mines aren't built: Shift + Right-Click natural ground to register a Mine Entrance facing the way you look.");
            return;
        }
        Colony c = plugin.colonies().at(p.getWorld().getName(), pl.origin.x(), pl.origin.z());
        if (c == null || !c.isMember(p.getUniqueId())) {
            Text.send(p, "<red>You can only order construction inside your own colony.");
            return;
        }
        String err = check(c, pl, p.getWorld());
        if (err != null) {
            Text.send(p, "<red>Can't build here: " + err + ".");
            return;
        }
        if (c.buildQueue.size() >= 8) {
            Text.send(p, "<red>The Builders already have 8 orders. Cancel one with /colony buildings.");
            return;
        }
        BuildJob j = new BuildJob(String.valueOf(c.nextBuildingId++), pl.type, pl.copy == null ? null : pl.copy.name, pl.origin, pl.facing, System.currentTimeMillis());
        c.buildQueue.add(j);
        Fx.sound(p, "minecraft:entity.villager.work_mason", 1f, 1f);
        Text.send(p, "<green>Ordered " + (pl.copy != null ? "a copy of <white>" + Text.esc(pl.copy.name) : "a <white>" + pl.type.display)
                + "</white>. The Builders will carry from the State Chest:");
        List<Map.Entry<String, Integer>> needs = new ArrayList<>(pl.bp.needs().entrySet());
        needs.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < needs.size() && i < 14; i++) {
            Text.raw(p, "<dark_gray>  • <white>" + needs.get(i).getValue() + "x</white> <gray>" + needs.get(i).getKey());
        }
        if (needs.size() > 14) Text.raw(p, "<dark_gray>  • <gray>...and " + (needs.size() - 14) + " more kinds (see /colony blueprints info " + (pl.copy != null ? Text.esc(pl.copy.name) : "") + ")");
        if (c.citizens.values().stream().noneMatch(ct -> ct.job == com.colonysmp.data.Job.BUILDER && ct.status == com.colonysmp.data.Status.CITIZEN)) {
            Text.send(p, "<yellow>You have no Builder! Assign one from the Town Hall (right-click a citizen).");
        }
        plugin.requestSave();
    }

    // ───────────── outlines ─────────────

    /** Every 5 ticks: outline the selected blueprint for players holding the book. */
    public void render() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!Items.is(p.getInventory().getItemInMainHand(), Items.BOOK)) continue;
            Colony c = plugin.colonies().at(p.getLocation());
            if (c != null && c.isMember(p.getUniqueId())) drawKnown(p, c);
            Placement pl = placement(p);
            if (pl == null) {
                CustomBlueprint cb = customMode(p);
                Text.bar(p, "<yellow>" + (cb != null ? Text.esc(cb.name) : mode(p).display) + " <dark_gray>|</dark_gray> <gray>look at the ground <dark_gray>|</dark_gray> <white>Right-Click</white><gray>: next blueprint");
                continue;
            }
            Colony here = plugin.colonies().at(p.getWorld().getName(), pl.origin.x(), pl.origin.z());
            boolean mine = here != null && here.isMember(p.getUniqueId());
            if (pl.copy == null && pl.type == BuildingType.MINE) {
                drawMine(p, pl, mine);
                Text.bar(p, "<yellow>Mine Entrance <dark_gray>|</dark_gray> <gray>faces where you look <dark_gray>|</dark_gray> <white>Shift+Right-Click</white> <gray>natural ground to register");
                continue;
            }
            String err = mine ? check(here, pl, p.getWorld()) : "Outside your colony";
            draw(p, pl, err == null);
            Text.bar(p, "<yellow>" + Text.esc(pl.label()) + "</yellow> " + (err == null ? "<green>✔ fits" : "<red>✘ " + err)
                    + " <dark_gray>|</dark_gray> <gold>Shift+Left-Click</gold><gray>: order"
                    + (pl.copy == null ? " <dark_gray>|</dark_gray> <white>Shift+Right-Click</white><gray>: register existing" : " <dark_gray>|</dark_gray> <gray>turn: face another way"));
        }
    }

    private void draw(Player p, Placement pl, boolean ok) {
        Blueprint bp = pl.bp;
        BlockPos[] bb = bp.bounds(pl.origin, pl.facing);
        Color c = ok ? GOOD : BAD;
        boolean flat = pl.type == BuildingType.FARM;
        double y = bb[0].y() + 0.05;
        double x1 = bb[0].x(), z1 = bb[0].z(), x2 = bb[1].x() + 1, z2 = bb[1].z() + 1;
        Fx.line(p, x1, y, z1, x2, y, z1, c, 0.5, 1.1f);
        Fx.line(p, x1, y, z2, x2, y, z2, c, 0.5, 1.1f);
        Fx.line(p, x1, y, z1, x1, y, z2, c, 0.5, 1.1f);
        Fx.line(p, x2, y, z1, x2, y, z2, c, 0.5, 1.1f);
        if (!flat) {
            double top = bb[1].y() + 1;
            Fx.line(p, x1, y, z1, x1, top, z1, c, 0.5, 0.9f);
            Fx.line(p, x2, y, z1, x2, top, z1, c, 0.5, 0.9f);
            Fx.line(p, x1, y, z2, x1, top, z2, c, 0.5, 0.9f);
            Fx.line(p, x2, y, z2, x2, top, z2, c, 0.5, 0.9f);
        }
        for (Blueprint.Step s : bp.steps) {
            Color mark = switch (s.spec()) {
                case DOOR, IRON_DOOR -> DOOR;
                case BED -> BED;
                case WATER -> WATER;
                case LADDER -> LADDER;
                default -> null;
            };
            if (mark == null) continue;
            BlockPos w = bp.pos(pl.origin, pl.facing, s);
            Fx.dust(p, w.x() + 0.5, w.y() + 0.3, w.z() + 0.5, mark, 1.6f);
            if (s.spec() == Blueprint.Spec.BED) {
                BlockPos head = w.relative(pl.facing);
                Fx.dust(p, head.x() + 0.5, head.y() + 0.3, head.z() + 0.5, mark, 1.6f);
            }
        }
    }

    private void drawMine(Player p, Placement pl, boolean ok) {
        Color c = ok ? GOOD : BAD;
        BlockPos o = pl.origin;
        BlockFace f = pl.facing;
        for (int i = 0; i < 6; i++) {
            double x = o.x() + 0.5 + f.getModX() * i, z = o.z() + 0.5 + f.getModZ() * i;
            double y = o.y() + 1.05 - i;
            Fx.dust(p, x, y, z, c, 1.4f);
        }
        Fx.box(p, o.x(), o.y(), o.z(), o.x(), o.y(), o.z(), c, 0.25, 1f, true);
    }

    private void drawKnown(Player p, Colony c) {
        double px = p.getX(), pz = p.getZ();
        for (Building b : c.buildings.values()) {
            if (b.min == null || b.max == null) continue;
            if (Math.abs(b.min.x() - px) > 40 || Math.abs(b.min.z() - pz) > 40) continue;
            if (b.type == BuildingType.TOWER && b.anchor != null) {
                Fx.dust(p, b.anchor.x() + 0.5, b.anchor.y() + 0.5, b.anchor.z() + 0.5, KNOWN, 1.5f);
                continue;
            }
            if (b.type == BuildingType.MINE && b.anchor != null) {
                Fx.dust(p, b.anchor.x() + 0.5, b.anchor.y() + 1.2, b.anchor.z() + 0.5, KNOWN, 1.5f);
                continue;
            }
            Fx.box(p, b.min.x(), b.min.y(), b.min.z(), b.max.x(), b.type == BuildingType.FARM ? b.min.y() : b.max.y(), b.max.z(), KNOWN, 1.0, 0.8f, b.type != BuildingType.FARM);
        }
        for (BuildJob j : c.buildQueue) {
            Blueprint bp = Blueprint.of(c, j);
            if (bp == null) continue;
            BlockPos[] bb = bp.bounds(j.origin, j.facing);
            if (Math.abs(bb[0].x() - px) > 40 || Math.abs(bb[0].z() - pz) > 40) continue;
            Fx.box(p, bb[0].x(), bb[0].y(), bb[0].z(), bb[1].x(), bb[1].y(), bb[1].z(), QUEUED, 1.0, 0.8f, true);
        }
    }

    // ───────────── nightly upkeep ─────────────

    /** Re-checks registered buildings (beds removed, walls broken, farms dug up). */
    public void validate(Colony c) {
        World w = c.world();
        if (w == null) return;
        List<String> lost = new ArrayList<>();
        Iterator<Building> it = c.buildings.values().iterator();
        List<Building> changedType = new ArrayList<>();
        Map<Building, BlockPos> farms = new java.util.LinkedHashMap<>();
        while (it.hasNext()) {
            Building b = it.next();
            if (b.anchor == null || !b.anchor.loaded(w)) continue;
            switch (b.type) {
                case HOUSE, PRISON, SCHOOL -> {
                    RoomScanner.Room r = RoomScanner.scan(b.anchor.block(w));
                    if (!r.enclosed()) {
                        for (BlockPos bed : b.beds) {
                            if (!bed.loaded(w)) continue;
                            r = RoomScanner.scan(bed.block(w).getRelative(BlockFace.UP));
                            if (r.enclosed()) break;
                        }
                    }
                    BuildingType now = r.enclosed() ? roomType(r) : null;
                    if (now == null || (now != BuildingType.SCHOOL && r.beds().isEmpty())) {
                        lost.add(b.label());
                        it.remove();
                        continue;
                    }
                    if (now != b.type) {
                        changedType.add(b);
                        it.remove();
                        continue;
                    }
                    b.min = r.min();
                    b.max = r.max();
                    b.beds.clear();
                    b.tiles.clear();
                    if (now == BuildingType.SCHOOL) {
                        b.tiles.addAll(r.lecterns());
                        b.progress = r.bookshelves();
                    } else {
                        b.beds.addAll(r.beds());
                    }
                    b.doors.clear();
                    b.doors.addAll(now == BuildingType.PRISON ? r.ironDoors() : r.doors());
                }
                case FARM -> {
                    BlockPos start = null;
                    for (BlockPos t : b.tiles) {
                        if (t.loaded(w) && isSoil(t.block(w).getType())) {
                            start = t;
                            break;
                        }
                    }
                    // a fully trampled field still counts: the farmers re-till it
                    if (start == null) {
                        for (BlockPos t : b.tiles) {
                            if (t.loaded(w) && Farming.tillable(t.block(w))) {
                                start = t;
                                break;
                            }
                        }
                    }
                    if (start == null) {
                        lost.add(b.label());
                        it.remove();
                        continue;
                    }
                    farms.put(b, start);
                }
                case TOWER -> {
                    Block s = b.anchor.block(w);
                    if (!s.isPassable() || !s.getRelative(BlockFace.UP).isPassable()) {
                        lost.add(b.label());
                        it.remove();
                    }
                }
                default -> {
                }
            }
        }
        for (Building b : changedType) {
            Result r = registerRoom(c, b.anchor.block(w), b.type);
            if (r.error != null) lost.add(b.label());
        }
        // farms are re-scanned so tiles players added or dug up are picked up, keeping their ids
        for (Map.Entry<Building, BlockPos> e : farms.entrySet()) {
            Building old = e.getKey();
            c.buildings.remove(old.id);
            Result r = registerFarm(c, e.getValue().block(w), new HashSet<>(old.tiles));
            if (r.error != null) {
                lost.add(old.label());
                continue;
            }
            if (!r.building.id.equals(old.id)) {
                c.buildings.remove(r.building.id);
                Building nb = new Building(old.id, BuildingType.FARM);
                nb.tiles.addAll(r.building.tiles);
                nb.min = r.building.min;
                nb.max = r.building.max;
                nb.anchor = r.building.anchor;
                c.buildings.put(old.id, nb);
            }
        }
        if (!lost.isEmpty()) {
            for (var p : c.onlineMembers()) {
                Text.send(p, "<yellow>Unregistered (no longer valid): <white>" + String.join(", ", lost) + "</white>. Fix and register them again with the Blueprint Book.");
            }
        }
    }

    /** True if a bed head is still a bed. */
    public static boolean bedExists(World w, BlockPos bed) {
        return bed != null && bed.loaded(w) && Tag.BEDS.isTagged(bed.block(w).getType());
    }
}
