package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Animal keepers look after the animals inside their pen: gather eggs, shear sheep, milk cows into buckets,
 * breed each kind up to the pen's herd size with feed from the pen's stock or storage, and send the surplus to
 * slaughter for meat, leather and wool. At the stables they breed horses and break them in as war horses for the
 * cavalry (each takes a saddle). What they gather goes into the pen's stock for the haulers.
 */
public final class Herding {

    private enum Task { EGG, SHEAR, MILK, BREED, CULL, BREAK_IN }

    private final ColonySMP plugin;
    private final NpcManager m;

    Herding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private static boolean herd(Entity e, boolean stables) {
        if (stables) {
            return e instanceof AbstractHorse h && e.getType() == EntityType.HORSE && !h.isTamed() && e.customName() == null && !NpcManager.isBody(e);
        }
        if (!(e instanceof Cow || e instanceof Sheep || e instanceof Pig || e instanceof Chicken || e instanceof Rabbit || e instanceof Goat)) return false;
        if (e.customName() != null || e instanceof Tameable t && t.isTamed()) return false;
        return !(e instanceof org.bukkit.entity.LivingEntity le && le.isLeashed());
    }

    private static List<String> feed(EntityType t) {
        return switch (t) {
            case PIG -> List.of("carrot", "potato", "beetroot");
            case CHICKEN -> List.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds");
            case RABBIT -> List.of("carrot", "dandelion");
            case HORSE -> List.of("golden_carrot", "golden_apple", "hay_block", "wheat");
            default -> List.of("wheat");
        };
    }

    private static String kind(EntityType t) {
        return t.name().toLowerCase(Locale.ROOT);
    }

