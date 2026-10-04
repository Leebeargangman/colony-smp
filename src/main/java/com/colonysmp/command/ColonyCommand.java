package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Rank;
import com.colonysmp.data.Status;
import com.colonysmp.gui.BuildingsMenu;
import com.colonysmp.gui.CitizensMenu;
import com.colonysmp.gui.ConfirmMenu;
import com.colonysmp.gui.EstablishMenu;
import com.colonysmp.gui.TownHallMenu;
import com.colonysmp.gui.WarMenu;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import com.colonysmp.war.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /colony - the command hub. */
public final class ColonyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "info", "quests", "mobilize", "declare", "surrender", "war", "storage", "citizens",
            "prison", "buildings", "invite", "join", "leave", "kick", "promote", "demote", "rename", "policy", "relocate", "abandon",
            "book", "core", "list", "spoils", "traveler", "admin");
    private static final List<String> ADMIN = List.of("reload", "give", "delete", "stability", "shield", "traveler", "endwar", "save", "bypass", "tp", "ration", "food");

    private final ColonySMP plugin;

    public ColonyCommand(ColonySMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            admin(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (sub.equals("list")) {
            list(sender);
            return true;
        }
        if (sub.equals("help")) {
            help(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            Text.send(sender, "Players only (try /colony list or /colony admin).");
            return true;
        }
        if (!p.hasPermission("colonysmp.play")) {
            Text.send(p, "<red>You don't have permission.");
            return true;
        }
        Colony c = plugin.colonies().of(p);
        switch (sub) {
            case "info" -> {
                Colony target = args.length > 1 ? plugin.colonies().byName(join(args, 1)) : c != null ? c : plugin.colonies().at(p.getLocation());
                if (target == null) {
                    Text.send(p, "You don't belong to a colony. Craft a <gold>Colony Selection Wand</gold> (stick + gold nugget side by side) to found one.");
                    return true;
                }
                sendInfo(p, target);
            }
            case "quests" -> {
                if (need(p, c)) return true;
                if (args.length > 1 && (args[1].equalsIgnoreCase("hide") || args[1].equalsIgnoreCase("show"))) {
                    plugin.quests().setHidden(c, p, args[1].equalsIgnoreCase("hide"));
                    Text.send(p, "Quest bar " + (args[1].equalsIgnoreCase("hide") ? "hidden" : "shown") + ".");
                    return true;
                }
                QuestManager q = plugin.quests();
                Text.raw(p, "<gold><bold>☭ Starter Quests</bold> <gray>- " + Text.esc(c.name));
                for (int i = 1; i <= 3; i++) {
                    String mark = c.questStage > i ? "<green>✔" : c.questStage == i ? "<yellow>➤" : "<dark_gray>✘";
                    Text.raw(p, " " + mark + " <white>" + i + ". " + q.title(i) + " <gray>- " + q.task(i));
                }
                Text.raw(p, c.questStage >= QuestManager.DONE ? "<green> All done! Tools and seeds were sent to the State Chest." : "<dark_gray> Reward: starter farming tools and seeds in the State Chest. (/colony quests hide|show)");
            }
            case "mobilize" -> {
                if (need(p, c) || lead(p, c)) return true;
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
                    if (!c.mobilized()) Text.send(p, "The colony isn't mobilized.");
                    else plugin.sim().demobilize(c, Text.esc(p.getName()) + " stood the militia down.");
                    return true;
                }
                String err = plugin.sim().mobilize(c, p);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "declare" -> {
                if (need(p, c)) return true;
                if (args.length < 2) {
                    new WarMenu(plugin, p).open();
                    return true;
                }
                Colony target = plugin.colonies().byName(join(args, 1));
                String err = plugin.wars().declare(p, target);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "surrender" -> {
                String err = plugin.wars().surrender(p);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "war" -> new WarMenu(plugin, p).open();
            case "storage", "chest" -> {
                if (need(p, c)) return true;
                if (!c.region.contains(p.getLocation()) || !p.getWorld().getName().equals(c.world)) {
                    Text.send(p, "<red>You must be inside your colony to open the State Chest remotely.");
                    return true;
                }
                int page = 0;
                if (args.length > 1) {
                    try {
                        page = Integer.parseInt(args[1]) - 1;
                    } catch (NumberFormatException ignored) {
                    }
                }
                plugin.townHall().openStorage(p, c, page);
            }
            case "citizens" -> {
                if (need(p, c)) return true;
                new CitizensMenu(plugin, p, c, false, 0).open();
            }
            case "prison" -> {
                if (need(p, c)) return true;
                new CitizensMenu(plugin, p, c, true, 0).open();
            }
            case "buildings" -> {
                if (need(p, c)) return true;
                new BuildingsMenu(plugin, p, c, 0).open();
            }
            case "hall", "townhall", "menu" -> {
                if (need(p, c)) return true;
                new TownHallMenu(plugin, p, c).open();
            }
            case "invite" -> {
                if (need(p, c) || lead(p, c)) return true;
                if (args.length < 2) {
                    Text.send(p, "/colony invite <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    Text.send(p, "<red>That player isn't online.");
                    return true;
                }
                if (plugin.colonies().coloniesOf(t.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) {
                    Text.send(p, "<red>" + Text.esc(t.getName()) + " already belongs to a colony.");
                    return true;
                }
                c.invites.add(t.getUniqueId());
                Text.send(p, "Invited <white>" + Text.esc(t.getName()) + "</white>.");
                t.sendMessage(Text.mm(Text.PREFIX + "<gold>" + Text.esc(p.getName()) + "</gold> invites you to join <gold>" + Text.esc(c.name) + "</gold>. ")
                        .append(Text.mm("<green><bold>[Join]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/colony join " + c.id))));
            }
            case "join" -> {
                if (args.length < 2) {
                    Text.send(p, "/colony join <colony>");
                    return true;
                }
                Colony t = plugin.colonies().byName(join(args, 1));
                if (t == null || !t.invites.contains(p.getUniqueId())) {
                    Text.send(p, "<red>You haven't been invited to that colony.");
                    return true;
                }
                if (plugin.colonies().coloniesOf(p.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) {
                    Text.send(p, "<red>Leave your current colony first.");
                    return true;
                }
                t.invites.remove(p.getUniqueId());
                t.members.put(p.getUniqueId(), Rank.MEMBER);
                for (Player m : t.onlineMembers()) Text.send(m, "<green>" + Text.esc(p.getName()) + " joined " + Text.esc(t.name) + ". Welcome, comrade!");
                plugin.quests().refresh(t);
                plugin.requestSave();
            }
            case "leave" -> {
                if (need(p, c)) return true;
                if (c.founder.equals(p.getUniqueId())) {
                    Text.send(p, "<red>The Chairman can't leave. Use /colony abandon to dissolve the colony, or promote someone and ask an admin.");
                    return true;
                }
                c.members.remove(p.getUniqueId());
                plugin.quests().refresh(c);
                Text.send(p, "You left " + Text.esc(c.name) + ".");
                for (Player m : c.onlineMembers()) Text.send(m, "<gray>" + Text.esc(p.getName()) + " left the colony.");
                plugin.requestSave();
            }
            case "kick", "promote", "demote" -> {
                if (need(p, c) || lead(p, c)) return true;
                if (args.length < 2) {
                    Text.send(p, "/colony " + sub + " <player>");
                    return true;
                }
                UUID u = member(c, args[1]);
                if (u == null) {
                    Text.send(p, "<red>No member called " + Text.esc(args[1]) + ".");
                    return true;
                }
                if (u.equals(c.founder)) {
                    Text.send(p, "<red>That's the Chairman.");
                    return true;
                }
                if (!c.founder.equals(p.getUniqueId()) && c.rank(u) == Rank.OFFICER) {
                    Text.send(p, "<red>Only the Chairman can do that to a Commissar.");
                    return true;
                }
                switch (sub) {
                    case "kick" -> {
                        c.members.remove(u);
                        Text.send(p, "Removed " + Text.esc(args[1]) + " from the colony.");
                        Player t = Bukkit.getPlayer(u);
                        if (t != null) Text.send(t, "<red>You were removed from " + Text.esc(c.name) + ".");
                    }
                    case "promote" -> {
                        c.members.put(u, Rank.OFFICER);
                        Text.send(p, Text.esc(args[1]) + " is now a Commissar.");
                    }
                    default -> {
                        c.members.put(u, Rank.MEMBER);
                        Text.send(p, Text.esc(args[1]) + " is now a Comrade.");
                    }
                }
                plugin.quests().refresh(c);
                plugin.requestSave();
            }
            case "rename" -> {
                if (need(p, c) || lead(p, c)) return true;
                if (args.length < 2) {
                    Text.send(p, "/colony rename <name>");
                    return true;
                }
                String name = join(args, 1);
                String bad = EstablishMenu.checkName(plugin, name, c);
                if (bad != null) {
                    Text.send(p, "<red>" + bad);
                    return true;
                }
                c.name = name;
                Text.send(p, "The colony is now called <gold>" + Text.esc(name) + "</gold>.");
                plugin.requestSave();
            }
            case "policy" -> {
                if (need(p, c) || lead(p, c)) return true;
                Policy pol = args.length > 1 ? Policy.parse(args[1]) : null;
                if (pol == null) {
                    Text.send(p, "Default prisoner policy: <white>" + c.defaultPolicy.display + "</white>. /colony policy <indoctrinate|enslave>");
                    return true;
                }
                c.defaultPolicy = pol;
                Text.send(p, "New prisoners will be put under <white>" + pol.display + "</white>.");
                plugin.requestSave();
            }
            case "relocate" -> {
                if (need(p, c)) return true;
                if (!c.founder.equals(p.getUniqueId())) {
                    Text.send(p, "<red>Only the Chairman can move the Town Hall.");
                    return true;
                }
                if (plugin.wars().warOf(c) != null) {
                    Text.send(p, "<red>Not during a war.");
                    return true;
                }
                if (c.core == null) {
                    Text.send(p, "Your Town Hall Core isn't placed. Use /colony core if you lost the item.");
                    return true;
                }
                new ConfirmMenu(p, "<yellow>Move the Town Hall?", List.of("<gray>The core and State Chest block are", "<gray>removed (the chest's contents are kept)",
                        "<gray>and you get the core back to place again."), () -> {
                    World w = c.world();
                    if (w != null) {
                        Block core = c.core.block(w), chest = c.chest == null ? null : c.chest.block(w);
                        if (core.getType() == Material.LODESTONE) core.setType(Material.AIR);
                        if (chest != null && chest.getType() == Material.CHEST) chest.setType(Material.AIR);
                    }
                    c.core = null;
                    c.chest = null;
                    plugin.npcs().despawnColony(c);
                    give(p, plugin.items().core(c.id, c.name));
                    Text.send(p, "The Town Hall was dismantled. Place the core again inside your claim.");
                    plugin.requestSave();
                }, null).open();
            }
            case "abandon", "disband" -> {
                if (need(p, c)) return true;
                if (!c.founder.equals(p.getUniqueId())) {
                    Text.send(p, "<red>Only the Chairman can dissolve the colony.");
                    return true;
                }
                new ConfirmMenu(p, "<red>Dissolve " + Text.esc(c.name) + "?", List.of("<gray>Every citizen leaves, the claim is released", "<gray>and the State Chest spills its contents.", "<red>This can't be undone."),
                        () -> plugin.colonies().delete(c, "dissolved by its Chairman"), null).open();
            }
            case "book" -> {
                if (need(p, c)) return true;
                give(p, plugin.items().book());
                Text.send(p, "Here is a Colony Blueprint Book.");
            }
            case "core" -> {
                if (need(p, c) || lead(p, c)) return true;
                if (c.core != null) {
                    Text.send(p, "<red>Your Town Hall is already standing.");
                    return true;
                }
                for (ItemStack it : p.getInventory().getContents()) {
                    if (Items.is(it, Items.CORE) && c.id.equals(Items.colonyOf(it))) {
                        Text.send(p, "<gray>You already carry the Town Hall Core.");
                        return true;
                    }
                }
                give(p, plugin.items().core(c.id, c.name));
                Text.send(p, "Here is your Town Hall Core. Place it inside your claim.");
            }
            case "spoils" -> {
                if (need(p, c)) return true;
                claimSpoils(p, c);
            }
            case "traveler" -> {
                if (need(p, c)) return true;
                String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                String err = switch (act) {
                    case "recruit" -> plugin.travelers().recruit(c, p);
                    case "dismiss", "turnaway", "away" -> plugin.travelers().dismiss(c, p);
                    default -> "/colony traveler <recruit|dismiss>";
                };
                if (err != null) Text.send(p, "<red>" + err);
            }
            default -> help(p);
        }
        return true;
    }

    private boolean need(Player p, Colony c) {
        if (c != null) return false;
        Text.send(p, "<red>You don't belong to a colony. Craft a Colony Selection Wand (stick + gold nugget side by side) to found one.");
        return true;
    }

    private boolean lead(Player p, Colony c) {
        if (c.leads(p.getUniqueId()) || plugin.isBypassing(p)) return false;
        Text.send(p, "<red>Only the Chairman or a Commissar can do that.");
        return true;
    }

    private static UUID member(Colony c, String name) {
        for (UUID u : c.members.keySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length)).trim();
    }

    public static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    public void claimSpoils(Player p, Colony c) {
        if (c.spoils.isEmpty()) {
            Text.send(p, "There's nothing waiting to go into the State Chest.");
            return;
        }
        int moved = 0;
        Iterator<ItemStack> it = c.spoils.iterator();
        List<ItemStack> keep = new ArrayList<>();
        while (it.hasNext()) {
            ItemStack s = it.next();
            ItemStack left = c.storage.add(s);
            moved += s.getAmount() - (left == null ? 0 : left.getAmount());
            if (left != null) keep.add(left);
            it.remove();
        }
        c.spoils.addAll(keep);
        Text.send(p, "Moved <white>" + moved + "</white> items into the State Chest" + (keep.isEmpty() ? "." : ". <red>It's full: " + keep.size() + " stacks still waiting."));
        plugin.requestSave();
    }

    public void sendInfo(CommandSender to, Colony c) {
        long now = System.currentTimeMillis();
        OfflinePlayer f = Bukkit.getOfflinePlayer(c.founder);
        double need = plugin.sim().dailyNeed(c);
        double food = c.storage.foodPoints(plugin.settings().neverEat);
        int prisoners = 0, slaves = 0, children = 0;
        for (Citizen ct : c.citizens.values()) {
            if (ct.status == Status.PRISONER || ct.status == Status.CAPTIVE) prisoners++;
            if (ct.status == Status.SLAVE) slaves++;
            if (ct.status == Status.CHILD) children++;
        }
        Text.raw(to, "<dark_red>☭ <gold><bold>" + Text.esc(c.name) + "</bold></gold> <dark_gray>(" + c.id + ")");
        Text.raw(to, "<gray> Chairman: <white>" + Text.esc(f.getName() == null ? "?" : f.getName()) + "</white>  Members: <white>" + c.members.size());
        Text.raw(to, "<gray> Land: <white>" + c.region.width() + "x" + c.region.length() + "</white> at <white>" + c.region.minX() + ", " + c.region.minZ() + "</white> to <white>"
                + c.region.maxX() + ", " + c.region.maxZ() + "</white> (" + Text.esc(c.world) + ")");
        Text.raw(to, "<gray> Town Hall: " + (c.core == null ? "<red>not placed" : "<white>" + c.core));
        Text.raw(to, "<gray> Population: <white>" + c.population() + "</white> (" + children + " children)  Prisoners: <white>" + prisoners + "</white>  Forced labour: <white>" + slaves);
        Text.raw(to, "<gray> Stability: " + Text.stabilityColor(c.stability) + Math.round(c.stability) + "%" + (c.strike ? " <dark_red><bold>ON STRIKE" : "")
                + "  <gray>Food: <white>" + Math.round(food) + " pts" + (need > 0 ? " (" + String.format("%.1f", food / need) + " days)" : ""));
        Text.raw(to, "<gray> Reputation: <white>" + c.reputation + "/100  <gray>Peace Shield: " + (c.shielded() ? "<aqua>" + Text.duration(c.shieldUntil - now) : "<red>none"));
        WarManager.War w = plugin.wars().warOf(c);
        if (w != null) {
            Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
            Text.raw(to, "<red> At war: " + (atk == null ? "?" : Text.esc(atk.name)) + " vs " + (def == null ? "?" : Text.esc(def.name)) + " <gray>(" + Text.nice(w.phase.name()) + ", "
                    + Text.duration(Math.max(0, w.phaseEnds - now)) + " left)");
        }
        if (c.mobilized()) Text.raw(to, "<red> Mobilized for " + Text.duration(c.mobilizedUntil - now));
    }

    public void help(CommandSender to) {
        Text.raw(to, "<dark_red>☭ <gold><bold>ColonySMP</bold></gold> <gray>- commands");
        String[][] lines = {
                {"info [colony]", "colony overview"},
                {"quests [hide|show]", "starter quests"},
                {"citizens | prison | buildings", "management menus"},
                {"storage [page]", "open the Central State Chest (inside your claim)"},
                {"mobilize [stop]", "arm your workers as militia"},
                {"declare <colony>", "declare war (needs a War Banner)"},
                {"war | surrender", "war status / give up"},
                {"invite | join | leave | kick | promote | demote", "members"},
                {"rename <name> | policy <indoctrinate|enslave>", "settings"},
                {"traveler <recruit|dismiss>", "answer a visiting traveler"},
                {"book | core | spoils | relocate | abandon", "items and the Town Hall"},
                {"list", "every colony on the server"},
        };
        for (String[] l : lines) Text.raw(to, " <yellow>/colony " + l[0] + " <dark_gray>- <gray>" + l[1]);
        Text.raw(to, "<dark_gray> Wand: stick + gold nugget side by side. Rope: 3 string in a column. Shackles: iron, chain, iron. War Banner: red banner + iron sword + gold ingot.");
        if (to.hasPermission("colonysmp.admin")) Text.raw(to, " <red>/colony admin <" + String.join("|", ADMIN) + ">");
    }

    private void list(CommandSender to) {
        if (plugin.colonies().all().isEmpty()) {
            Text.send(to, "No colonies have been founded yet.");
            return;
        }
        Text.raw(to, "<dark_red>☭ <gold>Colonies <gray>(" + plugin.colonies().all().size() + ")");
        for (Colony c : plugin.colonies().all()) {
            String state = plugin.wars().warOf(c) != null ? " <red>[WAR]" : c.shielded() ? " <aqua>[shield]" : "";
            Text.raw(to, " <gold>" + Text.esc(c.name) + "</gold> <gray>- pop " + c.population() + ", " + c.members.size() + " members, stability " + Math.round(c.stability) + "%" + state);
        }
    }

    // ───────────── admin ─────────────

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("colonysmp.admin")) {
            Text.send(s, "<red>You don't have permission.");
            return;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                Text.send(s, "<green>ColonySMP configuration reloaded.");
            }
            case "save" -> {
                plugin.saveNow();
                Text.send(s, "<green>Saved.");
            }
            case "give" -> {
                if (a.length < 2) {
                    Text.send(s, "/colony admin give <wand|book|rope|shackles|war_banner> [player] [amount]");
                    return;
                }
                ItemStack it = plugin.items().byId(a[1].toLowerCase(Locale.ROOT));
                if (it == null) {
                    Text.send(s, "<red>Unknown item.");
                    return;
                }
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (t == null) {
                    Text.send(s, "<red>No such player.");
                    return;
                }
                int n = 1;
                if (a.length > 3) {
                    try {
                        n = Math.max(1, Math.min(64, Integer.parseInt(a[3])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                it.setAmount(Math.min(n, it.getMaxStackSize()));
                give(t, it);
                Text.send(s, "Gave " + a[1] + " to " + t.getName() + ".");
            }
            case "bypass" -> {
                if (!(s instanceof Player p)) return;
                boolean on = plugin.toggleBypass(p);
                Text.send(p, "Claim bypass " + (on ? "<green>on" : "<red>off"));
            }
            case "delete", "stability", "shield", "traveler", "endwar", "tp", "ration", "food" -> {
                if (a.length < 2) {
                    Text.send(s, "/colony admin " + sub + " <colony> ...");
                    return;
                }
                Colony c = plugin.colonies().byName(a[1]);
                if (c == null) {
                    Text.send(s, "<red>No such colony.");
                    return;
                }
                switch (sub) {
                    case "delete" -> {
                        plugin.colonies().delete(c, "removed by an administrator");
                        Text.send(s, "Deleted " + Text.esc(c.name) + ".");
                    }
                    case "stability" -> {
                        double v = a.length > 2 ? parse(a[2], c.stability) : c.stability;
                        c.stability = Math.max(0, Math.min(100, v));
                        Text.send(s, Text.esc(c.name) + " stability set to " + Math.round(c.stability) + "%.");
                    }
                    case "shield" -> {
                        double h = a.length > 2 ? parse(a[2], 0) : 0;
                        c.shieldUntil = h <= 0 ? 0 : System.currentTimeMillis() + (long) (h * 3600_000L);
                        Text.send(s, Text.esc(c.name) + " Peace Shield " + (h <= 0 ? "removed." : "set to " + h + " hours."));
                    }
                    case "traveler" -> Text.send(s, plugin.travelers().summon(c) ? "A traveler is on the way." : "<red>Couldn't summon one (already one there, no Town Hall, or not loaded).");
                    case "endwar" -> Text.send(s, plugin.wars().forceEnd(c) ? "War ended." : "<red>That colony isn't at war.");
                    case "tp" -> {
                        if (!(s instanceof Player p)) return;
                        Location l = c.coreLocation();
                        if (l == null) {
                            World w = c.world();
                            if (w == null) return;
                            l = new Location(w, c.region.centerX(), w.getHighestBlockYAt((int) c.region.centerX(), (int) c.region.centerZ()) + 1, c.region.centerZ());
                        }
                        p.teleport(l.add(0, 1, 0));
                    }
                    case "ration" -> {
                        World w = c.world();
                        if (w == null) return;
                        plugin.sim().ration(c, w, NpcManager.day(w));
                        Text.send(s, "Ran the nightly rationing for " + Text.esc(c.name) + ".");
                    }
                    case "food" -> {
                        double pts = c.storage.foodPoints(plugin.settings().neverEat);
                        Text.send(s, Text.esc(c.name) + ": " + Math.round(pts) + " food points, daily need " + Math.round(plugin.sim().dailyNeed(c)) + ".");
                    }
                    default -> {
                    }
                }
                plugin.requestSave();
            }
            default -> {
                Text.raw(s, "<red>/colony admin reload | save | bypass | give <item> [player] [amount]");
                Text.raw(s, "<red>/colony admin delete|stability|shield|traveler|endwar|tp|ration|food <colony> [value]");
            }
        }
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ───────────── tab completion ─────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && (!s.equals("admin") || sender.hasPermission("colonysmp.admin"))) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "info", "declare", "join" -> {
                    for (Colony c : plugin.colonies().all()) if (c.name.toLowerCase(Locale.ROOT).startsWith(last) || c.id.startsWith(last)) out.add(c.id);
                }
                case "invite" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
                }
                case "kick", "promote", "demote" -> {
                    if (sender instanceof Player p) {
                        Colony c = plugin.colonies().of(p);
                        if (c != null) for (Map.Entry<UUID, Rank> e : c.members.entrySet()) {
                            String n = Bukkit.getOfflinePlayer(e.getKey()).getName();
                            if (n != null && n.toLowerCase(Locale.ROOT).startsWith(last)) out.add(n);
                        }
                    }
                }
                case "quests" -> out.addAll(filter(List.of("hide", "show"), last));
                case "mobilize" -> out.addAll(filter(List.of("stop"), last));
                case "policy" -> out.addAll(filter(List.of("indoctrinate", "enslave"), last));
                case "traveler" -> out.addAll(filter(List.of("recruit", "dismiss"), last));
                case "admin" -> {
                    if (sender.hasPermission("colonysmp.admin")) out.addAll(filter(ADMIN, last));
                }
                default -> {
                }
            }
            return out;
        }
        if (sub.equals("admin") && sender.hasPermission("colonysmp.admin") && args.length == 3) {
            String a = args[1].toLowerCase(Locale.ROOT);
            if (a.equals("give")) out.addAll(filter(List.of(Items.WAND, Items.BOOK, Items.ROPE, Items.SHACKLES, Items.WAR_BANNER), last));
            else if (!a.equals("reload") && !a.equals("save") && !a.equals("bypass")) {
                for (Colony c : plugin.colonies().all()) if (c.id.startsWith(last)) out.add(c.id);
            }
        }
        if (sub.equals("admin") && args.length == 4 && args[1].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
        }
        return out;
    }

    private static List<String> filter(List<String> all, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : all) if (s.startsWith(prefix)) out.add(s);
        return out;
    }
}
