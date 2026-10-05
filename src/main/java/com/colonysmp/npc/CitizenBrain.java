package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.BlueprintManager;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/** Decides what each citizen does: work by day, eat at evening, sleep at night - or strike, flee and fight. */
public final class CitizenBrain {

    private final ColonySMP plugin;
    private final NpcManager m;
    public final Farming farming;
    public final Logging logging;
    public final Mining mining;
    public final Construction construction;
    public final Guarding guarding;
    public final Wardening wardening;
    public final Needs needs;
    public final Schooling schooling;
    public final Fishing fishing;
    public final Herding herding;
    public final Doctoring doctoring;
    final Workshop workshop;

    CitizenBrain(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
        this.farming = new Farming(plugin, m);
        this.logging = new Logging(plugin, m);
        this.mining = new Mining(plugin, m);
        this.construction = new Construction(plugin, m);
        this.guarding = new Guarding(plugin, m);
        this.wardening = new Wardening(plugin, m);
        this.needs = new Needs(plugin);
        this.schooling = new Schooling(plugin, m);
        this.fishing = new Fishing(plugin, m);
        this.herding = new Herding(plugin, m);
        this.doctoring = new Doctoring(plugin, m);
        this.workshop = new Workshop(plugin, m);
    }

    void think(Npc n, long now) {
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null) {
            m.despawn(n);
            return;
        }
        m.rename(n);
        if (n.c.downed()) {
            n.mover.stop();
            n.activity = "Downed";
            return;
        }
        needs.tick(n, col, now);
        switch (n.c.status) {
            case CAPTIVE -> plugin.prison().thinkCaptive(n, col, now);
            case PRISONER -> plugin.prison().thinkPrisoner(n, col, now);
            case SLAVE -> plugin.prison().thinkSlave(n, col, now);
            case REBEL -> guarding.thinkRebel(n, col, now);
            case CHILD -> child(n, col, now);
            case CITIZEN -> citizen(n, col, now);
        }
        m.showHand(n);
    }

    private void citizen(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        NpcManager.Phase ph = m.phase(w);
        if (n.sleeping && ph != NpcManager.Phase.NIGHT) wake(n);
        if (n.c.job == Job.GUARD || n.c.militia) {
            if (n.sleeping) wake(n);
            guarding.think(n, col, now);
            return;
        }
        if (flee(n, col)) return;
        if (col.strike) {
            strike(n, col, now);
            return;
        }
        switch (ph) {
            case WORK -> {
                if (!needs.napping(n, now)) work(n, col, now);
            }
            case EVENING -> evening(n, col, now);
            case NIGHT -> sleep(n, col, now);
        }
    }

    private void work(Npc n, Colony col, long now) {
        switch (n.c.job) {
            case FARMER -> farming.think(n, col, now);
            case BUILDER -> {
                if (!construction.think(n, col, now) && !m.returnLeftovers(n, col)) logging.think(n, col, now, "Cutting wood (no build orders)");
            }
            case LUMBERJACK -> logging.think(n, col, now, null);
            case MINER -> mining.think(n, col, now);
            case WARDEN -> wardening.think(n, col, now);
            case TEACHER -> schooling.teach(n, col, now);
            case FISHER -> fishing.think(n, col, now);
            case HERDER -> herding.think(n, col, now);
            case COOK, SMITH -> workshop.think(n, col, now);
            case DOCTOR -> doctoring.think(n, col, now);
            case STUDENT -> {
                if (!schooling.study(n, col, now)) idle(n, col, now, "No School with room - register one with the Blueprint Book");
            }
            case GUARD -> guarding.think(n, col, now);
            case NONE -> idle(n, col, now, "Unassigned - waiting for a job");
        }
    }

    /** Wanders around the Town Hall. */
    public void idle(Npc n, Colony col, long now, String label) {
        Location core = col.coreLocation();
        if (core == null) return;
        if (n.idleSpot == null || now > n.idleUntil || n.idleSpot.getWorld() != core.getWorld() || n.idleSpot.distanceSquared(core) > 144) {
            n.idleSpot = m.spotNear(core, 2, 8);
            n.idleUntil = now + 200 + ThreadLocalRandom.current().nextInt(300);
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed * 0.7, 1.2);
        n.activity = label;
    }

    /** Wanders around a point. */
    public void wander(Npc n, Location center, int radius, long now, String label) {
        if (n.idleSpot == null || now > n.idleUntil || n.idleSpot.getWorld() != center.getWorld() || n.idleSpot.distanceSquared(center) > radius * radius * 2) {
            n.idleSpot = m.spotNear(center, 1, radius);
            n.idleUntil = now + 160 + ThreadLocalRandom.current().nextInt(240);
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed * 0.7, 1.2);
        n.activity = label;
    }

    private void evening(Npc n, Colony col, long now) {
        Location core = col.coreLocation();
        if (core == null) return;
        if (supper(n, col)) return;
        if (n.idleSpot == null || n.idleSpot.distanceSquared(core) > 49 || now > n.idleUntil) {
            n.idleSpot = m.spotNear(core, 2, 5);
            n.idleUntil = now + 600;
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed, 1.2);
        n.activity = "Gathering at the Town Hall for supper";
    }

    /** Supper: walk to the State Chest, put back what's in the satchel and eat a ration. True while busy. */
    private boolean supper(Npc n, Colony col) {
        long today = NpcManager.day(n.body.getWorld());
        if (n.c.supperDay == today) return false;
        var s = plugin.settings();
        double need = com.colonysmp.colony.Simulation.share(n.c, s) * s.mealPoints;
        if (need <= 0 || col.storage.foodPoints(s.neverEat) <= 0 && n.c.carry.isEmpty()) return false;
        Location chest = col.chestLocation();
        if (chest == null) return false;
        if (!m.atChest(n, col)) {
            n.mover.moveTo(chest, s.walkSpeed, 2.2);
            n.activity = "Going to the State Chest for supper";
            return true;
        }
        n.mover.stop();
        m.unload(n, col, it -> true);
        n.c.supperEaten = col.storage.consumeFood(need, s.neverEat);
        n.c.supperDay = today;
        Fx.sound(n.body.getLocation(), "minecraft:entity.generic.eat", 0.7f, 1f);
        n.body.getWorld().spawnParticle(Particle.ITEM, n.body.getEyeLocation(), 6, 0.15, 0.1, 0.15, 0.04, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD));
        n.activity = n.c.supperEaten >= need * 0.999 ? "Ate supper" : "Ate a short ration - the State Chest is low on food";
        return true;
    }

    public void sleep(Npc n, Colony col, long now) {
        if (n.sleeping) {
            n.activity = "Sleeping";
            return;
        }
        World w = n.body.getWorld();
        BlockPos bed = n.c.bed;
        if (bed == null || !BlueprintManager.bedExists(w, bed)) {
            idle(n, col, now, "Homeless - no free bed in a registered house");
            return;
        }
        Location bl = bed.center(w);
        if (!n.mover.near(bl, 1.9)) {
            n.mover.moveTo(bl, plugin.settings().walkSpeed, 1.4);
            n.activity = "Going to bed";
            return;
        }
        n.mover.stop();
        if (n.body instanceof Villager v) {
            try {
                if (v.sleep(bl)) {
                    n.sleeping = true;
                    n.activity = "Sleeping";
                    return;
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
            }
        }
        n.activity = "Can't sleep: bed occupied";
    }

    public void wake(Npc n) {
        if (n.body instanceof Villager v && v.isSleeping()) {
            try {
                v.wakeup();
            } catch (IllegalStateException ignored) {
            }
        }
        n.sleeping = false;
        n.mover.sync();
    }

    private void strike(Npc n, Colony col, long now) {
        if (n.sleeping) wake(n);
        Location core = col.coreLocation();
        if (core == null) return;
        if (n.idleSpot == null || n.idleSpot.distanceSquared(core) > 64 || now > n.idleUntil) {
            n.idleSpot = m.spotNear(core, 2, 6);
            n.idleUntil = now + 400;
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed, 1.2);
        n.activity = "ON STRIKE! Demanding food and dignity";
        if (ThreadLocalRandom.current().nextInt(12) == 0) {
            n.body.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, n.body.getLocation().add(0, 2.1, 0), 3, 0.3, 0.2, 0.3, 0);
        }
    }

    /** Non-combatants run from monsters. */
    private boolean flee(Npc n, Colony col) {
        LivingEntity threat = null;
        double best = 36;
        for (Entity e : n.body.getNearbyEntities(6, 4, 6)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || le.isDead() || NpcManager.isBody(e)) continue;
            double d = e.getLocation().distanceSquared(n.body.getLocation());
            if (d < best) {
                best = d;
                threat = le;
            }
        }
        if (threat == null) return false;
        if (n.sleeping) return false; // safe in bed
        Vector away = n.body.getLocation().toVector().subtract(threat.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        away.normalize().multiply(8);
        Location to = Mover.safeSpot(n.body.getLocation().add(away));
        if (to != null) n.mover.moveTo(to, plugin.settings().runSpeed, 1.5);
        n.activity = "Fleeing from a " + threat.getType().name().toLowerCase().replace('_', ' ');
        return true;
    }

    private void child(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        NpcManager.Phase ph = m.phase(w);
        if (n.sleeping && ph != NpcManager.Phase.NIGHT) wake(n);
        if (flee(n, col)) return;
        if (ph == NpcManager.Phase.NIGHT) {
            sleep(n, col, now);
            return;
        }
        // children go to school by day
        if (ph == NpcManager.Phase.WORK && schooling.study(n, col, now)) return;
        Location home = n.c.bed != null ? n.c.bed.center(w) : col.coreLocation();
        if (home == null) return;
        wander(n, home, 7, now, "Playing");
        if (ThreadLocalRandom.current().nextInt(40) == 0) {
            w.spawnParticle(Particle.HEART, n.body.getLocation().add(0, 1.2, 0), 1, 0.2, 0.2, 0.2, 0);
        }
    }

    /** Turns the body to look at a point. */
    public static void face(Npc n, Location at) {
        Location l = n.body.getLocation();
        double dx = at.getX() - l.getX(), dz = at.getZ() - l.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        n.body.setRotation(yaw, 0);
    }
}
