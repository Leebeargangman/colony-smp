package com.colonysmp;

import com.colonysmp.core.Needs;
import com.colonysmp.model.Job;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Typed view of config.yml. Rebuilt on /colony admin reload. */
public final class Settings {

    // general
    public final int autosaveMinutes, backups;
    public final String currency;

    // colonies and land
    public final int maxColoniesPerPlayer, startRadius, chunkGap, baseChunks, chunksPerLevel, maxChunks;
    public final long claimCost, claimCostStep;
    public final List<String> worlds;
    public final boolean blockPvp, protectExplosions, protectFire, protectFarmland, enterMessages;
    public final double shieldNewHours, shieldDefeatHours;

    // citizens
    public final List<Job> startingJobs;
    public final int maxPopulation, maxBodiesPerColony, maxBodiesTotal, workMargin, stuckTeleportSeconds, stuckForceSeconds;
    public final double citizenHealth, walkSpeed, runSpeed;
    public final double farmInterval, chopInterval, mineInterval, buildInterval, fishInterval, stationInterval;

    // life
    public final double adultAge, elderAge, maxAge, birthChance, birthReserveDays, birthMinMood, partnerAffinity;

    // needs
    public final Needs.Params needs;
    public final Set<Material> neverEat;
    public final long eveningEnd;

    // education
    public final double perLesson, childLearning, bookLearning;
    public final int educationMin, educationMax;

    // economy
    public final long startingTreasury, recruitCost;
    public final double wageMultiplier, upkeepMultiplier, marketSpread, marketRecovery, mealPrice, shopMarkup;
    public final Material depositItem;
    public final long depositValue;
    public final boolean useVault;
    public final Map<String, Double> prices;

    // storage
    public final int hallSlotsPerLevel, slotsPerContainer, equipmentBase, armourySlotsPerContainer;
    public final double granaryFactor, warehouseLevelBonus;

    // simulation
    public final int stepSeconds, stepBudgetMs;
    public final double maxCatchUpHours;
    public final boolean backgroundProduction;
    public final int invalidGraceDays;

    // health and order
    public final double illnessChance, injuryThreshold, medicineHeal, theftChance, protestBelow, riotBelow;
    public final int protestDays, emigrateDays;
    public final double emigrateBelow, emigrateChance;

    // military
    public final int freeSoldiers;
    public final double trainingPerHour, upkeepPerClass;
    public final long recruitSoldierCost;

    // prisoners
    public final double downedChance, travelerDownedChance;
    public final int downedSeconds, resistanceMin, resistanceMax, persuasionMin, persuasionMax, starveDays;
    public final long ransomBase;

    // travellers
    public final boolean travelersEnabled;
    public final double travelerChance;
    public final int travelerWaitMinutes, reputationStart, attackPenalty, capturePenalty, recruitBonus;
    public final long lodgingFee;

    // war and diplomacy
    public final int warmupMinutes, siegeMinutes, raidMinutes, campMinDistance, campMaxDistance, supplyCheckSeconds, cooldownMinutes,
            requireOnlineDefenders, restoreDelaySeconds, proposalMinutes, truceDays, shipmentSecondsPerChunk;
    public final double supplyCutStability, spoilsFraction, raidLootFraction;
    public final boolean restoreBlocks, respawnAtCamp, allowOfflineRaids;
    public final long declareCost;

    // mobilisation
    public final int mobilizeMinutes, mobilizeCooldownMinutes;
    public final double mobilizeCost;

    // blueprints and mines
    public final int blueprintMaxSize, blueprintMaxBlocks, blueprintMax;
    public final int mineTargetY, mineTunnelLength, mineBranchLength, maxMines;

    public final List<ItemStack> questReward;

