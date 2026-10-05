package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Status;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Lightable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Cooks and Smiths work at stations: they carry the inputs from the State Chest to a furnace, smoker,
 * campfire or anvil, work them there, and carry what they made back to the chest.
 */
final class Workshop {

    /** Something to carry from the chest for an order. */
    record In(Predicate<ItemStack> what, int amount) {}

    /** One action at the station; false when the order is finished. */
    interface Act {
        boolean step(Npc n, Colony col, Block station);
    }

    /** A piece of work at a station. */
    static final class Order {
        final Job job;
        final String label;
        final List<In> inputs;
        final BlockPos station;
        final double seconds;
        final Act act;
        boolean fetched;

        Order(Job job, String label, List<In> inputs, BlockPos station, double seconds, Act act) {
            this.job = job;
            this.label = label;
            this.inputs = inputs;
            this.station = station;
            this.seconds = seconds;
            this.act = act;
        }
    }

    private static final Map<Material, Material> COOK = new EnumMap<>(Material.class);
    private static final Map<Material, Material> SMELT = new EnumMap<>(Material.class);
    private static final Map<Material, Double> FUEL = new EnumMap<>(Material.class);
    private static final Set<Material> COOKERS = Set.of(Material.SMOKER, Material.FURNACE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);
    private static final Set<Material> SMELTERS = Set.of(Material.BLAST_FURNACE, Material.FURNACE);
    private static final Set<Material> BENCHES = Set.of(Material.ANVIL, Material.SMITHING_TABLE, Material.CRAFTING_TABLE);

    static {
        COOK.put(Material.BEEF, Material.COOKED_BEEF);
        COOK.put(Material.PORKCHOP, Material.COOKED_PORKCHOP);
        COOK.put(Material.CHICKEN, Material.COOKED_CHICKEN);
        COOK.put(Material.MUTTON, Material.COOKED_MUTTON);
        COOK.put(Material.RABBIT, Material.COOKED_RABBIT);
        COOK.put(Material.COD, Material.COOKED_COD);
        COOK.put(Material.SALMON, Material.COOKED_SALMON);
        COOK.put(Material.POTATO, Material.BAKED_POTATO);
        SMELT.put(Material.RAW_IRON, Material.IRON_INGOT);
        SMELT.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        SMELT.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        SMELT.put(Material.IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT);
        SMELT.put(Material.GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT);
        SMELT.put(Material.COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.DEEPSLATE_COPPER_ORE, Material.COPPER_INGOT);
        SMELT.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
        SMELT.put(Material.SAND, Material.GLASS);
        SMELT.put(Material.CLAY_BALL, Material.BRICK);
        FUEL.put(Material.COAL, 8.0);
        FUEL.put(Material.CHARCOAL, 8.0);
        FUEL.put(Material.COAL_BLOCK, 80.0);
        FUEL.put(Material.BLAZE_ROD, 12.0);
        FUEL.put(Material.DRIED_KELP_BLOCK, 20.0);
        FUEL.put(Material.STICK, 0.5);
        for (Material m : Tag.LOGS.getValues()) FUEL.put(m, 1.5);
        for (Material m : Tag.PLANKS.getValues()) FUEL.put(m, 1.5);
    }

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Stations stations = new Stations();

