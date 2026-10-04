package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Status;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The commune's daily life: rations at sunset, stability, strikes, births, children growing up,
 * bed assignment and the State Mobilization Order. Runs while a colony's Town Hall is loaded.
 */
public final class Simulation {

    private final ColonySMP plugin;
    private long lastVerify;

    public Simulation(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        boolean verify = now - lastVerify > 30_000;
        if (verify) lastVerify = now;
        for (Colony col : plugin.colonies().list()) {
            if (col.mobilizedUntil > 0 && now > col.mobilizedUntil) demobilize(col, "The mobilization order has expired.");
            if (!plugin.npcs().active(col)) continue;
            World w = col.world();
            if (verify) plugin.townHall().verify(col);
            long day = NpcManager.day(w);
            boolean past = w.getTime() >= plugin.settings().rationTime;
            if (col.lastRationDay < 0) {
                col.lastRationDay = past ? day : day - 1;
                continue;
            }
            boolean due = (day > col.lastRationDay && past) || day > col.lastRationDay + 1;
            if (due) {
                long forDay = past ? day : day - 1;
                col.lastRationDay = forDay;
                ration(col, w, forDay);
            }
        }
    }

    // ───────────── nightly rations ─────────────

    public void ration(Colony col, World w, long day) {
        Settings s = plugin.settings();
        plugin.blueprints().validate(col);
        assignBeds(col);
        double need = 0;
        List<Citizen> eaters = new ArrayList<>();
        for (Citizen c : col.citizens.values()) {
            double f = share(c, s);
            if (f <= 0) continue;
            need += f * s.mealPoints;
            eaters.add(c);
        }
        double before = col.stability;
        double fraction = 1;
        double eaten = 0;
        if (need > 0) {
            eaten = col.storage.consumeFood(need, s.neverEat);
            fraction = Math.min(1, eaten / need);
            for (Citizen c : eaters) c.fed = fraction;
            col.lastFed = fraction;
            double delta;
            if (fraction >= 0.999) delta = s.stabilityFull * (col.strike ? 2 : 1);
            else if (fraction >= 0.5) delta = -s.stabilityPartialMaxLoss * (1 - fraction) * 2;
            else delta = -(s.stabilityPartialMaxLoss + (s.stabilityNone - s.stabilityPartialMaxLoss) * (0.5 - fraction) * 2);
            col.addStability(delta);
        }
        // homes, speeches and oppression
        int homeless = 0, speakers = 0, slaves = 0;
        for (Citizen c : col.citizens.values()) {
            if (c.status == Status.CITIZEN && c.bed == null) homeless++;
            if (c.status == Status.SLAVE) slaves++;
        }
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CITIZEN || c.job != Job.WARDEN) continue;
            boolean busy = false;
            for (Citizen p : col.citizens.values()) if (p.status == Status.PRISONER && p.policy == Policy.INDOCTRINATE) busy = true;
            if (!busy) speakers++;
        }
        col.addStability(-Math.min(3, homeless * 0.5) + Math.min(3, speakers) - Math.min(2, slaves * 0.25));
        // strike
        if (col.stability < s.lowThreshold) col.lowDays++;
        else col.lowDays = 0;
        boolean struck = false, recovered = false;
        if (!col.strike && col.lowDays >= s.strikeDays) {
            col.strike = true;
            struck = true;
        } else if (col.strike && col.stability >= s.strikeRecover) {
            col.strike = false;
            col.lowDays = 0;
            recovered = true;
        }
        prisoners(col, day);
        grow(col, w, day);
        boolean born = reproduce(col, w, day, need);
        eatingShow(col);
        report(col, fraction, eaten, need, before, struck, recovered, born);
        for (Npc n : plugin.npcs().of(col)) plugin.npcs().refresh(n);
        plugin.quests().check(col);
        plugin.requestSave();
    }

    /** Share of a full meal a citizen eats. Prisoners are fed by wardens, captives not at all. */
    public static double share(Citizen c, Settings s) {
        return switch (c.status) {
            case CITIZEN -> c.trait.food();
            case CHILD -> s.childFactor * c.trait.food();
            case SLAVE -> s.slaveFactor * c.trait.food();
            default -> 0;
        };
    }

    public double dailyNeed(Colony col) {
        double need = 0;
        for (Citizen c : col.citizens.values()) need += share(c, plugin.settings()) * plugin.settings().mealPoints;
        return need;
    }

    private void prisoners(Colony col, long day) {
        Settings s = plugin.settings();
        for (Citizen p : new ArrayList<>(col.citizens.values())) {
            if (p.status != Status.PRISONER) continue;
            if (p.lastVisitDay < day && p.policy == Policy.INDOCTRINATE) p.resistance = Math.min(100, p.resistance + s.unvisitedGain);
            if (p.lastFedDay < day) p.unfedDays++;
            else p.unfedDays = 0;
            if (p.unfedDays >= s.starveDays) {
                for (Player m : col.onlineMembers()) Text.send(m, "<red>✝ Prisoner " + Text.esc(p.name) + " starved to death. Assign a Warden and keep food in the State Chest.");
                Npc n = plugin.npcs().npc(p);
                plugin.npcs().remove(p);
                if (n != null && n.body.isValid()) n.body.remove();
            } else if (p.unfedDays >= 2) {
                for (Player m : col.onlineMembers()) Text.send(m, "<yellow>Prisoner " + Text.esc(p.name) + " hasn't eaten for " + p.unfedDays + " days.");
            }
        }
    }

    private void grow(Colony col, World w, long day) {
        int matDays = plugin.settings().maturityDays;
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CHILD || c.bornDay < 0 || day < c.bornDay + matDays) continue;
            c.status = Status.CITIZEN;
            c.job = autoJob(col);
            Npc n = plugin.npcs().npc(c);
            if (n != null) plugin.npcs().refresh(n);
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<aqua>" + Text.esc(c.name) + " has grown up and joined the workforce as a <white>" + c.job.display + "</white>.");
            }
        }
    }

    private boolean reproduce(Colony col, World w, long day, double need) {
        Settings s = plugin.settings();
        if (col.lastChildDay >= day || col.strike || col.stability < s.reproductionMinStability) return false;
        if (col.citizens.size() >= s.maxPopulation) return false;
        if (freeHouseBeds(col) <= 0) return false;
        double reserve = col.storage.foodPoints(s.neverEat);
        double daily = Math.max(need, s.mealPoints);
        if (reserve < daily * s.reserveDays) return false;
        Citizen c = plugin.npcs().newCitizen(col, Job.NONE, Status.CHILD);
        c.bornDay = day;
        col.lastChildDay = day;
        col.addStability(2);
        assignBeds(col);
        Location at = c.bed != null ? c.bed.center(w).add(0, 0.6, 0) : col.coreLocation();
        if (at != null && plugin.npcs().active(col)) {
            Location spot = plugin.npcs().spotNear(at, 0, 2);
            plugin.npcs().spawn(col, c, spot);
            w.spawnParticle(Particle.HEART, spot.clone().add(0, 1, 0), 10, 0.4, 0.4, 0.4, 0);
        }
        return true;
    }

    private void eatingShow(Colony col) {
        Location core = col.coreLocation();
        if (core == null) return;
        for (Npc n : plugin.npcs().of(col)) {
            if (n.c.status != Status.CITIZEN && n.c.status != Status.CHILD) continue;
            if (n.body.getLocation().distanceSquared(core) > 20 * 20) continue;
            n.body.getWorld().spawnParticle(Particle.ITEM, n.body.getEyeLocation(), 6, 0.15, 0.1, 0.15, 0.04, new ItemStack(Material.BREAD));
            Fx.sound(n.body.getLocation(), "minecraft:entity.generic.eat", 0.6f, 1f);
        }
    }

    private void report(Colony col, double fraction, double eaten, double need, double before, boolean struck, boolean recovered, boolean born) {
        double delta = col.stability - before;
        String fed = fraction >= 0.999 ? "<green>full rations" : fraction >= 0.5 ? "<yellow>short rations (" + Math.round(fraction * 100) + "%)" : "<red>hunger rations (" + Math.round(fraction * 100) + "%)";
        String msg = "<gold>Sunset rations:</gold> " + fed + "</gray> <dark_gray>(" + Math.round(eaten) + "/" + Math.round(need) + " food points)</dark_gray> <gray>| Stability "
                + Text.stabilityColor(col.stability) + Math.round(col.stability) + "%</gray> <dark_gray>(" + (delta >= 0 ? "+" : "") + Math.round(delta) + ")";
        double reserve = col.storage.foodPoints(plugin.settings().neverEat);
        double days = need > 0 ? reserve / need : 0;
        for (Player p : col.onlineMembers()) {
            Text.send(p, msg);
            if (need > 0) Text.raw(p, "<dark_gray>   Food left in the State Chest: <white>" + String.format("%.1f", days) + "</white> days");
            if (col.lowDays > 0 && !col.strike) {
                Text.raw(p, "<red>   Stability has been low for " + col.lowDays + "/" + plugin.settings().strikeDays + " days. The workers are close to striking!");
            }
            if (struck) {
                Text.title(p, "<dark_red>GENERAL STRIKE", "<red>The workers refuse to work until stability recovers", 10, 80, 20);
                Fx.sound(p, "minecraft:event.raid.horn", 0.6f, 0.8f);
            }
            if (recovered) Text.send(p, "<green>The strike is over! The workers return to their posts.");
            if (born) Text.send(p, "<light_purple>A child has been born in the commune! <gray>Children grow into workers in " + plugin.settings().maturityDays + " days.");
        }
    }

    // ───────────── beds ─────────────

    public int freeHouseBeds(Colony col) {
        World w = col.world();
        if (w == null) return 0;
        int beds = 0;
        for (Building b : col.buildings(BuildingType.HOUSE)) beds += b.beds.size();
        int used = 0;
        for (Citizen c : col.citizens.values()) if (c.status.free()) used++;
        return beds - used;
    }

    /** Gives every free citizen a house bed and every prisoner a bed in their cell. */
    public void assignBeds(Colony col) {
        World w = col.world();
        if (w == null) return;
        List<BlockPos> houseBeds = new ArrayList<>();
        java.util.Map<BlockPos, String> owner = new java.util.HashMap<>();
        for (Building b : col.buildings(BuildingType.HOUSE)) {
            for (BlockPos bed : b.beds) {
                houseBeds.add(bed);
                owner.put(bed, b.id);
            }
        }
        Set<BlockPos> used = new HashSet<>();
        List<Citizen> free = new ArrayList<>();
        for (Citizen c : col.citizens.values()) if (c.status.free()) free.add(c);
        for (Citizen c : free) {
            if (c.bed != null && owner.containsKey(c.bed) && used.add(c.bed)) c.bedBuilding = owner.get(c.bed);
            else {
                c.bed = null;
                c.bedBuilding = null;
            }
        }
        for (Citizen c : free) {
            if (c.bed != null) continue;
            for (BlockPos bed : houseBeds) {
                if (used.add(bed)) {
                    c.bed = bed;
                    c.bedBuilding = owner.get(bed);
                    break;
                }
            }
        }
        // prisoners and forced labourers sleep in their cells
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.PRISONER && c.status != Status.SLAVE) {
                if (!c.status.free()) {
                    c.bed = null;
                    c.bedBuilding = null;
                }
                continue;
            }
            Building cell = c.cell == null ? null : col.buildings.get(c.cell);
            if (cell == null || cell.type != BuildingType.PRISON) {
                cell = freeCell(col, c);
                if (cell == null) {
                    // their cell is gone and there's no other: they wait, bound, for a new one
                    c.status = Status.CAPTIVE;
                    c.cell = null;
                    c.bed = null;
                    c.awaitingEscort = true;
                    Npc n = plugin.npcs().npc(c);
                    if (n != null) plugin.npcs().refresh(n);
                    continue;
                }
                c.cell = cell.id;
            }
            if (c.bed == null || !cell.beds.contains(c.bed) || !used.add(c.bed)) {
                c.bed = null;
                for (BlockPos bed : cell.beds) {
                    if (used.add(bed)) {
                        c.bed = bed;
                        break;
                    }
                }
            }
            c.bedBuilding = cell.id;
        }
    }

    /** A cell with a free bed (the citizen's own cell counts as free for them). */
    public Building freeCell(Colony col, Citizen who) {
        for (Building b : col.buildings(BuildingType.PRISON)) {
            int held = 0;
            for (Citizen c : col.citizens.values()) {
                if (c != who && b.id.equals(c.cell) && (c.status == Status.PRISONER || c.status == Status.SLAVE)) held++;
            }
            if (held < b.beds.size()) return b;
        }
        return null;
    }

    /** A useful job for a new adult. */
    public Job autoJob(Colony col) {
        int pop = 0, farmers = 0, miners = 0, builders = 0;
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CITIZEN) continue;
            pop++;
            if (c.job == Job.FARMER) farmers++;
            if (c.job == Job.MINER) miners++;
            if (c.job == Job.BUILDER) builders++;
        }
        boolean farms = !col.buildings(BuildingType.FARM).isEmpty();
        long mines = col.buildings(BuildingType.MINE).stream().filter(b -> !b.exhausted).count();
        if (farms && farmers < Math.max(1, pop / 3)) return Job.FARMER;
        if (!col.buildQueue.isEmpty() && builders < 1) return Job.BUILDER;
        if (mines > 0 && miners < mines) return Job.MINER;
        return Job.LUMBERJACK;
    }

    // ───────────── State Mobilization Order ─────────────

    public String mobilize(Colony col, Player by) {
        long now = System.currentTimeMillis();
        Settings s = plugin.settings();
        if (col.mobilized()) return "The colony is already mobilized (" + Text.duration(col.mobilizedUntil - now) + " left).";
        if (now < col.mobilizeCooldownUntil) return "The workers are exhausted. Mobilization is possible again in " + Text.duration(col.mobilizeCooldownUntil - now) + ".";
        int called = 0;
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CITIZEN || !c.job.worker()) continue;
            c.militia = true;
            called++;
            if (c.weapon == null) {
                ItemStack w = col.storage.takeBest(it -> Tools.isMelee(Tools.kind(it)), Tools::score);
                if (w != null) {
                    c.weapon = w;
                    c.militiaIssued = true;
                }
            }
            Npc n = plugin.npcs().npc(c);
            if (n != null) {
                n.resetWork();
                plugin.npcs().brain().wake(n);
                plugin.npcs().refresh(n);
            }
        }
        if (called == 0) return "There are no workers to mobilize.";
        col.mobilizedUntil = now + s.mobilizeMinutes * 60_000L;
        col.mobilizeCooldownUntil = col.mobilizedUntil + s.mobilizeCooldownMinutes * 60_000L;
        col.addStability(-s.mobilizeCost);
        for (Player p : col.onlineMembers()) {
            Text.title(p, "<dark_red>☭ STATE MOBILIZATION", "<red>" + called + " workers take up arms for " + s.mobilizeMinutes + " minutes", 10, 70, 20);
            Fx.sound(p, "minecraft:event.raid.horn", 1f, 1f);
            Text.send(p, "<red>" + Text.esc(by.getName()) + " issued the State Mobilization Order.</red> <gray>Farmers and builders are now armed militia (stability -" + Math.round(s.mobilizeCost) + ").");
        }
        plugin.requestSave();
        return null;
    }

    public void demobilize(Colony col, String why) {
        col.mobilizedUntil = 0;
        for (Citizen c : col.citizens.values()) {
            if (!c.militia) continue;
            c.militia = false;
            if (c.militiaIssued && c.weapon != null) {
                plugin.npcs().deposit(col, List.of(c.weapon));
                c.weapon = null;
            }
            c.militiaIssued = false;
            Npc n = plugin.npcs().npc(c);
            if (n != null) {
                n.enemy = null;
                plugin.npcs().refresh(n);
            }
        }
        for (Player p : col.onlineMembers()) Text.send(p, "<gray>" + why + " The militia lay down their arms and return to work.");
        plugin.requestSave();
    }
}
