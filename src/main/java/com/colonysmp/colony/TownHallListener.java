package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.gui.TownHallMenu;
import com.colonysmp.store.Storage;
import com.colonysmp.store.StorageHolder;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The Town Hall Core block, the Central State Chest block, and the State Chest pages. */
public final class TownHallListener implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH};

    private final ColonySMP plugin;

    public TownHallListener(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── placing the core ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        Player p = e.getPlayer();
        if (Items.is(it, Items.CORE)) {
            e.setCancelled(true);
            placeCore(p, e.getBlockPlaced(), it, e.getHand());
            return;
        }
        // a chest next to the State Chest would turn it into a double chest
        Material m = e.getBlockPlaced().getType();
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
            for (BlockFace f : SIDES) {
                if (isStateChest(e.getBlockPlaced().getRelative(f)) != null) {
                    e.setCancelled(true);
                    Text.send(p, "<red>You can't build a chest against the Central State Chest.");
                    return;
                }
            }
        }
    }

    private void placeCore(Player p, Block block, ItemStack it, EquipmentSlot hand) {
        Colony c = plugin.colonies().get(Items.colonyOf(it));
        if (c == null) {
            Text.send(p, "<red>This Town Hall Core belongs to a colony that no longer exists.");
            return;
        }
        if (!c.isMember(p.getUniqueId())) {
            Text.send(p, "<red>Only members of " + Text.esc(c.name) + " can place its Town Hall Core.");
            return;
        }
        if (c.core != null) {
            Text.send(p, "<red>" + Text.esc(c.name) + " already has a Town Hall. Use <white>/colony relocate</white> to move it.");
            return;
        }
        if (!block.getWorld().getName().equals(c.world) || !c.region.contains(block.getX(), block.getZ())) {
            Text.send(p, "<red>Place the Town Hall Core inside your claim.");
            return;
        }
        Block chestBlock = null;
        for (BlockFace f : SIDES) {
            Block b = block.getRelative(f);
            if (!b.getType().isAir() && !b.isReplaceable()) continue;
            boolean nextToChest = false;
            for (BlockFace g : SIDES) {
                Material n = b.getRelative(g).getType();
                if (n == Material.CHEST || n == Material.TRAPPED_CHEST) nextToChest = true;
            }
            if (nextToChest) continue;
            chestBlock = b;
            break;
        }
        if (chestBlock == null) {
            Text.send(p, "<red>The Central State Chest needs a free block beside the core (not next to another chest).");
            return;
        }
        // place both blocks ourselves (the event is cancelled so the item is taken by hand)
        block.setType(Material.LODESTONE, false);
        chestBlock.setType(Material.CHEST, false);
        if (chestBlock.getBlockData() instanceof Chest cd) {
            BlockFace away = block.getFace(chestBlock);
            cd.setFacing(away == null ? BlockFace.SOUTH : away);
            cd.setType(Chest.Type.SINGLE);
            chestBlock.setBlockData(cd, false);
        }
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            ItemStack held = p.getInventory().getItem(hand);
            if (Items.is(held, Items.CORE)) {
                held.setAmount(held.getAmount() - 1);
                p.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
            }
        }
        c.core = BlockPos.of(block);
        c.chest = BlockPos.of(chestBlock);
        Location at = block.getLocation().add(0.5, 1.2, 0.5);
        Fx.particle(at, Particle.TOTEM_OF_UNDYING, 60, 0.6);
        Fx.sound(at, "minecraft:block.beacon.activate", 1.2f, 0.9f);
        Fx.sound(at, "minecraft:event.raid.horn", 0.5f, 1.4f);
        for (Player m : c.onlineMembers()) {
            Text.title(m, "<red>☭ Town Hall Raised", "<gold>" + Text.esc(c.name) + "</gold> <gray>is founded", 10, 60, 20);
        }
        Text.send(p, "The <red>Town Hall</red> stands and the <gold>Central State Chest</gold> has been built beside it. "
                + "Everything your workers produce goes into it.");
        if (!c.founded) {
            c.founded = true;
            plugin.npcs().spawnStarting(c);
        }
        plugin.quests().check(c);
        plugin.requestSave();
    }

    // ───────────── protecting the core and chest ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Colony core = isCore(b);
        if (core != null) {
            if (plugin.wars().onCoreBreak(e.getPlayer(), core)) {
                e.setDropItems(false);
                return;
            }
            e.setCancelled(true);
            if (core.isMember(e.getPlayer().getUniqueId())) {
                Text.send(e.getPlayer(), "<red>The Town Hall Core can't be broken. Founders can move it with <white>/colony relocate</white>.");
            } else {
                Text.send(e.getPlayer(), "<red>The Town Hall Core of " + Text.esc(core.name) + " can only be destroyed by attackers during a siege.");
            }
            return;
        }
        Colony chest = isStateChest(b);
        if (chest != null) {
            e.setCancelled(true);
            Text.send(e.getPlayer(), "<red>The Central State Chest belongs to the whole commune and can't be broken.");
        }
    }

    public Colony isCore(Block b) {
        Colony c = plugin.colonies().at(b);
        return c != null && c.core != null && c.core.x() == b.getX() && c.core.y() == b.getY() && c.core.z() == b.getZ() ? c : null;
    }

    public Colony isStateChest(Block b) {
        Colony c = plugin.colonies().at(b);
        return c != null && c.chest != null && c.chest.x() == b.getX() && c.chest.y() == b.getY() && c.chest.z() == b.getZ() ? c : null;
    }

    // ───────────── using them ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        // let people build against the blocks while sneaking with a block in hand
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock() && Items.id(e.getItem()) == null) return;
        Block b = e.getClickedBlock();
        Colony core = isCore(b);
        if (core != null) {
            e.setCancelled(true);
            if (core.isMember(p.getUniqueId()) || plugin.isBypassing(p)) new TownHallMenu(plugin, p, core).open();
            else plugin.commands().sendInfo(p, core);
            return;
        }
        Colony chest = isStateChest(b);
        if (chest != null) {
            e.setCancelled(true);
            if (chest.isMember(p.getUniqueId()) || plugin.isBypassing(p)) {
                openStorage(p, chest, 0);
            } else {
                Text.send(p, "<red>The Central State Chest of " + Text.esc(chest.name) + " is not yours.");
            }
        }
    }

    public void openStorage(Player p, Colony c, int page) {
        Storage s = c.storage;
        page = Math.max(0, Math.min(s.pageCount() - 1, page));
        s.nav(page);
        p.openInventory(s.page(page));
        Fx.sound(p, "minecraft:block.chest.open", 0.6f, 1f);
    }

    // ───────────── State Chest pages ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onStorageClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof StorageHolder h)) return;
        Colony c = plugin.colonies().get(h.colonyId);
        if (c == null) {
            e.setCancelled(true);
            return;
        }
        int raw = e.getRawSlot();
        boolean top = raw >= 0 && raw < 54;
        if (top && raw >= Storage.SLOTS) {
            e.setCancelled(true);
            String nav = Storage.navId(e.getCurrentItem());
            if (e.getWhoClicked() instanceof Player p) {
                if ("prev".equals(nav)) Bukkit.getScheduler().runTask(plugin, () -> openStorage(p, c, h.page - 1));
                else if ("next".equals(nav)) Bukkit.getScheduler().runTask(plugin, () -> openStorage(p, c, h.page + 1));
            }
            return;
        }
        // collecting to cursor could pull navigation icons
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        // shift-clicking into the chest: only the 45 storage slots may receive it
        if (!top && e.isShiftClick() && e.getCurrentItem() != null) {
            e.setCancelled(true);
            ItemStack moving = e.getCurrentItem().clone();
            ItemStack left = c.storage.add(moving);
            e.setCurrentItem(left);
            if (e.getWhoClicked() instanceof Player p) {
                if (left != null) Text.bar(p, "<red>The Central State Chest is full!");
                plugin.quests().check(c);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStorageDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof StorageHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= Storage.SLOTS && raw < 54) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onStorageClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof StorageHolder h)) return;
        Colony c = plugin.colonies().get(h.colonyId);
        if (c == null) return;
        c.storage.nav(h.page);
        plugin.quests().check(c);
    }

    // ───────────── hoppers feed the State Chest ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        Colony into = chestOf(e.getDestination());
        Colony from = chestOf(e.getSource());
        if (from != null) {
            // nothing leaves the State Chest by hopper
            e.setCancelled(true);
            return;
        }
        if (into == null) return;
        e.setCancelled(true);
        Inventory src = e.getSource();
        ItemStack item = e.getItem().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            var left = src.removeItem(item.clone());
            int removed = item.getAmount();
            for (ItemStack l : left.values()) removed -= l.getAmount();
            if (removed <= 0) return;
            ItemStack add = item.clone();
            add.setAmount(removed);
            ItemStack over = into.storage.add(add);
            if (over == null) return;
            // full: hand it back to the hopper (or drop it if that filled up meanwhile)
            for (ItemStack back : src.addItem(over).values()) {
                Location l = src.getLocation();
                if (l != null && l.getWorld() != null) l.getWorld().dropItemNaturally(l.add(0.5, 1, 0.5), back);
            }
        });
    }

    private Colony chestOf(Inventory inv) {
        if (inv == null) return null;
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) return null;
        return isStateChest(l.getBlock());
    }

    /** Is the chest block still standing? (re-placed if something removed it, e.g. WorldEdit) */
    public void verify(Colony c) {
        World w = c.world();
        if (w == null || c.core == null || c.chest == null || !c.chest.loaded(w)) return;
        Block cb = c.chest.block(w);
        if (cb.getType() != Material.CHEST) cb.setType(Material.CHEST, false);
        Block core = c.core.block(w);
        if (core.getType() != Material.LODESTONE && !plugin.wars().coreDestroyed(c)) core.setType(Material.LODESTONE, false);
    }
}
