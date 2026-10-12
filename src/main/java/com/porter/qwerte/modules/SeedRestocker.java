package com.porter.qwerte.modules;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.EChestMemory;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.IntFunction;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Restocks seeds for {@link AutoTill}, either from a storage block the player picked, or from a shulker kept in their
 * ender chest. Runs one small step per tick while the player stands still; travelling is left to AutoTill.
 */
class SeedRestocker {
    enum Result { Busy, Done, Failed }

    private enum Step {
        OpenStorage, TakeFromStorage,
        PlaceEChest, OpenEChest, TakeShulker, PlaceShulker, OpenShulker, TakeSeeds, BreakShulker, CollectShulker,
        ReopenEChest, ReturnShulker, BreakEChest, CollectEChest
    }

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int PLACE_TIMEOUT = 20 * 3;
    private static final int BREAK_TIMEOUT = 20 * 15;
    private static final int COLLECT_TIMEOUT = 20 * 5;
    private static final int CLICK_DELAY = 2;

    private final AutoTill module;

    private Step step;
    private int stepTicks;
    private int wait;
    private String failure;

    private BlockPos storage;
    private BlockPos echestSpot, shulkerSpot;
    private int shulkerSlot = -1;
    private Item shulkerItem;
    private int echestCount, shulkerCount;

    SeedRestocker(AutoTill module) {
        this.module = module;
    }

    String failure() {
        return failure;
    }

    /**
     * Restock from a container the player picked, which must already be in reach.
     */
    void startManual(BlockPos storage) {
        reset();
        this.storage = storage;
        go(Step.OpenStorage);
    }

    /**
     * Restock from a seed shulker kept in the ender chest.
     * @return a reason it can't start, or null if it started
     */
    String startEnderChest() {
        reset();

        if (!InvUtils.find(Items.ENDER_CHEST).found()) return "No ender chest in your inventory.";
        if (!InvUtils.find(this::isSilkPickaxe).found()) return "No silk touch pickaxe to pick the ender chest back up with.";
        if (emptySlots() < 2) return "Need 2 free inventory slots to restock from the ender chest.";

        // Meteor remembers what was in the ender chest last time it was opened, check that before placing anything
        if (EChestMemory.isKnown() && bestShulkerIn(EChestMemory.ITEMS::get, EChestMemory.ITEMS.size()) == -1) {
            return "No shulker with your selected seeds in your ender chest.";
        }

        echestSpot = findSpot(null);
        if (echestSpot == null) return "No room next to you to place the ender chest.";

        echestCount = InvUtils.find(Items.ENDER_CHEST).count();
        go(Step.PlaceEChest);
        return null;
    }

