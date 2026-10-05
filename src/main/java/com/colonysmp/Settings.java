package com.colonysmp;

import com.colonysmp.data.Job;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Typed view of config.yml. Rebuilt on /colony admin reload. */
public final class Settings {

    // claims
    public final int claimMin, claimMax, claimGap, maxColoniesPerPlayer;
    public final boolean blockPvp, protectExplosions, protectFire, protectFarmland, enterMessages;
    public final List<String> worlds;

    // shield
    public final double shieldNewHours, shieldDefeatHours;

    // citizens
    public final List<Job> startingJobs;
    public final int maxPopulation;
    public final double citizenHealth, walkSpeed, runSpeed;
    public final int workMargin;
    public final double farmInterval, chopInterval, mineInterval, buildInterval, fishInterval, cookInterval, smithInterval;
    public final int herdSize, stuckTeleportSeconds, stuckForceSeconds;

    // needs
    public final double restLoss, tired, emigrateBelow, emigrateChance;
    public final int emigrateNights;

    // education
    public final double perLesson, childLearning, bookLearning;
    public final int schoolCapacity, educationMin, educationMax;

    // blueprints
    public final int blueprintMaxSize, blueprintMaxBlocks, blueprintMax;

    // day
    public final int workStart, evening, rationTime, night;

    // rations
    public final double mealPoints, childFactor, slaveFactor;
    public final double stabilityFull, stabilityPartialMaxLoss, stabilityNone;
    public final double lowThreshold, strikeRecover, strikeEfficiency;
    public final int strikeDays;
    public final Set<Material> neverEat;

    // reproduction
    public final double reserveDays, reproductionMinStability;
    public final int maturityDays;

    // prisoners
    public final double downedChance, travelerDownedChance;
    public final int downedSeconds;
    public final int resistanceMin, resistanceMax, indoctrinationMin, indoctrinationMax, unvisitedGain, starveDays;
    public final double slaveGuardRadius, uprisingStability;
    public final int slaveGraceSeconds, rebelEscapeMinutes;

    // travelers
    public final boolean travelersEnabled;
    public final double travelerChance;
    public final int travelerWaitMinutes, reputationStart, attackPenalty, capturePenalty, recruitBonus;

    // war
    public final int warmupMinutes, siegeMinutes, campMinDistance, campMaxDistance;
    public final int supplyCheckSeconds, cooldownMinutes, requireOnlineDefenders, restoreDelaySeconds;
    public final double supplyCutStability, spoilsFraction;
    public final boolean restoreBlocks, respawnAtCamp;

    // mobilization
    public final int mobilizeMinutes, mobilizeCooldownMinutes;
    public final double mobilizeCost;

    // storage
    public final int storagePages;

    // quests
    public final List<ItemStack> questReward;

    // mines
    public final int mineTargetY, mineTunnelLength, mineBranchLength, maxMines;

    public final int autosaveMinutes;

