package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Guided first steps, shown on a boss bar: found the colony, house the settlers, start a farm with a farmer,
 * and stock food. Finishing them sends starter supplies to storage.
 */
public final class QuestManager implements Listener {

    public static final int COUNT = 4, DONE = COUNT + 1;
    private static final int FOOD_GOAL = 32, BED_GOAL = 2;

    private final ColonySMP plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();

    public QuestManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public String title(int stage) {
        return switch (stage) {
            case 1 -> "Found the Colony";
            case 2 -> "A Roof Overhead";
            case 3 -> "Break Ground";
            case 4 -> "Stock the Stores";
            default -> "Complete";
        };
    }

    private static String button(String label, String command, String hover) {
        return "<click:run_command:'" + command + "'><hover:show_text:'<gray>" + hover + "'><aqua>[" + label + "]</aqua></hover></click>";
    }

    /** What to do next, with buttons that do it: shown on founding, after each step and with /colony guide. */
    public void guide(Player p, Colony c) {
        if (c.questStage >= DONE) {
            Text.raw(p, "<gold><bold>" + Text.esc(c.name) + "</bold> <gray>- your first steps are done. Ideas for what's next:");
            Text.raw(p, " <gray>• A <white>Tavern</white> (door, 4 stairs, a barrel): citizens meet there after work.");
            Text.raw(p, " <gray>• A <white>School</white> (door, lectern) so children and workers learn, and a <white>Clinic</white> for the sick.");
            Text.raw(p, " <gray>• Kitchens, a Bakery, a Market... place the Building Marker in anything you build to see what it is.");
            Text.raw(p, " " + button("Town Hall", "/colony menu", "Open the Town Hall menu") + " " + button("Building Marker", "/colony marker", "Get a Building Marker"));
            return;
        }
        int s = c.questStage;
        Text.raw(p, "<gold><bold>Step " + s + "/" + COUNT + ": " + title(s) + "</bold>");
        Text.raw(p, " <gray>" + task(s));
        switch (s) {
            case 1 -> Text.raw(p, " " + button("Get the Town Hall Core", "/colony core", "Hands you the core if you lost it"));
            case 2 -> {
                Text.raw(p, " <gray>Easiest: let your builder do it. Or build a room (3x3 inside, roof, wooden door, 2 beds) and place the marker in it.");
                Text.raw(p, " " + (c.starterHouse ? "" : button("Build me a free house", "/colony starterhouse", "Your builder builds a House next to the Town Hall; the materials are free") + " ")
                        + button("Building Marker", "/colony marker", "Get a Building Marker"));
            }
            case 3 -> {
                Text.raw(p, " <gray>Till 9+ dirt into farmland next to water, then place the Building Marker on the farmland. A Farmer is put to work automatically.");
                Text.raw(p, " " + button("Building Marker", "/colony marker", "Get a Building Marker") + " " + button("Citizens", "/colony citizens", "See your citizens"));
            }
            case 4 -> {
                Text.raw(p, " <gray>Your farmer fills storage as crops ripen. Speed it up by putting bread, carrots or potatoes in the storage chest.");
                Text.raw(p, " " + button("Storage", "/colony storage", "What the colony has"));
            }
            default -> {
            }
        }
    }

    public String task(int stage) {
        return switch (stage) {
            case 1 -> "Place the Town Hall Core where your colony should stand.";
            case 2 -> "Get your settlers a House with 2 beds.";
            case 3 -> "Register a Farm (place the Building Marker on its farmland) and have a Farmer working it.";
            case 4 -> "Have " + FOOD_GOAL + " food in colony storage (bread, carrots, potatoes, cooked meat...).";
            default -> "All first steps are done.";
        };
    }

    private double progress(Colony c, StringBuilder label) {
        switch (c.questStage) {
            case 1 -> {
                label.append(c.core != null ? "done" : "place the core");
                return c.core != null ? 1 : 0;
            }
            case 2 -> {
                int best = bestHouseBeds(c);
                label.append(Math.min(best, BED_GOAL)).append("/").append(BED_GOAL).append(" beds in a house");
                return Math.min(1.0, best / (double) BED_GOAL);
            }
            case 3 -> {
                boolean farm = !c.valid(BuildingType.FARM).isEmpty();
                boolean farmer = farmer(c);
                label.append(farm ? "farm registered" : "no farm yet").append(", ").append(farmer ? "farmer at work" : "no farmer yet");
                return (farm ? 0.5 : 0) + (farmer ? 0.5 : 0);
            }
            case 4 -> {
                long food = food(c);
                label.append(Math.min(food, FOOD_GOAL)).append("/").append(FOOD_GOAL).append(" food");
                return Math.min(1.0, food / (double) FOOD_GOAL);
            }
            default -> {
                return 1;
            }
        }
    }