    public Settings(FileConfiguration c, Logger log) {
        autosaveMinutes = Math.max(1, c.getInt("general.autosave-minutes", 5));
        backups = Math.max(0, c.getInt("general.backups-to-keep", 5));
        currency = c.getString("general.currency", "crowns");

        maxColoniesPerPlayer = Math.max(1, c.getInt("colonies.max-per-player", 1));
        startRadius = clampI(c.getInt("colonies.starting-radius", 1), 0, 4);
        chunkGap = Math.max(0, c.getInt("colonies.chunk-gap", 2));
        baseChunks = Math.max(1, c.getInt("colonies.base-chunks", 12));
        chunksPerLevel = Math.max(0, c.getInt("colonies.chunks-per-level", 10));
        maxChunks = Math.max(baseChunks, c.getInt("colonies.max-chunks", 120));
        claimCost = Math.max(0, c.getLong("colonies.claim-cost", 25));
        claimCostStep = Math.max(0, c.getLong("colonies.claim-cost-step", 5));
        worlds = c.getStringList("colonies.worlds");
        blockPvp = c.getBoolean("protection.block-pvp", true);
        protectExplosions = c.getBoolean("protection.protect-explosions", true);
        protectFire = c.getBoolean("protection.protect-fire", true);
        protectFarmland = c.getBoolean("protection.protect-farmland", true);
        enterMessages = c.getBoolean("protection.enter-messages", true);
        shieldNewHours = c.getDouble("protection.new-colony-shield-hours", 72);
        shieldDefeatHours = c.getDouble("protection.after-defeat-shield-hours", 24);

        List<Job> jobs = new ArrayList<>();
        for (String s : c.getStringList("citizens.starting-jobs")) {
            Job j = Job.parse(s);
            if (j == null) log.warning("citizens.starting-jobs: unknown job " + s);
            else jobs.add(j);
        }
        if (jobs.isEmpty()) jobs.addAll(List.of(Job.FARMER, Job.BUILDER, Job.UNEMPLOYED));
        startingJobs = List.copyOf(jobs);
        maxPopulation = Math.max(2, c.getInt("citizens.max-population", 150));
        maxBodiesPerColony = Math.max(4, c.getInt("citizens.max-bodies-per-colony", 60));
        maxBodiesTotal = Math.max(maxBodiesPerColony, c.getInt("citizens.max-bodies-total", 300));
        citizenHealth = Math.max(1, c.getDouble("citizens.health", 20));
        walkSpeed = clamp(c.getDouble("citizens.walk-speed", 0.13), 0.02, 0.6);
        runSpeed = clamp(c.getDouble("citizens.run-speed", 0.21), 0.02, 0.8);
        workMargin = Math.max(0, c.getInt("citizens.work-margin", 24));
        farmInterval = Math.max(0.1, c.getDouble("citizens.farm-interval", 1.5));
        chopInterval = Math.max(0.1, c.getDouble("citizens.chop-interval", 2.0));
        mineInterval = Math.max(0.1, c.getDouble("citizens.mine-interval", 2.5));
        buildInterval = Math.max(0.1, c.getDouble("citizens.build-interval", 1.2));
        fishInterval = Math.max(1, c.getDouble("citizens.fish-interval", 12));
        stationInterval = Math.max(0.2, c.getDouble("citizens.station-interval", 2.0));
        stuckTeleportSeconds = Math.max(3, c.getInt("citizens.stuck-teleport-seconds", 20));
        stuckForceSeconds = Math.max(stuckTeleportSeconds, c.getInt("citizens.stuck-force-seconds", 60));

        adultAge = Math.max(0, c.getDouble("life.adult-age-days", 5));
        elderAge = Math.max(adultAge + 1, c.getDouble("life.elder-age-days", 60));
        maxAge = Math.max(elderAge + 1, c.getDouble("life.max-age-days", 90));
        birthChance = clamp(c.getDouble("life.birth-chance", 0.25), 0, 1);
        birthReserveDays = Math.max(0, c.getDouble("life.birth-food-reserve-days", 3));
        birthMinMood = clamp(c.getDouble("life.birth-min-mood", 45), 0, 100);
        partnerAffinity = clamp(c.getDouble("life.partner-affinity", 60), 0, 100);

        needs = new Needs.Params(
                c.getDouble("needs.hunger-awake", 3.5), c.getDouble("needs.hunger-asleep", 1.5),
                c.getDouble("needs.eat-below", 55), c.getDouble("needs.eat-until", 92), c.getDouble("needs.satiety-per-point", 6),
                c.getDouble("needs.energy-awake", 5), c.getDouble("needs.energy-bed", 12), c.getDouble("needs.energy-no-bed", 6),
                c.getDouble("needs.tired", 30), c.getDouble("needs.exhausted", 12), c.getDouble("needs.starve-damage", 1.5),
                c.getDouble("needs.heal-fed", 0.4), c.getDouble("needs.heal-bed", 1.5), c.getDouble("needs.social-decay", 2),
                c.getDouble("needs.social-gain", 8), c.getDouble("needs.heavy-work-factor", 1.3));
        Set<Material> never = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("needs.never-eat")) {
            Material m = Material.matchMaterial(s);
            if (m != null) never.add(m);
        }
        neverEat = never;
        eveningEnd = c.getLong("needs.bedtime", 13500);

