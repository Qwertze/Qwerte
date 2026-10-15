package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.Players;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.utils.AutoHotbar;
import com.porter.qwerte.utils.BaritoneHelper;
import com.porter.qwerte.utils.WorkPattern;
import com.porter.qwerte.utils.WorkPattern.RowDirection;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AutoTill extends Module implements Restocker.Owner {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlanting = settings.createGroup("Planting");
    private final SettingGroup sgWater = settings.createGroup("Water");
    private final SettingGroup sgIce = settings.createGroup("Ice");
    private final SettingGroup sgHotbar = settings.createGroup("Hotbar");
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
        .description("Switches to a hoe, shovel, water bucket, ice or glowstone in your hotbar when needed.")
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
        .description("Faces the block being tilled or dug. Placing water, ice and glowstone always rotates, since they use your look direction.")
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
        .onChanged(mode -> this.seedSupply.unavailable = false)
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

    // The storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> storageData = sgPlanting.add(new StringListSetting.Builder()
        .name("seed-storages")
        .description("The containers to take seeds from, managed with pick-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    // Older versions kept a single storage here, it's folded into the list when the settings load
    private final Setting<BlockPos> legacyStorage = sgPlanting.add(new BlockPosSetting.Builder()
        .name("seed-storage")
        .description("Old single seed storage, moved into seed-storages.")
        .defaultValue(BlockPos.ZERO)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickStorage = sgPlanting.add(new BoolSetting.Builder()
        .name("pick-storage")
        .description("Turn on, then right-click a container to add it to the seed storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.onlyPick(this.pickStorage);
        })
        .visible(this::usingStorage)
        .build()
    );

    private final Setting<Boolean> boneMealRestock = sgPlanting.add(new BoolSetting.Builder()
        .name("bone-meal-restock")
        .description("Restocks bone meal when you run low.")
        .defaultValue(false)
        .visible(() -> autoPlant.get() && boneMeal.get())
        .build()
    );

    private final Setting<RestockMode> boneMealRestockMode = sgPlanting.add(new EnumSetting.Builder<RestockMode>()
        .name("bone-meal-restock-mode")
        .description("Storage: take bone meal from a container you pick. Ender Chest: place your ender chest, take bone meal from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> this.boneMealSupply.unavailable = false)
        .visible(() -> (autoPlant.get() && boneMeal.get()) && boneMealRestock.get())
        .build()
    );

    private final Setting<Integer> boneMealRestockAt = sgPlanting.add(new IntSetting.Builder()
        .name("bone-meal-restock-at")
        .description("Restock when you're down to this much bone meal or less, and there are crops waiting to be grown.")
        .defaultValue(4)
        .range(0, 256)
        .sliderRange(0, 64)
        .visible(() -> (autoPlant.get() && boneMeal.get()) && boneMealRestock.get())
        .build()
    );

    // The bone-meal storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> boneMealStorageData = sgPlanting.add(new StringListSetting.Builder()
        .name("bone-meal-storages")
        .description("The containers to take bone meal from, managed with pick-bone-meal-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickBoneMealStorage = sgPlanting.add(new BoolSetting.Builder()
        .name("pick-bone-meal-storage")
        .description("Turn on, then right-click a container to add it to the bone-meal storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.onlyPick(this.pickBoneMealStorage);
        })
        .visible(() -> this.boneMealSupply.usingStorage())
        .build()
    );

    // Water

    private final Setting<Boolean> autoWater = sgWater.add(new BoolSetting.Builder()
        .name("auto-water")
        .description("Digs a hole and fills it with water (from a bucket, or with ice, see water-source) when tillable blocks have no water nearby. Dry blocks wait to be tilled until they're watered.")
        .defaultValue(true)
        .onChanged(on -> this.cancelIce())
        .build()
    );

    private final Setting<WaterSource> waterSource = sgWater.add(new EnumSetting.Builder<WaterSource>()
        .name("water-source")
        .description("What fills the holes. Bucket: pours a water bucket. Ice: places an ice block in the hole and breaks it, which turns it into water (needs a solid block under the hole, and doesn't work in the Nether or in creative).")
        .defaultValue(WaterSource.Bucket)
        .onChanged(source -> {
            this.warnedNoBucket = false;
            this.warnedIceProblem = false;
            this.iceBroken = false;
            this.iceFailures = 0;
            this.iceProblemTick = -1;
            this.cancelIce();
        })
        .visible(autoWater::get)
        .build()
    );

    private final Setting<Boolean> fillHoles = sgWater.add(new BoolSetting.Builder()
        .name("fill-holes")
        .description("Fills any empty 1x1 hole in reach that would water dry farmland or dirt, even while Baritone is walking, so holes missed earlier get filled on the way past.")
        .defaultValue(true)
        .visible(autoWater::get)
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

    private final Setting<Boolean> lightHoles = sgWater.add(new BoolSetting.Builder()
        .name("light-holes")
        .description("Puts a glowstone block directly above each water hole this module fills, so the field is lit. Farmland is still watered. Glowstone comes from your inventory and goes on after the water is in, against a solid block next to the spot above the hole when there is one, otherwise by air placing (see light-air-place).")
        .defaultValue(false)
        .visible(autoWater::get)
        .build()
    );

    private final Setting<Boolean> lightAirPlace = sgWater.add(new BoolSetting.Builder()
        .name("light-air-place")
        .description("Places the glowstone by clicking the empty spot above the hole when no block is next to it. Turn off on servers that reject or flag air placing, then only holes with a block to place against are lit.")
        .defaultValue(true)
        .visible(() -> autoWater.get() && lightHoles.get())
        .build()
    );

    private final Setting<Boolean> glowstoneRestock = sgWater.add(new BoolSetting.Builder()
        .name("glowstone-restock")
        .description("Restocks glowstone when you run low.")
        .defaultValue(false)
        .visible(() -> autoWater.get() && lightHoles.get())
        .build()
    );

    private final Setting<RestockMode> glowstoneRestockMode = sgWater.add(new EnumSetting.Builder<RestockMode>()
        .name("glowstone-restock-mode")
        .description("Storage: take glowstone from a container you pick. Ender Chest: place your ender chest, take glowstone from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> this.glowstoneSupply.unavailable = false)
        .visible(() -> (autoWater.get() && lightHoles.get()) && glowstoneRestock.get())
        .build()
    );

    private final Setting<Integer> glowstoneRestockAt = sgWater.add(new IntSetting.Builder()
        .name("glowstone-restock-at")
        .description("Restock when you're down to this much glowstone or less, and there are holes waiting to be lit.")
        .defaultValue(1)
        .range(0, 256)
        .sliderRange(0, 64)
        .visible(() -> (autoWater.get() && lightHoles.get()) && glowstoneRestock.get())
        .build()
    );

    // The glowstone storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> glowstoneStorageData = sgWater.add(new StringListSetting.Builder()
        .name("glowstone-storages")
        .description("The containers to take glowstone from, managed with pick-glowstone-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickGlowstoneStorage = sgWater.add(new BoolSetting.Builder()
        .name("pick-glowstone-storage")
        .description("Turn on, then right-click a container to add it to the glowstone storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.onlyPick(this.pickGlowstoneStorage);
        })
        .visible(() -> this.glowstoneSupply.usingStorage())
        .build()
    );

    // Ice

    private final Setting<Boolean> iceRestock = sgIce.add(new BoolSetting.Builder()
        .name("ice-restock")
        .description("Restocks ice when you run low.")
        .defaultValue(false)
        .visible(this::iceMode)
        .build()
    );

    private final Setting<RestockMode> iceRestockMode = sgIce.add(new EnumSetting.Builder<RestockMode>()
        .name("ice-restock-mode")
        .description("Storage: take ice from a container you pick. Ender Chest: place your ender chest, take ice from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> this.iceSupply.unavailable = false)
        .visible(() -> iceMode() && iceRestock.get())
        .build()
    );

    private final Setting<Integer> iceRestockAt = sgIce.add(new IntSetting.Builder()
        .name("ice-restock-at")
        .description("Restock when you're down to this much ice or less, and there are dry blocks waiting for water.")
        .defaultValue(2)
        .range(0, 256)
        .sliderRange(0, 64)
        .visible(() -> iceMode() && iceRestock.get())
        .build()
    );

    // The ice storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> iceStorageData = sgIce.add(new StringListSetting.Builder()
        .name("ice-storages")
        .description("The containers to take ice from, managed with pick-ice-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickIceStorage = sgIce.add(new BoolSetting.Builder()
        .name("pick-ice-storage")
        .description("Turn on, then right-click a container to add it to the ice storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.onlyPick(this.pickIceStorage);
        })
        .visible(this::usingIceStorage)
        .build()
    );

    // Hotbar

    private final Setting<Boolean> autoHotbar = sgHotbar.add(new BoolSetting.Builder()
        .name("auto-hotbar")
        .description("Keeps the hoe and the items you need in your hotbar: pulls them out of your inventory when they're missing from it, and tops up seeds, bone meal, ice and glowstone when the hotbar stack runs low. Never drops anything, and never moves anything while a screen is open. When off, an item is only pulled into an empty hotbar slot when it's needed and missing.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Integer> hoeSlot = sgHotbar.add(new IntSetting.Builder()
        .name("hoe-slot")
        .description("The hotbar slot (1-9) to keep the hoe in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get())
        .build()
    );

    private final Setting<Integer> seedSlot = sgHotbar.add(new IntSetting.Builder()
        .name("seed-slot")
        .description("The hotbar slot (1-9) to keep the seeds in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get() && autoPlant.get())
        .build()
    );

    private final Setting<Integer> boneMealSlot = sgHotbar.add(new IntSetting.Builder()
        .name("bone-meal-slot")
        .description("The hotbar slot (1-9) to keep the bone meal in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get() && autoPlant.get() && boneMeal.get())
        .build()
    );

    private final Setting<Integer> bucketSlot = sgHotbar.add(new IntSetting.Builder()
        .name("water-bucket-slot")
        .description("The hotbar slot (1-9) to keep the water bucket in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get() && autoWater.get() && !iceMode())
        .build()
    );

    private final Setting<Integer> iceSlot = sgHotbar.add(new IntSetting.Builder()
        .name("ice-slot")
        .description("The hotbar slot (1-9) to keep the ice in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get() && iceMode())
        .build()
    );

    private final Setting<Integer> glowstoneSlot = sgHotbar.add(new IntSetting.Builder()
        .name("glowstone-slot")
        .description("The hotbar slot (1-9) to keep the glowstone in. 0 uses any free slot.")
        .defaultValue(0)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(() -> autoHotbar.get() && autoSwitch.get() && autoWater.get() && lightHoles.get())
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
        .visible(() -> autoWalk.get() && !iceMode())
        .build()
    );

    private final Setting<RefillSource> refillSource = sgAutomation.add(new EnumSetting.Builder<RefillSource>()
        .name("refill-source")
        .description("Where to refill buckets: the nearest 4x4 water source, or a station you choose.")
        .defaultValue(RefillSource.NearestPool)
        .onChanged(source -> refillUnavailable = false)
        .visible(() -> autoWalk.get() && !iceMode() && refillWater.get())
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
        .onChanged(on -> {
            if (on) this.supplies.forEach(supply -> supply.pick.set(false));
        })
        .visible(this::usingStation)
        .build()
    );

    private final Setting<Integer> waterSearchRadius = sgAutomation.add(new IntSetting.Builder()
        .name("water-search-radius")
        .description("How far around you to look for a 4x4 water source, at any height. The last pool used is remembered even when out of range.")
        .defaultValue(96)
        .range(16, 256)
        .sliderRange(16, 192)
        .visible(() -> autoWalk.get() && !iceMode() && refillWater.get() && refillSource.get() == RefillSource.NearestPool)
        .build()
    );

    private final Setting<WorkPattern> workPattern = sgAutomation.add(new EnumSetting.Builder<WorkPattern>()
        .name("work-pattern")
        .description("How to pick the next land to till when nothing is left in reach. Nearest: always the closest uncovered spot. Rows: sweeps back and forth along parallel lanes, like mowing a lawn. Spiral Out: rings from where you enabled it outwards. Spiral In: the outer ring first, working towards the middle.")
        .defaultValue(WorkPattern.Nearest)
        .onChanged(pattern -> resetSweep())
        .visible(autoWalk::get)
        .build()
    );

    private final Setting<RowDirection> rowDirection = sgAutomation.add(new EnumSetting.Builder<RowDirection>()
        .name("row-direction")
        .description("Which way the lanes run. Auto runs them the way you were facing when you enabled the module.")
        .defaultValue(RowDirection.Auto)
        .onChanged(direction -> resetSweep())
        .visible(() -> autoWalk.get() && workPattern.get() == WorkPattern.Rows)
        .build()
    );

    private final Setting<Integer> laneWidth = sgAutomation.add(new IntSetting.Builder()
        .name("lane-width")
        .description("How wide each lane or ring is in blocks. 0 works it out from your reach, so one pass covers everything you can reach.")
        .defaultValue(0)
        .range(0, 32)
        .sliderRange(0, 16)
        .onChanged(width -> resetSweep())
        .visible(() -> autoWalk.get() && (workPattern.get() == WorkPattern.Rows || workPattern.get() == WorkPattern.SpiralOut || workPattern.get() == WorkPattern.SpiralIn))
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
        .description("Restocks seeds, ice, bone meal and glowstone on the way back from refilling water or from each other, and refills empty buckets on the way back from restocking.")
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

    private final Setting<Integer> iceTopUpAt = sgAutomation.add(new IntSetting.Builder()
        .name("ice-top-up-at")
        .description("When combining trips, restocks ice on another trip if you have fewer than this much.")
        .defaultValue(64)
        .range(1, 2304)
        .sliderRange(1, 576)
        .visible(() -> autoWalk.get() && combineTrips.get() && iceMode() && iceRestock.get())
        .build()
    );

    private final Setting<Integer> boneMealTopUpAt = sgAutomation.add(new IntSetting.Builder()
        .name("bone-meal-top-up-at")
        .description("When combining trips, restocks bone meal on another trip if you have fewer than this much.")
        .defaultValue(64)
        .range(1, 2304)
        .sliderRange(1, 576)
        .visible(() -> autoWalk.get() && combineTrips.get() && autoPlant.get() && boneMeal.get() && boneMealRestock.get())
        .build()
    );

    private final Setting<Integer> glowstoneTopUpAt = sgAutomation.add(new IntSetting.Builder()
        .name("glowstone-top-up-at")
        .description("When combining trips, restocks glowstone on another trip if you have fewer than this much.")
        .defaultValue(32)
        .range(1, 2304)
        .sliderRange(1, 576)
        .visible(() -> autoWalk.get() && combineTrips.get() && autoWater.get() && lightHoles.get() && glowstoneRestock.get())
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
        .description("Disables the module when no usable hoe is available. With hoe-restock on, it restocks first and only disables if that fails.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hoeRestock = sgSafety.add(new BoolSetting.Builder()
        .name("hoe-restock")
        .description("Restocks hoes when you run low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RestockMode> hoeRestockMode = sgSafety.add(new EnumSetting.Builder<RestockMode>()
        .name("hoe-restock-mode")
        .description("Storage: take hoes from a container you pick. Ender Chest: place your ender chest, take hoes from a shulker inside, put everything back and pick the ender chest up with a silk touch pickaxe.")
        .defaultValue(RestockMode.Storage)
        .onChanged(mode -> this.hoeSupply.unavailable = false)
        .visible(hoeRestock::get)
        .build()
    );

    private final Setting<Integer> hoeRestockAt = sgSafety.add(new IntSetting.Builder()
        .name("hoe-restock-at")
        .description("Restock when you're down to this many usable hoes or fewer, and there's land waiting to be tilled. 0 restocks once you have none left.")
        .defaultValue(0)
        .range(0, 36)
        .sliderRange(0, 9)
        .visible(hoeRestock::get)
        .build()
    );

    private final Setting<Integer> hoeRestockAmount = sgSafety.add(new IntSetting.Builder()
        .name("hoe-restock-amount")
        .description("How many hoes to take from each storage visited, the ones with the most uses left first. Hoes that are about to break don't count.")
        .defaultValue(2)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(hoeRestock::get)
        .build()
    );

    // The hoe storages are listed in the module's GUI, this only holds them for saving
    private final Setting<List<String>> hoeStorageData = sgSafety.add(new StringListSetting.Builder()
        .name("hoe-storages")
        .description("The containers to take hoes from, managed with pick-hoe-storage and the list below the settings.")
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> pickHoeStorage = sgSafety.add(new BoolSetting.Builder()
        .name("pick-hoe-storage")
        .description("Turn on, then right-click a container to add it to the hoe storages, or to remove it if it's already one. Turns itself off after one click. Works while the module is off.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.onlyPick(this.pickHoeStorage);
        })
        .visible(() -> this.hoeSupply.usingStorage())
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

    // Filling holes we pass by
    private BlockPos passingHole;
    private boolean heldForHole;
    private int holdTicks;
    private int holeScanTicks;
    private int holeCooldown;
    private final Map<BlockPos, Integer> holeAttempts = new HashMap<>();
    private BlockPos approachedSpot;
    private final Set<BlockPos> badWaterSpots = new HashSet<>();
    private final Map<BlockPos, Integer> tillAttempts = new HashMap<>();
    private final Set<BlockPos> failedTills = new HashSet<>();
    private int hoeMissingTicks;
    private boolean warnedHoldHoe;
    private int slotBeforeTrip = -1;

    // Whether the last tick did something: walking, refilling, tilling or planting, as opposed to waiting for ground
    private boolean busy;

    /**
     * True only while it's actually working, not while it's on and waiting for something to do.
     */
    public boolean working() {
        return isActive() && busy;
    }

    private static boolean sorterBusy() {
        StashSorter sorter = Modules.get().get(StashSorter.class);
        return sorter != null && sorter.busyWithJob();
    }

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

    /**
     * Something the module restocks: its settings, the storages to take it from and what went wrong with them lately.
     * Seeds and ice each have one, so their storages are independent.
     */
    private final class Supply {
        final String noun;
        final String items;
        final Predicate<ItemStack> is;
        final BooleanSupplier active;
        final Setting<Boolean> enabled;
        final Setting<RestockMode> mode;
        final Setting<Integer> at;
        final Setting<Boolean> pick;
        final Restocker restocker;
        final StorageList storages;
        boolean unavailable;
        int retryTicks;
        // Already been out for this on the trip, so a combined trip doesn't go round in circles
        boolean thisTrip;

        Supply(String noun, String items, Predicate<ItemStack> is, BooleanSupplier active, Setting<Boolean> enabled,
               Setting<RestockMode> mode, Setting<Integer> at, Setting<Boolean> pick, Setting<List<String>> data) {
            this.noun = noun;
            this.items = items;
            this.is = is;
            this.active = active;
            this.enabled = enabled;
            this.mode = mode;
            this.at = at;
            this.pick = pick;
            this.restocker = new Restocker(AutoTill.this, is, items);
            this.storages = new StorageList(data, () -> unavailable = false);
        }

        int count() {
            return InvUtils.find(is).count();
        }

        boolean usingStorage() {
            return active.getAsBoolean() && mode.get() == RestockMode.Storage;
        }

        /**
         * A failed restock only pauses restocking, it's tried again after a while.
         */
        void tick() {
            storages.tick();
            if (unavailable && retryTicks > 0 && --retryTicks == 0) unavailable = false;
        }

        void reset() {
            unavailable = false;
            retryTicks = 0;
            thisTrip = false;
            storages.resetSession();
        }
    }

    private final Supply seedSupply = new Supply("seed", "seeds", this::isSeed, () -> autoPlant.get() && seedRestock.get(),
        seedRestock, restockMode, restockAt, pickStorage, storageData);
    private final Supply iceSupply = new Supply("ice", "ice", this::isIce, () -> iceMode() && iceRestock.get(),
        iceRestock, iceRestockMode, iceRestockAt, pickIceStorage, iceStorageData);
    private final Supply boneMealSupply = new Supply("bone meal", "bone meal", stack -> stack.is(Items.BONE_MEAL), () -> autoPlant.get() && boneMeal.get() && boneMealRestock.get(),
        boneMealRestock, boneMealRestockMode, boneMealRestockAt, pickBoneMealStorage, boneMealStorageData);
    private final Supply glowstoneSupply = new Supply("glowstone", "glowstone", this::isGlowstone, () -> autoWater.get() && lightHoles.get() && glowstoneRestock.get(),
        glowstoneRestock, glowstoneRestockMode, glowstoneRestockAt, pickGlowstoneStorage, glowstoneStorageData);
    private final Supply hoeSupply = new Supply("hoe", "hoes", this::isUsableHoe, hoeRestock::get,
        hoeRestock, hoeRestockMode, hoeRestockAt, pickHoeStorage, hoeStorageData);
    private final List<Supply> supplies = List.of(seedSupply, iceSupply, boneMealSupply, glowstoneSupply, hoeSupply);

    private final AutoHotbar.Need hoeNeed = new AutoHotbar.Need("hoe", this::isUsableHoe, hoeSlot::get, false, () -> true);
    private final AutoHotbar.Need seedNeed = new AutoHotbar.Need("seeds", this::isSeed, seedSlot::get, true, autoPlant::get);
    private final AutoHotbar.Need boneMealNeed = new AutoHotbar.Need("bone meal", stack -> stack.is(Items.BONE_MEAL), boneMealSlot::get, true, () -> autoPlant.get() && boneMeal.get());
    // The empty bucket a water bucket turns into may be swapped out of its slot for the next one
    private final AutoHotbar.Need bucketNeed = new AutoHotbar.Need("water bucket", stack -> stack.is(Items.WATER_BUCKET), bucketSlot::get, false, () -> autoWater.get() && !iceMode())
        .sharing(stack -> stack.is(Items.BUCKET));
    private final AutoHotbar.Need iceNeed = new AutoHotbar.Need("ice", this::isIce, iceSlot::get, true, this::iceMode);
    private final AutoHotbar.Need glowstoneNeed = new AutoHotbar.Need("glowstone", this::isGlowstone, glowstoneSlot::get, true, () -> autoWater.get() && lightHoles.get());
    // Not kept in the hotbar between uses, only pulled in when the pool needs filling, into the water bucket's slot
    private final AutoHotbar.Need emptyBucketNeed = new AutoHotbar.Need("bucket", stack -> stack.is(Items.BUCKET), bucketSlot::get, false, () -> autoWater.get() && !iceMode());
    // Tools it digs and breaks ice with are never swapped out of the way. Worn out hoes aren't kept, only the usable
    // ones, and those are a need.
    private final AutoHotbar hotbar = new AutoHotbar(() -> autoHotbar.get() && autoSwitch.get(),
        stack -> stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.PICKAXES) || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET),
        message -> warning("%s", message), message -> info("%s", message),
        List.of(hoeNeed, seedNeed, boneMealNeed, bucketNeed, iceNeed, glowstoneNeed));
    // Which one the restock that's running is for
    private Supply restocking = seedSupply;

    // What this trip has already been out for, so a combined trip doesn't go round in circles
    private boolean waterThisTrip;

    // Filling holes with ice
    private enum IceStage { Place, Break, Verify }

    private static final int ICE_STAGE_TICKS = 20 * 5;
    // The water shows up in the tick the ice breaks, but the update can lag behind
    private static final int ICE_VERIFY_TICKS = 20;
    private static final int ICE_JOB_TICKS = 20 * 30;
    private static final int ICE_MAX_TRIES = 5;
    // Ice that broke without leaving water this many times in a row isn't going to work here
    private static final int ICE_MAX_FAILURES = 3;
    private static final int LIGHT_MAX_AGE = 120;
    private static final int LIGHT_MAX_TRIES = 12;

    private BlockPos iceHole;
    private IceStage iceStage;
    private int iceStageTicks;
    private int iceCooldown;
    private int iceTries;
    private int iceJobTicks;
    private int iceFailures;
    private boolean iceBroken;
    private boolean warnedIceProblem;
    private boolean iceBreakRetried;
    // iceProblem() looks through the hotbar, so it's worked out once a tick
    private int tickCount;
    private int iceProblemTick = -1;
    private String iceProblemCache;

    // Holes that got water and are waiting for their glowstone, and how long they've been waiting
    private final Map<BlockPos, Integer> lightQueue = new HashMap<>();
    // Tries that went nowhere per hole: placements that didn't land, or no way to place at all
    private final Map<BlockPos, Integer> lightTries = new HashMap<>();
    private int lightCooldown;
    private boolean warnedNoGlowstone;
    private boolean warnedNoSupport;

    public enum WaterSource {
        Bucket("Bucket"),
        Ice("Ice");

        private final String title;

        WaterSource(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

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
    // Baritone can still be sliding to a stop when it says it's done
    private static final int SETTLE_TICKS = 10;
    // How long a trip waits for us to stop and pour into a hole before carrying on without it
    private static final int HOLD_TICKS = 20;
    // Slower than this counts as standing still, so where the server thinks we are matches where we aim from
    private static final double STILL_SPEED = 0.03;
    // How far the server's idea of our eyes can be from ours while the aim still lands in the hole
    private static final double AIM_SLACK = 0.12;
    private static final int MAX_STAND_TRIES = 3;

    private State state = State.Working;
    private boolean usedBaritone;
    private BlockPos home;
    private final WorkPattern.Sweep sweep = new WorkPattern.Sweep();
    private int stateTicks;
    private int actionCooldown;
    private int exploreCooldown;
    private final Set<BlockPos> unreachable = new HashSet<>();
    // Holes we've already walked over to fill, so one we can't fill doesn't pull us back forever
    private final Set<BlockPos> holeTrips = new HashSet<>();

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
    // Whether the refill trip that's running is going to the station rather than a pool
    private boolean tripStation;
    private BlockPos knownPoolCorner;
    private BlockPos tap;
    private BlockPos lastScooped;
    private boolean sawDrained;
    private int regenWait;
    private final Set<BlockPos> badPools = new HashSet<>();

    // Arriving at the water: bank spots that didn't work out, and a moment to stop moving before judging
    private final Set<BlockPos> badStands = new HashSet<>();
    private int standTries;
    private int settleTicks;
    private boolean arrived;

    public AutoTill() {
        super(QwerteAddon.CATEGORY, "auto-till", "Uses a hoe to turn nearby dirt and grass into farmland.");

        // Only a few hoes are taken per restock, the ones with the most uses left
        hoeSupply.restocker.limit(hoeRestockAmount::get, stack -> stack.getMaxDamage() - stack.getDamageValue());

        // Always listening so the station and storage can be picked while the module is off
        BlockPicker picker = new BlockPicker();
        RightClick.listen(picker::onRightClick);
    }

    private class BlockPicker {
        /**
         * @return true when picking, so the click doesn't place, use or open anything
         */
        private boolean onRightClick() {
            // Hidden pick flags don't count, or they'd swallow right-clicks with nothing to show for it
            Supply picking = null;
            for (Supply supply : supplies) {
                if (supply.pick.get() && supply.usingStorage()) {
                    picking = supply;
                    break;
                }
            }
            if (!pickStation.get() && picking == null) return false;

            // The mouse is only grabbed in game, never while a screen is open
            if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;

            if (pickStation.get()) pickStation();
            else pickStorage(picking);
            return true;
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

        private void pickStorage(Supply supply) {
            Vec3 eyes = mc.player.getEyePosition();
            Vec3 end = eyes.add(mc.player.getViewVector(1f).scale(64));
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

            if (hit.getType() != HitResult.Type.BLOCK || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof Container)) {
                warning("That isn't a container, try again.");
                return;
            }

            if (supply == seedSupply) supply.storages.migrate(legacyStorage);
            BlockPos pos = hit.getBlockPos().immutable();
            boolean added = supply.storages.toggle(pos);
            supply.pick.set(false);
            info("%s %s storage at %s (%d storages).", added ? "Added" : "Removed", supply.noun, pos.toShortString(), supply.storages.size());
        }
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        Module module = super.fromTag(tag);
        seedSupply.storages.migrate(legacyStorage);
        return module;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        seedSupply.storages.migrate(legacyStorage);

        WVerticalList list = theme.verticalList();
        list.add(seedSupply.storages.widget(theme, "Seed storages", "pick-storage", seedSupply::usingStorage)).expandX();
        list.add(iceSupply.storages.widget(theme, "Ice storages", "pick-ice-storage", iceSupply::usingStorage)).expandX();
        list.add(boneMealSupply.storages.widget(theme, "Bone meal storages", "pick-bone-meal-storage", boneMealSupply::usingStorage)).expandX();
        list.add(glowstoneSupply.storages.widget(theme, "Glowstone storages", "pick-glowstone-storage", glowstoneSupply::usingStorage)).expandX();
        list.add(hoeSupply.storages.widget(theme, "Hoe storages", "pick-hoe-storage", hoeSupply::usingStorage)).expandX();
        return list;
    }

    @Override
    public void onActivate() {
        timer = 0;
        hotbar.reset();
        waterCooldown = 0;
        warnedNoBucket = false;
        targets.clear();
        dryTargets.clear();

        state = State.Working;
        slotBeforeTrip = -1;
        hoeMissingTicks = 0;
        warnedHoldHoe = false;
        home = mc.player != null ? mc.player.blockPosition() : null;
        resetSweep();
        pathTarget = null;
        returnPos = null;
        pool = null;
        poolCorner = null;
        lastScooped = null;
        actionCooldown = 0;
        exploreCooldown = 0;
        refillUnavailable = false;
        refillRetryTicks = 0;
        seedSupply.storages.migrate(legacyStorage);
        for (Supply supply : supplies) supply.reset();
        restocking = seedSupply;
        waterThisTrip = false;
        iceHole = null;
        iceStage = null;
        iceCooldown = 0;
        iceFailures = 0;
        iceBroken = false;
        warnedIceProblem = false;
        lightQueue.clear();
        lightTries.clear();
        lightCooldown = 0;
        warnedNoGlowstone = false;
        warnedNoSupport = false;
        noAimTicks = 0;
        approachedSpot = null;
        unreachable.clear();
        holeTrips.clear();
        badPools.clear();
        badWaterSpots.clear();
        holeAttempts.clear();
        passingHole = null;
        heldForHole = false;
        holeScanTicks = 0;
        holeCooldown = 0;
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
        cancelIce();
        if (state == State.Restocking) restocking.restocker.cancel();

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
    private void onGameLeft(GameLeftEvent event) {
        if (state == State.Restocking) restocking.restocker.cancel();

        if (usedBaritone) {
            BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        // The world is gone, so is the hole we were digging and everything we knew about it. onActivate resets the rest.
        waterSpot = null;
        digSwapped = false;
        digTicks = 0;
        waterMisses = 0;
        onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        busy = false;
        targets.clear();
        dryTargets.clear();
        plantTargets.clear();
        boneTargets.clear();
        for (Supply supply : supplies) supply.tick();

        // onActivate can run before the player exists
        if (home == null) home = mc.player.blockPosition();

        if (autoWalk.get() && !BARITONE) {
            error("Baritone isn't installed, turning off auto-walk.");
            autoWalk.set(false);
        }

        // A failed refill only pauses refilling, try again after a while
        if (refillUnavailable && refillRetryTicks > 0 && --refillRetryTicks == 0) refillUnavailable = false;

        tickCount++;
        warnAboutIce();

        // The stash sorter is in the middle of a job: no new action starts until it's done (a step of ours under way is
        // finished first)
        if (state == State.Working && iceHole == null && !digSwapped && !mc.gameMode.isDestroying() && sorterBusy()) return;

        // Keep the hotbar stocked. A move takes the tick, and it waits while a hole is being dug or filled with ice
        // or a tool swapped in for digging is still out.
        // A swapped-in digging tool outside of any dig would otherwise hold the hotbar still for good
        if (digSwapped && waterSpot == null && iceHole == null && !mc.gameMode.isDestroying()) finishDigging();

        if (state != State.Restocking && iceHole == null && !digSwapped && !mc.gameMode.isDestroying() && hotbar.tick()) return;

        // A pick flag that got hidden would otherwise stay on and be waiting for a click that never comes
        for (Supply supply : supplies) {
            if (supply.pick.get() && !supply.usingStorage()) supply.pick.set(false);
        }

        if (iceHole != null) busy = true;

        // A hole being filled with ice takes the whole tick, wherever we are, until it's watered
        if (iceHole != null && state != State.Approaching && state != State.Filling && state != State.Restocking
            && driveIce(mc.player.getEyePosition())) return;

        // Fill any open hole we're passing, walking or not. Only skipped while filling buckets or restocking, those
        // need the hands and the view to themselves.
        if (state != State.Filling && state != State.Restocking && fillPassingHole()) return;

        // Travelling or refilling takes over the whole tick
        if (state != State.Working) {
            busy = true;
            tickAutomation();
            return;
        }

        Vec3 eyes = mc.player.getEyePosition();
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos center = mc.player.blockPosition();

        // Dry blocks are held back for watering only while there's a bucket or ice to water them with, or a way to get more
        moveToHotbar(waterNeed());
        boolean hasWater = hasWaterSupply();
        boolean holdDry = autoWater.get() && (hasWater || canRefill() || canRestockIce());

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

        busy = !targets.isEmpty() || !plantTargets.isEmpty() || !boneTargets.isEmpty() || waterSpot != null;

        // Low on seeds comes first: no point tilling land we have nothing to plant on. Leave a hole half dug
        // rather than mid-swing though, it's picked back up afterwards.
        if (seedSupply.active.getAsBoolean() && !seedSupply.unavailable && seedSupply.count() <= restockAt.get()) {
            if (waterSpot != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            finishDigging();
            startRestock(seedSupply);
            return;
        }

        // Low on ice only matters while there's something waiting for water
        if (canRestockIce() && iceSupply.count() <= iceRestockAt.get() && (!dryTargets.isEmpty() || waterSpot != null)) {
            if (waterSpot != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            finishDigging();
            startRestock(iceSupply);
            return;
        }

        // Low on bone meal, glowstone or hoes only matters while there's something to use them on
        if (restockIfLow(boneMealSupply, !boneTargets.isEmpty())) return;
        if (restockIfLow(glowstoneSupply, glowstoneSupply.active.getAsBoolean() && hasLightableHole(eyes))) return;
        if (restockIfLow(hoeSupply, !targets.isEmpty())) return;

        if (lightHoles.get() && tickLight(eyes)) return;

        noWaterSpot = false;
        if (autoWater.get() && handleWater(eyes)) return;

        // Nowhere around here can hold water, so till the dry blocks anyway instead of leaving them
        if (noWaterSpot && !dryTargets.isEmpty()) {
            targets.addAll(dryTargets);
            dryTargets.clear();
        }

        Comparator<BlockPos> nearestFirst = Comparator.comparingDouble(pos -> eyes.distanceToSqr(Vec3.atCenterOf(pos)));

        // With a work pattern the strip in reach is finished in the sweep's order, the nearest only breaks ties
        if (walking() && workPattern.get() != WorkPattern.Nearest) {
            prepareSweep();
            nearestFirst = Comparator.comparingLong((BlockPos pos) -> sweep.key(pos.getX(), pos.getZ(), Long.MAX_VALUE)).thenComparing(nearestFirst);
        }
        targets.sort(nearestFirst);
        plantTargets.sort(nearestFirst);
        boneTargets.sort(nearestFirst);

        // Planting and bone meal only count as work while we have the items for them
        if (!plantTargets.isEmpty()) moveToHotbar(seedNeed);
        if (!boneTargets.isEmpty()) moveToHotbar(boneMealNeed);
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
            if (waterSpot != null && !hasWater && !canRefill() && !canRestockIce()) abandonWaterSpot();

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
     * Starts restocking {@code supply} if it's low and there's work waiting for it. Like seeds, it leaves a hole half dug
     * rather than mid-swing.
     * @return true if this tick was used
     */
    private boolean restockIfLow(Supply supply, boolean workWaiting) {
        if (!workWaiting || !supply.active.getAsBoolean() || supply.unavailable || supply.count() > supply.at.get()) return false;

        if (waterSpot != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        finishDigging();
        startRestock(supply);
        return true;
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
            moveToHotbar(hoeNeed);

            // Only give up when there's truly no usable hoe left, and only after a moment: a slot swap that hasn't
            // settled yet shouldn't turn the module off
            if (InvUtils.find(this::isUsableHoe).found()) {
                hoeMissingTicks = 0;
                if (!autoSwitch.get() && !warnedHoldHoe) {
                    warning("Hold a hoe, or turn on auto-switch to let me grab one.");
                    warnedHoldHoe = true;
                }
            }
            // With hoe-restock on, running out is only final once the restock has failed
            else if (disableWhenOut.get() && !(hoeSupply.active.getAsBoolean() && !hoeSupply.unavailable) && ++hoeMissingTicks >= HOE_MISSING_TICKS) {
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

        // Ice left in the hole by an earlier job is broken, never dug (the tool for digging could be a Silk Touch one)
        if (!state.isAir() && !(iceMode() && state.is(Blocks.ICE))) {
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

        if (iceMode()) return startIceHere(eyes);

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
        if (pourInto(spot)) waterMisses = 0;
        else {
            waterCooldown = 1;
            waterMisses++;
        }
    }

    /**
     * Empties a water bucket into {@code spot}.
     * @return false if the bucket wouldn't have landed in the spot from where we're looking, so nothing was used
     */
    private boolean pourInto(BlockPos spot) {
        if (!mc.level.getBlockState(spot).isAir()) return true;

        // The aim was worked out at the start of the tick, but we've moved since. Trace exactly what the bucket will
        // see from here and skip this tick rather than spill water on the wrong block.
        BlockHitResult hit = traceLook(ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(spot)) return false;

        FindItemResult bucket = findItem(stack -> stack.is(Items.WATER_BUCKET));
        if (!bucket.found()) return true;

        InteractionHand hand = bucket.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean doSwapBack = autoSwitch.get() && swapBack.get();

        if (hand == InteractionHand.MAIN_HAND && !InvUtils.swap(bucket.slot(), doSwapBack)) return true;

        mc.gameMode.useItem(mc.player, hand);

        if (doSwapBack) InvUtils.swapBack();

        // Its glowstone goes on once the water is in
        if (lightHoles.get()) lightQueue.putIfAbsent(spot.immutable(), 0);
        return true;
    }

    // Ice

    private boolean iceMode() {
        return autoWater.get() && waterSource.get() == WaterSource.Ice;
    }

    /**
     * What fills a hole.
     */
    private Item waterItem() {
        return iceMode() ? Items.ICE : Items.WATER_BUCKET;
    }

    private boolean hasWaterSupply() {
        return iceMode() ? iceUsable() && hasItem(Items.ICE) : hasItem(Items.WATER_BUCKET);
    }

    private boolean isIce(ItemStack stack) {
        return stack.is(Items.ICE);
    }

    private boolean isGlowstone(ItemStack stack) {
        return stack.is(Items.GLOWSTONE);
    }

    private boolean usingIceStorage() {
        return iceSupply.usingStorage();
    }

    /**
     * Ice can be restocked, and is worth going for.
     */
    private boolean canRestockIce() {
        return iceSupply.active.getAsBoolean() && !iceSupply.unavailable && iceUsable();
    }

    private boolean iceUsable() {
        return iceProblem() == null;
    }

    /**
     * Why ice can't make water right now, or null if it can. Breaking ice only leaves water behind in survival, outside
     * the Nether, with something that doesn't have Silk Touch, and with a block under the ice (holes always have one).
     */
    private String iceProblem() {
        if (iceProblemTick != tickCount) {
            iceProblemCache = computeIceProblem();
            iceProblemTick = tickCount;
        }
        return iceProblemCache;
    }

    private String computeIceProblem() {
        if (iceBroken) return "breaking ice kept not leaving any water behind";
        if (mc.level.dimension() == Level.NETHER) return "ice doesn't turn into water in the Nether";
        if (mc.player.isCreative()) return "breaking ice in creative mode doesn't leave any water behind";
        if (pickIceBreaker(Blocks.ICE.defaultBlockState()) == -1) return "there's no hotbar slot to break the ice with that isn't a silk touch tool";
        return null;
    }

    private void warnAboutIce() {
        String problem = iceMode() ? iceProblem() : null;

        if (problem == null) warnedIceProblem = false;
        else if (!warnedIceProblem) {
            warning("Can't water holes with ice: %s.", problem);
            warnedIceProblem = true;
        }
    }

    /**
     * The hole in waterSpot is dug, so hand it to the ice job.
     * @return true if this tick was used
     */
    private boolean startIceHere(Vec3 eyes) {
        if (!iceUsable()) {
            abandonWaterSpot();
            return false;
        }

        // Ice that's already in the hole only needs breaking
        if (!mc.level.getBlockState(waterSpot).is(Blocks.ICE)) {
            moveToHotbar(iceNeed);
            if (!findItem(this::isIce).found()) {
                if (!warnedNoBucket && !canRestockIce()) {
                    warning("No ice in your hotbar, can't water dry blocks.");
                    warnedNoBucket = true;
                }
                return false;
            }
        }
        warnedNoBucket = false;

        BlockPos hole = waterSpot;
        if (holeAttempts.merge(hole, 1, Integer::sum) > ICE_MAX_TRIES) {
            giveUpOnWaterSpot();
            return false;
        }

        abandonWaterSpot();
        startIce(hole);
        return driveIce(eyes);
    }

    private void startIce(BlockPos hole) {
        iceHole = hole.immutable();
        iceCooldown = 0;
        iceJobTicks = 0;
        iceBreakRetried = false;
        noAimTicks = 0;
        setIceStage(IceStage.Place);
    }

    private void setIceStage(IceStage next) {
        iceStage = next;
        iceStageTicks = 0;
        iceTries = 0;
    }

    /**
     * Advances the ice job by one step. Ice goes into the hole, then it's broken from its open top face and leaves a water
     * source behind. Glowstone, if wanted, goes on afterwards through the light queue, so the ice is never covered while
     * it is being broken.
     * @return true if the tick was used, false when the job is over
     */
    private boolean driveIce(Vec3 eyes) {
        BlockPos hole = iceHole;

        // A trip stands still for the job and carries on afterwards
        if (state != State.Working && !heldForHole) {
            BaritoneHelper.stop();
            heldForHole = true;
            holdTicks = 0;
        }

        // Knocked away or walked off: not the hole's fault, so just let go of the job
        if (!mc.level.isLoaded(hole) || !inReach(hole)) {
            clearIce();
            return false;
        }

        BlockState cell = mc.level.getBlockState(hole);
        if (cell.getFluidState().is(FluidTags.WATER)) {
            finishIce();
            return false;
        }

        if (++iceJobTicks > ICE_JOB_TICKS) {
            clearIce();
            return false;
        }

        int limit = switch (iceStage) {
            case Break -> DIG_TIMEOUT_TICKS;
            case Verify -> ICE_VERIFY_TICKS;
            default -> ICE_STAGE_TICKS;
        };

        if (++iceStageTicks > limit) {
            switch (iceStage) {
                case Verify -> failIce("the ice broke but no water appeared", true);
                case Break -> failIce("the ice took too long to break", false);
                // Placing waited too long for a clear look or for us to stand still, try again later
                default -> clearIce();
            }
            return false;
        }

        return switch (iceStage) {
            case Place -> icePlace(hole, cell, eyes);
            case Break -> iceBreak(hole, cell);
            case Verify -> iceVerify(cell);
        };
    }

    private boolean icePlace(BlockPos hole, BlockState cell, Vec3 eyes) {
        if (cell.is(Blocks.ICE)) {
            setIceStage(IceStage.Break);
            return true;
        }

        if (!cell.isAir()) {
            failIce("something is in the way", false);
            return false;
        }

        if (iceCooldown > 0) {
            iceCooldown--;
            return true;
        }

        if (iceTries >= ICE_MAX_TRIES) {
            failIce("the ice wouldn't stay in", false);
            return false;
        }

        moveToHotbar(iceNeed);
        if (!findItem(this::isIce).found()) {
            clearIce();
            return false;
        }

        Vec3 aim = findPlaceAim(eyes, hole);
        if (aim == null) {
            // Can't see into the hole from here. Walk over to it once, and if that doesn't help, let go of it
            // instead of standing here forever.
            if (++noAimTicks < 20) return true;
            noAimTicks = 0;

            if (walking() && state == State.Working && !hole.equals(approachedSpot)) {
                approachedSpot = hole;
                goTo(hole, 2, State.Approaching);
                return true;
            }

            clearIce();
            return false;
        }
        noAimTicks = 0;

        // The server traces from where it last saw us, which lags behind while we move
        if (mc.player.getDeltaMovement().horizontalDistance() > STILL_SPEED) return true;

        iceTries++;
        iceCooldown = 3;
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), () -> placeAt(hole, this::isIce));
        return true;
    }

    private boolean iceBreak(BlockPos hole, BlockState cell) {
        if (!cell.is(Blocks.ICE)) {
            setIceStage(IceStage.Verify);
            return true;
        }

        int slot = pickIceBreaker(cell);
        if (slot == -1) {
            failIce("there's no hotbar slot to break it with that isn't a silk touch tool", false);
            return false;
        }

        if (autoSwitch.get() && InvUtils.swap(slot, swapBack.get())) digSwapped = true;
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(hole), Rotations.getPitch(hole));
        BlockUtils.breakBlock(hole, true);
        return true;
    }

    private boolean iceVerify(BlockState cell) {
        // The server put the ice back, or never broke it
        if (cell.is(Blocks.ICE)) setIceStage(IceStage.Break);
        return true;
    }

    /**
     * The hole has water. It's put in the queue to be lit if that's wanted.
     */
    private void finishIce() {
        iceFailures = 0;
        if (lightHoles.get() && !mc.level.getBlockState(iceHole.above()).is(Blocks.GLOWSTONE)) lightQueue.putIfAbsent(iceHole, 0);
        clearIce();
    }

    /**
     * The job is over without water. The hole is skipped from now on, and a few of these in a row turn ice off.
     * @param noWater the ice was broken but no water came, which means ice doesn't work where we are
     */
    private void failIce(String reason, boolean noWater) {
        // Ice still in the hole gets one more go at being broken before the hole is written off
        if (!iceBreakRetried && mc.level.getBlockState(iceHole).is(Blocks.ICE) && iceUsable()) {
            iceBreakRetried = true;
            iceJobTicks = 0;
            setIceStage(IceStage.Break);
            return;
        }

        badWaterSpots.add(iceHole);
        warning("Couldn't water the hole at %s with ice: %s.", iceHole.toShortString(), reason);

        if (noWater && ++iceFailures >= ICE_MAX_FAILURES) {
            iceBroken = true;
            iceProblemTick = -1;
        }
        clearIce();
    }

    private void clearIce() {
        cancelIce();
        resumeAfterHole();
    }

    /**
     * Drops the job, leaving any trip as it is.
     */
    private void cancelIce() {
        if (iceHole != null && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        finishDigging();
        iceHole = null;
        iceStage = null;
    }

    /**
     * Air, or something like grass that a block replaces. Fluids are replaceable too but aren't free: glowstone isn't
     * put into water.
     */
    private boolean isFreeCell(BlockPos cell) {
        BlockState at = mc.level.getBlockState(cell);
        return at.isAir() || (at.canBeReplaced() && at.getFluidState().isEmpty());
    }

    /**
     * Clicking it would use it (open, toggle, press) instead of placing against it.
     */
    private boolean isClickableBlock(BlockPos pos) {
        return BlockUtils.isClickable(mc.level.getBlockState(pos).getBlock()) || mc.level.getBlockEntity(pos) != null;
    }

    /**
     * Places an item into {@code cell} by clicking a block next to it, the way the player would, from where we're looking.
     */
    private void placeAt(BlockPos cell, Predicate<ItemStack> item) {
        if (!isFreeCell(cell)) return;

        // Same as pouring water: make sure the click lands on the face we meant from where we are now
        BlockHitResult hit = traceLook(ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(cell)) return;

        // Clicking something replaceable would put the item there instead, and clicking a chest or a door would open it
        if (mc.level.getBlockState(hit.getBlockPos()).canBeReplaced() || isClickableBlock(hit.getBlockPos())) return;

        useOn(item, hit);
    }

    /**
     * The hotbar slot to break ice with: the fastest thing that isn't a Silk Touch tool (that would keep the ice instead
     * of melting it) and isn't about to break. Bare hands or any non-tool item will do, so tools are only used when they
     * are actually faster.
     * @return -1 if there's none
     */
    private int pickIceBreaker(BlockState ice) {
        boolean canSwap = autoSwitch.get();
        int selected = Players.selectedSlot();

        int best = -1;
        float bestSpeed = 0;

        for (int i = 0; i < 9; i++) {
            if (!canSwap && i != selected) continue;

            ItemStack stack = mc.player.getInventory().getItem(i);
            if (Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) continue;

            float speed = stack.isEmpty() ? 1f : stack.getDestroySpeed(ice);

            // Tools that are no faster than a hand would just wear down for nothing
            if (stack.isDamageableItem()) {
                if (speed <= 1f) continue;
                if (antiBreak.get() && stack.getMaxDamage() - stack.getDamageValue() <= minDurability.get()) continue;
            }

            if (best == -1 || speed > bestSpeed || (speed == bestSpeed && i == selected)) {
                best = i;
                bestSpeed = speed;
            }
        }

        return best;
    }

    // Lighting

    /**
     * Whether a hole in reach is ready to be lit: loaded, with water in it and a free spot above that isn't lit yet.
     */
    private boolean hasLightableHole(Vec3 eyes) {
        double reachSq = reach.get() * reach.get();
        for (BlockPos hole : lightQueue.keySet()) {
            if (!mc.level.isLoaded(hole)) continue;
            BlockPos above = hole.above();
            if (mc.level.getBlockState(above).is(Blocks.GLOWSTONE) || !isFreeCell(above)) continue;
            if (eyes.distanceToSqr(Vec3.atCenterOf(above)) > reachSq) continue;
            if (mc.level.getFluidState(hole).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /**
     * Puts glowstone above the holes that got water and are waiting for it, one at a time.
     * @return true if this tick was used
     */
    private boolean tickLight(Vec3 eyes) {
        if (lightQueue.isEmpty()) return false;

        if (lightCooldown > 0) {
            lightCooldown--;
            return false;
        }

        double reachSq = reach.get() * reach.get();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Iterator<Map.Entry<BlockPos, Integer>> it = lightQueue.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos hole = entry.getKey();
            if (!mc.level.isLoaded(hole)) continue;

            // Lit already, or something is there that we won't touch
            BlockState up = mc.level.getBlockState(hole.above());
            if (up.is(Blocks.GLOWSTONE) || !isFreeCell(hole.above())) {
                dropLight(it, hole);
                continue;
            }

            double dist = eyes.distanceToSqr(Vec3.atCenterOf(hole.above()));
            if (dist > reachSq) continue;

            // The water has to be in first. If it never shows up the pour didn't take, so there's nothing to light.
            if (!mc.level.getFluidState(hole).is(FluidTags.WATER)) {
                entry.setValue(entry.getValue() + 1);
                if (entry.getValue() > LIGHT_MAX_AGE) dropLight(it, hole);
                continue;
            }

            if (dist < bestDist) {
                best = hole;
                bestDist = dist;
            }
        }

        if (best == null) return false;

        BlockPos above = best.above();

        // Moving or in the way are passing conditions, they don't count against the hole
        if (mc.player.getBoundingBox().intersects(new AABB(above))) return false;

        moveToHotbar(glowstoneNeed);
        if (!findItem(this::isGlowstone).found()) {
            warnNoGlowstone();
            lightQueue.clear();
            lightTries.clear();
            return false;
        }
        warnedNoGlowstone = false;

        if (mc.player.getDeltaMovement().horizontalDistance() > STILL_SPEED) return false;

        // Over water there's nothing to click, so it needs a block beside or above the spot, unless air placing is allowed
        Vec3 aim = findPlaceAim(eyes, above);
        boolean airPlace = aim == null && lightAirPlace.get();

        // Only tries that go nowhere count, and they're what gets a hole given up on
        int tries = lightTries.merge(best, 1, Integer::sum);
        if (aim == null && !airPlace) {
            if (tries > LIGHT_MAX_AGE) {
                lightQueue.remove(best);
                lightTries.remove(best);
                warnNoLightSupport(best);
            }
            return false;
        }

        if (tries > LIGHT_MAX_TRIES) {
            lightQueue.remove(best);
            lightTries.remove(best);
            warning("Couldn't place glowstone above the water hole at %s, leaving it unlit.", best.toShortString());
            return false;
        }

        lightCooldown = 3;

        if (airPlace) {
            Vec3 center = Vec3.atCenterOf(above);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> airPlaceAt(above));
        }
        else Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), () -> placeAt(above, this::isGlowstone));
        return true;
    }

    /**
     * Places glowstone into an empty spot by clicking the spot itself, which needs no block next to it.
     */
    private void airPlaceAt(BlockPos cell) {
        if (!isFreeCell(cell)) return;

        useOn(this::isGlowstone, new BlockHitResult(Vec3.atCenterOf(cell), Direction.UP, cell, false));
    }

    private void warnNoGlowstone() {
        if (warnedNoGlowstone) return;
        warning("No glowstone in your hotbar, can't light the water holes.");
        warnedNoGlowstone = true;
    }

    private void warnNoLightSupport(BlockPos hole) {
        if (warnedNoSupport) return;
        warning("Nothing next to the spot above the water hole at %s to place glowstone against, so it stays unlit. Turn on light-air-place to place it against nothing.", hole.toShortString());
        warnedNoSupport = true;
    }

    private void dropLight(Iterator<Map.Entry<BlockPos, Integer>> it, BlockPos hole) {
        it.remove();
        lightTries.remove(hole);
    }

    // Filling holes in passing

    /**
     * Pours water into an open hole in reach that would hydrate dry land, wherever we are. Holes are looked for a few
     * times a second, and a hole we've picked is aimed at every tick until it's filled or we've passed it.
     * @return true if it used the tick
     */
    private boolean fillPassingHole() {
        if (!autoWater.get() || !fillHoles.get()) {
            resumeAfterHole();
            return false;
        }

        // A hole is already being filled with ice, and that has the tick (or is off walking over to its hole)
        if (iceHole != null) return false;

        Vec3 eyes = mc.player.getEyePosition();

        // Done with the hole we stopped for, or it's taking too long: carry on with the trip
        if (heldForHole && (passingHole == null || ++holdTicks > HOLD_TICKS || !isOpenHole(passingHole, eyes))) {
            if (passingHole != null && holdTicks > HOLD_TICKS) badWaterSpots.add(passingHole);
            passingHole = null;
            resumeAfterHole();
        }

        if (holeCooldown > 0) {
            holeCooldown--;
            return heldForHole;
        }

        Item fillItem = waterItem();
        moveToHotbar(waterNeed());
        if ((iceMode() && !iceUsable()) || !findItem(stack -> stack.is(fillItem)).found()) {
            passingHole = null;
            resumeAfterHole();
            return false;
        }

        if (passingHole != null && !isOpenHole(passingHole, eyes)) passingHole = null;
        if (passingHole == null) {
            if (holeScanTicks-- > 0) return false;
            holeScanTicks = 5;

            passingHole = findOpenHole(eyes);
            if (passingHole == null) return false;
        }

        // Out of sight from here; while walking, we'll likely see into it in a moment
        Vec3 aim = findWaterAim(eyes, passingHole);
        if (aim == null) {
            resumeAfterHole();
            return false;
        }

        // The server traces the bucket from where it last saw us, which lags behind while we move. Pouring on the move
        // lands the water a block off, so stop first: pause Baritone mid-trip, or just wait if you're walking yourself.
        if (mc.player.getDeltaMovement().horizontalDistance() > STILL_SPEED) {
            if (state != State.Working && !heldForHole) {
                BaritoneHelper.stop();
                heldForHole = true;
                holdTicks = 0;
            }
            return heldForHole;
        }

        if (holeAttempts.merge(passingHole, 1, Integer::sum) > 5) {
            badWaterSpots.add(passingHole);
            passingHole = null;
            resumeAfterHole();
            return false;
        }

        // Ice takes a few ticks, so it's a job of its own that holds the trip until the hole has water
        if (iceMode()) {
            startIce(passingHole);
            passingHole = null;
            return driveIce(eyes);
        }

        BlockPos hole = passingHole;
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), () -> {
            if (pourInto(hole)) passingHole = null;
        });
        holeCooldown = 2;
        return true;
    }

    /**
     * Picks the trip back up after stopping to fill a hole.
     */
    private void resumeAfterHole() {
        if (!heldForHole) return;
        heldForHole = false;

        if (state == State.Working || pathTarget == null) return;
        BaritoneHelper.moveNear(pathTarget, pathRange);
        goalTicks = 0;
        stuckTicks = 0;
        lastPos = mc.player.position();
    }

    private BlockPos findOpenHole(Vec3 eyes) {
        double reachSq = reach.get() * reach.get();
        int r = (int) Math.ceil(reach.get());
        BlockPos center = mc.player.blockPosition();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r - 1, -r), center.offset(r, r, r))) {
            double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
            if (dist > reachSq || dist >= bestDist) continue;
            if (!isOpenHole(pos, eyes) || !hydratesDryLand(pos)) continue;

            best = pos.immutable();
            bestDist = dist;
        }

        return best;
    }

    /**
     * An empty 1x1x1 hole that will hold water: air with air above, solid on every side and underneath.
     */
    private boolean isOpenHole(BlockPos pos, Vec3 eyes) {
        if (!mc.level.getBlockState(pos).isAir()) return false;
        if (pos.equals(waterSpot) || pos.equals(iceHole) || badWaterSpots.contains(pos)) return false;
        return isValidWaterSpot(pos, eyes);
    }

    /**
     * Water here would hydrate at least one farmland or tillable block that has no water yet.
     */
    private boolean hydratesDryLand(BlockPos hole) {
        for (BlockPos pos : BlockPos.betweenClosed(hole.offset(-4, 0, -4), hole.offset(4, 0, 4))) {
            BlockState state = mc.level.getBlockState(pos);
            if (!state.is(Blocks.FARMLAND) && !blocks.get().contains(state.getBlock())) continue;
            if (!isNearWater(pos)) return true;
        }
        return false;
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

    @SuppressWarnings("deprecation")
    private boolean isValidWaterSpot(BlockPos pos, Vec3 eyes) {
        if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > reach.get() * reach.get()) return false;

        BlockState state = mc.level.getBlockState(pos);
        boolean diggable = blocks.get().contains(state.getBlock()) || state.is(Blocks.FARMLAND);
        boolean leftoverIce = iceMode() && state.is(Blocks.ICE);
        if (!diggable && !leftoverIce && !state.isAir() && !state.getFluidState().is(FluidTags.WATER)) return false;

        if (!mc.level.getBlockState(pos.above()).isAir()) return false;

        // Broken ice only turns into water over something that stops movement
        if (iceMode() && !mc.level.getBlockState(pos.below()).blocksMotion()) return false;

        // The hole has to hold the water: walled in on every side and underneath
        if (!holdsWater(pos.below())) return false;
        for (Direction dir : HORIZONTALS) {
            if (!holdsWater(pos.relative(dir))) return false;
        }

        // Don't dig out the block you're standing on
        return !mc.player.getBoundingBox().intersects(new AABB(pos).expandTowards(0, 2, 0));
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(mc.level, pos);
    }

    /**
     * Water can't flow into it. Unlike {@link #isSolid}, this counts farmland and dirt paths, which are a little short of
     * a full block, so holes in a tilled field qualify.
     */
    private boolean holdsWater(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.getFluidState().isEmpty() && !state.canBeReplaced() && !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private Vec3 findWaterAim(Vec3 eyes, BlockPos spot) {
        return findAim(eyes, spot, false);
    }

    /**
     * Like {@link #findWaterAim}, for placing blocks: faces of blocks that would be used instead of placed against don't count.
     */
    private Vec3 findPlaceAim(Vec3 eyes, BlockPos spot) {
        return findAim(eyes, spot, true);
    }

    private Vec3 findAim(Vec3 eyes, BlockPos spot, boolean placing) {
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
            if (aim.distanceTo(eyes) > range) continue;
            if (landsIn(eyes, aim, spot, placing) && aimHasSlack(eyes, aim, spot, placing)) return aim;
        }

        return null;
    }

    /**
     * A bucket used from {@code eyes} looking at {@code aim} pours into {@code spot}.
     */
    private boolean landsIn(Vec3 eyes, Vec3 aim, BlockPos spot, boolean placing) {
        double range = mc.player.blockInteractionRange();
        Vec3 end = eyes.add(aim.subtract(eyes).normalize().scale(range));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(spot)) return false;
        return !placing || !isClickableBlock(hit.getBlockPos());
    }

    /**
     * The aim still lands in the spot from eyes shifted a little in every direction, in case the server has us
     * slightly somewhere else.
     */
    private boolean aimHasSlack(Vec3 eyes, Vec3 aim, BlockPos spot, boolean placing) {
        Vec3 look = aim.subtract(eyes);
        for (Direction dir : HORIZONTALS) {
            Vec3 shifted = eyes.add(dir.getStepX() * AIM_SLACK, 0, dir.getStepZ() * AIM_SLACK);
            if (!landsIn(shifted, shifted.add(look), spot, placing)) return false;
        }
        return true;
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
        return walking() && !iceMode() && refillWater.get() && !refillUnavailable && hasItem(Items.BUCKET);
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
                if (!arrived && stillTravelling()) return;
                arrived = true;
                arriveAtWater();
            }
            case Filling -> tickFilling();
            case GoingToStorage -> {
                if (stillTravelling()) return;

                BlockPos storage = restocking.storages.current();
                if (storage != null && inReach(storage)) {
                    restocking.restocker.startManual(storage);
                    state = State.Restocking;
                }
                else {
                    restocking.storages.markBad(storage);
                    if (tryNextStorage()) return;
                    String what = restocking.noun + " storage";
                    noStorageFinished(storage == null ? "Couldn't reach the " + what + "." : "Couldn't reach the " + what + " at " + storage.toShortString() + ".");
                    nextErrand();
                }
            }
            default -> endTrip();
        }
    }

    // Restocking

    private boolean usingStorage() {
        return seedSupply.usingStorage();
    }

    /**
     * @return true if a restock started, either here or with a walk to the storage
     */
    private boolean startRestock(Supply supply) {
        restocking = supply;
        supply.thisTrip = true;

        if (supply.mode.get() == RestockMode.EnderChest) {
            String problem = supply.restocker.startEnderChest();
            if (problem != null) {
                restockFailed(problem);
                return false;
            }

            startRestockingHere();
            return true;
        }

        supply.storages.beginAttempt();
        if (supply.storages.isEmpty()) {
            blockRestock("No %s storage set, use %s.", supply.noun, supply.pick.name);
            return false;
        }

        if (tryNextStorage()) return true;
        noStorageLeft();
        return false;
    }

    /**
     * Starts on the next storage that's worth trying, in reach first and then nearest.
     * @return false when there's none left to try
     */
    private boolean tryNextStorage() {
        BlockPos storage = restocking.storages.next(mc.player.getEyePosition(), this::inReach, walking());
        if (storage == null) return false;

        if (inReach(storage)) {
            restocking.restocker.startManual(storage);
            startRestockingHere();
            return true;
        }

        // On a combined trip, or after another storage, we still head back to where the first errand started from
        if (returnPos == null) returnPos = mc.player.blockPosition();
        goTo(storage, 2, State.GoingToStorage);
        return true;
    }

    /**
     * No storage is left to try: turns restocking off if they're all empty, pauses it otherwise.
     */
    private void noStorageLeft() {
        Supply supply = restocking;

        if (supply.storages.allEmpty()) {
            error("All %d %s storages are empty. Turning off %s.", supply.storages.size(), supply.noun, supply.enabled.name);
            supply.enabled.set(false);
            supply.storages.forgetEmpty();
        }
        else if (supply.storages.skippedOutOfReach() && !autoWalk.get()) blockRestock("The %s storages are out of reach, turn on auto-walk to walk there.", supply.noun);
        else if (supply.storages.skippedOutOfReach()) blockRestock("Baritone isn't installed, so I can't walk to the %s storages.", supply.noun);
        else blockRestock("Couldn't use any of the %s storages.", supply.noun);
    }

    /**
     * A storage failed and there's none left to try. If an earlier one got us enough, that still counts.
     */
    private void noStorageFinished(String reason) {
        Supply supply = restocking;
        int count = supply.count();

        if (supply.storages.allEmpty()) noStorageLeft();
        else if (count > supply.at.get()) info("Restocked %s, you now have %d.", supply.items, count);
        else blockRestock("%s", reason);
    }

    private void startRestockingHere() {
        // Restocking swaps items around, so put back what we were holding afterwards like after a walk
        if (slotBeforeTrip < 0) slotBeforeTrip = Players.selectedSlot();
        state = State.Restocking;
        stateTicks = 0;
    }

    private void tickRestock() {
        Supply supply = restocking;

        switch (supply.restocker.tick()) {
            case Busy -> {}
            case Done -> {
                int count = supply.count();

                if (supply.mode.get() == RestockMode.Storage) {
                    if (supply.restocker.sourceEmpty()) supply.storages.markEmpty(supply.storages.current());

                    // Still low, so carry on with the next storage before heading back
                    if (count <= supply.at.get() && tryNextStorage()) return;

                    if (supply.storages.allEmpty()) {
                        warning("Restocked to %d %s, but that emptied all %d %s storages. Turning off %s.", count, supply.items, supply.storages.size(), supply.noun, supply.enabled.name);
                        supply.enabled.set(false);
                        supply.storages.forgetEmpty();
                    }
                    // Restocking goes first, so storages that have run dry would otherwise send us straight back
                    else if (count <= supply.at.get()) blockRestock("Restocked, but you only have %d %s. Are the storages running low?", count, supply.items);
                    else info("Restocked %s, you now have %d.", supply.items, count);
                }
                else if (supply.restocker.sourceEmpty()) {
                    warning("Restocked to %d %s, but that emptied your %s. Turning off %s.", count, supply.items, sourceName(), supply.enabled.name);
                    supply.enabled.set(false);
                }
                else if (count <= supply.at.get()) blockRestock("Restocked, but you only have %d %s. Is the storage running low?", count, supply.items);
                else info("Restocked %s, you now have %d.", supply.items, count);

                nextErrand();
            }
            case Failed -> {
                if (supply.mode.get() == RestockMode.Storage) {
                    if (supply.restocker.sourceEmpty()) supply.storages.markEmpty(supply.storages.current());
                    else supply.storages.markBad(supply.storages.current());

                    if (tryNextStorage()) return;
                    noStorageFinished(supply.restocker.failure());
                }
                else restockFailed(supply.restocker.failure());
                nextErrand();
            }
        }
    }

    /**
     * A storage seen to be empty turns restocking off, anything else only pauses it for a while.
     */
    private void restockFailed(String reason) {
        if (restocking.restocker.sourceEmpty()) {
            error("%s Turning off %s.", reason, restocking.enabled.name);
            restocking.enabled.set(false);
        }
        else blockRestock("%s", reason);
    }

    private String sourceName() {
        return restocking.mode.get() == RestockMode.EnderChest ? "ender chest's " + restocking.noun + " shulkers" : restocking.noun + " storage";
    }

    /**
     * Called whenever an errand ends. With combine-trips on and while we're away from the field, does the other
     * errand before heading back.
     */
    private void nextErrand() {
        if (returnPos != null && walking() && combineTrips.get()) {
            if (!seedSupply.thisTrip && wantsTopUp(seedSupply, topUpAt) && startRestock(seedSupply)) return;
            if (!iceSupply.thisTrip && iceUsable() && wantsTopUp(iceSupply, iceTopUpAt) && startRestock(iceSupply)) return;
            if (!boneMealSupply.thisTrip && wantsTopUp(boneMealSupply, boneMealTopUpAt) && startRestock(boneMealSupply)) return;
            if (!glowstoneSupply.thisTrip && wantsTopUp(glowstoneSupply, glowstoneTopUpAt) && startRestock(glowstoneSupply)) return;
            if (!waterThisTrip && autoWater.get() && canRefill() && startRefill()) return;
        }

        goBack();
    }

    /**
     * Turns off every pick flag (the station's too) but {@code keep}, so a click is only ever for one thing.
     */
    private void onlyPick(Setting<Boolean> keep) {
        if (keep != pickStation) pickStation.set(false);
        for (Supply supply : supplies) {
            if (supply.pick != keep) supply.pick.set(false);
        }
    }

    private boolean wantsTopUp(Supply supply, Setting<Integer> topUp) {
        return supply.active.getAsBoolean() && !supply.unavailable && supply.count() < topUp.get();
    }

    /**
     * Pauses restocking for a while after it fails, it's tried again after {@link #RESTOCK_RETRY_TICKS}.
     */
    private void blockRestock(String message, Object... args) {
        warning(message, args);
        restocking.unavailable = true;
        restocking.retryTicks = RESTOCK_RETRY_TICKS;
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
        if (state == State.Working) slotBeforeTrip = Players.selectedSlot();

        pathTarget = pos;
        pathRange = range;
        arrived = false;
        settleTicks = 0;
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
        clearTrip();

        if (state != State.Working && slotBeforeTrip >= 0 && mc.player != null
            && Players.selectedSlot() != slotBeforeTrip) {
            InvUtils.swap(slotBeforeTrip, false);
        }

        slotBeforeTrip = -1;
        state = State.Working;
    }

    private void clearTrip() {
        for (Supply supply : supplies) supply.thisTrip = false;
        waterThisTrip = false;
    }

    private void goBack() {
        pool = null;
        clearTrip();
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

        // All tilled: walk over to a hole we missed that would water dry farmland, fill-holes does the rest
        if (target == null && autoWater.get() && fillHoles.get() && hasWaterSupply()) {
            target = findMissedHole(eyes);
            if (target != null) holeTrips.add(target);
        }

        if (target == null) {
            info("No more land to till within %d blocks, waiting.", searchRadius.get());
            exploreCooldown = 200;
            return;
        }

        goTo(target, exploreRange(), State.Exploring);
    }

    /**
     * The nearest open hole within the search area that would water dry land, and that we haven't already walked to.
     */
    private BlockPos findMissedHole(Vec3 eyes) {
        if (home == null) home = mc.player.blockPosition();

        int radius = searchRadius.get();
        int height = searchHeight.get();
        int y = mc.player.blockPosition().getY();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(home.getX() - radius, y - height - 1, home.getZ() - radius, home.getX() + radius, y + height, home.getZ() + radius)) {
            double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
            if (dist >= bestDist || !mc.level.getBlockState(pos).isAir()) continue;
            if (holeTrips.contains(pos) || unreachable.contains(pos) || badWaterSpots.contains(pos)) continue;
            if (!isHoleShape(pos) || !hydratesDryLand(pos)) continue;

            best = pos.immutable();
            bestDist = dist;
        }

        return best;
    }

    /**
     * An open 1x1 hole that holds water, without the reach check, for holes further away.
     */
    private boolean isHoleShape(BlockPos pos) {
        if (!mc.level.getBlockState(pos.above()).isAir() || !holdsWater(pos.below())) return false;
        for (Direction dir : HORIZONTALS) {
            if (!holdsWater(pos.relative(dir))) return false;
        }
        return true;
    }

    /**
     * Starts the sweep over, facing the way the player is now.
     */
    private void resetSweep() {
        sweep.reset(mc.player != null ? mc.player.getYRot() : Float.NaN);
    }

    private void prepareSweep() {
        if (home == null) home = mc.player.blockPosition();
        sweep.prepare(workPattern.get(), rowDirection.get(), laneWidth.get(), reach.get(), home.getX(), home.getZ(), searchRadius.get(), mc.player.getYRot());
    }

    /**
     * The next land to till in the search area that's out of reach: the nearest, or the first in the work pattern's
     * order (nearest breaking ties).
     */
    private BlockPos findLand(Vec3 eyes) {
        prepareSweep();

        int radius = searchRadius.get();
        int height = searchHeight.get();
        int y = mc.player.blockPosition().getY();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        long bestKey = Long.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Rows are laid over the box the work sits in, so the first scan of a run only measures that box
        for (int pass = sweep.needsBounds() ? 0 : 1; pass < 2; pass++) {
            boolean measuring = pass == 0;
            if (measuring) sweep.beginBounds();
            boolean patterned = sweep.active();

            // Column by column, skipping unloaded chunks and anything that can't beat the best so far. The pattern key
            // is the same for a whole column, only equal keys fall back to the distance.
            for (int x = home.getX() - radius; x <= home.getX() + radius; x++) {
                double dx = x + 0.5 - eyes.x;
                double dx2 = dx * dx;
                if (!patterned && !measuring && dx2 >= bestDist) continue;

                for (int z = home.getZ() - radius; z <= home.getZ() + radius; z++) {
                    double dz = z + 0.5 - eyes.z;
                    double dxz = dx2 + dz * dz;
                    if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;

                    long key = sweep.key(x, z, bestKey);
                    if (key > bestKey || (key == bestKey && dxz >= bestDist)) continue;

                    for (int py = y - height; py <= y + height; py++) {
                        double dy = py + 0.5 - eyes.y;
                        double dist = dxz + dy * dy;
                        if (key == bestKey && dist >= bestDist) continue;

                        pos.set(x, py, z);
                        if (!isTillableBlock(pos) || inReach(pos) || unreachable.contains(pos) || failedTills.contains(pos)) continue;

                        if (measuring) {
                            sweep.include(x, z);
                            break;
                        }

                        best = pos.immutable();
                        bestDist = dist;
                        bestKey = key;
                    }
                }
            }

            if (measuring) sweep.endBounds(mc.player.blockPosition().getX(), mc.player.blockPosition().getZ());
        }

        if (best != null) sweep.picked(bestKey);
        return best;
    }

    // Refilling

    /**
     * @return true if we set off for water
     */
    private boolean startRefill() {
        waterThisTrip = true;
        badStands.clear();
        standTries = 0;

        // The settings can be switched mid-trip, so the trip sticks to the source it set out for
        tripStation = usingStation();

        if (tripStation) {
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
        pool = tripStation ? stationPool() : squareAt(poolCorner);

        if (pool == null || pool.isEmpty()) {
            failPool("there's no water source there anymore");
            return;
        }

        if (findFillAim() != null) {
            if (!tripStation) knownPoolCorner = poolCorner;

            state = State.Filling;
            stateTicks = 0;
            actionCooldown = 0;
            lastScooped = null;
            return;
        }

        // Baritone can call it done while we're still sliding, give it a moment before judging the view
        if (++settleTicks < SETTLE_TICKS) return;
        settleTicks = 0;

        if (pool.stream().noneMatch(this::isTap)) {
            failPool("none of the water there refills itself, each source needs at least 2 water sources beside it");
            return;
        }

        // This bank spot didn't give us a look at the water, or Baritone stopped short of it. Try another one.
        if (pathTarget != null && pathRange == 0) badStands.add(pathTarget);

        BlockPos stand = standTries < MAX_STAND_TRIES ? findStandSpot(pool) : null;
        if (stand != null) {
            standTries++;
            goTo(stand, 0, State.GoingToWater);
            return;
        }

        // Every bank spot we stood on was a dud, or there was never one to begin with
        failPool(badStands.isEmpty()
            ? "there's nowhere on the bank to stand within reach of the water"
            : "couldn't get a clear look at the water from the bank");
    }

    private void failPool(String reason) {
        if (tripStation) {
            blockRefill("Couldn't refill at the station at %s: %s. Tilling dry land without water for now.", station.get().toShortString(), reason);
            nextErrand();
            return;
        }

        // Try the next closest pool
        badPools.add(poolCorner);
        if (poolCorner.equals(knownPoolCorner)) knownPoolCorner = null;
        badStands.clear();
        standTries = 0;

        BlockPos corner = findPoolCorner();
        if (corner != null) {
            poolCorner = corner;
            pool = squareAt(corner);
            goToWater(corner);
            return;
        }

        blockRefill("Couldn't fill up at any 4x4 water source (the last one: %s), tilling dry land without water for now.", reason);
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
        return autoWalk.get() && !iceMode() && refillWater.get() && refillSource.get() == RefillSource.Station;
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
                    if (badStands.contains(feet) || !isStandable(feet)) continue;

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

        moveToHotbar(emptyBucketNeed);
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
            for (Vec3 aim : fillAimPoints(p)) {
                Vec3 dir = aim.subtract(eyes);
                if (dir.length() > range) continue;

                Vec3 end = eyes.add(dir.normalize().scale(range));
                BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p)) {
                    tap = p;
                    return aim;
                }
            }
        }

        return null;
    }

    /**
     * Points to aim a bucket at: the middle of the block, then just under the water's surface across the top. The
     * edge of the bank often blocks the line to the middle while the near side of the surface is in plain view.
     */
    private List<Vec3> fillAimPoints(BlockPos p) {
        Vec3 c = Vec3.atCenterOf(p);
        double surface = p.getY() + 0.8;

        List<Vec3> points = new ArrayList<>();
        points.add(c);
        points.add(new Vec3(c.x, surface, c.z));
        for (Direction dir : HORIZONTALS) {
            points.add(new Vec3(c.x + dir.getStepX() * 0.35, surface, c.z + dir.getStepZ() * 0.35));
        }
        return points;
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
     * Pulls an item from the main inventory into the hotbar so it can be used: through auto-hotbar if it's on, otherwise
     * into an empty slot.
     */
    private void moveToHotbar(AutoHotbar.Need need) {
        if (!autoSwitch.get()) return;

        if (autoHotbar.get()) hotbar.ensure(need);
        else moveToHotbar(need.test());
    }

    private AutoHotbar.Need waterNeed() {
        return iceMode() ? iceNeed : bucketNeed;
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
        if (predicate.test(mc.player.getMainHandItem())) return new FindItemResult(Players.selectedSlot(), 1);
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

        if (iceHole != null) {
            event.renderer.box(iceHole, waterSideColor.get(), waterLineColor.get(), shapeMode.get(), 0);
        }
    }
}
