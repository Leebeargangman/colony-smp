package com.colonysmp.data;

import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.ItemCodec;
import com.colonysmp.util.Region;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A colony: its land, members, State Chest, buildings and citizens. */
public final class Colony {

    public final String id;
    public String name;
    public UUID founder;
    public final Map<UUID, Rank> members = new LinkedHashMap<>();
    public String world;
    public Region region;
    /** Town Hall Core and Central State Chest (null until the core is placed). */
    public BlockPos core, chest;
    public long createdAt;
    public long shieldUntil;

    // the commune's state
    public double stability = 70;
    public int lowDays;
    public boolean strike;
    /** Day of the last sunset rationing; NEVER until the first sunset is seen. */
    public static final long NEVER = Long.MIN_VALUE;
    public long lastRationDay = NEVER;
    public double lastFed = 1;
    public int reputation = 50;
    public Policy defaultPolicy = Policy.INDOCTRINATE;
    public long lastChildDay = -1, lastTravelerDay = -1;

    // onboarding
    public int questStage = 1;
    public boolean founded;
    public final Set<UUID> questHidden = new HashSet<>();

    // buildings
    public final Map<String, Building> buildings = new LinkedHashMap<>();
    public final List<BuildJob> buildQueue = new ArrayList<>();
    public int nextBuildingId = 1;
    /** Structures copied with the wand, by lower-case name. */
    public final Map<String, CustomBlueprint> blueprints = new LinkedHashMap<>();

    public final Map<UUID, Citizen> citizens = new LinkedHashMap<>();
    public Storage storage;
    /** War spoils that didn't fit in the State Chest. */
    public final List<ItemStack> spoils = new ArrayList<>();

    // emergencies
    public long mobilizedUntil, mobilizeCooldownUntil, warCooldownUntil;
    public long uprisingSince;

    // runtime only
    public final transient Set<UUID> invites = new HashSet<>();
    public transient long lastFullWarn, lastMaterialWarn;

    public Colony(String id) {
        this.id = id;
    }

    public World world() {
        return Bukkit.getWorld(world);
    }

    public boolean isMember(UUID u) {
        return members.containsKey(u);
    }

    public Rank rank(UUID u) {
        return members.get(u);
    }

    public boolean leads(UUID u) {
        Rank r = members.get(u);
        return r != null && r.leads();
    }

    public List<Player> onlineMembers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
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

    public String newBuildingId() {
        return String.valueOf(nextBuildingId++);
    }

    public int population() {
        int n = 0;
        for (Citizen c : citizens.values()) if (c.status.free()) n++;
        return n;
    }