        perLesson = Math.max(0, c.getDouble("education.per-lesson", 1.5));
        childLearning = Math.max(0, c.getDouble("education.child-multiplier", 2));
        bookLearning = Math.max(1, c.getDouble("education.book-multiplier", 1.5));
        educationMin = clampI(c.getInt("education.starting-min", 10), 0, 100);
        educationMax = clampI(c.getInt("education.starting-max", 45), educationMin, 100);

        startingTreasury = Math.max(0, c.getLong("economy.starting-treasury", 150));
        recruitCost = Math.max(0, c.getLong("economy.recruit-cost", 30));
        wageMultiplier = Math.max(0, c.getDouble("economy.wage-multiplier", 1));
        upkeepMultiplier = Math.max(0, c.getDouble("economy.upkeep-multiplier", 1));
        marketSpread = clamp(c.getDouble("economy.market-spread", 0.25), 0, 0.9);
        marketRecovery = clamp(c.getDouble("economy.market-recovery-per-hour", 0.02), 0, 1);
        mealPrice = Math.max(0, c.getDouble("economy.meal-price-multiplier", 1));
        shopMarkup = Math.max(1, c.getDouble("economy.shop-markup", 1.2));
        Material dep = Material.matchMaterial(c.getString("economy.deposit-item", "EMERALD"));
        depositItem = dep == null ? Material.EMERALD : dep;
        depositValue = Math.max(1, c.getLong("economy.deposit-value", 10));
        useVault = c.getBoolean("economy.use-vault", true);
        Map<String, Double> pr = new HashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("economy.prices");
        if (ps != null) for (String k : ps.getKeys(false)) pr.put(k.toLowerCase(java.util.Locale.ROOT), ps.getDouble(k));
        prices = pr;

        hallSlotsPerLevel = Math.max(9, c.getInt("storage.town-hall-slots-per-level", 27));
        slotsPerContainer = Math.max(1, c.getInt("storage.slots-per-container", 27));
        granaryFactor = Math.max(1, c.getDouble("storage.granary-factor", 1.5));
        warehouseLevelBonus = Math.max(0, c.getDouble("storage.level-bonus", 0.25));
        equipmentBase = Math.max(9, c.getInt("storage.equipment-base-slots", 54));
        armourySlotsPerContainer = Math.max(1, c.getInt("storage.armoury-slots-per-container", 27));

        stepSeconds = clampI(c.getInt("simulation.step-seconds", 30), 5, 600);
        stepBudgetMs = clampI(c.getInt("simulation.step-budget-ms", 15), 1, 1000);
        maxCatchUpHours = Math.max(1, c.getDouble("simulation.max-catch-up-hours", 48));
        backgroundProduction = c.getBoolean("simulation.background-production", true);
        invalidGraceDays = Math.max(0, c.getInt("simulation.invalid-building-grace-days", 2));

        illnessChance = clamp(c.getDouble("health.illness-chance", 0.01), 0, 1);
        injuryThreshold = clamp(c.getDouble("health.injury-threshold", 0.4), 0, 1);
        medicineHeal = Math.max(0, c.getDouble("health.medicine-heal", 6));
        theftChance = clamp(c.getDouble("order.theft-chance", 0.04), 0, 1);
        protestBelow = clamp(c.getDouble("order.protest-below", 35), 0, 100);
        riotBelow = clamp(c.getDouble("order.riot-below", 15), 0, 100);
        protestDays = Math.max(1, c.getInt("order.protest-days", 3));
        emigrateBelow = clamp(c.getDouble("order.emigrate-below", 15), 0, 100);
        emigrateDays = Math.max(1, c.getInt("order.emigrate-days", 3));
        emigrateChance = clamp(c.getDouble("order.emigrate-chance", 0.5), 0, 1);

        freeSoldiers = Math.max(0, c.getInt("military.soldiers-without-barracks", 4));
        trainingPerHour = Math.max(0, c.getDouble("military.training-per-hour", 0.02));
        upkeepPerClass = Math.max(0, c.getDouble("military.upkeep-multiplier", 1));
        recruitSoldierCost = Math.max(0, c.getLong("military.recruit-cost", 20));

