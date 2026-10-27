package com.colonysmp.economy;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Market;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.store.Snapshot;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Money between colonies, the market and players. Colonies sell surplus and buy what they lack on the server-wide
 * market through a staffed Trading Post (better merchants get better prices), automatically if their policies say
 * so. Players put money into a treasury and take it out (through Vault if the server has an economy, otherwise as
 * emeralds), and can shop at a colony's staffed Market.
 */
public final class EconomyManager {

    private final ColonySMP plugin;
    private final Market market = new Market();
    private Object vault;
    private Method vHas, vWithdraw, vDeposit, vSuccess, vBalance;

    public EconomyManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Market market() {
        return market;
    }

    public void configure() {
        Settings s = plugin.settings();
        market.configure(s.marketRecovery, s.marketSpread);
        hookVault();
    }

    // ───────────── Vault (optional, by reflection) ─────────────

    private void hookVault() {
        vault = null;
        if (!plugin.settings().useVault || Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(eco);
            if (rsp == null) return;
            vault = rsp.getProvider();
            vHas = eco.getMethod("has", OfflinePlayer.class, double.class);
            vWithdraw = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            vDeposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            vBalance = eco.getMethod("getBalance", OfflinePlayer.class);
            vSuccess = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            plugin.getLogger().info("Using Vault for player money.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            vault = null;
            plugin.getLogger().warning("Vault is installed but its economy couldn't be used (" + e.getMessage() + "); emeralds will be used instead.");
        }
    }

    public boolean vault() {
        return vault != null;
    }

    /** Takes money from a player (Vault, or emeralds from their inventory). */
    private boolean charge(Player p, long amount) {
        if (amount <= 0) return true;
        if (vault != null) {
            try {
                if (!(boolean) vHas.invoke(vault, p, (double) amount)) return false;
                return (boolean) vSuccess.invoke(vWithdraw.invoke(vault, p, (double) amount));
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        Settings s = plugin.settings();
        long items = (amount + s.depositValue - 1) / s.depositValue;
        if (count(p, s.depositItem) < items) return false;
        if (p.getGameMode() == GameMode.CREATIVE) return true;
        long left = items;
        for (ItemStack it : p.getInventory().getContents()) {
            if (left <= 0) break;
            if (it == null || it.getType() != s.depositItem || ItemGoods.goodOf(it) == null) continue;
            int take = (int) Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
        }
        // change for a part of an emerald is not given back: prices are rounded up to whole emeralds
        return true;
    }

    /** Pays money to a player (Vault, or emeralds). Returns what was actually paid. */
    private long pay(Player p, long amount) {
        if (amount <= 0) return 0;
        if (vault != null) {
            try {
                boolean ok = (boolean) vSuccess.invoke(vDeposit.invoke(vault, p, (double) amount));
                return ok ? amount : 0;
            } catch (ReflectiveOperationException e) {
                return 0;
            }
        }
        Settings s = plugin.settings();
        long items = amount / s.depositValue;
        long given = 0;
        while (items > 0) {
            int n = (int) Math.min(items, s.depositItem.getMaxStackSize());
            for (ItemStack left : p.getInventory().addItem(new ItemStack(s.depositItem, n)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            items -= n;
            given += n;
        }
        return given * s.depositValue;
    }

    private static long count(Player p, Material m) {
        long n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == m && ItemGoods.goodOf(it) != null) n += it.getAmount();
        return n;
    }

    public String money(Player p) {
        if (vault != null) {
            try {
                return Text.money(Math.round((double) vBalance.invoke(vault, p)), plugin.settings().currency);
            } catch (ReflectiveOperationException e) {
                return "?";
            }
        }
        Settings s = plugin.settings();
        return count(p, s.depositItem) + " " + Text.nice(s.depositItem.name()).toLowerCase() + " (" + Text.money(count(p, s.depositItem) * s.depositValue, s.currency) + ")";
    }

    /** A member puts money into the treasury. */
    public String deposit(Player p, Colony c, long amount) {
        if (amount <= 0) return "How much?";
        if (!c.isMember(p.getUniqueId()) && !plugin.isBypassing(p)) return "You aren't a member of " + Text.esc(c.name) + ".";
        Settings s = plugin.settings();
        if (vault == null) amount = (amount + s.depositValue - 1) / s.depositValue * s.depositValue;
        if (!charge(p, amount)) {
            return vault != null ? "You don't have " + Text.money(amount, s.currency) + "."
                    : "You need " + (amount / s.depositValue) + " " + Text.nice(s.depositItem.name()).toLowerCase() + " (each is worth " + s.depositValue + " " + s.currency + ").";
        }
        c.treasury.credit(amount, TxnType.DEPOSIT, "Deposit by " + p.getName(), System.currentTimeMillis(), plugin.sim().day(c));
        plugin.requestSave();
        return null;
    }

    public String withdraw(Player p, Colony c, long amount) {
        if (amount <= 0) return "How much?";
        if (!plugin.access().can(p, c, Perm.TREASURY)) return "You may not withdraw from " + Text.esc(c.name) + "'s treasury.";
        Settings s = plugin.settings();
        if (vault == null) amount = amount / s.depositValue * s.depositValue;
        if (amount <= 0) return "Withdrawals are paid in " + Text.nice(s.depositItem.name()).toLowerCase() + " worth " + s.depositValue + " each.";
        if (c.treasury.debit(amount, TxnType.WITHDRAWAL, "Withdrawn by " + p.getName(), System.currentTimeMillis(), plugin.sim().day(c)) == null) {
            return "The treasury only has " + Text.money(c.treasury.balance(), s.currency) + ".";
        }
        long paid = pay(p, amount);
        if (paid < amount) c.treasury.credit(amount - paid, TxnType.ADMIN, "Withdrawal refund", System.currentTimeMillis(), plugin.sim().day(c));
        plugin.requestSave();
        return null;
    }

    // ───────────── the market ─────────────

    /** The colony's staffed Trading Post (or null): needed to trade on the market. */
    public Building tradingPost(Colony c) {
        return plugin.sim().citizens().staffed(c, BuildingType.TRADING_POST);
    }

    /** The best merchant's trading level (it narrows the spread). */
    public int merchantSkill(Colony c) {
        Building tp = tradingPost(c);
        if (tp == null) return 0;
        int best = 0;
        for (Citizen ct : c.workers(tp)) best = Math.max(best, ct.level(Skill.TRADING) + (ct.trait == com.colonysmp.model.Trait.CHARISMATIC ? 2 : 0));
        return best;
    }

    /** How much the colony would get for selling (null if it can't). */
    public String sell(Player by, Colony c, String good, long qty, boolean quiet) {
        if (by != null && !plugin.access().can(by, c, Perm.TRADE)) return "You may not trade for " + Text.esc(c.name) + ".";
        if (tradingPost(c) == null) return "You need a Trading Post with a merchant at work to trade on the market.";
        if (!Goods.known(good)) return "Unknown good.";
        if (c.storage.available(good) <= 0) return "Storage has no " + Goods.get(good).name() + " to sell.";
        qty = Math.min(qty, plugin.sim().finances().spare(c, good));
        if (qty <= 0) return "That food is the colony's reserve (" + c.policies.foodReserveDays + " days, see the policies).";
        int skill = merchantSkill(c);
        long price = market.quoteSale(good, qty, skill);
        if (price <= 0) return "Nobody will pay for that.";
        long got = c.storage.take(good, qty);
        if (got < qty) price = market.quoteSale(good, got, skill);
        market.recordSale(good, got);
        long day = plugin.sim().day(c);
        c.treasury.credit(price, TxnType.SALE, "Sold " + got + " " + Goods.get(good).name(), System.currentTimeMillis(), day);
        for (Citizen m : c.citizens.values()) if (m.job == com.colonysmp.model.Job.MERCHANT && m.status.free()) m.skills.gain(Skill.TRADING, got * 0.2);
        if (!quiet) c.log(ColonyEvent.Type.ECONOMY, day, (by == null ? "Sold " : by.getName() + " sold ") + got + " " + Goods.get(good).name() + " for " + Text.money(price, plugin.settings().currency) + ".");
        plugin.requestSave();
        return null;
    }

    public String buy(Player by, Colony c, String good, long qty, boolean quiet) {
        if (by != null && !plugin.access().can(by, c, Perm.TRADE)) return "You may not trade for " + Text.esc(c.name) + ".";
        if (tradingPost(c) == null) return "You need a Trading Post with a merchant at work to trade on the market.";
        if (!Goods.known(good) || Goods.get(good).custom() && !good.equals(Goods.FLOUR)) return "The market doesn't sell that.";
        qty = Math.min(qty, c.storage.room(good));
        if (qty <= 0) return "There's no room in storage for " + Goods.get(good).name() + ".";
        int skill = merchantSkill(c);
        long cost = market.quotePurchase(good, qty, skill);
        long day = plugin.sim().day(c);
        if (c.treasury.debit(cost, TxnType.PURCHASE, "Bought " + qty + " " + Goods.get(good).name(), System.currentTimeMillis(), day) == null) {
            return "That costs " + Text.money(cost, plugin.settings().currency) + "; the treasury has " + Text.money(c.treasury.balance(), plugin.settings().currency) + ".";
        }
        long added = c.storage.add(good, qty);
        market.recordPurchase(good, added);
        if (!quiet) c.log(ColonyEvent.Type.ECONOMY, day, (by == null ? "Bought " : by.getName() + " bought ") + added + " " + Goods.get(good).name() + " for " + Text.money(cost, plugin.settings().currency) + ".");
        plugin.requestSave();
        return null;
    }

    /** Every second: market prices drift back toward normal. */
    public void tick() {
        market.recover(1.0 / 50);
    }

    /** Once a day: the auto-sell and auto-buy policies. */
    public void daily(Colony c, long day) {
        if (tradingPost(c) == null || !c.policies.autoSell && !c.policies.autoBuy) return;
        var prod = plugin.sim().production();
        long budget = 64L * Math.max(1, c.workers(tradingPost(c)).size()) * 4;
        long earned = 0, spent = 0;
        List<String> sold = new ArrayList<>(), bought = new ArrayList<>();
        if (c.policies.autoSell) {
            for (Map.Entry<String, Long> e : new ArrayList<>(c.storage.contents().entrySet())) {
                if (budget <= 0) break;
                String k = e.getKey();
                if (Goods.get(k).custom() && !k.equals(Goods.FLOUR)) continue;
                long surplus = Math.min(plugin.sim().finances().spare(c, k), c.storage.available(k) - prod.max(c, k));
                if (surplus <= 0) continue;
                long n = Math.min(surplus, budget);
                long before = c.treasury.balance();
                if (sell(null, c, k, n, true) == null) {
                    earned += c.treasury.balance() - before;
                    budget -= n;
                    sold.add(n + " " + Goods.get(k).name());
                }
            }
        }
        if (c.policies.autoBuy) {
            long reserve = plugin.sim().finances().dailyCosts(c) * 2;
            for (Map.Entry<String, int[]> e : c.targets.entrySet()) {
                if (budget <= 0) break;
                long lack = e.getValue()[0] - prod.stock(c, e.getKey());
                if (lack <= 0) continue;
                long n = Math.min(lack, budget);
                long cost = market.quotePurchase(e.getKey(), n, merchantSkill(c));
                if (c.treasury.balance() - cost < reserve) continue;
                if (buy(null, c, e.getKey(), n, true) == null) {
                    spent += cost;
                    budget -= n;
                    bought.add(n + " " + Goods.get(e.getKey()).name());
                }
            }
        }
        String cur = plugin.settings().currency;
        if (!sold.isEmpty()) c.log(ColonyEvent.Type.ECONOMY, day, "The merchants sold " + String.join(", ", sold) + " for " + Text.money(earned, cur) + ".");
        if (!bought.isEmpty()) c.log(ColonyEvent.Type.ECONOMY, day, "The merchants bought " + String.join(", ", bought) + " for " + Text.money(spent, cur) + ".");
    }

    // ───────────── shopping at a colony's market ─────────────

    /** The colony's staffed Market (players can shop there), or null. */
    public Building shop(Colony c) {
        return plugin.sim().citizens().staffed(c, BuildingType.MARKET);
    }

    /** What a player pays a colony's market for one of a good. */
    public long shopPrice(Colony c, String good) {
        return Math.max(1, Math.round(market.mid(good) * plugin.settings().shopMarkup));
    }

    /** What a colony's market pays a player for one of a good. */
    public long shopOffer(Colony c, String good) {
        return Math.max(0, (long) Math.floor(market.mid(good) / plugin.settings().shopMarkup));
    }

    /** Goods a colony's market will sell to players: what it has beyond its own needs. */
    public long forSale(Colony c, String good) {
        return Math.max(0, Math.min(plugin.sim().finances().spare(c, good), c.storage.available(good) - plugin.sim().production().max(c, good) / 2));
    }

    public String shopBuy(Player p, Colony c, String good, int qty) {
        if (shop(c) == null) return Text.esc(c.name) + " has no Market open (it needs a shopkeeper at work).";
        if (!plugin.diplomacy().canTradeWith(p, c)) return Text.esc(c.name) + " won't trade with you.";
        qty = (int) Math.min(qty, forSale(c, good));
        if (qty <= 0) return Text.esc(c.name) + " has none of that to sell.";
        long cost = shopPrice(c, good) * qty;
        if (!charge(p, cost)) return "That costs " + Text.money(cost, plugin.settings().currency) + ". You have " + money(p) + ".";
        long got = c.storage.take(good, qty);
        for (ItemStack it : ItemGoods.stacks(good, got)) {
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        c.treasury.credit(cost, TxnType.SERVICES, p.getName() + " bought " + got + " " + Goods.get(good).name() + " at the market", System.currentTimeMillis(), plugin.sim().day(c));
        plugin.requestSave();
        return null;
    }

    /** A player sells what's in their hand to a colony's market. */
    public String shopSell(Player p, Colony c, ItemStack hand) {
        if (shop(c) == null) return Text.esc(c.name) + " has no Market open (it needs a shopkeeper at work).";
        if (!plugin.diplomacy().canTradeWith(p, c)) return Text.esc(c.name) + " won't trade with you.";
        String good = ItemGoods.goodOf(hand);
        if (good == null) return "The market only buys plain goods.";
        long each = shopOffer(c, good);
        if (each <= 0) return "The market won't pay for that.";
        int qty = (int) Math.min(hand.getAmount(), c.storage.room(good));
        if (qty <= 0) return Text.esc(c.name) + " has no room for that.";
        long pay = each * qty;
        long day = plugin.sim().day(c);
        if (c.treasury.debit(pay, TxnType.PURCHASE, "Bought " + qty + " " + Goods.get(good).name() + " from " + p.getName(), System.currentTimeMillis(), day) == null) {
            return Text.esc(c.name) + "'s treasury can't afford it.";
        }
        hand.setAmount(hand.getAmount() - qty);
        c.storage.add(good, qty);
        long paid = pay(p, pay);
        if (paid < pay) c.treasury.credit(pay - paid, TxnType.ADMIN, "Change kept from a market sale", System.currentTimeMillis(), day);
        plugin.requestSave();
        return null;
    }

    // ───────────── persistence ─────────────

    public void snapshot(Snapshot s) {
        s.market.putAll(market.all());
    }

    public void load(Snapshot s) {
        market.load(s.market);
    }
}