    Workshop(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    static boolean fuel(ItemStack it) {
        return FUEL.containsKey(it.getType()) && Storage.plain(it);
    }

    /** The best fuel in the State Chest (coal before wood: planks and logs are building material). */
    private static In bestFuel(Colony col, int items) {
        Material best = null;
        for (Material m : List.of(Material.COAL_BLOCK, Material.COAL, Material.CHARCOAL, Material.BLAZE_ROD, Material.DRIED_KELP_BLOCK)) {
            if (col.storage.count(m) > 0) {
                best = m;
                break;
            }
        }
        if (best == null) {
            int most = 0;
            for (Map.Entry<Material, Double> e : FUEL.entrySet()) {
                int c = col.storage.count(e.getKey());
                if (c > most) {
                    most = c;
                    best = e.getKey();
                }
            }
        }
        if (best == null) return new In(Workshop::fuel, items);
        final Material pick = best;
        int amount = (int) Math.max(1, Math.ceil(items / FUEL.get(pick)));
        return new In(it -> it.getType() == pick && Storage.plain(it), amount);
    }

    Stations stations() {
        return stations;
    }

    // ───────────── the work loop ─────────────

    void think(Npc n, Colony col, long now) {
        Job job = n.c.job;
        Order o = n.order instanceof Order or && or.job == job ? or : null;
        if (o == null) {
            // bring back what was made (and anything left over) before the next order
            if (!n.c.carry.isEmpty()) {
                if (m.returnLeftovers(n, col)) {
                    n.activity = "Bringing " + (job == Job.COOK ? "food" : "goods") + " to the State Chest";
                    return;
                }
            }
            if (now < n.planAt) {
                m.brain().idle(n, col, now, n.activity);
                return;
            }
            String[] why = {null};
            o = job == Job.COOK ? planCook(n, col, now, why) : planSmith(n, col, now, why);
            if (o == null) {
                n.planAt = now + 200;
                m.brain().idle(n, col, now, why[0] == null ? "Nothing to do" : why[0]);
                return;
            }
            n.order = o;
        }
        World w = n.body.getWorld();
        if (!o.fetched) {
            if (!m.atChest(n, col)) {
                n.mover.moveTo(col.chestLocation(), plugin.settings().walkSpeed, 2.2);
                n.activity = "Fetching supplies for " + o.label.toLowerCase();
                return;
            }
            n.mover.stop();
            m.unload(n, col, x -> true);
            for (In in : o.inputs) {
                for (ItemStack it : col.storage.take(in.what, in.amount)) {
                    ItemStack left = n.c.stow(it);
                    if (left != null) col.storage.add(left);
                }
            }
            if (!o.inputs.isEmpty() && n.c.carried(o.inputs.get(0).what) == 0) {
                n.order = null;
                return;
            }
            Fx.sound(col.chestLocation(), "minecraft:block.chest.open", 0.5f, 1.1f);
            o.fetched = true;
            return;
        }
        Block st = o.station.loaded(w) ? o.station.block(w) : null;
        if (st == null || !(COOKERS.contains(st.getType()) || SMELTERS.contains(st.getType()) || BENCHES.contains(st.getType()) || Tag.ANVIL.isTagged(st.getType()))) {
            stations.forget(col);
            n.order = null;
            return;
        }
        Location spot = Stations.standBy(w, o.station);
        if (spot == null) {
            n.order = null;
            n.planAt = now + 200;
            n.activity = "Can't reach the " + Text.nice(st.getType().name());
            return;
        }
        if (!n.mover.near(spot, 1.3)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.0);
            n.activity = "Walking to the " + Text.nice(st.getType().name());
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, o.station.center(w));
        n.activity = o.label;
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c, job);
        n.actionAt = now + m.interval(o.seconds, eff);
        boolean more = o.act.step(n, col, st);
        if (!more) n.order = null;
    }

    // ───────────── cooks ─────────────

    private Order planCook(Npc n, Colony col, long now, String[] why) {
        World w = col.world();
        Stations.Found f = stations.get(col, now);
        List<BlockPos> all = new ArrayList<>(f.smokers());
        all.addAll(f.furnaces());
        all.addAll(f.campfires());
        Location here = n.body.getLocation();
        BlockPos st = Stations.nearest(w, all, here, COOKERS);
        if (st == null) {
            why[0] = "No furnace, smoker or campfire in the colony";
            return null;
        }
        // the raw food there's most of (keeping some potatoes back for planting)
        Material raw = null;
        int most = 0;
        for (Material r : COOK.keySet()) {
            int c = col.storage.count(r) - (r == Material.POTATO ? 16 : 0);
            if (c > most) {
                most = c;
                raw = r;
            }
        }
        boolean bake = raw == null && col.storage.count(Material.WHEAT) >= 6;
        if (raw == null && !bake) {
            why[0] = "Nothing to cook (no raw meat, fish or potatoes in the State Chest)";
            return null;
        }
        Material stType = st.block(w).getType();
        boolean burns = stType == Material.FURNACE || stType == Material.SMOKER;
        if (burns && n.fuel < 1 && !col.storage.has(Workshop::fuel)) {
            BlockPos camp = Stations.nearest(w, f.campfires(), here, COOKERS);
            if (camp == null) {
                why[0] = "No fuel (coal, charcoal or wood) for the " + Text.nice(stType.name());
                return null;
            }
            st = camp;
            stType = st.block(w).getType();
            burns = false;
        }
        double seconds = plugin.settings().cookInterval * (stType == Material.SMOKER ? 0.5 : stType == Material.FURNACE ? 1 : 1.5);
        List<In> in = new ArrayList<>();
        final boolean usesFuel = burns;
        if (bake) {
            int wheat = Math.min(48, col.storage.count(Material.WHEAT) / 3 * 3);
            in.add(new In(it -> it.getType() == Material.WHEAT && Storage.plain(it), wheat));
            return new Order(Job.COOK, "Baking bread", in, st, seconds, (cook, c, block) -> {
                if (cook.c.useCarried(it -> it.getType() == Material.WHEAT && Storage.plain(it), 3) < 3) return false;
                give(cook, c, new ItemStack(Material.BREAD));
                effects(block, "minecraft:block.smoker.smoke");
                return cook.c.carried(it -> it.getType() == Material.WHEAT) >= 3;
            });
        }
        final Material food = raw;
        int amount = Math.min(32, most);
        in.add(new In(it -> it.getType() == food && Storage.plain(it), amount));
        if (usesFuel) in.add(bestFuel(col, amount));
        return new Order(Job.COOK, "Cooking " + Text.nice(food.name()).toLowerCase(), in, st, seconds, (cook, c, block) -> {
            if (usesFuel && !burn(cook)) return false;
            if (cook.c.useCarried(it -> it.getType() == food && Storage.plain(it), 1) < 1) return false;
            give(cook, c, new ItemStack(COOK.get(food)));
            effects(block, block.getType() == Material.SMOKER ? "minecraft:block.smoker.smoke" : "minecraft:block.fire.ambient");
            return cook.c.carried(it -> it.getType() == food) > 0;
        });
    }

    /** Uses up fuel for one item; false when there's none left. */
    private static boolean burn(Npc n) {
        if (n.fuel >= 1) {
            n.fuel -= 1;
            return true;
        }
        for (ItemStack it : new ArrayList<>(n.c.carry)) {
            if (!fuel(it)) continue;
            Material t = it.getType();
            if (n.c.useCarried(x -> x.getType() == t, 1) == 1) {
                n.fuel += FUEL.get(t) - 1;
                if (t == Material.LAVA_BUCKET) n.c.stow(new ItemStack(Material.BUCKET));
                return true;
            }
        }
        return false;
    }

    private void give(Npc n, Colony col, ItemStack out) {
        ItemStack left = n.c.stow(out);
        if (left != null) m.deposit(col, List.of(left));
    }

    private static void effects(Block b, String sound) {
        World w = b.getWorld();
        w.spawnParticle(Particle.SMOKE, b.getLocation().add(0.5, 1.1, 0.5), 4, 0.2, 0.1, 0.2, 0.01);
        Fx.sound(b.getLocation(), sound, 0.6f, 1f);
        if (b.getState() instanceof Furnace f) {
            f.setBurnTime((short) 60);
            f.update(true, false);
        }
        if (b.getBlockData() instanceof Lightable l && !l.isLit() && b.getType() != Material.CAMPFIRE && b.getType() != Material.SOUL_CAMPFIRE) {
            l.setLit(true);
            b.setBlockData(l, false);
        }
    }

    // ───────────── smiths ─────────────

    private Order planSmith(Npc n, Colony col, long now, String[] why) {
        World w = col.world();
        Stations.Found f = stations.get(col, now);
        Location here = n.body.getLocation();
        List<BlockPos> smelters = new ArrayList<>(f.blast());
        smelters.addAll(f.furnaces());
        BlockPos furnace = Stations.nearest(w, smelters, here, SMELTERS);
        List<BlockPos> benches = new ArrayList<>(f.anvils());
        benches.addAll(f.tables());
        BlockPos bench = Stations.nearest(w, benches, here, BENCHES);
        BlockPos anvil = Stations.nearest(w, f.anvils(), here, Set.of(Material.ANVIL));
        if (furnace == null && bench == null) {
            why[0] = "No furnace, blast furnace, anvil or crafting table in the colony";
            return null;
        }
        // 1. smelt ore from the mines
        if (furnace != null && (n.fuel >= 1 || col.storage.has(Workshop::fuel))) {
            for (Map.Entry<Material, Material> e : SMELT.entrySet()) {
                Material ore = e.getKey();
                int have = col.storage.count(ore);
                if (ore == Material.SAND || ore == Material.CLAY_BALL) have -= 32; // keep some for building
                if (have <= 0) continue;
                Material out = e.getValue();
                boolean blast = furnace.block(w).getType() == Material.BLAST_FURNACE;
                List<In> in = new ArrayList<>();
                in.add(new In(it -> it.getType() == ore && Storage.plain(it), Math.min(32, have)));
                in.add(bestFuel(col, Math.min(32, have)));
                return new Order(Job.SMITH, "Smelting " + Text.nice(ore.name()).toLowerCase(), in, furnace,
                        plugin.settings().smithInterval * (blast ? 0.5 : 1), (smith, c, block) -> {
                            if (!burn(smith)) return false;
                            if (smith.c.useCarried(it -> it.getType() == ore && Storage.plain(it), 1) < 1) return false;
                            give(smith, c, new ItemStack(out));
                            effects(block, "minecraft:block.blastfurnace.fire_crackle");
                            return smith.c.carried(it -> it.getType() == ore) > 0;
                        });
            }
        }
        // 2. mend worn tools, weapons and armour
        if (anvil != null) {
            for (Storage.Entry e : col.storage.entries()) {
                ItemStack proto = e.item();
                double worn = Tools.wornFraction(proto);
                Material mat = repairMaterial(proto.getType());
                if (worn < 0.3 || mat == null || col.storage.count(mat) <= 0) continue;
                int need = Math.min(4, (int) Math.ceil(worn / 0.25));
                List<In> in = List.of(new In(it -> it.isSimilar(proto), 1), new In(it -> it.getType() == mat && Storage.plain(it), need));
                return new Order(Job.SMITH, "Repairing a " + Text.nice(proto.getType().name()).toLowerCase(), in, anvil, plugin.settings().smithInterval * 1.5, (smith, c, block) -> {
                    ItemStack tool = null;
                    for (ItemStack it : smith.c.carry) if (it.getType() == proto.getType() && Tools.wornFraction(it) > 0) tool = it;
                    if (tool == null) return false;
                    if (smith.c.useCarried(it -> it.getType() == mat && Storage.plain(it), 1) < 1) return false;
                    mend(tool, 0.25);
                    Fx.sound(block.getLocation(), "minecraft:block.anvil.use", 0.6f, 1.1f);
                    block.getWorld().spawnParticle(Particle.CRIT, block.getLocation().add(0.5, 1.1, 0.5), 6, 0.2, 0.1, 0.2, 0.1);
                    return Tools.wornFraction(tool) > 0 && smith.c.carried(it -> it.getType() == mat) > 0;
                });
            }
        }
        // 3. spare tools for the workers, armour for the guards
        if (bench != null) {
            Order o = makeTool(col, bench);
            if (o != null) return o;
            o = makeArmour(col, bench);
            if (o != null) return o;
        }
        why[0] = "Nothing to smith (no ore to smelt, nothing worn, tools in stock)";
        return null;
    }

    private static Material repairMaterial(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE_")) return Material.NETHERITE_INGOT;
        if (n.startsWith("DIAMOND_")) return Material.DIAMOND;
        if (n.startsWith("IRON_") || n.startsWith("CHAINMAIL_") || m == Material.SHEARS) return Material.IRON_INGOT;
        if (n.startsWith("GOLDEN_")) return Material.GOLD_INGOT;
        if (n.startsWith("STONE_")) return Material.COBBLESTONE;
        if (n.startsWith("WOODEN_")) return Material.OAK_PLANKS;
        if (n.startsWith("LEATHER_")) return Material.LEATHER;
        if (m == Material.BOW || m == Material.CROSSBOW || m == Material.FISHING_ROD) return Material.STRING;
        if (m == Material.SHIELD) return Material.OAK_PLANKS;
        return null;
    }

    private static void mend(ItemStack it, double fraction) {
        ItemMeta meta = it.getItemMeta();
        if (!(meta instanceof Damageable d)) return;
        int max = it.getType().getMaxDurability();
        d.setDamage(Math.max(0, d.getDamage() - (int) Math.ceil(max * fraction)));
        it.setItemMeta(meta);
    }

    /** The tool kinds the colony's workers use. */
    private static List<Tools.Kind> wanted(Colony col) {
        List<Tools.Kind> out = new ArrayList<>();
        for (Citizen c : col.citizens.values()) {
            Job j = c.status == Status.SLAVE ? c.labour : c.status == Status.CITIZEN ? c.job : null;
            if (j == null) continue;
            Tools.Kind k = switch (j) {
                case FARMER -> Tools.Kind.HOE;
                case LUMBERJACK, BUILDER -> Tools.Kind.AXE;
                case MINER -> Tools.Kind.PICKAXE;
                case HERDER -> Tools.Kind.SHEARS;
                case FISHER -> Tools.Kind.FISHING_ROD;
                case GUARD -> Tools.Kind.SWORD;
                default -> null;
            };
            if (k != null && !out.contains(k)) out.add(k);
        }
        return out;
    }

    private Order makeTool(Colony col, BlockPos bench) {
        for (Tools.Kind k : wanted(col)) {
            if (k == Tools.Kind.SWORD ? col.storage.has(it -> Tools.isMelee(Tools.kind(it))) : col.storage.has(it -> Tools.kind(it) == k)) continue;
            int head = switch (k) {
                case PICKAXE, AXE -> 3;
                case HOE, SWORD, SHEARS -> 2;
                default -> 0;
            };
            int sticks = switch (k) {
                case SWORD -> 1;
                case SHEARS -> 0;
                case FISHING_ROD -> 3;
                default -> 2;
            };
            List<In> in = new ArrayList<>();
            Material out;
            if (k == Tools.Kind.FISHING_ROD) {
                if (col.storage.count(Material.STRING) < 2) continue;
                in.add(new In(it -> it.getType() == Material.STRING && Storage.plain(it), 2));
                out = Material.FISHING_ROD;
            } else if (k == Tools.Kind.SHEARS) {
                if (col.storage.count(Material.IRON_INGOT) < 2) continue;
                in.add(new In(it -> it.getType() == Material.IRON_INGOT && Storage.plain(it), 2));
                out = Material.SHEARS;
            } else {
                String tier;
                Predicate<ItemStack> mat;
                if (col.storage.count(Material.IRON_INGOT) >= head + 6) {
                    tier = "IRON_";
                    mat = it -> it.getType() == Material.IRON_INGOT && Storage.plain(it);
                } else if (col.storage.count(Material.COBBLESTONE) >= head) {
                    tier = "STONE_";
                    mat = it -> it.getType() == Material.COBBLESTONE && Storage.plain(it);
                } else if (col.storage.count(it -> Tag.PLANKS.isTagged(it.getType()) && Storage.plain(it)) >= head + 2) {
                    tier = "WOODEN_";
                    mat = it -> Tag.PLANKS.isTagged(it.getType()) && Storage.plain(it);
                } else continue;
                in.add(new In(mat, head));
                out = Material.matchMaterial(tier + k.name());
                if (out == null) continue;
            }
            if (sticks > 0) {
                if (col.storage.count(Material.STICK) >= sticks) in.add(new In(it -> it.getType() == Material.STICK && Storage.plain(it), sticks));
                else if (col.storage.count(it -> Tag.PLANKS.isTagged(it.getType()) && Storage.plain(it)) >= 2 + head) in.add(new In(it -> Tag.PLANKS.isTagged(it.getType()) && Storage.plain(it), 2));
                else continue;
            }
            return craft(Text.nice(out.name()), in, bench, out, 3);
        }
        return null;
    }

    private Order makeArmour(Colony col, BlockPos bench) {
        Material[] pieces = {Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.IRON_CHESTPLATE, Material.IRON_HELMET};
        int[] cost = {4, 7, 8, 5};
        Tools.Kind[] kinds = {Tools.Kind.BOOTS, Tools.Kind.LEGGINGS, Tools.Kind.CHESTPLATE, Tools.Kind.HELMET};
        for (int i = 0; i < 4; i++) {
            final int slot = i;
            boolean lacking = col.citizens.values().stream().anyMatch(c -> c.status == Status.CITIZEN && c.job == Job.GUARD && c.armor[slot] == null);
            if (!lacking || col.storage.has(it -> Tools.kind(it) == kinds[slot])) continue;
            if (col.storage.count(Material.IRON_INGOT) < cost[i] + 8) continue;
            return craft(Text.nice(pieces[i].name()), List.of(new In(it -> it.getType() == Material.IRON_INGOT && Storage.plain(it), cost[i])), bench, pieces[i], 5);
        }
        return null;
    }

    /** Hammering out an item from the carried materials over a few actions. */
    private Order craft(String what, List<In> in, BlockPos bench, Material out, int actions) {
        int[] done = {0};
        return new Order(Job.SMITH, "Making a " + what.toLowerCase(), in, bench, plugin.settings().smithInterval, (smith, c, block) -> {
            Fx.sound(block.getLocation(), Tag.ANVIL.isTagged(block.getType()) ? "minecraft:block.anvil.use" : "minecraft:block.smithing_table.use", 0.6f, 1f);
            block.getWorld().spawnParticle(Particle.CRIT, block.getLocation().add(0.5, 1.1, 0.5), 6, 0.2, 0.1, 0.2, 0.1);
            if (++done[0] < actions) return true;
            for (In i : in) smith.c.useCarried(i.what, i.amount);
            give(smith, c, new ItemStack(out));
            return false;
        });
    }
}
