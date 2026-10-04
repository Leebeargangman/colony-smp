package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Blueprint;
import com.colonysmp.data.BuildJob;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Registered buildings and the Builders' order queue. */
public final class BuildingsMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;
    private final int page;

    public BuildingsMenu(ColonySMP plugin, Player viewer, Colony col, int page) {
        super(viewer, 6, "<aqua>Buildings <dark_gray>- " + Text.esc(col.name));
        this.plugin = plugin;
        this.col = col;
        this.page = page;
    }

    @Override
    protected void draw() {
        boolean lead = col.leads(viewer.getUniqueId()) || plugin.isBypassing(viewer);
        List<Object> all = new ArrayList<>(col.buildQueue);
        all.addAll(col.buildings.values());
        int pages = Math.max(1, (all.size() + 44) / 45);
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < all.size(); i++) {
            Object o = all.get(from + i);
            if (o instanceof BuildJob j) {
                Blueprint bp = Blueprint.of(j.type);
                int pct = bp == null ? 0 : j.step * 100 / Math.max(1, bp.steps.size());
                List<String> lore = new ArrayList<>();
                lore.add("<gold>Ordered - " + pct + "% built");
                lore.add("<gray>At <white>" + j.origin);
                if (j.waitingFor != null) lore.add("<red>Waiting for " + j.waitingFor);
                lore.add("");
                lore.add(lead ? "<yellow>Shift-click to cancel the order" : "<gray>Leaders can cancel orders");
                set(i, Items.icon(Material.SCAFFOLDING, "<gold>" + j.type.display + " <gray>(order #" + j.id + ")", lore), c -> {
                    if (!lead || !c.shift()) return;
                    col.buildQueue.remove(j);
                    Text.send(viewer, "Cancelled the " + j.type.display + " order. Blocks already placed stay.");
                    plugin.requestSave();
                    refresh();
                });
            } else if (o instanceof Building b) {
                List<String> lore = new ArrayList<>();
                switch (b.type) {
                    case HOUSE -> lore.add("<gray>Beds: <white>" + b.beds.size());
                    case PRISON -> lore.add("<gray>Cells: <white>" + b.beds.size());
                    case FARM -> lore.add("<gray>Tiles: <white>" + b.tiles.size());
                    case MINE -> lore.add("<gray>Facing <white>" + b.facing.name().toLowerCase() + "</white>, " + (b.exhausted ? "<red>exhausted" : "<white>" + b.progress + " blocks dug"));
                    case TOWER -> lore.add("<gray>Guard post at <white>" + b.anchor);
                }
                if (b.anchor != null) lore.add("<gray>At <white>" + b.anchor);
                lore.add("");
                lore.add(lead ? "<yellow>Shift-click to unregister" : "<gray>Leaders can unregister");
                set(i, Items.icon(b.type.icon, "<aqua>" + b.label(), lore), c -> {
                    if (!lead || !c.shift()) return;
                    col.buildings.remove(b.id);
                    plugin.sim().assignBeds(col);
                    Text.send(viewer, "Unregistered " + b.label() + ".");
                    plugin.requestSave();
                    refresh();
                });
            }
        }
        if (all.isEmpty()) {
            button(22, Material.BOOK, "<gray>Nothing registered yet", List.of("<gray>Hold the <yellow>Blueprint Book</yellow>:", "<white>Right-Click</white> <gray>to pick a blueprint,",
                    "<yellow>Shift + Right-Click</yellow> <gray>a block to register", "<gray>an existing building,", "<gold>Shift + Left-Click</gold> <gray>to order one built."), null);
        }
        if (page > 0) button(45, Material.ARROW, "<yellow>← Previous", List.of(), c -> new BuildingsMenu(plugin, viewer, col, page - 1).open());
        if (page < pages - 1) button(53, Material.ARROW, "<yellow>Next →", List.of(), c -> new BuildingsMenu(plugin, viewer, col, page + 1).open());
        button(49, Material.OAK_DOOR, "<gray>Back to the Town Hall", List.of(), c -> new TownHallMenu(plugin, viewer, col).open());
        List<String> types = new ArrayList<>();
        for (BuildingType t : BuildingType.values()) types.add("<white>" + t.display + "<gray>: " + t.description);
        button(47, Material.BOOK, "<yellow>Blueprints", types, null);
        button(51, Material.YELLOW_BED, "<gray>Free beds: <white>" + plugin.sim().freeHouseBeds(col), List.of("<gray>Children are born when there are", "<gray>free beds and 3 days of food."), null);
        for (int i = 45; i < 54; i++) if (getInventory().getItem(i) == null) set(i, Items.icon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> "));
    }
}
