package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.BlueprintManager;
import com.colonysmp.colony.Copier;
import com.colonysmp.colony.SelectionManager;
import com.colonysmp.data.Colony;
import com.colonysmp.data.CustomBlueprint;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Shift + Right-Click with the wand as a colony member: copy the selection as a blueprint for the Builders. */
public final class WandMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;

    public WandMenu(ColonySMP plugin, Player viewer, Colony col) {
        super(viewer, 3, "<gold>Colony Wand <dark_gray>- " + Text.esc(col.name));
        this.plugin = plugin;
        this.col = col;
    }

    @Override
    protected void draw() {
        SelectionManager.Selection s = plugin.selection().get(viewer);
        World w = viewer.getWorld();
        Copier.Box box = s == null || s.p1 == null || s.p2 == null || !w.getName().equals(s.world) ? null : Copier.box(s.p1, s.p2);
        String err = box == null ? "Set both corners first: left-click one corner, right-click the opposite corner (include the top and bottom)." : Copier.check(plugin, w, box);
        BlockFace facing = BlueprintManager.cardinal(viewer.getLocation().getYaw());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Saves the blocks between your two corners");
        lore.add("<gray>as a blueprint. Pick it in the <yellow>Blueprint Book</yellow>");
        lore.add("<gray>and order the Builders to build a copy anywhere");
        lore.add("<gray>in the colony. They carry the materials from");
        lore.add("<gray>the State Chest.");
        lore.add("");
        if (box != null) {
            lore.add("<gray>Selection: <white>" + box.size() + "</white> <dark_gray>(" + box.volume() + " blocks of space)");
            lore.add("<gray>Front: the side facing you (you look <white>" + facing.name().toLowerCase() + "</white>)");
        }
        lore.add("<gray>Blueprints: <white>" + col.blueprints.size() + "/" + plugin.settings().blueprintMax);
        lore.add("");
        lore.add(err == null ? "<yellow>Click to copy and name it" : "<red>✘ " + err);
        final String error = err;
        button(11, err == null ? Material.STRUCTURE_VOID : Material.GRAY_DYE, "<gold><bold>Copy as Blueprint", lore, c -> {
            if (error != null) return;
            viewer.closeInventory();
            plugin.prompt().ask(viewer, "Name this blueprint (e.g. <white>Big House</white>):", 60, typed -> copy(box, facing, typed));
        });
        List<String> list = new ArrayList<>();
        if (col.blueprints.isEmpty()) list.add("<gray>No copies yet.");
        for (CustomBlueprint cb : col.blueprints.values()) list.add("<dark_gray>• <white>" + Text.esc(cb.name) + " <gray>" + cb.size());
        list.add("");
        list.add("<yellow>Click to manage");
        button(13, Material.WRITABLE_BOOK, "<aqua>Colony Blueprints", list, c -> new BlueprintsMenu(plugin, viewer, col).open());
        button(15, Material.BARRIER, "<red>Clear Selection", List.of("<gray>Forget both corners."), c -> {
            plugin.selection().clear(viewer);
            viewer.closeInventory();
            Text.send(viewer, "Selection cleared.");
        });
        button(22, Material.SPYGLASS, "<aqua>Show Outline", List.of("<gray>Show the selection for 15 seconds."), c -> {
            if (s != null) s.showUntil = System.currentTimeMillis() + 15_000;
            viewer.closeInventory();
        });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void copy(Copier.Box box, BlockFace facing, String typed) {
        if (plugin.colonies().get(col.id) == null) return;
        String name = typed.trim();
        String bad = Copier.checkName(plugin, col, name);
        if (bad != null) {
            Text.send(viewer, "<red>" + bad);
            return;
        }
        String err = Copier.check(plugin, viewer.getWorld(), box);
        String[] why = {err};
        CustomBlueprint cb = err == null ? Copier.copy(plugin, viewer.getWorld(), box, facing, name, why) : null;
        if (cb == null) {
            Text.send(viewer, "<red>" + why[0]);
            return;
        }
        cb.author = viewer.getName();
        col.blueprints.put(CustomBlueprint.key(cb.name), cb);
        plugin.blueprints().selectCustom(viewer, cb);
        plugin.requestSave();
        Fx.sound(viewer, "minecraft:entity.villager.work_cartographer", 1f, 1.2f);
        Text.send(viewer, "<green>Saved blueprint <white>" + Text.esc(cb.name) + "</white> <gray>(" + cb.size() + ", " + cb.solid() + " blocks). "
                + "Hold the <yellow>Blueprint Book</yellow> - it's selected - look where it should go and <gold>Shift + Left-Click</gold> to order a copy.");
        if (!viewer.getInventory().containsAtLeast(plugin.items().book(), 1) && !hasBook(viewer)) {
            EstablishMenu.give(viewer, plugin.items().book());
            Text.send(viewer, "<gray>You didn't have a Blueprint Book, so here's one.");
        }
    }

    private static boolean hasBook(Player p) {
        for (var it : p.getInventory().getContents()) if (Items.is(it, Items.BOOK)) return true;
        return false;
    }
}
