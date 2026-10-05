package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.store.Storage;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Herders look after the colony's animals: shear sheep, gather eggs, breed each kind up to the herd size with
 * feed carried from the State Chest, and slaughter the surplus for meat, leather and wool.
 */
public final class Herding {

    private enum Task { SHEAR, EGG, BREED, CULL }

    private final ColonySMP plugin;
    private final NpcManager m;

    Herding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private static boolean herd(Entity e) {
        if (!(e instanceof Cow || e instanceof Sheep || e instanceof Pig || e instanceof Chicken || e instanceof Rabbit)) return false;
        if (e.customName() != null || e instanceof Tameable t && t.isTamed()) return false;
        return !(e instanceof org.bukkit.entity.LivingEntity le && le.isLeashed()) && !NpcManager.isBody(e);
    }

    private static Predicate<ItemStack> feed(EntityType t) {
        return switch (t) {
            case PIG -> it -> (it.getType() == Material.CARROT || it.getType() == Material.POTATO || it.getType() == Material.BEETROOT) && Storage.plain(it);
            case CHICKEN -> it -> (it.getType() == Material.WHEAT_SEEDS || it.getType() == Material.BEETROOT_SEEDS || it.getType() == Material.MELON_SEEDS
                    || it.getType() == Material.PUMPKIN_SEEDS) && Storage.plain(it);
            case RABBIT -> it -> (it.getType() == Material.CARROT || it.getType() == Material.DANDELION) && Storage.plain(it);
            default -> it -> it.getType() == Material.WHEAT && Storage.plain(it);
        };
    }

