package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.util.Text;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What citizens need besides food: rest (lost while awake, refilled in bed), health (slowly mended when fed,
 * faster in bed or by a Doctor) and happiness, which follows from everything else and drives their work.
 * Very unhappy citizens leave the colony.
 */
public final class Needs {

    private static final double TICKS_PER_HOUR = 1000;

    private final ColonySMP plugin;

    Needs(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Called from the brain (every 10 ticks per citizen). */
    void tick(Npc n, Colony col, long now) {
        Citizen c = n.c;
        long last = n.needsAt;
        n.needsAt = now;
        if (last <= 0 || now - last > 200) return;
        double hours = (now - last) / TICKS_PER_HOUR;
        Settings s = plugin.settings();
        // rest
        if (n.sleeping) {
            c.rest = Math.min(100, c.rest + 14 * hours);
        } else if (n.napUntil > now) {
            c.rest = Math.min(100, c.rest + 30 * hours);
        } else if (c.status == Status.CITIZEN || c.status == Status.CHILD || c.status == Status.SLAVE) {
            boolean night = plugin.npcs().phase(n.body.getWorld()) == NpcManager.Phase.NIGHT;
            // the homeless doze on the ground at night, which only gets them so far
            if (night && c.bed == null && c.status != Status.SLAVE && n.c.job != Job.GUARD && !c.militia) {
                if (c.rest < 50) c.rest = Math.min(50, c.rest + 6 * hours);
            } else {
                double loss = s.restLoss * (c.status == Status.SLAVE ? 1.5 : 1);
                c.rest = Math.max(0, c.rest - loss * hours);
            }
        }
        // health mends when fed; much faster asleep
        AttributeInstance max = n.body.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? s.citizenHealth : max.getValue();
        if (!c.downed() && n.body.getHealth() < cap && c.fed >= 0.5) {
            double heal = (n.sleeping ? 20 : 4) * hours;
            n.body.setHealth(Math.min(cap, n.body.getHealth() + heal));
        }
        // happiness drifts toward what their life deserves
        double target = target(col, c, n);
        double step = 6 * hours;
        if (Math.abs(target - c.happiness) <= step) c.happiness = target;
        else c.happiness += Math.signum(target - c.happiness) * step;
    }

    /** True while the citizen is too exhausted to work and is taking a nap. */
    boolean napping(Npc n, long now) {
        if (n.napUntil > now) {
            n.mover.stop();
            n.activity = "Napping - exhausted (rest " + Math.round(n.c.rest) + ")";
            if (ThreadLocalRandom.current().nextInt(8) == 0) {
                n.body.getWorld().spawnParticle(Particle.CLOUD, n.body.getLocation().add(0, 2.1, 0), 1, 0.1, 0.05, 0.1, 0.005);
            }
            return true;
        }
        if (n.c.rest < 8) {
            n.napUntil = now + 600;
            n.resetWork();
            return true;
        }
        return false;
    }

    /** The happiness a citizen's circumstances deserve (0-100). */
    public double target(Colony col, Citizen c, Npc n) {
        Settings s = plugin.settings();
        double t = 50;
        t += (Math.max(0, Math.min(1, c.fed)) - 0.5) * 30;           // food: -15..+15
        t += (c.rest - 50) / 5;                                      // rest: -10..+10
        if (c.status == Status.CITIZEN || c.status == Status.CHILD) t += c.bed != null ? 8 : -12; // a home
        double hp = n != null ? n.body.getHealth() : c.health;
        t -= (1 - Math.max(0, Math.min(1, hp / s.citizenHealth))) * 20; // injuries
        t += (col.stability - 50) / 5;                               // the commune's mood: -10..+10
        if (c.status == Status.CITIZEN) {
            double w = c.trait.work(c.job);
            if (w > 1.05) t += 5;
            else if (w < 0.95) t -= 5;
            // the educated want skilled work
            if (c.education >= 50 && c.job.education == 0 && c.job != Job.GUARD) t -= 4;
            if (c.job.education > 0) t += 4;
            if (c.militia) t -= 5;
        }
        if (c.status == Status.CHILD && !col.buildings(BuildingType.SCHOOL).isEmpty()) t += 5;
        if (c.status == Status.SLAVE) t -= 25;
        if (col.strike) t -= 8;
        return Math.max(0, Math.min(100, t));
    }

    /** Work speed from mood, rest and schooling: about 0.6 to 1.6. */
    public static double factor(Citizen c, double tired) {
        double mood = 0.8 + c.happiness / 100 * 0.4;
        double rest = c.rest >= tired ? 1 : 0.7 + 0.3 * c.rest / Math.max(1, tired);
        double edu = 1 + Math.min(0.3, c.education / 100 * 0.3);
        return mood * rest * edu;
    }

    /** At the nightly rationing: the people's mood moves stability, and the miserable may leave. */
    public void nightly(Colony col) {
        Settings s = plugin.settings();
        double sum = 0;
        int count = 0;
        List<Citizen> leaving = new ArrayList<>();
        int free = 0;
        for (Citizen c : col.citizens.values()) if (c.status == Status.CITIZEN) free++;
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CITIZEN && c.status != Status.CHILD) continue;
            // citizens far away (no body) are judged by their circumstances
            if (plugin.npcs().npc(c) == null) c.happiness += (target(col, c, null) - c.happiness) * 0.5;
            sum += c.happiness;
            count++;
            if (c.status != Status.CITIZEN) continue;
            if (c.happiness < s.emigrateBelow) c.miserableNights++;
            else c.miserableNights = 0;
            if (c.miserableNights >= s.emigrateNights && free - leaving.size() > 2 && ThreadLocalRandom.current().nextDouble() < s.emigrateChance) {
                leaving.add(c);
            }
        }
        if (count > 0) col.addStability((sum / count - 50) / 25);
        for (Citizen c : leaving) {
            plugin.npcs().remove(c);
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<red>" + Text.esc(c.name) + " was miserable for too long and left the colony.</red> <gray>Keep your people fed, housed, rested and healthy.");
            }
        }
    }

    /** Average happiness of free citizens (for menus). */
    public static double average(Colony col) {
        double sum = 0;
        int n = 0;
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CITIZEN && c.status != Status.CHILD) continue;
            sum += c.happiness;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }
}
