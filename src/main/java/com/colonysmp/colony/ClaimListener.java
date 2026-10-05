package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps claims safe: only members build, except attackers during a siege. */
public final class ClaimListener implements Listener {

    private final ColonySMP plugin;
    private final Map<UUID, Long> lastDeny = new HashMap<>();
    private final Map<UUID, String> lastArea = new HashMap<>();

    public ClaimListener(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Can this player change blocks here? (Records the original block when an attacker does.) */
    public boolean canBuild(Player p, Block b, boolean record) {
        Colony c = plugin.colonies().at(b);
        if (c == null || c.isMember(p.getUniqueId()) || plugin.isBypassing(p)) return true;
        if (plugin.wars().siegeAttacker(p, c)) {
            if (record) plugin.wars().record(c, b);
            return true;
        }
        return false;
    }

    private void deny(Player p, Colony c, String what) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastDeny.put(p.getUniqueId(), now);
        Text.bar(p, "<red>☭ " + Text.esc(c.name) + " is protected: you can't " + what + " here.");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "break blocks");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Colony c = plugin.colonies().at(b);
        if (c != null && !c.isMember(e.getPlayer().getUniqueId()) && !plugin.isBypassing(e.getPlayer())) {
            if (plugin.wars().siegeAttacker(e.getPlayer(), c)) {
                plugin.wars().recordState(c, e.getBlockReplacedState());
                return;
            }
            e.setCancelled(true);
            deny(e.getPlayer(), c, "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "pour liquids");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "take liquids");
        }
    }