    void think(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        Location core = col.coreLocation();
        if (core == null) return;
        BoundingBox box = new BoundingBox(col.region.minX(), core.getY() - 24, col.region.minZ(), col.region.maxX() + 1, core.getY() + 24, col.region.maxZ() + 1);
        List<Animals> animals = new ArrayList<>();
        Map<EntityType, List<Animals>> kinds = new EnumMap<>(EntityType.class);
        for (Entity e : w.getNearbyEntities(box, Herding::herd)) {
            Animals a = (Animals) e;
            animals.add(a);
            kinds.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }
        List<Item> eggs = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(box, e -> e instanceof Item it && it.getItemStack().getType() == Material.EGG && it.getTicksLived() > 40)) eggs.add((Item) e);
        if (animals.isEmpty() && eggs.isEmpty()) {
            m.brain().idle(n, col, now, "No animals in the colony (lead some cows, sheep, pigs or chickens in)");
            return;
        }
        // keep going with the current animal or egg if it's still there
        Entity subject = n.subject != null && n.subject.isValid() ? n.subject : null;
        Task task = n.jobKey == null ? null : switch (n.jobKey) {
            case "herd:shear" -> Task.SHEAR;
            case "herd:egg" -> Task.EGG;
            case "herd:breed" -> Task.BREED;
            case "herd:cull" -> Task.CULL;
            default -> null;
        };
        if (subject == null || task == null || !still(task, subject, kinds)) {
            subject = null;
            task = null;
            int size = plugin.settings().herdSize;
            Location here = n.body.getLocation();
            for (Item egg : eggs) {
                subject = egg;
                task = Task.EGG;
                break;
            }
            boolean shears = n.c.tool != null && Tools.kind(n.c.tool) == Tools.Kind.SHEARS || col.storage.has(it -> Tools.kind(it) == Tools.Kind.SHEARS);
            if (subject == null && shears) {
                for (Animals a : animals) {
                    if (a instanceof Sheep s && s.isAdult() && !s.isSheared()) {
                        subject = s;
                        task = Task.SHEAR;
                        break;
                    }
                }
            }
            if (subject == null) {
                for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) {
                    List<Animals> list = e.getValue();
                    if (list.size() > size) {
                        subject = closest(list, here, a -> a.isAdult() && !a.isLoveMode());
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
                    Predicate<ItemStack> f = feed(e.getKey());
                    if (n.c.carried(f) == 0 && !col.storage.has(f)) continue;
                    subject = closest(list, here, a -> a.isAdult() && a.canBreed() && !a.isLoveMode());
                    task = Task.BREED;
                    if (subject != null) break;
                }
            }
            n.subject = subject;
            n.jobKey = task == null ? null : "herd:" + task.name().toLowerCase();
        }
        if (subject == null) {
            Animals a = animals.isEmpty() ? null : animals.get(Math.floorMod(n.c.id.hashCode(), animals.size()));
            m.brain().wander(n, a != null ? a.getLocation() : core, 4, now, "Watching over the herd (" + animals.size() + " animals)");
            return;
        }
        // tools and feed first
        if (task == Task.SHEAR && !m.ensureTool(n, col, Tools.Kind.SHEARS, now)) return;
        if (task == Task.BREED) {
            Predicate<ItemStack> f = feed(subject.getType());
            if (n.c.carried(f) == 0) {
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
            n.activity = switch (task) {
                case SHEAR -> "Going to shear a sheep";
                case EGG -> "Collecting eggs";
                case BREED -> "Bringing feed to the " + Text.nice(subject.getType().name()).toLowerCase() + "s";
                case CULL -> "Picking out a " + Text.nice(subject.getType().name()).toLowerCase() + " for slaughter";
            };
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, at);
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(2.0, m.efficiency(col, n.c, Job.HERDER));
        switch (task) {
            case SHEAR -> shear(n, col, (Sheep) subject);
            case EGG -> {
                Item egg = (Item) subject;
                m.deposit(col, List.of(egg.getItemStack()));
                egg.remove();
                Fx.sound(at, "minecraft:entity.item.pickup", 0.6f, 1f);
                n.activity = "Collected eggs";
            }
            case BREED -> {
                Animals a = (Animals) subject;
                if (n.c.useCarried(feed(a.getType()), 1) == 1) {
                    a.setLoveModeTicks(600);
                    a.setBreedCause(null);
                    at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1, 0), 4, 0.3, 0.3, 0.3, 0);
                    Fx.sound(at, "minecraft:entity.generic.eat", 0.6f, 1f);
                    n.activity = "Feeding the " + Text.nice(a.getType().name()).toLowerCase() + "s";
                }
            }
            case CULL -> cull(n, col, (Animals) subject);
        }
        n.subject = null;
        n.jobKey = null;
    }

    private static boolean still(Task t, Entity e, Map<EntityType, List<Animals>> kinds) {
        return switch (t) {
            case SHEAR -> e instanceof Sheep s && !s.isSheared();
            case EGG -> e instanceof Item;
            case BREED -> e instanceof Animals a && a.canBreed() && !a.isLoveMode();
            case CULL -> e instanceof Animals a && kinds.getOrDefault(a.getType(), List.of()).size() > 0;
        };
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

    private void shear(Npc n, Colony col, Sheep s) {
        if (n.c.tool == null || Tools.kind(n.c.tool) != Tools.Kind.SHEARS) return;
        s.setSheared(true);
        DyeColor c = s.getColor() == null ? DyeColor.WHITE : s.getColor();
        Material wool = Material.matchMaterial(c.name() + "_WOOL");
        m.deposit(col, List.of(new ItemStack(wool == null ? Material.WHITE_WOOL : wool, 1 + ThreadLocalRandom.current().nextInt(3))));
        Fx.sound(s.getLocation(), "minecraft:entity.sheep.shear", 0.8f, 1f);
        m.wearTool(n, col);
        n.activity = "Shearing sheep";
    }

    private void cull(Npc n, Colony col, Animals a) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> drops = new ArrayList<>();
        switch (a.getType()) {
            case COW, MOOSHROOM -> {
                drops.add(new ItemStack(Material.BEEF, 1 + r.nextInt(3)));
                if (r.nextInt(3) > 0) drops.add(new ItemStack(Material.LEATHER, 1 + r.nextInt(2)));
            }
            case PIG -> drops.add(new ItemStack(Material.PORKCHOP, 1 + r.nextInt(3)));
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
        m.deposit(col, drops);
        n.activity = "Slaughtered a " + Text.nice(a.getType().name()).toLowerCase() + " (over the herd size)";
    }
}
