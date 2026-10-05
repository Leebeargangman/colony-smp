package com.colonysmp.prison;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Status;
import com.colonysmp.data.Trait;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Food;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Illusioner;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RimWorld-style captives: people go down instead of dying, get bound with rope, are led to prison cells,
 * and are either indoctrinated into equal citizens or shackled for forced labour - which needs guarding,
 * or the labourers rise up.
 */
public final class PrisonManager implements Listener {

    private final ColonySMP plugin;
    /** Downed people who aren't citizens yet (villagers, illagers, travelers): entity -> bleed-out time. */
    private final Map<UUID, Long> downedOthers = new HashMap<>();

    public PrisonManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── who is a person ─────────────

    /** Vanilla mobs that count as people and can be taken prisoner. */
    public static boolean human(Entity e) {
        return (e instanceof Villager || e instanceof WanderingTrader || e instanceof Pillager || e instanceof Vindicator
                || e instanceof Evoker || e instanceof Illusioner || e instanceof Witch) && !NpcManager.isBody(e);
    }

    public boolean isDowned(Entity e) {
        Npc n = plugin.npcs().npc(e);
        if (n != null) return n.c.downed();
        return downedOthers.containsKey(e.getUniqueId());
    }

    // ───────────── going down ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Npc n = plugin.npcs().npc(le);
        boolean traveler = plugin.travelers().isTraveler(le);
        if (n == null && !traveler && !human(le)) return;
        if (isDowned(le)) return; // a finishing blow
        if (le.getHealth() - e.getFinalDamage() > 0) return;
        if (!(e instanceof EntityDamageByEntityEvent) && !combat(e.getCause())) return;
        double chance = traveler ? plugin.settings().travelerDownedChance : plugin.settings().downedChance;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        e.setCancelled(true);
        le.setHealth(Math.min(maxHealth(le), 2.0));
        le.playHurtAnimation(0);
        if (n != null) downCitizen(n);
        else downOther(le);
    }

    private static boolean combat(EntityDamageEvent.DamageCause c) {
        return switch (c) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, THORNS, MAGIC -> true;
            default -> false;
        };
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private void downCitizen(Npc n) {
        if (n.sleeping) plugin.npcs().brain().wake(n);
        n.c.downedUntil = System.currentTimeMillis() + plugin.settings().downedSeconds * 1000L;
        n.mover.stop();
        n.enemy = null;
        n.visiting = null;
        n.body.setPose(Pose.SLEEPING, true);
        n.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, n.body.getLocation().add(0, 0.5, 0), 6, 0.3, 0.2, 0.3, 0.1);
        Fx.sound(n.body.getLocation(), "minecraft:entity.villager.hurt", 1f, 0.7f);
        plugin.npcs().showHand(n);
        plugin.npcs().rename(n);
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col != null) {
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<red>" + Text.esc(n.c.name) + " is down!</red> <gray>Right-click them to get them back up within " + plugin.settings().downedSeconds + "s, or they'll bleed out.");
            }
        }
    }

    private void downOther(LivingEntity le) {
        long until = System.currentTimeMillis() + plugin.settings().downedSeconds * 1000L;
        downedOthers.put(le.getUniqueId(), until);
        le.getPersistentDataContainer().set(Keys.DOWNED, PersistentDataType.LONG, until);
        if (le instanceof Mob m) {
            m.setAware(false);
            m.setTarget(null);
        }
        le.setPose(Pose.SLEEPING, true);
        le.customName(Text.mm("<red>✚ DOWNED <gray>- bind with Rope"));
        le.setCustomNameVisible(true);
        Fx.sound(le.getLocation(), "minecraft:entity.villager.hurt", 1f, 0.7f);
    }

    /** Every second: bleeding out. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Colony col : plugin.colonies().list()) {
            for (Citizen c : new ArrayList<>(col.citizens.values())) {
                if (!c.downed() || now < c.downedUntil) continue;
                Npc n = plugin.npcs().npc(c);
                c.downedUntil = 0;
                if (n != null) {
                    for (Player p : col.onlineMembers()) Text.send(p, "<red>✝ " + Text.esc(c.name) + " bled out.");
                    n.body.setPose(Pose.STANDING, false);
                    n.body.setHealth(0);
                } else {
                    plugin.npcs().remove(c);
                }
            }
            if (plugin.npcs().active(col)) labour(col, now);
            rebels(col, now);
        }
        var it = downedOthers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof LivingEntity le) || !le.isValid()) {
                it.remove();
                continue;
            }
            if (now >= e.getValue()) {
                it.remove();
                le.setPose(Pose.STANDING, false);
                le.setHealth(0);
            } else {
                long left = (e.getValue() - now) / 1000;
                le.customName(Text.mm("<red>✚ DOWNED <gray>(" + left + "s) - bind with Rope"));
            }
        }
    }

    /** A downed citizen helped up by their own colony. */
    public void rescue(Player p, Npc n) {
        n.c.downedUntil = 0;
        n.body.setPose(Pose.STANDING, false);
        n.body.setHealth(Math.min(maxHealth(n.body), 6));
        n.mover.sync();
        plugin.npcs().rename(n);
        n.body.getWorld().spawnParticle(Particle.HEART, n.body.getLocation().add(0, 1.5, 0), 5, 0.3, 0.3, 0.3, 0);
        Fx.sound(n.body.getLocation(), "minecraft:entity.villager.yes", 1f, 1f);
        Text.send(p, "You helped <white>" + Text.esc(n.c.name) + "</white> back to their feet.");
    }

    // ───────────── binding ─────────────

    /** Rope on a citizen body (downed). */
    public void useRope(Player p, Npc n, ItemStack rope) {
        if (!n.c.downed()) {
            Text.send(p, "<gray>Only someone who is <red>downed</red> can be bound.");
            return;
        }
        Colony captor = plugin.colonies().of(p);
        if (captor == null || captor.core == null) {
            Text.send(p, "<red>You need a colony with a Town Hall to hold prisoners.");
            return;
        }
        Citizen c = n.c;
        Colony from = plugin.colonies().colonyOf(c);
        if (from == captor && c.status != Status.REBEL) {
            Text.send(p, "<gray>That's your own comrade. Right-click without rope to help them up.");
            return;
        }
        useItem(p, rope);
        // their arms and tools are confiscated by the captor's State
        List<ItemStack> gear = new ArrayList<>();
        for (ItemStack it : new ItemStack[]{c.tool, c.weapon, c.bow, c.armor[0], c.armor[1], c.armor[2], c.armor[3]}) {
            if (it != null && !it.getType().isAir()) gear.add(it);
        }
        c.tool = c.weapon = c.bow = null;
        c.arrows = 0;
        java.util.Arrays.fill(c.armor, null);
        plugin.npcs().deposit(captor, gear);
        if (from != captor) {
            if (from != null) {
                for (Player m : from.onlineMembers()) {
                    Text.send(m, "<red>" + Text.esc(c.name) + " was captured by " + Text.esc(p.getName()) + " of " + Text.esc(captor.name) + "!");
                }
                c.origin = from.name;
            }
            plugin.colonies().transferCitizen(c, captor);
        }
        c.downedUntil = 0;
        c.status = Status.CAPTIVE;
        c.militia = false;
        c.militiaIssued = false;
        c.escortPlayer = p.getUniqueId();
        c.escortGuard = null;
        c.awaitingEscort = false;
        c.policy = Policy.INDOCTRINATE;
        c.cell = null;
        c.bed = null;
        n.body.setPose(Pose.STANDING, false);
        n.body.setLeashHolder(p);
        n.resetWork();
        plugin.npcs().refresh(n);
        boundMessage(p, c.name);
        plugin.requestSave();
    }

    /** Rope on a downed villager, illager or traveler: they become a captive citizen of the captor's colony. */
    public Citizen bindOther(Player p, LivingEntity le, ItemStack rope, String name, Trait trait, String origin) {
        if (!downedOthers.containsKey(le.getUniqueId())) {
            Text.send(p, "<gray>Only someone who is <red>downed</red> can be bound.");
            return null;
        }
        Colony captor = plugin.colonies().of(p);
        if (captor == null || captor.core == null) {
            Text.send(p, "<red>You need a colony with a Town Hall to hold prisoners.");
            return null;
        }
        useItem(p, rope);
        downedOthers.remove(le.getUniqueId());
        Location at = le.getLocation();
        Citizen c = plugin.npcs().newCitizen(captor, Job.NONE, Status.CAPTIVE);
        if (name != null) c.name = name;
        if (trait != null) c.trait = trait;
        c.origin = origin;
        c.escortPlayer = p.getUniqueId();
        le.remove();
        Npc n = plugin.npcs().spawn(captor, c, at);
        if (n != null) {
            n.body.setLeashHolder(p);
            plugin.npcs().refresh(n);
        }
        boundMessage(p, c.name);
        plugin.requestSave();
        return c;
    }

    private void boundMessage(Player p, String name) {
        Fx.sound(p.getLocation(), "minecraft:entity.leash_knot.place", 1f, 1f);
        Text.send(p, "You bound <white>" + Text.esc(name) + "</white>. Lead them into one of your <white>Prison Cells</white>, "
                + "or <yellow>Shift + Right-Click</yellow> them to hand them to your Guards.");
    }

    private static void useItem(Player p, ItemStack it) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        it.setAmount(it.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractOther(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity t = e.getRightClicked();
        if (!human(t) || plugin.travelers().isTraveler(t) || !(t instanceof LivingEntity le)) return;
        if (!downedOthers.containsKey(t.getUniqueId())) return;
        e.setCancelled(true);
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (!Items.is(hand, Items.ROPE)) {
            Text.send(e.getPlayer(), "<gray>They're down. Bind them with <white>Rope</white> to take them prisoner.");
            return;
        }
        String origin = Text.nice(t.getType().name());
        String name = le.customName() == null ? null : PlainTextComponentSerializer.plainText().serialize(le.customName());
        if (name != null && name.contains("DOWNED")) name = null;
        bindOther(e.getPlayer(), le, hand, name == null ? Names.random() : name, Trait.random(), origin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent e) {
        if (NpcManager.isBody(e.getEntity())) e.setDropLeash(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerUnleash(PlayerUnleashEntityEvent e) {
        if (NpcManager.isBody(e.getEntity())) e.setCancelled(true);
    }

    public void handToGuards(Player p, Npc n) {
        n.c.escortPlayer = null;
        n.c.escortGuard = null;
        n.c.awaitingEscort = true;
        n.body.setLeashHolder(null);
        Colony col = plugin.colonies().colonyOf(n.c);
        boolean guards = col != null && col.citizens.values().stream().anyMatch(c -> c.status == Status.CITIZEN && c.job == Job.GUARD);
        if (col == null || col.buildings(BuildingType.PRISON).isEmpty()) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. You have no Prison Cell: register one with the Blueprint Book.");
        } else if (plugin.sim().freeCell(col, n.c) == null) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. Every prison cell is full: add a bed to a cell or build another.");
        } else if (!guards) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. Assign a Guard to escort them, or lead them to a cell yourself.");
        } else {
            Text.send(p, "<white>" + Text.esc(n.c.name) + "</white> will be escorted to a free cell by your Guards.");
        }
    }

    public boolean feedByHand(Player p, Npc n, ItemStack hand) {
        if (!Food.isFood(hand, plugin.settings().neverEat)) return false;
        World w = n.body.getWorld();
        long today = NpcManager.day(w);
        useItem(p, hand);
        n.c.lastFedDay = today;
        n.c.unfedDays = 0;
        n.c.resistance = Math.max(0, n.c.resistance - 2);
        w.spawnParticle(Particle.HEART, n.body.getLocation().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
        Fx.sound(n.body.getLocation(), "minecraft:entity.generic.eat", 0.8f, 1f);
        Text.send(p, "You fed <white>" + Text.esc(n.c.name) + "</white>. <gray>Resistance " + Math.round(n.c.resistance) + "%");
        plugin.npcs().rename(n);
        return true;
    }

    // ───────────── captives ─────────────

    public void thinkCaptive(Npc n, Colony col, long now) {
        Citizen c = n.c;
        Player p = c.escortPlayer == null ? null : Bukkit.getPlayer(c.escortPlayer);
        if (p != null && p.getWorld() == n.body.getWorld()) {
            follow(n, p.getLocation());
            if (!n.body.isLeashed()) n.body.setLeashHolder(p);
            n.activity = "Bound, led by " + p.getName();
            checkCell(n, col);
            return;
        }
        if (c.escortPlayer != null) {
            c.escortPlayer = null;
            c.awaitingEscort = true;
            n.body.setLeashHolder(null);
        }
        Npc guard = null;
        if (c.escortGuard != null) {
            Citizen g = plugin.colonies().citizen(c.escortGuard);
            guard = g == null ? null : plugin.npcs().npc(g);
            if (guard == null || guard.escorting != c) {
                c.escortGuard = null;
                c.awaitingEscort = true;
                guard = null;
            }
        }
        if (guard != null) {
            follow(n, guard.body.getLocation());
            n.activity = "Bound, escorted by guard " + guard.c.name;
            return;
        }
        c.awaitingEscort = true;
        n.mover.stop();
        n.activity = col.buildings(BuildingType.PRISON).isEmpty() ? "Bound - you have no Prison Cell!" : "Bound, waiting for a guard escort";
    }

    private void follow(Npc n, Location to) {
        Location me = n.body.getLocation();
        double d = me.distance(to);
        if (d > 14) {
            Location s = Mover.safeSpot(to);
            if (s != null) {
                n.body.teleport(s);
                n.mover.sync();
            }
        } else if (d > 2.6) {
            n.mover.moveTo(to, plugin.settings().runSpeed, 2.0);
        } else {
            n.mover.stop();
        }
    }

    /** A captive standing in (or at the door of) a cell with a free bed is locked up. */
    private void checkCell(Npc n, Colony col) {
        Location at = n.body.getLocation();
        for (Building cell : col.buildings(BuildingType.PRISON)) {
            boolean in = inside(cell, at);
            if (!in) {
                Location out = outsideDoor(col, cell);
                in = out != null && out.getWorld() == at.getWorld() && out.distanceSquared(at) < 2.5 * 2.5;
            }
            if (!in) continue;
            if (plugin.sim().freeCell(col, n.c) != cell && !hasRoom(col, cell, n.c)) {
                Player p = n.c.escortPlayer == null ? null : Bukkit.getPlayer(n.c.escortPlayer);
                if (p != null) Text.bar(p, "<red>" + cell.label() + " is full.");
                continue;
            }
            imprison(col, n, cell);
            return;
        }
    }

    private boolean hasRoom(Colony col, Building cell, Citizen who) {
        int held = 0;
        for (Citizen c : col.citizens.values()) {
            if (c != who && cell.id.equals(c.cell) && (c.status == Status.PRISONER || c.status == Status.SLAVE)) held++;
        }
        return held < cell.beds.size();
    }

    public void imprison(Colony col, Npc n, Building cell) {
        Citizen c = n.c;
        c.status = Status.PRISONER;
        c.cell = cell.id;
        c.bed = null;
        int base = ThreadLocalRandom.current().nextInt(plugin.settings().resistanceMin, plugin.settings().resistanceMax + 1);
        c.resistance = Math.max(1, Math.min(100, base + c.trait.resistance()));
        long today = NpcManager.day(n.body.getWorld());
        c.lastVisitDay = -1;
        c.lastFedDay = today;
        c.unfedDays = 0;
        c.escortPlayer = null;
        c.awaitingEscort = false;
        if (c.escortGuard != null) {
            Citizen g = plugin.colonies().citizen(c.escortGuard);
            Npc gn = g == null ? null : plugin.npcs().npc(g);
            if (gn != null) gn.escorting = null;
        }
        c.escortGuard = null;
        n.body.setLeashHolder(null);
        plugin.sim().assignBeds(col);
        Location in = cellSpot(col, cell);
        if (in != null) {
            n.body.teleport(in);
            n.mover.sync();
        }
        n.mover.stop();
        Fx.sound(n.body.getLocation(), "minecraft:block.iron_door.close", 1f, 0.8f);
        if (col.defaultPolicy == Policy.ENSLAVE) {
            if (takeShackles(null, col)) enslave(col, c);
        }
        plugin.npcs().refresh(n);
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<yellow>" + Text.esc(c.name) + "</yellow> was locked in " + cell.label() + ". <gray>Resistance <white>" + Math.round(c.resistance)
                    + "%</white>. Policy: <white>" + (c.status == Status.SLAVE ? Policy.ENSLAVE.display : Policy.INDOCTRINATE.display) + "</white> (change it in the prison menu).");
        }
        plugin.requestSave();
    }

    /** Guards bring captives who are waiting for an escort to a free cell. */
    public boolean escortDuty(Npc guard, Colony col, long now) {
        Citizen cap = guard.escorting;
        if (cap != null && (cap.status != Status.CAPTIVE || plugin.colonies().colonyOf(cap) != col || cap.escortPlayer != null)) {
            guard.escorting = null;
            cap = null;
        }
        if (cap == null) {
            if (col.buildings(BuildingType.PRISON).isEmpty()) return false;
            for (Citizen c : col.citizens.values()) {
                if (c.status != Status.CAPTIVE || !c.awaitingEscort || c.escortGuard != null || c.downed()) continue;
                if (plugin.npcs().npc(c) == null || plugin.sim().freeCell(col, c) == null) continue;
                boolean taken = false;
                for (Npc o : plugin.npcs().of(col)) if (o != guard && o.escorting == c) taken = true;
                if (taken) continue;
                cap = c;
                break;
            }
            if (cap == null) return false;
            guard.escorting = cap;
        }
        Npc cn = plugin.npcs().npc(cap);
        if (cn == null) {
            guard.escorting = null;
            return false;
        }
        if (cap.escortGuard == null) {
            if (!guard.mover.near(cn.body.getLocation(), 2.2)) {
                guard.mover.moveTo(cn.body.getLocation(), plugin.settings().walkSpeed, 1.8);
                guard.activity = "Collecting captive " + cap.name;
                return true;
            }
            cap.escortGuard = guard.c.id;
            cap.awaitingEscort = false;
            cn.body.setLeashHolder(guard.body);
        }
        Building cell = plugin.sim().freeCell(col, cap);
        if (cell == null) {
            guard.activity = "No free prison cell!";
            return false;
        }
        Location target = outsideDoor(col, cell);
        if (target == null) target = cellSpot(col, cell);
        if (target == null) return false;
        if (!guard.mover.near(target, 1.8)) {
            guard.mover.moveTo(target, plugin.settings().walkSpeed, 1.4);
            guard.activity = "Escorting " + cap.name + " to " + cell.label();
            return true;
        }
        if (!cell.doors.isEmpty() && cell.doors.get(0).loaded(guard.body.getWorld())) {
            guard.mover.openFor(cell.doors.get(0).block(guard.body.getWorld()), now, 40);
        }
        imprison(col, cn, cell);
        guard.escorting = null;
        return true;
    }

    // ───────────── prisoners ─────────────

    public void thinkPrisoner(Npc n, Colony col, long now) {
        Building cell = n.c.cell == null ? null : col.buildings.get(n.c.cell);
        if (cell == null) {
            n.c.status = Status.CAPTIVE;
            n.c.awaitingEscort = true;
            plugin.npcs().refresh(n);
            return;
        }
        if (!inside(cell, n.body.getLocation()) && !n.sleeping) {
            Location in = cellSpot(col, cell);
            if (in != null) {
                n.body.teleport(in);
                n.mover.sync();
            }
        }
        if (plugin.npcs().phase(n.body.getWorld()) == NpcManager.Phase.NIGHT) {
            plugin.npcs().brain().sleep(n, col, now);
            n.activity = n.sleeping ? "Sleeping in " + cell.label() : "Locked in " + cell.label();
            return;
        }
        if (n.sleeping) plugin.npcs().brain().wake(n);
        n.mover.stop();
        n.activity = "Locked in " + cell.label() + " (resistance " + Math.round(n.c.resistance) + "%)";
    }

    /** A warden's daily visit: a meal from the State Chest and an hour of re-education. */
    public void indoctrinate(Colony col, Citizen p, Npc warden) {
        World w = warden.body.getWorld();
        long today = NpcManager.day(w);
        p.lastVisitDay = today;
        double meal = plugin.settings().mealPoints;
        double got = col.storage.consumeFood(meal, plugin.settings().neverEat);
        boolean fed = got >= meal * 0.5;
        if (fed) {
            p.lastFedDay = today;
            p.unfedDays = 0;
            p.fed = Math.min(1, got / meal);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double amount = r.nextInt(plugin.settings().indoctrinationMin, plugin.settings().indoctrinationMax + 1)
                * (0.6 + col.stability / 100.0 * 0.8) * (fed ? 1 : 0.5) * warden.c.trait.work(Job.WARDEN);
        p.resistance = Math.max(0, p.resistance - amount);
        Npc pn = plugin.npcs().npc(p);
        if (pn != null) {
            Fx.sound(pn.body.getLocation(), fed ? "minecraft:entity.generic.eat" : "minecraft:entity.villager.no", 0.7f, 1f);
            plugin.npcs().rename(pn);
        }
        for (Player m : col.onlineMembers()) {
            if (pn != null && m.getWorld() == w && m.getLocation().distanceSquared(pn.body.getLocation()) < 40 * 40) {
                Text.bar(m, "<yellow>" + Text.esc(p.name) + "</yellow> <gray>resistance <white>" + Math.round(p.resistance) + "%</white> (-" + Math.round(amount) + ")"
                        + (fed ? "" : " <red>- no food in the State Chest!"));
            }
        }
        if (p.resistance <= 0) convert(col, p);
    }

    /** Resistance broken: they join the commune as an equal citizen. */
    public void convert(Colony col, Citizen p) {
        Building cell = p.cell == null ? null : col.buildings.get(p.cell);
        p.status = Status.CITIZEN;
        p.job = plugin.sim().autoJob(col);
        p.cell = null;
        p.bed = null;
        p.resistance = 0;
        col.addStability(2);
        plugin.sim().assignBeds(col);
        Npc n = plugin.npcs().npc(p);
        if (n != null) {
            if (cell != null) {
                Location out = outsideDoor(col, cell);
                if (out != null) {
                    n.body.teleport(out);
                    n.mover.sync();
                }
            }
            plugin.npcs().refresh(n);
            n.body.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, n.body.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.2);
        }
        for (Player m : col.onlineMembers()) {
            Text.title(m, "<red>☭ <gold>A New Comrade", "<white>" + Text.esc(p.name) + "</white> <gray>joins as an Equal Citizen", 10, 60, 20);
            Text.send(m, "<gold>" + Text.esc(p.name) + "</gold> has embraced the Commune and joined as an <green>Equal Citizen</green> (" + p.job.display + ").");
        }
        plugin.requestSave();
    }

    // ───────────── policies ─────────────

    public String setPolicy(Player by, Colony col, Citizen c, Policy policy) {
        if (c.status != Status.PRISONER && c.status != Status.SLAVE) return "Only prisoners in a cell can be sentenced.";
        if (policy == Policy.ENSLAVE) {
            if (c.status == Status.SLAVE) return c.name + " is already doing forced labour.";
            if (!takeShackles(by, col)) return "You need Iron Shackles (in your inventory or the State Chest). Craft them: iron ingot, chain, iron ingot.";
            enslave(col, c);
            String warn = col.stability < plugin.settings().uprisingStability
                    ? " <red>Warning: stability is below " + Math.round(plugin.settings().uprisingStability) + "%, they will rise up!" : "";
            return "<green>" + Text.esc(c.name) + " has been shackled and sent to forced " + (c.labour == Job.LUMBERJACK ? "logging" : "mining")
                    + ". Keep a Guard within " + Math.round(plugin.settings().slaveGuardRadius) + " blocks." + warn;
        }
        if (c.status == Status.SLAVE) {
            c.status = Status.PRISONER;
            plugin.npcs().deposit(col, List.of(plugin.items().shackles()));
            Npc n = plugin.npcs().npc(c);
            if (n != null) {
                n.resetWork();
                plugin.npcs().refresh(n);
            }
        }
        c.policy = Policy.INDOCTRINATE;
        plugin.requestSave();
        return "<green>" + Text.esc(c.name) + " will be visited by a Warden daily until they join the commune.";
    }

    private void enslave(Colony col, Citizen c) {
        c.status = Status.SLAVE;
        c.policy = Policy.ENSLAVE;
        boolean mine = col.buildings(BuildingType.MINE).stream().anyMatch(b -> !b.exhausted);
        c.labour = mine ? Job.MINER : Job.LUMBERJACK;
        c.unmonitoredSeconds = 0;
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            n.resetWork();
            plugin.npcs().refresh(n);
            Fx.sound(n.body.getLocation(), "minecraft:block.chain.place", 1f, 0.8f);
        }
        plugin.requestSave();
    }

    private boolean takeShackles(Player p, Colony col) {
        if (p != null) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (Items.is(it, Items.SHACKLES)) {
                    if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
                    return true;
                }
            }
        }
        return col.storage.remove(it -> Items.is(it, Items.SHACKLES), 1) == 1;
    }

    public void setLabour(Colony col, Citizen c, Job job) {
        c.labour = job == Job.LUMBERJACK ? Job.LUMBERJACK : Job.MINER;
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            n.resetWork();
            plugin.npcs().refresh(n);
        }
    }

    /** Lets a prisoner go: they walk free and leave the colony. */
    public void release(Player by, Colony col, Citizen c) {
        if (c.origin != null && c.origin.equals("Traveler")) col.reputation = Math.min(100, col.reputation + 3);
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            n.body.getWorld().spawnParticle(Particle.CLOUD, n.body.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        }
        plugin.npcs().remove(c);
        Text.send(by, "<gray>" + Text.esc(c.name) + " was released and left the colony.");
    }

    // ───────────── forced labour ─────────────

    public void thinkSlave(Npc n, Colony col, long now) {
        Building cell = n.c.cell == null ? null : col.buildings.get(n.c.cell);
        if (cell == null) {
            n.c.status = Status.CAPTIVE;
            n.c.awaitingEscort = true;
            plugin.npcs().refresh(n);
            return;
        }
        NpcManager.Phase ph = plugin.npcs().phase(n.body.getWorld());
        if (ph == NpcManager.Phase.WORK) {
            if (n.sleeping) plugin.npcs().brain().wake(n);
            if (inside(cell, n.body.getLocation())) {
                Location out = outsideDoor(col, cell);
                if (!cell.doors.isEmpty() && cell.doors.get(0).loaded(n.body.getWorld())) {
                    n.mover.openFor(cell.doors.get(0).block(n.body.getWorld()), now, 30);
                }
                if (out != null) {
                    n.body.teleport(out);
                    n.mover.sync();
                }
            }
            if (n.c.labour == Job.MINER && col.buildings(BuildingType.MINE).stream().anyMatch(b -> !b.exhausted)) {
                plugin.npcs().brain().mining.think(n, col, now);
            } else {
                plugin.npcs().brain().logging.think(n, col, now, null);
            }
            n.activity = "Forced labour: " + n.activity;
            return;
        }
        // back to the cell for the night
        if (!inside(cell, n.body.getLocation())) {
            Location out = outsideDoor(col, cell);
            if (out != null && !n.mover.near(out, 1.8)) {
                n.mover.moveTo(out, plugin.settings().walkSpeed, 1.3);
                n.activity = "Marched back to " + cell.label();
                return;
            }
            Location in = cellSpot(col, cell);
            if (in != null) {
                n.body.teleport(in);
                n.mover.sync();
            }
            Fx.sound(n.body.getLocation(), "minecraft:block.iron_door.close", 0.8f, 1f);
        }
        n.mover.stop();
        if (ph == NpcManager.Phase.NIGHT) plugin.npcs().brain().sleep(n, col, now);
        n.activity = n.sleeping ? "Sleeping in " + cell.label() : "Locked in " + cell.label();
    }

    /** Guards keep watch over the forced labourers (one guard per work gang). */
    public boolean overseeDuty(Npc guard, Colony col, long now) {
        if (plugin.npcs().phase(guard.body.getWorld()) != NpcManager.Phase.WORK) return false;
        List<Npc> miners = new ArrayList<>(), loggers = new ArrayList<>();
        for (Npc n : plugin.npcs().of(col)) {
            if (n.c.status != Status.SLAVE || n.c.downed()) continue;
            Building cell = n.c.cell == null ? null : col.buildings.get(n.c.cell);
            if (cell != null && inside(cell, n.body.getLocation())) continue;
            (n.c.labour == Job.LUMBERJACK ? loggers : miners).add(n);
        }
        List<List<Npc>> gangs = new ArrayList<>();
        if (!miners.isEmpty()) gangs.add(miners);
        if (!loggers.isEmpty()) gangs.add(loggers);
        if (gangs.isEmpty()) return false;
        List<Npc> guards = new ArrayList<>();
        for (Npc n : plugin.npcs().of(col)) {
            if (n.c.status == Status.CITIZEN && n.c.job == Job.GUARD && !n.c.militia && !n.c.downed() && n.escorting == null) guards.add(n);
        }
        guards.sort(java.util.Comparator.comparing(n -> n.c.id));
        int idx = guards.indexOf(guard);
        if (idx < 0 || idx >= gangs.size()) return false;
        List<Npc> gang = gangs.get(idx);
        double x = 0, y = 0, z = 0;
        for (Npc s : gang) {
            Location l = s.body.getLocation();
            x += l.getX();
            y += l.getY();
            z += l.getZ();
        }
        Location center = new Location(guard.body.getWorld(), x / gang.size(), y / gang.size(), z / gang.size());
        if (!guard.mover.near(center, 6)) guard.mover.moveTo(center, plugin.settings().walkSpeed, 5);
        else guard.mover.stop();
        guard.activity = "Watching the forced labourers";
        return true;
    }

    /** Every second: is every working labourer watched? Has stability collapsed? */
    private void labour(Colony col, long now) {
        if (col.uprisingSince > 0) return;
        List<Npc> slaves = new ArrayList<>();
        for (Npc n : plugin.npcs().of(col)) if (n.c.status == Status.SLAVE && !n.c.downed()) slaves.add(n);
        if (slaves.isEmpty()) return;
        if (col.stability < plugin.settings().uprisingStability) {
            uprising(col, "stability fell below " + Math.round(plugin.settings().uprisingStability) + "%");
            return;
        }
        if (plugin.npcs().phase(col.world()) != NpcManager.Phase.WORK) {
            for (Npc s : slaves) s.c.unmonitoredSeconds = 0;
            return;
        }
        double r2 = plugin.settings().slaveGuardRadius * plugin.settings().slaveGuardRadius;
        List<Npc> guards = new ArrayList<>();
        for (Npc n : plugin.npcs().of(col)) {
            if (n.c.status == Status.CITIZEN && (n.c.job == Job.GUARD || n.c.militia) && !n.c.downed()) guards.add(n);
        }
        for (Npc s : slaves) {
            Building cell = s.c.cell == null ? null : col.buildings.get(s.c.cell);
            if (cell != null && inside(cell, s.body.getLocation())) {
                s.c.unmonitoredSeconds = 0;
                continue;
            }
            boolean watched = false;
            for (Npc g : guards) {
                if (g.body.getWorld() == s.body.getWorld() && g.body.getLocation().distanceSquared(s.body.getLocation()) <= r2) {
                    watched = true;
                    break;
                }
            }
            if (watched) {
                s.c.unmonitoredSeconds = 0;
                continue;
            }
            s.c.unmonitoredSeconds++;
            int grace = plugin.settings().slaveGraceSeconds;
            if (s.c.unmonitoredSeconds == Math.max(1, grace - 10)) {
                for (Player p : col.onlineMembers()) Text.bar(p, "<red>⚠ " + Text.esc(s.c.name) + " is working unguarded! Get a Guard within " + Math.round(plugin.settings().slaveGuardRadius) + " blocks.");
            }
            if (s.c.unmonitoredSeconds >= grace) {
                uprising(col, Text.esc(s.c.name) + " was left unguarded");
                return;
            }
        }
    }

    /** The forced labourers break their shackles, raid the State Chest for weapons and fight. */
    public void uprising(Colony col, String why) {
        long now = System.currentTimeMillis();
        col.uprisingSince = now;
        col.addStability(-8);
        int n = 0;
        for (Citizen c : new ArrayList<>(col.citizens.values())) {
            if (c.status != Status.SLAVE) continue;
            c.status = Status.REBEL;
            c.rebelSince = now;
            c.unmonitoredSeconds = 0;
            if (c.weapon == null) c.weapon = col.storage.takeBest(it -> Tools.isMelee(Tools.kind(it)), Tools::score);
            Npc b = plugin.npcs().npc(c);
            if (b != null) {
                if (b.sleeping) plugin.npcs().brain().wake(b);
                Building cell = c.cell == null ? null : col.buildings.get(c.cell);
                if (cell != null && inside(cell, b.body.getLocation())) {
                    Location out = outsideDoor(col, cell);
                    if (out != null) {
                        b.body.teleport(out);
                        b.mover.sync();
                    }
                }
                b.resetWork();
                plugin.npcs().refresh(b);
                b.body.getWorld().spawnParticle(Particle.CRIT, b.body.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);
                Fx.sound(b.body.getLocation(), "minecraft:block.chain.break", 1f, 0.7f);
            }
            c.cell = null;
            c.bed = null;
            n++;
        }
        if (n == 0) {
            col.uprisingSince = 0;
            return;
        }
        for (Player p : col.onlineMembers()) {
            Text.title(p, "<dark_red>⚠ UPRISING", "<red>" + n + " forced labourers broke their shackles!", 10, 80, 20);
            Fx.sound(p, "minecraft:event.raid.horn", 1f, 1.2f);
            Text.send(p, "<red>Internal uprising: " + why + ".</red> <gray>The rebels stole weapons from the State Chest. Down them and bind them with Rope, or they escape in "
                    + plugin.settings().rebelEscapeMinutes + " minutes.");
        }
        plugin.requestSave();
    }

    private void rebels(Colony col, long now) {
        if (col.uprisingSince <= 0) return;
        boolean any = false;
        long limit = plugin.settings().rebelEscapeMinutes * 60_000L;
        for (Citizen c : new ArrayList<>(col.citizens.values())) {
            if (c.status != Status.REBEL) continue;
            any = true;
            if (now - c.rebelSince > limit && !c.downed()) {
                Npc n = plugin.npcs().npc(c);
                if (n != null) escaped(n, col);
                else {
                    plugin.npcs().remove(c);
                    for (Player p : col.onlineMembers()) Text.send(p, "<red>" + Text.esc(c.name) + " escaped during the uprising.");
                }
            }
        }
        if (!any) {
            col.uprisingSince = 0;
            for (Player p : col.onlineMembers()) Text.send(p, "<green>The uprising is over.");
        }
    }

    public void escaped(Npc n, Colony col) {
        Citizen c = n.c;
        String loot = c.weapon == null ? "" : " with a stolen " + Text.nice(c.weapon.getType().name());
        for (Player p : col.onlineMembers()) Text.send(p, "<red>" + Text.esc(c.name) + " escaped" + loot + "!");
        n.body.getWorld().spawnParticle(Particle.CLOUD, n.body.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.05);
        plugin.npcs().remove(c);
    }

    // ───────────── cells ─────────────

    public static boolean inside(Building cell, Location l) {
        if (cell.min == null || cell.max == null) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= cell.min.x() && x <= cell.max.x() && z >= cell.min.z() && z <= cell.max.z() && y >= cell.min.y() - 1 && y <= cell.max.y();
    }

    /** A spot inside a cell to stand. */
    public Location cellSpot(Colony col, Building cell) {
        World w = col.world();
        if (w == null || cell == null || cell.min == null) return null;
        List<Location> spots = new ArrayList<>();
        for (int x = cell.min.x(); x <= cell.max.x(); x++) {
            for (int z = cell.min.z(); z <= cell.max.z(); z++) {
                for (int y = cell.min.y(); y <= Math.min(cell.max.y(), cell.min.y() + 1); y++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable() && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid()
                            && !org.bukkit.Tag.BEDS.isTagged(b.getRelative(BlockFace.DOWN).getType())) {
                        spots.add(new Location(w, x + 0.5, y, z + 0.5));
                    }
                }
            }
        }
        if (spots.isEmpty()) {
            if (!cell.beds.isEmpty()) return cell.beds.get(0).center(w).add(0, 0.6, 0);
            return cell.anchor == null ? null : cell.anchor.center(w);
        }
        return spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }

    /** The standing spot just outside a cell's door. */
    public Location outsideDoor(Colony col, Building cell) {
        World w = col.world();
        if (w == null || cell == null || cell.doors.isEmpty()) return null;
        BlockPos door = cell.doors.get(0);
        if (!door.loaded(w)) return null;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockPos o = door.relative(f);
            if (cell.contains(o)) continue;
            Location l = Mover.safeSpot(o.center(w));
            if (l != null && !inside(cell, l)) return l;
        }
        return null;
    }
}