    Result tick() {
        stepTicks++;
        if (wait > 0) {
            wait--;
            return Result.Busy;
        }

        return switch (step) {
            case OpenStorage -> {
                if (open(storage, menu -> menu != mc.player.inventoryMenu)) go(Step.TakeFromStorage);
                yield timedOut(OPEN_TIMEOUT) ? fail("Couldn't open the seed storage.") : Result.Busy;
            }
            case TakeFromStorage -> takeSeeds(0) ? Result.Busy : finishStorage();

            case PlaceEChest -> {
                if (mc.level.getBlockState(echestSpot).is(Blocks.ENDER_CHEST)) go(Step.OpenEChest);
                else if (stepTicks == 1 || stepTicks % 20 == 0) placeOnTop(echestSpot, stack -> stack.is(Items.ENDER_CHEST));
                yield timedOut(PLACE_TIMEOUT) ? fail("Couldn't place the ender chest.") : Result.Busy;
            }
            case OpenEChest -> {
                if (open(echestSpot, this::isEChestMenu)) go(Step.TakeShulker);
                else if (timedOut(OPEN_TIMEOUT)) cleanUp("Couldn't open the ender chest.");
                yield Result.Busy;
            }
            case TakeShulker -> {
                takeShulker();
                yield Result.Busy;
            }
            case PlaceShulker -> {
                if (mc.level.getBlockState(shulkerSpot).getBlock() instanceof ShulkerBoxBlock) go(Step.OpenShulker);
                else if (stepTicks == 1 || stepTicks % 20 == 0) placeOnTop(shulkerSpot, stack -> stack.is(shulkerItem));
                else if (timedOut(PLACE_TIMEOUT)) cleanUp("Couldn't place the shulker.", Step.ReopenEChest);
                yield Result.Busy;
            }
            case OpenShulker -> {
                if (open(shulkerSpot, menu -> menu instanceof ShulkerBoxMenu)) go(Step.TakeSeeds);
                else if (timedOut(OPEN_TIMEOUT)) {
                    failure = "Couldn't open the shulker.";
                    go(Step.BreakShulker);
                }
                yield Result.Busy;
            }
            case TakeSeeds -> {
                // Keep one slot free so the shulker has somewhere to go when we break it
                if (!takeSeeds(1)) {
                    mc.player.closeContainer();
                    go(Step.BreakShulker);
                }
                yield Result.Busy;
            }
            case BreakShulker -> {
                if (mc.level.getBlockState(shulkerSpot).isAir()) go(Step.CollectShulker);
                else if (timedOut(BREAK_TIMEOUT)) cleanUp("Couldn't break the shulker, it's still placed at " + shulkerSpot.toShortString() + ".");
                else breakWith(shulkerSpot, null);
                yield Result.Busy;
            }
            case CollectShulker -> {
                if (InvUtils.find(shulkerItem).count() >= shulkerCount) go(Step.ReopenEChest);
                else if (timedOut(COLLECT_TIMEOUT)) cleanUp("Couldn't pick the shulker back up, it's on the ground at " + shulkerSpot.toShortString() + ".");
                yield Result.Busy;
            }
            case ReopenEChest -> {
                if (open(echestSpot, this::isEChestMenu)) go(Step.ReturnShulker);
                else if (timedOut(OPEN_TIMEOUT)) cleanUp("Couldn't reopen the ender chest to put the shulker back, it's in your inventory.");
                yield Result.Busy;
            }
            case ReturnShulker -> {
                returnShulker();
                yield Result.Busy;
            }
            case BreakEChest -> {
                if (mc.level.getBlockState(echestSpot).isAir()) go(Step.CollectEChest);
                else if (timedOut(BREAK_TIMEOUT)) yield fail(prefix() + "Couldn't break the ender chest, it's still placed at " + echestSpot.toShortString() + ".");
                else breakWith(echestSpot, this::isSilkPickaxe);
                yield Result.Busy;
            }
            case CollectEChest -> {
                if (InvUtils.find(Items.ENDER_CHEST).count() >= echestCount) yield failure == null ? Result.Done : Result.Failed;
                if (timedOut(COLLECT_TIMEOUT)) yield fail(prefix() + "Couldn't pick the ender chest back up, it's on the ground at " + echestSpot.toShortString() + ".");
                yield Result.Busy;
            }
        };
    }

