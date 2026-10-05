package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

/** Doctors run to the downed and get them back up, and patch up the injured. */
public final class Doctoring {

    private final ColonySMP plugin;
    private final NpcManager m;

    Doctoring(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private static double max(Npc n) {
        AttributeInstance a = n.body.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    void think(Npc n, Colony col, long now) {
        Npc patient = null;
        double best = Double.MAX_VALUE;
        boolean urgent = false;
        Location here = n.body.getLocation();
        for (Npc o : m.of(col)) {
            if (o == n || o.body.getWorld() != here.getWorld()) continue;
            Status s = o.c.status;
            if (s != Status.CITIZEN && s != Status.CHILD && s != Status.SLAVE && s != Status.PRISONER) continue;
            boolean down = o.c.downed();
            boolean hurt = o.body.getHealth() < max(o) * 0.75;
            if (!down && !hurt) continue;
            // the downed come first, then the nearest
            double d = o.body.getLocation().distanceSquared(here) - (down ? 1e6 : 0);
            if (d < best) {
                best = d;
                patient = o;
                urgent = down;
            }
        }
        if (patient == null) {
            Location core = col.coreLocation();
            if (core != null) m.brain().wander(n, core, 5, now, "Running the clinic (no one is hurt)");
            return;
        }
        Location at = patient.body.getLocation();
        if (!n.mover.near(at, 1.8)) {
            n.mover.moveTo(at, urgent ? plugin.settings().runSpeed : plugin.settings().walkSpeed, 1.4);
            n.activity = urgent ? "Running to save " + patient.c.name + "!" : "Going to treat " + patient.c.name;
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, at);
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c, Job.DOCTOR);
        n.actionAt = now + m.interval(3.0, eff);
        if (patient.c.downed()) {
            plugin.prison().revive(patient, 10);
            n.activity = "Revived " + patient.c.name;
            return;
        }
        patient.body.setHealth(Math.min(max(patient), patient.body.getHealth() + 4));
        at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
        Fx.sound(at, "minecraft:entity.generic.drink", 0.5f, 1.2f);
        n.activity = "Treating " + patient.c.name;
    }
}
