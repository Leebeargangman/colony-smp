package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.data.Trait;
import com.colonysmp.gui.CitizenMenu;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns, moves and thinks for every citizen body. Bodies only exist while their colony's Town Hall chunk is
 * loaded; they aren't saved with the world (the database is the record), so there are never duplicates.
 */
public final class NpcManager implements Listener {

    public enum Phase { WORK, EVENING, NIGHT }

    private static final String[] SKINS = {"plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga"};

    private final ColonySMP plugin;
    private final Map<UUID, Npc> byCitizen = new HashMap<>();
    private final Map<UUID, Npc> byEntity = new HashMap<>();
    private final CitizenBrain brain;
    private int pathBudget;
    /** Players who attacked a colony's people recently: colony id -> player -> until (ms). */
    private final Map<String, Map<UUID, Long>> aggressors = new HashMap<>();
    /** Farm tiles / trees being worked, so two workers don't pick the same one. */
    final Map<String, Set<BlockPos>> claimed = new HashMap<>();

    public NpcManager(ColonySMP plugin) {
        this.plugin = plugin;
        this.brain = new CitizenBrain(plugin, this);
    }

    public CitizenBrain brain() {
        return brain;
    }

    public ColonySMP plugin() {
        return plugin;
    }

    // ───────────── lookups ─────────────

    public Npc npc(Citizen c) {
        Npc n = c == null ? null : byCitizen.get(c.id);
        return n != null && n.valid() ? n : null;
    }

    public Npc npc(Entity e) {
        if (e == null) return null;
        Npc n = byEntity.get(e.getUniqueId());
        return n != null && n.valid() ? n : null;
    }

    public Collection<Npc> all() {
        return byCitizen.values();
    }

