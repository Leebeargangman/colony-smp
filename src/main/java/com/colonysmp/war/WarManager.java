package com.colonysmp.war;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Status;
import com.colonysmp.gui.WarMenu;
import com.colonysmp.npc.Npc;
import com.colonysmp.store.Database;
import com.colonysmp.store.Storage;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Region;
import com.colonysmp.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Colony warfare. A War Banner declares war; after the warm-up the attackers must have raised a War Camp
 * (the banner, placed outside every claim near the enemy). During the siege attackers win by destroying the
 * Town Hall Core, or by cutting the citizens off from their State Chest until stability hits 0%. Defenders
 * win by holding out or tearing down the War Camp. The winner takes half the loser's raw resources.
 */
public final class WarManager implements Listener {

    public enum Phase { WARMUP, SIEGE, ENDED }

    public static final class War {
        public final String id;
        public final String attacker, defender;
        public Phase phase = Phase.WARMUP;
        public long declaredAt, phaseEnds;
        public String campWorld;
        public BlockPos camp;
        public long lastSupply;
        public boolean supplyCut, coreDestroyed;
        public String world;
        /** Original blocks changed during the siege, put back afterwards. */
        public final LinkedHashMap<String, String> restore = new LinkedHashMap<>();
        public long restoreAt;
        transient BossBar atkBar, defBar;
        transient final Set<UUID> atkViewers = new HashSet<>(), defViewers = new HashSet<>();
        transient long warned;

        War(String id, String attacker, String defender) {
            this.id = id;
            this.attacker = attacker;
            this.defender = defender;
        }
    }

    private final ColonySMP plugin;
    private final List<War> wars = new ArrayList<>();
    private int seq;

    public WarManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── queries ─────────────

    public List<War> active() {
        List<War> out = new ArrayList<>();
        for (War w : wars) if (w.phase != Phase.ENDED) out.add(w);
        return out;
    }

    public War warOf(Colony c) {
        for (War w : wars) if (w.phase != Phase.ENDED && (w.attacker.equals(c.id) || w.defender.equals(c.id))) return w;
        return null;
    }

    public boolean atWar(Colony a, Colony b) {
        if (a == null || b == null || a == b) return false;
        for (War w : wars) {
            if (w.phase != Phase.SIEGE) continue;
            if ((w.attacker.equals(a.id) && w.defender.equals(b.id)) || (w.attacker.equals(b.id) && w.defender.equals(a.id))) return true;
        }
        return false;
    }

    /** The colony is being besieged right now. */
    public boolean siegeActive(Colony c) {
        for (War w : wars) if (w.phase == Phase.SIEGE && (w.defender.equals(c.id) || w.attacker.equals(c.id))) return true;
        return false;
    }

    private War defending(Colony c) {
        for (War w : wars) if (w.phase == Phase.SIEGE && w.defender.equals(c.id)) return w;
        return null;
    }

    public boolean warmup(Colony c) {
        for (War w : wars) if (w.phase == Phase.WARMUP && (w.defender.equals(c.id) || w.attacker.equals(c.id))) return true;
        return false;
    }

    /** Is this player an attacker in a siege of this colony? */
    public boolean siegeAttacker(Player p, Colony c) {
        War w = defending(c);
        if (w == null) return false;
        Colony pc = plugin.colonies().of(p);
        return pc != null && pc.id.equals(w.attacker);
    }

    public boolean pvpAllowed(Player a, Player b) {
        Colony ca = plugin.colonies().of(a), cb = plugin.colonies().of(b);
        if (!atWar(ca, cb)) return false;
        for (War w : wars) {
            if (w.phase != Phase.SIEGE) continue;
            if (!(w.attacker.equals(ca.id) || w.defender.equals(ca.id))) continue;
            Colony def = plugin.colonies().get(w.defender);
            if (def == null) continue;
            for (Player p : new Player[]{a, b}) {
                if (!p.getWorld().getName().equals(def.world)) continue;
                if (def.region.distanceTo(p.getX(), p.getZ()) <= 64) return true;
                if (w.camp != null && w.camp.distSq(p.getLocation()) < 64 * 64) return true;
            }
        }
        return false;
    }

    public boolean coreDestroyed(Colony c) {
        for (War w : wars) if (w.defender.equals(c.id) && w.coreDestroyed && (w.phase != Phase.ENDED || !w.restore.isEmpty())) return true;
        return false;
    }

