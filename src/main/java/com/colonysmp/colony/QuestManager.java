package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Building;
import com.colonysmp.data.BuildingType;
import com.colonysmp.data.Colony;
import com.colonysmp.util.Fx;
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
 * Guided starter quests, shown on a boss bar:
 * 1 Found the State (place the core), 2 Worker Shelter (house with 2 beds), 3 Feed the Commune (32 food).
 */
public final class QuestManager implements Listener {

    public static final int DONE = 4;
    private static final int FOOD_GOAL = 32, BED_GOAL = 2;

    private final ColonySMP plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();

    public QuestManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public void start(Colony c) {
        c.questStage = 1;
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<gold>Starter quests unlocked!</gold> <gray>Follow the bar at the top of your screen. <white>/colony quests</white> lists them.");
        }
        check(c);
    }

    public String title(int stage) {
        return switch (stage) {
            case 1 -> "Found the State";
            case 2 -> "Worker Shelter";
            case 3 -> "Feed the Commune";
            default -> "Complete";
        };
    }

    public String task(int stage) {
        return switch (stage) {
            case 1 -> "Place the Town Hall Core inside your claim.";
            case 2 -> "Build an enclosed 5x5 room (walls included) with a door and 2 beds, then Shift + Right-Click inside it with the Blueprint Book (Starter House).";
            case 3 -> "Stock " + FOOD_GOAL + " food items (bread, wheat, carrots, meat...) in the Central State Chest.";
            default -> "All starter quests are done.";
        };
    }

    /** Progress of the current quest, 0..1, and a short label. */
    private double progress(Colony c, StringBuilder label) {
        switch (c.questStage) {
            case 1 -> {
                label.append(c.core != null ? "done" : "place the core");
                return c.core != null ? 1 : 0;
            }
            case 2 -> {
                int best = bestHouseBeds(c);
                label.append(Math.min(best, BED_GOAL)).append("/").append(BED_GOAL).append(" beds in a registered house");
                return Math.min(1.0, best / (double) BED_GOAL);
            }
            case 3 -> {
                int food = c.storage.foodItems(plugin.settings().neverEat);
                label.append(Math.min(food, FOOD_GOAL)).append("/").append(FOOD_GOAL).append(" food");
                return Math.min(1.0, food / (double) FOOD_GOAL);
            }
            default -> {
                return 1;
            }
        }
    }

    private int bestHouseBeds(Colony c) {
        int best = 0;
        for (Building b : c.buildings(BuildingType.HOUSE)) best = Math.max(best, b.beds.size());
        return best;
    }

    private boolean complete(Colony c) {
        return switch (c.questStage) {
            case 1 -> c.core != null;
            case 2 -> bestHouseBeds(c) >= BED_GOAL;
            case 3 -> c.storage.foodItems(plugin.settings().neverEat) >= FOOD_GOAL;
            default -> false;
        };
    }

    /** Advances quests whose goals are met and refreshes the bar. Cheap enough to call often. */
    public void check(Colony c) {
        int guard = 0;
        while (c.questStage < DONE && complete(c) && guard++ < 4) {
            int done = c.questStage;
            c.questStage++;
            for (Player p : c.onlineMembers()) {
                Text.title(p, "<gold>Quest Complete", "<white>" + title(done), 5, 50, 15);
                Fx.sound(p, "minecraft:entity.player.levelup", 0.8f, 1.2f);
                Text.send(p, "<green>✔ Quest " + done + "/3 complete: <white>" + title(done) + "</white>."
                        + (c.questStage < DONE ? " <gray>Next: <gold>" + title(c.questStage) + "</gold> - " + task(c.questStage) : ""));
            }
            if (c.questStage == DONE) reward(c);
            plugin.requestSave();
        }
        refresh(c);
    }

    private void reward(Colony c) {
        List<ItemStack> left = c.storage.addAll(plugin.settings().questReward.stream().map(ItemStack::clone).toList());
        c.spoils.addAll(left);
        for (Player p : c.onlineMembers()) {
            Text.title(p, "<red>☭ <gold>The Commune Thrives", "<gray>Starter tools and seeds sent to the State Chest", 10, 70, 20);
            Fx.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
            Text.send(p, "<gold>All starter quests complete!</gold> Farming tools and seeds were delivered to the <gold>Central State Chest</gold>.");
        }
    }

    /** Shows or hides the bar for each online member. */
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
        String name = "<gold>☭ Quest " + c.questStage + "/3: <yellow>" + title(c.questStage) + "</yellow> <gray>— " + label;
        if (bar == null) {
            bar = BossBar.bossBar(Text.mm(name), (float) prog, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            bars.put(c.id, bar);
        } else {
            bar.name(Text.mm(name));
            bar.progress((float) Math.max(0, Math.min(1, prog)));
        }
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) if (!c.questHidden.contains(p.getUniqueId())) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(shown)) {
            if (!want.contains(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
                shown.remove(u);
            }
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
        if (bar != null && shown != null) {
            for (UUID u : shown) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
            }
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
