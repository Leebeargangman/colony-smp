package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.store.Storage;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Tools;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Guards and militia: they draw arms from the State Chest, patrol the guard towers, fight invaders, monsters
 * and rebels, and (guards only) escort captives and watch over forced labourers. Rebels use the same combat.
 */
public final class Guarding {

    private final ColonySMP plugin;
    private final NpcManager m;

    Guarding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, long now) {
        boolean alert = plugin.wars().siegeActive(col) || plugin.wars().warmup(col) || col.mobilized() || col.uprisingSince > 0;
        if (!alert) n.gearChecked = false;
        LivingEntity t = target(n, col, now);
        if (t != null) {
            engage(n, col, t, now);
            return;
        }
        n.enemy = null;
        if (!gear(n, col, alert)) return;
        if (n.c.job == Job.GUARD && !n.c.militia) {
            if (plugin.prison().escortDuty(n, col, now)) return;
            if (plugin.prison().overseeDuty(n, col, now)) return;
        }
        patrol(n, col, now);
    }

    // ───────────── arms ─────────────

    /** Draws a weapon (and in emergencies the best armour and a bow) from the State Chest. False while walking there. */
    private boolean gear(Npc n, Colony col, boolean alert) {
        Citizen c = n.c;
        boolean wantWeapon = c.weapon == null && col.storage.has(it -> Tools.isMelee(Tools.kind(it)));
        boolean wantUpgrade = alert && !n.gearChecked && betterAvailable(c, col);
        if (!wantWeapon && !wantUpgrade) return true;
        Location chest = col.chestLocation();
        if (chest == null) return true;
        if (!n.mover.near(chest, 2.8)) {
            n.mover.moveTo(chest, alert ? plugin.settings().runSpeed : plugin.settings().walkSpeed, 2.2);
            n.activity = alert ? "Drawing arms from the State Chest!" : "Fetching a weapon from the State Chest";
            return false;
        }
        boolean armed = c.weapon != null;
        equipBest(c, col, alert);
        // a worker's weapon drawn for the mobilization goes back to the State afterwards
        if (c.militia && !armed && c.weapon != null) c.militiaIssued = true;
        n.gearChecked = true;
        m.refresh(n);
        Fx.sound(chest, "minecraft:item.armor.equip_iron", 0.8f, 1f);
        return true;
    }

    private boolean betterAvailable(Citizen c, Colony col) {
        if (col.storage.bestScore(it -> Tools.isMelee(Tools.kind(it)), Tools::score) > Tools.score(c.weapon)) return true;
        if (col.storage.bestScore(it -> Tools.kind(it) == Tools.Kind.BOW, Tools::score) > Tools.score(c.bow)) return true;
        if (c.bow != null && c.arrows < 16 && col.storage.count(Material.ARROW) > 0) return true;
        Tools.Kind[] slots = {Tools.Kind.BOOTS, Tools.Kind.LEGGINGS, Tools.Kind.CHESTPLATE, Tools.Kind.HELMET};
        for (int i = 0; i < 4; i++) {
            Tools.Kind k = slots[i];
            if (col.storage.bestScore(it -> Tools.kind(it) == k, Tools::score) > Tools.score(c.armor[i])) return true;
        }
        return false;
    }

    /** Swaps in anything better from the State Chest (what it replaces goes back in). */
    public void equipBest(Citizen c, Colony col, boolean full) {
        c.weapon = swap(col, c.weapon, it -> Tools.isMelee(Tools.kind(it)));
        if (!full) return;
        c.bow = swap(col, c.bow, it -> Tools.kind(it) == Tools.Kind.BOW);
        if (c.bow != null && c.arrows < 32) {
            int got = col.storage.remove(it -> it.getType() == Material.ARROW && Storage.plain(it), 32 - c.arrows);
            c.arrows += got;
        }
        Tools.Kind[] slots = {Tools.Kind.BOOTS, Tools.Kind.LEGGINGS, Tools.Kind.CHESTPLATE, Tools.Kind.HELMET};
        for (int i = 0; i < 4; i++) {
            Tools.Kind k = slots[i];
            c.armor[i] = swap(col, c.armor[i], it -> Tools.kind(it) == k);
        }
    }

    private ItemStack swap(Colony col, ItemStack current, java.util.function.Predicate<ItemStack> kind) {
        double have = Tools.score(current);
        if (col.storage.bestScore(kind, Tools::score) <= have) return current;
        ItemStack better = col.storage.takeBest(kind, Tools::score);
        if (better == null) return current;
        if (current != null) m.deposit(col, List.of(current));
        return better;
    }

    // ───────────── targets ─────────────

    private LivingEntity target(Npc n, Colony col, long now) {
        if (n.enemy != null && valid(n, col, n.enemy) && now < n.enemyCheckAt + 40) return n.enemy;
        n.enemyCheckAt = now;
        double range = n.c.bow != null && n.c.arrows > 0 ? 24 : 18;
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        Location here = n.body.getLocation();
        for (Entity e : n.body.getNearbyEntities(range, 10, range)) {
            if (!(e instanceof LivingEntity le) || !valid(n, col, le)) continue;
            double d = le.getLocation().distanceSquared(here);
            if (d > 16 && !n.body.hasLineOfSight(le)) continue;
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        n.enemy = best;
        return best;
    }

    private boolean valid(Npc n, Colony col, LivingEntity e) {
        if (e == null || !e.isValid() || e.isDead() || e == n.body) return false;
        if (e.getWorld() != n.body.getWorld()) return false;
        if (col.region.distanceTo(e.getX(), e.getZ()) > 24) return false;
        return hostile(n, col, e);
    }

    /** Is this an enemy of this NPC? */
    public boolean hostile(Npc self, Colony col, LivingEntity e) {
        boolean rebel = self.c.status == Status.REBEL;
        if (plugin.prison().isDowned(e)) return false;
        if (e instanceof Player p) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
            if (col.isMember(p.getUniqueId())) return rebel;
            if (rebel) return false;
            Colony pc = plugin.colonies().of(p);
            if (pc != null && plugin.wars().atWar(pc, col)) return true;
            return m.aggressor(col, p);
        }
        Npc o = m.npc(e);
        if (o != null) {
            if (o.c.downed()) return false;
            Colony oc = plugin.colonies().colonyOf(o.c);
            if (rebel) return oc == col && o.c.status != Status.REBEL && (o.c.status == Status.CITIZEN);
            if (oc == col) return o.c.status == Status.REBEL;
            return oc != null && plugin.wars().atWar(oc, col);
        }
        if (plugin.travelers().isTraveler(e)) return false;
        if (rebel) return false;
        return e instanceof Enemy;
    }

    // ───────────── fighting ─────────────

    void engage(Npc n, Colony col, LivingEntity t, long now) {
        if (n.sleeping) m.brain().wake(n);
        Citizen c = n.c;
        Location me = n.body.getLocation(), them = t.getLocation();
        double d = me.distance(them);
        boolean ranged = c.bow != null && c.arrows > 0 && d > 4.5 && d < 24 && n.body.hasLineOfSight(t);
        n.activity = (c.status == Status.REBEL ? "Fighting for freedom against " : "Fighting ") + label(t);
        m.showHand(n);
        if (ranged) {
            n.mover.stop();
            CitizenBrain.face(n, them);
            if (now - n.lastShot >= 30) {
                n.lastShot = now;
                shoot(n, t);
            }
            return;
        }
        if (d > 2.3) {
            n.mover.moveTo(them, plugin.settings().runSpeed, 1.7);
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, them);
        if (now - n.lastAttack >= 16) {
            n.lastAttack = now;
            hit(n, col, t);
        }
    }

    private void hit(Npc n, Colony col, LivingEntity t) {
        Citizen c = n.c;
        double dmg = Tools.meleeDamage(c.weapon) * c.trait.combat();
        n.body.swingMainHand();
        t.damage(dmg, n.body);
        Fx.sound(n.body.getLocation(), "minecraft:entity.player.attack.strong", 0.7f, 1f);
        if (c.weapon != null && Tools.wear(c.weapon, 1)) {
            c.weapon = null;
            Fx.sound(n.body.getLocation(), "minecraft:entity.item.break", 0.8f, 1f);
            m.showHand(n);
        }
    }

    private void shoot(Npc n, LivingEntity t) {
        Citizen c = n.c;
        Location eye = n.body.getEyeLocation();
        Location aim = t.getEyeLocation().subtract(0, 0.4, 0);
        double dist = eye.distance(aim);
        Vector dir = aim.toVector().subtract(eye.toVector());
        dir.setY(dir.getY() + dist * 0.12);
        dir.normalize();
        double spread = 0.04;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        dir.add(new Vector(r.nextGaussian() * spread, r.nextGaussian() * spread * 0.5, r.nextGaussian() * spread)).normalize();
        Arrow a = n.body.launchProjectile(Arrow.class, dir.multiply(2.2));
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        int power = c.bow.getEnchantmentLevel(Enchantment.POWER);
        a.setDamage((2.0 + (power > 0 ? 0.5 * power + 0.5 : 0)) * c.trait.combat());
        Fx.sound(eye, "minecraft:entity.arrow.shoot", 0.8f, 1f);
        c.arrows--;
        if (Tools.wear(c.bow, 1)) {
            c.bow = null;
            Fx.sound(eye, "minecraft:entity.item.break", 0.8f, 1f);
        }
        m.showHand(n);
    }

    private static String label(LivingEntity e) {
        if (e instanceof Player p) return p.getName();
        if (e.customName() != null) return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.customName());
        return e.getType().name().toLowerCase().replace('_', ' ');
    }

    // ───────────── patrols ─────────────

    private void patrol(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        List<Location> points = waypoints(col, w, n.c.militia);
        if (points.isEmpty()) return;
        if (n.waypoint < 0 || n.waypoint >= points.size()) {
            n.waypoint = (int) (Math.abs(n.c.id.getLeastSignificantBits()) % points.size());
            n.waypointUntil = 0;
        }
        Location wp = points.get(n.waypoint);
        if (!n.mover.near(wp, 1.8)) {
            n.mover.moveTo(wp, plugin.settings().walkSpeed, 1.3);
            n.activity = n.c.militia ? "Militia patrol" : "Patrolling";
            n.waypointUntil = 0;
            return;
        }
        n.mover.stop();
        if (n.waypointUntil == 0) n.waypointUntil = now + 300 + ThreadLocalRandom.current().nextInt(300);
        n.activity = n.c.militia ? "Standing guard (militia)" : "Keeping watch";
        if (now > n.waypointUntil) {
            n.waypoint = (n.waypoint + 1) % points.size();
            n.waypointUntil = 0;
        }
    }

    /** Guard tower tops, or a ring around the Town Hall. */
    List<Location> waypoints(Colony col, World w, boolean militia) {
        List<Location> out = new ArrayList<>();
        if (!militia) {
            for (Building b : col.buildings(BuildingType.TOWER)) if (b.anchor != null) out.add(b.anchor.center(w));
        }
        Location core = col.coreLocation();
        if (core == null) return out;
        if (out.isEmpty()) {
            int r = militia ? 6 : Math.max(6, Math.min(16, Math.min(col.region.width(), col.region.length()) / 3));
            int[][] dirs = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
            for (int[] d : dirs) {
                Location l = Mover.safeSpot(core.clone().add(d[0], 0, d[1]));
                if (l == null) l = Mover.safeSpot(core.clone().add(d[0] / 2.0, 0, d[1] / 2.0));
                if (l != null) out.add(l);
            }
        }
        return out;
    }

    // ───────────── rebels ─────────────

    void thinkRebel(Npc n, Colony col, long now) {
        LivingEntity t = target(n, col, now);
        // first, a raid on the State Chest for weapons (unless someone is already on them)
        Location chest = col.chestLocation();
        boolean close = t != null && t.getLocation().distanceSquared(n.body.getLocation()) < 16;
        if (!close && n.c.weapon == null && !n.gearChecked && chest != null && col.storage.has(it -> Tools.isMelee(Tools.kind(it)))) {
            if (!n.mover.near(chest, 2.8)) {
                n.mover.moveTo(chest, plugin.settings().runSpeed, 2.2);
                n.activity = "Raiding the State Chest for weapons!";
                return;
            }
            n.c.weapon = col.storage.takeBest(it -> Tools.isMelee(Tools.kind(it)), Tools::score);
            n.gearChecked = true;
            m.refresh(n);
            Fx.sound(chest, "minecraft:block.chest.open", 1f, 0.8f);
        }
        if (t != null) {
            engage(n, col, t, now);
            return;
        }
        n.enemy = null;
        // nobody to fight: run for the border
        Location here = n.body.getLocation();
        double cx = col.region.centerX(), cz = col.region.centerZ();
        Vector away = new Vector(here.getX() - cx, 0, here.getZ() - cz);
        if (away.lengthSquared() < 1) away = new Vector(1, 0, 0);
        away.normalize().multiply(10);
        Location to = Mover.safeSpot(here.clone().add(away));
        if (to != null) n.mover.moveTo(to, plugin.settings().runSpeed, 2);
        n.activity = "Escaping!";
        if (col.region.distanceTo(here.getX(), here.getZ()) > 6) plugin.prison().escaped(n, col);
    }
}
