package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts the chests and barrels around you, either into tiers (shulkers first, then stackables, then singles) or into
 * whichever container's signs best describe each item. Items are carried in the inventory slots that were empty when
 * the module was turned on, so the player's own items are never touched.
 */
public class StashSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    public enum Mode {
        Tiers("Tiers"),
        Signs("Signs");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // General

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Tiers: filled shulkers, empty shulkers, 64-stackables, 16-stackables, then singles, filling the nearest containers first. Signs: each item goes to the container whose signs describe it best.")
        .defaultValue(Mode.Tiers)
        .build()
    );

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How far around you to look for containers to sort.")
        .defaultValue(8)
        .range(2, 32)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("How far above and below you to look for containers.")
        .defaultValue(3)
        .range(0, 16)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Sorts barrels as well as chests.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How close a container has to be to open it.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between inventory clicks. Raise it if the server rejects clicks.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> walk = sgGeneral.add(new BoolSetting.Builder()
        .name("walk")
        .description("Uses Baritone to walk to containers that are out of reach.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces containers when opening them.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the container being worked on.")
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
        .defaultValue(new SettingColor(255, 170, 0, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the highlight.")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .visible(render::get)
        .build()
    );

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;

    private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    /**
     * A chest, double chest or barrel. Double chests are one box with both halves as parts.
     */
    private static class Box {
        final BlockPos pos;
        final List<BlockPos> parts;
        final List<String> labels = new ArrayList<>();
        ItemStack[] contents;
        boolean unreachable;
        int order;

        Box(BlockPos pos, List<BlockPos> parts) {
            this.pos = pos;
            this.parts = parts;
        }

        boolean known() {
            return contents != null;
        }

        int free() {
            int count = 0;
            for (ItemStack stack : contents) if (stack.isEmpty()) count++;
            return count;
        }
    }

    private enum Job { Survey, Take, Deposit, Dump }

    private enum Step { Walking, Opening, Working }

    private final List<Box> boxes = new ArrayList<>();
    private final Set<Integer> carrySlots = new HashSet<>();

    // Tiers mode: which boxes each tier fills
    private final Map<Integer, List<Box>> tierBoxes = new HashMap<>();

    private Job job;
    private Box target;
    private Step step;
    private int stepTicks;
    private int wait;
    private int swapStep;
    private int swapCarry, swapSlot;
    private boolean usedBaritone;
    private int moved;

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private int actions;

    public StashSorter() {
        super(QwerteAddon.STORAGE, "stash-sorter", "Sorts nearby chests and barrels into tiers, or by the signs on them.");
    }

    @Override
    public void onActivate() {
        boxes.clear();
        carrySlots.clear();
        tierBoxes.clear();
        job = null;
        target = null;
        moved = 0;
        wait = 0;
        stalled.clear();

        // The slots that are empty now are ours to carry items in, everything else is the player's
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) carrySlots.add(i);
        }
        if (carrySlots.isEmpty()) {
            error("Empty at least one inventory slot to carry items in.");
            toggle();
            return;
        }

        findBoxes();
        if (boxes.size() < 2) {
            error("Found %d container%s within %d blocks, need at least 2 to sort.", boxes.size(), boxes.size() == 1 ? "" : "s", radius.get());
            toggle();
            return;
        }

        if (mode.get() == Mode.Signs && boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            error("None of the containers have signs on them. Put signs on them, or use Tiers mode.");
            toggle();
            return;
        }

        info("Sorting %d containers by %s, carrying items in %d free slots.", boxes.size(), mode.get() == Mode.Tiers ? "tier" : "sign", carrySlots.size());
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();

        if (usedBaritone) {
            BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        if (mc.player != null && carriedSlots().size() > 0) {
            warning("Stopped while carrying %d stacks from the stash, they're in your inventory.", carriedSlots().size());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null && !nextJob()) return;
        stepTicks++;

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
    }

    // Choosing what to do next

    /**
     * Picks the next job. Read every container first, then put away what we're carrying, then pick up what's in the
     * wrong place.
     * @return false when there's nothing left to do
     */
    private boolean nextJob() {
        Box unread = nearest(boxes.stream().filter(b -> !b.known() && !b.unreachable).toList());
        if (unread != null) return start(Job.Survey, unread);

        plan();

        List<Integer> carried = carriedSlots();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                    if (!stalled.contains(box) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                }
            }

            Box place = nearest(places);
            if (place != null) return start(Job.Deposit, place);

            // Nowhere it belongs has room: put it down anywhere with space rather than walk around holding it
            Box spare = nearest(boxes.stream().filter(b -> b.known() && !b.unreachable && b.free() > 0 && !stalled.contains(b)).toList());
            if (spare != null) return start(Job.Dump, spare);

            warning("Found nowhere with room for what I'm carrying, stopping. You have %d stacks from the stash in your inventory.", carried.size());
            toggle();
            return false;
        }

        Box source = nearest(boxes.stream().filter(b -> b.known() && !b.unreachable && !stalled.contains(b) && hasTakeable(b)).toList());
        if (source != null) return start(Job.Take, source);

        long unreachable = boxes.stream().filter(b -> b.unreachable).count();
        if (unreachable > 0) info("Done, moved %d stacks. Couldn't open %d containers, they were left alone.", moved, unreachable);
        else info("Done, moved %d stacks.", moved);
        toggle();
        return false;
    }

    private boolean start(Job next, Box box) {
        job = next;
        target = box;
        swapStep = 0;
        actions = 0;

        if (inReach(box)) {
            step = Step.Opening;
        }
        else if (walk.get() && BARITONE) {
            BaritoneHelper.protect(List.of());
            usedBaritone = true;
            BaritoneHelper.moveNear(box.pos, 2);
            step = Step.Walking;
        }
        else {
            box.unreachable = true;
            job = null;
            return false;
        }

        stepTicks = 0;
        return true;
    }

    private void finishJob() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();

        // Opened it and got nothing done: don't keep coming back while nothing else changes
        if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (actions == 0) stalled.add(target);
            else stalled.clear();
        }

        job = null;
        target = null;
        wait = delay.get();
    }

    // Steps

    private void tickWalking() {
        if (inReach(target)) {
            BaritoneHelper.stop();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (stopped || stepTicks > WALK_TIMEOUT) {
            BaritoneHelper.stop();
            target.unreachable = true;
            finishJob();
        }
    }

    private void tickOpening() {
        if (mc.player.containerMenu instanceof ChestMenu) {
            step = Step.Working;
            stepTicks = 0;
            read(target);
            wait = delay.get();
            return;
        }

        if (stepTicks > OPEN_TIMEOUT) {
            target.unreachable = true;
            finishJob();
            return;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            BlockPos pos = nearestPart(target);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    private void tickWorking() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu)) {
            // Closed on us, by the server or the player
            finishJob();
            return;
        }

        read(target);

        boolean busy = switch (job) {
            case Survey -> false;
            case Take -> takeOne(menu);
            case Deposit -> depositOne(menu);
            case Dump -> dumpOne(menu);
        };

        if (busy) {
            actions++;
            wait = delay.get();
        }
        else finishJob();
    }

    // Moving items

    /**
     * Takes one stack that belongs somewhere else, if there's somewhere for it to go.
     * @return false when there's nothing more to take
     */
    private boolean takeOne(AbstractContainerMenu menu) {
        refreshCarrySlots();
        if (freeCarrySlots() == 0) return false;

        int slot = takeableSlot(target);
        if (slot == -1) return false;

        InvUtils.shiftClick().slotId(slot);
        return true;
    }

    /**
     * A slot in this box holding something that belongs elsewhere, where that somewhere still has room once what
     * we're already carrying is put away. -1 if there's none.
     */
    private int takeableSlot(Box source) {
        // What each destination can still take, so we don't pick up more than we can put away
        Map<Box, Integer> room = new HashMap<>();
        for (Box box : boxes) {
            if (box.known() && !box.unreachable && box != source) room.put(box, box.free() + misplacedCount(box));
        }
        for (int carried : carriedSlots()) {
            for (Box box : destinationsFor(mc.player.getInventory().getItem(carried))) {
                if (room.containsKey(box)) {
                    room.merge(box, -1, Integer::sum);
                    break;
                }
            }
        }

        for (int i = 0; i < source.contents.length; i++) {
            ItemStack stack = source.contents[i];
            if (stack.isEmpty() || !misplaced(stack, source) || wouldMergeIntoPlayer(stack)) continue;
            if (destinationsFor(stack).stream().anyMatch(b -> room.getOrDefault(b, 0) > 0)) return i;
        }

        return -1;
    }

    private boolean hasTakeable(Box box) {
        return takeableSlot(box) != -1;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne(AbstractContainerMenu menu) {
        int size = target.contents.length;

        // Mid-swap: carried stack on the cursor, then into the slot, then what was there back into the carry slot
        if (swapStep > 0) {
            if (swapStep == 1) InvUtils.click().slotId(swapSlot);
            else InvUtils.click().slotId(inventoryToMenu(swapCarry, size));

            swapStep = swapStep == 2 ? 0 : swapStep + 1;
            if (swapStep == 0) moved++;
            return true;
        }

        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!destinationsFor(stack).contains(target)) continue;

            if (canFit(stack)) {
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, size));
                moved++;
                return true;
            }

            for (int i = 0; i < size; i++) {
                ItemStack here = target.contents[i];
                if (here.isEmpty() || !misplaced(here, target) || ItemStack.isSameItemSameComponents(here, stack)) continue;

                swapCarry = slot;
                swapSlot = i;
                swapStep = 1;
                InvUtils.click().slotId(inventoryToMenu(slot, size));
                return true;
            }
        }

        return false;
    }

    /**
     * Puts carried stacks down in a container with space, when nowhere they belong has room for them.
     */
    private boolean dumpOne(AbstractContainerMenu menu) {
        for (int slot : carriedSlots()) {
            if (!canFit(mc.player.getInventory().getItem(slot))) continue;

            InvUtils.shiftClick().slotId(inventoryToMenu(slot, target.contents.length));
            return true;
        }
        return false;
    }

    private boolean canFit(ItemStack stack) {
        for (ItemStack here : target.contents) {
            if (here.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Shift-clicking this out of a container would top up one of the player's own stacks instead of landing in a
     * carry slot, mixing stash items into the player's things.
     */
    private boolean wouldMergeIntoPlayer(ItemStack stack) {
        for (int i = 0; i < 36; i++) {
            if (carrySlots.contains(i)) continue;
            ItemStack own = mc.player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(own, stack) && own.getCount() < own.getMaxStackSize()) return true;
        }
        return false;
    }

    // Planning

    /**
     * Works out where everything belongs. Signs mode needs no planning, each item is matched on its own.
     */
    private void plan() {
        tierBoxes.clear();
        if (mode.get() != Mode.Tiers) return;

        List<Box> ordered = boxes.stream().filter(b -> b.known() && !b.unreachable).sorted(Comparator.comparingInt(b -> b.order)).toList();

        // How many stacks of each tier there are, carried ones included
        int[] counts = new int[5];
        for (Box box : ordered) {
            for (ItemStack stack : box.contents) if (!stack.isEmpty()) counts[tier(stack)]++;
        }
        for (int slot : carriedSlots()) counts[tier(mc.player.getInventory().getItem(slot))]++;

        // Fill the containers in order, a tier at a time. A container can end up shared by two tiers.
        int index = 0, used = 0;
        for (int t = 0; t < counts.length; t++) {
            List<Box> list = new ArrayList<>();
            int need = counts[t];

            while (need > 0 && index < ordered.size()) {
                Box box = ordered.get(index);
                if (!list.contains(box)) list.add(box);

                int take = Math.min(need, box.contents.length - used);
                need -= take;
                used += take;

                if (used >= box.contents.length) {
                    index++;
                    used = 0;
                }
            }

            // More of this tier than room: the overflow can sit in the last container
            if (list.isEmpty() && !ordered.isEmpty()) list.add(ordered.get(Math.min(index, ordered.size() - 1)));
            tierBoxes.put(t, list);
        }
    }

    /**
     * Where a stack should go: the boxes it belongs in, all equally good. Empty if it doesn't belong anywhere in
     * particular, in which case it's left where it is.
     */
    private List<Box> destinationsFor(ItemStack stack) {
        if (stack.isEmpty()) return List.of();

        if (mode.get() == Mode.Tiers) return tierBoxes.getOrDefault(tier(stack), List.of());

        int best = 0;
        List<Box> result = new ArrayList<>();
        for (Box box : boxes) {
            if (!box.known() || box.unreachable) continue;

            int score = labelScore(box, stack);
            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        return result;
    }

    private boolean misplaced(ItemStack stack, Box box) {
        List<Box> places = destinationsFor(stack);
        return !places.isEmpty() && !places.contains(box);
    }

    private int misplacedCount(Box box) {
        int count = 0;
        for (ItemStack stack : box.contents) if (!stack.isEmpty() && misplaced(stack, box)) count++;
        return count;
    }

    // Tiers

    /**
     * 0 filled shulkers, 1 empty shulkers, 2 stacks to 64, 3 stacks to 16, 4 everything else.
     */
    private static int tier(ItemStack stack) {
        if (Utils.isShulker(stack.getItem())) return hasContents(stack) ? 0 : 1;
        if (stack.getMaxStackSize() == 64) return 2;
        if (stack.getMaxStackSize() == 16) return 3;
        return 4;
    }

    private static boolean hasContents(ItemStack shulker) {
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(shulker, contents);
        for (ItemStack stack : contents) if (stack != null && !stack.isEmpty()) return true;
        return false;
    }

    // Signs

    private static final Set<String> CATCH_ALL = Set.of("misc", "other", "others", "*", "overflow", "everything", "junk", "random");

    /**
     * How well a container's signs describe an item: an exact name beats matching words, which beat a category,
     * which beats a catch-all. 0 means the signs don't describe it at all.
     */
    private static int labelScore(Box box, ItemStack stack) {
        int best = 0;
        for (String label : box.labels) best = Math.max(best, lineScore(label, stack));
        return best;
    }

    private static int lineScore(String line, ItemStack stack) {
        String name = normalize(stack.getHoverName().getString());
        String id = normalize(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        String label = normalize(line);

        if (label.equals(name) || label.equals(id)) return 100;

        if (category(label, stack)) return 30;
        if (CATCH_ALL.contains(label)) return 1;

        Set<String> words = new HashSet<>();
        for (String word : (name + " " + id).split(" ")) if (!word.isEmpty()) words.add(singular(word));

        boolean all = true;
        for (String word : label.split(" ")) {
            if (!word.isEmpty() && !words.contains(singular(word))) {
                all = false;
                break;
            }
        }
        return all && !label.isEmpty() ? 50 : 0;
    }

    private static boolean category(String label, ItemStack stack) {
        return switch (label) {
            case "shulker", "shulkers", "shulker box", "shulker boxes" -> Utils.isShulker(stack.getItem());
            case "food", "foods" -> stack.has(DataComponents.FOOD);
            case "tool", "tools" -> stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
            case "weapon", "weapons" -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
            case "armor", "armour" -> stack.has(DataComponents.EQUIPPABLE);
            case "block", "blocks" -> stack.getItem() instanceof BlockItem;
            case "log", "logs", "wood" -> stack.is(ItemTags.LOGS);
            case "stackable", "stackables", "x64", "64" -> stack.getMaxStackSize() == 64;
            case "x16", "16" -> stack.getMaxStackSize() == 16;
            case "single", "singles", "unstackable", "unstackables", "x1" -> stack.getMaxStackSize() == 1;
            default -> false;
        };
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9* ]", "").replaceAll("\\s+", " ").trim();
    }

    private static String singular(String word) {
        if (word.length() > 4 && word.endsWith("es") && !word.endsWith("ses")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) return word.substring(0, word.length() - 1);
        return word;
    }

    // Finding containers

    private void findBoxes() {
        BlockPos center = mc.player.blockPosition();
        int r = radius.get(), h = height.get();
        Map<BlockPos, Box> byPart = new HashMap<>();

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -h, -r), center.offset(r, h, r))) {
            BlockEntity entity = mc.level.getBlockEntity(p);
            boolean chest = entity instanceof ChestBlockEntity;
            if (!chest && !(barrels.get() && entity instanceof BarrelBlockEntity)) continue;

            BlockPos pos = p.immutable();
            if (byPart.containsKey(pos)) continue;

            List<BlockPos> parts = new ArrayList<>(List.of(pos));
            BlockState state = mc.level.getBlockState(pos);
            if (chest && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
            }

            Box box = new Box(pos, parts);
            for (BlockPos part : parts) byPart.put(part, box);
            readSigns(box);
            boxes.add(box);
        }

        // Tiers fill the nearest containers first
        Vec3 start = mc.player.position();
        boxes.sort(Comparator.<Box>comparingDouble(b -> start.distanceToSqr(Vec3.atCenterOf(b.pos)))
            .thenComparingInt(b -> b.pos.getY()).thenComparingInt(b -> b.pos.getX()).thenComparingInt(b -> b.pos.getZ()));
        for (int i = 0; i < boxes.size(); i++) boxes.get(i).order = i;
    }

    /**
     * Signs on the side of a container facing away from it, or standing on top of it.
     */
    private void readSigns(Box box) {
        for (BlockPos part : box.parts) {
            for (Direction dir : HORIZONTALS) {
                BlockPos signPos = part.relative(dir);
                BlockState state = mc.level.getBlockState(signPos);
                if (state.getBlock() instanceof WallSignBlock && state.getValue(WallSignBlock.FACING) == dir) addLines(box, signPos);
            }

            BlockPos above = part.above();
            if (mc.level.getBlockState(above).getBlock() instanceof StandingSignBlock) addLines(box, above);
        }
    }

    private void addLines(Box box, BlockPos signPos) {
        if (!(mc.level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) return;

        for (var text : List.of(sign.getFrontText(), sign.getBackText())) {
            for (int i = 0; i < 4; i++) {
                String line = text.getMessage(i, false).getString().trim();
                if (!line.isEmpty()) box.labels.add(line);
            }
        }
    }

    // Helpers

    private void read(Box box) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        int size = menu.slots.size() - 36;

        box.contents = new ItemStack[size];
        for (int i = 0; i < size; i++) box.contents[i] = menu.getSlot(i).getItem().copy();
    }

    /**
     * Inventory slots holding stash items we're carrying.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carrySlots) {
            if (!mc.player.getInventory().getItem(slot).isEmpty()) slots.add(slot);
        }
        return slots;
    }

    private int freeCarrySlots() {
        int count = 0;
        for (int slot : carrySlots) if (mc.player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    /**
     * A slot of the player's that's emptied since we started, say by eating, can carry too. Taking a stack fills
     * whichever empty slot vanilla picks, so every empty slot has to count as one of ours.
     */
    private void refreshCarrySlots() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) carrySlots.add(i);
        }
    }

    /**
     * Where inventory slot {@code index} sits in a container menu: the main inventory follows the container's slots,
     * then the hotbar.
     */
    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }

    private boolean inReach(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        for (BlockPos part : box.parts) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(part)) <= reachSq) return true;
        }
        return false;
    }

    private BlockPos nearestPart(Box box) {
        Vec3 eyes = mc.player.getEyePosition();
        return box.parts.stream().min(Comparator.comparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p)))).orElse(box.pos);
    }

    private Box nearest(List<Box> list) {
        Vec3 eyes = mc.player.getEyePosition();
        return list.stream().min(Comparator.comparingDouble(b -> eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b))))).orElse(null);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, StashSorter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null) return;

        for (BlockPos part : target.parts) {
            event.renderer.box(part, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
