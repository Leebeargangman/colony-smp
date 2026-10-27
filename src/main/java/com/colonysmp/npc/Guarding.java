package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The watch: guards draw arms from the armoury, keep watch from their watchtowers (or walk a ring around the
 * Town Hall), and fight monsters and enemies on the colony's land. In an emergency under the militia policy,
 * ordinary adults take up arms the same way.
 */
public final class Guarding {

    private final ColonySMP plugin;
    private final NpcManager m;
    final Fighting fighting;

    Guarding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
        this.fighting = new Fighting(plugin, m);
    }

    public Fighting fighting() {
        return fighting;
    }

    public boolean hostile(Npc n, Colony col, LivingEntity e) {
        return fighting.hostile(n, col, e);
    }

    void think(Npc n, Colony col, long now) {
        LivingEntity t = fighting.target(n, col, now, n.c.ranged != null && n.c.ammo > 0 ? 24 : 18, false);
        if (t != null) {
            fighting.engage(n, col, t, now);
            n.working = true;
            return;
        }
        n.enemy = null;
        boolean alert = plugin.military().alert(col);
        if (!alert) n.gearChecked = false;
        if (!gear(n, col, alert)) return;
        patrol(n, col, now);
    }

    /** A citizen called up as militia: arms, then defend the Town Hall. */
    boolean militia(Npc n, Colony col, long now) {
        LivingEntity t = fighting.target(n, col, now, 16, false);
        if (t != null) {
            fighting.engage(n, col, t, now);
            return true;
        }
        n.enemy = null;
        if (!gear(n, col, true)) return true;
        Location core = col.coreLocation();
        if (core == null) return false;
        m.brain().wander(n, core, 6, now, "Militia: defending the Town Hall");
        return true;
    }

    /** Draws a weapon (and in an emergency the best armour too) from the armoury. False while walking there. */
    public boolean gear(Npc n, Colony col, boolean alert) {
        boolean want = plugin.military().wantsGear(col, n.c, alert) && !n.gearChecked;
        if (!want) return true;
        if (!m.goToStorage(n, col, alert ? "Drawing arms from the armoury!" : "Fetching a weapon from the armoury")) return false;
        plugin.military().equip(col, n.c, alert);
        n.gearChecked = true;
        m.refresh(n);
        Fx.sound(n.loc(), "minecraft:item.armor.equip_iron", 0.8f, 1f);
        return true;
    }

    private void patrol(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        List<Location> points = waypoints(n, col, w);
        if (points.isEmpty()) return;
        if (n.waypoint < 0 || n.waypoint >= points.size()) {
            n.waypoint = Math.floorMod(n.c.id.hashCode(), points.size());
            n.waypointUntil = 0;
        }
        Location wp = points.get(n.waypoint);
        n.working = true;
        if (!n.mover.near(wp, 1.8)) {
            n.mover.moveTo(wp, plugin.settings().walkSpeed, 1.3);
            n.doing("Patrolling");
            n.waypointUntil = 0;
            return;
        }
        n.mover.stop();
        if (n.waypointUntil == 0) n.waypointUntil = now + 300 + ThreadLocalRandom.current().nextInt(300);
        n.doing("Keeping watch");
        if (now > n.waypointUntil) {
            n.waypoint = (n.waypoint + 1) % points.size();
            n.waypointUntil = 0;
        }
    }

    /** Their own watchtower, every watchtower, or a ring around the Town Hall. */
    private List<Location> waypoints(Npc n, Colony col, World w) {
        List<Location> out = new ArrayList<>();
        Building own = col.building(n.c.workplace);
        if (own != null && own.type == BuildingType.WATCHTOWER && own.valid && own.anchor != null) {
            out.add(own.anchor.center(w));
            return out;
        }
        for (Building b : col.valid(BuildingType.WATCHTOWER)) if (b.anchor != null) out.add(b.anchor.center(w));
        Location core = col.coreLocation();
        if (core == null) return out;
        int r = Math.max(6, Math.min(24, (int) Math.sqrt(col.territory.size()) * 6));
        int[][] dirs = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
        for (int[] d : dirs) {
            Location l = Mover.safeSpot(core.clone().add(d[0], 0, d[1]));
            if (l == null) l = Mover.safeSpot(core.clone().add(d[0] / 2.0, 0, d[1] / 2.0));
            if (l != null) out.add(l);
        }
        return out;
    }

    public static String label(LivingEntity e) {
        if (e instanceof Player p) return p.getName();
        if (e.customName() != null) return PlainTextComponentSerializer.plainText().serialize(e.customName());
        return Text.a(e.getType().name().toLowerCase().replace('_', ' '));
    }
}