        downedChance = clamp(c.getDouble("prisoners.downed-chance", 0.3), 0, 1);
        travelerDownedChance = clamp(c.getDouble("prisoners.traveler-downed-chance", 1.0), 0, 1);
        downedSeconds = Math.max(5, c.getInt("prisoners.downed-seconds", 60));
        resistanceMin = clampI(c.getInt("prisoners.resistance-min", 45), 1, 100);
        resistanceMax = clampI(c.getInt("prisoners.resistance-max", 100), resistanceMin, 100);
        persuasionMin = Math.max(1, c.getInt("prisoners.persuasion-min", 8));
        persuasionMax = Math.max(persuasionMin, c.getInt("prisoners.persuasion-max", 18));
        starveDays = Math.max(1, c.getInt("prisoners.starve-days", 5));
        ransomBase = Math.max(0, c.getLong("prisoners.ransom-base", 40));

        travelersEnabled = c.getBoolean("travelers.enabled", true);
        travelerChance = clamp(c.getDouble("travelers.chance-per-day", 0.6), 0, 1);
        travelerWaitMinutes = Math.max(1, c.getInt("travelers.wait-minutes", 6));
        reputationStart = clampI(c.getInt("travelers.reputation-start", 50), 0, 100);
        attackPenalty = Math.max(0, c.getInt("travelers.attack-penalty", 5));
        capturePenalty = Math.max(0, c.getInt("travelers.capture-penalty", 15));
        recruitBonus = Math.max(0, c.getInt("travelers.recruit-bonus", 5));
        lodgingFee = Math.max(0, c.getLong("travelers.lodging-fee", 6));

        warmupMinutes = Math.max(0, c.getInt("war.warmup-minutes", 20));
        siegeMinutes = Math.max(1, c.getInt("war.siege-minutes", 45));
        raidMinutes = Math.max(1, c.getInt("war.raid-minutes", 20));
        campMinDistance = Math.max(0, c.getInt("war.camp-min-distance", 8));
        campMaxDistance = Math.max(campMinDistance + 1, c.getInt("war.camp-max-distance", 64));
        supplyCheckSeconds = Math.max(5, c.getInt("war.supply-check-seconds", 30));
        supplyCutStability = Math.max(0, c.getDouble("war.supply-cut-stability", 5));
        spoilsFraction = clamp(c.getDouble("war.spoils-fraction", 0.3), 0, 1);
        raidLootFraction = clamp(c.getDouble("war.raid-loot-fraction", 0.05), 0, 1);
        cooldownMinutes = Math.max(0, c.getInt("war.cooldown-minutes", 60));
        requireOnlineDefenders = Math.max(0, c.getInt("war.require-online-defenders", 1));
        allowOfflineRaids = c.getBoolean("war.allow-offline-raids", false);
        restoreBlocks = c.getBoolean("war.restore-blocks", true);
        restoreDelaySeconds = Math.max(0, c.getInt("war.restore-delay-seconds", 120));
        respawnAtCamp = c.getBoolean("war.respawn-at-camp", true);
        declareCost = Math.max(0, c.getLong("war.declare-cost", 50));
        proposalMinutes = Math.max(1, c.getInt("diplomacy.proposal-minutes", 30));
        truceDays = Math.max(0, c.getInt("diplomacy.truce-days", 3));
        shipmentSecondsPerChunk = Math.max(0, c.getInt("diplomacy.caravan-seconds-per-chunk", 2));

        mobilizeMinutes = Math.max(1, c.getInt("military.mobilize-minutes", 10));
        mobilizeCooldownMinutes = Math.max(0, c.getInt("military.mobilize-cooldown-minutes", 20));
        mobilizeCost = Math.max(0, c.getDouble("military.mobilize-stability-cost", 5));

        blueprintMaxSize = Math.max(4, c.getInt("blueprints.max-size", 48));
        blueprintMaxBlocks = Math.max(64, c.getInt("blueprints.max-blocks", 20000));
        blueprintMax = Math.max(1, c.getInt("blueprints.max-per-colony", 10));
        mineTargetY = c.getInt("mines.target-y", 12);
        mineTunnelLength = Math.max(4, c.getInt("mines.tunnel-length", 32));
        mineBranchLength = Math.max(2, c.getInt("mines.branch-length", 16));
        maxMines = Math.max(1, c.getInt("mines.max-per-colony", 3));

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
            reward.add(new ItemStack(m, Math.max(1, n)));
        }
        questReward = List.copyOf(reward);
    }

    public boolean worldAllowed(String world) {
        if (worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(world)) return true;
        return false;
    }

    /** Most chunks a colony of this settlement level may hold. */
    public int chunkLimit(int level) {
        return Math.min(maxChunks, baseChunks + chunksPerLevel * (level - 1));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
