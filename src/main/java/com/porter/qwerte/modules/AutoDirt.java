package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.modules.AutoTill.RestockMode;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Covers the ground around you with dirt: places a dirt block on top of every exposed block that isn't already dirt,
 * so the area can be tilled.
 */
public class AutoDirt extends Module implements Restocker.Owner {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRestock = settings.createGroup("Restock");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Maximum distance from your eyes to the center of the spot to place dirt in.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between placing batches.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many dirt blocks to place per batch.")
        .defaultValue(6)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Item> dirtItem = sgGeneral.add(new ItemSetting.Builder()
        .name("dirt-item")
        .description("The block to cover the ground with.")
        .defaultValue(Items.DIRT)
        .filter(item -> item == Items.DIRT || item == Items.COARSE_DIRT || item == Items.ROOTED_DIRT || item == Items.GRASS_BLOCK)
        .build()
    );

    private final Setting<Boolean> belowFeet = sgGeneral.add(new BoolSetting.Builder()
        .name("below-feet-only")
        .description("Only covers blocks below your feet, so walls and ledges around you are left alone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> depth = sgGeneral.add(new IntSetting.Builder()
        .name("depth")
        .description("How many blocks below your feet to look for ground to cover.")
        .defaultValue(1)
        .range(1, 6)
        .sliderRange(1, 6)
        .visible(belowFeet::get)
        .build()
    );

    private final Setting<Boolean> skipSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-own-column")
        .description("Doesn't cover the block you're standing on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> ignore = sgGeneral.add(new BlockListSetting.Builder()
        .name("ignore")
        .description("Blocks that are never covered.")
        .defaultValue(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.FURNACE)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the block you're placing against.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches back to your previous slot after placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableWhenOut = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-out")
        .description("Turns the module off when you run out of dirt and can't restock.")
        .defaultValue(true)
        .build()
    );

    // Restock

    private final Setting<Boolean> restock = sgRestock.add(new BoolSetting.Builder()
        .name("restock")
        .description("Restocks dirt when you run low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RestockMode> restockMode = sgRestock.add(new EnumSetting.Builder<RestockMode>()
        .name("restock-mode")
        .description("Storage: take dirt from a container you pick. Ender Chest: place your ender chest, take dirt from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> restockUnavailable = false)
        .visible(restock::get)
        .build()
    );

    private final Setting<Integer> restockAt = sgRestock.add(new IntSetting.Builder()
        .name("restock-at")
        .description("Restock when you're down to this much dirt or less.")
        .defaultValue(0)
        .range(0, 2304)
        .sliderRange(0, 64)
        .visible(restock::get)
        .build()
    );

    private final Setting<BlockPos> dirtStorage = sgRestock.add(new BlockPosSetting.Builder()
        .name("dirt-storage")
        .description("Coordinates of the container to take dirt from. Walk-to-storage is needed if it's out of reach.")
        .defaultValue(BlockPos.ZERO)
        .onChanged(pos -> restockUnavailable = false)
        .visible(this::usingStorage)
        .build()
    );

    private final Setting<Boolean> pickStorage = sgRestock.add(new BoolSetting.Builder()
        .name("pick-storage")
        .description("Turn on, then right-click a container to set it as the dirt storage. Works while the module is off.")
        .defaultValue(false)
        .visible(this::usingStorage)
        .build()
    );

    private final Setting<Boolean> walkToStorage = sgRestock.add(new BoolSetting.Builder()
        .name("walk-to-storage")
        .description("Uses Baritone to walk to the dirt storage when it's out of reach, then walks back.")
        .defaultValue(false)
        .visible(this::usingStorage)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the spots dirt will be placed in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the highlight.")
        .defaultValue(new SettingColor(110, 75, 40, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the highlight.")
        .defaultValue(new SettingColor(110, 75, 40, 255))
        .visible(render::get)
        .build()
    );

    private static final int MAX_ATTEMPTS = 5;
    private static final int RESTOCK_RETRY_TICKS = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;
    private static final int PATH_TIMEOUT_TICKS = 20 * 60 * 5;

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private enum State { Working, GoingToStorage, Restocking, Returning }

    private final List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private int timer;
    private boolean warnedInventory;

    private final Restocker restocker = new Restocker(this, this::isDirtItem, "dirt");
    private State state = State.Working;
    private boolean restockUnavailable;
    private int restockRetryTicks;
    private boolean usedBaritone;
    private BlockPos returnPos;
    private int slotBefore = -1;
    private int stateTicks;
    private int pathRetries;

    public AutoDirt() {
        super(QwerteAddon.CATEGORY, "auto-dirt", "Covers the ground around you with dirt, never stacking dirt on dirt.");

        // Always listening so the storage can be picked while the module is off
        RightClick.listen(this::onRightClick);
    }

    /**
     * Picks the dirt storage while pick-storage is on.
     * @return true when picking, so the click doesn't open the container
     */
    private boolean onRightClick() {
        if (!pickStorage.get()) return false;

        // The mouse is only grabbed in game, never while a screen is open
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(64));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

        if (hit.getType() != HitResult.Type.BLOCK || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
            warning("That isn't a container, try again.");
            return true;
        }

        dirtStorage.set(hit.getBlockPos().immutable());
        pickStorage.set(false);
        restockUnavailable = false;
        info("Dirt storage set to %s.", dirtStorage.get().toShortString());
        return true;
    }

    @Override
    public void onActivate() {
        targets.clear();
        attempts.clear();
        failed.clear();
        timer = 0;
        warnedInventory = false;

        state = State.Working;
        restockUnavailable = false;
        restockRetryTicks = 0;
        returnPos = null;
        slotBefore = -1;
    }

    @Override
    public void onDeactivate() {
        if (state == State.Restocking) restocker.cancel();

        // Only touch Baritone if we actually drove it this session
        if (usedBaritone) {
            if (state != State.Working) BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        finishRestock();
        targets.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        targets.clear();

        // A failed restock only pauses restocking, try again after a while
        if (restockUnavailable && restockRetryTicks > 0 && --restockRetryTicks == 0) restockUnavailable = false;

        // Restocking, and walking to and from the storage, take over the whole tick
        if (state != State.Working) {
            tickRestock();
            return;
        }

        // Low on dirt comes first, then we cover the ground with a full inventory
        if (canRestock() && InvUtils.find(this::isDirtItem).count() <= restockAt.get()) {
            startRestock();
            return;
        }

        // Places that keep failing are forgotten once we're out of reach of them, so they get another go later
        double forgetSq = (reach.get() + 2) * (reach.get() + 2);
        Vec3 eyes = mc.player.getEyePosition();
        failed.removeIf(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)) > forgetSq);
        attempts.keySet().removeIf(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)) > forgetSq);

        findTargets(eyes);
        if (targets.isEmpty()) return;

        FindItemResult dirt = InvUtils.findInHotbar(this::isDirtItem);
        if (!dirt.found()) {
            FindItemResult anywhere = InvUtils.find(this::isDirtItem);
            if (anywhere.found()) {
                // Pull a stack into a free hotbar slot, or the selected one if the hotbar is full
                InvUtils.move().from(anywhere.slot()).toHotbar(freeHotbarSlot());
                return;
            }

            // Restocking is tried first, above, so here we've truly got none left
            if (disableWhenOut.get()) {
                error("Out of %s, disabling.", dirtItem.get().getName().getString());
                toggle();
            }
            else if (!warnedInventory) {
                warning("Out of %s.", dirtItem.get().getName().getString());
                warnedInventory = true;
            }
            return;
        }
        warnedInventory = false;

        if (timer < delay.get()) {
            timer++;
            return;
        }

        int count = Math.min(blocksPerTick.get(), targets.size());
        for (int i = 0; i < count; i++) {
            BlockPos pos = targets.get(i);

            if (attempts.merge(pos, 1, Integer::sum) > MAX_ATTEMPTS) {
                failed.add(pos);
                continue;
            }

            // Meteor's placement: picks a face to click, rotates to it, swaps to the dirt and swings
            BlockUtils.place(pos, dirt, rotate.get(), 50, true, true, swapBack.get());
        }

        timer = 0;
    }

    private void findTargets(Vec3 eyes) {
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos feet = mc.player.blockPosition();

        int minY = belowFeet.get() ? feet.getY() - depth.get() : feet.getY() - r;
        int maxY = belowFeet.get() ? feet.getY() : feet.getY() + r;

        // pos is the empty spot the dirt goes in, the block it covers is the one below it
        for (BlockPos p : BlockPos.betweenClosed(feet.getX() - r, minY, feet.getZ() - r, feet.getX() + r, maxY, feet.getZ() + r)) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(p)) > reachSq) continue;