    @SuppressWarnings("deprecation") // Material#isInteractable is still the best broad check
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Colony c = plugin.colonies().at(b);
        Player p = e.getPlayer();
        if (c == null || c.isMember(p.getUniqueId()) || plugin.isBypassing(p)) return;
        if (e.getAction() == Action.PHYSICAL) {
            // no trampling farmland or triggering plates in someone else's colony
            if (b.getType() == Material.FARMLAND || !plugin.wars().siegeAttacker(p, c)) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        BlockState st = b.getState(false);
        boolean container = st instanceof Container;
        boolean siege = plugin.wars().siegeAttacker(p, c);
        if (container) {
            // attackers may build against containers (to wall in a State Chest) but never open them: sneaking with a
            // block in hand never opens a container, so that case is left to the game untouched
            if (siege && p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return;
            e.setCancelled(true);
            deny(p, c, "open containers");
            return;
        }
        boolean interactable = b.getType().isInteractable();
        if (interactable && !siege) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            // still allow placing a block against it if they could build (they can't) - so cancel fully
            e.setCancelled(true);
            deny(p, c, "use that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof Hanging || t instanceof ArmorStand || t instanceof Vehicle && !(t instanceof org.bukkit.entity.LivingEntity))) return;
        Colony c = plugin.colonies().at(t.getLocation());
        Player p = e.getPlayer();
        if (c != null && !c.isMember(p.getUniqueId()) && !plugin.isBypassing(p) && !plugin.wars().siegeAttacker(p, c)) {
            e.setCancelled(true);
            deny(p, c, "touch that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Colony c = plugin.colonies().at(e.getRightClicked().getLocation());
        if (c != null && !c.isMember(e.getPlayer().getUniqueId()) && !plugin.isBypassing(e.getPlayer())) {
            e.setCancelled(true);
            deny(e.getPlayer(), c, "touch that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p == null) return;
        Colony c = plugin.colonies().at(e.getEntity().getLocation());
        if (c != null && !c.isMember(p.getUniqueId()) && !plugin.isBypassing(p) && !plugin.wars().siegeAttacker(p, c)) {
            e.setCancelled(true);
            deny(p, c, "break that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleBreak(VehicleDestroyEvent e) {
        Player p = attacker(e.getAttacker());
        if (p == null) return;
        Colony c = plugin.colonies().at(e.getVehicle().getLocation());
        if (c != null && !c.isMember(p.getUniqueId()) && !plugin.isBypassing(p) && !plugin.wars().siegeAttacker(p, c)) {
            e.setCancelled(true);
            deny(p, c, "break that");
        }
    }

    /** Livestock, armor stands and item frames in a claim; and PvP. (Citizens are handled by the NPC manager.) */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = attacker(e.getDamager());
        if (p == null) return;
        Entity victim = e.getEntity();
        Colony c = plugin.colonies().at(victim.getLocation());
        if (victim instanceof Player target) {
            if (target.equals(p)) return;
            if (plugin.wars().pvpAllowed(p, target)) return;
            Colony atkC = plugin.colonies().of(p), defC = plugin.colonies().of(target);
            if (atkC != null && atkC == defC) return; // same colony: up to the server's own team rules
            Colony here = c != null ? c : plugin.colonies().at(p.getLocation());
            if (here != null && plugin.settings().blockPvp) {
                e.setCancelled(true);
                deny(p, here, "fight players (no war)");
            }
            return;
        }
        if (c == null || c.isMember(p.getUniqueId()) || plugin.isBypassing(p)) return;
        if (victim instanceof Animals || victim instanceof ArmorStand || victim instanceof Hanging) {
            if (plugin.wars().siegeAttacker(p, c)) return;
            e.setCancelled(true);
            deny(p, c, "hurt that");
        }
    }

    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    // ───────────── world damage ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        filter(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filter(e.blockList());
    }

    private void filter(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            Colony c = plugin.colonies().at(b);
            if (c == null) continue;
            if (plugin.townHall().isCore(b) != null || plugin.townHall().isStateChest(b) != null) {
                it.remove();
                continue;
            }
            if (plugin.wars().siegeActive(c)) {
                plugin.wars().record(c, b);
                continue;
            }
            if (plugin.settings().protectExplosions) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (plugin.settings().protectFire && plugin.colonies().at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Colony c = plugin.colonies().at(e.getBlock());
        if (c == null) return;
        if (e.getPlayer() != null) {
            if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
                e.setCancelled(true);
                deny(e.getPlayer(), c, "light fires");
            }
            return;
        }
        if (plugin.settings().protectFire && e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (plugin.settings().protectFire && e.getNewState().getType() == Material.FIRE && plugin.colonies().at(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Liquids can't flow into a claim from outside it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Colony to = plugin.colonies().at(e.getToBlock());
        if (to == null) return;
        Colony from = plugin.colonies().at(e.getBlock());
        if (from != to) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Colony pc = plugin.colonies().at(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (blocked(pc, b) || blocked(pc, b.getRelative(e.getDirection()))) {
                e.setCancelled(true);
                return;
            }
        }
        if (blocked(pc, e.getBlock().getRelative(e.getDirection()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Colony pc = plugin.colonies().at(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (blocked(pc, b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private boolean blocked(Colony pistonColony, Block b) {
        if (plugin.townHall().isCore(b) != null || plugin.townHall().isStateChest(b) != null) return true;
        Colony c = plugin.colonies().at(b);
        return c != null && c != pistonColony;
    }

    /** Mobs trampling farmland, endermen taking blocks, etc. inside a claim. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        Colony c = plugin.colonies().at(b);
        if (c == null) return;
        if (e.getEntity() instanceof Player p) {
            if (!canBuild(p, b, true)) e.setCancelled(true);
            return;
        }
        if (b.getType() == Material.FARMLAND) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof org.bukkit.entity.Enderman || e.getEntity() instanceof org.bukkit.entity.Ravager
                || e.getEntity() instanceof org.bukkit.entity.Wither || e.getEntity() instanceof org.bukkit.entity.EnderDragon) {
            if (!plugin.wars().siegeActive(c)) e.setCancelled(true);
        }
        if (Tag.DOORS.isTagged(b.getType()) && e.getEntity() instanceof org.bukkit.entity.Zombie) e.setCancelled(true);
    }

    // ───────────── entering / leaving ─────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.settings().enterMessages) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) return;
        Colony c = plugin.colonies().at(to);
        String id = c == null ? "" : c.id;
        String last = lastArea.put(e.getPlayer().getUniqueId(), id);
        if (last == null || last.equals(id)) return;
        Player p = e.getPlayer();
        if (c != null) {
            String rel = c.isMember(p.getUniqueId()) ? "<green>Welcome home, comrade." : c.shielded() ? "<aqua>Peace Shield active" : "<gray>Respect the State.";
            Text.bar(p, "<red>☭</red> <gold>Entering " + Text.esc(c.name) + "</gold> <dark_gray>|</dark_gray> " + rel);
        } else {
            Colony old = plugin.colonies().get(last);
            if (old != null) Text.bar(p, "<gray>Leaving " + Text.esc(old.name));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastArea.remove(e.getPlayer().getUniqueId());
        lastDeny.remove(e.getPlayer().getUniqueId());
    }
}