    private static int bestHouseBeds(Colony c) {
        int best = 0;
        for (Building b : c.valid(BuildingType.HOUSE)) best = Math.max(best, b.beds.size());
        return best;
    }

    private static boolean farmer(Colony c) {
        for (Citizen ct : c.citizens.values()) {
            Building w = c.building(ct.workplace);
            if (ct.job == Job.FARMER && w != null && w.type == BuildingType.FARM && w.valid) return true;
        }
        return false;
    }

    private static long food(Colony c) {
        long n = 0;
        for (Map.Entry<String, Long> e : c.storage.contents().entrySet()) if (Goods.get(e.getKey()).nutrition() >= 1) n += e.getValue();
        return n;
    }

    private boolean complete(Colony c) {
        return switch (c.questStage) {
            case 1 -> c.core != null;
            case 2 -> bestHouseBeds(c) >= BED_GOAL;
            case 3 -> !c.valid(BuildingType.FARM).isEmpty() && farmer(c);
            case 4 -> food(c) >= FOOD_GOAL;
            default -> false;
        };
    }

    /** Advances quests whose goals are met and refreshes the bar. Cheap enough to call often. */
    public void check(Colony c) {
        int guard = 0;
        while (c.questStage < DONE && complete(c) && guard++ < COUNT) {
            int done = c.questStage;
            c.questStage++;
            for (Player p : c.onlineMembers()) {
                Text.title(p, "<gold>Step Complete", "<white>" + title(done), 5, 50, 15);
                Fx.sound(p, "minecraft:entity.player.levelup", 0.8f, 1.2f);
                Text.send(p, "<green>✔ Step " + done + "/" + COUNT + ": <white>" + title(done) + "</white>.");
                if (c.questStage < DONE) guide(p, c);
            }
            if (c.questStage == DONE) reward(c);
            plugin.requestSave();
        }
        refresh(c);
    }

    private void reward(Colony c) {
        for (ItemStack it : plugin.settings().questReward) {
            String good = ItemGoods.goodOf(it);
            if (good != null) c.storage.force(good, it.getAmount());
            else c.equipment.force(it);
        }
        for (Player p : c.onlineMembers()) {
            Text.title(p, "<gold>The Colony Takes Root", "<gray>Starter supplies sent to storage", 10, 70, 20);
            Fx.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
            Text.send(p, "<gold>First steps complete!</gold> Tools, seeds and food were delivered to colony storage. Open the Town Hall to see what to build next.");
        }
    }

    public void refresh(Colony c) {
        BossBar bar = bars.get(c.id);
        Set<UUID> shown = viewers.computeIfAbsent(c.id, k -> new HashSet<>());
        if (c.questStage >= DONE) {
            if (bar != null) {
                for (UUID u : shown) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.hideBossBar(bar);
                }
                shown.clear();
                bars.remove(c.id);
            }
            return;
        }
        StringBuilder label = new StringBuilder();
        double prog = progress(c, label);
        String name = "<aqua>Step " + c.questStage + "/" + COUNT + ": <yellow>" + title(c.questStage) + "</yellow> <gray>- " + label;
        if (bar == null) {
            bar = BossBar.bossBar(Text.mm(name), (float) prog, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
            bars.put(c.id, bar);
        } else {
            bar.name(Text.mm(name));
            bar.progress((float) Math.max(0, Math.min(1, prog)));
        }
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) if (!c.questHidden.contains(p.getUniqueId())) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(shown)) {
            if (want.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            shown.remove(u);
        }
        for (UUID u : want) {
            if (shown.add(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    public void setHidden(Colony c, Player p, boolean hidden) {
        if (hidden) c.questHidden.add(p.getUniqueId());
        else c.questHidden.remove(p.getUniqueId());
        refresh(c);
    }

    public void tick() {
        for (Colony c : plugin.colonies().all()) if (c.questStage < DONE) check(c);
    }

    public void colonyRemoved(Colony c) {
        BossBar bar = bars.remove(c.id);
        Set<UUID> shown = viewers.remove(c.id);
        if (bar == null || shown == null) return;
        for (UUID u : shown) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
        }
    }

    public void shutdown() {
        for (String id : List.copyOf(bars.keySet())) {
            Colony c = plugin.colonies().get(id);
            if (c != null) colonyRemoved(c);
        }
        bars.clear();
        viewers.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Colony c = plugin.colonies().of(e.getPlayer());
        if (c != null) Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(c), 20);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Set<UUID> s : viewers.values()) s.remove(e.getPlayer().getUniqueId());
    }
}
