package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AutoTill extends Module implements Restocker.Owner {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlanting = settings.createGroup("Planting");
    private final SettingGroup sgWater = settings.createGroup("Water");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Maximum distance from your eyes to the center of a block to till it.")
        .defaultValue(4.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between tilling batches.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to till per batch.")
        .defaultValue(7)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to turn into farmland.")
        .defaultValue(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT_PATH, Blocks.COARSE_DIRT)
        .build()
    );

    private final Setting<Boolean> onlyHydrated = sgGeneral.add(new BoolSetting.Builder()
        .name("only-hydrated")
        .description("Only till blocks that would be hydrated (water within 4 blocks horizontally, same level or one above).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to a hoe, shovel or water bucket in your hotbar when needed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches back to your previous slot after tilling.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the block being tilled or dug. Placing water always rotates, since buckets use your look direction.")
        .defaultValue(false)
        .build()
    );

    // Planting

    private static final Set<Item> SEED_ITEMS = Set.of(
        Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.CARROT, Items.POTATO,
        Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD
    );

    private final Setting<Boolean> autoPlant = sgPlanting.add(new BoolSetting.Builder()
        .name("auto-plant")
        .description("Plants seeds on empty farmland once the land around is tilled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> seeds = sgPlanting.add(new ItemListSetting.Builder()
        .name("seeds")
        .description("Which seeds to plant. The first one found in your hotbar is used.")
        .defaultValue(Items.WHEAT_SEEDS)
        .filter(SEED_ITEMS::contains)
        .visible(autoPlant::get)
        .build()
    );

    private final Setting<Boolean> boneMeal = sgPlanting.add(new BoolSetting.Builder()
        .name("bone-meal")
        .description("Uses bone meal on crops growing on farmland until they're fully grown, while you have some.")
        .defaultValue(true)
        .visible(autoPlant::get)
        .build()
    );

    private final Setting<Boolean> seedRestock = sgPlanting.add(new BoolSetting.Builder()
        .name("seed-restock")
        .description("Restocks seeds when you run low.")
        .defaultValue(false)
        .visible(autoPlant::get)
        .build()
    );

    private final Setting<RestockMode> restockMode = sgPlanting.add(new EnumSetting.Builder<RestockMode>()
        .name("restock-mode")
        .description("Storage: take seeds from a container you pick. Ender Chest: place your ender chest, take seeds from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> restockUnavailable = false)
        .visible(() -> autoPlant.get() && seedRestock.get())
        .build()
    );

    private final Setting<Integer> restockAt = sgPlanting.add(new IntSetting.Builder()
        .name("restock-at")
        .description("Restock when you're down to this many seeds or fewer, and there's farmland waiting to be planted.")
        .defaultValue(1)
        .range(0, 256)
        .sliderRange(0, 64)
        .visible(() -> autoPlant.get() && seedRestock.get())
        .build()
    );

    private final Setting<BlockPos> seedStorage = sgPlanting.add(new BlockPosSetting.Builder()
        .name("seed-storage")
        .description("Coordinates of the container to take seeds from. Auto-walk is needed if it's out of reach.")
        .defaultValue(BlockPos.ZERO)
        .onChanged(pos -> restockUnavailable = false)
        .visible(this::usingStorage)
        .build()
    );

    private final Setting<Boolean> pickStorage = sgPlanting.add(new BoolSetting.Builder()
        .name("pick-storage")
        .description("Turn on, then right-click a container to set it as the seed storage. Works while the module is off.")
        .defaultValue(false)
        .visible(this::usingStorage)
        .build()
    );

    // Water

    private final Setting<Boolean> autoWater = sgWater.add(new BoolSetting.Builder()
        .name("auto-water")
        .description("Digs a hole and fills it with a water bucket when tillable blocks have no water nearby. Dry blocks wait to be tilled until they're watered.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> waterSideColor = sgWater.add(new ColorSetting.Builder()
        .name("water-side-color")
        .description("The side color of the planned water hole.")
        .defaultValue(new SettingColor(255, 3, 247, 50))
        .visible(autoWater::get)
        .build()
    );

    private final Setting<SettingColor> waterLineColor = sgWater.add(new ColorSetting.Builder()
        .name("water-line-color")
        .description("The line color of the planned water hole.")
        .defaultValue(new SettingColor(217, 40, 230, 255))
        .visible(autoWater::get)
        .build()
    );

    // Automation

    private final Setting<Boolean> autoWalk = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Uses Baritone to walk to more untilled land when nothing is left in reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refillWater = sgAutomation.add(new BoolSetting.Builder()
        .name("refill-water")
        .description("Uses Baritone to refill empty buckets at the nearest 4x4 infinite water source, then walks back.")
        .defaultValue(true)
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<RefillSource> refillSource = sgAutomation.add(new EnumSetting.Builder<RefillSource>()
        .name("refill-source")
        .description("Where to refill buckets: the nearest 4x4 water source, or a station you choose.")
        .defaultValue(RefillSource.NearestPool)
        .onChanged(source -> refillUnavailable = false)
        .visible(() -> autoWalk.get() && refillWater.get())
        .build()
    );

    private final Setting<BlockPos> station = sgAutomation.add(new BlockPosSetting.Builder()
        .name("station")
        .description("Coordinates of a water source block to refill at. Water sources within 2 blocks of it are used too.")
        .defaultValue(BlockPos.ZERO)
        .onChanged(pos -> refillUnavailable = false)
        .visible(this::usingStation)
        .build()
    );

    private final Setting<Boolean> pickStation = sgAutomation.add(new BoolSetting.Builder()
        .name("pick-station")
        .description("Turn on, then right-click a water block to set it as the station. Works while the module is off.")
        .defaultValue(false)
        .visible(this::usingStation)
        .build()
    );

    private final Setting<Integer> waterSearchRadius = sgAutomation.add(new IntSetting.Builder()
        .name("water-search-radius")
        .description("How far around you to look for a 4x4 water source, at any height. The last pool used is remembered even when out of range.")
        .defaultValue(96)
        .range(16, 256)
        .sliderRange(16, 192)
        .visible(() -> autoWalk.get() && refillWater.get() && refillSource.get() == RefillSource.NearestPool)
        .build()
    );

    private final Setting<Integer> searchRadius = sgAutomation.add(new IntSetting.Builder()
        .name("search-radius")
        .description("How far from where you enabled the module to look for land to till.")
        .defaultValue(64)
        .range(8, 128)
        .sliderRange(8, 64)
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<Integer> searchHeight = sgAutomation.add(new IntSetting.Builder()
        .name("search-height")
        .description("How many blocks above and below you to search.")
        .defaultValue(1)
        .range(1, 16)
        .sliderRange(1, 8)
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<Boolean> combineTrips = sgAutomation.add(new BoolSetting.Builder()
        .name("combine-trips")
        .description("Restocks seeds on the way back from refilling water, and refills empty buckets on the way back from restocking seeds.")
        .defaultValue(true)
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<Integer> topUpAt = sgAutomation.add(new IntSetting.Builder()
        .name("seed-top-up-at")
        .description("When combining trips, restocks seeds on a water trip if you have fewer than this many.")
        .defaultValue(128)
        .range(1, 2304)
        .sliderRange(1, 576)
        .visible(() -> autoWalk.get() && combineTrips.get() && autoPlant.get() && seedRestock.get())
        .build()
    );

    // Safety

    private final Setting<Boolean> antiBreak = sgSafety.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Won't use hoes that are about to break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDurability = sgSafety.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Hoes with this many uses left or fewer are skipped.")
        .defaultValue(10)
        .range(1, 500)
        .sliderRange(1, 100)
        .visible(antiBreak::get)
        .build()
    );

    private final Setting<Boolean> disableWhenOut = sgSafety.add(new BoolSetting.Builder()
        .name("disable-when-out")
        .description("Disables the module when no usable hoe is available.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights blocks that will be tilled.")
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
        .defaultValue(new SettingColor(87, 77, 255, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the highlight.")
        .defaultValue(new SettingColor(255, 119, 239, 255))
        .visible(render::get)
        .build()
    );

    private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private final List<BlockPos> targets = new ArrayList<>();
    private final List<BlockPos> dryTargets = new ArrayList<>();
    private final List<BlockPos> plantTargets = new ArrayList<>();
    private final List<BlockPos> boneTargets = new ArrayList<>();
    private final Map<BlockPos, Integer> plantAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> boneMealAttempts = new HashMap<>();
    private final Set<BlockPos> failedPlants = new HashSet<>();
    private final Set<BlockPos> failedBoneMeal = new HashSet<>();
    private int timer;

    private BlockPos waterSpot;
    private boolean noWaterSpot;
    private boolean digSwapped;
    private int waterCooldown;
    private boolean warnedNoBucket;
    private int noAimTicks;
    private int digTicks;
    private int waterMisses;
    private BlockPos approachedSpot;
    private final Set<BlockPos> badWaterSpots = new HashSet<>();
    private final Map<BlockPos, Integer> tillAttempts = new HashMap<>();
    private final Set<BlockPos> failedTills = new HashSet<>();
    private int hoeMissingTicks;
    private boolean warnedHoldHoe;
    private int slotBeforeTrip = -1;

    private enum State { Working, Exploring, Approaching, GoingToWater, Filling, GoingToStorage, Restocking, Returning }

    public enum RestockMode {
        Storage("Storage"),
        EnderChest("Ender Chest");

        private final String title;

        RestockMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static final int RESTOCK_RETRY_TICKS = 20 * 60;

    private final Restocker restocker = new Restocker(this, this::isSeed, "seeds");
    private boolean restockUnavailable;
    private int restockRetryTicks;

    // What this trip has already been out for, so a combined trip doesn't go round in circles
    private boolean seedsThisTrip, waterThisTrip;

    public enum RefillSource {
        NearestPool("Nearest Pool"),
        Station("Station");

        private final String title;

        RefillSource(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static final int PATH_GRACE_TICKS = 10;
    // Long enough for a trip to a far away refill station
    private static final int PATH_TIMEOUT_TICKS = 20 * 60 * 5;
    // Room for a full hotbar of buckets with time for the pool to refill between each
    private static final int FILL_TIMEOUT_TICKS = 20 * 30;
    // Not moved this long while Baritone says it's walking means it's stuck
    private static final int STUCK_TICKS = 20 * 5;
    private static final int REFILL_RETRY_TICKS = 20 * 30;
    private static final int HOE_MISSING_TICKS = 20 * 2;
    // Dirt takes under a second even by hand, so this only trips when something is badly wrong
    private static final int DIG_TIMEOUT_TICKS = 20 * 10;

    private State state = State.Working;
    private boolean usedBaritone;
    private BlockPos home;
    private int stateTicks;
    private int actionCooldown;
    private int exploreCooldown;
    private final Set<BlockPos> unreachable = new HashSet<>();

    // Current trip
    private BlockPos pathTarget;
    private int pathRange;
    private int goalTicks;
    private int stuckTicks;
    private int pathRetries;
    private Vec3 lastPos = Vec3.ZERO;
    private BlockPos returnPos;

    // Refilling
    private boolean refillUnavailable;
    private int refillRetryTicks;
    private List<BlockPos> pool;
    private BlockPos poolCorner;
    private BlockPos knownPoolCorner;
    private BlockPos tap;
    private BlockPos lastScooped;
    private boolean sawDrained;
    private int regenWait;
    private final Set<BlockPos> badPools = new HashSet<>();

    public AutoTill() {
        super(QwerteAddon.CATEGORY, "auto-till", "Uses a hoe to turn nearby dirt and grass into farmland.");

        // Always listening so the station and storage can be picked while the module is off
        MeteorClient.EVENT_BUS.subscribe(new BlockPicker());
    }

    private class BlockPicker {
        @EventHandler
        private void onMouseClick(MouseClickEvent event) {
            if (!pickStation.get() && !pickStorage.get()) return;

            // The mouse is only grabbed in game, never while a screen is open
            if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return;
            if (event.action != KeyAction.Press || event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

            // Don't let the click place, use or open anything while picking
            event.cancel();

            if (pickStation.get()) pickStation();
            else pickStorage();
        }

        private void pickStation() {
            // The crosshair ignores fluids, so trace our own ray that stops at water
            Vec3 eyes = mc.player.getEyePosition();
            Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(64));
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));

            if (hit.getType() != HitResult.Type.BLOCK || !mc.level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
                warning("That isn't a water source block, try again.");
                return;
            }

            station.set(hit.getBlockPos().immutable());
            pickStation.set(false);
            refillUnavailable = false;
            info("Refill station set to %s.", station.get().toShortString());
        }

        private void pickStorage() {
            Vec3 eyes = mc.player.getEyePosition();
            Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(64));
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

            if (hit.getType() != HitResult.Type.BLOCK || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
                warning("That isn't a container, try again.");
                return;
            }

            seedStorage.set(hit.getBlockPos().immutable());
            pickStorage.set(false);
            restockUnavailable = false;
            info("Seed storage set to %s.", seedStorage.get().toShortString());
        }
    }

    @Override
    public void onActivate() {
        timer = 0;
        waterCooldown = 0;
        warnedNoBucket = false;
        targets.clear();
        dryTargets.clear();

        state = State.Working;
        slotBeforeTrip = -1;
        hoeMissingTicks = 0;
        warnedHoldHoe = false;
        home = mc.player != null ? mc.player.blockPosition() : null;
        pathTarget = null;
        returnPos = null;
        pool = null;
        poolCorner = null;
        lastScooped = null;
        actionCooldown = 0;
        exploreCooldown = 0;
        refillUnavailable = false;
        refillRetryTicks = 0;
        restockUnavailable = false;
        restockRetryTicks = 0;
        noAimTicks = 0;
        approachedSpot = null;
        unreachable.clear();
        badPools.clear();
        badWaterSpots.clear();
        tillAttempts.clear();
        failedTills.clear();
        plantAttempts.clear();
        boneMealAttempts.clear();
        failedPlants.clear();
        failedBoneMeal.clear();
    }

    @Override
    public void onDeactivate() {
        abandonWaterSpot();
        if (state == State.Restocking) restocker.cancel();

        // Only touch Baritone if we actually drove it this session
        if (usedBaritone) {
            if (state != State.Working) BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }
        endTrip();
        targets.clear();
        dryTargets.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        targets.clear();
        dryTargets.clear();
        plantTargets.clear();
        boneTargets.clear();

        if (autoWalk.get() && !BARITONE) {
            error("Baritone isn't installed, turning off auto-walk.");
            autoWalk.set(false);
        }

        // A failed refill only pauses refilling, try again after a while
        if (refillUnavailable && refillRetryTicks > 0 && --refillRetryTicks == 0) refillUnavailable = false;
        if (restockUnavailable && restockRetryTicks > 0 && --restockRetryTicks == 0) restockUnavailable = false;

        // Travelling or refilling takes over the whole tick
        if (state != State.Working) {
            tickAutomation();
            return;
        }

        Vec3 eyes = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos center = mc.player.blockPosition();

        // Dry blocks are held back for watering only while there's a bucket to water them with, or one we can go fill
        moveToHotbar(Items.WATER_BUCKET);
        boolean hasWater = hasItem(Items.WATER_BUCKET);
        boolean holdDry = autoWater.get() && (hasWater || canRefill());

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r - 1, -r), center.offset(r, r, r))) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > reachSq) continue;

            if (autoPlant.get()) {
                if (isPlantable(pos) && !failedPlants.contains(pos)) plantTargets.add(pos.immutable());
                else if (boneMeal.get() && isBoneMealTarget(pos) && !failedBoneMeal.contains(pos)) boneTargets.add(pos.immutable());
            }

            if (!isTillableBlock(pos) || pos.equals(waterSpot) || failedTills.contains(pos)) continue;

            if ((holdDry || onlyHydrated.get()) && !isNearWater(pos)) {
                if (holdDry) dryTargets.add(pos.immutable());
                continue;
            }

            targets.add(pos.immutable());
        }

        // Low on seeds comes first: no point tilling land we have nothing to plant on. Leave a hole half dug
        // rather than mid-swing though, it's picked back up afterwards.
        if (autoPlant.get() && seedRestock.get() && !restockUnavailable && InvUtils.find(this::isSeed).count() <= restockAt.get()) {
            if (waterSpot != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            finishDigging();
            startRestock();
            return;
        }

        noWaterSpot = false;
        if (autoWater.get() && handleWater(eyes)) return;

        // Nowhere around here can hold water, so till the dry blocks anyway instead of leaving them
        if (noWaterSpot && !dryTargets.isEmpty()) {
            targets.addAll(dryTargets);
            dryTargets.clear();
        }

        Comparator<BlockPos> nearestFirst = Comparator.comparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)));
        targets.sort(nearestFirst);
        plantTargets.sort(nearestFirst);
        boneTargets.sort(nearestFirst);

        // Planting and bone meal only count as work while we have the items for them
        if (!plantTargets.isEmpty()) moveToHotbar(this::isSeed);
        if (!boneTargets.isEmpty()) moveToHotbar(Items.BONE_MEAL);
        boolean canPlant = !plantTargets.isEmpty() && findItem(this::isSeed).found();
        boolean canBoneMeal = !boneTargets.isEmpty() && findItem(stack -> stack.is(Items.BONE_MEAL)).found();

        // Nothing left to do in reach: go get water or find more land
        if (walking() && targets.isEmpty() && !canPlant && !canBoneMeal) {
            boolean needsWater = !hasWater && canRefill() && (!dryTargets.isEmpty() || waterSpot != null);
            boolean holeReady = waterSpot == null || mc.level.getBlockState(waterSpot).isAir();

            if (needsWater && holeReady) {
                startRefill();
                return;
            }

            // A hole we can't fill shouldn't keep us here forever
            if (waterSpot != null && !hasWater && !canRefill()) abandonWaterSpot();

            if (waterSpot == null) {
                startExplore(eyes);
                return;
            }
        }

        if (timer < delay.get()) {
            timer++;
            return;
        }

        // Till first, then plant what's tilled, then grow what's planted
        if (!targets.isEmpty()) {
            if (hoeReady()) runBatch(targets, this::till);
        }
        else if (canPlant) runBatch(plantTargets, this::plant);
        else if (canBoneMeal) runBatch(boneTargets, this::applyBoneMeal);
    }

    /**
     * Runs {@code action} on up to blocks-per-tick of the nearest targets, rotating first if enabled.
     */
    private void runBatch(List<BlockPos> list, Consumer<BlockPos> action) {
        int count = Math.min(blocksPerTick.get(), list.size());
        for (int i = 0; i < count; i++) {
            BlockPos pos = list.get(i);

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> action.accept(pos));
            else action.accept(pos);
        }

        timer = 0;
    }

    /**
     * Checks we have a hoe to till with, disabling the module if we've truly run out.
     */
    private boolean hoeReady() {
        if (!findItem(this::isUsableHoe).found()) {
            moveToHotbar(this::isUsableHoe);

            // Only give up when there's truly no usable hoe left, and only after a moment: a slot swap that hasn't
            // settled yet shouldn't turn the module off
            if (InvUtils.find(this::isUsableHoe).found()) {
                hoeMissingTicks = 0;
                if (!autoSwitch.get() && !warnedHoldHoe) {
                    warning("Hold a hoe, or turn on auto-switch to let me grab one.");
                    warnedHoldHoe = true;
                }
            }
            else if (disableWhenOut.get() && ++hoeMissingTicks >= HOE_MISSING_TICKS) {
                error("No usable hoe found, disabling.");
                toggle();
            }
            return false;
        }

        hoeMissingTicks = 0;
        warnedHoldHoe = false;
        return true;
    }

    private void till(BlockPos pos) {
        // Rotation callbacks run later in the tick, so re-validate everything here
        if (!isTillable(pos)) return;

        // A block the server keeps refusing would otherwise hold us here until the player moves
        if (tillAttempts.merge(pos, 1, Integer::sum) > 5) {
            failedTills.add(pos);
            return;
        }

        useOn(this::isUsableHoe, new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false));
    }

    // Planting

    private void plant(BlockPos pos) {
        if (!isPlantable(pos)) return;

        if (plantAttempts.merge(pos, 1, Integer::sum) > 5) {
            failedPlants.add(pos);
            return;
        }

        // Seeds are planted by clicking the top of the farmland
        useOn(this::isSeed, new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false));
    }

    private void applyBoneMeal(BlockPos pos) {
        if (!isBoneMealTarget(pos)) return;

        // A crop takes a few doses to grow, and each one can fail, so allow plenty before giving up on it
        if (boneMealAttempts.merge(pos, 1, Integer::sum) > 20) {
            failedBoneMeal.add(pos);
            return;
        }

        useOn(stack -> stack.is(Items.BONE_MEAL), new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /**
     * Switches to an item matching {@code predicate} and uses it on a block, switching back after if enabled.
     */
    private void useOn(Predicate<ItemStack> predicate, BlockHitResult hit) {
        FindItemResult item = findItem(predicate);
        if (!item.found()) return;

        InteractionHand hand = item.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean doSwapBack = autoSwitch.get() && swapBack.get();

        if (hand == InteractionHand.MAIN_HAND && !InvUtils.swap(item.slot(), doSwapBack)) return;

        BlockUtils.interact(hit, hand, true);

        if (doSwapBack) InvUtils.swapBack();
    }

    @Override
    public boolean rotates() {
        return rotate.get();
    }

    boolean isSeed(ItemStack stack) {
        return !stack.isEmpty() && seeds.get().contains(stack.getItem());
    }

    /**
     * Empty farmland, ready for a seed.
     */
    private boolean isPlantable(BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.FARMLAND) && mc.level.getBlockState(pos.above()).isAir();
    }

    /**
     * A crop on farmland that isn't fully grown yet.
     */
    private boolean isBoneMealTarget(BlockPos pos) {
        if (!mc.level.getBlockState(pos.below()).is(Blocks.FARMLAND)) return false;

        BlockState state = mc.level.getBlockState(pos);
        return state.getBlock() instanceof BonemealableBlock crop && crop.isValidBonemealTarget(mc.level, pos, state);
    }

    // Water

    /**
     * Advances the water hole job by one step.
     * @return true if this tick was used for watering and tilling should wait
     */
    private boolean handleWater(Vec3 eyes) {
        if (waterCooldown > 0) waterCooldown--;

        if (waterSpot != null && !isValidWaterSpot(waterSpot, eyes)) abandonWaterSpot();

        if (waterSpot == null) {
            if (dryTargets.isEmpty()) return false;
            waterSpot = findWaterSpot(eyes);
            if (waterSpot == null) {
                noWaterSpot = true;
                return false;
            }
        }

        BlockState state = mc.level.getBlockState(waterSpot);

        if (state.getFluidState().is(FluidTags.WATER)) {
            abandonWaterSpot();
            return false;
        }

        if (!state.isAir()) {
            // A hole that never finishes digging would hold up tilling forever
            if (++digTicks > DIG_TIMEOUT_TICKS) {
                giveUpOnWaterSpot();
                return false;
            }

            dig(waterSpot, state);
            return true;
        }
        digTicks = 0;

        finishDigging();

        // Waiting between bucket tries doesn't need the tick, let tilling use it
        if (waterCooldown > 0) return false;

        // The bucket check right before using it keeps failing, something else is fighting over where we look
        if (waterMisses > 10) {
            giveUpOnWaterSpot();
            return false;
        }

        if (!findItem(stack -> stack.is(Items.WATER_BUCKET)).found()) {
            if (!warnedNoBucket && !canRefill()) {
                warning("No water bucket in your hotbar, can't water dry blocks.");
                warnedNoBucket = true;
            }
            return false;
        }
        warnedNoBucket = false;

        // Buckets raycast from where you look, so we need an aim point that actually lands in the hole
        Vec3 aim = findWaterAim(eyes, waterSpot);
        if (aim == null) {
            // Can't see into the hole from here. Walk over to it once, and if that doesn't help, give up on it
            // instead of standing here forever.
            if (++noAimTicks < 20) return false;
            noAimTicks = 0;

            if (walking() && !waterSpot.equals(approachedSpot)) {
                approachedSpot = waterSpot;
                goTo(waterSpot, 2, State.Approaching);
                return true;
            }

            giveUpOnWaterSpot();
            return false;
        }
        noAimTicks = 0;

        BlockPos spot = waterSpot;
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), () -> placeWater(spot));
        waterCooldown = 5;
        return true;
    }

    /**
     * Skips this hole for good and frees up the tick for tilling.
     */
    private void giveUpOnWaterSpot() {
        badWaterSpots.add(waterSpot);
        abandonWaterSpot();
    }

    private void dig(BlockPos pos, BlockState state) {
        if (autoSwitch.get()) {
            FindItemResult tool = InvUtils.findFastestTool(state);
            if (tool.found() && InvUtils.swap(tool.slot(), swapBack.get())) digSwapped = true;
        }

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos));
        BlockUtils.breakBlock(pos, true);
    }

    private void finishDigging() {
        if (!digSwapped) return;
        if (swapBack.get()) InvUtils.swapBack();
        digSwapped = false;
    }

    private void abandonWaterSpot() {
        if (waterSpot != null && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        finishDigging();
        waterSpot = null;
        digTicks = 0;
        waterMisses = 0;
    }

    private void placeWater(BlockPos spot) {
        if (!mc.level.getBlockState(spot).isAir()) return;

        // The aim was worked out at the start of the tick, but we've moved since. Trace exactly what the bucket will
        // see from here and skip this tick rather than spill water on the wrong block.
        BlockHitResult hit = traceLook(ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(spot)) {
            waterCooldown = 1;
            waterMisses++;
            return;
        }
        waterMisses = 0;

        FindItemResult bucket = findItem(stack -> stack.is(Items.WATER_BUCKET));
        if (!bucket.found()) return;

        InteractionHand hand = bucket.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean doSwapBack = autoSwitch.get() && swapBack.get();

        if (hand == InteractionHand.MAIN_HAND && !InvUtils.swap(bucket.slot(), doSwapBack)) return;

        mc.gameMode.useItem(mc.player, hand);

        if (doSwapBack) InvUtils.swapBack();
    }

    private BlockPos findWaterSpot(Vec3 eyes) {
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos center = mc.player.blockPosition();

        BlockPos best = null;
        int bestScore = 0;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r - 1, -r), center.offset(r, r, r))) {
            if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > reachSq) continue;
            if (badWaterSpots.contains(pos) || !isValidWaterSpot(pos, eyes)) continue;

            // Water hydrates farmland on its own level within 4 blocks horizontally
            int score = 0;
            for (BlockPos dry : dryTargets) {
                if (dry.getY() != pos.getY() || dry.equals(pos)) continue;
                if (Math.abs(dry.getX() - pos.getX()) <= 4 && Math.abs(dry.getZ() - pos.getZ()) <= 4) score++;
            }
            if (score == 0) continue;

            double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                best = pos.immutable();
                bestScore = score;
                bestDist = dist;
            }
        }

        return best;
    }

    private boolean isValidWaterSpot(BlockPos pos, Vec3 eyes) {
        if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > reach.get() * reach.get()) return false;

        BlockState state = mc.level.getBlockState(pos);
        boolean diggable = blocks.get().contains(state.getBlock()) || state.is(Blocks.FARMLAND);
        if (!diggable && !state.isAir() && !state.getFluidState().is(FluidTags.WATER)) return false;

        if (!mc.level.getBlockState(pos.above()).isAir()) return false;

        // The hole has to hold the water: solid on every side and underneath
        if (!isSolid(pos.below())) return false;
        for (Direction dir : HORIZONTALS) {
            if (!isSolid(pos.relative(dir))) return false;
        }

        // Don't dig out the block you're standing on
        return !mc.player.getBoundingBox().intersects(new AABB(pos).expandTowards(0, 2, 0));
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(mc.level, pos);
    }

    private Vec3 findWaterAim(Vec3 eyes, BlockPos spot) {
        double range = mc.player.blockInteractionRange();
        Vec3 c = Vec3.atCenterOf(spot);

        List<Vec3> candidates = new ArrayList<>();
        candidates.add(new Vec3(c.x, spot.getY(), c.z));
        for (Direction dir : HORIZONTALS) {
            for (double dy : new double[] { -0.25, 0.25 }) {
                candidates.add(c.add(dir.getStepX() * 0.5, dy, dir.getStepZ() * 0.5));
            }
        }

        for (Vec3 aim : candidates) {
            Vec3 dir = aim.subtract(eyes);
            if (dir.length() > range) continue;

            Vec3 end = eyes.add(dir.normalize().scale(range));
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().relative(hit.getDirection()).equals(spot)) return aim;
        }

        return null;
    }

    /**
     * Traces along the player's current look, the same ray a bucket uses.
     */
    private BlockHitResult traceLook(ClipContext.Fluid fluid) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(mc.player.blockInteractionRange()));
        return mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, fluid, mc.player));
    }

    // Automation

    private boolean walking() {
        return autoWalk.get() && BARITONE;
    }

    private boolean canRefill() {
        return walking() && refillWater.get() && !refillUnavailable && hasItem(Items.BUCKET);
    }

    private void tickAutomation() {
        // Restocking happens in place, so it doesn't need auto-walk
        if (state == State.Restocking) {
            tickRestock();
            return;
        }

        if (!walking()) {
            if (BARITONE) BaritoneHelper.stop();
            endTrip();
            return;
        }

        stateTicks++;

        switch (state) {
            case Exploring -> {
                // Close enough to start working, no need to walk the last step
                if (stateTicks > PATH_GRACE_TICKS && inReach(pathTarget)) BaritoneHelper.stop();
                else if (stillTravelling()) return;

                if (!inReach(pathTarget)) unreachable.add(pathTarget);
                endTrip();
            }
            case Approaching, Returning -> {
                if (stillTravelling()) return;
                endTrip();
            }
            case GoingToWater -> {
                if (stillTravelling()) return;
                arriveAtWater();
            }
            case Filling -> tickFilling();
            case GoingToStorage -> {
                if (stillTravelling()) return;

                if (inReach(seedStorage.get())) {
                    restocker.startManual(seedStorage.get());
                    state = State.Restocking;
                }
                else {
                    blockRestock("Couldn't reach the seed storage at %s.", seedStorage.get().toShortString());
                    nextErrand();
                }
            }
            default -> endTrip();
        }
    }

    // Restocking

    private boolean usingStorage() {
        return autoPlant.get() && seedRestock.get() && restockMode.get() == RestockMode.Storage;
    }

    /**
     * @return true if a restock started, either here or with a walk to the storage
     */
    private boolean startRestock() {
        seedsThisTrip = true;

        if (restockMode.get() == RestockMode.EnderChest) {
            String problem = restocker.startEnderChest();
            if (problem != null) {
                restockFailed(problem);
                return false;
            }

            startRestockingHere();
            return true;
        }

        BlockPos storage = seedStorage.get();
        if (storage.equals(BlockPos.ZERO)) {
            blockRestock("No seed storage set, use pick-storage or enter its coordinates.");
            return false;
        }

        if (inReach(storage)) {
            restocker.startManual(storage);
            startRestockingHere();
            return true;
        }

        if (!walking()) {
            blockRestock("The seed storage at %s is out of reach, turn on auto-walk to walk there.", storage.toShortString());
            return false;
        }

        // On a combined trip, we still head back to where the first errand started from
        if (returnPos == null) returnPos = mc.player.blockPosition();
        goTo(storage, 2, State.GoingToStorage);
        return true;
    }

    private void startRestockingHere() {
        // Restocking swaps items around, so put back what we were holding afterwards like after a walk
        if (slotBeforeTrip < 0) slotBeforeTrip = mc.player.getInventory().getSelectedSlot();
        state = State.Restocking;
        stateTicks = 0;
    }

    private void tickRestock() {
        switch (restocker.tick()) {
            case Busy -> {}
            case Done -> {
                int seedCount = InvUtils.find(this::isSeed).count();

                if (restocker.sourceEmpty()) {
                    warning("Restocked to %d seeds, but that emptied your %s. Turning off seed-restock.", seedCount, sourceName());
                    seedRestock.set(false);
                }
                // Restocking goes first, so a storage that's run dry would otherwise send us straight back
                else if (seedCount <= restockAt.get()) blockRestock("Restocked, but you only have %d seeds. Is the storage running low?", seedCount);
                else info("Restocked seeds, you now have %d.", seedCount);

                nextErrand();
            }
            case Failed -> {
                restockFailed(restocker.failure());
                nextErrand();
            }
        }
    }

    /**
     * A storage seen to be empty turns restocking off, anything else only pauses it for a while.
     */
    private void restockFailed(String reason) {
        if (restocker.sourceEmpty()) {
            error("%s Turning off seed-restock.", reason);
            seedRestock.set(false);
        }
        else blockRestock(reason);
    }

    private String sourceName() {
        return restockMode.get() == RestockMode.EnderChest ? "ender chest's seed shulkers" : "seed storage";
    }

    /**
     * Called whenever an errand ends. With combine-trips on and while we're away from the field, does the other
     * errand before heading back.
     */
    private void nextErrand() {
        if (returnPos != null && walking() && combineTrips.get()) {
            if (!seedsThisTrip && wantsSeedTopUp() && startRestock()) return;
            if (!waterThisTrip && autoWater.get() && canRefill() && startRefill()) return;
        }

        goBack();
    }

    private boolean wantsSeedTopUp() {
        return autoPlant.get() && seedRestock.get() && !restockUnavailable && InvUtils.find(this::isSeed).count() < topUpAt.get();
    }

    /**
     * Pauses restocking for a while after it fails, it's tried again after {@link #RESTOCK_RETRY_TICKS}.
     */
    private void blockRestock(String message, Object... args) {
        warning(message, args);
        restockUnavailable = true;
        restockRetryTicks = RESTOCK_RETRY_TICKS;
    }

    /**
     * Waits for Baritone to finish the current trip, nudging it if it stalls.
     * @return true while still on the way
     */
    private boolean stillTravelling() {
        if (stateTicks > PATH_TIMEOUT_TICKS) {
            BaritoneHelper.stop();
            return false;
        }

        // Baritone needs a moment to start pathing after a goal is set
        if (goalTicks++ < PATH_GRACE_TICKS) return true;

        Vec3 pos = mc.player.position();
        if (pos.distanceToSqr(lastPos) > 0.25) {
            lastPos = pos;
            stuckTicks = 0;
        }
        else if (!BaritoneHelper.isCalculating()) stuckTicks++;

        // Baritone sometimes stalls, or quietly gives up short of the goal. Give it a few nudges before calling it a failure.
        boolean moving = BaritoneHelper.isMoving();
        if ((moving && stuckTicks > STUCK_TICKS) || (!moving && !atGoal())) {
            if (pathRetries++ < 3) {
                BaritoneHelper.moveNear(pathTarget, pathRange);
                goalTicks = 0;
                stuckTicks = 0;
                return true;
            }

            if (moving) BaritoneHelper.stop();
            return false;
        }

        return moving;
    }

    private boolean atGoal() {
        BlockPos feet = mc.player.blockPosition();
        int dx = feet.getX() - pathTarget.getX();
        int dy = feet.getY() - pathTarget.getY();
        int dz = feet.getZ() - pathTarget.getZ();
        return dx * dx + dy * dy + dz * dz <= pathRange * pathRange;
    }

    private void goTo(BlockPos pos, int range, State next) {
        // Keep Baritone from digging through the farmland we just made or messing with water
        BaritoneHelper.protect(List.of(Blocks.FARMLAND));
        usedBaritone = true;

        // Baritone picks its own tools and blocks while it walks, remember what we were holding
        if (state == State.Working) slotBeforeTrip = mc.player.getInventory().getSelectedSlot();

        pathTarget = pos;
        pathRange = range;
        BaritoneHelper.moveNear(pos, range);

        state = next;
        stateTicks = 0;
        goalTicks = 0;
        stuckTicks = 0;
        pathRetries = 0;
        lastPos = mc.player.position();
    }

    /**
     * Back to working in place, holding whatever we held before Baritone took over.
     */
    private void endTrip() {
        seedsThisTrip = waterThisTrip = false;

        if (state != State.Working && slotBeforeTrip >= 0 && mc.player != null
            && mc.player.getInventory().getSelectedSlot() != slotBeforeTrip) {
            InvUtils.swap(slotBeforeTrip, false);
        }

        slotBeforeTrip = -1;
        state = State.Working;
    }

    private void goBack() {
        pool = null;
        seedsThisTrip = waterThisTrip = false;
        if (returnPos != null) goTo(returnPos, 1, State.Returning);
        else endTrip();
        returnPos = null;
    }

    private void startExplore(Vec3 eyes) {
        if (exploreCooldown > 0) {
            exploreCooldown--;
            return;
        }

        BlockPos target = findLand(eyes);
        if (target == null) {
            info("No more land to till within %d blocks, waiting.", searchRadius.get());
            exploreCooldown = 200;
            return;
        }

        goTo(target, exploreRange(), State.Exploring);
    }

    private BlockPos findLand(Vec3 eyes) {
        if (home == null) home = mc.player.blockPosition();

        int radius = searchRadius.get();
        int height = searchHeight.get();
        int y = mc.player.blockPosition().getY();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(home.getX() - radius, y - height, home.getZ() - radius, home.getX() + radius, y + height, home.getZ() + radius)) {
            if (!isTillableBlock(pos) || inReach(pos) || unreachable.contains(pos) || failedTills.contains(pos)) continue;

            double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
            if (dist < bestDist) {
                best = pos.immutable();
                bestDist = dist;
            }
        }

        return best;
    }

    // Refilling

    /**
     * @return true if we set off for water
     */
    private boolean startRefill() {
        waterThisTrip = true;

        if (usingStation()) {
            if (station.get().equals(BlockPos.ZERO)) {
                blockRefill("No refill station set, tilling dry land without water for now.");
                return false;
            }

            // A far away station may not be loaded yet, its water is checked once we get there
            pool = mc.level.isLoaded(station.get()) ? stationPool() : null;
            if (pool != null && pool.isEmpty()) {
                blockRefill("Refill station at %s isn't water, tilling dry land without water for now.", station.get().toShortString());
                return false;
            }

            // On a combined trip, we still head back to where the first errand started from
            if (returnPos == null) returnPos = mc.player.blockPosition();
            goToWater(station.get());
            return true;
        }

        BlockPos corner = findPoolCorner();
        if (corner == null && knownPoolCorner != null && !badPools.contains(knownPoolCorner)) corner = knownPoolCorner;

        if (corner == null) {
            blockRefill("No 4x4 water source within %d blocks, tilling dry land without water for now.", waterSearchRadius.get());
            return false;
        }

        if (returnPos == null) returnPos = mc.player.blockPosition();
        poolCorner = corner;
        pool = mc.level.isLoaded(corner) ? squareAt(corner) : null;
        goToWater(corner);
        return true;
    }

    /**
     * Heads for a spot on the bank if we can see the water already, otherwise just towards {@code fallback}.
     */
    private void goToWater(BlockPos fallback) {
        BlockPos stand = pool != null ? findStandSpot(pool) : null;

        // Standing on the bank keeps Baritone out of the water, so it never has to climb out over blocks it placed
        if (stand != null) goTo(stand, 0, State.GoingToWater);
        else goTo(fallback, 3, State.GoingToWater);
    }

    private void arriveAtWater() {
        // The station or a remembered pool may not have been loaded when we set off
        pool = usingStation() ? stationPool() : squareAt(poolCorner);

        if (pool == null || pool.isEmpty()) {
            failPool();
            return;
        }

        if (findFillAim() != null) {
            if (!usingStation()) knownPoolCorner = poolCorner;

            state = State.Filling;
            stateTicks = 0;
            actionCooldown = 0;
            lastScooped = null;
            return;
        }

        // Near the water but nothing to fill from in view, walk to a spot on the bank once
        BlockPos stand = findStandSpot(pool);
        if (stand != null && !(stand.equals(pathTarget) && pathRange == 0)) {
            goTo(stand, 0, State.GoingToWater);
            return;
        }

        failPool();
    }

    private void failPool() {
        if (usingStation()) {
            blockRefill("Couldn't refill at the station at %s, tilling dry land without water for now.", station.get().toShortString());
            nextErrand();
            return;
        }

        // Try the next closest pool
        badPools.add(poolCorner);
        if (poolCorner.equals(knownPoolCorner)) knownPoolCorner = null;

        BlockPos corner = findPoolCorner();
        if (corner != null) {
            poolCorner = corner;
            pool = squareAt(corner);
            goToWater(corner);
            return;
        }

        blockRefill("Couldn't reach any 4x4 water source, tilling dry land without water for now.");
        nextErrand();
    }

    /**
     * Turns refilling off for a while, it's tried again after {@link #REFILL_RETRY_TICKS}.
     */
    private void blockRefill(String message, Object... args) {
        warning(message, args);
        refillUnavailable = true;
        refillRetryTicks = REFILL_RETRY_TICKS;
    }

    private boolean usingStation() {
        return autoWalk.get() && refillWater.get() && refillSource.get() == RefillSource.Station;
    }

    /**
     * Water source blocks at and around the station, on the station's level.
     */
    private List<BlockPos> stationPool() {
        List<BlockPos> sources = new ArrayList<>();
        BlockPos s = station.get();

        for (BlockPos pos : BlockPos.betweenClosed(s.offset(-2, 0, -2), s.offset(2, 0, 2))) {
            if (isWaterSource(pos)) sources.add(pos.immutable());
        }

        return sources;
    }

    /**
     * Finds the closest 4x4 square of water sources open to the sky, which refills itself when buckets are taken from
     * it. Only the top block of each column is checked, which is where an open pool always is, so this stays cheap
     * over a big radius with no height limit.
     */
    private BlockPos findPoolCorner() {
        BlockPos center = mc.player.blockPosition();
        int radius = waterSearchRadius.get();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;

                pos.set(x, mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                if (!isOpenWaterSource(pos) || badPools.contains(pos)) continue;

                double dist = mc.player.distanceToSqr(x + 2, pos.getY(), z + 2);
                if (dist >= bestDist || squareAt(pos) == null) continue;

                best = pos.immutable();
                bestDist = dist;
            }
        }

        return best;
    }

    /**
     * The 16 blocks of a 4x4 pool with {@code corner} as its lowest x/z corner, or null if they aren't all open water sources.
     */
    private List<BlockPos> squareAt(BlockPos corner) {
        if (corner == null) return null;

        List<BlockPos> square = new ArrayList<>(16);
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                BlockPos p = corner.offset(dx, 0, dz);
                if (!isOpenWaterSource(p)) return null;
                square.add(p);
            }
        }

        return square;
    }

    private boolean isWaterSource(BlockPos pos) {
        FluidState fluid = mc.level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && fluid.isSource();
    }

    private boolean isOpenWaterSource(BlockPos pos) {
        return isWaterSource(pos) && mc.level.getBlockState(pos.above()).isAir();
    }

    private int sourceNeighbors(BlockPos pos) {
        int count = 0;
        for (Direction dir : HORIZONTALS) {
            if (isWaterSource(pos.relative(dir))) count++;
        }
        return count;
    }

    /**
     * A source block that grows back after being scooped: vanilla refills it when at least two horizontal neighbors are
     * sources and it sits on something solid or more water.
     */
    private boolean isTap(BlockPos pos) {
        if (!isWaterSource(pos) || sourceNeighbors(pos) < 2) return false;
        return isSolid(pos.below()) || isWaterSource(pos.below());
    }

    /**
     * A dry spot on the bank to stand on while filling, within reach of a tap block.
     */
    private BlockPos findStandSpot(List<BlockPos> water) {
        double range = mc.player.blockInteractionRange() - 0.5;
        List<BlockPos> taps = water.stream().filter(this::isTap).toList();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos p : water) {
            for (Direction dir : HORIZONTALS) {
                BlockPos bank = p.relative(dir);
                if (water.contains(bank) || !mc.level.getFluidState(bank).isEmpty()) continue;

                for (BlockPos feet : new BlockPos[] { bank.above(), bank, bank.above(2) }) {
                    if (!isStandable(feet)) continue;

                    Vec3 eyes = Vec3.atBottomCenterOf(feet).add(0, mc.player.getEyeHeight(), 0);
                    if (taps.stream().noneMatch(t -> eyes.distanceTo(Vec3.atCenterOf(t)) <= range)) continue;

                    double dist = mc.player.distanceToSqr(Vec3.atCenterOf(feet));
                    if (dist < bestDist) {
                        best = feet.immutable();
                        bestDist = dist;
                    }
                }
            }
        }

        return best;
    }

    private boolean isStandable(BlockPos feet) {
        return isSolid(feet.below()) && isPassable(feet) && isPassable(feet.above());
    }

    private boolean isPassable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private void tickFilling() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        moveToHotbar(Items.BUCKET);
        if (!findItem(stack -> stack.is(Items.BUCKET)).found() || stateTicks > FILL_TIMEOUT_TICKS) {
            warnedNoBucket = false;
            nextErrand();
            return;
        }

        // Let the block we just scooped grow back before taking more, so the pool never drains. We wait to see it
        // empty and come back, which also gives the server time to catch up.
        if (lastScooped != null) {
            regenWait++;
            boolean source = isWaterSource(lastScooped);
            if (!source) sawDrained = true;

            boolean caughtUp = sawDrained ? source : regenWait > 20;
            if (!caughtUp && regenWait < 60) return;
            lastScooped = null;
        }

        // Wait for a clear look at the water, the timeout above gives up eventually
        Vec3 aim = findFillAim();
        if (aim == null) return;

        BlockPos target = tap;
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), () -> fillBucket(target));
        actionCooldown = 2;
    }

    /**
     * Picks the best tap in view, preferring blocks deepest inside the pool since they refill most reliably.
     * Sets {@link #tap} and returns where to look.
     */
    private Vec3 findFillAim() {
        if (pool == null) return null;

        Vec3 eyes = mc.player.getEyePosition();
        double range = mc.player.blockInteractionRange();

        List<BlockPos> taps = pool.stream()
            .filter(this::isTap)
            .sorted(Comparator.<BlockPos>comparingInt(p -> -sourceNeighbors(p)).thenComparingDouble(p -> eyes.distanceToSqr(Vec3.atCenterOf(p))))
            .toList();

        for (BlockPos p : taps) {
            Vec3 aim = Vec3.atCenterOf(p);
            Vec3 dir = aim.subtract(eyes);
            if (dir.length() > range) continue;

            Vec3 end = eyes.add(dir.normalize().scale(range));
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p)) {
                tap = p;
                return aim;
            }
        }

        return null;
    }

    private void fillBucket(BlockPos target) {
        // Same as placing water: make sure the bucket will scoop the block we picked from where we are now
        BlockHitResult hit = traceLook(ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) return;

        FindItemResult bucket = findItem(stack -> stack.is(Items.BUCKET));
        if (!bucket.found()) return;

        InteractionHand hand = bucket.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean doSwapBack = autoSwitch.get() && swapBack.get();

        if (hand == InteractionHand.MAIN_HAND && !InvUtils.swap(bucket.slot(), doSwapBack)) return;

        mc.gameMode.useItem(mc.player, hand);

        if (doSwapBack) InvUtils.swapBack();

        lastScooped = target;
        sawDrained = false;
        regenWait = 0;
    }

    private BlockPos nearest(List<BlockPos> positions) {
        return positions.stream().min(Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p)))).orElse(null);
    }

    /**
     * How close Baritone needs to get to a block before it's comfortably in reach.
     */
    private int exploreRange() {
        return (int) Math.max(1, Math.floor(reach.get() - 1.5));
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    // Helpers

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, AutoTill.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean hasItem(Item item) {
        return InvUtils.find(item).found();
    }

    /**
     * Pulls an item from the main inventory into an empty hotbar slot so it can be used.
     */
    private void moveToHotbar(Item item) {
        moveToHotbar(stack -> stack.is(item));
    }

    @Override
    public void moveToHotbar(Predicate<ItemStack> predicate) {
        if (!autoSwitch.get() || InvUtils.findInHotbar(predicate).found()) return;

        FindItemResult result = InvUtils.find(predicate);
        if (!result.found() || result.isHotbar()) return;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                InvUtils.move().from(result.slot()).toHotbar(i);
                return;
            }
        }
    }

    private FindItemResult findItem(Predicate<ItemStack> predicate) {
        // findInHotbar checks the offhand and main hand before the rest of the hotbar
        if (autoSwitch.get()) return InvUtils.findInHotbar(predicate);

        if (predicate.test(mc.player.getOffhandItem())) return InvUtils.findInHotbar(predicate);
        if (predicate.test(mc.player.getMainHandItem())) return new FindItemResult(mc.player.getInventory().getSelectedSlot(), 1);
        return new FindItemResult(-1, 0);
    }

    private boolean isUsableHoe(ItemStack stack) {
        if (!stack.is(ItemTags.HOES)) return false;
        if (!antiBreak.get() || !stack.isDamageableItem()) return true;

        int usesLeft = stack.getMaxDamage() - stack.getDamageValue();
        return usesLeft > minDurability.get();
    }

    private boolean isTillableBlock(BlockPos pos) {
        if (!blocks.get().contains(mc.level.getBlockState(pos).getBlock())) return false;
        return mc.level.getBlockState(pos.above()).isAir();
    }

    private boolean isTillable(BlockPos pos) {
        if (!isTillableBlock(pos)) return false;
        return !onlyHydrated.get() || isNearWater(pos);
    }

    // Mirrors FarmBlock's hydration check
    private boolean isNearWater(BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (mc.level.getFluidState(p).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (BlockPos pos : targets) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }

        if (waterSpot != null) {
            event.renderer.box(waterSpot, waterSideColor.get(), waterLineColor.get(), shapeMode.get(), 0);
        }
    }
}