    public boolean campInside(String world, Region r) {
        for (War w : wars) if (w.phase != Phase.ENDED && w.camp != null && world.equals(w.campWorld) && r.grow(4).contains(w.camp)) return true;
        return false;
    }

    // ───────────── recording siege damage ─────────────

    public void record(Colony defender, Block b) {
        War w = defending(defender);
        if (w == null || !plugin.settings().restoreBlocks) return;
        w.restore.putIfAbsent(b.getX() + "," + b.getY() + "," + b.getZ(), b.getBlockData().getAsString());
    }

    public void recordState(Colony defender, BlockState replaced) {
        War w = defending(defender);
        if (w == null || !plugin.settings().restoreBlocks || replaced == null) return;
        w.restore.putIfAbsent(replaced.getX() + "," + replaced.getY() + "," + replaced.getZ(), replaced.getBlockData().getAsString());
    }

    // ───────────── declaring ─────────────

    public String declare(Player p, Colony target) {
        Colony atk = plugin.colonies().of(p);
        long now = System.currentTimeMillis();
        if (atk == null) return "You don't belong to a colony.";
        if (!atk.leads(p.getUniqueId())) return "Only the Chairman or a Commissar can declare war.";
        if (atk.core == null) return "Your colony needs a Town Hall first.";
        if (target == null) return "No such colony.";
        if (target == atk) return "You can't declare war on yourself.";
        if (target.core == null) return Text.esc(target.name) + " has no Town Hall to besiege.";
        if (target.shielded()) return Text.esc(target.name) + " is protected by a Peace Shield for " + Text.duration(target.shieldUntil - now) + ".";
        if (warOf(atk) != null) return "Your colony is already at war.";
        if (warOf(target) != null) return Text.esc(target.name) + " is already at war.";
        if (now < atk.warCooldownUntil) return "Your colony must rest " + Text.duration(atk.warCooldownUntil - now) + " before another war.";
        int online = target.onlineMembers().size();
        if (online < plugin.settings().requireOnlineDefenders) return "At least " + plugin.settings().requireOnlineDefenders + " member(s) of " + Text.esc(target.name) + " must be online.";
        if (!hasBanner(p)) return "You need a War Banner (red banner + iron sword + gold ingot) in your inventory.";
        War w = new War("war" + (++seq) + "_" + Long.toString(now, 36), atk.id, target.id);
        w.declaredAt = now;
        w.phaseEnds = now + Math.max(1, plugin.settings().warmupMinutes) * 60_000L;
        w.world = target.world;
        wars.add(w);
        if (atk.shielded()) atk.shieldUntil = 0;
        String warm = Text.duration(w.phaseEnds - now);
        for (Player m : atk.onlineMembers()) {
            Text.title(m, "<dark_red>⚔ WAR DECLARED", "<red>on " + Text.esc(target.name) + "</red> <gray>- siege in " + warm, 10, 80, 20);
            Fx.sound(m, "minecraft:event.raid.horn", 1f, 0.9f);
            Text.send(m, "<red>War on " + Text.esc(target.name) + "!</red> <gray>Before the siege begins (" + warm + "), place your <white>War Banner</white> outside every claim, "
                    + plugin.settings().campMinDistance + "-" + plugin.settings().campMaxDistance + " blocks from their border, to raise your <red>War Camp</red>.");
        }
        for (Player m : target.onlineMembers()) {
            Text.title(m, "<dark_red>⚠ WAR", "<red>" + Text.esc(atk.name) + "</red> <gray>declared war on you - siege in " + warm, 10, 80, 20);
            Fx.sound(m, "minecraft:event.raid.horn", 1f, 0.7f);
            Text.send(m, "<red>" + Text.esc(atk.name) + " declared war on " + Text.esc(target.name) + "!</red> <gray>Stock the State Chest with arms and armour: your Guards will draw them when the siege begins. "
                    + "Destroy their War Camp to win, protect your Town Hall Core and keep the path to your State Chest open.");
        }
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<red>" + Text.esc(atk.name) + " has declared war on " + Text.esc(target.name) + "!"));
        plugin.requestSave();
        return null;
    }

    private static boolean hasBanner(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (Items.is(it, Items.WAR_BANNER)) return true;
        return false;
    }

