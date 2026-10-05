package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Blueprint;
import com.colonysmp.colony.Copier;
import com.colonysmp.data.Colony;
import com.colonysmp.data.CustomBlueprint;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** The colony's copied structures: pick one for the Blueprint Book, or delete it. */
public final class BlueprintsMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;

    public BlueprintsMenu(ColonySMP plugin, Player viewer, Colony col) {
        super(viewer, 3, "<aqua>Colony Blueprints");
        this.plugin = plugin;
        this.col = col;
    }

    @Override
    protected void draw() {
        boolean lead = col.leads(viewer.getUniqueId()) || plugin.isBypassing(viewer);
        int i = 0;
        for (CustomBlueprint cb : new ArrayList<>(col.blueprints.values())) {
            if (i >= 18) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Size: <white>" + cb.size() + "</white>, <white>" + cb.solid() + "</white> blocks");
            lore.add("<gray>Copied by <white>" + Text.esc(cb.author) + "</white> on " + new SimpleDateFormat("yyyy-MM-dd").format(new Date(cb.created)));
            lore.add("");
            lore.add("<gray>Needs (most used):");
            List<Map.Entry<String, Integer>> needs = new ArrayList<>(Blueprint.custom(cb).needs().entrySet());
            needs.sort((a, b) -> b.getValue() - a.getValue());
            for (int k = 0; k < needs.size() && k < 6; k++) lore.add("<dark_gray> • <white>" + needs.get(k).getValue() + "x <gray>" + needs.get(k).getKey());
            if (needs.size() > 6) lore.add("<dark_gray> • ...and " + (needs.size() - 6) + " more");
            lore.add("");
            lore.add("<yellow>Click: select it in your Blueprint Book");
            lore.add(lead ? "<red>Shift + Right-Click: delete" : "<gray>Leaders can delete blueprints");
            set(i++, Items.icon(Material.MAP, "<white>" + Text.esc(cb.name), lore), c -> {
                if (c.shift() && c.right()) {
                    if (!lead) return;
                    new ConfirmMenu(viewer, "<red>Delete " + Text.esc(cb.name) + "?", List.of("<gray>Orders to build it are cancelled."), () -> {
                        int n = Copier.delete(col, cb);
                        plugin.requestSave();
                        Text.send(viewer, "Deleted blueprint <white>" + Text.esc(cb.name) + "</white>" + (n > 0 ? " and cancelled " + n + " order(s)." : "."));
                    }, () -> new BlueprintsMenu(plugin, viewer, col).open()).open();
                    return;
                }
                plugin.blueprints().selectCustom(viewer, cb);
                viewer.closeInventory();
                Text.send(viewer, "<green>Selected <white>" + Text.esc(cb.name) + "</white>. Hold the <yellow>Blueprint Book</yellow>, look at the ground and <gold>Shift + Left-Click</gold> to order a copy. Face another way to turn it.");
            });
        }
        if (col.blueprints.isEmpty()) {
            button(13, Material.PAPER, "<gray>No blueprints yet", List.of("<gray>Select a structure with the <gold>Colony Wand</gold>",
                    "<gray>(left-click and right-click two opposite corners,", "<gray>top and bottom), then <yellow>Shift + Right-Click</yellow>."), null);
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
