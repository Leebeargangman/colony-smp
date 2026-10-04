package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Status;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wardens (commissars) visit every prisoner under the Indoctrination policy once a day: they unlock the
 * iron door, bring a meal from the State Chest and wear down the prisoner's Resistance. With no prisoners
 * they give speeches at the Town Hall, which raises stability at the next rationing.
 */
public final class Wardening {

    private final ColonySMP plugin;
    private final NpcManager m;

    Wardening(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, long now) {
        if (n.visiting != null) {
            visit(n, col, now);
            return;
        }
        World w = n.body.getWorld();
        long today = NpcManager.day(w);
        Citizen next = null;
        double best = Double.MAX_VALUE;
        for (Citizen p : col.citizens.values()) {
            if (p.status != Status.PRISONER || p.policy != Policy.INDOCTRINATE || p.lastVisitDay >= today || p.cell == null) continue;
            if (p.downed() || m.npc(p) == null || col.buildings.get(p.cell) == null) continue;
            if (claimedByOther(n, col, p)) continue;
            double d = m.npc(p).body.getLocation().distanceSquared(n.body.getLocation());
            if (d < best) {
                best = d;
                next = p;
            }
        }
        if (next == null) {
            speech(n, col, now);
            return;
        }
        n.visiting = next;
        n.visitStage = 0;
        visit(n, col, now);
    }

    private boolean claimedByOther(Npc self, Colony col, Citizen p) {
        for (Npc o : m.of(col)) if (o != self && o.visiting == p) return true;
        return false;
    }

    private void visit(Npc n, Colony col, long now) {
        Citizen p = n.visiting;
        Npc pn = m.npc(p);
        Building cell = p.cell == null ? null : col.buildings.get(p.cell);
        World w = n.body.getWorld();
        if (pn == null || cell == null || p.status != Status.PRISONER || p.downed()) {
            end(n);
            return;
        }
        if (n.visitStage == 0) {
            Location out = plugin.prison().outsideDoor(col, cell);
            if (out == null) out = pn.body.getLocation();
            if (!n.mover.near(out, 1.7)) {
                n.mover.moveTo(out, plugin.settings().walkSpeed, 1.3);
                n.activity = "Going to visit prisoner " + p.name;
                return;
            }
            n.mover.stop();
            if (!cell.doors.isEmpty()) {
                BlockPos door = cell.doors.get(0);
                if (door.loaded(w)) n.mover.openFor(door.block(w), now, 120);
            }
            Location in = plugin.prison().cellSpot(col, cell);
            n.visitReturn = out;
            if (in != null) {
                in.setYaw(n.body.getLocation().getYaw());
                n.body.teleport(in);
                n.mover.sync();
            }
            n.visitStage = 1;
            n.visitUntil = now + 100;
            n.activity = "Re-educating " + p.name;
            return;
        }
        CitizenBrain.face(n, pn.body.getLocation());
        CitizenBrain.face(pn, n.body.getLocation());
        w.spawnParticle(Particle.ENCHANT, pn.body.getLocation().add(0, 1.6, 0), 12, 0.4, 0.4, 0.4, 0.6);
        if (ThreadLocalRandom.current().nextInt(4) == 0) Fx.sound(n.body.getLocation(), "minecraft:item.book.page_turn", 0.6f, 1f);
        if (now < n.visitUntil) return;
        plugin.prison().indoctrinate(col, p, n);
        if (n.visitReturn != null) {
            n.body.teleport(n.visitReturn);
            n.mover.sync();
        }
        end(n);
    }

    private void end(Npc n) {
        n.visiting = null;
        n.visitStage = 0;
        n.visitReturn = null;
    }

    private void speech(Npc n, Colony col, long now) {
        Location core = col.coreLocation();
        if (core == null) return;
        m.brain().wander(n, core, 4, now, "Giving speeches at the Town Hall (+stability)");
        if (ThreadLocalRandom.current().nextInt(15) == 0) {
            n.body.getWorld().spawnParticle(Particle.NOTE, n.body.getLocation().add(0, 2.2, 0), 3, 0.4, 0.2, 0.4, 1);
            Fx.sound(n.body.getLocation(), "minecraft:entity.villager.celebrate", 0.6f, 0.9f);
        }
    }
}
