package com.porter.qwerte.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.ICustomGoalProcess;
import com.porter.qwerte.compat.BaritoneFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Thin wrapper around dekrom's Baritone fork (https://github.com/dekrom/baritone).
 * Only call this after checking Baritone is installed, it references Baritone classes directly.
 */
public final class BaritoneHelper {
    private static final List<Block> protectedBlocks = new ArrayList<>();

    // The user's own values, restored by unprotect()
    private static Boolean waterBucketFall;

    private BaritoneHelper() {}

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Paths to anywhere within {@code range} blocks of {@code pos}, 0 meaning exactly there.
     */
    public static Goal moveNear(BlockPos pos, int range) {
        Goal goal = new GoalNear(pos, range);
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
        return goal;
    }

    /**
     * Clears the custom goal process, but only if its current goal is {@code ours}. Never cancels anything else, so
     * the user's own Baritone processes and goals survive. Returns true if we released it.
     */
    public static boolean releaseOurs(Goal ours) {
        if (ours == null) return false;
        ICustomGoalProcess process = baritone().getCustomGoalProcess();
        if (process.getGoal() != ours) return false;
        process.onLostControl();
        return true;
    }

    /**
     * True while the custom goal process still has {@code ours} as its goal: it has not been cleared or replaced.
     */
    public static boolean goalIs(Goal ours) {
        return ours != null && baritone().getCustomGoalProcess().getGoal() == ours;
    }

    /**
     * True while Baritone is pathing or any process is active, whoever owns it.
     */
    public static boolean isActive() {
        IBaritone baritone = baritone();
        return baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().getInProgress().isPresent();
    }

    public static boolean isPathing() {
        return baritone().getPathingBehavior().isPathing();
    }

    /**
     * True if Baritone is busy with something that is not our goal {@code ours}: another process is in control, or
     * the custom goal process was given a different goal (e.g. the user's #goto).
     */
    public static boolean controlledByOther(Goal ours) {
        if (!isActive()) return false;
        IBaritone baritone = baritone();
        ICustomGoalProcess custom = baritone.getCustomGoalProcess();
        if (ours == null || custom.getGoal() != ours) return true;
        IBaritoneProcess inControl = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        return inControl != null && inControl != custom;
    }

    /**
     * True while Baritone is still calculating or walking a path for our goal.
     */
    public static boolean isMoving() {
        IBaritone baritone = baritone();
        return baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive();
    }

    /**
     * True while Baritone is still working out a path, which can take a while for far away goals.
     */
    public static boolean isCalculating() {
        return baritone().getPathingBehavior().getInProgress().isPresent();
    }

    public static void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }

    /**
     * Stops Baritone from breaking these blocks, placing blocks in water (which deletes water sources) or using our
     * water buckets to break falls. Applied on top of whatever the user already has set.
     */
    public static void protect(Collection<Block> blocks) {
        Settings settings = BaritoneAPI.getSettings();

        // Copy rather than mutate, the list Baritone holds may be immutable
        List<Block> disallowed = new ArrayList<>(settings.blocksToDisallowBreaking.value);
        for (Block block : blocks) {
            if (disallowed.contains(block)) continue;
            disallowed.add(block);
            protectedBlocks.add(block);
        }
        settings.blocksToDisallowBreaking.value = disallowed;

        if (waterBucketFall == null) {
            waterBucketFall = settings.allowWaterBucketFall.value;
            settings.allowWaterBucketFall.value = false;
        }
        BaritoneFluids.protect(settings);
    }

    /**
     * Undoes {@link #protect(Collection)}, leaving the user's own entries alone.
     */
    public static void unprotect() {
        Settings settings = BaritoneAPI.getSettings();

        if (!protectedBlocks.isEmpty()) {
            List<Block> disallowed = new ArrayList<>(settings.blocksToDisallowBreaking.value);
            disallowed.removeAll(protectedBlocks);
            protectedBlocks.clear();
            settings.blocksToDisallowBreaking.value = disallowed;
        }

        if (waterBucketFall != null) {
            settings.allowWaterBucketFall.value = waterBucketFall;
            waterBucketFall = null;
        }
        BaritoneFluids.restore(settings);
    }
}