    public List<Npc> of(Colony col) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : byCitizen.values()) if (n.valid() && col.id.equals(n.c.colonyId)) out.add(n);
        return out;
    }

    public static boolean isBody(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Keys.CITIZEN, PersistentDataType.STRING);
    }

    /** At most a handful of path searches per tick, so a crowd of NPCs never causes a lag spike. */
    boolean pathBudget() {
        if (pathBudget <= 0) return false;
        pathBudget--;
        return true;
    }

    // ───────────── time ─────────────

    public Phase phase(World w) {
        long t = w.getTime();
        var s = plugin.settings();
        if (t >= s.night || t < s.workStart) return Phase.NIGHT;
        if (t >= s.evening) return Phase.EVENING;
        return Phase.WORK;
    }

    public static long day(World w) {
        return w.getFullTime() / 24000L;
    }

    // ───────────── economy helpers ─────────────

    public double efficiency(Colony col, Citizen c, Job job) {
        if (col.strike) return plugin.settings().strikeEfficiency;
        double stab = 0.5 + col.stability / 100.0 * 0.75;
        double fed = 0.55 + 0.45 * Math.max(0, Math.min(1, c.fed));
        return stab * fed * c.trait.work(job);
    }

    public long interval(double seconds, double eff, ItemStack tool) {
        if (eff <= 0.01) return Long.MAX_VALUE / 4;
        return Math.max(4, Math.round(seconds * 20 / (eff * Tools.speed(tool))));
    }

    /** Puts items in the State Chest; what doesn't fit is dropped beside it (or kept as spoils if it's unloaded). */
    public void deposit(Colony col, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        List<ItemStack> left = col.storage.addAll(items);
        if (left.isEmpty()) return;
        World w = col.world();
        Location at = col.chestLocation();
        if (w != null && at != null && w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            for (ItemStack it : left) w.dropItemNaturally(at.clone().add(0, 1, 0), it);
        } else if (col.spoils.size() < 270) {
            col.spoils.addAll(left);
        }
        long now = System.currentTimeMillis();
        if (now - col.lastFullWarn > 300_000) {
            col.lastFullWarn = now;
            for (Player p : col.onlineMembers()) Text.send(p, "<red>The Central State Chest is full! Workers are dropping goods beside it.");
        }
    }

    /** Makes sure a worker holds the right kind of tool. Returns false while walking to the chest for one. */
    public boolean ensureTool(Npc n, Colony col, Tools.Kind kind, long now) {
        Citizen c = n.c;
        if (c.tool != null && Tools.kind(c.tool) != kind) {
            deposit(col, List.of(c.tool));
            c.tool = null;
            showHand(n);
        }
        if (c.tool != null) {
            n.fetching = false;
            return true;
        }
        if (now < n.noToolUntil) return true;
        if (!col.storage.has(it -> Tools.kind(it) == kind)) {
            n.noToolUntil = now + 1200;
            n.fetching = false;
            return true;
        }
        Location chest = col.chestLocation();
        if (chest == null) return true;
        if (!n.mover.near(chest, 2.8)) {
            n.mover.moveTo(chest, plugin.settings().walkSpeed, 2.2);
            n.activity = "Fetching a " + Text.nice(kind.name()) + " from the State Chest";
            n.fetching = true;
            return false;
        }
        ItemStack best = col.storage.takeBest(it -> Tools.kind(it) == kind, Tools::score);
        n.fetching = false;
        if (best != null) {
            c.tool = best;
            showHand(n);
            Fx.sound(chest, "minecraft:block.chest.open", 0.5f, 1.1f);
        } else {
            n.noToolUntil = now + 1200;
        }
        return true;
    }

    /** Wears the citizen's tool; if it breaks, they'll fetch a new one from the State Chest. */
    public void wearTool(Npc n, Colony col) {
        if (n.c.tool == null) return;
        if (Tools.wear(n.c.tool, 1)) {
            String what = Text.nice(n.c.tool.getType().name());
            n.c.tool = null;
            n.noToolUntil = 0;
            showHand(n);
            Fx.sound(n.loc(), "minecraft:entity.item.break", 0.8f, 1f);
            n.body.getWorld().spawnParticle(Particle.SMOKE, n.body.getLocation().add(0, 1.2, 0), 8, 0.2, 0.2, 0.2, 0.01);
            for (Player p : col.onlineMembers()) {
                if (p.getWorld() == n.body.getWorld() && p.getLocation().distanceSquared(n.loc()) < 48 * 48) {
                    Text.bar(p, "<gray>" + Text.esc(n.c.name) + "'s " + what + " broke. <white>Fetching a replacement from the State Chest.");
                }
            }
        }
    }

    // ───────────── citizens ─────────────

    public Citizen newCitizen(Colony col, Job job, Status status) {
        Citizen c = new Citizen(UUID.randomUUID());
        c.name = Names.random();
        c.job = job;
        c.status = status;
        c.trait = Trait.random();
        c.skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];
        c.health = plugin.settings().citizenHealth;
        plugin.colonies().addCitizen(col, c);
        return c;
    }

    public void spawnStarting(Colony col) {
        Location core = col.coreLocation();
        if (core == null) return;
        for (Job j : plugin.settings().startingJobs) {
            Citizen c = newCitizen(col, j, Status.CITIZEN);
            Location at = spotNear(core, 2, 5);
            if (at != null && active(col)) spawn(col, c, at);
        }
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<green>Your first comrades have arrived: <white>" + col.citizens.size() + "</white> workers ready to serve the State. "
                    + "Right-click a citizen to see them or change their job.");
        }
        plugin.requestSave();
    }

    /** Removes a citizen for good (death, escape, release). */
    public void remove(Citizen c) {
        Npc n = byCitizen.get(c.id);
        if (n != null) despawn(n);
        plugin.colonies().removeCitizen(c);
        plugin.requestSave();
    }

    // ───────────── bodies ─────────────

    public boolean active(Colony col) {
        World w = col.world();
        if (w == null || col.core == null) return false;
        int cx = col.core.x() >> 4, cz = col.core.z() >> 4;
        if (!w.isChunkLoaded(cx, cz)) return false;
        Chunk ch = w.getChunkAt(cx, cz);
        return ch.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    private static boolean ticking(World w, Location l) {
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        return w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    /** Every second: bodies appear when a colony loads and vanish when it unloads. */
    public void activityLoop() {
        for (Colony col : plugin.colonies().list()) {
            if (!active(col)) {
                for (Npc n : new ArrayList<>(byCitizen.values())) {
                    if (!col.id.equals(n.c.colonyId)) continue;
                    if (escorted(n)) continue;
                    despawn(n);
                }
                continue;
            }
            int spawned = 0;
            for (Citizen c : new ArrayList<>(col.citizens.values())) {
                if (byCitizen.containsKey(c.id)) {
                    Npc n = byCitizen.get(c.id);
                    if (n.valid()) continue;
                    lost(n);
                }
                if (spawned >= 6) break;
                Location at = respawnSpot(col, c);
                if (at == null) continue;
                if (spawn(col, c, at) != null) spawned++;
            }
        }
    }

    /** A captive being led by an online player away from their captor's colony keeps their body. */
    private static boolean escorted(Npc n) {
        if (n.c.status != Status.CAPTIVE || n.c.escortPlayer == null) return false;
        Player p = Bukkit.getPlayer(n.c.escortPlayer);
        return p != null && p.getWorld() == n.body.getWorld() && p.getLocation().distanceSquared(n.body.getLocation()) < 32 * 32;
    }

    private Location respawnSpot(Colony col, Citizen c) {
        World w = col.world();
        Location core = col.coreLocation();
        if (w == null || core == null) return null;
        if ((c.status == Status.PRISONER || c.status == Status.SLAVE) && c.cell != null) {
            Building cell = col.buildings.get(c.cell);
            Location in = plugin.prison().cellSpot(col, cell);
            if (in != null && ticking(w, in)) return in;
        }
        if (c.hasLast) {
            Location l = new Location(w, c.lastX, c.lastY, c.lastZ);
            if (ticking(w, l) && col.region.grow(plugin.settings().workMargin + 16).contains(l)) {
                Location safe = Mover.safeSpot(l);
                if (safe != null) return safe;
            }
        }
        return spotNear(core, 2, 5);
    }

    /** A standable spot around a point, between min and max blocks away. */
    public Location spotNear(Location center, int min, int max) {
        World w = center.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            int dx = r.nextInt(-max, max + 1), dz = r.nextInt(-max, max + 1);
            if (Math.abs(dx) < min && Math.abs(dz) < min) continue;
            Location l = Mover.safeSpot(center.clone().add(dx, 0, dz));
            if (l != null) return l;
        }
        Location l = Mover.safeSpot(center.clone().add(0, 1, 0));
        return l != null ? l : center.clone().add(0.5, 1, 0.5);
    }

    public Npc spawn(Colony col, Citizen c, Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        Villager v;
        try {
            v = w.spawn(at, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, vil -> {
                vil.setPersistent(false);
                vil.setRemoveWhenFarAway(false);
                vil.setAware(false);
                vil.setCollidable(false);
                vil.setCanPickupItems(false);
                vil.getPersistentDataContainer().set(Keys.CITIZEN, PersistentDataType.STRING, c.id.toString());
                vil.setVillagerExperience(1);
                attr(vil, Attribute.MAX_HEALTH, plugin.settings().citizenHealth);
                attr(vil, Attribute.FOLLOW_RANGE, 64);
                double hp = c.health <= 0 ? plugin.settings().citizenHealth : Math.min(c.health, plugin.settings().citizenHealth);
                vil.setHealth(Math.max(1, hp));
                EntityEquipment eq = vil.getEquipment();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        eq.setDropChance(slot, 0f);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (v == null || !v.isValid()) return null;
        Npc n = new Npc(c, v);
        byCitizen.put(c.id, n);
        byEntity.put(v.getUniqueId(), n);
        refresh(n);
        return n;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** Updates looks, name and equipment after a change of job or status. */
    public void refresh(Npc n) {
        if (!(n.body instanceof Villager v)) return;
        Citizen c = n.c;
        Villager.Type type = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(c.skin.toLowerCase(Locale.ROOT)));
        if (type != null) v.setVillagerType(type);
        Villager.Profession prof = switch (c.status) {
            case CITIZEN -> c.job.profession();
            case CHILD -> Villager.Profession.NONE;
            default -> Villager.Profession.NITWIT;
        };
        if (v.getProfession() != prof) v.setProfession(prof);
        // the badge shows how hard they work: efficiency 0.5 -> stone, 1.2+ -> diamond
        Colony col = plugin.colonies().colonyOf(c);
        double eff = col == null ? 1 : efficiency(col, c, c.job);
        v.setVillagerLevel(Math.max(1, Math.min(5, (int) Math.round(eff * 4 - 1))));
        if (c.status == Status.CHILD) {
            v.setBaby();
        } else {
            v.setAdult();
        }
        v.setAgeLock(true);
        n.mover.setIronKeys(c.status == Status.CITIZEN && (c.job == Job.WARDEN || c.job == Job.GUARD));
        EntityEquipment eq = v.getEquipment();
        eq.setBoots(c.armor[0]);
        eq.setLeggings(c.armor[1]);
        eq.setChestplate(c.armor[2]);
        eq.setHelmet(c.armor[3]);
        showHand(n);
        rename(n);
    }

    public void showHand(Npc n) {
        Citizen c = n.c;
        ItemStack hand = null;
        if (c.downed()) hand = null;
        else if (c.status == Status.REBEL || (c.status == Status.CITIZEN && (c.job == Job.GUARD || c.militia))) hand = c.weapon != null ? c.weapon : c.bow;
        else if (c.status == Status.CITIZEN || c.status == Status.SLAVE) hand = c.tool;
        if (n.enemy != null && c.bow != null && c.arrows > 0 && n.body.getLocation().distanceSquared(n.enemy.getLocation()) > 25) hand = c.bow;
        if (same(hand, n.shownHand)) return;
        n.shownHand = hand == null ? null : hand.clone();
        n.body.getEquipment().setItemInMainHand(hand == null ? null : hand.clone());
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) return a == b;
        return a.getType() == b.getType();
    }

    public void rename(Npc n) {
        Citizen c = n.c;
        String icon = switch (c.status) {
            case CITIZEN -> c.militia ? "<red>⚔" : "<red>☭";
            case CHILD -> "<aqua>✿";
            case CAPTIVE -> "<gold>✋";
            case PRISONER -> "<yellow>▦";
            case SLAVE -> "<gray>⛓";
            case REBEL -> "<dark_red>⚠";
        };
        String name;
        if (c.downed()) {
            long left = Math.max(0, (c.downedUntil - System.currentTimeMillis()) / 1000);
            name = "<red>✚ DOWNED <white>" + Text.esc(c.name) + " <gray>(" + left + "s)";
        } else {
            name = icon + " <white>" + Text.esc(c.name) + " <gray>[" + c.title() + "]";
            if (c.status == Status.PRISONER) name += " <yellow>" + Math.round(c.resistance) + "%";
        }
        if (name.equals(n.shownName)) return;
        n.shownName = name;
        n.body.customName(Text.mm(name));
        n.body.setCustomNameVisible(c.downed() || c.status == Status.CAPTIVE || c.status == Status.REBEL);
    }

    /** Body lost (chunk unloaded, killed by another plugin...): forget it; the activity loop respawns it. */
    private void lost(Npc n) {
        byCitizen.remove(n.c.id);
        byEntity.remove(n.body.getUniqueId());
        release(n);
    }

    private void release(Npc n) {
        Set<BlockPos> cl = claimed.get(n.c.colonyId);
        if (cl != null) {
            if (n.target != null) cl.remove(n.target);
            if (n.jobKey != null && n.jobKey.startsWith("tree:")) cl.remove(BlockPos.parse(n.jobKey.substring(5)));
        }
    }

    public void despawn(Npc n) {
        rememberPosition(n.c);
        if (n.sleeping && n.body instanceof Villager v) {
            try {
                v.wakeup();
            } catch (IllegalStateException ignored) {
            }
        }
        n.mover.closeAll();
        n.c.health = n.body.getHealth();
        byCitizen.remove(n.c.id);
        byEntity.remove(n.body.getUniqueId());
        release(n);
        if (n.body.isValid()) n.body.remove();
    }

    public void despawn(Citizen c) {
        Npc n = byCitizen.get(c.id);
        if (n != null) despawn(n);
    }

    public void despawnColony(Colony col) {
        for (Npc n : new ArrayList<>(byCitizen.values())) if (col.id.equals(n.c.colonyId)) despawn(n);
    }

    public void despawnAll() {
        for (Npc n : new ArrayList<>(byCitizen.values())) despawn(n);
    }

    public void rememberPosition(Citizen c) {
        Npc n = byCitizen.get(c.id);
        if (n == null || !n.body.isValid()) return;
        Location l = n.body.getLocation();
        c.lastX = l.getX();
        c.lastY = l.getY();
        c.lastZ = l.getZ();
        c.hasLast = true;
        c.health = n.body.getHealth();
    }

    /** Respawns a body (after moving a citizen into a cell, between colonies...). */
    public Npc respawnAt(Citizen c, Location at) {
        Npc old = byCitizen.get(c.id);
        if (old != null) despawn(old);
        Colony col = plugin.colonies().colonyOf(c);
        if (col == null || !active(col)) return null;
        return spawn(col, c, at);
    }

    // ───────────── ticking ─────────────

    public void tick(long now) {
        pathBudget = 8;
        for (Npc n : new ArrayList<>(byCitizen.values())) {
            if (!n.valid()) {
                lost(n);
                continue;
            }
            try {
                n.mover.tick(this, now);
                if ((now + n.thinkOffset) % 20 == 0) unstick(n);
                if ((now + n.thinkOffset) % 10 == 0) brain.think(n, now);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Citizen " + n.c.name + " hit an error", ex);
                n.resetWork();
                n.mover.stop();
            }
        }
    }

    /** A body buried in blocks (a player built over it, a wall went up) steps out instead of suffocating. */
    private void unstick(Npc n) {
        if (n.sleeping || n.c.downed()) return;
        org.bukkit.block.Block feet = n.body.getLocation().getBlock(), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        if (!(feet.getType().isOccluding() || head.getType().isOccluding())) return;
        Location free = Mover.safeSpot(n.body.getLocation().add(0, 1, 0));
        if (free == null) free = Mover.safeSpot(n.body.getLocation().add(0, 3, 0));
        if (free != null) {
            n.body.teleport(free);
            n.mover.sync();
        }
    }

    // ───────────── hostility ─────────────

    public void markAggressor(Colony col, Player p) {
        aggressors.computeIfAbsent(col.id, k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis() + 30_000);
    }

    public boolean aggressor(Colony col, Player p) {
        Map<UUID, Long> m = aggressors.get(col.id);
        if (m == null) return false;
        Long until = m.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            m.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    /** Who really did it (the shooter of an arrow, the lighter of TNT). */
    public static Entity source(Entity damager) {
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            return s instanceof Entity e ? e : null;
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed t) return t.getSource();
        return damager;
    }

    /** May this attacker hurt this citizen? */
    public boolean mayHurt(Entity src, Npc victim) {
        Colony col = plugin.colonies().colonyOf(victim.c);
        if (src == null || col == null) return true;
        Npc atk = npc(src);
        if (atk != null) {
            Colony ac = plugin.colonies().colonyOf(atk.c);
            if (atk.c.status == Status.REBEL || victim.c.status == Status.REBEL) return atk.c.status != victim.c.status || ac != col;
            if (ac == col) return false;
            return ac != null && plugin.wars().atWar(ac, col);
        }
        if (src instanceof Player p) {
            if (plugin.isBypassing(p)) return true;
            if (victim.c.status == Status.REBEL) return true;
            if (col.isMember(p.getUniqueId())) return false;
            Colony pc = plugin.colonies().of(p);
            return pc != null && plugin.wars().atWar(pc, col);
        }
        if (plugin.travelers().isTraveler(src)) return false;
        return true;
    }

    // ───────────── events ─────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Npc attacker = npc(source(e.getDamager()));
        Npc victim = npc(e.getEntity());
        if (victim != null) {
            Entity src = source(e.getDamager());
            if (!mayHurt(src, victim)) {
                e.setCancelled(true);
                if (src instanceof Player p) {
                    Colony col = plugin.colonies().colonyOf(victim.c);
                    if (col != null && !col.isMember(p.getUniqueId())) {
                        markAggressor(col, p);
                        Text.bar(p, "<red>The citizens of " + Text.esc(col.name) + " are protected (no war). The guards have seen you!");
                    } else {
                        Text.bar(p, "<red>You can't hurt your own comrades.");
                    }
                }
                return;
            }
            victim.mover.pause(8, plugin.tick());
            return;
        }
        // our NPCs never hurt their own colony's people or pets by accident
        if (attacker != null && e.getEntity() instanceof Player p) {
            Colony col = plugin.colonies().colonyOf(attacker.c);
            if (col != null && col.isMember(p.getUniqueId()) && attacker.c.status != Status.REBEL) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSuffocate(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) return;
        Npc n = npc(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        unstick(n);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        Npc n = npc(e.getEntity());
        if (n == null) return;
        if (n.sleeping && n.body instanceof Villager v) {
            v.wakeup();
            n.sleeping = false;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (n.valid()) n.c.health = n.body.getHealth();
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        Npc n = byEntity.get(e.getEntity().getUniqueId());
        if (n == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Citizen c = n.c;
        Colony col = plugin.colonies().colonyOf(c);
        List<ItemStack> gear = new ArrayList<>();
        for (ItemStack it : new ItemStack[]{c.tool, c.weapon, c.bow, c.armor[0], c.armor[1], c.armor[2], c.armor[3]}) {
            if (it != null && !it.getType().isAir()) gear.add(it);
        }
        e.getDrops().addAll(gear);
        byCitizen.remove(c.id);
        byEntity.remove(n.body.getUniqueId());
        release(n);
        plugin.colonies().removeCitizen(c);
        if (col != null) {
            String how = e.getEntity().getKiller() != null ? " by " + Text.esc(e.getEntity().getKiller().getName()) : "";
            if (c.status.free()) col.addStability(-2);
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<red>✝ " + Text.esc(c.name) + " <gray>(" + c.title() + ") has died" + how + ".");
            }
            plugin.sim().assignBeds(col);
        }
        plugin.requestSave();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = npc(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null) return;
        if (Items.is(hand, Items.ROPE)) {
            plugin.prison().useRope(p, n, hand);
            return;
        }
        if (n.c.downed()) {
            if (col.isMember(p.getUniqueId())) plugin.prison().rescue(p, n);
            else Text.send(p, "<gray>" + Text.esc(n.c.name) + " is down. Bind them with <white>Rope</white> to take them prisoner.");
            return;
        }
        if (n.c.status == Status.CAPTIVE && col.isMember(p.getUniqueId()) && p.isSneaking()) {
            plugin.prison().handToGuards(p, n);
            return;
        }
        if (n.c.status == Status.PRISONER && col.isMember(p.getUniqueId()) && plugin.prison().feedByHand(p, n, hand)) return;
        if (col.isMember(p.getUniqueId()) || plugin.isBypassing(p)) {
            new CitizenMenu(plugin, p, col, n.c).open();
        } else {
            Text.send(p, "<white>" + Text.esc(n.c.name) + "</white> <gray>- " + n.c.title() + " of <gold>" + Text.esc(col.name) + "</gold>. <dark_gray>" + n.activity);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCareer(VillagerCareerChangeEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            Npc n = byEntity.get(en.getUniqueId());
            if (n == null) continue;
            rememberPosition(n.c);
            n.mover.closeAll();
            byCitizen.remove(n.c.id);
            byEntity.remove(en.getUniqueId());
            release(n);
        }
    }

    /** Stray bodies from a crash (they aren't meant to be saved): remove them when their chunk loads. */
    @EventHandler
    public void onLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (isBody(en) && !byEntity.containsKey(en.getUniqueId())) en.remove();
        }
    }
}