    public Settings(FileConfiguration c, Logger log) {
        claimMin = Math.max(4, c.getInt("claims.min-size", 16));
        claimMax = Math.max(claimMin, c.getInt("claims.max-size", 128));
        claimGap = Math.max(0, c.getInt("claims.min-gap", 4));
        blockPvp = c.getBoolean("claims.block-pvp", true);
        protectExplosions = c.getBoolean("claims.protect-explosions", true);
        protectFire = c.getBoolean("claims.protect-fire", true);
        protectFarmland = c.getBoolean("claims.protect-farmland", true);
        maxColoniesPerPlayer = Math.max(1, c.getInt("claims.max-colonies-per-player", 1));
        enterMessages = c.getBoolean("claims.enter-messages", true);
        worlds = c.getStringList("claims.worlds");

        shieldNewHours = c.getDouble("shield.new-colony-hours", 72);
        shieldDefeatHours = c.getDouble("shield.after-defeat-hours", 24);

        List<Job> jobs = new ArrayList<>();
        for (String s : c.getStringList("citizens.starting-jobs")) {
            Job j = Job.parse(s);
            if (j == null) log.warning("citizens.starting-jobs: unknown job " + s);
            else jobs.add(j);
        }
        if (jobs.isEmpty()) {
            jobs.add(Job.FARMER);
            jobs.add(Job.BUILDER);
        }
        startingJobs = List.copyOf(jobs);
        maxPopulation = Math.max(2, c.getInt("citizens.max-population", 40));
        citizenHealth = Math.max(1, c.getDouble("citizens.health", 20));
        walkSpeed = clamp(c.getDouble("citizens.walk-speed", 0.13), 0.02, 0.6);
        runSpeed = clamp(c.getDouble("citizens.run-speed", 0.21), 0.02, 0.8);
        workMargin = Math.max(0, c.getInt("citizens.work-margin", 24));
        farmInterval = Math.max(0.1, c.getDouble("citizens.farm-interval", 1.5));
        chopInterval = Math.max(0.1, c.getDouble("citizens.chop-interval", 2.0));
        mineInterval = Math.max(0.1, c.getDouble("citizens.mine-interval", 2.5));
        buildInterval = Math.max(0.1, c.getDouble("citizens.build-interval", 1.2));
        fishInterval = Math.max(1, c.getDouble("citizens.fish-interval", 12));
        cookInterval = Math.max(0.2, c.getDouble("citizens.cook-interval", 2.5));
        smithInterval = Math.max(0.2, c.getDouble("citizens.smith-interval", 4));
        herdSize = Math.max(2, c.getInt("citizens.herd-size", 8));
        stuckTeleportSeconds = Math.max(3, c.getInt("citizens.stuck-teleport-seconds", 20));
        stuckForceSeconds = Math.max(stuckTeleportSeconds, c.getInt("citizens.stuck-force-seconds", 60));

        restLoss = Math.max(0, c.getDouble("needs.rest-loss", 5));
        tired = clamp(c.getDouble("needs.tired", 30), 0, 100);
        emigrateBelow = clamp(c.getDouble("needs.emigrate-below", 15), 0, 100);
        emigrateNights = Math.max(1, c.getInt("needs.emigrate-nights", 3));
        emigrateChance = clamp(c.getDouble("needs.emigrate-chance", 0.5), 0, 1);

        perLesson = Math.max(0, c.getDouble("education.per-lesson", 1.5));
        childLearning = Math.max(0, c.getDouble("education.child-multiplier", 2));
        bookLearning = Math.max(1, c.getDouble("education.book-multiplier", 1.5));
        schoolCapacity = Math.max(1, c.getInt("education.school-capacity", 10));
        educationMin = (int) clamp(c.getInt("education.starting-min", 10), 0, 100);
        educationMax = (int) clamp(c.getInt("education.starting-max", 50), educationMin, 100);

        blueprintMaxSize = Math.max(4, c.getInt("blueprints.max-size", 48));
        blueprintMaxBlocks = Math.max(64, c.getInt("blueprints.max-blocks", 20000));
        blueprintMax = Math.max(1, c.getInt("blueprints.max-per-colony", 10));

        workStart = c.getInt("day.work-start", 0);
        evening = c.getInt("day.evening", 11500);
        rationTime = c.getInt("day.ration-time", 12300);
        night = c.getInt("day.night", 13000);

        mealPoints = Math.max(0.5, c.getDouble("rations.meal-points", 6));
        childFactor = Math.max(0, c.getDouble("rations.child-factor", 0.5));
        slaveFactor = Math.max(0, c.getDouble("rations.slave-factor", 0.5));
        stabilityFull = c.getDouble("rations.stability-full", 4);
        stabilityPartialMaxLoss = c.getDouble("rations.stability-partial-max-loss", 6);
        stabilityNone = c.getDouble("rations.stability-none", 10);
        lowThreshold = c.getDouble("rations.low-threshold", 35);
        strikeDays = Math.max(1, c.getInt("rations.strike-days", 3));
        strikeRecover = c.getDouble("rations.strike-recover", 50);
        strikeEfficiency = clamp(c.getDouble("rations.strike-efficiency", 0.0), 0, 1);
        Set<Material> never = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("rations.never-eat")) {
            Material m = Material.matchMaterial(s);
            if (m != null) never.add(m);
        }
        neverEat = never;

        reserveDays = Math.max(0, c.getDouble("reproduction.reserve-days", 3));
        maturityDays = Math.max(0, c.getInt("reproduction.maturity-days", 3));
        reproductionMinStability = c.getDouble("reproduction.min-stability", 30);

