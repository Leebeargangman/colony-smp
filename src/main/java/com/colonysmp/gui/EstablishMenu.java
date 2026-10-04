package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.SelectionManager;
import com.colonysmp.data.Colony;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Region;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Shift + Right-Click with the wand: review the selection and found the colony. */
public final class EstablishMenu extends Menu {

    private final ColonySMP plugin;

    public EstablishMenu(ColonySMP plugin, Player viewer) {
        super(viewer, 3, "<dark_red>☭ Establish Colony");
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        SelectionManager.Selection s = plugin.selection().get(viewer);
        Region r = s == null ? null : s.region();
        Colony existing = plugin.colonies().of(viewer);
        int min = plugin.settings().claimMin, max = plugin.settings().claimMax;
        String err;
        if (r == null) err = "Set both corners with the wand first.";
        else if (!viewer.getWorld().getName().equals(s.world)) err = "Your selection is in another world.";
        else err = plugin.colonies().validate(s.world, r, null);
        if (err == null && plugin.colonies().coloniesOf(viewer.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) {
            err = "You already belong to " + Text.esc(existing.name) + ". Leave it first.";
        }

        List<String> lore = new ArrayList<>();
        if (r != null) {
            lore.add("<gray>Size: <white>" + r.width() + " x " + r.length() + " <dark_gray>(" + r.width() * r.length() + " blocks)");
            lore.add("<gray>From <white>" + r.minX() + ", " + r.minZ() + "</white> to <white>" + r.maxX() + ", " + r.maxZ());
        }
        lore.add("<gray>Allowed: <white>" + min + "x" + min + "</white> to <white>" + max + "x" + max);
        lore.add("");
        lore.add(err == null ? "<green>✔ This land can be claimed." : "<red>✘ " + err);
        lore.add("");
        lore.add("<gray>New colonies get a <aqua>" + (int) plugin.settings().shieldNewHours + "-hour Peace Shield</aqua>:");
        lore.add("<gray>no war can be declared on you while it lasts.");
        button(11, Material.FILLED_MAP, "<gold>Selected Land", lore, null);

        final boolean ok = err == null;
        if (ok) {
            button(13, Material.LIME_CONCRETE, "<green><bold>Establish Colony", List.of(
                    "<gray>Claim this land for the people.",
                    "",
                    "<gray>You'll name the colony in chat and get",
                    "<gray>a <red>Town Hall Core</red> and a <yellow>Blueprint Book</yellow>.",
                    "",
                    "<yellow>Click to confirm"), c -> confirm());
        } else {
            button(13, Material.GRAY_CONCRETE, "<gray>Establish Colony", List.of("<red>" + err), null);
        }
        button(15, Material.BARRIER, "<red>Clear Selection", List.of("<gray>Forget both corners."), c -> {
            plugin.selection().clear(viewer);
            viewer.closeInventory();
            Text.send(viewer, "Selection cleared.");
        });
        button(22, Material.SPYGLASS, "<aqua>Show Outline", List.of("<gray>Show the claim border for 15 seconds."), c -> {
            if (s != null) s.showUntil = System.currentTimeMillis() + 15_000;
            viewer.closeInventory();
        });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void confirm() {
        viewer.closeInventory();
        String def = "People's Commune of " + viewer.getName();
        plugin.prompt().ask(viewer, "Name your colony! Type a name, or <white>default</white> for <gold>" + Text.esc(def) + "</gold>.", 60, typed -> {
            String name = typed.equalsIgnoreCase("default") || typed.isBlank() ? def : typed.trim();
            String bad = checkName(plugin, name, null);
            if (bad != null) {
                Text.send(viewer, "<red>" + bad);
                return;
            }
            found(name);
        });
    }

    public static String checkName(ColonySMP plugin, String name, Colony ignore) {
        if (name.length() < 3 || name.length() > 32) return "Colony names must be 3 to 32 characters.";
        if (!name.matches("[A-Za-z0-9 '_.\\-]+")) return "Use letters, numbers, spaces and ' _ . - only.";
        if (plugin.colonies().nameTaken(name, ignore)) return "Another colony already has that name.";
        return null;
    }

    private void found(String name) {
        SelectionManager.Selection s = plugin.selection().get(viewer);
        Region r = s == null ? null : s.region();
        if (r == null || !viewer.getWorld().getName().equals(s.world)) {
            Text.send(viewer, "<red>Your selection is gone. Select the land again.");
            return;
        }
        String err = plugin.colonies().validate(s.world, r, null);
        if (err != null) {
            Text.send(viewer, "<red>" + err);
            return;
        }
        if (plugin.colonies().coloniesOf(viewer.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) {
            Text.send(viewer, "<red>You already belong to a colony.");
            return;
        }
        Colony c = plugin.colonies().create(viewer, s.world, r, name);
        plugin.selection().clear(viewer);
        give(viewer, plugin.items().core(c.id, c.name));
        give(viewer, plugin.items().book());
        Text.title(viewer, "<red>☭ " + Text.esc(c.name), "<gray>has been founded! Place the <red>Town Hall Core</red>.", 10, 70, 20);
        Fx.sound(viewer, "minecraft:ui.toast.challenge_complete", 0.8f, 1f);
        Text.send(viewer, "<gold>" + Text.esc(c.name) + "</gold> claims <white>" + r.width() + "x" + r.length() + "</white> blocks. "
                + "Place the <red>Town Hall Core</red> inside your claim to raise the Town Hall and the <gold>Central State Chest</gold>.");
        Text.send(viewer, "<aqua>Peace Shield</aqua> active for <white>" + Text.duration(c.shieldUntil - System.currentTimeMillis()) + "</white>.");
        plugin.getServer().broadcast(Text.mm(Text.PREFIX + "A new colony has been founded: <gold>" + Text.esc(c.name) + "</gold> by <white>" + Text.esc(viewer.getName())));
        plugin.quests().start(c);
    }

    static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }
}
