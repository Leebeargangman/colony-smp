package com.colonysmp.prison;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Status;
import com.colonysmp.data.Trait;
import com.colonysmp.gui.TravelerMenu;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
 * Neutral travelers visit Town Halls. Members can recruit them (if there's a bed and food), turn them away,
 * or knock them down and take them prisoner - which costs the colony its standing with travelers.
 */
public final class TravelerManager implements Listener {

    public enum State { ARRIVING, WAITING, LEAVING }

    public static final class Traveler {
        public final String colonyId;
        public final String name;
        public final Trait trait;
        public final WanderingTrader body;
        final Mover mover;
        public State state = State.ARRIVING;
        long waitUntil, leaveBy;
        boolean hit;

        Traveler(String colonyId, String name, Trait trait, WanderingTrader body) {
            this.colonyId = colonyId;
            this.name = name;
            this.trait = trait;
            this.body = body;
            this.mover = new Mover(body);
        }
    }

    private final ColonySMP plugin;
    private final Map<String, Traveler> byColony = new HashMap<>();
    private final Map<UUID, Traveler> byEntity = new HashMap<>();

    public TravelerManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public boolean isTraveler(Entity e) {
        return e != null && (byEntity.containsKey(e.getUniqueId()) || e.getPersistentDataContainer().has(Keys.TRAVELER, PersistentDataType.STRING));
    }

    public Traveler of(Colony col) {
        return byColony.get(col.id);
    }

    public Traveler traveler(Entity e) {
        return e == null ? null : byEntity.get(e.getUniqueId());
    }

    // ───────────── ticking ─────────────

    /** Every tick: walking. */
    public void move(long now) {
        for (Traveler t : new ArrayList<>(byEntity.values())) {
            if (!t.body.isValid()) {
                forget(t);
                continue;
            }
            if (!plugin.prison().isDowned(t.body)) t.mover.tick(plugin.npcs(), now);
        }
    }

    /** Every second: arrivals, waiting, leaving. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (plugin.settings().travelersEnabled) {
            for (Colony col : plugin.colonies().list()) {
                if (byColony.containsKey(col.id) || col.core == null || !plugin.npcs().active(col)) continue;
                World w = col.world();
                long day = NpcManager.day(w);
                long t = w.getTime();
                if (col.lastTravelerDay >= day || t < 3000 || t > 9000) continue;
                col.lastTravelerDay = day;
                double chance = col.reputation < 15 ? 0 : plugin.settings().travelerChance * Math.min(1.6, col.reputation / 50.0);
                if (ThreadLocalRandom.current().nextDouble() < chance) spawn(col);
            }
        }
        for (Traveler t : new ArrayList<>(byEntity.values())) {
            if (!t.body.isValid()) {
                forget(t);
                continue;
            }
            Colony col = plugin.colonies().get(t.colonyId);
            if (col == null) {
                t.body.remove();
                forget(t);
                continue;
            }
            if (plugin.prison().isDowned(t.body)) continue;
            Location core = col.coreLocation();
            switch (t.state) {
                case ARRIVING -> {
                    if (core == null) {
                        leave(col, t, null);
                        break;
                    }
                    if (t.mover.near(core, 4)) {
                        t.mover.stop();
                        t.state = State.WAITING;
                        t.waitUntil = now + plugin.settings().travelerWaitMinutes * 60_000L;
                        announce(col, t);
                    } else {
                        t.mover.moveTo(core, plugin.settings().walkSpeed * 0.85, 3.5);
                    }
                }
                case WAITING -> {
                    if (core != null && !t.mover.near(core, 6)) t.mover.moveTo(core, plugin.settings().walkSpeed * 0.8, 3.5);
                    if (now > t.waitUntil) leave(col, t, "<gray>" + Text.esc(t.name) + " grew tired of waiting and moved on.");
                }
                case LEAVING -> {
                    if (now > t.leaveBy || col.region.distanceTo(t.body.getX(), t.body.getZ()) > 8) {
                        t.body.remove();
                        forget(t);
                    }
                }
            }
        }
    }

    private void spawn(Colony col) {
        World w = col.world();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = null;
        for (int i = 0; i < 12 && at == null; i++) {
            int x, z;
            switch (r.nextInt(4)) {
                case 0 -> {
                    x = col.region.minX() - 3;
                    z = r.nextInt(col.region.minZ(), col.region.maxZ() + 1);
                }
                case 1 -> {
                    x = col.region.maxX() + 3;
                    z = r.nextInt(col.region.minZ(), col.region.maxZ() + 1);
                }
                case 2 -> {
                    z = col.region.minZ() - 3;
                    x = r.nextInt(col.region.minX(), col.region.maxX() + 1);
                }
                default -> {
                    z = col.region.maxZ() + 3;
                    x = r.nextInt(col.region.minX(), col.region.maxX() + 1);
                }
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (plugin.colonies().at(w.getName(), x, z) != null) continue;
            int y = w.getHighestBlockYAt(x, z) + 1;
            Location l = Mover.safeSpot(new Location(w, x + 0.5, y, z + 0.5));
            if (l != null && !l.getBlock().isLiquid()) at = l;
        }
        if (at == null) return;
        String name = Names.random();
        Trait trait = Trait.random();
        WanderingTrader body;
        try {
            body = w.spawn(at, WanderingTrader.class, CreatureSpawnEvent.SpawnReason.CUSTOM, wt -> {
                wt.setPersistent(false);
                wt.setRemoveWhenFarAway(false);
                wt.setAware(false);
                wt.setCollidable(false);
                wt.setDespawnDelay(0);
                wt.setCanDrinkMilk(false);
                wt.setCanDrinkPotion(false);
                wt.getPersistentDataContainer().set(Keys.TRAVELER, PersistentDataType.STRING, col.id);
                AttributeInstance fr = wt.getAttribute(Attribute.FOLLOW_RANGE);
                if (fr != null) fr.setBaseValue(96);
                wt.customName(Text.mm("<#c9a26b>🎒 <white>" + name + " <gray>— " + trait.display));
                wt.setCustomNameVisible(true);
            });
        } catch (IllegalArgumentException e) {
            return;
        }
        if (body == null || !body.isValid()) return;
        Traveler t = new Traveler(col.id, name, trait, body);
        byColony.put(col.id, t);
        byEntity.put(body.getUniqueId(), t);
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<#c9a26b>A traveler, <white>" + Text.esc(name) + "</white>, is approaching the Town Hall.");
        }
    }

    private void announce(Colony col, Traveler t) {
        Component msg = Text.mm(Text.PREFIX + "<#c9a26b>Traveler <white>" + Text.esc(t.name) + "</white> waits at the Town Hall.</#c9a26b> <gray>Trait: <white>"
                + t.trait.display + "</white> (" + t.trait.description + ") ")
                .append(Text.mm("<green><bold>[Recruit]</bold></green>")
                        .clickEvent(ClickEvent.runCommand("/colony traveler recruit"))
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Needs a free bed and a day of food"))))
                .append(Component.text(" "))
                .append(Text.mm("<red><bold>[Turn Away]</bold></red>")
                        .clickEvent(ClickEvent.runCommand("/colony traveler dismiss"))
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Send them on their way"))));
        for (Player p : col.onlineMembers()) {
            p.sendMessage(msg);
            Fx.sound(p, "minecraft:entity.wandering_trader.ambient", 0.8f, 1f);
        }
    }

    // ───────────── decisions ─────────────

    public String recruit(Colony col, Player by) {
        Traveler t = byColony.get(col.id);
        if (t == null || t.state == State.LEAVING || plugin.prison().isDowned(t.body)) return "No traveler is waiting.";
        if (t.state != State.WAITING) return t.name + " hasn't reached the Town Hall yet.";
        if (plugin.sim().freeHouseBeds(col) <= 0) return "There's no free bed for " + t.name + ". Build or register another house.";
        double need = plugin.sim().dailyNeed(col) + plugin.settings().mealPoints;
        if (col.storage.foodPoints(plugin.settings().neverEat) < need) return "The State Chest doesn't hold a day of food for one more mouth.";
        if (col.citizens.size() >= plugin.settings().maxPopulation) return "The colony is at its population limit.";
        Location at = t.body.getLocation();
        t.body.remove();
        forget(t);
        Citizen c = plugin.npcs().newCitizen(col, plugin.sim().autoJob(col), Status.CITIZEN);
        c.name = t.name;
        c.trait = t.trait;
        c.origin = "Traveler";
        col.reputation = Math.min(100, col.reputation + plugin.settings().recruitBonus);
        col.addStability(2);
        plugin.sim().assignBeds(col);
        Npc n = plugin.npcs().spawn(col, c, at);
        if (n != null) n.body.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0);
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<green>" + Text.esc(c.name) + " joined the commune as a <white>" + c.job.display + "</white>! <gray>(" + c.trait.display + ", recruited by " + Text.esc(by.getName()) + ")");
        }
        plugin.requestSave();
        return null;
    }

    public String dismiss(Colony col, Player by) {
        Traveler t = byColony.get(col.id);
        if (t == null || t.state == State.LEAVING || plugin.prison().isDowned(t.body)) return "No traveler is waiting.";
        leave(col, t, "<gray>" + Text.esc(by.getName()) + " sent " + Text.esc(t.name) + " on their way.");
        return null;
    }

    private void leave(Colony col, Traveler t, String msg) {
        t.state = State.LEAVING;
        t.leaveBy = System.currentTimeMillis() + 90_000;
        Location here = t.body.getLocation();
        double cx = col.region.centerX(), cz = col.region.centerZ();
        double dx = here.getX() - cx, dz = here.getZ() - cz;
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double reach = Math.max(col.region.width(), col.region.length()) / 2.0 + 14;
        Location out = Mover.safeSpot(new Location(here.getWorld(), cx + dx / len * reach, here.getY(), cz + dz / len * reach));
        if (out == null) out = Mover.safeSpot(here.clone().add(dx / len * 20, 0, dz / len * 20));
        if (out != null) t.mover.moveTo(out, plugin.settings().walkSpeed, 2);
        if (msg != null) for (Player p : col.onlineMembers()) Text.send(p, msg);
    }

    private void forget(Traveler t) {
        byColony.remove(t.colonyId, t);
        byEntity.remove(t.body.getUniqueId());
    }

    // ───────────── violence ─────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Traveler t = byEntity.get(e.getEntity().getUniqueId());
        if (t == null) return;
        Entity src = NpcManager.source(e.getDamager());
        if (!(src instanceof Player p)) return;
        Colony col = plugin.colonies().get(t.colonyId);
        if (col == null || !col.isMember(p.getUniqueId())) return;
        if (!t.hit) {
            t.hit = true;
            col.reputation = Math.max(0, col.reputation - plugin.settings().attackPenalty);
            Text.bar(p, "<red>Attacking a traveler hurts your colony's reputation (" + col.reputation + "/100).");
        }
        if (t.state != State.LEAVING) leave(col, t, null);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Traveler t = byEntity.get(e.getEntity().getUniqueId());
        if (t == null) return;
        e.getDrops().clear();
        Colony col = plugin.colonies().get(t.colonyId);
        Player killer = e.getEntity().getKiller();
        if (col != null && killer != null && col.isMember(killer.getUniqueId())) {
            col.reputation = Math.max(0, col.reputation - plugin.settings().capturePenalty);
            for (Player p : col.onlineMembers()) Text.send(p, "<red>A traveler was killed. Word spreads: reputation " + col.reputation + "/100.");
        }
        forget(t);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Traveler t = byEntity.get(e.getRightClicked().getUniqueId());
        if (t == null) {
            if (isTraveler(e.getRightClicked())) {
                e.setCancelled(true); // a stray from before a restart
                e.getRightClicked().remove();
            }
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Colony col = plugin.colonies().get(t.colonyId);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (plugin.prison().isDowned(t.body)) {
            if (!Items.is(hand, Items.ROPE)) {
                Text.send(p, "<gray>They're down. Bind them with <white>Rope</white> to take them prisoner.");
                return;
            }
            Colony captor = plugin.colonies().of(p);
            Citizen c = plugin.prison().bindOther(p, t.body, hand, t.name, t.trait, "Traveler");
            if (c != null) {
                forget(t);
                Colony hurt = captor != null ? captor : col;
                if (hurt != null) {
                    hurt.reputation = Math.max(0, hurt.reputation - plugin.settings().capturePenalty);
                    for (Player m : hurt.onlineMembers()) Text.send(m, "<red>Capturing a traveler cost your colony reputation: " + hurt.reputation + "/100. Fewer travelers will visit.");
                }
            }
            return;
        }
        if (col != null && (col.isMember(p.getUniqueId()) || plugin.isBypassing(p))) {
            new TravelerMenu(plugin, p, col, t).open();
        } else {
            Text.send(p, "<#c9a26b>" + Text.esc(t.name) + "</#c9a26b> <gray>is visiting " + (col == null ? "a colony" : Text.esc(col.name)) + ".");
        }
    }

    public void colonyRemoved(Colony col) {
        Traveler t = byColony.remove(col.id);
        if (t != null) {
            t.body.remove();
            byEntity.remove(t.body.getUniqueId());
        }
    }

    public void shutdown() {
        for (Traveler t : new ArrayList<>(byEntity.values())) t.body.remove();
        byEntity.clear();
        byColony.clear();
    }

    public List<Traveler> all() {
        return new ArrayList<>(byEntity.values());
    }

    /** Admin: summon a traveler now. */
    public boolean summon(Colony col) {
        if (byColony.containsKey(col.id) || col.core == null || !plugin.npcs().active(col)) return false;
        spawn(col);
        return byColony.containsKey(col.id);
    }
}