        downedChance = clamp(c.getDouble("prisoners.downed-chance", 0.2), 0, 1);
        travelerDownedChance = clamp(c.getDouble("prisoners.traveler-downed-chance", 1.0), 0, 1);
        downedSeconds = Math.max(5, c.getInt("prisoners.downed-seconds", 60));
        resistanceMin = (int) clamp(c.getInt("prisoners.resistance-min", 45), 1, 100);
        resistanceMax = (int) clamp(c.getInt("prisoners.resistance-max", 100), resistanceMin, 100);
        indoctrinationMin = Math.max(1, c.getInt("prisoners.indoctrination-min", 12));
        indoctrinationMax = Math.max(indoctrinationMin, c.getInt("prisoners.indoctrination-max", 25));
        unvisitedGain = Math.max(0, c.getInt("prisoners.unvisited-gain", 4));
        starveDays = Math.max(1, c.getInt("prisoners.starve-days", 5));
        slaveGuardRadius = Math.max(4, c.getDouble("prisoners.slave-guard-radius", 20));
        slaveGraceSeconds = Math.max(0, c.getInt("prisoners.slave-grace-seconds", 30));
        uprisingStability = c.getDouble("prisoners.uprising-stability", 40);
        rebelEscapeMinutes = Math.max(1, c.getInt("prisoners.rebel-escape-minutes", 5));

        travelersEnabled = c.getBoolean("travelers.enabled", true);
        travelerChance = clamp(c.getDouble("travelers.chance-per-day", 0.6), 0, 1);
        travelerWaitMinutes = Math.max(1, c.getInt("travelers.wait-minutes", 6));
        reputationStart = (int) clamp(c.getInt("travelers.reputation-start", 50), 0, 100);
        attackPenalty = Math.max(0, c.getInt("travelers.attack-penalty", 5));
        capturePenalty = Math.max(0, c.getInt("travelers.capture-penalty", 15));
        recruitBonus = Math.max(0, c.getInt("travelers.recruit-bonus", 5));

        warmupMinutes = Math.max(0, c.getInt("war.warmup-minutes", 20));
        siegeMinutes = Math.max(1, c.getInt("war.siege-minutes", 45));
        campMinDistance = Math.max(0, c.getInt("war.camp-min-distance", 8));
        campMaxDistance = Math.max(campMinDistance + 1, c.getInt("war.camp-max-distance", 64));
        supplyCheckSeconds = Math.max(5, c.getInt("war.supply-check-seconds", 30));
        supplyCutStability = Math.max(0, c.getDouble("war.supply-cut-stability", 5));
        spoilsFraction = clamp(c.getDouble("war.spoils-fraction", 0.5), 0, 1);
        cooldownMinutes = Math.max(0, c.getInt("war.cooldown-minutes", 60));
        requireOnlineDefenders = Math.max(0, c.getInt("war.require-online-defenders", 1));
        restoreBlocks = c.getBoolean("war.restore-blocks", true);
        restoreDelaySeconds = Math.max(0, c.getInt("war.restore-delay-seconds", 120));
        respawnAtCamp = c.getBoolean("war.respawn-at-camp", true);

        mobilizeMinutes = Math.max(1, c.getInt("mobilization.minutes", 10));
        mobilizeCooldownMinutes = Math.max(0, c.getInt("mobilization.cooldown-minutes", 20));
        mobilizeCost = Math.max(0, c.getDouble("mobilization.stability-cost", 5));

        storagePages = (int) clamp(c.getInt("storage.pages", 3), 1, 10);

        List<ItemStack> reward = new ArrayList<>();
        for (String s : c.getStringList("quests.reward")) {
            String[] p = s.split(":");
            Material m = Material.matchMaterial(p[0].trim());
            if (m == null || !m.isItem()) {
                log.warning("quests.reward: unknown item " + s);
                continue;
            }
            int n = 1;
            if (p.length > 1) {
                try {
                    n = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException e) {
                    log.warning("quests.reward: bad amount in " + s);
                }
            }
            while (n > 0) {
                int take = Math.min(n, m.getMaxStackSize());
                reward.add(new ItemStack(m, take));
                n -= take;
            }
        }
        questReward = List.copyOf(reward);

        mineTargetY = c.getInt("mines.target-y", 12);
        mineTunnelLength = Math.max(4, c.getInt("mines.tunnel-length", 32));
        mineBranchLength = Math.max(2, c.getInt("mines.branch-length", 16));
        maxMines = Math.max(1, c.getInt("mines.max-per-colony", 3));

        autosaveMinutes = Math.max(1, c.getInt("autosave-minutes", 5));
    }

    public boolean worldAllowed(String world) {
        if (worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(world)) return true;
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String upper(String s) {
        return s.trim().toUpperCase(Locale.ROOT);
    }
}