    public List<Citizen> byStatus(Status s) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens.values()) if (c.status == s) out.add(c);
        return out;
    }

    public List<Building> buildings(BuildingType t) {
        List<Building> out = new ArrayList<>();
        for (Building b : buildings.values()) if (b.type == t) out.add(b);
        return out;
    }

    public void addStability(double d) {
        stability = Math.max(0, Math.min(100, stability + d));
    }

    // ───────────── persistence ─────────────

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("founder", founder.toString());
        for (Map.Entry<UUID, Rank> e : members.entrySet()) s.set("members." + e.getKey(), e.getValue().name());
        s.set("world", world);
        s.set("region", region.toString());
        s.set("core", core == null ? null : core.toString());
        s.set("chest", chest == null ? null : chest.toString());
        s.set("created", createdAt);
        s.set("shield-until", shieldUntil);
        s.set("stability", stability);
        s.set("low-days", lowDays);
        s.set("strike", strike);
        s.set("last-ration-day", lastRationDay);
        s.set("last-fed", lastFed);
        s.set("reputation", reputation);
        s.set("default-policy", defaultPolicy.name());
        s.set("last-child-day", lastChildDay);
        s.set("last-traveler-day", lastTravelerDay);
        s.set("quest-stage", questStage);
        s.set("founded", founded);
        List<String> hidden = new ArrayList<>();
        for (UUID u : questHidden) hidden.add(u.toString());
        s.set("quest-hidden", hidden);
        s.set("next-building-id", nextBuildingId);
        for (Building b : buildings.values()) b.save(s.createSection("buildings." + b.id));
        for (BuildJob j : buildQueue) j.save(s.createSection("build-queue." + j.id));
        int bi = 0;
        for (CustomBlueprint cb : blueprints.values()) cb.save(s.createSection("blueprints." + (bi++)));
        List<String> sp = new ArrayList<>();
        for (ItemStack it : spoils) {
            String enc = ItemCodec.encode(it);
            if (enc != null) sp.add(enc);
        }
        s.set("spoils", sp);
        s.set("mobilized-until", mobilizedUntil);
        s.set("mobilize-cooldown", mobilizeCooldownUntil);
        s.set("war-cooldown", warCooldownUntil);
    }

    public static Colony load(String id, ConfigurationSection s) {
        Colony c = new Colony(id);
        c.name = s.getString("name", "Commune");
        try {
            c.founder = UUID.fromString(s.getString("founder", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        ConfigurationSection m = s.getConfigurationSection("members");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                try {
                    c.members.put(UUID.fromString(k), Rank.valueOf(m.getString(k, "MEMBER")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        c.members.put(c.founder, Rank.FOUNDER);
        c.world = s.getString("world");
        c.region = Region.parse(s.getString("region"));
        if (c.world == null || c.region == null) return null;
        c.core = BlockPos.parse(s.getString("core"));
        c.chest = BlockPos.parse(s.getString("chest"));
        c.createdAt = s.getLong("created");
        c.shieldUntil = s.getLong("shield-until");
        c.stability = s.getDouble("stability", 70);
        c.lowDays = s.getInt("low-days");
        c.strike = s.getBoolean("strike");
        c.lastRationDay = s.getLong("last-ration-day", NEVER);
        c.lastFed = s.getDouble("last-fed", 1);
        c.reputation = s.getInt("reputation", 50);
        Policy p = Policy.parse(s.getString("default-policy"));
        c.defaultPolicy = p == null ? Policy.INDOCTRINATE : p;
        c.lastChildDay = s.getLong("last-child-day", -1);
        c.lastTravelerDay = s.getLong("last-traveler-day", -1);
        c.questStage = s.getInt("quest-stage", 1);
        c.founded = s.getBoolean("founded");
        for (String u : s.getStringList("quest-hidden")) {
            try {
                c.questHidden.add(UUID.fromString(u));
            } catch (IllegalArgumentException ignored) {
            }
        }
        c.nextBuildingId = s.getInt("next-building-id", 1);
        ConfigurationSection bs = s.getConfigurationSection("buildings");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                ConfigurationSection b = bs.getConfigurationSection(k);
                Building bl = b == null ? null : Building.load(k, b);
                if (bl != null) c.buildings.put(k, bl);
            }
        }
        ConfigurationSection q = s.getConfigurationSection("build-queue");
        if (q != null) {
            for (String k : q.getKeys(false)) {
                ConfigurationSection b = q.getConfigurationSection(k);
                BuildJob j = b == null ? null : BuildJob.load(k, b);
                if (j != null) c.buildQueue.add(j);
            }
        }
        ConfigurationSection bps = s.getConfigurationSection("blueprints");
        if (bps != null) {
            for (String k : bps.getKeys(false)) {
                ConfigurationSection b = bps.getConfigurationSection(k);
                CustomBlueprint cb = b == null ? null : CustomBlueprint.load(b);
                if (cb != null) c.blueprints.put(CustomBlueprint.key(cb.name), cb);
            }
        }
        for (String enc : s.getStringList("spoils")) {
            ItemStack it = ItemCodec.decode(enc);
            if (it != null) c.spoils.add(it);
        }
        c.mobilizedUntil = s.getLong("mobilized-until");
        c.mobilizeCooldownUntil = s.getLong("mobilize-cooldown");
        c.warCooldownUntil = s.getLong("war-cooldown");
        return c;
    }
}