    public String surrender(Player p) {
        Colony c = plugin.colonies().of(p);
        if (c == null) return "You don't belong to a colony.";
        if (!c.leads(p.getUniqueId())) return "Only the Chairman or a Commissar can surrender.";
        War w = warOf(c);
        if (w == null) return "Your colony isn't at war.";
        Colony other = plugin.colonies().get(w.attacker.equals(c.id) ? w.defender : w.attacker);
        end(w, other, Text.esc(c.name) + " surrendered", w.phase == Phase.SIEGE);
        return null;
    }

    // ───────────── the War Banner ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseBanner(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.WAR_BANNER)) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR) return;
        e.setCancelled(true);
        new WarMenu(plugin, e.getPlayer()).open();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceBanner(BlockPlaceEvent e) {
        if (!Items.is(e.getItemInHand(), Items.WAR_BANNER)) return;
        Player p = e.getPlayer();
        Colony atk = plugin.colonies().of(p);
        War w = atk == null ? null : warOf(atk);
        if (w == null || !w.attacker.equals(atk.id)) {
            e.setCancelled(true);
            Text.send(p, "<red>Declare war first (right-click the banner in the air, or /colony declare <colony>).");
            return;
        }
        if (w.camp != null) {
            e.setCancelled(true);
            Text.send(p, "<red>Your War Camp already stands.");
            return;
        }
        Colony def = plugin.colonies().get(w.defender);
        Block b = e.getBlockPlaced();
        if (def == null || !b.getWorld().getName().equals(def.world)) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be in the same world as the enemy.");
            return;
        }
        if (plugin.colonies().at(b) != null) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be outside every claim.");
            return;
        }
        double d = def.region.distanceTo(b.getX() + 0.5, b.getZ() + 0.5);
        if (d < plugin.settings().campMinDistance || d > plugin.settings().campMaxDistance) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be " + plugin.settings().campMinDistance + "-" + plugin.settings().campMaxDistance
                    + " blocks from " + Text.esc(def.name) + "'s border (this spot is " + Math.round(d) + ").");
            return;
        }
        w.camp = BlockPos.of(b);
        w.campWorld = b.getWorld().getName();
        for (Player m : atk.onlineMembers()) Text.send(m, "<red>" + Text.esc(p.getName()) + " raised the War Camp</red> <gray>at " + w.camp + ". Defend it: if it falls, the war is lost.");
        for (Player m : def.onlineMembers()) Text.send(m, "<red>" + Text.esc(atk.name) + " has raised a War Camp</red> <gray>near your border (" + w.camp.x() + ", " + w.camp.z() + "). Break the banner during the siege to win!");
        Fx.sound(b.getLocation(), "minecraft:event.raid.horn", 1.5f, 1.2f);
        plugin.requestSave();
    }

    private War campAt(Block b) {
        for (War w : wars) {
            if (w.phase == Phase.ENDED || w.camp == null || !b.getWorld().getName().equals(w.campWorld)) continue;
            if (w.camp.x() == b.getX() && w.camp.y() == b.getY() && w.camp.z() == b.getZ()) return w;
            // breaking the block under a standing banner pops it
            if (w.camp.x() == b.getX() && w.camp.y() == b.getY() + 1 && w.camp.z() == b.getZ()) return w;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreakCamp(BlockBreakEvent e) {
        War w = campAt(e.getBlock());
        if (w == null) return;
        Player p = e.getPlayer();
        Colony pc = plugin.colonies().of(p);
        if (w.phase == Phase.SIEGE && pc != null && pc.id.equals(w.defender)) {
            e.setDropItems(false);
            end(w, pc, Text.esc(p.getName()) + " tore down the War Camp", true);
            return;
        }
        e.setCancelled(true);
        Text.send(p, w.phase == Phase.WARMUP ? "<red>The War Camp can only be destroyed once the siege begins." : "<red>Only the defenders can tear down this War Camp.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> campAt(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> campAt(b) != null);
    }

    /** Breaking the core during a siege is the attackers' victory. */
    public boolean onCoreBreak(Player p, Colony c) {
        War w = defending(c);
        if (w == null || !siegeAttacker(p, c)) return false;
        record(c, c.core.block(c.world()));
        w.coreDestroyed = true;
        Colony atk = plugin.colonies().get(w.attacker);
        end(w, atk, Text.esc(p.getName()) + " destroyed the Town Hall Core of " + Text.esc(c.name), true);
        return true;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.settings().respawnAtCamp) return;
        Colony c = plugin.colonies().of(e.getPlayer());
        if (c == null) return;
        for (War w : wars) {
            if (w.phase != Phase.SIEGE || !w.attacker.equals(c.id) || w.camp == null) continue;
            World world = Bukkit.getWorld(w.campWorld);
            if (world == null) continue;
            Location l = com.colonysmp.npc.Mover.safeSpot(w.camp.center(world).add(1, 0, 0));
            if (l != null) e.setRespawnLocation(l);
            return;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::bars, 20);
    }

    // ───────────── ticking ─────────────

    public void tick() {
        long now = System.currentTimeMillis();
        for (War w : new ArrayList<>(wars)) {
            Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
            if (w.phase == Phase.ENDED) {
                if (now >= w.restoreAt) restoreSome(w, 200);
                if (w.restore.isEmpty()) wars.remove(w);
                continue;
            }
            if (atk == null || def == null) {
                end(w, atk != null ? atk : def, "the other colony was dissolved", false);
                continue;
            }
            if (w.phase == Phase.WARMUP) {
                long left = w.phaseEnds - now;
                for (long mark : new long[]{600_000, 300_000, 60_000}) {
                    if (left <= mark && left > mark - 1000 && w.warned != mark) {
                        w.warned = mark;
                        for (Player p : atk.onlineMembers()) Text.send(p, "<red>The siege of " + Text.esc(def.name) + " begins in " + Text.duration(mark) + "." + (w.camp == null ? " <yellow>Place your War Camp!" : ""));
                        for (Player p : def.onlineMembers()) Text.send(p, "<red>The siege by " + Text.esc(atk.name) + " begins in " + Text.duration(mark) + ".");
                    }
                }
                if (left <= 0) {
                    if (w.camp == null) end(w, def, Text.esc(atk.name) + " never raised a War Camp", false);
                    else startSiege(w, atk, def);
                }
            } else if (w.phase == Phase.SIEGE) {
                if (now >= w.phaseEnds) {
                    end(w, def, Text.esc(def.name) + " held out until the siege collapsed", true);
                    continue;
                }
                World cw = Bukkit.getWorld(w.campWorld);
                if (cw != null && w.camp.loaded(cw) && !Tag.BANNERS.isTagged(w.camp.block(cw).getType())) {
                    end(w, def, "the War Camp fell", true);
                    continue;
                }
                if (now - w.lastSupply >= plugin.settings().supplyCheckSeconds * 1000L) {
                    w.lastSupply = now;
                    supply(w, atk, def);
                    if (def.stability <= 0) {
                        end(w, atk, Text.esc(def.name) + " was starved of supplies until the commune collapsed", true);
                        continue;
                    }
                }
            }
        }
        bars();
    }

    private void startSiege(War w, Colony atk, Colony def) {
        w.phase = Phase.SIEGE;
        w.phaseEnds = System.currentTimeMillis() + plugin.settings().siegeMinutes * 60_000L;
        w.lastSupply = System.currentTimeMillis();
        for (Player p : atk.onlineMembers()) {
            Text.title(p, "<dark_red>⚔ THE SIEGE BEGINS", "<gray>Destroy the <red>Town Hall Core</red> or cut off their State Chest", 10, 80, 20);
            Fx.sound(p, "minecraft:event.raid.horn", 1f, 1f);
        }
        for (Player p : def.onlineMembers()) {
            Text.title(p, "<dark_red>⚠ UNDER SIEGE", "<gray>Defend the Town Hall! Guards are drawing arms", 10, 80, 20);
            Fx.sound(p, "minecraft:event.raid.horn", 1f, 0.8f);
        }
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<red>The siege of " + Text.esc(def.name) + " by " + Text.esc(atk.name) + " has begun!"));
        plugin.requestSave();
    }

    /** Can the defenders' citizens still reach their State Chest? If not, stability drains. */
    private void supply(War w, Colony atk, Colony def) {
        if (!plugin.npcs().active(def)) return;
        boolean ok = chestReachable(def);
        if (ok) {
            if (w.supplyCut) for (Player p : def.onlineMembers()) Text.send(p, "<green>Supply lines restored: your citizens can reach the State Chest again.");
            w.supplyCut = false;
            return;
        }
        w.supplyCut = true;
        def.addStability(-plugin.settings().supplyCutStability);
        for (Player p : def.onlineMembers()) {
            Text.send(p, "<red>⚠ Your citizens can't reach the Central State Chest!</red> <gray>Stability " + Math.round(def.stability) + "% (-"
                    + Math.round(plugin.settings().supplyCutStability) + ")." + (def.stability > 0 ? " Clear the way before it hits 0%." : ""));
        }
        for (Player p : atk.onlineMembers()) Text.bar(p, "<gold>Supply lines cut! " + Text.esc(def.name) + " stability: " + Math.round(def.stability) + "%");
    }

    /** Walks the ground from the chest outwards; true if any defender citizen can get to it. */
    public boolean chestReachable(Colony def) {
        World w = def.world();
        if (w == null || def.chest == null || !def.chest.loaded(w)) return true;
        Block above = def.chest.add(0, 1, 0).block(w);
        if (above.getType().isOccluding()) return false; // the lid can't open
        Set<BlockPos> targets = new HashSet<>();
        for (Npc n : plugin.npcs().of(def)) {
            Citizen c = n.c;
            if ((c.status == Status.CITIZEN || c.status == Status.CHILD) && !c.downed()) targets.add(BlockPos.of(n.body.getLocation()));
        }
        if (targets.isEmpty()) return false;
        Region area = def.region.grow(8);
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos s = def.chest.add(d[0], dy, d[1]);
                if (walkable(w, s) && seen.add(s)) q.add(s);
            }
        }
        while (!q.isEmpty() && seen.size() < 25000) {
            BlockPos p = q.poll();
            if (targets.contains(p)) return true;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos n = p.add(d[0], dy, d[1]);
                    if (!area.contains(n) || seen.contains(n) || !n.loaded(w)) continue;
                    if (dy == 1 && !open(w, p.add(0, 2, 0))) continue;
                    if (dy < 0 && !open(w, p.add(d[0], 0, d[1])) ) continue;
                    if (walkable(w, n)) {
                        seen.add(n);
                        q.add(n);
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static boolean open(World w, BlockPos p) {
        Block b = p.block(w);
        if (b.isPassable()) return true;
        Material m = b.getType();
        return Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.WOODEN_TRAPDOORS.isTagged(m) || (b.getBlockData() instanceof Openable o && o.isOpen());
    }

    private static boolean walkable(World w, BlockPos p) {
        if (!open(w, p) || !open(w, p.add(0, 1, 0))) return false;
        Block below = p.add(0, -1, 0).block(w);
        return below.getType().isSolid() || below.isLiquid() || below.getType() == Material.LADDER;
    }

    // ───────────── ending ─────────────

    public void end(War w, Colony winner, String reason, boolean spoils) {
        if (w.phase == Phase.ENDED) return;
        Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
        Colony loser = winner == null ? null : (winner == atk ? def : atk);
        w.phase = Phase.ENDED;
        w.restoreAt = System.currentTimeMillis() + plugin.settings().restoreDelaySeconds * 1000L;
        hide(w);
        // take down the camp
        World cw = w.campWorld == null ? null : Bukkit.getWorld(w.campWorld);
        if (cw != null && w.camp != null && w.camp.loaded(cw) && Tag.BANNERS.isTagged(w.camp.block(cw).getType())) {
            w.camp.block(cw).setType(Material.AIR);
        }
        String loot = "";
        if (spoils && winner != null && loser != null) loot = plunder(loser, winner);
        long now = System.currentTimeMillis();
        if (loser != null) {
            loser.shieldUntil = Math.max(loser.shieldUntil, now + (long) (plugin.settings().shieldDefeatHours * 3600_000L));
            loser.addStability(-15);
        }
        if (winner != null) winner.addStability(10);
        if (atk != null) atk.warCooldownUntil = now + plugin.settings().cooldownMinutes * 60_000L;
        String head = winner == null ? "The war is over" : Text.esc(winner.name) + " wins the war";
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<gold>" + head + "</gold> <gray>- " + reason + "."));
        for (Colony c : new Colony[]{atk, def}) {
            if (c == null) continue;
            boolean won = c == winner;
            for (Player p : c.onlineMembers()) {
                Text.title(p, won ? "<gold>☭ VICTORY" : "<dark_red>DEFEAT", "<gray>" + reason, 10, 80, 30);
                Fx.sound(p, won ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.wither.death", 0.8f, 1f);
                if (!loot.isEmpty()) Text.send(p, won ? "<gold>Spoils of war delivered to your State Chest: <white>" + loot : "<red>The enemy plundered <white>" + loot + "</white> from your State Chest.");
                if (!won) Text.send(p, "<aqua>Your colony has a " + (int) plugin.settings().shieldDefeatHours + "-hour Peace Shield to recover.");
                if (plugin.settings().restoreBlocks && c == def && !w.restore.isEmpty()) {
                    Text.send(p, "<gray>Siege damage to your colony will be repaired in " + Text.duration(w.restoreAt - now) + ".");
                }
            }
        }
        if (!plugin.settings().restoreBlocks) w.restore.clear();
        if (w.restore.isEmpty()) wars.remove(w);
        plugin.requestSave();
    }

    /** Moves the configured share of every raw resource stack. Returns a short summary. */
    private String plunder(Colony loser, Colony winner) {
        double frac = plugin.settings().spoilsFraction;
        int stacks = 0, items = 0;
        for (Storage.Entry e : loser.storage.entries()) {
            ItemStack it = e.item();
            if (!raw(it)) continue;
            int take = (int) Math.floor(it.getAmount() * frac);
            if (take <= 0) continue;
            ItemStack proto = it.clone();
            int got = loser.storage.remove(s -> s.isSimilar(proto), take);
            if (got <= 0) continue;
            ItemStack moved = proto.clone();
            moved.setAmount(got);
            ItemStack left = winner.storage.add(moved);
            if (left != null) winner.spoils.add(left);
            items += got;
            stacks++;
        }
        return items == 0 ? "" : items + " items (" + stacks + " stacks)";
    }

    /** Raw resources: plain stackable goods (ores, ingots, logs, crops, food...), never tools, arms or named items. */
    public static boolean raw(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (it.getType().getMaxDurability() > 0 || it.getMaxStackSize() <= 1) return false;
        if (Items.id(it) != null) return false;
        if (it.hasItemMeta()) {
            ItemMeta m = it.getItemMeta();
            if (m.hasDisplayName() || m.hasLore() || m.hasEnchants()) return false;
        }
        String n = it.getType().name();
        return !n.contains("SHULKER_BOX") && !n.equals("BUNDLE");
    }

    private void restoreSome(War w, int max) {
        World world = Bukkit.getWorld(w.world == null ? "" : w.world);
        if (world == null) {
            w.restore.clear();
            return;
        }
        Iterator<Map.Entry<String, String>> it = w.restore.entrySet().iterator();
        int n = 0;
        while (it.hasNext() && n < max) {
            Map.Entry<String, String> e = it.next();
            BlockPos p = BlockPos.parse(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (!p.loaded(world)) continue;
            try {
                BlockData d = Bukkit.createBlockData(e.getValue());
                p.block(world).setBlockData(d, false);
            } catch (IllegalArgumentException ignored) {
                // a block from an older version; leave it
            }
            it.remove();
            n++;
        }
        if (w.restore.isEmpty()) {
            Colony def = plugin.colonies().get(w.defender);
            if (def != null) for (Player p : def.onlineMembers()) Text.send(p, "<green>Siege damage to " + Text.esc(def.name) + " has been repaired.");
        }
    }

    public void colonyRemoved(Colony c) {
        for (War w : new ArrayList<>(wars)) {
            if (w.phase == Phase.ENDED) continue;
            if (w.attacker.equals(c.id) || w.defender.equals(c.id)) {
                Colony other = plugin.colonies().get(w.attacker.equals(c.id) ? w.defender : w.attacker);
                end(w, other, Text.esc(c.name) + " was dissolved", false);
            }
        }
    }

    /** Admin: end a war with no winner. */
    public boolean forceEnd(Colony c) {
        War w = warOf(c);
        if (w == null) return false;
        end(w, null, "an administrator ended it", false);
        return true;
    }

    // ───────────── boss bars ─────────────

    private void bars() {
        long now = System.currentTimeMillis();
        for (War w : wars) {
            if (w.phase == Phase.ENDED) continue;
            Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
            if (atk == null || def == null) continue;
            String time = Text.duration(Math.max(0, w.phaseEnds - now));
            String a, d;
            float prog;
            if (w.phase == Phase.WARMUP) {
                long total = Math.max(1, w.phaseEnds - w.declaredAt);
                prog = (float) Math.max(0, Math.min(1, (w.phaseEnds - now) / (double) total));
                a = "<red>⚔ War on " + Text.esc(def.name) + " <gray>| siege in <white>" + time + (w.camp == null ? " <yellow>| Place your War Camp!" : " <green>| War Camp raised");
                d = "<red>⚠ " + Text.esc(atk.name) + " declared war <gray>| siege in <white>" + time;
            } else {
                prog = (float) Math.max(0, Math.min(1, def.stability / 100.0));
                String supply = w.supplyCut ? " <red>| SUPPLY CUT" : "";
                a = "<dark_red>⚔ SIEGE of " + Text.esc(def.name) + " <gray>| <white>" + time + " <gray>| enemy stability " + Math.round(def.stability) + "%" + supply;
                d = "<dark_red>⚠ SIEGE by " + Text.esc(atk.name) + " <gray>| <white>" + time + " <gray>| stability " + Math.round(def.stability) + "%" + supply;
            }
            if (w.atkBar == null) w.atkBar = BossBar.bossBar(Text.mm(a), prog, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            else {
                w.atkBar.name(Text.mm(a));
                w.atkBar.progress(prog);
            }
            if (w.defBar == null) w.defBar = BossBar.bossBar(Text.mm(d), prog, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            else {
                w.defBar.name(Text.mm(d));
                w.defBar.progress(prog);
            }
            show(w.atkBar, w.atkViewers, atk);
            show(w.defBar, w.defViewers, def);
        }
    }

    private static void show(BossBar bar, Set<UUID> viewers, Colony c) {
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(viewers)) {
            if (want.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            viewers.remove(u);
        }
        for (UUID u : want) {
            if (viewers.add(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    private void hide(War w) {
        for (UUID u : w.atkViewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && w.atkBar != null) p.hideBossBar(w.atkBar);
        }
        for (UUID u : w.defViewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && w.defBar != null) p.hideBossBar(w.defBar);
        }
        w.atkViewers.clear();
        w.defViewers.clear();
    }

    public void shutdown() {
        for (War w : wars) hide(w);
    }

    // ───────────── persistence ─────────────

    public List<Database.Row> snapshot() {
        List<Database.Row> out = new ArrayList<>();
        for (War w : wars) {
            YamlConfiguration y = new YamlConfiguration();
            y.set("attacker", w.attacker);
            y.set("defender", w.defender);
            y.set("phase", w.phase.name());
            y.set("declared", w.declaredAt);
            y.set("phase-ends", w.phaseEnds);
            y.set("camp-world", w.campWorld);
            y.set("camp", w.camp == null ? null : w.camp.toString());
            y.set("supply-cut", w.supplyCut);
            y.set("core-destroyed", w.coreDestroyed);
            y.set("world", w.world);
            y.set("restore-at", w.restoreAt);
            y.set("saved-at", System.currentTimeMillis());
            List<String> r = new ArrayList<>();
            for (Map.Entry<String, String> e : w.restore.entrySet()) r.add(e.getKey() + "|" + e.getValue());
            y.set("restore", r);
            out.add(new Database.Row(w.id, null, y.saveToString()));
        }
        return out;
    }

    public void load(List<Database.Row> rows) {
        wars.clear();
        for (Database.Row row : rows) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(row.data());
            } catch (InvalidConfigurationException e) {
                plugin.getLogger().warning("War " + row.id() + " is corrupt and was skipped");
                continue;
            }
            War w = new War(row.id(), y.getString("attacker", ""), y.getString("defender", ""));
            try {
                w.phase = Phase.valueOf(y.getString("phase", "ENDED"));
            } catch (IllegalArgumentException e) {
                w.phase = Phase.ENDED;
            }
            w.declaredAt = y.getLong("declared");
            w.phaseEnds = y.getLong("phase-ends");
            w.campWorld = y.getString("camp-world");
            w.camp = BlockPos.parse(y.getString("camp"));
            w.supplyCut = y.getBoolean("supply-cut");
            w.coreDestroyed = y.getBoolean("core-destroyed");
            w.world = y.getString("world");
            w.restoreAt = y.getLong("restore-at");
            for (String s : y.getStringList("restore")) {
                int i = s.indexOf('|');
                if (i > 0) w.restore.put(s.substring(0, i), s.substring(i + 1));
            }
            // the clocks stop while the server is down, so a siege can't run out unseen
            long savedAt = y.getLong("saved-at", System.currentTimeMillis());
            long down = Math.max(0, System.currentTimeMillis() - savedAt);
            if (w.phase != Phase.ENDED) w.phaseEnds += down;
            else w.restoreAt += down;
            if (w.phase == Phase.ENDED && w.restore.isEmpty()) continue;
            wars.add(w);
            seq++;
        }
    }
}
