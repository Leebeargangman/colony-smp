package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.colony.RoomScanner;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Schools: a Teacher stands at the lectern and gives a lesson every 30 seconds to the children and Students in
 * the room. A book from the State Chest (one per school per day, carried over by the teacher) and bookshelves
 * make lessons better. Education speeds up all work, makes citizens happier and opens skilled jobs.
 */
public final class Schooling {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final ColonySMP plugin;
    private final NpcManager m;

    Schooling(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    static boolean book(ItemStack it) {
        return it.getType() == Material.BOOK && Storage.plain(it);
    }

    // ───────────── teachers ─────────────

    void teach(Npc n, Colony col, long now) {
        Building school = pick(n, col, "teach:", Job.TEACHER, 2);
        if (school == null) {
            m.brain().idle(n, col, now, "No School registered (an enclosed room with a door and a lectern)");
            return;
        }
        World w = n.body.getWorld();
        long today = NpcManager.day(w);
        // a book for today's lessons, carried over from the State Chest
        if (school.bookDay != today && n.c.carried(Schooling::book) == 0 && col.storage.has(Schooling::book)) {
            if (m.fetch(n, col, Schooling::book, 2, "a book for the lessons") == NpcManager.Fetch.FETCHING) return;
        }
        Location spot = lecternSpot(w, school);
        if (spot == null) spot = interiorSpot(w, school, n);
        if (spot == null) {
            m.brain().idle(n, col, now, "Can't get into " + school.label());
            return;
        }
        if (!n.mover.near(spot, 1.2)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 0.9);
            n.activity = "Walking to " + school.label();
            return;
        }
        n.mover.stop();
        if (school.min != null && school.max != null) {
            CitizenBrain.face(n, new Location(w, (school.min.x() + school.max.x()) / 2.0 + 0.5, spot.getY(), (school.min.z() + school.max.z()) / 2.0 + 0.5));
        }
        int students = present(col, school, false).size();
        n.activity = students == 0 ? "Waiting for students at " + school.label() : "Teaching " + students + " student" + (students == 1 ? "" : "s") + " at " + school.label();
    }

    // ───────────── students ─────────────

    /** Goes to school and sits the lessons. False if there's no school with room. */
    boolean study(Npc n, Colony col, long now) {
        Building school = pick(n, col, "study:", null, plugin.settings().schoolCapacity);
        if (school == null) return false;
        World w = n.body.getWorld();
        Location spot = interiorSpot(w, school, n);
        if (spot == null) return false;
        if (!inside(school, n.body.getLocation()) || !n.mover.near(spot, 1.0)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 0.8);
            n.activity = "Going to " + school.label();
            return true;
        }
        n.mover.stop();
        Npc teacher = teacherIn(col, school);
        if (teacher != null) CitizenBrain.face(n, teacher.body.getLocation());
        n.activity = teacher != null ? "Studying at " + school.label() + " (education " + Math.round(n.c.education) + ")" : "Waiting for a Teacher at " + school.label();
        return true;
    }

    /** The school this citizen goes to (sticky), with room for them. */
    private Building pick(Npc n, Colony col, String prefix, Job sameJob, int capacity) {
        List<Building> schools = col.buildings(BuildingType.SCHOOL);
        if (schools.isEmpty()) return null;
        if (n.jobKey != null && n.jobKey.startsWith(prefix)) {
            Building b = col.buildings.get(n.jobKey.substring(prefix.length()));
            if (b != null && b.type == BuildingType.SCHOOL) return b;
        }
        Building best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Building b : schools) {
            int count = 0;
            for (Npc o : m.of(col)) if (o != n && (prefix + b.id).equals(o.jobKey)) count++;
            if (count < capacity && count < bestCount) {
                best = b;
                bestCount = count;
            }
        }
        if (best != null) n.jobKey = prefix + best.id;
        return best;
    }

    private static boolean inside(Building b, Location l) {
        if (b.min == null || b.max == null) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= b.min.x() && x <= b.max.x() && z >= b.min.z() && z <= b.max.z() && y >= b.min.y() - 1 && y <= b.max.y();
    }

    /** Where the teacher stands: beside the lectern, inside the room. */
    private static Location lecternSpot(World w, Building school) {
        for (BlockPos lec : school.tiles) {
            if (!lec.loaded(w)) continue;
            for (BlockFace f : SIDES) {
                BlockPos p = lec.relative(f);
                if (!school.contains(p)) continue;
                if (RoomScanner.standable(w, p.x(), p.y(), p.z())) return p.center(w).subtract(0, 0.5, 0);
            }
        }
        return null;
    }

    /** A spot in the room for this person (the same one every time). */
    private static Location interiorSpot(World w, Building school, Npc n) {
        if (school.min == null || school.max == null) return null;
        List<Location> spots = new ArrayList<>();
        for (int x = school.min.x(); x <= school.max.x(); x++) {
            for (int z = school.min.z(); z <= school.max.z(); z++) {
                int y = school.min.y();
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                if (RoomScanner.standable(w, x, y, z)) spots.add(new Location(w, x + 0.5, y, z + 0.5));
            }
        }
        if (spots.isEmpty()) return null;
        return spots.get(Math.floorMod(n.c.id.hashCode(), spots.size()));
    }

    private Npc teacherIn(Colony col, Building school) {
        for (Npc o : m.of(col)) {
            if (o.c.status == Status.CITIZEN && o.c.job == Job.TEACHER && !o.sleeping && inside(school, o.body.getLocation())) return o;
        }
        return null;
    }

    private List<Npc> present(Colony col, Building school, boolean capped) {
        List<Npc> out = new ArrayList<>();
        for (Npc o : m.of(col)) {
            boolean pupil = o.c.status == Status.CHILD || o.c.status == Status.CITIZEN && o.c.job == Job.STUDENT;
            if (!pupil || o.sleeping || !inside(school, o.body.getLocation())) continue;
            out.add(o);
            if (capped && out.size() >= plugin.settings().schoolCapacity) break;
        }
        return out;
    }

    // ───────────── lessons ─────────────

    /** Every 30 seconds: a lesson in every school that has a teacher. */
    void lessons(Colony col) {
        World w = col.world();
        if (w == null) return;
        Settings s = plugin.settings();
        long today = NpcManager.day(w);
        for (Building school : col.buildings(BuildingType.SCHOOL)) {
            Npc teacher = teacherIn(col, school);
            if (teacher == null) continue;
            List<Npc> pupils = present(col, school, true);
            if (pupils.isEmpty()) continue;
            if (school.bookDay != today && teacher.c.useCarried(Schooling::book, 1) == 1) school.bookDay = today;
            double book = school.bookDay == today ? s.bookLearning : 1;
            double shelves = 1 + Math.min(0.5, school.progress * 0.05);
            double skill = Math.max(0.5, Math.min(1.5, teacher.c.education / 60)) * teacher.c.trait.work(Job.TEACHER);
            for (Npc p : pupils) {
                double gain = s.perLesson * (p.c.status == Status.CHILD ? s.childLearning : 1) * book * shelves * skill * (0.8 + p.c.happiness / 250);
                p.c.education = Math.min(100, p.c.education + gain);
                w.spawnParticle(Particle.ENCHANT, p.body.getLocation().add(0, 1.8, 0), 8, 0.3, 0.3, 0.3, 0.5);
            }
            Fx.sound(teacher.body.getLocation(), "minecraft:item.book.page_turn", 0.8f, 1f);
            w.spawnParticle(Particle.NOTE, teacher.body.getLocation().add(0, 2.2, 0), 2, 0.3, 0.2, 0.3, 1);
        }
    }
}
