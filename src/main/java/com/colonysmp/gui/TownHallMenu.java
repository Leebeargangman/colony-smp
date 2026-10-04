package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Rank;
import com.colonysmp.data.Status;
import com.colonysmp.prison.TravelerManager;
import com.colonysmp.util.Text;
import com.colonysmp.war.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Right-click the Town Hall Core: the colony at a glance and every control. */
public final class TownHallMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;

    public TownHallMenu(ColonySMP plugin, Player viewer, Colony col) {
        super(viewer, 6, "<dark_red>☭ Town Hall <dark_gray>- " + Text.esc(col.name));
        this.plugin = plugin;
        this.col = col;
    }

    @Override
    protected void draw() {
        boolean lead = col.leads(viewer.getUniqueId()) || plugin.isBypassing(viewer);
        long now = System.currentTimeMillis();
        int pop = col.population();
        double need = plugin.sim().dailyNeed(col);
        double food = col.storage.foodPoints(plugin.settings().neverEat);
        List<String> info = new ArrayList<>();
        OfflinePlayer founder = Bukkit.getOfflinePlayer(col.founder);
        info.add("<gray>Chairman: <white>" + Text.esc(founder.getName() == null ? "?" : founder.getName()));
        info.add("<gray>Land: <white>" + col.region.width() + "x" + col.region.length());
        info.add("<gray>Population: <white>" + pop + "</white> <dark_gray>(" + col.citizens.size() + " people incl. prisoners)");
        info.add("<gray>Stability: " + Text.stabilityColor(col.stability) + Math.round(col.stability) + "% " + Text.bar(col.stability / 100, 20, Text.stabilityColor(col.stability), "<dark_gray>"));
        info.add("<gray>Food: <white>" + Math.round(food) + "</white> points" + (need > 0 ? " <dark_gray>(" + String.format("%.1f", food / need) + " days)" : ""));
        info.add("<gray>Last rations: <white>" + Math.round(col.lastFed * 100) + "%");
        info.add("<gray>Traveler reputation: <white>" + col.reputation + "/100");
        if (col.strike) info.add("<dark_red><bold>GENERAL STRIKE</bold> <red>(stability must reach " + Math.round(plugin.settings().strikeRecover) + "%)");
        else if (col.lowDays > 0) info.add("<red>Low stability for " + col.lowDays + "/" + plugin.settings().strikeDays + " days");
        info.add(col.shielded() ? "<aqua>Peace Shield: " + Text.duration(col.shieldUntil - now) : "<gray>Peace Shield: <red>none");
        if (col.mobilized()) info.add("<red>MOBILIZED: " + Text.duration(col.mobilizedUntil - now));
        if (col.uprisingSince > 0) info.add("<dark_red><bold>UPRISING IN PROGRESS");
        button(4, Material.RED_BANNER, "<red>☭ <gold>" + Text.esc(col.name), info, null);

        button(10, Material.VILLAGER_SPAWN_EGG, "<green>Citizens <gray>(" + pop + ")", List.of("<gray>Every comrade, their job and mood.", "", "<yellow>Click to view"),
                c -> new CitizensMenu(plugin, viewer, col, false, 0).open());
        button(12, Material.CHEST, "<gold>Central State Chest", List.of("<gray>" + (col.storage.pageCount() * 45 - col.storage.freeSlots()) + "/" + col.storage.pageCount() * 45 + " slots used", "", "<yellow>Click to open"),
                c -> plugin.townHall().openStorage(viewer, col, 0));
        int houses = col.buildings(BuildingType.HOUSE).size(), farms = col.buildings(BuildingType.FARM).size();
        button(14, Material.BRICKS, "<aqua>Buildings", List.of("<gray>Houses: <white>" + houses + "</white>  Farms: <white>" + farms,
                "<gray>Towers: <white>" + col.buildings(BuildingType.TOWER).size() + "</white>  Cells: <white>" + col.buildings(BuildingType.PRISON).size()
                        + "</white>  Mines: <white>" + col.buildings(BuildingType.MINE).size(),
                "<gray>Free beds: <white>" + plugin.sim().freeHouseBeds(col) + "</white>  Build orders: <white>" + col.buildQueue.size(), "", "<yellow>Click to manage"),
                c -> new BuildingsMenu(plugin, viewer, col, 0).open());
        int prisoners = 0;
        for (Citizen ct : col.citizens.values()) if (!ct.status.free()) prisoners++;
        button(16, Material.IRON_BARS, "<yellow>Prison <gray>(" + prisoners + ")", List.of("<gray>Captives, prisoners and forced labour.", "", "<yellow>Click to view"),
                c -> new CitizensMenu(plugin, viewer, col, true, 0).open());

        List<String> q = new ArrayList<>();
        QuestManager qm = plugin.quests();
        for (int i = 1; i <= 3; i++) {
            q.add((col.questStage > i ? "<green>✔ " : col.questStage == i ? "<yellow>➤ " : "<dark_gray>✘ ") + qm.title(i));
        }
        q.add("");
        q.add(col.questStage >= QuestManager.DONE ? "<green>All done!" : "<gray>" + qm.task(col.questStage));
        q.add("");
        q.add("<yellow>Click to " + (col.questHidden.contains(viewer.getUniqueId()) ? "show" : "hide") + " the quest bar");
        button(19, Material.BOOK, "<gold>Starter Quests", q, c -> {
            plugin.quests().setHidden(col, viewer, !col.questHidden.contains(viewer.getUniqueId()));
            refresh();
        });
        List<String> mob = new ArrayList<>();
        mob.add("<gray>Arm every farmer, builder, lumberjack");
        mob.add("<gray>and miner for " + plugin.settings().mobilizeMinutes + " minutes (weapons from");
        mob.add("<gray>the State Chest). Costs " + Math.round(plugin.settings().mobilizeCost) + " stability.");
        mob.add("");
        if (col.mobilized()) mob.add("<red>Mobilized: " + Text.duration(col.mobilizedUntil - now) + " <gray>(click to stand down)");
        else if (now < col.mobilizeCooldownUntil) mob.add("<gray>Available in " + Text.duration(col.mobilizeCooldownUntil - now));
        else mob.add(lead ? "<yellow>Click to mobilize" : "<gray>Leaders only");
        button(21, Material.IRON_SWORD, "<red>State Mobilization Order", mob, c -> {
            if (!lead) return;
            if (col.mobilized()) {
                plugin.sim().demobilize(col, Text.esc(viewer.getName()) + " stood the militia down.");
                refresh();
                return;
            }
            new ConfirmMenu(viewer, "<red>Mobilize the workers?", List.of("<gray>Production stops while they fight."), () -> {
                String err = plugin.sim().mobilize(col, viewer);
                if (err != null) Text.send(viewer, "<red>" + err);
            }, () -> open()).open();
        });
        WarManager.War war = plugin.wars().warOf(col);
        List<String> wl = new ArrayList<>();
        if (war == null) {
            wl.add("<gray>No war.");
            wl.add("<gray>Declare one with a <white>War Banner</white>.");
        } else {
            Colony atk = plugin.colonies().get(war.attacker), def = plugin.colonies().get(war.defender);
            wl.add("<red>" + (atk == null ? "?" : Text.esc(atk.name)) + " <gray>vs <red>" + (def == null ? "?" : Text.esc(def.name)));
            wl.add("<gray>Phase: <white>" + Text.nice(war.phase.name()) + " <gray>(" + Text.duration(Math.max(0, war.phaseEnds - now)) + ")");
        }
        wl.add("");
        wl.add("<yellow>Click for war options");
        button(23, Material.RED_BANNER, "<dark_red>War", wl, c -> new WarMenu(plugin, viewer).open());
        Policy pol = col.defaultPolicy;
        button(25, pol == Policy.ENSLAVE ? plugin.items().chainMaterial() : Material.WRITABLE_BOOK, "<yellow>Default Prisoner Policy: <white>" + pol.display,
                List.of("<gray>" + pol.description, "", "<gray>New prisoners get this policy (forced", "<gray>labour uses Iron Shackles from the chest).", "", lead ? "<yellow>Click to switch" : "<gray>Leaders only"),
                c -> {
                    if (!lead) return;
                    col.defaultPolicy = pol == Policy.ENSLAVE ? Policy.INDOCTRINATE : Policy.ENSLAVE;
                    plugin.requestSave();
                    refresh();
                });

        List<String> mem = new ArrayList<>();
        for (Map.Entry<UUID, Rank> e : col.members.entrySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            mem.add((op.isOnline() ? "<green>● " : "<dark_gray>● ") + "<white>" + Text.esc(op.getName() == null ? "?" : op.getName()) + " <gray>(" + e.getValue().display + ")");
            if (mem.size() > 14) {
                mem.add("<gray>...");
                break;
            }
        }
        mem.add("");
        mem.add("<gray>/colony invite <player>");
        button(28, Material.PLAYER_HEAD, "<green>Members <gray>(" + col.members.size() + ")", mem, null);
        if (!col.spoils.isEmpty()) {
            int n = 0;
            for (ItemStack it : col.spoils) n += it.getAmount();
            button(30, Material.GOLD_INGOT, "<gold>Overflow <gray>(" + n + " items)", List.of("<gray>Goods that didn't fit in the State Chest.", "", "<yellow>Click to move them in"),
                    c -> {
                        plugin.commands().claimSpoils(viewer, col);
                        refresh();
                    });
        }
        TravelerManager.Traveler t = plugin.travelers().of(col);
        if (t != null) {
            button(32, Material.EMERALD, "<#c9a26b>Traveler: <white>" + Text.esc(t.name), List.of("<gray>Trait: <white>" + t.trait.display, "<gray>" + t.trait.description,
                    "<gray>Status: <white>" + Text.nice(t.state.name()), "", "<yellow>Click to decide"), c -> new TravelerMenu(plugin, viewer, col, t).open());
        }
        button(34, Material.KNOWLEDGE_BOOK, "<aqua>How the Commune works", List.of(
                "<gray>Workers deliver everything to the State Chest.",
                "<gray>At sunset rations are shared from it: full",
                "<gray>rations raise stability, hunger lowers it.",
                "<gray>3 low days in a row = general strike.",
                "<gray>Free beds + 3 days of food = a child is born.",
                "<gray>Register houses, farms, towers, cells and",
                "<gray>mines with the Blueprint Book.",
                "", "<yellow>Click for the command list"), c -> {
            viewer.closeInventory();
            plugin.commands().help(viewer);
        });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
