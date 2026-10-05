package com.colonysmp.data;

import com.colonysmp.util.BlockPos;
import com.colonysmp.util.ItemCodec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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

    // needs (0-100)
    /** Sleep: drops while awake, refilled in bed. */
    public double rest = 100;
    /** Overall contentment, from food, rest, home, health, education and the colony's mood. */
    public double happiness = 60;
    /** Schooling: raises work speed and opens skilled jobs. */
    public double education;
    /** Nights in a row spent miserable (very unhappy citizens may emigrate). */
    public int miserableNights;

    /** Supplies the citizen carries from the State Chest to where they're used (a small satchel). */
    public final List<ItemStack> carry = new ArrayList<>();
    public static final int CARRY_SLOTS = 4;

    // runtime only
    /** Day the citizen last ate supper at the State Chest, and the hunger points they got. */
    public transient long supperDay = Long.MIN_VALUE;
    public transient double supperEaten;
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

    // ───────────── satchel ─────────────

    public int carried(Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack it : carry) if (what.test(it)) n += it.getAmount();
        return n;
    }

    /** Takes up to n matching items out of the satchel; returns how many were taken. */
    public int useCarried(Predicate<ItemStack> what, int n) {
        int left = n;
        for (int i = carry.size() - 1; i >= 0 && left > 0; i--) {
            ItemStack it = carry.get(i);
            if (!what.test(it)) continue;
            int take = Math.min(left, it.getAmount());
            left -= take;
            if (take >= it.getAmount()) carry.remove(i);
            else it.setAmount(it.getAmount() - take);
        }
        return n - left;
    }

    /** Puts an item in the satchel; returns what didn't fit (or null). */
    public ItemStack stow(ItemStack in) {
        ItemStack item = in.clone();
        for (ItemStack it : carry) {
            if (item.getAmount() <= 0) break;
            if (!it.isSimilar(item) || it.getAmount() >= it.getMaxStackSize()) continue;
            int move = Math.min(item.getAmount(), it.getMaxStackSize() - it.getAmount());
            it.setAmount(it.getAmount() + move);
            item.setAmount(item.getAmount() - move);
        }
        while (item.getAmount() > 0 && carry.size() < CARRY_SLOTS) {
            ItemStack put = item.clone();
            put.setAmount(Math.min(item.getMaxStackSize(), item.getAmount()));
            carry.add(put);
            item.setAmount(item.getAmount() - put.getAmount());
        }
        return item.getAmount() > 0 ? item : null;
    }

    public int freeCarrySlots() {
        return CARRY_SLOTS - carry.size();
    }

    public String educationTier() {
        if (education >= 75) return "Scholar";
        if (education >= 50) return "Educated";
        if (education >= 25) return "Literate";
        return "Unschooled";
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
        s.set("rest", rest);
        s.set("happiness", happiness);
        s.set("education", education);
        s.set("miserable-nights", miserableNights);
        List<String> bag = new ArrayList<>();
        for (ItemStack it : carry) {
            String enc = ItemCodec.encode(it);
            if (enc != null) bag.add(enc);
        }
        s.set("carry", bag);
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
        c.rest = s.getDouble("rest", 100);
        c.happiness = s.getDouble("happiness", 60);
        c.education = s.getDouble("education", 20);
        c.miserableNights = s.getInt("miserable-nights");
        for (String enc : s.getStringList("carry")) {
            ItemStack it = ItemCodec.decode(enc);
            if (it != null && c.carry.size() < CARRY_SLOTS) c.carry.add(it);
        }
        // a rebel or captive when the server stopped: the uprising / escort is over, back into custody
        if (c.status == Status.REBEL) c.status = Status.CAPTIVE;
        return c;
    }
}
