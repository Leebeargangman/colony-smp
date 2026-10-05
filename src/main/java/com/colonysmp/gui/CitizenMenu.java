package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.data.Citizen;
import com.colonysmp.data.Colony;
import com.colonysmp.data.Job;
import com.colonysmp.data.Policy;
import com.colonysmp.data.Status;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/** One citizen: details, job assignment, prisoner sentences. */
public final class CitizenMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony col;
    private final Citizen c;

    public CitizenMenu(ColonySMP plugin, Player viewer, Colony col, Citizen c) {
        super(viewer, 6, "<dark_red>☭ <white>" + Text.esc(c.name));
        this.plugin = plugin;
        this.col = col;
        this.c = c;
    }

    public static ItemStack icon(ColonySMP plugin, Colony col, Citizen c) {
        Material m = switch (c.status) {
            case CITIZEN -> c.job.icon;
            case CHILD -> Material.EGG;
            case CAPTIVE -> Material.LEAD;
            case PRISONER -> Material.IRON_BARS;
            case SLAVE -> plugin.items().chainMaterial();
            case REBEL -> Material.TNT;
        };
        Npc n = plugin.npcs().npc(c);
        List<String> lore = new ArrayList<>();
        lore.add(c.status.color + c.title());
        lore.add("<gray>Trait: <white>" + c.trait.display + " <dark_gray>(" + c.trait.description + ")");
        double hp = n != null ? n.body.getHealth() : c.health;
        lore.add("<gray>Health: <white>" + Math.round(hp) + "/" + Math.round(plugin.settings().citizenHealth));
        if (c.status == Status.CITIZEN || c.status == Status.CHILD || c.status == Status.SLAVE) {
            lore.add("<gray>Happiness: " + need(c.happiness) + " <dark_gray>" + mood(c.happiness));
            lore.add("<gray>Rest:      " + need(c.rest) + (c.rest < plugin.settings().tired ? " <red>tired" : ""));
            lore.add("<gray>Education: <aqua>" + Math.round(c.education) + " " + Text.bar(c.education / 100, 10, "<aqua>", "<dark_gray>") + " <dark_gray>" + c.educationTier());
        }
        if (c.status.free() || c.status == Status.SLAVE) {
            lore.add("<gray>Fed last night: <white>" + Math.round(c.fed * 100) + "%");
            lore.add("<gray>Efficiency: <white>" + Math.round(plugin.npcs().efficiency(col, c, c.job) * 100) + "%");
        }
        if (c.status == Status.CITIZEN || c.status == Status.SLAVE) {
            ItemStack held = c.job == Job.GUARD || c.militia ? c.weapon : c.tool;
            lore.add("<gray>" + (c.job == Job.GUARD || c.militia ? "Weapon" : "Tool") + ": <white>" + (held == null ? "none" : Text.nice(held.getType().name())
                    + " <dark_gray>(" + Math.round((1 - Tools.wornFraction(held)) * 100) + "%)"));
            if (c.bow != null) lore.add("<gray>Bow: <white>" + c.arrows + " arrows");
        }
        if (c.status.free()) lore.add("<gray>Bed: <white>" + (c.bed == null ? "<red>homeless" : c.bed.toString()));
        if (c.status == Status.CHILD && c.bornDay >= 0) lore.add("<gray>Grows up after day <white>" + (c.bornDay + plugin.settings().maturityDays));
        if (c.status == Status.PRISONER) {
            lore.add("<gray>Resistance: <yellow>" + Math.round(c.resistance) + "% " + Text.bar(c.resistance / 100, 20, "<yellow>", "<dark_gray>"));
            lore.add("<gray>Policy: <white>" + c.policy.display);
            if (c.unfedDays > 0) lore.add("<red>Unfed for " + c.unfedDays + " day(s)");
        }
        if (c.cell != null && col.buildings.get(c.cell) != null) lore.add("<gray>Cell: <white>" + col.buildings.get(c.cell).label());
        if (!c.origin.isEmpty()) lore.add("<gray>Origin: <white>" + Text.esc(c.origin));
        lore.add("<dark_gray>" + (n == null ? "Far away" : n.activity));
        if (c.downed()) lore.add("<red><bold>DOWNED");
        ItemStack it = Items.icon(m, (c.downed() ? "<red>✚ " : "<white>") + Text.esc(c.name), lore);
        return it;
    }

    private static String need(double v) {
        String col = v >= 60 ? "<green>" : v >= 30 ? "<yellow>" : "<red>";
        return col + Math.round(v) + " " + Text.bar(v / 100, 10, col, "<dark_gray>");
    }

    private static String mood(double h) {
        if (h >= 80) return "(joyful)";
        if (h >= 60) return "(content)";
        if (h >= 40) return "(so-so)";
        if (h >= 20) return "(unhappy)";
        return "(miserable - may leave)";
    }

    @Override
    protected void draw() {
        boolean lead = col.leads(viewer.getUniqueId()) || plugin.isBypassing(viewer);
        set(4, icon(plugin, col, c));
        if (c.status == Status.CITIZEN) {
            int slot = 19;
            for (Job j : Job.values()) {
                boolean cur = c.job == j;
                boolean able = c.education >= j.education;
                List<String> lore = new ArrayList<>();
                lore.add("<gray>" + j.description);
                if (j.education > 0) lore.add((able ? "<green>" : "<red>") + "Needs education " + j.education + " (has " + Math.round(c.education) + ")");
                if (j == Job.FARMER) lore.add("<dark_gray>Needs a registered farm and a hoe");
                if (j == Job.MINER) lore.add("<dark_gray>Needs a registered mine and a pickaxe");
                if (j == Job.LUMBERJACK) lore.add("<dark_gray>Needs trees nearby and an axe");
                if (j == Job.BUILDER) lore.add("<dark_gray>Order buildings with the Blueprint Book");
                if (j == Job.GUARD) lore.add("<dark_gray>Draws weapons and armour from the State Chest");
                if (j == Job.WARDEN) lore.add("<dark_gray>Needs prisoners under Indoctrination");
                if (j == Job.FISHER) lore.add("<dark_gray>Needs water in the colony and a fishing rod");
                if (j == Job.HERDER) lore.add("<dark_gray>Needs animals in the colony (shears for sheep)");
                if (j == Job.COOK) lore.add("<dark_gray>Needs a furnace, smoker or campfire in the colony");
                if (j == Job.SMITH) lore.add("<dark_gray>Needs an anvil, furnace or blast furnace in the colony");
                if (j == Job.DOCTOR) lore.add("<dark_gray>Works anywhere; faster with a brewing stand");
                if (j == Job.TEACHER || j == Job.STUDENT) lore.add("<dark_gray>Needs a registered School (lectern inside)");
                lore.add("");
                lore.add(cur ? "<green>Current job" : !able ? "<red>Not educated enough - send them to school" : lead ? "<yellow>Click to assign" : "<gray>Leaders assign jobs");
                ItemStack icon = Items.icon(j.icon, (cur ? "<green>» " : "<white>") + j.display, lore);
                if (cur) {
                    ItemMeta meta = icon.getItemMeta();
                    meta.setEnchantmentGlintOverride(true);
                    icon.setItemMeta(meta);
                }
                set(slot, icon, click -> {
                    if (!lead || cur) return;
                    if (!able) {
                        Text.send(viewer, "<red>" + Text.esc(c.name) + " needs education " + j.education + " to be a " + j.display + " (has " + Math.round(c.education) + "). Make them a Student at a School first.");
                        return;
                    }
                    assign(j);
                    refresh();
                });
                slot++;
                if (slot == 26) slot = 28;
            }
        }
        if (c.status == Status.PRISONER || c.status == Status.SLAVE) {
            boolean indoc = c.status == Status.PRISONER;
            button(20, Material.WRITABLE_BOOK, (indoc ? "<green>» " : "<white>") + Policy.INDOCTRINATE.display, List.of("<gray>" + Policy.INDOCTRINATE.description, "",
                    "<gray>A Warden lowers Resistance each day.", "<gray>At 0% they join as an Equal Citizen.", "", indoc ? "<green>Current policy" : lead ? "<yellow>Click to switch" : "<gray>Leaders only"),
                    click -> {
                        if (!lead || indoc) return;
                        Text.send(viewer, plugin.prison().setPolicy(viewer, col, c, Policy.INDOCTRINATE));
                        refresh();
                    });
            button(22, plugin.items().chainMaterial(), (!indoc ? "<green>» " : "<white>") + Policy.ENSLAVE.display, List.of("<gray>" + Policy.ENSLAVE.description, "",
                    "<gray>Uses one <white>Iron Shackles</white> (inventory or chest).", "<red>Unguarded labourers, or stability below " + Math.round(plugin.settings().uprisingStability) + "%,",
                    "<red>start an uprising.", "", !indoc ? "<green>Current policy" : lead ? "<yellow>Click to sentence" : "<gray>Leaders only"),
                    click -> {
                        if (!lead || !indoc) return;
                        Text.send(viewer, plugin.prison().setPolicy(viewer, col, c, Policy.ENSLAVE));
                        refresh();
                    });
            if (!indoc) {
                boolean mining = c.labour != Job.LUMBERJACK;
                button(24, mining ? Material.IRON_PICKAXE : Material.IRON_AXE, "<white>Labour: " + (mining ? "Mining" : "Logging"),
                        List.of("<gray>Heavy labour assignment.", "", lead ? "<yellow>Click to switch" : "<gray>Leaders only"), click -> {
                            if (!lead) return;
                            plugin.prison().setLabour(col, c, mining ? Job.LUMBERJACK : Job.MINER);
                            refresh();
                        });
            }
        }
        if (c.status == Status.CAPTIVE && c.escortPlayer != null && lead) {
            button(22, Material.IRON_SWORD, "<white>Hand to the Guards", List.of("<gray>A Guard will take them to a free cell."), click -> {
                Npc n = plugin.npcs().npc(c);
                if (n != null) plugin.prison().handToGuards(viewer, n);
                refresh();
            });
        }
        if (!c.status.free() && c.status != Status.REBEL && lead) {
            button(31, Material.OAK_DOOR, "<white>Release", List.of("<gray>Let them go free. They leave the colony.", "", "<yellow>Click to release"),
                    click -> new ConfirmMenu(viewer, "<white>Release " + Text.esc(c.name) + "?", List.of("<gray>They will leave for good."),
                            () -> plugin.prison().release(viewer, col, c), this::open).open());
        }
        button(49, Material.COMPASS, "<aqua>Locate", List.of("<gray>Makes them glow for 15 seconds."), click -> {
            Npc n = plugin.npcs().npc(c);
            if (n == null) {
                Text.send(viewer, "<gray>" + Text.esc(c.name) + " is too far away (their part of the colony isn't loaded).");
                return;
            }
            n.body.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 300, 0, false, false));
            var l = n.body.getLocation();
            Text.send(viewer, Text.esc(c.name) + " is at <white>" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + "</white>.");
            viewer.closeInventory();
        });
        button(45, Material.ARROW, "<gray>Back", List.of(), click -> new CitizensMenu(plugin, viewer, col, !c.status.free(), 0).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void assign(Job j) {
        Job old = c.job;
        c.job = j;
        if (old == Job.GUARD && j != Job.GUARD && !c.militia) {
            // guards hand their arms back to the State
            List<ItemStack> back = new ArrayList<>();
            if (c.weapon != null) back.add(c.weapon);
            if (c.bow != null) back.add(c.bow);
            if (c.arrows > 0) back.add(new ItemStack(Material.ARROW, c.arrows));
            for (int i = 0; i < 4; i++) if (c.armor[i] != null) back.add(c.armor[i]);
            c.weapon = null;
            c.bow = null;
            c.arrows = 0;
            java.util.Arrays.fill(c.armor, null);
            plugin.npcs().deposit(col, back);
        }
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            n.resetWork();
            n.mover.stop();
            plugin.npcs().refresh(n);
        }
        if (j == Job.FARMER && col.buildings(com.colonysmp.data.BuildingType.FARM).isEmpty()) Text.send(viewer, "<yellow>Tip: register a farm with the Blueprint Book so they have fields to work.");
        if (j == Job.MINER && col.buildings(com.colonysmp.data.BuildingType.MINE).isEmpty()) Text.send(viewer, "<yellow>Tip: register a Mine Entrance with the Blueprint Book first.");
        if ((j == Job.TEACHER || j == Job.STUDENT) && col.buildings(com.colonysmp.data.BuildingType.SCHOOL).isEmpty()) Text.send(viewer, "<yellow>Tip: build a School (an enclosed room with a door and a lectern) and register it with the Blueprint Book.");
        Text.send(viewer, Text.esc(c.name) + " is now a <white>" + j.display + "</white>.");
        plugin.requestSave();
    }
}