    /**
     * Stops mid-way, closing any open container. Anything placed stays placed.
     */
    void cancel() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        step = null;
    }

    // Steps

    private Result finishStorage() {
        mc.player.closeContainer();
        return Result.Done;
    }

    private void takeShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isEChestMenu(menu)) {
            go(Step.OpenEChest);
            return;
        }

        shulkerSlot = bestShulkerIn(i -> menu.getSlot(i).getItem(), 27);
        if (shulkerSlot == -1) {
            cleanUp("No shulker with your selected seeds in your ender chest.");
            return;
        }

        shulkerSpot = findSpot(echestSpot);
        if (shulkerSpot == null) {
            cleanUp("No room next to you to place the shulker.");
            return;
        }

        shulkerItem = menu.getSlot(shulkerSlot).getItem().getItem();
        shulkerCount = InvUtils.find(shulkerItem).count() + 1;

        InvUtils.shiftClick().slotId(shulkerSlot);
        mc.player.closeContainer();
        wait = CLICK_DELAY;
        go(Step.PlaceShulker);
    }

    private void returnShulker() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isEChestMenu(menu)) {
            go(Step.ReopenEChest);
            return;
        }

        int index = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(shulkerItem)) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            // Put it back exactly where it came from, falling back to any free slot if that one got used
            if (menu.getSlot(shulkerSlot).getItem().isEmpty()) InvUtils.move().from(index).toId(shulkerSlot);
            else InvUtils.shiftClick().slot(index);
        }

        mc.player.closeContainer();
        wait = CLICK_DELAY;
        go(Step.BreakEChest);
    }

    /**
     * Shift-clicks one stack of seeds out of the open container.
     * @return false once there's nothing left to take or no room left to take it into
     */
    private boolean takeSeeds(int keepFree) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) return false;
        if (emptySlots() <= keepFree) return false;

        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            if (module.isSeed(menu.getSlot(i).getItem())) {
                InvUtils.shiftClick().slotId(i);
                wait = CLICK_DELAY;
                return true;
            }
        }

        return false;
    }

    // Helpers

    private void reset() {
        failure = null;
        storage = echestSpot = shulkerSpot = null;
        shulkerSlot = -1;
        shulkerItem = null;
        wait = 0;
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
    }

    private boolean timedOut(int ticks) {
        return stepTicks > ticks;
    }

    private Result fail(String reason) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        failure = reason;
        return Result.Failed;
    }

    /**
     * Something went wrong after the ender chest was placed: remember why, then still put everything away.
     */
    private void cleanUp(String reason) {
        cleanUp(reason, Step.BreakEChest);
    }

    private void cleanUp(String reason, Step next) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (failure == null) failure = reason;
        go(next);
    }

    private String prefix() {
        return failure != null ? failure + " " : "";
    }

    /**
     * Clicks a container open, retrying now and then.
     * @return true once the expected screen is open
     */
    private boolean open(BlockPos pos, Predicate<AbstractContainerMenu> expected) {
        if (expected.test(mc.player.containerMenu)) {
            wait = CLICK_DELAY;
            return true;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);

            if (module.rotates()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }

        return false;
    }

    /**
     * Places a block by clicking the top of the block under {@code spot}, so shulkers always face up and have room to open.
     */
    private void placeOnTop(BlockPos spot, Predicate<ItemStack> item) {
        module.moveToHotbar(item);
        FindItemResult result = InvUtils.findInHotbar(item);
        if (!result.found()) return;

        BlockPos below = spot.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);

        Runnable click = () -> {
            InvUtils.swap(result.slot(), false);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
        };

        if (module.rotates()) Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), click);
        else click.run();
    }

    private void breakWith(BlockPos pos, Predicate<ItemStack> tool) {
        if (tool != null) {
            module.moveToHotbar(tool);
            FindItemResult result = InvUtils.findInHotbar(tool);
            if (result.found()) InvUtils.swap(result.slot(), false);
        }
        else {
            FindItemResult best = InvUtils.findFastestTool(mc.level.getBlockState(pos));
            if (best.found()) InvUtils.swap(best.slot(), false);
        }

        if (module.rotates()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos));
        BlockUtils.breakBlock(pos, true);
    }

    /**
     * A free spot right beside the player, on solid ground with air above. Right beside means the drop lands within
     * pickup range when we break it again.
     */
    private BlockPos findSpot(BlockPos exclude) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos fallback = null;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos pos = feet.offset(dx, 0, dz);
                if (pos.equals(exclude) || !isGoodSpot(pos)) continue;

                // Straight sides are closest, so drops land right next to us
                if (dx == 0 || dz == 0) return pos;
                if (fallback == null) fallback = pos;
            }
        }

        return fallback;
    }

    private boolean isGoodSpot(BlockPos pos) {
        if (!mc.level.getBlockState(pos).isAir() || !mc.level.getBlockState(pos.above()).isAir()) return false;
        if (mc.player.getBoundingBox().intersects(new AABB(pos))) return false;

        // Solid to place on, and nothing that would open a screen instead when clicked
        BlockPos below = pos.below();
        BlockState ground = mc.level.getBlockState(below);
        return ground.isCollisionShapeFullBlock(mc.level, below) && ground.getFluidState().isEmpty()
            && mc.level.getBlockEntity(below) == null && !BlockUtils.isClickable(ground.getBlock());
    }

    /**
     * The slot of the shulker holding the most of the selected seeds, or -1.
     */
    private int bestShulkerIn(IntFunction<ItemStack> slots, int size) {
        int best = -1, bestSeeds = 0;
        ItemStack[] contents = new ItemStack[27];

        for (int i = 0; i < size; i++) {
            ItemStack stack = slots.apply(i);
            if (!Utils.isShulker(stack.getItem())) continue;

            Utils.getItemsInContainerItem(stack, contents);
            int seeds = 0;
            for (ItemStack content : contents) {
                if (content != null && module.isSeed(content)) seeds += content.getCount();
            }

            if (seeds > bestSeeds) {
                best = i;
                bestSeeds = seeds;
            }
        }

        return best;
    }

    private boolean isEChestMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu chest && chest.getRowCount() == 3;
    }

    private boolean isSilkPickaxe(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH);
    }

    private int emptySlots() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        }
        return count;
    }
}
