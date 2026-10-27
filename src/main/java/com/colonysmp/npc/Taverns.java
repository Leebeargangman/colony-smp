package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.sim.CitizenSim;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Evenings at the tavern: when work is over, sociable citizens walk to the nearest tavern, take a free chair
 * (the ones a player chose, or any seat) and sit with the others until bedtime. A staffed tavern sells them a
 * drink. If every chair is taken they stand at the bar.
 */
final class Taverns {

    private final ColonySMP plugin;
    private final NpcManager m;

    Taverns(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** The chairs of a tavern that still stand: the chosen ones, or every seat if none were chosen. */
    static List<BlockPos> chairs(Building b, World w) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : b.chairs.isEmpty() ? b.seats : b.chairs) {
            if (!p.loaded(w) || Tag.STAIRS.isTagged(p.block(w).getType())) out.add(p);
        }
        return out;
    }

    /** Spends the evening at a tavern. False if there's no tavern (or this citizen isn't one for company). */
    boolean evening(Npc n, Colony col, long now) {
        Citizen c = n.c;
        if (c.trait.social() <= 0) return false;
        World w = n.body.getWorld();
        Building tavern = null;
        double best = Double.MAX_VALUE;
        for (Building b : col.valid(BuildingType.TAVERN)) {
            if (b.anchor == null) continue;
            double d = b.anchor.center(w).distanceSquared(n.loc());
            if (d < best) {
                best = d;
                tavern = b;
            }
        }
        if (tavern == null) return false;
        // already sitting here
        if (n.seat != null && n.seatEntity != null && n.seatEntity.isValid() && chairs(tavern, w).contains(n.seat)) {
            n.keepSeat = true;
            n.mover.stop();
            drink(n, col, tavern);
            n.doing("Sitting with friends at " + tavern.label());
            if (ThreadLocalRandom.current().nextInt(12) == 0) {
                w.spawnParticle(Particle.NOTE, n.body.getEyeLocation().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0);
            }
            return true;
        }
        // the nearest free chair
        BlockPos chair = null;
        double near = Double.MAX_VALUE;
        for (BlockPos p : chairs(tavern, w)) {
            if (!m.seatFree(n, p)) continue;
            double d = p.center(w).distanceSquared(n.loc());
            if (d < near) {
                near = d;
                chair = p;
            }
        }
        if (chair == null) {
            Location bar = m.place(w, tavern);
            if (bar == null) return false;
            drink(n, col, tavern);
            m.brain().wander(n, bar, 3, now, "Standing at the bar in " + tavern.label() + " (every chair is taken)");
            return true;
        }
        Location at = chair.center(w);
        if (!n.mover.near(at, 1.6)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 1.2);
            n.doing("Heading to " + tavern.label() + " now work is over");
            return true;
        }
        m.sit(n, chair);
        n.keepSeat = n.seat != null;
        n.doing("Sitting down at " + tavern.label());
        return true;
    }

    /** One drink an evening, bought at a staffed tavern (on wages), and a good evening remembered. */
    private void drink(Npc n, Colony col, Building tavern) {
        Citizen c = n.c;
        CitizenSim.Context ctx = m.context(col);
        if (c.tavernDay == ctx.day) return;
        c.tavernDay = ctx.day;
        boolean served = false;
        for (Citizen k : col.citizens.values()) {
            if (k.job == Job.BARKEEP && k.workplace == tavern.id && k.status.free()) {
                served = true;
                break;
            }
        }
        if (served && ctx.wages && c.wallet > 0) {
            c.wallet--;
            col.treasury.credit(1, TxnType.SALE, "Drinks at " + tavern.label(), System.currentTimeMillis(), ctx.day);
        }
    }
}