            BlockPos pos = p.immutable();
            if (failed.contains(pos)) continue;
            if (skipSelf.get() && pos.getX() == feet.getX() && pos.getZ() == feet.getZ()) continue;
            if (!isTarget(pos)) continue;

            targets.add(pos);
        }

        targets.sort(Comparator.comparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos))));
    }

    /**
     * An empty spot sitting on a solid block that isn't dirt.
     */
    private boolean isTarget(BlockPos pos) {
        BlockState space = mc.level.getBlockState(pos);
        if (!space.canBeReplaced() || !space.getFluidState().isEmpty()) return false;

        BlockPos belowPos = pos.below();
        BlockState below = mc.level.getBlockState(belowPos);
        if (isDirt(below)) return false;
        if (ignore.get().contains(below.getBlock())) return false;
        if (!below.isFaceSturdy(mc.level, belowPos, Direction.UP)) return false;

        // Also checks for entities, including us, in the way
        return BlockUtils.canPlace(pos, true);
    }

    private static boolean isDirt(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    private boolean isDirtItem(ItemStack stack) {
        return stack.is(dirtItem.get());
    }

    // Restocking

    private boolean usingStorage() {
        return restock.get() && restockMode.get() == RestockMode.Storage;
    }

    private boolean canRestock() {
        return restock.get() && !restockUnavailable;
    }

    private void startRestock() {
        if (restockMode.get() == RestockMode.EnderChest) {
            String problem = restocker.startEnderChest();
            if (problem != null) {
                restockFailed(problem);
                return;
            }

            startRestockingHere();
            return;
        }

        BlockPos storage = dirtStorage.get();
        if (storage.equals(BlockPos.ZERO)) {
            blockRestock("No dirt storage set, use pick-storage or enter its coordinates.");
            return;
        }

        if (inReach(storage)) {
            restocker.startManual(storage);
            startRestockingHere();
            return;
        }

        if (!walkToStorage.get()) {
            blockRestock("The dirt storage at %s is out of reach, turn on walk-to-storage to walk there.", storage.toShortString());
            return;
        }

        if (!BARITONE) {
            blockRestock("Baritone isn't installed, so I can't walk to the dirt storage at %s.", storage.toShortString());
            return;
        }

        returnPos = mc.player.blockPosition();
        slotBefore = Players.selectedSlot();
        walkTo(storage, 2, State.GoingToStorage);
    }

    private void startRestockingHere() {
        // Restocking swaps items around, so put back what we were holding afterwards
        if (slotBefore < 0) slotBefore = Players.selectedSlot();
        state = State.Restocking;
        stateTicks = 0;
    }

    private void tickRestock() {
        stateTicks++;

        switch (state) {
            case Restocking -> {
                switch (restocker.tick()) {
                    case Busy -> {}
                    case Done -> {
                        int count = InvUtils.find(this::isDirtItem).count();

                        if (restocker.sourceEmpty()) {
                            warning("Restocked to %d dirt, but that emptied your %s. Turning off restock.", count, sourceName());
                            restock.set(false);
                        }
                        // Restocking goes first, so a storage that's run dry would otherwise send us straight back
                        else if (count <= restockAt.get()) blockRestock("Restocked, but you only have %d dirt. Is the storage running low?", count);
                        else info("Restocked dirt, you now have %d.", count);

                        goBack();
                    }
                    case Failed -> {
                        restockFailed(restocker.failure());
                        goBack();
                    }
                }
            }
            case GoingToStorage -> {
                if (stillWalking()) return;

                if (inReach(dirtStorage.get())) {
                    restocker.startManual(dirtStorage.get());
                    state = State.Restocking;
                    stateTicks = 0;
                }
                else {
                    blockRestock("Couldn't reach the dirt storage at %s.", dirtStorage.get().toShortString());
                    goBack();
                }
            }
            case Returning -> {
                if (stillWalking()) return;
                finishRestock();
            }
            default -> finishRestock();
        }
    }

    /**
     * A storage seen to be empty turns restocking off, anything else only pauses it for a while.
     */
    private void restockFailed(String reason) {
        if (restocker.sourceEmpty()) {
            error("%s Turning off restock.", reason);
            restock.set(false);
        }
        else blockRestock(reason);
    }

    /**
     * Pauses restocking for a while after it fails, it's tried again after {@link #RESTOCK_RETRY_TICKS}.
     */
    private void blockRestock(String message, Object... args) {
        warning(message, args);
        restockUnavailable = true;
        restockRetryTicks = RESTOCK_RETRY_TICKS;
    }

    private String sourceName() {
        return restockMode.get() == RestockMode.EnderChest ? "ender chest's dirt shulkers" : "dirt storage";
    }

    private void goBack() {
        if (returnPos != null && BARITONE) walkTo(returnPos, 1, State.Returning);
        else finishRestock();
        returnPos = null;
    }

    /**
     * Back to placing, holding whatever we held before restocking.
     */
    private void finishRestock() {
        if (state != State.Working && slotBefore >= 0 && mc.player != null
            && Players.selectedSlot() != slotBefore) {
            InvUtils.swap(slotBefore, false);
        }

        slotBefore = -1;
        state = State.Working;
    }

    private void walkTo(BlockPos pos, int range, State next) {
        // Keep Baritone off farmland and out of water on the way
        BaritoneHelper.protect(List.of(Blocks.FARMLAND));
        usedBaritone = true;

        BaritoneHelper.moveNear(pos, range);
        state = next;
        stateTicks = 0;
        pathRetries = 0;
    }

    /**
     * @return true while Baritone is still on its way, giving it a few nudges if it stops early
     */
    private boolean stillWalking() {
        if (stateTicks > PATH_TIMEOUT_TICKS) {
            BaritoneHelper.stop();
            return false;
        }

        // Baritone needs a moment to start pathing after a goal is set
        if (stateTicks < PATH_GRACE_TICKS || BaritoneHelper.isMoving()) return true;

        BlockPos target = state == State.GoingToStorage ? dirtStorage.get() : null;
        boolean arrived = target == null || inReach(target);
        if (!arrived && pathRetries++ < 3) {
            BaritoneHelper.moveNear(target, 2);
            stateTicks = 0;
            return true;
        }

        return false;
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    @Override
    public boolean rotates() {
        return rotate.get();
    }

    @Override
    public void moveToHotbar(Predicate<ItemStack> predicate) {
        if (InvUtils.findInHotbar(predicate).found()) return;

        FindItemResult result = InvUtils.find(predicate);
        if (!result.found() || result.isHotbar()) return;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                InvUtils.move().from(result.slot()).toHotbar(i);
                return;
            }
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, AutoDirt.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private int freeHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return Players.selectedSlot();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (BlockPos pos : targets) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
