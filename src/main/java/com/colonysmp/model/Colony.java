package com.colonysmp.model;

import com.colonysmp.core.Ledger;
import com.colonysmp.core.Treasury;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Territory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** A colony: its land, members, storage, treasury, buildings, citizens, policies and history. */
public final class Colony {

    /** A day's figures, for trends in the Town Hall. */
    public record DayStats(long day, int population, double foodDays, long balance, double mood, double stability,
                           long produced, long income, long expenses) {}

    public static final int KEEP_EVENTS = 150, KEEP_STATS = 14;

    public final String id;
    public String name;
    public UUID owner;
    public final Map<UUID, Role> members = new LinkedHashMap<>();
    /** Per-colony permission changes for the editable roles. */
    public final Map<Role, EnumSet<Perm>> perms = new EnumMap<>(Role.class);
    public String world;
    public final Territory territory = new Territory();
    /** Town Hall Core and its storage chest (null until the core is placed). */
    public BlockPos core, chest;
    public long createdAt;
    public long foundedDay;

    public final Ledger storage = new Ledger("s");
    public final Equipment equipment = new Equipment();
    public final Treasury treasury = new Treasury();

    public final Map<Integer, Building> buildings = new LinkedHashMap<>();
    public int nextBuildingId = 1;
    public final Map<UUID, Citizen> citizens = new LinkedHashMap<>();
    public final List<BuildOrder> buildQueue = new ArrayList<>();
    public final Map<String, CustomBlueprint> blueprints = new LinkedHashMap<>();
    public final Map<Integer, Squad> squads = new LinkedHashMap<>();
    public int nextSquadId = 1;

    public final Policies policies = new Policies();
    /** Stock targets per good: [min, max] (0 = no target). */
    public final Map<String, int[]> targets = new TreeMap<>();

    // the colony's state
    public double stability = 60;
    public int lowDays;
    public boolean protest;
    public long riotUntil;
    public int reputation = 50;
    public final List<Modifier> modifiers = new ArrayList<>();
    public final Deque<DayStats> stats = new ArrayDeque<>();

    // history
    public final Deque<ColonyEvent> events = new ArrayDeque<>();
    public final List<ColonyEvent> unsavedEvents = new ArrayList<>();
    public long nextEventId = 1;

    // simulation clock (world game time)
    public long lastSim = -1;
    public long lastDay = Long.MIN_VALUE;
    public long lastTravelerDay = -1;

    // protection and war
    public long shieldUntil;
    public long warCooldownUntil;
    public long mobilizedUntil;

    // onboarding
    public int questStage = 1;
    /** The free first house (ordered next to the Town Hall with its materials) has been given. */
    public boolean starterHouse;
    public boolean founded;
    public final Set<UUID> questHidden = new HashSet<>();

    // runtime only
    public final transient Set<UUID> invites = new HashSet<>();
    public transient boolean active;
    public transient long activeSince;
    /** Milliseconds the last background step took (admin performance view). */
    public transient double lastStepMs;
    public transient long producedToday, incomeToday, expensesToday;
    public transient long lastFullWarn, lastMaterialWarn, lastShortageWarn;

    public Colony(String id) {
        this.id = id;
    }

    // ───────────── world ─────────────

    public World world() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    public Location coreLocation() {
        World w = world();
        return w == null || core == null ? null : core.center(w);
    }

    public Location chestLocation() {
        World w = world();
        return w == null || chest == null ? null : chest.center(w);
    }

    public boolean shielded() {
        return shieldUntil > System.currentTimeMillis();
    }

    public boolean mobilized() {
        return mobilizedUntil > System.currentTimeMillis();
    }

    // ───────────── members ─────────────

    public boolean isMember(UUID u) {
        return members.containsKey(u);
    }

    public Role role(UUID u) {
        return members.get(u);
    }

    /** What a role may do here (built-in defaults plus this colony's changes). */
    public Set<Perm> perms(Role r) {
        EnumSet<Perm> own = perms.get(r);
        if (own != null && r.editable()) return own;
        return r.defaults();
    }

    public boolean can(Role r, Perm p) {
        return r != null && perms(r).contains(p);
    }

    public List<Player> onlineMembers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    public boolean anyMemberOnline() {
        for (UUID u : members.keySet()) if (Bukkit.getPlayer(u) != null) return true;
        return false;
    }

