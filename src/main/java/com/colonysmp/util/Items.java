package com.colonysmp.util;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/** The plugin's custom items, their recipes, and a guard that keeps them out of vanilla recipes. */
public final class Items implements Listener {

    public static final String WAND = "wand", CORE = "core", BOOK = "book", ROPE = "rope",
            SHACKLES = "shackles", WAR_BANNER = "war_banner";

    private final Plugin plugin;
    private final Material chain;

    public Items(Plugin plugin) {
        this.plugin = plugin;
        Material c = Material.matchMaterial("IRON_CHAIN");
        if (c == null) c = Material.matchMaterial("CHAIN");
        chain = c != null ? c : Material.IRON_BARS;
    }

    public Material chainMaterial() {
        return chain;
    }

    // ───────────── identification ─────────────

    public static String id(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) {
        return id.equals(id(it));
    }

    public static String colonyOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.COLONY, PersistentDataType.STRING);
    }

    // ───────────── factories ─────────────

    private ItemStack make(Material m, String id, String name, boolean glint, int maxStack, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        meta.lore(Text.lore(lore));
        meta.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, id);
        if (glint) meta.setEnchantmentGlintOverride(true);
        if (maxStack > 0) meta.setMaxStackSize(maxStack);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack wand() {
        return make(Material.STICK, WAND, "<gold><bold>Colony Selection Wand", true, 1,
                "<gray>Mark out the land of a new commune.",
                "",
                "<green>Left-Click</green> <gray>a block: <white>Position 1",
                "<white>Right-Click</white> <gray>a block: <white>Position 2",
                "<yellow>Shift + Right-Click</yellow><gray>: <white>Establish Colony",
                "",
                "<dark_gray>Claims must be 16x16 to 128x128.");
    }

    public ItemStack core(String colonyId, String colonyName) {
        ItemStack it = make(Material.LODESTONE, CORE, "<red><bold>☭ Town Hall Core", true, 1,
                "<gray>The heart of <white>" + Text.esc(colonyName) + "</white>.",
                "",
                "<gray>Place it inside your claim to found the",
                "<gray>colony. A <gold>Central State Chest</gold> will be",
                "<gray>built next to it and your first two",
                "<gray>comrades will arrive.");
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.COLONY, PersistentDataType.STRING, colonyId);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack book() {
        return make(Material.BOOK, BOOK, "<yellow><bold>Colony Blueprint Book", true, 1,
                "<gray>Plans approved by the Central Committee.",
                "",
                "<white>Hold</white> <gray>to preview a building outline",
                "<white>Right-Click</white><gray>: next blueprint",
                "<yellow>Shift + Right-Click</yellow> <gray>a block: <white>register",
                "<gray>  an existing building there",
                "<gold>Shift + Left-Click</gold><gray>: <white>order the Builder",
                "<gray>  to construct the blueprint");
    }

    public ItemStack rope() {
        return make(Material.LEAD, ROPE, "<#c9a26b>Rope", false, 16,
                "<gray>Right-click a <red>downed</red> person to bind them.",
                "<gray>Lead them into a <white>Prison Cell</white>, or",
                "<gray>Shift + Right-click them to hand them",
                "<gray>to your Guards for escort.");
    }

    public ItemStack shackles() {
        return make(chain, SHACKLES, "<gray><bold>Iron Shackles", false, 16,
                "<gray>Fitted to a prisoner sentenced to",
                "<white>Forced Labour</white><gray>. Set the policy from the",
                "<gray>prison menu with these in your inventory",
                "<gray>or in the Central State Chest.");
    }

    public ItemStack warBanner() {
        ItemStack it = make(Material.RED_BANNER, WAR_BANNER, "<dark_red><bold>War Banner", true, 1,
                "<gray>A call to arms against another colony.",
                "",
                "<white>Right-Click</white> <gray>(or <white>/colony declare</white>) to declare war.",
                "<white>Place</white> <gray>it outside the enemy's claim during the",
                "<gray>warm-up to raise your <red>War Camp</red>.");
        if (it.getItemMeta() instanceof BannerMeta bm) {
            bm.setPatterns(List.of(
                    new Pattern(DyeColor.YELLOW, PatternType.FLOWER),
                    new Pattern(DyeColor.RED, PatternType.BORDER)));
            bm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(bm);
        }
        return it;
    }

    public ItemStack byId(String id) {
        return switch (id) {
            case WAND -> wand();
            case BOOK -> book();
            case ROPE -> rope();
            case SHACKLES -> shackles();
            case WAR_BANNER -> warBanner();
            default -> null;
        };
    }

    /** A menu icon. Each gets a unique tag so icons never stack or merge with real items. */
    public static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.lore(lore));
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(Keys.UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack icon(Material m, String name, String... lore) {
        return icon(m, name, List.of(lore));
    }

    // ───────────── recipes ─────────────

    public void registerRecipes() {
        ShapedRecipe wand = new ShapedRecipe(Keys.RECIPE_WAND, wand());
        wand.shape("SG");
        wand.setIngredient('S', Material.STICK);
        wand.setIngredient('G', Material.GOLD_NUGGET);
        add(wand);

        ItemStack ropes = rope();
        ropes.setAmount(2);
        ShapedRecipe rope = new ShapedRecipe(Keys.RECIPE_ROPE, ropes);
        rope.shape("S", "S", "S");
        rope.setIngredient('S', Material.STRING);
        add(rope);

        ShapedRecipe shackles = new ShapedRecipe(Keys.RECIPE_SHACKLES, shackles());
        shackles.shape("ICI");
        shackles.setIngredient('I', Material.IRON_INGOT);
        shackles.setIngredient('C', chain);
        add(shackles);

        ShapelessRecipe banner = new ShapelessRecipe(Keys.RECIPE_BANNER, warBanner());
        banner.addIngredient(Material.RED_BANNER);
        banner.addIngredient(Material.IRON_SWORD);
        banner.addIngredient(Material.GOLD_INGOT);
        add(banner);

        ShapelessRecipe book = new ShapelessRecipe(Keys.RECIPE_BOOK, book());
        book.addIngredient(Material.BOOK);
        book.addIngredient(Material.GOLD_NUGGET);
        book.addIngredient(Material.PAPER);
        add(book);
    }

    private void add(Recipe r) {
        try {
            if (r instanceof ShapedRecipe s) Bukkit.removeRecipe(s.getKey());
            if (r instanceof ShapelessRecipe s) Bukkit.removeRecipe(s.getKey());
            Bukkit.addRecipe(r);
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Could not register recipe: " + e.getMessage());
        }
    }

    public void unregisterRecipes() {
        for (var k : List.of(Keys.RECIPE_WAND, Keys.RECIPE_ROPE, Keys.RECIPE_SHACKLES, Keys.RECIPE_BANNER, Keys.RECIPE_BOOK)) {
            Bukkit.removeRecipe(k);
        }
    }

    public void discover(Player p) {
        p.discoverRecipes(List.of(Keys.RECIPE_WAND, Keys.RECIPE_ROPE, Keys.RECIPE_SHACKLES, Keys.RECIPE_BANNER, Keys.RECIPE_BOOK));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        discover(e.getPlayer());
    }

    /** Custom items can't be eaten by vanilla recipes (a wand is not a torch handle). */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r instanceof ShapedRecipe s && s.getKey().getNamespace().equals(Keys.RECIPE_WAND.getNamespace())) return;
        if (r instanceof ShapelessRecipe s && s.getKey().getNamespace().equals(Keys.RECIPE_WAND.getNamespace())) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (id(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
