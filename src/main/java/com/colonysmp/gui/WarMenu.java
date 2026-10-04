package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Colony;
import com.colonysmp.util.Text;
import com.colonysmp.war.WarManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** War status, surrender, and the list of colonies a War Banner can be raised against. */
public final class WarMenu extends Menu {

    private final ColonySMP plugin;

    public WarMenu(ColonySMP plugin, Player viewer) {
        super(viewer, 6, "<dark_red>⚔ War");
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Colony mine = plugin.colonies().of(viewer);
        long now = System.currentTimeMillis();
        WarManager.War war = mine == null ? null : plugin.wars().warOf(mine);
        if (war != null) {
            Colony atk = plugin.colonies().get(war.attacker), def = plugin.colonies().get(war.defender);
            List<String> lore = new ArrayList<>();
            lore.add("<red>" + (atk == null ? "?" : Text.esc(atk.name)) + " <gray>attacks <red>" + (def == null ? "?" : Text.esc(def.name)));
            lore.add("<gray>Phase: <white>" + Text.nice(war.phase.name()) + " <gray>(" + Text.duration(Math.max(0, war.phaseEnds - now)) + " left)");
            lore.add("<gray>War Camp: " + (war.camp == null ? "<red>not raised" : "<white>" + war.camp));
            if (def != null) lore.add("<gray>Defender stability: <white>" + Math.round(def.stability) + "%");
            if (war.supplyCut) lore.add("<red>Supply lines are cut!");
            lore.add("");
            lore.add("<gray>Attackers win by destroying the Town Hall");
            lore.add("<gray>Core, or cutting citizens off from the");
            lore.add("<gray>State Chest until stability hits 0%.");
            lore.add("<gray>Defenders win by holding out or");
            lore.add("<gray>breaking the War Camp banner.");
            button(4, Material.RED_BANNER, "<dark_red>Current War", lore, null);
            if (mine.leads(viewer.getUniqueId())) {
                button(8, Material.WHITE_BANNER, "<white>Surrender", List.of("<gray>End the war now. The enemy wins", "<gray>(and takes spoils if the siege began)."), c ->
                        new ConfirmMenu(viewer, "<white>Surrender?", List.of("<red>Your colony loses the war."), () -> {
                            String err = plugin.wars().surrender(viewer);
                            if (err != null) Text.send(viewer, "<red>" + err);
                        }, this::open).open());
            }
        } else {
            button(4, Material.WHITE_BANNER, "<gray>At peace", List.of(
                    "<gray>Declare war on a colony below. You need a",
                    "<white>War Banner</white> <gray>(red banner + iron sword + gold ingot).",
                    "<gray>After a " + plugin.settings().warmupMinutes + "-minute warm-up the siege",
                    "<gray>lasts up to " + plugin.settings().siegeMinutes + " minutes. The winner takes",
                    "<gray>" + Math.round(plugin.settings().spoilsFraction * 100) + "% of the loser's raw resources."), null);
        }
        List<Colony> targets = new ArrayList<>();
        for (Colony c : plugin.colonies().all()) if (c != mine) targets.add(c);
        if (mine != null && mine.coreLocation() != null) {
            var home = mine.coreLocation();
            targets.sort(Comparator.comparingDouble(c -> c.world.equals(mine.world) ? c.region.distanceTo(home.getX(), home.getZ()) : 1e9));
        }
        int slot = 9;
        for (Colony c : targets) {
            if (slot >= 54) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Members online: <white>" + c.onlineMembers().size() + "/" + c.members.size());
            lore.add("<gray>Population: <white>" + c.population());
            if (mine != null && c.world.equals(mine.world) && mine.coreLocation() != null) {
                lore.add("<gray>Distance: <white>" + Math.round(c.region.distanceTo(mine.coreLocation().getX(), mine.coreLocation().getZ())) + " blocks");
            } else {
                lore.add("<gray>World: <white>" + Text.esc(c.world));
            }
            String block = null;
            if (c.core == null) block = "No Town Hall yet";
            else if (c.shielded()) block = "Peace Shield: " + Text.duration(c.shieldUntil - now);
            else if (plugin.wars().warOf(c) != null) block = "Already at war";
            else if (c.onlineMembers().size() < plugin.settings().requireOnlineDefenders) block = "Not enough members online";
            lore.add("");
            lore.add(block == null ? "<yellow>Click to declare war" : "<red>" + block);
            final String why = block;
            button(slot++, block == null ? Material.RED_BANNER : Material.GRAY_BANNER, "<gold>" + Text.esc(c.name), lore, click -> {
                if (why != null || war != null) return;
                new ConfirmMenu(viewer, "<dark_red>Declare war on " + Text.esc(c.name) + "?", List.of("<gray>Siege begins in " + plugin.settings().warmupMinutes + " minutes.",
                        "<gray>Your own Peace Shield ends now."), () -> {
                    String err = plugin.wars().declare(viewer, c);
                    if (err != null) Text.send(viewer, "<red>" + err);
                }, this::open).open();
            });
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
