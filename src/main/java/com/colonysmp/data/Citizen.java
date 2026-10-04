package com.colonysmp.data;

import com.colonysmp.util.BlockPos;
import com.colonysmp.util.ItemCodec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Everything the State knows about one person. The body (a villager) is spawned and despawned with the
 * colony; this record is what is saved.
 */
public final class Citizen {

    public final UUID id;
    public String colonyId;
    public String name;
    public Job job = Job.NONE;
    public Status status = Status.CITIZEN;
    public Trait trait = Trait.ORDINARY;
    /** Villager biome skin (plains, desert, taiga...). */
    public String skin = "plains";
    public double health = 20;
    /** Day the citizen was born (children) or -1. */
    public long bornDay = -1;
    /** Share of a full meal received at the last rationing (0..1). */
    public double fed = 1;
    /** Assigned bed (head block) and the building it is in. */
    public BlockPos bed;
    public String bedBuilding;
    /** Last known position (to respawn the body where it was). */
    public double lastX, lastY, lastZ;
    public boolean hasLast;

    // equipment issued by the State (no personal inventory)
    public ItemStack tool, weapon, bow;
    public int arrows;
    /** boots, leggings, chestplate, helmet */
    public final ItemStack[] armor = new ItemStack[4];

    // prisoners
    public double resistance;
    public Policy policy = Policy.INDOCTRINATE;
    /** Cell building id. */
    public String cell;
    public long lastVisitDay = -1, lastFedDay = -1;
    public int unfedDays;
    /** Forced labour job: MINER or LUMBERJACK. */
    public Job labour = Job.MINER;
    /** Where they came from: "Traveler", "Pillager", another colony's name... */
    public String origin = "";

    // mobilization
    public boolean militia;
    /** Weapon issued for mobilization, returned to the State after. */
    public boolean militiaIssued;

    // rebels
    public long rebelSince;

    // runtime only
    public transient long downedUntil;
    public transient UUID escortPlayer;
    public transient UUID escortGuard;
    public transient boolean awaitingEscort;
    public transient int unmonitoredSeconds;

    public Citizen(UUID id) {
        this.id = id;
    }

    public boolean downed() {
        return downedUntil > 0;
    }

    public boolean adult() {
        return status != Status.CHILD;
    }

    public String title() {
        if (status == Status.CITIZEN) return job.display;
        if (status == Status.SLAVE) return "Forced " + (labour == Job.LUMBERJACK ? "Logger" : "Miner");
        return status.display;
    }

    public void save(ConfigurationSection s) {
        s.set("colony", colonyId);
        s.set("name", name);
        s.set("job", job.name());
        s.set("status", status.name());
        s.set("trait", trait.name());
        s.set("skin", skin);
        s.set("health", health);
        s.set("born", bornDay);
        s.set("fed", fed);
        s.set("bed", bed == null ? null : bed.toString());
        s.set("bed-building", bedBuilding);
        if (hasLast) {
            s.set("last", lastX + "," + lastY + "," + lastZ);
        }
        s.set("tool", ItemCodec.encode(tool));
        s.set("weapon", ItemCodec.encode(weapon));
        s.set("bow", ItemCodec.encode(bow));
        s.set("arrows", arrows);
        for (int i = 0; i < 4; i++) s.set("armor." + i, ItemCodec.encode(armor[i]));
        s.set("resistance", resistance);
        s.set("policy", policy.name());
        s.set("cell", cell);
        s.set("visit-day", lastVisitDay);
        s.set("fed-day", lastFedDay);
        s.set("unfed-days", unfedDays);
        s.set("labour", labour.name());
        s.set("origin", origin);
        s.set("militia", militia);
        s.set("militia-issued", militiaIssued);
        s.set("rebel-since", rebelSince);
    }

    public static Citizen load(UUID id, ConfigurationSection s) {
        Citizen c = new Citizen(id);
        c.colonyId = s.getString("colony");
        c.name = s.getString("name", "Comrade");
        Job j = Job.parse(s.getString("job"));
        c.job = j == null ? Job.NONE : j;
        try {
            c.status = Status.valueOf(s.getString("status", "CITIZEN"));
        } catch (IllegalArgumentException e) {
            c.status = Status.CITIZEN;
        }
        c.trait = Trait.parse(s.getString("trait"));
        c.skin = s.getString("skin", "plains");
        c.health = s.getDouble("health", 20);
        c.bornDay = s.getLong("born", -1);
        c.fed = s.getDouble("fed", 1);
        c.bed = BlockPos.parse(s.getString("bed"));
        c.bedBuilding = s.getString("bed-building");
        String last = s.getString("last");
        if (last != null) {
            String[] p = last.split(",");
            if (p.length == 3) {
                try {
                    c.lastX = Double.parseDouble(p[0]);
                    c.lastY = Double.parseDouble(p[1]);
                    c.lastZ = Double.parseDouble(p[2]);
                    c.hasLast = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        c.tool = ItemCodec.decode(s.getString("tool"));
        c.weapon = ItemCodec.decode(s.getString("weapon"));
        c.bow = ItemCodec.decode(s.getString("bow"));
        c.arrows = s.getInt("arrows");
        for (int i = 0; i < 4; i++) c.armor[i] = ItemCodec.decode(s.getString("armor." + i));
        c.resistance = s.getDouble("resistance");
        Policy p = Policy.parse(s.getString("policy"));
        c.policy = p == null ? Policy.INDOCTRINATE : p;
        c.cell = s.getString("cell");
        c.lastVisitDay = s.getLong("visit-day", -1);
        c.lastFedDay = s.getLong("fed-day", -1);
        c.unfedDays = s.getInt("unfed-days");
        Job l = Job.parse(s.getString("labour"));
        c.labour = l == Job.LUMBERJACK ? Job.LUMBERJACK : Job.MINER;
        c.origin = s.getString("origin", "");
        c.militia = s.getBoolean("militia");
        c.militiaIssued = s.getBoolean("militia-issued");
        c.rebelSince = s.getLong("rebel-since");
        // a rebel or captive when the server stopped: the uprising / escort is over, back into custody
        if (c.status == Status.REBEL) c.status = Status.CAPTIVE;
        return c;
    }
}