    /** The animals in a pen, by kind (also recorded on the building for the background model). */
    Map<EntityType, List<Animals>> census(World w, Building pen) {
        Map<EntityType, List<Animals>> kinds = new EnumMap<>(EntityType.class);
        if (pen.min == null || pen.max == null) return kinds;
        boolean stables = pen.type == BuildingType.STABLES;
        BoundingBox box = new BoundingBox(pen.min.x(), pen.min.y() - 1, pen.min.z(), pen.max.x() + 1, pen.max.y() + 3, pen.max.z() + 1);
        for (Entity e : w.getNearbyEntities(box, e -> herd(e, stables))) {
            kinds.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add((Animals) e);
        }
        pen.counts.clear();
        for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) pen.counts.put(kind(e.getKey()), e.getValue().size());
        return kinds;
    }

    void think(Npc n, Colony col, Building pen, long now) {
        World w = n.body.getWorld();
        boolean stables = pen.type == BuildingType.STABLES;
        Map<EntityType, List<Animals>> kinds = census(w, pen);
        List<Animals> animals = new ArrayList<>();
        for (List<Animals> l : kinds.values()) animals.addAll(l);
        List<Item> eggs = new ArrayList<>();
        if (!stables && pen.min != null) {
            BoundingBox box = new BoundingBox(pen.min.x(), pen.min.y() - 1, pen.min.z(), pen.max.x() + 1, pen.max.y() + 3, pen.max.z() + 1);
            for (Entity e : w.getNearbyEntities(box, e -> e instanceof Item it && it.getItemStack().getType() == Material.EGG && it.getTicksLived() > 40)) eggs.add((Item) e);
        }
        if (animals.isEmpty() && eggs.isEmpty()) {
            m.brain().wanderAt(n, col, pen, now, stables ? "No horses in " + pen.label() + " (lead some in)" : "No animals in " + pen.label() + " (lead some in)");
            return;
        }
        Entity subject = n.subject != null && n.subject.isValid() ? n.subject : null;
        Task task = n.jobKey == null || !n.jobKey.startsWith("herd:") ? null : Task.valueOf(n.jobKey.substring(5));
        if (subject == null || task == null || !still(task, subject)) {
            subject = null;
            task = null;
            int size = Math.max(2, pen.herdTarget);
            Location here = n.loc();
            if (!eggs.isEmpty()) {
                subject = eggs.get(0);
                task = Task.EGG;
            }
            boolean shears = n.c.tool != null && Tools.kind(n.c.tool) == Tools.Kind.SHEARS || col.equipment.has(it -> Tools.kind(it) == Tools.Kind.SHEARS);
            if (subject == null && shears) {
                for (Animals a : animals) {
                    if (a instanceof Sheep s && s.isAdult() && !s.isSheared()) {
                        subject = s;
                        task = Task.SHEAR;
                        break;
                    }
                }
            }
            if (subject == null && pen.stock.available("bucket") > 0 && ThreadLocalRandom.current().nextInt(4) == 0) {
                subject = closest(kinds.getOrDefault(EntityType.COW, List.of()), here, Animals::isAdult);
                if (subject != null) task = Task.MILK;
            }
            if (subject == null && stables && pen.stock.available("saddle") + col.storage.available("saddle") > 0) {
                List<Animals> horses = kinds.getOrDefault(EntityType.HORSE, List.of());
                if (horses.size() > 2) {
                    subject = closest(horses, here, Animals::isAdult);
                    if (subject != null) task = Task.BREAK_IN;
                }
            }
            if (subject == null && !stables) {
                for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) {
                    if (e.getValue().size() > size) {
                        subject = closest(e.getValue(), here, a -> a.isAdult() && !a.isLoveMode());
                        task = Task.CULL;
                        if (subject != null) break;
                    }
                }
            }
            if (subject == null) {
                for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) {
                    List<Animals> list = e.getValue();
                    if (list.size() >= size || list.size() < 2) continue;
                    long ready = list.stream().filter(a -> a.isAdult() && a.canBreed() && !a.isLoveMode()).count();
                    long inLove = list.stream().filter(Animals::isLoveMode).count();
                    if (ready + inLove < 2) continue;
                    if (!hasFeed(n, col, pen, feed(e.getKey()))) continue;
                    subject = closest(list, here, a -> a.isAdult() && a.canBreed() && !a.isLoveMode());
                    task = Task.BREED;
                    if (subject != null) break;
                }
            }
            n.subject = subject;
            n.jobKey = task == null ? null : "herd:" + task.name();
        }
        if (subject == null) {
            Animals a = animals.isEmpty() ? null : animals.get(Math.floorMod(n.c.id.hashCode(), animals.size()));
            Location at = a != null ? a.getLocation() : m.place(w, pen);
            if (at != null) m.brain().wander(n, at, 4, now, "Watching over the " + (stables ? "horses" : "herd") + " (" + animals.size() + " animals)");
            return;
        }
        if (task == Task.SHEAR && !m.ensureTool(n, col, Tools.Kind.SHEARS, now)) return;
        if (task == Task.BREED) {
            List<String> f = feed(subject.getType());
            if (!carriesAny(n, pen, f)) {
                NpcManager.Fetch got = m.fetch(n, col, f, 8, "animal feed");
                if (got == NpcManager.Fetch.FETCHING) return;
                if (got == NpcManager.Fetch.NONE) {
                    n.subject = null;
                    return;
                }
            }
        }
        Location at = subject.getLocation();
        if (!n.mover.near(at, 1.8)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 1.4);
            String what = Text.nice(subject.getType().name()).toLowerCase();
            n.doing(switch (task) {
                case SHEAR -> "Going to shear a sheep";
                case EGG -> "Collecting eggs";
                case MILK -> "Going to milk a cow";
                case BREED -> "Bringing feed to the " + what + "s";
                case CULL -> "Picking out a " + what + " for slaughter";
                case BREAK_IN -> "Saddling a horse";
            });
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, at);
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(2.0, m.efficiency(col, n.c));
        switch (task) {
            case SHEAR -> shear(n, col, pen, (Sheep) subject);
            case EGG -> {
                Item egg = (Item) subject;
                m.deposit(col, pen, List.of(egg.getItemStack()), at);
                egg.remove();
                Fx.sound(at, "minecraft:entity.item.pickup", 0.6f, 1f);
                n.doing("Collected eggs");
            }
            case MILK -> {
                if (pen.stock.take("bucket", 1) == 1) {
                    if (pen.stock.add("milk_bucket", 1) < 1) col.storage.force("milk_bucket", 1);
                    Fx.sound(at, "minecraft:entity.cow.milk", 0.7f, 1f);
                    n.doing("Milked a cow");
                }
            }
            case BREED -> {
                Animals a = (Animals) subject;
                if (useFeed(n, pen, feed(a.getType()))) {
                    a.setLoveModeTicks(600);
                    a.setBreedCause(null);
                    at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1, 0), 4, 0.3, 0.3, 0.3, 0);
                    Fx.sound(at, "minecraft:entity.generic.eat", 0.6f, 1f);
                    n.doing("Feeding the " + Text.nice(a.getType().name()).toLowerCase() + "s");
                }
            }
            case CULL -> cull(n, col, pen, (Animals) subject);
            case BREAK_IN -> breakIn(n, col, pen, (Animals) subject);
        }
        n.c.skills.gain(Skill.HUSBANDRY, 1.5 * n.c.trait.learning());
        n.subject = null;
        n.jobKey = null;
    }

    private static boolean still(Task t, Entity e) {
        return switch (t) {
            case SHEAR -> e instanceof Sheep s && !s.isSheared();
            case EGG -> e instanceof Item;
            case MILK, CULL, BREAK_IN -> e instanceof Animals a && a.isAdult();
            case BREED -> e instanceof Animals a && a.canBreed() && !a.isLoveMode();
        };
    }

    private boolean hasFeed(Npc n, Colony col, Building pen, List<String> keys) {
        for (String k : keys) if (n.c.carried.get(k) > 0 || pen.stock.available(k) > 0 || col.storage.available(k) > 0) return true;
        return false;
    }

    private static boolean carriesAny(Npc n, Building pen, List<String> keys) {
        for (String k : keys) if (n.c.carried.get(k) > 0 || pen.stock.available(k) > 0) return true;
        return false;
    }

    private static boolean useFeed(Npc n, Building pen, List<String> keys) {
        for (String k : keys) if (pen.stock.take(k, 1) == 1 || n.c.carried.take(k, 1) == 1) return true;
        return false;
    }

    private static Animals closest(List<Animals> list, Location here, Predicate<Animals> ok) {
        Animals best = null;
        double bd = Double.MAX_VALUE;
        for (Animals a : list) {
            if (!ok.test(a) || a.getWorld() != here.getWorld()) continue;
            double d = a.getLocation().distanceSquared(here);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    private void shear(Npc n, Colony col, Building pen, Sheep s) {
        if (n.c.tool == null || Tools.kind(n.c.tool) != Tools.Kind.SHEARS) return;
        s.setSheared(true);
        DyeColor c = s.getColor() == null ? DyeColor.WHITE : s.getColor();
        Material wool = Material.matchMaterial(c.name() + "_WOOL");
        m.deposit(col, pen, List.of(new ItemStack(wool == null ? Material.WHITE_WOOL : wool, 1 + ThreadLocalRandom.current().nextInt(3))), s.getLocation());
        Fx.sound(s.getLocation(), "minecraft:entity.sheep.shear", 0.8f, 1f);
        m.wearTool(n, col);
        n.doing("Shearing sheep");
    }

    private void cull(Npc n, Colony col, Building pen, Animals a) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> drops = new ArrayList<>();
        switch (a.getType()) {
            case COW, MOOSHROOM -> {
                drops.add(new ItemStack(Material.BEEF, 1 + r.nextInt(3)));
                if (r.nextInt(3) > 0) drops.add(new ItemStack(Material.LEATHER, 1 + r.nextInt(2)));
            }
            case PIG -> drops.add(new ItemStack(Material.PORKCHOP, 1 + r.nextInt(3)));
            case GOAT -> drops.add(new ItemStack(Material.MUTTON, 1 + r.nextInt(2)));
            case SHEEP -> {
                drops.add(new ItemStack(Material.MUTTON, 1 + r.nextInt(2)));
                Sheep s = (Sheep) a;
                if (!s.isSheared()) {
                    Material wool = Material.matchMaterial((s.getColor() == null ? DyeColor.WHITE : s.getColor()).name() + "_WOOL");
                    drops.add(new ItemStack(wool == null ? Material.WHITE_WOOL : wool));
                }
            }
            case CHICKEN -> {
                drops.add(new ItemStack(Material.CHICKEN));
                if (r.nextBoolean()) drops.add(new ItemStack(Material.FEATHER, 1 + r.nextInt(2)));
            }
            case RABBIT -> {
                drops.add(new ItemStack(Material.RABBIT));
                if (r.nextBoolean()) drops.add(new ItemStack(Material.RABBIT_HIDE));
            }
            default -> {
            }
        }
        Location at = a.getLocation();
        at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 0.8, 0), 1);
        Fx.sound(at, "minecraft:entity.player.attack.sweep", 0.7f, 1f);
        a.remove();
        m.deposit(col, pen, drops, at);
        n.doing("Slaughtered a " + Text.nice(a.getType().name()).toLowerCase() + " (over the herd size)");
    }

    /** A horse is saddled and trained into a war horse for the cavalry (it leaves the paddock for the stalls). */
    private void breakIn(Npc n, Colony col, Building pen, Animals horse) {
        if (pen.stock.take("saddle", 1) != 1 && col.storage.take("saddle", 1) != 1) return;
        Location at = horse.getLocation();
        horse.remove();
        if (pen.stock.add(Goods.WAR_HORSE, 1) < 1) col.storage.force(Goods.WAR_HORSE, 1);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0);
        Fx.sound(at, "minecraft:entity.horse.saddle", 0.8f, 1f);
        n.doing("Broke in a war horse");
    }
}
