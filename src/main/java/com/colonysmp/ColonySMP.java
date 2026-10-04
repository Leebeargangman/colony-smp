package com.colonysmp;

import com.colonysmp.colony.BlueprintManager;
import com.colonysmp.colony.ClaimListener;
import com.colonysmp.colony.ColonyManager;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.colony.SelectionManager;
import com.colonysmp.colony.Simulation;
import com.colonysmp.colony.TownHallListener;
import com.colonysmp.command.ColonyCommand;
import com.colonysmp.gui.ChatPrompt;
import com.colonysmp.gui.MenuListener;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.prison.PrisonManager;
import com.colonysmp.prison.TravelerManager;
import com.colonysmp.store.Database;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.war.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** ColonySMP: communist colonies for Paper. */
public final class ColonySMP extends JavaPlugin {

    private Settings settings;
    private Items items;
    private Database db;
    private ColonyManager colonies;
    private SelectionManager selection;
    private BlueprintManager blueprints;
    private QuestManager quests;
    private NpcManager npcs;
    private Simulation sim;
    private PrisonManager prison;
    private TravelerManager travelers;
    private WarManager wars;
    private TownHallListener townHall;
    private ColonyCommand commands;
    private ChatPrompt prompt;

    private long tick;
    private boolean dirty;
    private boolean loaded;
    private final Set<UUID> bypass = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        Keys.init(this);
        items = new Items(this);
        items.registerRecipes();

        colonies = new ColonyManager(this);
        wars = new WarManager(this);
        npcs = new NpcManager(this);
        sim = new Simulation(this);
        prison = new PrisonManager(this);
        travelers = new TravelerManager(this);
        quests = new QuestManager(this);
        blueprints = new BlueprintManager(this);
        selection = new SelectionManager(this);
        townHall = new TownHallListener(this);
        prompt = new ChatPrompt(this);
        commands = new ColonyCommand(this);

        db = new Database(this);
        try {
            db.open();
            Database.Snapshot snap = db.load();
            colonies.load(snap);
            wars.load(snap.wars());
            loaded = true;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the colony database. ColonySMP will not run, so nothing is overwritten.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        for (Listener l : new Listener[]{items, selection, blueprints, quests, npcs, prison, travelers, wars, townHall,
                new ClaimListener(this), new MenuListener(), prompt}) {
            Bukkit.getPluginManager().registerEvents(l, this);
        }
        PluginCommand cmd = getCommand("colony");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            npcs.tick(tick);
            travelers.move(tick);
        }, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            npcs.activityLoop();
            sim.tick();
            prison.tick();
            travelers.tick();
            wars.tick();
            quests.tick();
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            selection.render();
            blueprints.render();
        }, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) save();
        }, 600L, 600L);
        long auto = settings.autosaveMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::save, auto, auto);

        for (Player p : Bukkit.getOnlinePlayers()) items.discover(p);
        getLogger().info("ColonySMP enabled: " + colonies.all().size() + " colonies.");
    }

    @Override
    public void onDisable() {
        if (!loaded) return;
        Database.Snapshot snap = colonies.snapshot();
        npcs.despawnAll();
        travelers.shutdown();
        quests.shutdown();
        wars.shutdown();
        db.saveNow(snap);
        db.close();
        items.unregisterRecipes();
    }

    // ───────────── saving ─────────────

    /** Something changed: it will be written within 30 seconds. */
    public void requestSave() {
        dirty = true;
    }

    private void save() {
        dirty = false;
        db.saveAsync(colonies.snapshot());
    }

    public void saveNow() {
        dirty = false;
        db.saveAsync(colonies.snapshot());
    }

    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        items.registerRecipes();
        colonies.resizeStorage(settings.storagePages);
        requestSave();
    }

    // ───────────── access ─────────────

    public long tick() {
        return tick;
    }

    public boolean isBypassing(Player p) {
        return bypass.contains(p.getUniqueId()) && p.hasPermission("colonysmp.admin");
    }

    public boolean toggleBypass(Player p) {
        if (!bypass.remove(p.getUniqueId())) {
            bypass.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    public Settings settings() {
        return settings;
    }

    public Items items() {
        return items;
    }

    public ColonyManager colonies() {
        return colonies;
    }

    public SelectionManager selection() {
        return selection;
    }

    public BlueprintManager blueprints() {
        return blueprints;
    }

    public QuestManager quests() {
        return quests;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public Simulation sim() {
        return sim;
    }

    public PrisonManager prison() {
        return prison;
    }

    public TravelerManager travelers() {
        return travelers;
    }

    public WarManager wars() {
        return wars;
    }

    public TownHallListener townHall() {
        return townHall;
    }

    public ColonyCommand commands() {
        return commands;
    }

    public ChatPrompt prompt() {
        return prompt;
    }
}
