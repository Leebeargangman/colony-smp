package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Rank;
import com.colonysmp.store.Database;
import com.colonysmp.store.Storage;
import com.colonysmp.util.ItemCodec;
import com.colonysmp.util.Region;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Every colony on the server, indexed by id, member and land. */
public final class ColonyManager {

    private final ColonySMP plugin;
    private final Map<String, Colony> colonies = new LinkedHashMap<>();
    private final Map<UUID, Citizen> citizenIndex = new HashMap<>();

    public ColonyManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Collection<Colony> all() {
        return Collections.unmodifiableCollection(colonies.values());
    }

    public List<Colony> list() {
        return new ArrayList<>(colonies.values());
    }

    public Colony get(String id) {
        return id == null ? null : colonies.get(id);
    }

    public Colony byName(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Colony c : colonies.values()) if (c.name.equalsIgnoreCase(n) || c.id.equalsIgnoreCase(n)) return c;
        String u = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Colony c : colonies.values()) if (c.name.toLowerCase(Locale.ROOT).replace('_', ' ').equals(u)) return c;
        return null;
    }

    public Colony at(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return at(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public Colony at(Block b) {
        return at(b.getWorld().getName(), b.getX(), b.getZ());
    }

    public Colony at(String world, int x, int z) {
        for (Colony c : colonies.values()) {
            if (c.world.equals(world) && c.region.contains(x, z)) return c;
        }
        return null;
    }

    /** The colony a player belongs to (players belong to one colony unless the config allows more). */
    public Colony of(UUID player) {
        for (Colony c : colonies.values()) if (c.isMember(player)) return c;
        return null;
    }

    public Colony of(Player p) {
        return of(p.getUniqueId());
    }

    public List<Colony> coloniesOf(UUID player) {
        List<Colony> out = new ArrayList<>();
        for (Colony c : colonies.values()) if (c.isMember(player)) out.add(c);
        return out;
    }

    // ───────────── citizens ─────────────

    public Citizen citizen(UUID id) {
        return id == null ? null : citizenIndex.get(id);
    }

    public Colony colonyOf(Citizen c) {
        return get(c.colonyId);
    }

    public void addCitizen(Colony col, Citizen c) {
        c.colonyId = col.id;
        col.citizens.put(c.id, c);
        citizenIndex.put(c.id, c);
    }

    public void removeCitizen(Citizen c) {
        Colony col = get(c.colonyId);
        if (col != null) col.citizens.remove(c.id);
        citizenIndex.remove(c.id);
    }

    /** Moves a citizen to another colony (captured prisoners). */
    public void transferCitizen(Citizen c, Colony to) {
        Colony from = get(c.colonyId);
        if (from != null) from.citizens.remove(c.id);
        c.bed = null;
        c.bedBuilding = null;
        c.cell = null;
        addCitizen(to, c);
    }

    // ───────────── regions ─────────────

    /** Why a region can't be claimed, or null if it can. */
    public String validate(String world, Region r, Colony ignore) {
        int min = plugin.settings().claimMin, max = plugin.settings().claimMax;
        if (!plugin.settings().worldAllowed(world)) return "Colonies can't be founded in this world.";
        if (r.width() < min || r.length() < min) return "Too small: claims must be at least " + min + "x" + min + " (yours is " + r.width() + "x" + r.length() + ").";
        if (r.width() > max || r.length() > max) return "Too big: claims can be at most " + max + "x" + max + " (yours is " + r.width() + "x" + r.length() + ").";
        int gap = plugin.settings().claimGap;
        for (Colony c : colonies.values()) {
            if (c == ignore || !c.world.equals(world)) continue;
            if (c.region.overlaps(r, gap)) {
                return "Overlaps " + Text.esc(c.name) + (gap > 0 ? " (colonies need " + gap + " blocks between them)" : "") + ".";
            }
        }
        if (plugin.wars() != null && plugin.wars().campInside(world, r)) return "A War Camp stands on that land.";
        return null;
    }

    public String newId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "colony";
        if (base.length() > 24) base = base.substring(0, 24);
        String id = base;
        int n = 2;
        while (colonies.containsKey(id)) id = base + "_" + n++;
        return id;
    }

    public boolean nameTaken(String name, Colony ignore) {
        for (Colony c : colonies.values()) if (c != ignore && c.name.equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    public Colony create(Player founder, String world, Region r, String name) {
        Colony c = new Colony(newId(name));
        c.name = name;
        c.founder = founder.getUniqueId();
        c.members.put(founder.getUniqueId(), Rank.FOUNDER);
        c.world = world;
        c.region = r;
        c.createdAt = System.currentTimeMillis();
        c.shieldUntil = c.createdAt + (long) (plugin.settings().shieldNewHours * 3600_000L);
        c.reputation = plugin.settings().reputationStart;
        c.storage = new Storage(c.id, plugin.settings().storagePages);
        colonies.put(c.id, c);
        plugin.requestSave();
        return c;
    }

    /** Disbands a colony: bodies removed, core and chest broken, State Chest contents dropped at the chest. */
    public void delete(Colony c, String reason) {
        plugin.wars().colonyRemoved(c);
        plugin.npcs().despawnColony(c);
        plugin.travelers().colonyRemoved(c);
        plugin.quests().colonyRemoved(c);
        World w = c.world();
        if (w != null) {
            List<ItemStack> drop = new ArrayList<>(c.storage.contents());
            drop.addAll(c.spoils);
            Location at = c.chestLocation() != null ? c.chestLocation().add(0, 1, 0) : c.coreLocation();
            if (c.core != null) {
                Block b = c.core.block(w);
                if (b.getType() == Material.LODESTONE) b.setType(Material.AIR);
            }
            if (c.chest != null) {
                Block b = c.chest.block(w);
                if (b.getType() == Material.CHEST) b.setType(Material.AIR);
            }
            if (at != null && !drop.isEmpty()) {
                for (ItemStack it : drop) w.dropItemNaturally(at, it);
            }
        }
        for (Citizen ct : new ArrayList<>(c.citizens.values())) citizenIndex.remove(ct.id);
        c.citizens.clear();
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<red>" + Text.esc(c.name) + " has been dissolved" + (reason == null ? "." : ": " + reason));
        }
        colonies.remove(c.id);
        plugin.requestSave();
    }

    // ───────────── persistence ─────────────

    public void load(Database.Snapshot snap) {
        colonies.clear();
        citizenIndex.clear();
        Map<String, List<Storage.Entry>> items = new HashMap<>();
        for (Database.ItemRow r : snap.storage()) {
            ItemStack it = ItemCodec.fromBytes(r.item());
            if (it == null) {
                plugin.getLogger().warning("Dropped an unreadable item in " + r.colony() + "'s State Chest");
                continue;
            }
            items.computeIfAbsent(r.colony(), k -> new ArrayList<>()).add(new Storage.Entry(r.page(), r.slot(), it));
        }
        for (Database.Row r : snap.colonies()) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(r.data());
            } catch (InvalidConfigurationException e) {
                plugin.getLogger().severe("Colony " + r.id() + " is corrupt and was skipped: " + e.getMessage());
                continue;
            }
            Colony c = Colony.load(r.id(), y);
            if (c == null) {
                plugin.getLogger().severe("Colony " + r.id() + " is missing its founder, world or region and was skipped");
                continue;
            }
            c.storage = new Storage(c.id, plugin.settings().storagePages);
            List<ItemStack> over = c.storage.load(items.getOrDefault(c.id, List.of()));
            c.spoils.addAll(over);
            colonies.put(c.id, c);
        }
        for (Database.Row r : snap.citizens()) {
            Colony c = colonies.get(r.owner());
            if (c == null) continue;
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(r.data());
                UUID id = UUID.fromString(r.id());
                Citizen ct = Citizen.load(id, y);
                addCitizen(c, ct);
            } catch (InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().warning("Citizen " + r.id() + " is corrupt and was skipped");
            }
        }
        plugin.getLogger().info("Loaded " + colonies.size() + " colonies and " + citizenIndex.size() + " citizens");
    }

    public Database.Snapshot snapshot() {
        List<Database.Row> cols = new ArrayList<>(), cits = new ArrayList<>();
        List<Database.ItemRow> store = new ArrayList<>();
        for (Colony c : colonies.values()) {
            YamlConfiguration y = new YamlConfiguration();
            c.save(y);
            cols.add(new Database.Row(c.id, null, y.saveToString()));
            for (Citizen ct : c.citizens.values()) {
                plugin.npcs().rememberPosition(ct);
                YamlConfiguration cy = new YamlConfiguration();
                ct.save(cy);
                cits.add(new Database.Row(ct.id.toString(), c.id, cy.saveToString()));
            }
            for (Storage.Entry e : c.storage.entries()) {
                store.add(new Database.ItemRow(c.id, e.page(), e.slot(), ItemCodec.bytes(e.item())));
            }
        }
        return new Database.Snapshot(cols, cits, store, plugin.wars().snapshot());
    }

    /** Re-applies the configured page count to every State Chest (after a reload). */
    public void resizeStorage(int pages) {
        for (Colony c : colonies.values()) {
            if (c.storage.pageCount() == pages) continue;
            for (int i = 0; i < c.storage.pageCount(); i++) {
                for (var v : List.copyOf(c.storage.page(i).getViewers())) v.closeInventory();
            }
            List<ItemStack> over = c.storage.resize(pages);
            c.spoils.addAll(over);
        }
    }

    public static String describe(Colony c) {
        return Text.esc(c.name);
    }

    public static Player online(UUID u) {
        return u == null ? null : Bukkit.getPlayer(u);
    }
}
