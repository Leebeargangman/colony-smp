package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.prison.TravelerManager;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** A traveler at the Town Hall: recruit them, send them away, or (by force) take them. */
public final class TravelerMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;
    private final TravelerManager.Traveler t;

    public TravelerMenu(ColonySMP plugin, Player viewer, Colony col, TravelerManager.Traveler t) {
        super(viewer, 3, "<#c9a26b>Traveler <dark_gray>- " + Text.esc(t.name));
        this.plugin = plugin;
        this.col = col;
        this.t = t;
    }

    @Override
    protected void draw() {
        button(4, Material.LEATHER_CHESTPLATE, "<white>" + Text.esc(t.name), List.of(
                "<gray>Trait: <white>" + t.trait.display, "<gray>" + t.trait.description, "",
                "<gray>Your reputation with travelers: <white>" + col.reputation + "/100"), null);
        int beds = plugin.sim().freeHouseBeds(col);
        button(11, Material.EMERALD, "<green><bold>Recruit", List.of("<gray>Invite them to join the commune.", "",
                "<gray>Free beds: " + (beds > 0 ? "<green>" : "<red>") + beds, "<gray>Needs a day of food for one more.", "", "<yellow>Click to recruit"), c -> {
            String err = plugin.travelers().recruit(col, viewer);
            viewer.closeInventory();
            if (err != null) Text.send(viewer, "<red>" + err);
        });
        button(15, Material.BARRIER, "<red><bold>Turn Away", List.of("<gray>Send them on their way.", "", "<yellow>Click to dismiss"), c -> {
            String err = plugin.travelers().dismiss(col, viewer);
            viewer.closeInventory();
            if (err != null) Text.send(viewer, "<red>" + err);
        });
        button(22, Material.LEAD, "<gold>Take by force", List.of("<gray>Attack them: travelers surrender", "<gray>(go down) instead of dying.", "<gray>Then bind them with <white>Rope</white>.",
                "", "<red>Attacking: -" + plugin.settings().attackPenalty + " reputation", "<red>Capturing: -" + plugin.settings().capturePenalty + " reputation"), null);
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
