package com.colonysmp.npc;

import com.colonysmp.data.Citizen;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The live body of a citizen and what it is doing right now (nothing here is saved). */
public final class Npc {

    public final Citizen c;
    public final Mob body;
    public final Mover mover;

    /** Shown in menus: "Harvesting wheat", "Sleeping"... */
    public String activity = "Idle";
    public long thinkOffset;
    public long actionAt;

    // work
    public BlockPos target;
    public List<BlockPos> work;
    public int workIdx;
    public String jobKey;
    public final List<BlockPos> veins = new ArrayList<>();
    public long noToolUntil;
    public boolean fetching;

    // routine
    public boolean sleeping;
    public Location idleSpot;
    public long idleUntil;

    // combat
    public LivingEntity enemy;
    public long lastAttack, lastShot, enemyCheckAt;
    public int waypoint = -1;
    public long waypointUntil;
    public boolean gearChecked;

    // warden visits
    public Citizen visiting;
    public int visitStage;
    public long visitUntil;
    public Location visitReturn;

    // escort
    public Citizen escorting;

    // shown item
    public ItemStack shownHand;
    public String shownName;

    public Npc(Citizen c, Mob body) {
        this.c = c;
        this.body = body;
        this.mover = new Mover(body);
        this.thinkOffset = Math.floorMod(c.id.hashCode(), 10);
    }

    public Location loc() {
        return body.getLocation();
    }

    public boolean valid() {
        return body.isValid() && !body.isDead();
    }

    public void resetWork() {
        target = null;
        work = null;
        workIdx = 0;
        jobKey = null;
        veins.clear();
    }
}