    // ───────────── citizens and buildings ─────────────

    public int newBuildingId() {
        return nextBuildingId++;
    }

    public Building building(int id) {
        return buildings.get(id);
    }

    public List<Building> buildings(BuildingType t) {
        List<Building> out = new ArrayList<>();
        for (Building b : buildings.values()) if (b.type == t) out.add(b);
        return out;
    }

    public List<Building> valid(BuildingType t) {
        List<Building> out = new ArrayList<>();
        for (Building b : buildings.values()) if (b.type == t && b.valid) out.add(b);
        return out;
    }

    public Building townHall() {
        for (Building b : buildings.values()) if (b.type == BuildingType.TOWN_HALL) return b;
        return null;
    }

    /** Settlement level (the Town Hall's level). */
    public int level() {
        Building th = townHall();
        return th == null ? 1 : th.level;
    }

    public int population() {
        int n = 0;
        for (Citizen c : citizens.values()) if (c.status.free()) n++;
        return n;
    }

    public List<Citizen> free() {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens.values()) if (c.status.free()) out.add(c);
        return out;
    }

    public List<Citizen> workers(Building b) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens.values()) if (c.workplace == b.id && c.status.free()) out.add(c);
        return out;
    }

    public Squad squad(int id) {
        return squads.get(id);
    }

    public void addStability(double d) {
        stability = Math.max(0, Math.min(100, stability + d));
    }

    public double modifierMood(long day) {
        double m = 0;
        for (Modifier mod : modifiers) if (mod.untilDay() >= day) m += mod.mood();
        return m;
    }

    public void addModifier(Modifier m) {
        modifiers.removeIf(x -> x.key().equals(m.key()));
        modifiers.add(m);
    }

    public int[] target(String good) {
        return targets.get(good);
    }

    // ───────────── history ─────────────

    public ColonyEvent log(ColonyEvent.Type type, long day, String message) {
        ColonyEvent e = new ColonyEvent(nextEventId++, System.currentTimeMillis(), day, type, message);
        events.addFirst(e);
        while (events.size() > KEEP_EVENTS) events.removeLast();
        unsavedEvents.add(e);
        return e;
    }

    public void addStats(DayStats s) {
        stats.addFirst(s);
        while (stats.size() > KEEP_STATS) stats.removeLast();
    }

    // ───────────── persistence ─────────────

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("owner", owner.toString());
        s.set("world", world);
        s.set("core", core == null ? null : core.toString());
        s.set("chest", chest == null ? null : chest.toString());
        s.set("created", createdAt);
        s.set("founded-day", foundedDay);
        for (Map.Entry<Role, EnumSet<Perm>> e : perms.entrySet()) {
            List<String> l = new ArrayList<>();
            for (Perm p : e.getValue()) l.add(p.name());
            s.set("perms." + e.getKey().name(), l);
        }
        s.set("next-building-id", nextBuildingId);
        s.set("next-squad-id", nextSquadId);
        s.set("next-event-id", nextEventId);
        s.set("treasury.balance", treasury.balance());
        s.set("treasury.next-id", treasury.nextId());
        s.set("storage.capacity", storage.capacity());
        policies.save(s.createSection("policies"));
        for (Map.Entry<String, int[]> e : targets.entrySet()) s.set("targets." + e.getKey(), e.getValue()[0] + "," + e.getValue()[1]);
        s.set("stability", stability);
        s.set("low-days", lowDays);
        s.set("protest", protest);
        s.set("riot-until", riotUntil);
        s.set("reputation", reputation);
        int i = 0;
        for (Modifier m : modifiers) {
            String k = "modifiers." + (i++);
            s.set(k + ".key", m.key());
            s.set(k + ".label", m.label());
            s.set(k + ".mood", m.mood());
            s.set(k + ".until", m.untilDay());
        }
        i = 0;
        for (DayStats d : stats) {
            s.set("stats." + (i++), d.day() + "," + d.population() + "," + d.foodDays() + "," + d.balance() + "," + d.mood() + ","
                    + d.stability() + "," + d.produced() + "," + d.income() + "," + d.expenses());
        }
        s.set("last-sim", lastSim);
        s.set("last-day", lastDay);
        s.set("last-traveler-day", lastTravelerDay);
        s.set("shield-until", shieldUntil);
        s.set("war-cooldown", warCooldownUntil);
        s.set("mobilized-until", mobilizedUntil);
        s.set("quest-stage", questStage);
        s.set("starter-house", starterHouse);
        s.set("founded", founded);
        List<String> hidden = new ArrayList<>();
        for (UUID u : questHidden) hidden.add(u.toString());
        s.set("quest-hidden", hidden);
    }

    public static Colony load(String id, ConfigurationSection s) {
        Colony c = new Colony(id);
        c.name = s.getString("name", "Colony");
        try {
            c.owner = UUID.fromString(s.getString("owner", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        c.world = s.getString("world");
        if (c.world == null) return null;
        c.core = BlockPos.parse(s.getString("core"));
        c.chest = BlockPos.parse(s.getString("chest"));
        c.createdAt = s.getLong("created");
        c.foundedDay = s.getLong("founded-day");
        ConfigurationSection p = s.getConfigurationSection("perms");
        if (p != null) {
            for (String k : p.getKeys(false)) {
                Role r = Role.parse(k);
                if (r == null || !r.editable()) continue;
                EnumSet<Perm> set = EnumSet.noneOf(Perm.class);
                for (String n : p.getStringList(k)) {
                    try {
                        set.add(Perm.valueOf(n));
                    } catch (IllegalArgumentException ignored) {
                        // a permission that no longer exists
                    }
                }
                c.perms.put(r, set);
            }
        }
        c.nextBuildingId = s.getInt("next-building-id", 1);
        c.nextSquadId = s.getInt("next-squad-id", 1);
        c.nextEventId = s.getLong("next-event-id", 1);
        c.policies.load(s.getConfigurationSection("policies"));
        ConfigurationSection t = s.getConfigurationSection("targets");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                String[] v = t.getString(k, "0,0").split(",");
                try {
                    c.targets.put(k, new int[]{Integer.parseInt(v[0]), Integer.parseInt(v[1])});
                } catch (RuntimeException ignored) {
                    // a malformed target is dropped
                }
            }
        }
        c.stability = s.getDouble("stability", 60);
        c.lowDays = s.getInt("low-days");
        c.protest = s.getBoolean("protest");
        c.riotUntil = s.getLong("riot-until");
        c.reputation = s.getInt("reputation", 50);
        ConfigurationSection m = s.getConfigurationSection("modifiers");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                c.modifiers.add(new Modifier(m.getString(k + ".key", k), m.getString(k + ".label", k), m.getDouble(k + ".mood"), m.getLong(k + ".until")));
            }
        }
        ConfigurationSection st = s.getConfigurationSection("stats");
        if (st != null) {
            List<DayStats> list = new ArrayList<>();
            for (String k : st.getKeys(false)) {
                String[] v = st.getString(k, "").split(",");
                if (v.length < 9) continue;
                try {
                    list.add(new DayStats(Long.parseLong(v[0]), Integer.parseInt(v[1]), Double.parseDouble(v[2]), Long.parseLong(v[3]),
                            Double.parseDouble(v[4]), Double.parseDouble(v[5]), Long.parseLong(v[6]), Long.parseLong(v[7]), Long.parseLong(v[8])));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            list.sort((a, b) -> Long.compare(b.day(), a.day()));
            c.stats.addAll(list);
        }
        c.lastSim = s.getLong("last-sim", -1);
        c.lastDay = s.getLong("last-day", Long.MIN_VALUE);
        c.lastTravelerDay = s.getLong("last-traveler-day", -1);
        c.shieldUntil = s.getLong("shield-until");
        c.warCooldownUntil = s.getLong("war-cooldown");
        c.mobilizedUntil = s.getLong("mobilized-until");
        c.questStage = s.getInt("quest-stage", 1);
        c.starterHouse = s.getBoolean("starter-house");
        c.founded = s.getBoolean("founded");
        for (String u : s.getStringList("quest-hidden")) {
            try {
                c.questHidden.add(UUID.fromString(u));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        return c;
    }

    /** Treasury balance saved with the colony (transactions are loaded separately). */
    public static long savedBalance(ConfigurationSection s) {
        return s.getLong("treasury.balance");
    }

    public static long savedNextTxn(ConfigurationSection s) {
        return s.getLong("treasury.next-id", 1);
    }
}
