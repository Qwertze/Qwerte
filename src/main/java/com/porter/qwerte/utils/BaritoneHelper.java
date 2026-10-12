package com.porter.qwerte.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
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
    public static void moveNear(BlockPos pos, int range) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
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
