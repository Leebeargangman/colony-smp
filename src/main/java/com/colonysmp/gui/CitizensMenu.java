package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Paged list of a colony's people (citizens, or prisoners). */
public final class CitizensMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;
    private final boolean prison;
    private final int page;

    public CitizensMenu(ColonySMP plugin, Player viewer, Colony col, boolean prison, int page) {
        super(viewer, 6, prison ? "<yellow>Prison <dark_gray>- " + Text.esc(col.name) : "<green>Citizens <dark_gray>- " + Text.esc(col.name));
        this.plugin = plugin;
        this.col = col;
        this.prison = prison;
        this.page = page;
    }

    @Override
    protected void draw() {
        List<Citizen> list = new ArrayList<>();
        for (Citizen c : col.citizens.values()) if (prison != c.status.free()) list.add(c);
        int pages = Math.max(1, (list.size() + 44) / 45);
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < list.size(); i++) {
            Citizen c = list.get(from + i);
            set(i, CitizenMenu.icon(plugin, col, c), click -> new CitizenMenu(plugin, viewer, col, c).open());
        }
        if (list.isEmpty()) {
            button(22, Material.BARRIER, prison ? "<gray>No prisoners" : "<gray>No citizens yet",
                    prison ? List.of("<gray>Down an enemy (or a traveler) in combat,", "<gray>bind them with Rope and lead them", "<gray>into a registered Prison Cell.")
                            : List.of("<gray>Place the Town Hall Core to receive", "<gray>your first two workers."), null);
        }
        if (page > 0) button(45, Material.ARROW, "<yellow>← Previous", List.of(), c -> new CitizensMenu(plugin, viewer, col, prison, page - 1).open());
        if (page < pages - 1) button(53, Material.ARROW, "<yellow>Next →", List.of(), c -> new CitizensMenu(plugin, viewer, col, prison, page + 1).open());
        button(49, Material.OAK_DOOR, "<gray>Back to the Town Hall", List.of(), c -> new TownHallMenu(plugin, viewer, col).open());
        button(47, prison ? Material.VILLAGER_SPAWN_EGG : Material.IRON_BARS, prison ? "<green>Show citizens" : "<yellow>Show prisoners", List.of(),
                c -> new CitizensMenu(plugin, viewer, col, !prison, 0).open());
        int active = 0;
        for (Citizen c : list) {
            Npc n = plugin.npcs().npc(c);
            if (n != null) active++;
        }
        button(51, Material.PAPER, "<gray>Page " + (page + 1) + "/" + pages, List.of("<gray>" + list.size() + " people, " + active + " nearby"), null);
        for (int i = 45; i < 54; i++) if (getInventory().getItem(i) == null) set(i, com.colonysmp.util.Items.icon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> "));
    }
}
