package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Needs;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Thought bubbles: now and then a citizen near a player shows what's on their mind above their head for a few
 * seconds (hungry, tired, needs a home, wants work, happy...), so you can see how the colony is doing at a glance.
 */
final class Thoughts {

    /** About once a minute and a quarter per citizen (thinks come twice a second). */
    private static final int CHANCE = 150;
    private static final long SHOW_TICKS = 70;

    private final ColonySMP plugin;

    Thoughts(ColonySMP plugin) {
        this.plugin = plugin;
    }

    void think(Npc n, Colony col) {
        if (n.bubble != null && n.bubble.isValid()) return;
        if (n.sleeping || ThreadLocalRandom.current().nextInt(CHANCE) != 0 || !watched(n)) return;
        String t = thought(n, col);
        if (t == null) return;
        Location at = n.body.getEyeLocation().add(0, 0.7, 0);
        TextDisplay d = n.body.getWorld().spawn(at, TextDisplay.class, td -> {
            td.text(Text.mm(t));
            td.setBillboard(Display.Billboard.CENTER);
            td.setPersistent(false);
            td.setShadowed(true);
        });
        n.body.addPassenger(d);
        n.bubble = d;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.remove();
        }, SHOW_TICKS);
    }

    /** Somebody is close enough to see it. */
    private static boolean watched(Npc n) {
        Location l = n.loc();
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 24 * 24) return true;
        return false;
    }

    /** The most pressing thing on their mind, or null for nothing worth saying. */
    private String thought(Npc n, Colony col) {
        Citizen c = n.c;
        Needs.Params p = plugin.settings().needs;
        boolean adult = plugin.sim().citizens().stage(c) != LifeStage.CHILD;
        if (c.needs.satiety < 25) return "<gold>☹ Hungry";
        if (c.ill()) return "<green>✚ Feeling ill";
        if (c.injured) return "<red>✚ Hurt";
        if (c.home == 0) return "<yellow>⌂ Needs a home";
        if (c.needs.tired(p)) return "<aqua>☾ Tired";
        if (c.unpaidDays > 0) return "<red>$ Not paid";
        if (adult && c.job == Job.UNEMPLOYED) return "<gray>⚒ Wants work";
        if (n.seat != null) return "<light_purple>♪ Cheers!";
        if (c.needs.mood >= 75) return "<light_purple>♥ Happy";
        if (c.needs.mood < 35) return "<red>☹ " + Text.esc(plugin.sim().citizens().why(col, c));
        if (c.needs.social < 30 && c.trait.social() > 0) return "<yellow>☺ Lonely";
        return null;
    }
}
