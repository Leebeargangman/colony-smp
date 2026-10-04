package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.gui.EstablishMenu;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Region;
import com.colonysmp.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The Colony Selection Wand: two corners and the Establish Colony menu. */
public final class SelectionManager implements Listener {

    public static final class Selection {
        public String world;
        public BlockPos p1, p2;
        public long showUntil;

        public Region region() {
            return p1 == null || p2 == null ? null : Region.of(p1.x(), p1.z(), p2.x(), p2.z());
        }
    }

    private static final Color GREEN = Color.fromRGB(80, 255, 80), WHITE = Color.fromRGB(255, 255, 255),
            RED = Color.fromRGB(255, 60, 60), ORANGE = Color.fromRGB(255, 160, 40);

    private final ColonySMP plugin;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public SelectionManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Selection get(Player p) {
        return selections.get(p.getUniqueId());
    }

    public void clear(Player p) {
        selections.remove(p.getUniqueId());
    }

    private Selection sel(Player p) {
        Selection s = selections.computeIfAbsent(p.getUniqueId(), k -> new Selection());
        String w = p.getWorld().getName();
        if (s.world != null && !s.world.equals(w)) {
            s.p1 = null;
            s.p2 = null;
        }
        s.world = w;
        return s;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.WAND)) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && p.isSneaking()) {
            e.setCancelled(true);
            new EstablishMenu(plugin, p).open();
            return;
        }
        if (a == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            Selection s = sel(p);
            s.p1 = BlockPos.of(e.getClickedBlock());
            mark(p, e.getClickedBlock(), true);
            report(p, s, "<green>Position 1</green> set at <white>" + s.p1.x() + ", " + s.p1.z());
        } else if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            Selection s = sel(p);
            s.p2 = BlockPos.of(e.getClickedBlock());
            mark(p, e.getClickedBlock(), false);
            report(p, s, "<white>Position 2</white> set at <white>" + s.p2.x() + ", " + s.p2.z());
        } else if (a == Action.LEFT_CLICK_AIR) {
            Selection s = selections.get(p.getUniqueId());
            if (s != null && s.region() != null) {
                s.showUntil = System.currentTimeMillis() + 10_000;
                report(p, s, "<gray>Showing your selection.");
            } else {
                Text.send(p, "<gray>Left-click a block for <green>Position 1</green>, right-click a block for <white>Position 2</white>.");
            }
        }
    }

    /** No breaking blocks with the wand in creative. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (Items.is(e.getPlayer().getInventory().getItemInMainHand(), Items.WAND)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selections.remove(e.getPlayer().getUniqueId());
    }

    private void mark(Player p, Block b, boolean first) {
        Location c = b.getLocation().add(0.5, 1.1, 0.5);
        if (first) {
            p.spawnParticle(Particle.HAPPY_VILLAGER, c, 25, 0.4, 0.3, 0.4, 0);
            Fx.box(p, b.getX(), b.getY(), b.getZ(), b.getX(), b.getY(), b.getZ(), GREEN, 0.2, 1.2f, true);
            Fx.sound(p, "minecraft:block.note_block.chime", 0.8f, 1.4f);
        } else {
            p.spawnParticle(Particle.END_ROD, c, 20, 0.3, 0.3, 0.3, 0.01);
            Fx.box(p, b.getX(), b.getY(), b.getZ(), b.getX(), b.getY(), b.getZ(), WHITE, 0.2, 1.2f, true);
            Fx.sound(p, "minecraft:block.note_block.chime", 0.8f, 1.8f);
        }
    }

    private void report(Player p, Selection s, String head) {
        Region r = s.region();
        if (r == null) {
            Text.send(p, head + "<gray>. Now set the other corner.");
            return;
        }
        s.showUntil = System.currentTimeMillis() + 15_000;
        String err = plugin.colonies().validate(s.world, r, null);
        String size = r.width() + "x" + r.length();
        if (err == null) {
            Text.send(p, head + "<gray>. Selection <green>" + size + "</green> is valid. <yellow>Shift + Right-Click</yellow> to establish your colony.");
        } else {
            Text.send(p, head + "<gray>. Selection <red>" + size + "</red>: <red>" + err);
        }
    }

    /** Draws selections for players holding the wand (every 5 ticks). */
    public void render() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean holding = Items.is(p.getInventory().getItemInMainHand(), Items.WAND);
            Selection s = selections.get(p.getUniqueId());
            if (!holding && (s == null || s.showUntil < now)) continue;
            World w = p.getWorld();
            double y = Math.floor(p.getLocation().getY()) + 0.15;
            if (holding) {
                // nearby claims in orange so borders are easy to see
                for (Colony c : plugin.colonies().all()) {
                    if (!c.world.equals(w.getName()) || c.region.distanceTo(p.getX(), p.getZ()) > 48) continue;
                    outline(p, c.region, y, ORANGE);
                }
            }
            if (s == null || !w.getName().equals(s.world)) continue;
            Region r = s.region();
            if (r != null) {
                boolean ok = plugin.colonies().validate(s.world, r, null) == null;
                outline(p, r, y, ok ? GREEN : RED);
            }
            if (s.p1 != null) Fx.dust(p, s.p1.x() + 0.5, s.p1.y() + 1.2, s.p1.z() + 0.5, GREEN, 2f);
            if (s.p2 != null) Fx.dust(p, s.p2.x() + 0.5, s.p2.y() + 1.2, s.p2.z() + 0.5, WHITE, 2f);
        }
    }

    private void outline(Player p, Region r, double y, Color c) {
        double px = p.getX(), pz = p.getZ();
        double x1 = r.minX(), z1 = r.minZ(), x2 = r.maxX() + 1, z2 = r.maxZ() + 1;
        for (double x = x1; x <= x2; x += 1) {
            dot(p, x, y, z1, px, pz, c);
            dot(p, x, y, z2, px, pz, c);
        }
        for (double z = z1; z <= z2; z += 1) {
            dot(p, x1, y, z, px, pz, c);
            dot(p, x2, y, z, px, pz, c);
        }
    }

    private void dot(Player p, double x, double y, double z, double px, double pz, Color c) {
        double dx = x - px, dz = z - pz;
        if (dx * dx + dz * dz > 48 * 48) return;
        Fx.dust(p, x, y, z, c, 1.3f);
    }
}
