package com.colonysmp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** MiniMessage helpers. Every player-facing string goes through here. */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<dark_red>[<red>☭</red>]</dark_red> <gray>";

    private Text() {}

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** For item names and lore: no default italics. */
    public static Component item(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    public static void send(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(PREFIX + s));
    }

    public static void raw(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(s));
    }

    public static void bar(Player p, String s) {
        p.sendActionBar(mm(s));
    }

    public static void title(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(mm(title), mm(sub), Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    /** Escapes user-provided text (colony names, player names) for MiniMessage. */
    public static String esc(String s) {
        return s == null ? "" : MM.escapeTags(s);
    }

    public static String duration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    public static String bar(double fraction, int width, String on, String off) {
        int n = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return on + "|".repeat(n) + off + "|".repeat(width - n);
    }

    public static String pct(double v) {
        return Math.round(v) + "%";
    }

    public static String stabilityColor(double s) {
        if (s >= 70) return "<green>";
        if (s >= 50) return "<yellow>";
        if (s >= 35) return "<gold>";
        return "<red>";
    }

    public static String nice(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }
}
