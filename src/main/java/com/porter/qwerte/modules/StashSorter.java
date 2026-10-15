package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.ItemPreviewScreen;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.KitPreviewScreen;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.StackInfo;
import com.porter.qwerte.stash.StackInfo.BulkKey;
import com.porter.qwerte.stash.StashWatch;
import com.porter.qwerte.stash.Containers;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.function.Predicate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a stash of chests and barrels, either into tiers (shulkers first, then stackables, then singles) or into
 * whichever container's signs best describe each item. The stash is scanned once and saved, then kept up to date
 * whenever a container in it is opened, even with the module off. It can stay on as a sorting bot, and the saved
 * stash can be searched from the module's settings or by whitelisted players over whispers.
 */
public class StashSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBot = settings.createGroup("Bot");
    private final SettingGroup sgGround = settings.createGroup("Ground");
    private final SettingGroup sgWatch = settings.createGroup("Watch");
    private final SettingGroup sgLabels = settings.createGroup("Labels");
    private final SettingGroup sgShulkers = settings.createGroup("Shulkers");
    private final SettingGroup sgKits = settings.createGroup("Kits");
    private final SettingGroup sgSlots = settings.createGroup("Slots");
    private final SettingGroup sgSearch = settings.createGroup("Search");
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

    public enum Pick {
        Off("Off"),
        Exclude("Exclude"),
        DropOff("Drop-off"),
        CompactSkip("Compact skip"),
        Corner1("Area corner 1"),
        Corner2("Area corner 2");

        private final String title;

        Pick(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // General

    private final Setting<String> stashName = sgGeneral.add(new StringSetting.Builder()
        .name("stash-name")
        .description("The name the stash is saved under. Each name on each server is its own stash, scanned once and then kept up to date.")
        .defaultValue("stash")
        .onChanged(name -> stash = null)
        .build()
    );

    private final Setting<Boolean> sortItems = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-items")
        .description("Off: scan only. The module still reads containers it doesn't know or that went stale, watches containers and signs, and keeps the saved stash current for stash-overlay and search, but never moves items: no taking, depositing, compacting, labelling, kits or picking up from the ground. Containers are only opened to read them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-walk")
        .description("While the sorter walks, Baritone never parkours, never jumps off or falls more than one block, and the sorter sneaks at ledges with a drop of 2 or more blocks. A container with no such way to it is skipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Tiers: filled shulkers, empty shulkers, 64-stackables, 16-stackables, then singles, filling the nearest containers first. Signs: each item goes to the container whose signs describe it best.")
        .defaultValue(Mode.Tiers)
        .build()
    );

    private final Setting<Pick> pick = sgGeneral.add(new EnumSetting.Builder<Pick>()
        .name("pick")
        .description("Right-click containers to mark them. Exclude: never sorted into or out of. Drop-off: emptied into the stash, for others to drop items in. Compact skip: never used to gather stacks or shulkers for compacting. Right-click again to unmark. Area corner 1 and 2: the block you right-click becomes that corner of the stash perimeter. Works while the module is off.")
        .defaultValue(Pick.Off)
        .build()
    );

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How far around you to look for new containers to add to the stash.")
        .defaultValue(8)
        .range(2, 32)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("How far above and below you to look for new containers.")
        .defaultValue(3)
        .range(0, 16)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels")
        .description("Includes barrels as well as chests.")
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

    private final Setting<Integer> quiet = sgGeneral.add(new IntSetting.Builder()
        .name("quiet")
        .description("Seconds to wait after you or another module stops using the client (a screen, movement keys, Baritone, auto eat, auto-till, auto-dirt) before carrying on. Doubles for each repeated interruption within 5 minutes, up to 60 seconds.")
        .defaultValue(3)
        .range(0, 60)
        .sliderRange(0, 20)
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

    private final Setting<Boolean> unsignedOverflow = sgGeneral.add(new BoolSetting.Builder()
        .name("unsigned-overflow")
        .description("Signs mode: when nowhere an item belongs has room, and no container is signed misc, other or overflow, puts it in a container with no sign at all (one already holding items first). Never in a container signed for something else.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Signs)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Says why the sorter skips or gives up on containers, in chat. Always written to the log at debug level.")
        .defaultValue(false)
        .build()
    );

    // Bot

    private final Setting<Boolean> stayOn = sgBot.add(new BoolSetting.Builder()
        .name("stay-on")
        .description("Stays on after sorting and keeps the stash sorted: sorts again whenever a container changes, and empties drop-off containers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> recheck = sgBot.add(new IntSetting.Builder()
        .name("recheck")
        .description("Seconds between checks of drop-off containers for new items.")
        .defaultValue(30)
        .range(5, 600)
        .sliderRange(5, 120)
        .visible(stayOn::get)
        .build()
    );

    // Ground

    private final Setting<Boolean> collectGround = sgGround.add(new BoolSetting.Builder()
        .name("collect-ground")
        .description("Between jobs, walks onto items other players left lying in the stash area (the containers plus the range below, 4 blocks up and down) and sorts them with the rest. Needs Baritone. Never items you dropped or threw, or that came from blocks you broke, only items that have lain still for 3 seconds, and only while no other player is loaded. Not in scan-only mode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> collectRange = sgGround.add(new IntSetting.Builder()
        .name("collect-range")
        .description("How many blocks around the outermost containers to pick items up from.")
        .defaultValue(8)
        .range(0, 64)
        .sliderRange(0, 32)
        .visible(collectGround::get)
        .build()
    );

    // Labels

    private final Setting<Boolean> autoLabel = sgLabels.add(new BoolSetting.Builder()
        .name("auto-label")
        .description("Signs mode: when items have no container signed for them, puts up a sign for them on an empty, unsigned chest next to the signed ones, using signs from your inventory or the stash. Never places or breaks a chest, and never breaks a sign.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> labelGap = sgLabels.add(new IntSetting.Builder()
        .name("label-gap")
        .description("Seconds to wait between putting up signs.")
        .defaultValue(10)
        .range(0, 300)
        .sliderRange(0, 60)
        .visible(autoLabel::get)
        .build()
    );

    // Watch

    private final Setting<Boolean> watch = sgWatch.add(new BoolSetting.Builder()
        .name("watch")
        .description("Notices when another player opens a container of the stash and closes it again, and reads it again after the delay below. Works while the module is off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> watchDelay = sgWatch.add(new IntSetting.Builder()
        .name("watch-delay")
        .description("Seconds a container has to stay closed after someone else used it before it's read again.")
        .defaultValue(5)
        .range(0, 60)
        .sliderRange(0, 60)
        .visible(watch::get)
        .build()
    );

    private final Setting<Boolean> watchSigns = sgWatch.add(new BoolSetting.Builder()
        .name("watch-signs")
        .description("Signs mode, while sorting: notices when the text of a container's sign changes, and sorts by the new text after the delay below. A sign that's gone for a minute counts as removed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> signDelay = sgWatch.add(new IntSetting.Builder()
        .name("sign-delay")
        .description("Seconds a sign has to be left alone before the stash is sorted by what it says now. The container isn't used meanwhile.")
        .defaultValue(10)
        .range(0, 60)
        .sliderRange(0, 60)
        .visible(watchSigns::get)
        .build()
    );

    // Shulkers

    private final Setting<Boolean> compact = sgShulkers.add(new BoolSetting.Builder()
        .name("compact")
        .description("Signs mode, once everything is sorted: packs a full shulker's worth of one item (27 full stacks, or two of the same gear piece and up) from the category chests into an empty unnamed shulker and files it in the item's chest, so 27 slots become 1. Never mixed shulkers, never a named one, never kit chests. Shulkers are put down beside you for it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> compactTopUpAt = sgShulkers.add(new IntSetting.Builder()
        .name("compact-top-up-at")
        .description("Tops up a part-full shulker of one item once this many full stacks of it are waiting.")
        .defaultValue(9)
        .range(1, 26)
        .sliderRange(1, 26)
        .visible(compact::get)
        .build()
    );

    private final Setting<List<Item>> compactExclude = sgShulkers.add(new ItemListSetting.Builder()
        .name("compact-exclude")
        .description("Items that are never compacted, like ender pearls.")
        .defaultValue(List.of())
        .visible(compact::get)
        .build()
    );

    private final Setting<Boolean> repack = sgShulkers.add(new BoolSetting.Builder()
        .name("repack")
        .description("Signs mode: takes items that belong elsewhere out of mixed shulkers, and packs loose items into shulkers that already hold only that item. Places the shulker beside you to do it, then breaks it and picks it back up. Named shulkers, and shulkers when a sign asks for them whole (like \"kits\"), are left as they are.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> newBulkAt = sgShulkers.add(new IntSetting.Builder()
        .name("new-bulk-at")
        .description("Starts a new bulk shulker from an empty one once carrying this many stacks of one item. 0 only tops up shulkers that already exist. Only used while compact is off, which makes new bulk shulkers itself.")
        .defaultValue(9)
        .range(0, 27)
        .sliderRange(0, 27)
        .visible(repack::get)
        .build()
    );

    // Kits

    private final Setting<Boolean> buildKits = sgKits.add(new BoolSetting.Builder()
        .name("build-kits")
        .description("Signs mode: builds the kits saved below out of the stash, as many as it can. Takes an empty shulker, gathers the items, and fills it slot for slot with the saved counts, then files it in the chest signed with the kit's name (or \"<name> kit\"). A kit with no such chest isn't built. Uses every empty shulker in the stash.")
        .defaultValue(false)
        .build()
    );

    // Slots

    private final Setting<String> carrySlots = sgSlots.add(new StringSetting.Builder()
        .name("carry-slots")
        .description("Slots to carry stash items in, like \"28-36\" or \"1-3, 30\". 1-9 is the hotbar, 10-36 the inventory from top left. Blank uses every slot that's empty when sorting starts.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> keepSlots = sgSlots.add(new StringSetting.Builder()
        .name("keep-slots")
        .description("Slots never to touch, even when they're empty, in the same format as carry-slots.")
        .defaultValue("")
        .build()
    );

    // Search

    private final Setting<Boolean> whisperSearch = sgSearch.add(new BoolSetting.Builder()
        .name("whisper-search")
        .description("Lets whitelisted players whisper you to search the stash. You whisper back where it is. Works while the module is off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> whitelist = sgSearch.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Players allowed to search the stash by whisper.")
        .visible(whisperSearch::get)
        .build()
    );

    private final Setting<String> prefix = sgSearch.add(new StringSetting.Builder()
        .name("prefix")
        .description("Whispers starting with this are searches, like \"find diamonds\". Blank treats every whisper from the whitelist as a search.")
        .defaultValue("find")
        .visible(whisperSearch::get)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the container being worked on, and excluded and drop-off containers while picking or sorting.")
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

    private final Setting<SettingColor> areaColor = sgRender.add(new ColorSetting.Builder()
        .name("area-color")
        .description("The outline of the stash perimeter, drawn while stash-sorter or stash-overlay is on.")
        .defaultValue(new SettingColor(80, 255, 120, 255))
        .build()
    );

    private final Setting<SettingColor> workColor = sgRender.add(new ColorSetting.Builder()
        .name("work-color")
        .description("The container being worked on.")
        .defaultValue(new SettingColor(255, 170, 0, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> excludedColor = sgRender.add(new ColorSetting.Builder()
        .name("excluded-color")
        .description("Containers excluded from sorting.")
        .defaultValue(new SettingColor(255, 50, 50, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> compactSkipColor = sgRender.add(new ColorSetting.Builder()
        .name("compact-skip-color")
        .description("Containers never used for compacting.")
        .defaultValue(new SettingColor(180, 100, 255, 60))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> dropOffColor = sgRender.add(new ColorSetting.Builder()
        .name("drop-off-color")
        .description("Drop-off containers.")
        .defaultValue(new SettingColor(50, 150, 255, 60))
        .visible(render::get)
        .build()
    );

    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TIMEOUT = 20 * 4;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int PATH_GRACE_TICKS = 10;
    // Whispers back are spaced out so the server doesn't kick us for spam
    private static final int REPLY_GAP_TICKS = 20 * 3;

    private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    // Baritone is optional, BaritoneHelper must only be touched when it's installed
    private static final boolean BARITONE = classExists("baritone.api.BaritoneAPI");

    private static final Pattern[] WHISPER_FORMATS = {
        Pattern.compile("^(\\w{3,16}) whispers(?: to you)?: (.+)$"),
        Pattern.compile("^From (\\w{3,16}): (.+)$"),
        Pattern.compile("^\\[(\\w{3,16}) -> (?:me|you)] (.+)$"),
        Pattern.compile("^(\\w{3,16}) -> (?:me|you): (.+)$")
    };

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact, KitTake, KitUnpack, KitFill, FetchSigns, Label, Gather, Ground, Explore, Unbundle }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up
    private enum Handle { Place, Open, Work, Break, Collect, Return, Leave }

    // Giving way: running, letting go of what is ours, then waiting for the others to finish and be quiet
    private enum Gate { Running, Yielding, Waiting }

    private static final int MAX_JOB_INTERRUPTIONS = 5;
    private static final int CURSOR_TRIES = 40;

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 120;
    private static final int PLACE_GAP_TICKS = 20 * 5;
    private static final int PAUSE_TICKS = 20 * 60 * 3;

    private Stash stash;
    private String triedKey;
    private final Set<Integer> carry = new LinkedHashSet<>();

    // Tiers mode: which boxes each tier fills
    private final Map<Integer, List<Box>> tierBoxes = new HashMap<>();

    private Job job;
    private Box target;
    private Step step;
    private int stepTicks;
    private int wait;
    private int swapStep;
    private int swapCarry, swapSlot;
    private int recoveries;
    private ItemStack swapStack = ItemStack.EMPTY, swapDisplaced = ItemStack.EMPTY;
    private boolean usedBaritone;
    // How often a walk to a container ended with our goal gone from Baritone
    private final Map<Box, Integer> cancelRetries = new HashMap<>();

    // Giving way to the player and other modules
    private Gate gate = Gate.Running;
    private int ticks;
    private int lastOwnAct = -100;
    private int cleanupTries;
    private int jobInterruptions;
    private Handle returnTo;
    // The container window the sorter opened, and the Baritone goal it set (an Object so Baritone's classes are only
    // touched when it's installed), with when it was set and let go of
    private AbstractContainerMenu ownedMenu;
    private Object ourGoal;
    private int goalTick = -100, releaseTick = -100;
    // Set while the sorter presses keys itself
    private boolean foreignNow;

    // Putting up signs for items nothing is signed for
    private final StashLabeller labeller = new StashLabeller(new StashLabeller.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public Set<Integer> keep() {
            return parseSlots(keepSlots.get());
        }

        @Override
        public List<Integer> activeCarried() {
            return StashSorter.this.activeCarried();
        }

        @Override
        public List<Integer> carriedSlots() {
            return StashSorter.this.carriedSlots();
        }

        @Override
        public List<Box> destinationsFor(ItemStack stack) {
            return StashSorter.this.destinationsFor(stack);
        }

        @Override
        public boolean isMisc(Box box) {
            return StashSorter.this.isMisc(box);
        }

        @Override
        public boolean mixedShulker(ItemStack stack) {
            return StashSorter.mixedShulker(stack);
        }

        @Override
        public boolean keepWholeExists() {
            return !keepWholeBoxes().isEmpty();
        }

        @Override
        public int misplacedCount(Box box) {
            return StashSorter.this.misplacedCount(box);
        }

        @Override
        public boolean signPending(Box box) {
            return StashSorter.this.signPending(box);
        }

        @Override
        public boolean rereadPending(Box box) {
            return staleBoxes.contains(box) || deferredAccess.containsKey(box);
        }

        @Override
        public boolean fetchBlocked(Box box) {
            return blocked(box, Job.FetchSigns);
        }

        @Override
        public Box nearest(List<Box> boxes) {
            return StashSorter.this.nearest(boxes);
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public boolean autoLabel() {
            return autoLabel.get();
        }

        @Override
        public boolean signsMode() {
            return mode.get() == Mode.Signs;
        }

        @Override
        public int gapTicks() {
            return labelGap.get() * 20;
        }

        @Override
        public boolean walk() {
            return walkOn();
        }

        @Override
        public boolean rotate() {
            return rotate.get();
        }

        @Override
        public int delay() {
            return delay.get();
        }

        @Override
        public int clock() {
            return clock;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean running() {
            return gate == Gate.Running;
        }

        @Override
        public boolean foreign() {
            return foreignNow;
        }

        @Override
        public void beginLabel(Box target) {
            job = Job.Label;
            StashSorter.this.target = target;
            step = Step.Walking;
            handle = null;
            actions = 0;
            jobCounted = false;
            stepTicks = 0;
            jobInterruptions = 0;
            startSigSet = endSigSet = false;
            settled = false;
            openPos = null;
            openMenuBefore = null;
        }

        @Override
        public void endLabel() {
            job = null;
            StashSorter.this.target = null;
            wait = delay.get();
        }

        @Override
        public boolean fetchSigns(Box from) {
            return start(Job.FetchSigns, from);
        }

        @Override
        public void walkTo(BlockPos pos) {
            if (!BARITONE) return;
            walkNear(pos, 0);
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public boolean moving() {
            return BARITONE && BaritoneHelper.isMoving();
        }

        @Override
        public boolean leftArea() {
            return StashSorter.this.leftArea();
        }

        @Override
        public String blockName(Box box) {
            return StashSorter.this.blockName(box);
        }

        @Override
        public void pause(int ticks) {
            wait = ticks;
        }

        @Override
        public void closeOurWindow() {
            if (mc.player.containerMenu != mc.player.inventoryMenu) StashSorter.this.closeOurWindow(mc.player.containerMenu);
        }

        @Override
        public boolean containerOpen() {
            return mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu);
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void labelled(Box box) {
            stash.markDirty();
            stash.save();
            version++;
            seenVersion = version;
            clearStalled();
            stuckCarried.clear();
            stuckWarned = false;
            stashWatch.settleNow(box);
            idle = false;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    private int openClickTick = -100;
    private static final int OPEN_ANSWER_TICKS = 20 * 5;
    private final StashYield yielder = new StashYield(new StashYield.Owner() {
        @Override
        public boolean ourMenu(AbstractContainerMenu menu) {
            return StashSorter.this.ourMenu(menu);
        }

        @Override
        public boolean baritoneForeign() {
            if (!BARITONE || ticks - goalTick <= 3 || ticks - releaseTick <= 3) return false;
            return BaritoneHelper.controlledByOther((baritone.api.pathing.goals.Goal) ourGoal);
        }

        @Override
        public boolean baritoneActive() {
            return BARITONE && BaritoneHelper.isActive();
        }

        @Override
        public boolean drivingInput() {
            return labeller.drivingInput() || ledgeSneaking;
        }

        @Override
        public boolean justReleased() {
            return ticks - releaseTick <= 2;
        }

        @Override
        public boolean walkingOurs() {
            return ourGoal != null;
        }

        @Override
        public boolean ownInventoryActivity() {
            return handle != null || ticks - lastOwnAct < 20;
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int quietSeconds() {
            return quiet.get();
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }
    });
    private int moved;
    // Containers read by surveys since the last summary, for scan-only mode
    private int scanned;

    // The stash perimeter: chunk columns seen loaded since it was set, the ones that couldn't be reached, and the trip in
    // hand. Walking inside it is allowed, and the sorter explores it so its chunks load
    private final LongOpenHashSet exploredChunks = new LongOpenHashSet();
    private final LongOpenHashSet exploreSkipped = new LongOpenHashSet();
    private boolean exploring, exploreRan, areaDirty;
    private int exploreRunStart;
    private static final int EXPLORE_RUN_LIMIT = 20 * 60 * 30;
    private int exploreStanding;
    private long exploreTarget;
    private int exploreX, exploreZ;
    private int exploreTicks, exploreIssue, exploreBestTick;
    private double exploreBest;
    private boolean walkInside, baritoneWarned;
    // Containers already reported as impossible to reach without breaking or placing blocks
    private final Set<BlockPos> unreachableReported = new HashSet<>();
    private static final int EXPLORE_STANDING = 40;
    private static final int EXPLORE_TIMEOUT = 20 * 40;
    private static final int EXPLORE_STUCK = 20 * 15;
    private static final int AREA_MARGIN = 2;

    // Picking up what other players left on the ground
    private final GroundCollector ground = new GroundCollector(new GroundCollector.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean enabled() {
            return groundOn();
        }

        @Override
        public int range() {
            return collectRange.get();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public boolean carries(int slot) {
            return carry.contains(slot);
        }

        @Override
        public int moveToCarry(int slot) {
            int free = StashSorter.this.freeCarrySlot();
            if (free == -1 || mc.screen != null || !mc.player.containerMenu.getCarried().isEmpty()) return -1;
            InvUtils.move().from(slot).to(free);
            lastOwnAct = ticks;
            yielder.inventoryChangedByUs();
            return free;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void walkTo(BlockPos pos) {
            if (!BARITONE) return;
            walkNear(pos, 0);
        }

        @Override
        public void releaseGoal() {
            StashSorter.this.releaseGoal();
        }

        @Override
        public void confine() {
            // Every walk of the sorter's confines Baritone itself, see walkNear
        }

        @Override
        public void unconfine() {
            if (BARITONE && ourGoal == null) BaritoneHelper.unconfine();
        }

        @Override
        public void finished(boolean pickedUp) {
            if (job == Job.Ground) job = null;
            wait = delay.get();
            lastOwnAct = ticks;
            if (pickedUp) {
                // What came in is put away like anything else carried
                idle = false;
                clearStalled();
                stuckCarried.clear();
                stuckWarned = false;
            }
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private final Set<Box> compacted = new HashSet<>();
    private int actions;

    // Loop protection. What the carry slots and the target container held when the job's window opened and just before
    // it closed, to tell whether the clicks changed anything
    private long startSig, endSig;
    private boolean startSigSet, endSigSet;
    // Consecutive jobs per container that clicked but ended before the result could be read, and containers that closed
    // on us this pass (said once)
    private final Map<Box, Integer> unconfirmedRuns = new HashMap<>();
    private final Set<Box> closedEarlyReported = new HashSet<>();
    // Jobs at each container since the backlog last reached a new low, the ones given up on for going back and forth, and
    // the ones already reported this pass
    private final Map<Box, Integer> boxVisits = new HashMap<>();
    private final Set<Box> circular = new HashSet<>();
    private final Set<Box> loopReported = new HashSet<>();
    private boolean jobCounted;

    // Watching containers and signs: other players' lids and sign text, read from the client's own world
    private final StashWatch stashWatch = new StashWatch();
    private static final int SIGN_POLL_TICKS = 10;
    private static final int LID_POLL_TICKS = 4;
    private static final int SIGN_SWEEP_TICKS = 20 * 30;
    private static final int OWN_TOUCH_TICKS = 20 * 10;
    private static final int REREAD_COOLDOWN_TICKS = 20 * 60;
    // When the sorter or the player last had each container open, and when the sorter last read it
    private final Map<Box, Integer> ownTouch = new HashMap<>();
    private final Map<Box, Integer> readAt = new HashMap<>();
    // Containers someone else used, to be read again between jobs, and the ones held back by the cooldown
    private final Set<Box> staleBoxes = new LinkedHashSet<>();
    private final Map<Box, Integer> deferredAccess = new HashMap<>();
    // Jobs ended because the container's sign is unsettled, which is no fault of the container
    private boolean signAbort;
    private final Set<Box> signAborted = new HashSet<>();
    private final Set<Box> signHeld = new HashSet<>();
    private final Map<Box, Integer> signAbortRuns = new HashMap<>();
    private final Set<Box> signAbortReported = new HashSet<>();
    private int nextSignSweep;
    // Jobs that opened a container and clicked nothing, with what the carry slots and the container held then: not
    // repeated until one of them changes, however often stalled is cleared meanwhile
    private final Map<String, Long> futile = new HashMap<>();
    private final Set<String> noOpReported = new HashSet<>();
    private String lastSummary;
    private int passJobs;
    private int passMin = Integer.MAX_VALUE;
    // Containers that could not be read when visited: not tried again until something changes
    private final Set<Box> failedSurvey = new HashSet<>();

    // Carried stacks nothing has room for right now: slot to item id, left out of the plans until something changes
    private final Map<Integer, String> stuckCarried = new HashMap<>();
    private boolean stuckWarned;

    // Carry slots the sorter filled itself, slot to the stack it put there. The player's own gear in a carry slot is
    // never touched unless it's in here
    private final Map<Integer, ItemStack> stashHeld = new HashMap<>();
    private net.minecraft.client.player.LocalPlayer lastPlayer;
    // When stuck gear was last warned about, and when the stuck stacks were last looked at again
    private static final int STUCK_REWARN = 20 * 60 * 3;
    private static final int STUCK_RECHECK = 20 * 5;
    private int lastStuckWarn = -STUCK_REWARN, lastStuckCheck, stuckCheckVersion;
    // Ticks to let the server answer the last click before the result is judged
    private static final int SETTLE_TICKS = 2;
    private boolean settled;

    // Repacking
    private int wantSlot = -1;
    private int handledSlot;
    private BulkKey fillKey;
    private Item mainItem;
    // Safe shulker handling: spots where a place failed, how many in a row, when the last was, a pause after problems,
    // and a shulker that could not be picked up (no more are put down)
    private final Set<BlockPos> failedSpots = new HashSet<>();
    private int placeFailures;
    private int lastPlaceTick = -1000;
    private int repackPausedUntil;
    private boolean dropLost;
    private boolean placedConfirmed;
    private int placedStacks;
    private int breakTicks;
    private Predicate<ItemStack> fetchWants = s -> Utils.isShulker(s.getItem());
    private Handle handle;
    private int handleTicks;
    private BlockPos placed;
    // A hotbar slot a shulker was moved into, and the player's item that was in it, now in swapBackFrom
    private int swapBackFrom = -1, swapBackTo = -1;
    private ItemStack hotbarDisplaced = ItemStack.EMPTY;
    private boolean pickaxeSwapped;
    private int shulkersBefore;
    private final Set<Integer> shulkerSlotsBefore = new HashSet<>();
    private final Set<BulkKey> packTried = new HashSet<>();

    // Packing a full shulker's worth of one item
    private final Compactor compactor = new Compactor(new Compactor.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public boolean on() {
            return compactOn();
        }

        @Override
        public int topUpAt() {
            return compactTopUpAt.get();
        }

        @Override
        public boolean excluded(ItemStack stack) {
            return compactExclude.get().contains(stack.getItem());
        }

        @Override
        public List<Box> destinationsFor(ItemStack stack) {
            return StashSorter.this.destinationsFor(stack);
        }

        @Override
        public boolean isMisc(Box box) {
            return StashSorter.this.isMisc(box);
        }

        @Override
        public boolean isKitBox(Box box) {
            return StashSorter.this.isKitBox(box);
        }

        @Override
        public boolean isKitShulker(ItemStack stack) {
            return StashSorter.this.isKitShulker(stack);
        }

        @Override
        public boolean signPending(Box box) {
            return StashSorter.this.signPending(box);
        }

        @Override
        public boolean gatherBlocked(Box box) {
            return blocked(box, Job.Gather);
        }

        @Override
        public boolean fetchBlocked(Box box) {
            return blocked(box, Job.Fetch);
        }

        @Override
        public Box nearest(List<Box> boxes) {
            return StashSorter.this.nearest(boxes);
        }

        @Override
        public boolean closer(Box a, Box b) {
            return StashSorter.this.closer(a, b);
        }

        @Override
        public List<Integer> carriedSlots() {
            return StashSorter.this.carriedSlots();
        }

        @Override
        public List<Integer> activeCarried() {
            return StashSorter.this.activeCarried();
        }

        @Override
        public Set<Integer> carry() {
            return carry;
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean kitHolds(int slot) {
            return kitBuilder.holds(slot);
        }

        @Override
        public boolean fetch(Box box, int slot, Predicate<ItemStack> wants) {
            wantSlot = slot;
            fetchWants = wants;
            return start(Job.Fetch, box);
        }

        @Override
        public boolean gather(Box box) {
            return start(Job.Gather, box);
        }

        @Override
        public boolean pack(Compactor.Plan plan) {
            if (plan.placedPos != null) {
                startHandling(Job.Fill, -1, plan.key);
                placed = plan.placedPos;
                placedConfirmed = true;
                shulkersBefore = countShulkers() + 1;
                handle = Handle.Return;
                returnTo = Handle.Open;
                return true;
            }
            int slot = compactor.findPlanShulker();
            if (slot < 0) {
                compactor.abort("the shulker is missing");
                return compactor.next();
            }
            return startHandling(Job.Fill, slot, plan.key);
        }

        @Override
        public boolean recover(Compactor.Plan plan, BlockPos at) {
            startHandling(Job.Fill, -1, plan.key);
            placed = at;
            placedConfirmed = true;
            handle = Handle.Collect;
            shulkersBefore = countShulkers() + 1;
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            return true;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public void moved() {
            moved++;
        }

        @Override
        public void headway() {
            // Real headway: the circle watchdog starts over
            passMin = Integer.MAX_VALUE;
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
            clearStalled();
        }

        @Override
        public void clearOrphan(BlockPos at) {
            StashSorter.this.clearOrphan(at);
        }

        @Override
        public void progress(BlockPos at, int stacks) {
            Stash.Orphan old = stash.orphans.stream().filter(o -> o.pos.equals(at)).findFirst().orElse(null);
            recordOrphan(at, old != null ? old.item : placedName(), stacks);
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public boolean dropOffsBusy() {
            return stash.boxes.stream().anyMatch(b -> b.dropOff && b.known() && !b.unreachable && b.free() < b.contents.length);
        }

        @Override
        public int ticks() {
            return ticks;
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }

        @Override
        public void alert(String text) {
            error("%s", text);
        }

        @Override
        public void debug(String text) {
            StashSorter.this.debug("%s", text);
        }
    });

    // Building kits
    private final KitBuilder kitBuilder = new KitBuilder(new KitBuilder.Owner() {
        @Override
        public Stash stash() {
            return stash;
        }

        @Override
        public Set<Integer> carry() {
            return ownedCarry();
        }

        @Override
        public int freeCarrySlot() {
            return StashSorter.this.freeCarrySlot();
        }

        @Override
        public int freeCarryCount() {
            return StashSorter.this.freeCarryCount();
        }

        @Override
        public boolean usable(Box box) {
            return !blocked(box, Job.Take) && !signPending(box) && !box.unreachable;
        }

        @Override
        public void held(int slot, ItemStack stack) {
            stashHeld.put(slot, stack.copy());
        }

        @Override
        public boolean keepWhole(Box box) {
            return box.labels.stream().anyMatch(Categories::isKeepWhole);
        }

        @Override
        public double distance(Box box) {
            return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(nearestPart(box)));
        }

        @Override
        public void say(String text) {
            info("%s", text);
        }

        @Override
        public void warn(String text) {
            warning("%s", text);
        }
    });
    private int collectedSlot = -1;
    private int seenKits;

    // Bot mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;
    private int version;

    // Containers the player opens themselves
    private AbstractContainerMenu trackedMenu;
    private Box trackedBox;

    // The last block interact sent, so a menu is only filed under the box it was really opened from
    private static final int INTERACT_WINDOW = 20 * 5;
    private int clock;
    private BlockPos interactPos;
    private AbstractContainerMenu interactMenu;
    private int interactClock;

    // The sorter's own open click, same idea
    private BlockPos openPos;
    private AbstractContainerMenu openMenuBefore;

    private final Queue<String> replies = new ArrayDeque<>();
    private int replyCooldown;

    public StashSorter() {
        super(QwerteAddon.CATEGORY, "stash-sorter", "Sorts a saved stash by tier or by sign, keeps it sorted, and lets you search it.");

        // Always listening: keeping the stash up to date, picking containers and whisper searches all work with the
        // module off
        MeteorClient.EVENT_BUS.subscribe(new Tracker());
        RightClick.listen(this::onRightClick);
    }

    // Turning on and off

    @Override
    public void onActivate() {
        job = null;
        target = null;
        breakRetries = 0;
        brokenSince = -1;
        moved = 0;
        wait = 0;
        idle = false;
        stalled.clear();
        compacted.clear();
        resetLoopState();
        yielder.reset();
        gate = Gate.Running;
        ownedMenu = null;
        ourGoal = null;
        if (BARITONE) BaritoneHelper.unconfine();
        jobInterruptions = 0;
        returnTo = null;
        tierBoxes.clear();
        tiesReported.clear();
        handle = null;
        packTried.clear();
        compactor.reset();
        failedSpots.clear();
        placeFailures = 0;
        lastPlaceTick = -1000;
        repackPausedUntil = 0;
        dropLost = false;
        placedConfirmed = false;
        kitBuilder.reset();
        collectedSlot = -1;
        scanned = 0;
        unbundleBlocked.clear();
        ground.abort();

        // Scan-only never carries anything, so there are no slots to find
        if (sortItems.get()) {
            if (!pickCarrySlots()) {
                toggle();
                return;
            }
        }
        else {
            carry.clear();
            pruneHeld();
        }

        boolean existed = ensureStash() != null;
        if (stash == null) stash = new Stash(stashName.get(), Stash.currentServer());

        int added = addNearbyBoxes();
        labeller.reset();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            box.labelFailed = false;
            readSigns(box);

            // A sign somebody else has changed since is theirs now
            Containers.Signs signs = Containers.read(stash, box);
            if (box.autoLabel != null && signs != null && !signs.labels().contains(box.autoLabel)) {
                box.autoLabel = null;
                box.autoSignPos = null;
                stash.markDirty();
            }
        }
        // Everything given up on or unexplored before gets another try
        forgetExploration();
        orderBoxes();
        checkOrphans();

        // With a perimeter the containers come from exploring it, and the checks wait until that is done
        if (!stash.hasArea() && !checkStash()) return;

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!sortItems.get()) info("Stash \"%s\": %d containers, scan only: reading %d unknown or stale ones, then watching for changes.", stash.name, stash.boxes.size(), unknown);
        else if (!existed) info("New stash \"%s\": scanning %d containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0) info("Stash \"%s\": %d containers, scanning %d new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"%s\": %d containers, sorting by %s.", stash.name, stash.boxes.size(), mode.get() == Mode.Tiers ? "tier" : "sign");

        if (added > 0) stash.save();
    }

    @Override
    public void onDeactivate() {
        // Only a window of ours, never one the player opened
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu)) {
            if (!mc.player.containerMenu.getCarried().isEmpty()) recoverCursor();
            closeOurWindow(mc.player.containerMenu);
        }
        ownedMenu = null;
        gate = Gate.Running;
        labeller.deactivate();
        ledgeGuard(false);
        ground.abort();
        // Emptying a bundle: whatever is on the cursor goes into a carry slot
        if (job == Job.Unbundle && mc.player != null) finishUnbundle();

        if (usedBaritone) {
            releaseGoal();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        reportPlacedShulker();
        restoreHotbar();
        handle = null;
        kitBuilder.deactivate();

        if (stash != null && stash.isDirty()) stash.save();

        if (mc.player != null && !carriedSlots().isEmpty()) {
            warning("Stopped while carrying %d stacks from the stash, they're in your inventory.", carriedSlots().size());
        }

        job = null;
        target = null;
        if (mc.player != null) pruneHeld();
    }

    /**
     * Works out which slots to carry stash items in, from the settings or, by default, every empty slot.
     */
    private boolean pickCarrySlots() {
        carry.clear();
        pruneHeld();
        Set<Integer> keep = parseSlots(keepSlots.get());
        Set<Integer> chosen = parseSlots(carrySlots.get());
        boolean designated = !carrySlots.get().isBlank();

        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            if (designated && !chosen.contains(i)) continue;
            // Stacks from the stash still in the inventory from before (sorting turned off and on) are carried again, so
            // they are put away and not left behind as if they were the player's own
            ItemStack there = mc.player.getInventory().getItem(i);
            ItemStack held = stashHeld.get(i);
            if (there.isEmpty() || (held != null && ItemStack.matches(there, held))) carry.add(i);
        }

        if (designated && carry.size() < chosen.size()) {
            warning("%d of your carry-slots have items in them, they'll be left alone.", chosen.size() - carry.size());
        }

        if (carry.isEmpty()) {
            error(designated ? "Your carry-slots are all full. Empty at least one of them." : "Empty at least one inventory slot to carry items in.");
            return false;
        }
        return true;
    }

    /**
     * "1-9, 28-36" into inventory indexes. 1-9 is the hotbar and 10-36 the main inventory, matching index + 1.
     */
    private static Set<Integer> parseSlots(String text) {
        Set<Integer> slots = new HashSet<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                String[] range = part.split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = Math.max(1, Math.min(from, to)); n <= Math.min(36, Math.max(from, to)); n++) slots.add(n - 1);
            } catch (NumberFormatException ignored) {
                // Skip anything that isn't a number or range
            }
        }
        return slots;
    }

    // The sorting loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (stash == null) {
            toggle();
            return;
        }
        ticks++;
        if (handle != null) lastOwnAct = ticks;

        // What else is using the client, every tick: nothing of ours goes on while it's anything
        StashYield.Reason foreign = yielder.check();
        foreignNow = foreign != null;
        // The sorter has the controls while it walks or works; a ledge with a drop gets a sneak, which is let go of otherwise
        ledgeGuard(gate == Gate.Running && foreign == null && standingWork());
        switch (gate) {
            case Yielding -> {
                if (cleanupStep()) gate = Gate.Waiting;
                return;
            }
            case Waiting -> {
                if (!tickWaiting(foreign)) return;
            }
            case Running -> {
                // Stay-on mode with nothing to do holds nothing of anyone's, so there's nothing to give way
                if (foreign != null && !restingIdle() && !(job == Job.Label && labeller.critical())) {
                    beginYield(foreign);
                    return;
                }
            }
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null) {
            // Never start while a container is open, it might be one the player is using
            if (mc.player.containerMenu != mc.player.inventoryMenu) return;
            pruneHeld();
            remindStuck();
            releaseDeferred();
            applyStale();
            applySignChanges();
            // The perimeter changed while a job was under way: containers outside it go, the ones inside come in
            if (areaDirty) applyArea();
            // Chunks of the perimeter that loaded since, with containers in them
            if (ticks % 100 == 0 && stash.hasArea()) {
                int added = scanArea();
                // Containers that are gone, where the chunk is loaded to tell
                boolean gone = stash.boxes.removeIf(box -> mc.level.isLoaded(box.pos) && !isContainer(box.pos));
                if (gone) stash.markDirty();
                if (added > 0 || gone) {
                    version++;
                    idle = false;
                }
            }
            // Scan-only was switched off: there are carry slots to find now
            if (sortItems.get() && carry.isEmpty()) {
                if (!pickCarrySlots()) {
                    toggle();
                    return;
                }
                idle = false;
            }
            if (idle && !wakeUp()) {
                // Resting: only something on the ground can still start a job. Looked at every half second, and only
                // when nobody else is busy
                if (ticks % 10 != 0 || foreign != null || !yielder.quietElapsed() || !startGround()) return;
            }
            else {
                // Nothing is started while someone else is busy, nor until they have been quiet for a while
                if (foreign != null || !yielder.quietElapsed()) return;
                if (!nextJob()) return;
            }
        }

        if (handle != null) {
            tickHandling();
            return;
        }
        if (job == Job.Ground) {
            ground.tick();
            return;
        }
        if (job == Job.Explore) {
            exploreTick();
            return;
        }
        if (job == Job.Unbundle) {
            unbundleTick();
            return;
        }
        if (job == Job.Label) {
            labeller.tick();
            return;
        }
        stepTicks++;

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
    }

    // Giving way

    /**
     * Whether the sorter is in the middle of a job, so other modules can keep out of its way. False when it's off, resting
     * in stay-on mode, between jobs, or giving way to someone else.
     */
    public boolean busyWithJob() {
        // Between jobs, and while waiting for others, it holds nothing: whoever is working finishes its step first
        return isActive() && gate == Gate.Running && (job != null || handle != null);
    }

    private boolean restingIdle() {
        return idle && job == null && handle == null;
    }

    // Picking up from the ground

    /**
     * Collecting from the ground is on and possible: the setting, not scan-only, Baritone there, the module running.
     */
    private boolean groundOn() {
        return isActive() && sortItems.get() && collectGround.get() && BARITONE;
    }

    /**
     * Between jobs, with nothing else to do: starts a trip to something lying on the ground. Never while a shulker is
     * standing in the world, a kit or a compaction is in hand, or a container is open.
     * @return true when a trip has begun
     */
    private boolean startGround() {
        if (job != null || handle != null || placed != null || gate != Gate.Running) return false;
        if (compactor.active() || kitBuilder.active() || mc.player.containerMenu != mc.player.inventoryMenu) return false;
        if (!mc.player.containerMenu.getCarried().isEmpty() || mc.screen != null) return false;
        if (!ground.next()) return false;

        job = Job.Ground;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        return true;
    }

    /**
     * The menu is a container window the sorter opened, or is in the middle of opening.
     */
    private boolean ourMenu(AbstractContainerMenu menu) {
        if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) return false;
        if (menu == ownedMenu) return true;
        if (job == null || job == Job.Ground || job == Job.Explore || job == Job.Unbundle) return false;
        // A chest that opens while a sign is going up is the click's doing, and ours to close
        if (job == Job.Label) return labeller.ownsChest(menu);
        if (handle != null) return handle == Handle.Open && menu instanceof ShulkerBoxMenu;
        // Only a menu of this box's size that opened soon after our own click on it: never a chest screen the player opened
        return step == Step.Opening && openPos != null && ticks - openClickTick <= OPEN_ANSWER_TICKS && menu != openMenuBefore
            && menu instanceof ChestMenu && sizedForTarget(menu);
    }

    /**
     * Closes one of our windows without taking a screen of the player's down with it: with another screen on top, the
     * server is told and the menu let go of, and the screen stays.
     */
    private void closeOurWindow(AbstractContainerMenu menu) {
        boolean ourScreen = mc.screen == null || (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu);
        if (ourScreen) mc.player.closeContainer();
        else {
            mc.player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
            mc.player.containerMenu = mc.player.inventoryMenu;
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        yielder.inventoryChangedByUs();
    }

    /**
     * Lets go of the Baritone goal we set, and only that one: never cancels the player's own paths.
     */
    private void releaseGoal() {
        if (BARITONE && ourGoal != null) BaritoneHelper.releaseOurs((baritone.api.pathing.goals.Goal) ourGoal);
        ourGoal = null;
        releaseTick = ticks;
        // Baritone gets its break and place settings back the moment our walk is over
        if (BARITONE) BaritoneHelper.unconfine();
    }

    private void setGoal(Object goal) {
        ourGoal = goal;
        goalTick = ticks;
    }

    /**
     * Walks to within {@code range} blocks of a position with our own Baritone goal. Every walk the sorter makes goes
     * through here, with Baritone not allowed to break or place blocks for as long as the goal is ours: it never
     * digs or bridges its way to a destination. {@link #releaseGoal()} gives the settings back.
     */
    private void walkNear(BlockPos pos, int range) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null) walkInside = stash != null && stash.hasArea() && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        setGoal(BaritoneHelper.moveNear(pos, range));
    }

    /**
     * The same walk to a column, at any height: for places whose chunk isn't loaded yet.
     */
    private void walkXZ(int x, int z) {
        if (!BARITONE) return;
        BaritoneHelper.protect(List.of());
        usedBaritone = true;
        BaritoneHelper.confine(safeWalk.get());
        if (ourGoal == null) walkInside = stash != null && stash.hasArea() && stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
        setGoal(BaritoneHelper.moveXZ(x, z));
    }

    /**
     * Our walk has left the stash area it started in (plus the margin), by a way that is not to be taken.
     */
    private boolean leftArea() {
        return ourGoal != null && walkInside && stash != null && stash.hasArea()
            && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN);
    }

    // Ledge guard

    private boolean ledgeSneaking;

    /**
     * The sorter is standing still at its work with a window open or a click in the inventory: not while Baritone walks,
     * and never at the clicks that open or use something in the world (the container, the shulker, the sign), which
     * are made with the key up.
     */
    private boolean standingWork() {
        if (ourGoal != null) return false;
        if (handle != null) return handle == Handle.Work;
        if (job == null) return false;
        return switch (job) {
            case Unbundle -> true;
            case Label, Ground, Explore -> false;
            default -> step == Step.Working;
        };
    }

    /**
     * While the sorter has the controls (walking, or standing working), sneaks at the edge of a block when the drop on
     * that side is two blocks or more, so a push or a slip can't take it off. Let go as soon as the feet are 0.3 blocks
     * or more from every such edge, or the sorter doesn't have the controls: the key goes back to how it is physically.
     */
    private void ledgeGuard(boolean controlling) {
        boolean want = controlling && safeWalk.get() && mc.player != null && mc.level != null && nearDrop();
        KeyMapping key = mc.options.keyShift;
        if (want && !ledgeSneaking) {
            ledgeSneaking = true;
            key.setDown(true);
        }
        else if (!want && ledgeSneaking) {
            ledgeSneaking = false;
            key.setDown(Input.isPressed(key));
        }
    }

    /**
     * The feet are within 0.3 blocks of an edge of the block stood on, with a drop of 2 or more blocks beyond it: no solid
     * block in the two cells below the neighbouring cell (the cell beside the feet). Corners are checked diagonally.
     */
    private boolean nearDrop() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.getAbilities().flying) return false;

        BlockPos feet = mc.player.blockPosition();
        double fx = mc.player.getX() - feet.getX(), fz = mc.player.getZ() - feet.getZ();
        int dx = fx < LEDGE ? -1 : fx > 1 - LEDGE ? 1 : 0;
        int dz = fz < LEDGE ? -1 : fz > 1 - LEDGE ? 1 : 0;
        if (dx == 0 && dz == 0) return false;

        if (dx != 0 && drops(feet.offset(dx, 0, 0))) return true;
        if (dz != 0 && drops(feet.offset(0, 0, dz))) return true;
        return dx != 0 && dz != 0 && drops(feet.offset(dx, 0, dz));
    }

    private static final double LEDGE = 0.3;

    private boolean drops(BlockPos beside) {
        return !solid(beside.below()) && !solid(beside.below(2));
    }

    private boolean solid(BlockPos pos) {
        // What isn't loaded is no drop to guard against
        return !mc.level.isLoaded(pos) || !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /**
     * The sorter may walk: Baritone is there, and the walk setting is on or a perimeter is set (which implies it).
     */
    private boolean walkOn() {
        return BARITONE && (walk.get() || (stash != null && stash.hasArea()));
    }

    /**
     * Someone else started something. Everything of ours is let go of first: the path, the window, the cursor. The job
     * stays, and is picked up again once they have finished and been quiet for a while.
     */
    private void beginYield(StashYield.Reason reason) {
        gate = Gate.Yielding;
        cleanupTries = 0;
        wait = 0;
        releaseGoal();
        ledgeGuard(false);
        if (job == Job.Label) labeller.yielded();
        if (job != null) {
            yielder.interrupted();
            jobInterruptions++;
        }
        // A trip to the ground is simply dropped, the item is looked at again later
        if (job == Job.Ground) {
            ground.abort();
            job = null;
        }
        // Exploring carries on from the chunks still unseen
        if (job == Job.Explore || job == Job.Unbundle) job = null;
        if (stash.isDirty()) stash.save();
        yielder.startWaiting(reason);
    }

    /**
     * One step of letting go: the stack on the cursor is put away, then our own window is closed.
     * @return true when nothing of ours is left to let go of
     */
    private boolean cleanupStep() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu && ourMenu(menu)) {
            // Never closed with something on the cursor, it would be merged into the inventory or dropped. After a few
            // tries it is closed anyway, so the others are not kept waiting for a click that never works
            if (!menu.getCarried().isEmpty() && cleanupTries < CURSOR_TRIES) {
                if (cleanupTries++ % 4 == 0) recoverCursor();
                return false;
            }
            closeOurWindow(menu);
            return false;
        }
        ownedMenu = null;
        return true;
    }

    /**
     * Letting go is done: waiting for them to finish, and then to be quiet for a while.
     * @return true when waiting is over and sorting carries on this tick
     */
    private boolean tickWaiting(StashYield.Reason foreign) {
        // A late answer to our own click: a window of ours opened while we were waiting
        if (mc.player.containerMenu != mc.player.inventoryMenu && ourMenu(mc.player.containerMenu)) {
            cleanupStep();
            return false;
        }
        if (foreign != null || !yielder.quietElapsed()) return false;

        // A stack left on the cursor with no screen open (the server doesn't always hand it back) goes into a free slot
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            if (mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && cleanupTries++ % 4 == 0) returnCursor();
            return false;
        }

        yielder.endWaiting();
        gate = Gate.Running;
        yielder.inventoryChangedByUs();
        resumeJob();
        return true;
    }

    /**
     * Picks up where we left off. A job on a container is dropped and planned again: the world has changed under it,
     * and the containers are read as they are now. It isn't counted as getting nothing done. Repacking has a shulker in
     * the world, so it carries on from the same step.
     */
    private void resumeJob() {
        wait = delay.get();
        if (job == null) return;
        if (job == Job.Label) {
            labeller.resume();
            return;
        }
        if (handle != null) {
            resumeHandle();
            return;
        }

        if (job == Job.Compact && target != null) compacted.remove(target);
        if (jobCounted && target != null) {
            passJobs = Math.max(0, passJobs - 1);
            boxVisits.merge(target, -1, Integer::sum);
        }
        job = null;
        target = null;
        openPos = null;
        openMenuBefore = null;
        swapStep = 0;
        recoveries = 0;
        startSigSet = endSigSet = false;
        settled = false;
        jobInterruptions = 0;
    }

    private void resumeHandle() {
        boolean inWorld = placed != null && !mc.level.getBlockState(placed).isAir();

        // Interrupted over and over before anything was put down: plan something else
        if (jobInterruptions > MAX_JOB_INTERRUPTIONS && !inWorld) {
            endHandling();
            jobInterruptions = 0;
            return;
        }

        // The window was closed on the way: open it again
        Handle next = handle == Handle.Work ? Handle.Open : handle;
        if (!placedConfirmed && next != Handle.Place && next != Handle.Open) placedConfirmed = placed != null;
        if (inWorld && (next == Handle.Open || next == Handle.Break) && !inReachOf(placed)) {
            returnTo = next;
            next = Handle.Return;
        }
        handle = next;
        handleTicks = 0;
    }

    /**
     * Back to the shulker we put down, if we were moved away from it.
     */
    private void handleReturn() {
        if (placed == null || mc.level.getBlockState(placed).isAir() || inReachOf(placed)) {
            releaseGoal();
            handle = returnTo != null ? returnTo : Handle.Open;
            returnTo = null;
            handleTicks = 0;
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        if (!walkOn() || handleTicks > WALK_TIMEOUT) {
            abandonShulker("Couldn't get back to the shulker I put down at " + placed.toShortString() + ".");
            return;
        }

        boolean idleNow = handleTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        if (handleTicks == 1 || (idleNow && handleTicks % 40 == 0)) {
            walkNear(placed, 2);
        }
    }

    /**
     * Puts a stack left on the cursor, with no window open, into a free slot of the inventory.
     */
    private void returnCursor() {
        ItemStack cursor = mc.player.containerMenu.getCarried();
        Set<Integer> keep = parseSlots(keepSlots.get());

        // The carry slots first, so the stack is put away with the rest; any other free slot only if there is none
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < 36; slot++) {
                if (keep.contains(slot) || !mc.player.getInventory().getItem(slot).isEmpty()) continue;
                if ((pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, cursor.copy());
                InvUtils.click().slotId(slot < 9 ? 36 + slot : slot);
                lastOwnAct = ticks;
                return;
            }
        }
    }

    private boolean inReachOf(BlockPos pos) {
        return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach.get() * reach.get();
    }

    /**
     * Gear, food and the like that came out of the stash and has nowhere to go looks just like the player's own, so it's
     * mentioned again every few minutes for as long as it's stuck.
     */
    private void remindStuck() {
        if (stuckCarried.isEmpty() || clock - lastStuckWarn < STUCK_REWARN) return;

        int own = 0;
        for (int slot : stuckCarried.keySet()) if (ownKind(mc.player.getInventory().getItem(slot))) own++;
        if (own == 0) return;

        lastStuckWarn = clock;
        warning("Still carrying %d stacks of gear or food from the stash that have nowhere to go, they're in your inventory.", own);
    }

    /**
     * Bot mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        // Drop-offs are only emptied when sorting; scan-only learns of their changes from the lids
        if (sortItems.get() && idleTicks >= recheck.get() * 20) {
            idleTicks = 0;
            boolean any = false;
            for (Box box : stash.boxes) {
                if (box.dropOff && !box.unreachable) {
                    box.contents = null;
                    any = true;
                }
            }
            if (any) {
                idle = false;
                return true;
            }
        }

        if (Kits.version() != seenKits) {
            seenKits = Kits.version();
            version++;
        }

        if (version != seenVersion) {
            seenVersion = version;
            clearStalled();
            compacted.clear();
            packTried.clear();
            futile.clear();
            failedSurvey.clear();
            unconfirmedRuns.clear();
            stuckCarried.clear();
            stuckWarned = false;
            kitBuilder.wake();
            idle = false;
            return true;
        }

        return false;
    }

    /**
     * Picks the next job. Read every container we don't know yet, then put away what we're carrying, then pick up
     * what's in the wrong place.
     * @return false when there's nothing left to do
     */
    private boolean nextJob() {
        try {
            return pickJob();
        } finally {
            destCache = null;
            misplacedCache = null;
        }
    }

    /**
     * Per-planning-pass memos, only set while nextJob is choosing. Nothing that destinationsFor or misplacedCount read
     * (box contents, labels, flags, mode, tier boxes) changes during a pass, so within it equal inputs give equal
     * answers. Dropped when the pass ends, so later changes can never be served stale.
     */
    private Map<DestKey, List<Box>> destCache;
    private Map<Box, Integer> misplacedCache;

    /**
     * What destinationsFor and Categories.signScore depend on in a stack: its item and components (name, enchants,
     * potion, food, damage, shulker contents...), not its count.
     */
    private record DestKey(Item item, DataComponentMap components) {
        static DestKey of(ItemStack stack) {
            return new DestKey(stack.getItem(), stack.getComponents());
        }
    }

    private boolean pickJob() {
        // With a perimeter, its chunks are loaded first, so its containers are known before anything is sorted
        if (startExplore()) return true;
        if (!isActive()) return false;

        Box unread = nearest(stash.boxes.stream().filter(b -> !b.known() && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList());
        if (unread != null) return start(Job.Survey, unread);

        // Scan-only: everything is read, nothing is moved. A kit in hand is let go of, and a shulker standing in the
        // world is fetched back by finishSorting
        if (!sortItems.get()) {
            kitBuilder.suspend("scan only.");
            finishSorting();
            return false;
        }

        plan();
        destCache = new HashMap<>();
        misplacedCache = new HashMap<>();

        // Jobs started since the backlog (stacks out of place, and carried) last reached a new low: a pass that keeps
        // starting jobs without getting anywhere is going in circles, so say so and stop rather than reopen forever
        int carriedNow = carriedSlots().size();
        int backlog = carriedNow;
        for (Box box : stash.boxes) if (box.sortable()) backlog += misplacedCount(box);
        if (backlog < passMin) {
            passMin = backlog;
            passJobs = 0;
            // Real headway: containers given up on for going back and forth get another chance
            stalled.removeAll(circular);
            circular.clear();
            boxVisits.clear();
        }
        if (passJobs > 6 * stash.boxes.size() + 60) {
            warning("Sorting went in circles (%d jobs without getting closer to done), stopping this pass. %d stacks out of place, carrying %d.",
                passJobs, backlog - carriedNow, carriedNow);
            finishSorting();
            return false;
        }

        // A bundle with things in it is emptied first, its contents are then carried like anything else
        if (startUnbundle()) return true;

        // A kit being built carries on until it's finished or given up on
        if (kitBuilder.active()) {
            if (!kitsOn()) {
                kitBuilder.suspend(!shulkersOk() ? "placing shulkers failed lately, so no shulker work for a few minutes."
                    : mode.get() != Mode.Signs ? "kits are only built in Signs mode." : "build-kits was switched off.");
            }
            else if (runKit(kitBuilder.next())) return true;
        }

        // Compacting carries on until it is done or given up on: nothing else is started meanwhile
        if (compactor.active() && compactor.next()) return true;

        // Something nothing is signed for gets a new category on an empty chest, before it's put away
        if (labeller.next()) return true;

        if (repackOn() && nextRepackJob()) return true;

        // Stacks given up on are tried again every pass: a container read, emptied or signed since can give them a place
        stuckCarried.entrySet().removeIf(held -> {
            ItemStack stack = mc.player.getInventory().getItem(held.getKey());
            return stack.isEmpty() || !idOf(stack).equals(held.getValue());
        });
        // Looked at again when something changed or every few seconds, not on every pick
        if (!stuckCarried.isEmpty() && (stuckCheckVersion != version || clock - lastStuckCheck >= STUCK_RECHECK)) {
            stuckCheckVersion = version;
            lastStuckCheck = clock;
            stuckCarried.entrySet().removeIf(held -> canPlace(mc.player.getInventory().getItem(held.getKey())));
        }
        if (stuckCarried.isEmpty()) stuckWarned = false;

        List<Integer> carried = activeCarried();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                    if (!blocked(box, Job.Deposit) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                }
            }

            Box place = nearest(places);

            // A box given up on for going back and forth that is the only home for something carried is still used:
            // the pass watchdog stops a real loop
            if (place == null) {
                for (int slot : carried) {
                    for (Box box : destinationsFor(mc.player.getInventory().getItem(slot))) {
                        if (circular.contains(box) && !signPending(box) && !signHeld.contains(box) && !blockedByFutility(box, Job.Deposit) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                    }
                }
                place = nearest(places);
            }
            if (place != null) return start(Job.Deposit, place);

            // Nowhere it belongs has room: a container signed misc, other or overflow takes it, failing that (in Signs
            // mode) a container with no sign at all. Never one signed for something else
            Box spare = spareBox();
            if (spare != null) return start(Job.Dump, spare);

            // Nothing takes it for now. It stays in the carry slots, out of the plans, while the rest carries on, and is
            // looked at again when something in the stash changes
            for (int slot : carried) stuckCarried.put(slot, idOf(mc.player.getInventory().getItem(slot)));
            if (!stuckWarned) {
                stuckWarned = true;
                lastStuckWarn = clock;
                warning("Found nowhere with room for %d stacks I'm carrying (no container they belong in has space, and none signed misc/other/overflow does%s), keeping them in your inventory and carrying on with the rest.",
                    carried.size(), unsignedOverflow.get() && mode.get() == Mode.Signs ? ", and no unsigned container does" : "");
            }
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || blocked(box, Job.Fetch) || signPending(box) || isKitBox(box)) continue;
                for (int i = 0; i < box.contents.length; i++) {
                    if (!strays(box.contents[i]).isEmpty() && (best == null || closer(box, best))) {
                        best = box;
                        bestSlot = i;
                        break;
                    }
                }
            }
            if (best != null) return fetch(best, bestSlot);
        }

        // With every carry slot full there's nothing to pick up into: a trip would only open the container and stall it
        Box source = freeCarrySlot() == -1 ? null : nearest(stash.boxes.stream().filter(b -> b.sortable() && !blocked(b, Job.Take) && !signPending(b) && takeableSlot(b) != -1).toList());
        if (source != null) return start(Job.Take, source);

        // Downstack: merge partial stacks of the same item, once per container per run
        Box messy = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !blocked(b, Job.Compact) && !signPending(b) && !compacted.contains(b) && mergeable(b) != null).toList());
        if (messy != null) {
            compacted.add(messy);
            return start(Job.Compact, messy);
        }

        // A sign that is being changed decides what is left to do: wait for it before calling it done
        if (watchSigns.get() && mode.get() == Mode.Signs && stashWatch.anyPending()) return false;

        // Everything is put away: whatever others left on the ground comes before kits and compacting
        if (startGround()) return true;

        // Nothing left to sort: build kits
        if (kitsOn() && activeCarried().isEmpty() && !compactor.active() && kitBuilder.begin() && runKit(kitBuilder.next())) return true;

        // Everything is where it belongs: a full shulker's worth of anything is packed, which frees chest slots
        if (!kitBuilder.active() && compactor.start() && compactor.next()) return true;

        finishSorting();
        return false;
    }

    // Bundles

    private int unbundleSlot = -1, unbundleTaken, unbundleStalls, unbundleSize;
    private boolean unbundleClicked;
    private final Map<Integer, Integer> unbundleBlocked = new HashMap<>();
    private int unbundleStartSize;
    private static final int UNBUNDLE_BLOCK_TICKS = 20 * 30;

    private static boolean isBundle(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    /**
     * A bundle with something in it.
     */
    private static boolean fullBundle(ItemStack stack) {
        if (!isBundle(stack)) return false;
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents != null && !contents.isEmpty();
    }

    private static int bundleSize(ItemStack stack) {
        var contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents == null ? 0 : contents.size();
    }

    /**
     * Where an empty bundle goes: a container signed for bundles, else one signed for shulkers or containers. Empty when
     * there is none, and the usual rules apply.
     */
    private List<Box> bundleHomes(ItemStack bundle) {
        List<Box> signed = new ArrayList<>();
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || isKitBox(box)) continue;
            if (box.labels.stream().anyMatch(label -> Categories.normalize(label).contains("bundle"))) signed.add(box);
        }
        if (!signed.isEmpty()) return signed;
        return bestSigned(Items.SHULKER_BOX.getDefaultInstance());
    }

    /**
     * A bundle with things in it, in a carry slot, is emptied into the other carry slots before anything else is done with
     * it. Needs the plain inventory (no screen, nothing on the cursor) and a free carry slot for what comes out.
     * @return true when emptying has begun
     */
    private boolean startUnbundle() {
        if (!sortItems.get()) return false;
        if (mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        if (freeCarrySlot() == -1) return false;

        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!fullBundle(stack) || labeller.reservedSlot(slot) || setAside.contains(slot) || ticks < unbundleBlocked.getOrDefault(slot, -1)) continue;

            unbundleSlot = slot;
            unbundleTaken = 0;
            unbundleStalls = 0;
            unbundleClicked = false;
            unbundleSize = unbundleStartSize = bundleSize(stack);
            stashHeld.put(slot, stack.copy());

            job = Job.Unbundle;
            target = null;
            handle = null;
            actions = 0;
            jobCounted = false;
            stepTicks = 0;
            jobInterruptions = 0;
            startSigSet = endSigSet = false;
            settled = false;
            openPos = null;
            openMenuBefore = null;
            return true;
        }
        return false;
    }

    /**
     * Takes the contents of the bundle out one stack at a time. Right-clicking a bundle in the inventory with an empty
     * cursor puts the next stack in it on the cursor (the same in 1.21.4 and 1.21.11), and a left click on a free carry slot
     * puts that stack down. The bundle never leaves its slot, so there's nothing to put back.
     */
    private void unbundleTick() {
        stepTicks++;
        lastOwnAct = ticks;
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        if (mc.screen != null || mc.player.containerMenu != menu) {
            finishUnbundle();
            return;
        }

        ItemStack bundle = mc.player.getInventory().getItem(unbundleSlot);
        ItemStack cursor = menu.getCarried();
        if (!isBundle(bundle) || stepTicks > 20 * 30) {
            finishUnbundle();
            return;
        }

        // A stack is out: down into a free carry slot
        if (!cursor.isEmpty()) {
            int free = freeCarrySlot();
            if (free == -1) {
                // Nowhere to put it: back into the bundle (a left click on a bundle with a stack on the cursor)
                bundleClick(unbundleSlot, 0);
                finishUnbundle();
                return;
            }
            stashHeld.put(free, cursor.copy());
            bundleClick(free, 0);
            unbundleTaken++;
            unbundleClicked = false;
            wait = delay.get();
            return;
        }

        if (!fullBundle(bundle) || freeCarrySlot() == -1) {
            finishUnbundle();
            return;
        }

        // The last take got nothing out of the bundle: the server isn't letting it
        int size = bundleSize(bundle);
        boolean noProgress = (unbundleClicked && size >= unbundleSize) || (unbundleTaken > 0 && size >= unbundleStartSize);
        if (noProgress && ++unbundleStalls > 2) {
            finishUnbundle();
            return;
        }
        unbundleSize = size;
        unbundleClicked = true;
        bundleClick(unbundleSlot, 1);
        wait = delay.get();
    }

    /**
     * A click on a carry slot in the player inventory menu: button 0 is a left click, 1 a right click.
     */
    private void bundleClick(int slot, int button) {
        AbstractContainerMenu menu = mc.player.inventoryMenu;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot < 9 ? 36 + slot : slot, button, ClickType.PICKUP, mc.player);
        yielder.inventoryChangedByUs();
    }

    private void finishUnbundle() {
        ItemStack bundle = unbundleSlot >= 0 ? mc.player.getInventory().getItem(unbundleSlot) : ItemStack.EMPTY;
        if (isBundle(bundle)) stashHeld.put(unbundleSlot, bundle.copy());
        if (unbundleTaken > 0 && !fullBundle(bundle)) info("Emptied a bundle: %d stacks taken out.", unbundleTaken);
        if (fullBundle(bundle) && unbundleSlot >= 0) unbundleBlocked.put(unbundleSlot, ticks + UNBUNDLE_BLOCK_TICKS);
        // A stack still on the cursor, with no screen to take it: into a carry slot
        if (mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu && !mc.player.containerMenu.getCarried().isEmpty()) returnCursor();
        unbundleSlot = -1;
        if (job == Job.Unbundle) job = null;
        wait = delay.get();
        moved++;
    }

    // Building kits

    private boolean kitsOn() {
        return buildKits.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * Starts the job for the next step of the kit being built.
     * @return true when a job was started, false when the kit is finished or given up on
     */
    private boolean runKit(KitBuilder.Intent intent) {
        kitBuilder.jobStarted();

        switch (intent) {
            case KitBuilder.Take take -> {
                if (start(Job.KitTake, take.box())) return true;
                kitBuilder.suspend("couldn't reach " + take.box().pos.toShortString() + ".");
            }
            case KitBuilder.Fetch fetch -> {
                if (fetch(fetch.box(), fetch.slot())) return true;
                kitBuilder.suspend("couldn't reach " + fetch.box().pos.toShortString() + ".");
            }
            case KitBuilder.Unpack unpack -> {
                return startHandling(Job.KitUnpack, unpack.slot(), null);
            }
            case KitBuilder.Fill fill -> {
                return startHandling(Job.KitFill, fill.slot(), null);
            }
            case KitBuilder.Done done -> kitBuilder.finish();
            case KitBuilder.Abort abort -> kitBuilder.suspend(abort.reason());
        }
        return false;
    }

    private boolean isKitJob() {
        return job == Job.KitTake || job == Job.KitUnpack || job == Job.KitFill;
    }

    private boolean isKitBox(Box box) {
        return Kits.isKitBox(box);
    }

    // Repacking shulkers

    /**
     * Shulkers may be put down: not paused after a problem, and none left standing that couldn't be picked up.
     */
    private boolean shulkersOk() {
        return ticks >= repackPausedUntil && !dropLost;
    }

    private boolean compactOn() {
        return compact.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * No more shulkers for a few minutes, sorting goes on.
     */
    private void pauseShulkers(String why) {
        warning("%s, no more shulker work for a few minutes.", why);
        repackPausedUntil = ticks + PAUSE_TICKS;
    }

    private boolean repackOn() {
        return repack.get() && mode.get() == Mode.Signs && shulkersOk();
    }

    /**
     * Cleans a carried mixed shulker, or packs carried items into a bulk shulker, fetching one if needed.
     * @return true when a job was started
     */
    private boolean nextRepackJob() {
        // Compacting has the shulkers and the stacks set aside for it
        if (compactor.active()) return false;

        // A carried shulker with items in it that belong elsewhere: clean it out right here
        if (freeCarrySlot() != -1) {
            for (int slot : carriedSlots()) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (strays(stack).isEmpty()) continue;
                mainItem = Categories.dominantContent(stack).getItem();
                return startHandling(Job.Clean, slot, null);
            }
        }

        // Loose items with a bulk shulker to go in
        for (int slot : carriedSlots()) {
            ItemStack loose = mc.player.getInventory().getItem(slot);
            BulkKey key = keyOf(loose);
            if (key == null || packTried.contains(key)) continue;
            int stacks = carriedStacksOf(key);
            // With compacting on, new bulk shulkers are made by compacting: a full shulker's worth, never a part-filled one
            boolean startNew = !compact.get() && newBulkAt.get() > 0 && stacks >= newBulkAt.get();

            for (int s : carriedSlots()) {
                ItemStack shulker = mc.player.getInventory().getItem(s);
                if (bulkWithRoom(shulker, key) || (startNew && emptyShulker(shulker))) {
                    return startHandling(Job.Fill, s, key);
                }
            }

            if (freeCarrySlot() != -1) {
                Box from = null;
                int fromSlot = -1;
                for (Box box : stash.boxes) {
                    if (!box.sortable() || box.dropOff || blocked(box, Job.Fetch) || signPending(box) || isKitBox(box)) continue;
                    for (int i = 0; i < box.contents.length; i++) {
                        ItemStack s = box.contents[i];
                        boolean usable = bulkWithRoom(s, key) || (startNew && emptyShulker(s));
                        if (usable && (from == null || closer(box, from))) {
                            from = box;
                            fromSlot = i;
                            break;
                        }
                    }
                }
                if (from != null) return fetch(from, fromSlot);
            }

            // Nowhere to pack it: it's put away loose
            packTried.add(key);
        }

        return false;
    }

    private static BulkKey keyOf(ItemStack stack) {
        StackInfo info = StackInfo.of(stack);
        return info == null ? null : info.bulkKey();
    }

    private boolean fetch(Box box, int slot) {
        fetchWants = it -> Utils.isShulker(it.getItem());
        wantSlot = slot;
        return start(Job.Fetch, box);
    }

    /**
     * Slots inside a shulker holding items that belong in another signed container than what the shulker is mostly
     * full of. Empty when the shulker isn't one to clean: not a shulker, named, all one item, or wanted whole by a
     * sign like "kits".
     */
    private List<Integer> strays(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem())) return List.of();
        if (shulker.has(DataComponents.CUSTOM_NAME)) return List.of();

        ItemStack main = Categories.dominantContent(shulker);
        if (main == null) return List.of();

        // A saved kit, finished or part built, stays whole
        if (isKitShulker(shulker)) return List.of();

        // A chest signed for kits takes mixed shulkers whole instead
        if (!keepWholeBoxes().isEmpty()) return List.of();

        List<Integer> out = new ArrayList<>();
        ItemStack[] inside = inside(shulker);
        for (int i = 0; i < inside.length; i++) {
            ItemStack s = inside[i];
            if (s == null || s.isEmpty()) continue;
            if (fullBundle(s)) {
                out.add(i);
                continue;
            }
            if (s.is(main.getItem())) continue;
            if (!bestSigned(s).isEmpty()) out.add(i);
        }
        return out;
    }

    /**
     * A finished or mixed part-built kit, or what is left of a kit just given up on. A one-kind shulker that merely
     * fits inside a kit is an ordinary bulk shulker.
     */
    private boolean isKitShulker(ItemStack shulker) {
        return Kits.claims(shulker) || kitBuilder.heldKit(shulker) != null;
    }

    /**
     * A shulker holding only stacks of this bulk key, with room for more.
     */
    private boolean bulkWithRoom(ItemStack shulker, BulkKey key) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME) || isKitShulker(shulker)) return false;

        List<StackInfo> contents = StackInfo.of(shulker).contents();
        if (contents.isEmpty()) return false;
        boolean room = contents.size() < 27;
        for (StackInfo s : contents) {
            if (!key.equals(s.bulkKey())) return false;
            if (s.count < s.maxStack) room = true;
        }
        return room;
    }

    private static boolean emptyShulker(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME) || Kits.claims(shulker)) return false;
        for (ItemStack s : inside(shulker)) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    private static ItemStack[] inside(ItemStack shulker) {
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(shulker, contents);
        return contents;
    }

    private int carriedStacksOf(BulkKey key) {
        int count = 0;
        for (int slot : carriedSlots()) if (key.equals(keyOf(mc.player.getInventory().getItem(slot)))) count++;
        return count;
    }

    private int freeCarryCount() {
        int count = 0;
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    private boolean closer(Box a, Box b) {
        Vec3 eyes = mc.player.getEyePosition();
        return eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(a))) < eyes.distanceToSqr(Vec3.atCenterOf(nearestPart(b)));
    }

    private boolean startHandling(Job next, int slot, BulkKey fill) {
        restoreHotbar();
        job = next;
        target = null;
        handledSlot = slot;
        fillKey = fill;
        handle = Handle.Place;
        handleTicks = 0;
        breakRetries = 0;
        brokenSince = -1;
        placed = null;
        placedConfirmed = false;
        actions = 0;
        collectedSlot = -1;
        return true;
    }

    /**
     * One step of repacking a shulker beside us: place, open, move items, break, pick back up.
     */
    private void tickHandling() {
        handleTicks++;

        switch (handle) {
            case Place -> handlePlace();
            case Open -> handleOpen();
            case Work -> handleWork();
            case Break -> handleBreak();
            case Collect -> handleCollect();
            case Return -> handleReturn();
            case Leave -> handleLeave();
        }
    }

    private void handlePlace() {
        ItemStack shulker = mc.player.getInventory().getItem(handledSlot);
        if (!Utils.isShulker(shulker.getItem())) {
            endHandling();
            return;
        }

        // Shulkers are not put down in quick succession: that is what a build bot does
        if (ticks - lastPlaceTick < PLACE_GAP_TICKS) {
            wait = 10;
            return;
        }

        // Where first: nothing is moved for a spot that isn't there
        BlockPos spot = safeSpot();
        if (spot == null) {
            pauseShulkers("No safe room to put a shulker down beside me");
            endHandling();
            return;
        }

        // It has to be in the hotbar to place it
        int hotbar = handledSlot;
        if (hotbar >= 9) {
            hotbar = -1;
            Set<Integer> keep = parseSlots(keepSlots.get());
            // An empty slot first, so nothing of the player's moves
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
                if (hotbar != -1) {
                    // The player's item ends up in the carry slot: it's theirs until it's put back
                    swapBackFrom = handledSlot;
                    swapBackTo = hotbar;
                    hotbarDisplaced = mc.player.getInventory().getItem(hotbar).copy();
                }
            }
            if (hotbar == -1) {
                pauseShulkers("No hotbar slot I'm allowed to use to place shulkers");
                endHandling();
                return;
            }
            InvUtils.move().from(handledSlot).to(hotbar);
            handledSlot = hotbar;
            wait = delay.get();
            return;
        }

        shulkersBefore = countShulkers();
        placedStacks = StackInfo.of(shulker).contents().size();
        if (!BlockUtils.place(spot, new FindItemResult(hotbar, shulker.getCount()), rotate.get(), 50, true, true, true)) {
            placeFailed(spot);
            return;
        }

        lastPlaceTick = ticks;
        placed = spot;
        placedConfirmed = false;
        // Written down now, in case the block never shows: it is a shulker standing somewhere until it's picked up
        recordOrphan(spot, placedName(), placedStacks);
        shulkerSlotsBefore.clear();
        for (int i = 0; i < 36; i++) {
            if (i != handledSlot && Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
        }
        handle = Handle.Open;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * A spot where the shulker would not go: never tried again. After three in a row shulkers are left alone for a while.
     */
    private void placeFailed(BlockPos spot) {
        failedSpots.add(spot);
        debug("Couldn't put a shulker at %s.", spot.toShortString());
        if (++placeFailures >= 3) {
            placeFailures = 0;
            pauseShulkers("Couldn't put the shulker down (3 spots failed)");
            endHandling();
            return;
        }
        wait = 5;
    }

    private boolean shulkerAt(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock;
    }

    private void handleOpen() {
        // Put back whatever we swapped out of the hotbar to place it, as soon as it's safe to
        if (swapBackFrom != -1) {
            restoreHotbar();
            if (swapBackFrom == -1) {
                wait = delay.get();
                return;
            }
        }

        // The place may still be on its way: longer while the shulker is out of the inventory
        if (!shulkerAt(placed)) {
            if (placedConfirmed) {
                // Was there, now it isn't: broken, or picked up by someone
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }
            boolean gone = countShulkers() < shulkersBefore;
            if (handleTicks <= (gone ? 200 : 30)) return;

            if (gone) {
                // Out of the inventory but no block: a late update, or it's on the ground. Looked for, and on record
                warning("The shulker I put down at %s hasn't shown up as a block, looking for it.", placed.toShortString());
                placedConfirmed = true;
                handle = Handle.Collect;
                handleTicks = 0;
                return;
            }

            // Never got placed after all, it's still in the inventory
            BlockPos failed = placed;
            clearOrphan(failed);
            placed = null;
            handle = Handle.Place;
            handleTicks = 0;
            placeFailed(failed);
            return;
        }
        if (!placedConfirmed) {
            placedConfirmed = true;
            placeFailures = 0;
            recordOrphan(placed, placedName(), placedStacks);
        }

        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            ownedMenu = mc.player.containerMenu;
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkerSlotsBefore.add(i);
            handle = Handle.Work;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        if (handleTicks == 1 || handleTicks % 20 == 0) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placed), Direction.UP, placed, false);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
        }
    }

    /**
     * What the shulker in the world is holding, for the records.
     */
    private String placedName() {
        Compactor.Plan plan = compactor.plan;
        if (plan != null) return plan.name;
        return fillKey != null ? new ItemStack(fillKey.item()).getItemName().getString() : "items";
    }

    private void handleWork() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        boolean packing = job == Job.Fill && compactor.active();
        boolean busy = switch (job) {
            case Clean -> takeStray(menu);
            case KitUnpack -> kitBuilder.unpackOne(menu);
            case KitFill -> kitBuilder.fillOne(menu);
            default -> packing ? compactor.packOne(menu) : packOne(menu);
        };
        if (busy) {
            actions++;
            wait = delay.get();
            return;
        }

        // Never close with something on the cursor, it would be merged into the inventory or dropped
        if (!menu.getCarried().isEmpty()) recoverCursor();

        if (packing) {
            boolean stay = compactor.afterPacking(menu, actions, placed);
            closeOurWindow(mc.player.containerMenu);
            handle = stay ? Handle.Leave : Handle.Break;
            handleTicks = 0;
            wait = delay.get();
            return;
        }

        if (job == Job.Fill && actions == 0 && fillKey != null) packTried.add(fillKey);
        // Nothing to take out after all: stop trying, or we'd keep reopening the same shulker
        if (job == Job.Clean && actions == 0) pauseShulkers("There was nothing to take out of the shulker");
        if (isKitJob() && actions == 0) kitBuilder.suspend("there was nothing I could move in the shulker.");
        closeOurWindow(mc.player.containerMenu);
        handle = Handle.Break;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * Takes one stack that belongs elsewhere out of the open shulker, into a free carry slot. One slot stays free, for
     * picking the shulker up.
     */
    private boolean takeStray(AbstractContainerMenu menu) {
        int into = freeCarrySlot();
        if (into == -1 || freeCarryCount() <= 1) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || (!fullBundle(s) && (s.is(mainItem) || bestSigned(s).isEmpty()))) continue;
            stashHeld.put(into, s.copy());
            InvUtils.move().fromId(i).toId(inventoryToMenu(into, 27));
            moved++;
            return true;
        }
        return false;
    }

    /**
     * Puts one carried stack of the item being packed into the open shulker.
     */
    private boolean packOne(AbstractContainerMenu menu) {
        for (int slot : carriedSlots()) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (fillKey == null || !fillKey.equals(keyOf(stack))) continue;

            for (int i = 0; i < 27; i++) {
                ItemStack here = menu.getSlot(i).getItem();
                if (here.isEmpty() || (ItemStack.isSameItemSameComponents(here, stack) && here.getCount() < here.getMaxStackSize())) {
                    InvUtils.shiftClick().slotId(inventoryToMenu(slot, 27));
                    moved++;
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * The round is packed and more is wanted: the window is closed and the shulker stays where it is while the next round
     * is gathered.
     */
    private void handleLeave() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }
        // Left standing: the plan knows where, and the next round comes back to it
        Compactor.Plan plan = compactor.plan;
        if (plan != null) {
            plan.placedPos = placed;
            plan.dimension = mc.level.dimension().toString();
        }
        endHandling();
    }

    private void handleBreak() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            closeOurWindow(mc.player.containerMenu);
            return;
        }

        // The break is reported as soon as the block looks gone here, but the server can still refuse it and put the
        // shulker back: it only counts as broken once the spot has stayed clear for a moment
        if (!shulkerAt(placed)) {
            if (brokenSince < 0) brokenSince = ticks;
            if (ticks - brokenSince < BREAK_SETTLE_TICKS) return;
            brokenSince = -1;
            if (pickaxeSwapped) {
                InvUtils.swapBack();
                pickaxeSwapped = false;
            }
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }
        brokenSince = -1;

        // Breaking it needs a free slot to pick it up into: otherwise it would end up on the ground
        if (!freeInventorySlot()) {
            if (handleTicks % 200 == 1) warning("No free inventory slot to pick the shulker at %s up into, leaving it where it is until there is one.", placed.toShortString());
            if (handleTicks > 20 * 60 * 5) abandonShulker("No free inventory slot to pick the shulker at " + placed.toShortString() + " up into.");
            return;
        }

        if (++breakTicks > HANDLE_TIMEOUT * 2) {
            abandonShulker("Couldn't break the shulker at " + placed.toShortString() + ", it's still there.");
            return;
        }

        if (!pickaxeSwapped) {
            FindItemResult pickaxe = InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES));
            if (pickaxe.found()) {
                InvUtils.swap(pickaxe.slot(), true);
                pickaxeSwapped = true;
            }
        }
        BlockUtils.breakBlock(placed, true);
    }

    private static final int BREAK_SETTLE_TICKS = 10;
    private static final int MAX_BREAK_RETRIES = 3;
    private int breakRetries;
    private int brokenSince = -1;

    private static String fmtPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private boolean shulkerItemNearby() {
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4))) {
            if (Utils.isShulker(e.getItem().getItem())) return true;
        }
        return false;
    }

    private void handleCollect() {
        breakTicks = 0;
        // The server put the shulker back: it is broken again rather than looked for on the ground, where it never was
        if (shulkerAt(placed) && !shulkerItemNearby()) {
            if (++breakRetries > MAX_BREAK_RETRIES) {
                abandonShulker("Couldn't break the shulker at " + fmtPos(placed) + ", the server keeps putting it back. It's still standing there.");
                return;
            }
            warning("The shulker at %s is still standing, the server didn't let it break. Breaking it again (%d/%d).", fmtPos(placed), breakRetries, MAX_BREAK_RETRIES);
            brokenSince = -1;
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }
        // A block that turns up after all (a late update) is worked with again
        if (shulkerAt(placed)) {
            handle = Handle.Open;
            handleTicks = 0;
            return;
        }

        if (countShulkers() >= shulkersBefore) {
            // Picked up: the new shulker, or failing that the one that holds what the plan was packing, in any slot
            int found = -1;
            for (int i = 0; i <= 40 && found == -1; i++) {
                if ((i >= 36 && i < 40) || shulkerSlotsBefore.contains(i)) continue;
                if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) found = i;
            }
            Compactor.Plan plan = compactor.plan;
            if (found == -1 && plan != null) {
                for (int i = 0; i <= 40 && found == -1; i++) {
                    if (i >= 36 && i < 40) continue;
                    ItemStack there = mc.player.getInventory().getItem(i);
                    if (Utils.isShulker(there.getItem()) && StackInfo.of(there).contents().stream().anyMatch(c -> plan.key.equals(c.bulkKey()))) found = i;
                }
            }

            if (found == -1) {
                warning("I picked up the shulker from %s but couldn't tell which slot it is in.", placed.toShortString());
                compactor.collected(-1, placed, ItemStack.EMPTY);
            }
            else {
                ItemStack got = mc.player.getInventory().getItem(found).copy();
                collectedSlot = found;
                if (found < 36 && !carry.contains(found)) {
                    int free = freeCarrySlot();
                    if (free != -1) {
                        InvUtils.move().from(found).to(free);
                        collectedSlot = free;
                    }
                    else {
                        // No carry slot to move it to: that slot is carried from now on, so it gets put away
                        warning("The repacked shulker ended up outside the carry slots, in slot %d, treating it as carried.", found + 1);
                        carry.add(found);
                        stashHeld.put(found, got.copy());
                    }
                }
                else if (found >= 36) {
                    warning("The repacked shulker ended up in your offhand, please put it in your inventory.");
                }
                compactor.collected(collectedSlot, placed, got);
            }
            clearOrphan(placed);
            endHandling();
            return;
        }

        if (handleTicks == 20 * 30 || handleTicks == 20 * 60) {
            warning("Still looking for the shulker I broke at %s, it isn't in my inventory yet (%d s).", placed.toShortString(), handleTicks / 20);
        }
        if (handleTicks > COLLECT_TIMEOUT) {
            abandonShulker("Couldn't pick the shulker back up, it's on the ground near " + placed.toShortString() + ".");
            return;
        }

        if (leftArea()) {
            abandonShulker("Skipping the shulker box at " + at(placed) + ": the way there leaves the stash area.");
            return;
        }

        // Dropped out of reach: walk over it
        if (handleTicks >= 40 && handleTicks % 200 == 40 && walkOn()) {
            ItemEntity drop = null;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4))) {
                if (Utils.isShulker(e.getItem().getItem())) {
                    drop = e;
                    break;
                }
            }
            if (drop != null) {
                walkNear(drop.blockPosition(), 0);
            }
        }
    }

    private void endHandling() {
        // A shulker cleaned or filled is real headway: the counters start again
        if (job == Job.Clean || job == Job.Fill) {
            passJobs = 0;
            boxVisits.clear();
            stalled.removeAll(circular);
            circular.clear();
        }
        if (job == Job.KitFill || job == Job.KitUnpack) kitBuilder.handled(job == Job.KitFill, collectedSlot >= 0 ? collectedSlot : handledSlot);
        handle = null;
        placed = null;
        placedConfirmed = false;
        breakRetries = 0;
        brokenSince = -1;
        lastOwnAct = ticks;
        restoreHotbar();
        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        if (stash.isDirty()) stash.save();
        job = null;
        wait = delay.get();
    }

    /**
     * A shulker that could not be broken or picked up, or walked back to: said loudly, kept in the stash file and the
     * status with what it holds and where, and no more shulkers are put down until sorting is restarted. Sorting carries on.
     */
    private void abandonShulker(String why) {
        Compactor.Plan plan = compactor.plan;
        BlockPos at = placed != null ? placed : plan != null ? plan.placedPos : null;
        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        error("%s It holds %s of %s. No more shulkers are put down until sorting is restarted.", why, stacks > 0 ? stacks + " stacks" : "some stacks", what);
        if (at != null) recordOrphan(at, what, stacks);
        dropLost = true;
        compactor.drop();
        if (isKitJob()) kitBuilder.suspend(why);
        endHandling();
    }

    /**
     * Puts the player's hotbar item back where it was, once no screen is open and nothing is on the cursor, if it's still
     * where the move left it. Nothing is moved otherwise: a stale record is dropped.
     */
    private void restoreHotbar() {
        if (swapBackFrom == -1) return;
        if (mc.player == null || mc.screen != null || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        int from = swapBackFrom, to = swapBackTo;
        ItemStack displaced = hotbarDisplaced;
        swapBackFrom = swapBackTo = -1;
        hotbarDisplaced = ItemStack.EMPTY;

        if (displaced.isEmpty() || !ItemStack.matches(mc.player.getInventory().getItem(from), displaced)) return;
        ItemStack hot = mc.player.getInventory().getItem(to);
        // Into the free slot, or in place of the shulker still there (which goes back to the carry slot)
        if (hot.isEmpty() || Utils.isShulker(hot.getItem())) InvUtils.move().from(from).to(to);
    }

    // Shulkers standing in the world are kept in the stash file until they are picked up

    private void recordOrphan(BlockPos at, String item, int stacks) {
        if (stash == null) return;
        stash.orphans.removeIf(o -> o.pos.equals(at));
        stash.orphans.add(new Stash.Orphan(at, item, stacks, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            mc.level.dimension().toString()));
        stash.markDirty();
        stash.save();
    }

    private void clearOrphan(BlockPos at) {
        if (stash == null || at == null) return;
        if (stash.orphans.removeIf(o -> o.pos.equals(at))) {
            stash.markDirty();
            stash.save();
        }
    }

    /**
     * When sorting starts: shulkers left standing last time that are gone now are forgotten, and any still there mean no
     * more are put down.
     */
    private void checkOrphans() {
        String here = mc.level.dimension().toString();
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            // One in another dimension is neither forgotten nor looked for from here
            if (!orphan.dimension.isEmpty() && !orphan.dimension.equals(here)) continue;
            if (mc.level.isLoaded(orphan.pos) && !shulkerAt(orphan.pos)) {
                stash.orphans.remove(orphan);
                stash.markDirty();
                info("The shulker I left at %s is gone.", orphan.pos.toShortString());
            }
            else {
                warning("A shulker with %d stacks of %s is still standing at %s (since %s), pick it up by hand. No shulkers are put down until it's gone.",
                    orphan.stacks, orphan.item, orphan.pos.toShortString(), orphan.when);
                dropLost = true;
            }
        }
    }

    /**
     * A shulker of ours standing in the world when sorting stops: said loudly with what is in it and where.
     */
    private void reportPlacedShulker() {
        Compactor.Plan plan = compactor.plan;
        BlockPos at = null;
        if (handle != null && placed != null && mc.level != null && shulkerAt(placed)) at = placed;
        else if (plan != null && plan.placedPos != null) at = plan.placedPos;
        if (at == null) return;

        String what = placedName();
        int stacks = plan != null ? plan.placedSize : placedStacks;
        error("Stopped with a shulker holding %s of %s standing at %s. Pick it up by hand.", stacks > 0 ? stacks + " stacks" : "some stacks", what, at.toShortString());
        recordOrphan(at, what, stacks);
    }

    private boolean freeInventorySlot() {
        Set<Integer> keep = parseSlots(keepSlots.get());
        for (int i = 0; i < 36; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }

    private int countShulkers() {
        int count = 0;
        for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) count++;
        if (Utils.isShulker(mc.player.getInventory().getItem(40).getItem())) count++;
        return count;
    }

    /**
     * A spot beside us that is safe for a shulker: open air on solid ground with room for the lid, not where a place
     * failed before, not a stash container, not on top of one (it would not open), not next to a hopper (it would be
     * emptied), not where a player or a mob stands.
     */
    private BlockPos safeSpot() {
        BlockPos feet = mc.player.blockPosition();
        Map<String, Integer> rejected = new HashMap<>();
        for (int dy = 0; dy >= -1; dy--) {
            for (Direction dir : HORIZONTALS) {
                BlockPos p = feet.relative(dir).above(dy);
                if (!BlockUtils.canPlace(p, true)) continue;
                if (!mc.level.getBlockState(p.above()).isAir()) continue;
                if (!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), Direction.UP)) continue;

                String why = unsafeBecause(p);
                if (why == null) return p;
                debug("Not putting a shulker at %s: %s.", p.toShortString(), why);
                rejected.merge(why, 1, Integer::sum);
            }
        }
        if (!rejected.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (var entry : rejected.entrySet()) text.append(text.isEmpty() ? "" : ", ").append(entry.getValue()).append("x ").append(entry.getKey());
            info("No safe spot for a shulker beside me at %s: %s.", feet.toShortString(), text);
        }
        return null;
    }

    /**
     * Why a shulker mustn't be put at this spot, or null if it may.
     */
    private String unsafeBecause(BlockPos spot) {
        if (failedSpots.contains(spot)) return "placing there failed before";
        if (stash.boxAt(spot) != null) return "a stash container is there";
        // What is dropped there when the shulker is broken has to be walkable to: floor under it, and open on two sides
        // at least (our own side and one more), not a gap between containers
        BlockPos floor = spot.below();
        if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) return "there is no floor under it";
        int open = 0;
        for (Direction d : HORIZONTALS) {
            BlockPos side = spot.relative(d);
            if (mc.level.getBlockState(side).getCollisionShape(mc.level, side).isEmpty() && stash.boxAt(side) == null) open++;
        }
        if (open < 2) return "it is boxed in, an item dropped there could not be reached";
        // Another player or a mob whose box, a little wider than it is, overlaps the spot's cell blocks the place. Not the
        // player themselves, and not items, frames or armor stands
        if (!mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(spot).inflate(0.3), e -> e != mc.player && !(e instanceof ArmorStand)).isEmpty()) {
            return "a player or mob is standing in the way";
        }
        BlockPos below = spot.below();
        for (Box box : stash.boxes) if (box.has(below)) return "it is on top of the container at " + box.pos.toShortString();
        for (Direction d : Direction.values()) {
            if (mc.level.getBlockState(spot.relative(d)).is(Blocks.HOPPER)) return "there is a hopper next to it";
        }
        return null;
    }

    private void finishSorting() {
        // A plan in hand: the shulker standing in the world is fetched back first, then what was gathered for it is put away
        if (compactor.active()) {
            compactor.abort("sorting is finishing");
            if (compactor.next()) return;
            if (!activeCarried().isEmpty()) return;
        }

        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        closedEarlyReported.clear();
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        if (stash.isDirty()) stash.save();

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        extra += compactor.takeRunSummary();
        if (!stuckCarried.isEmpty()) extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (sortItems.get() && leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        // Scan-only: one scan, then off, unless stay-on keeps it watching
        if (!sortItems.get()) {
            if (!stayOn.get()) {
                info("Scan only: read %d containers, the saved stash is up to date.%s", scanned, extra);
                scanned = 0;
                toggle();
                return;
            }
            if (scanned > 0 || !extra.equals(lastSummary)) {
                info("Scan only: read %d containers, the saved stash is up to date.%s Watching for changes.", scanned, extra);
            }
            lastSummary = extra;
            scanned = 0;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        if (stayOn.get()) {
            // Waking up for a drop-off check that finds nothing says nothing; a changed summary does
            if (moved > 0 || !extra.equals(lastSummary)) info("Sorted, moved %d stacks.%s Waiting for more to sort.", moved, extra);
            lastSummary = extra;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = version;
            return;
        }

        info("Done, moved %d stacks.%s", moved, extra);
        toggle();
    }

    private boolean start(Job next, Box box) {
        // A kit being built has its own limits: its trips are not what the circle watchdog counts
        // Repacking trips (fetching a shulker), compacting, surveys and kit builds don't shrink the backlog, so they aren't
        // what the circle watchdog counts
        jobCounted = !kitBuilder.active() && next != Job.Fetch && next != Job.Compact && next != Job.Survey && next != Job.FetchSigns && next != Job.Gather;
        if (jobCounted) {
            passJobs++;
            boxVisits.merge(box, 1, Integer::sum);
        }
        startSigSet = endSigSet = false;
        settled = false;
        job = next;
        target = box;
        swapStep = 0;
        actions = 0;
        recoveries = 0;
        openPos = null;
        openMenuBefore = null;

        if (inReach(box)) {
            step = Step.Opening;
        }
        else if (walkOn()) {
            walkNear(box.pos, 2);
            step = Step.Walking;
        }
        else {
            if (stash.hasArea() && !BARITONE) warnNoBaritone();
            box.unreachable = true;
            job = null;
            target = null;
            return false;
        }

        stepTicks = 0;
        return true;
    }

    private void finishJob() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            // Never close with something on the cursor, it would be merged into the inventory or dropped
            if (!mc.player.containerMenu.getCarried().isEmpty() && !recoverCursor()) {
                error("Couldn't put away the stack on the cursor, no free slot. Stopping, and the stack may be dropped when the container closes.");
                toggle();
                return;
            }
            closeOurWindow(mc.player.containerMenu);
        }
        ownedMenu = null;
        lastOwnAct = ticks;
        openPos = null;
        openMenuBefore = null;
        recoveries = 0;

        // Clicks that changed neither the carry slots nor the container count for nothing: they never went through.
        // Clicks were made but the window went before the result could be read: an unconfirmed attempt, which is neither
        // progress nor proof of none. A box is stalled after two of them in a row
        boolean confirmed = endSigSet && startSigSet;
        boolean unconfirmed = actions > 0 && !confirmed;
        boolean progressed = actions > 0 && confirmed && startSig != endSig;

        // Opened it and got nothing done: not repeated until the carry slots or the container change
        if (actions == 0 && !signAbort && job != null && job != Job.Survey && target != null && !target.unreachable) {
            futile.put(futileKey(target, job), futileSig(target));
        }
        if (actions > 0 && target != null) signAbortRuns.remove(target);
        if (job == Job.Survey && target != null && target.known()) scanned++;

        int visits = target == null ? 0 : boxVisits.getOrDefault(target, 0);
        if (jobCounted && visits >= 3 && job != Job.Survey && target != null && loopReported.add(target)) {
            debug("Container at %s has been the target of %d jobs without the backlog shrinking: last job %s, %d actions, %s, %s, carrying %d stacks.",
                target.pos.toShortString(), visits, job, actions, confirmed ? "confirmed" : "unconfirmed",
                progressed ? "changed something" : "changed nothing", carriedSlots().size());
        }

        // Going back and forth between containers while the backlog doesn't shrink: given up on, however each job looks
        boolean circle = jobCounted && visits >= 4 && job != Job.Survey && target != null && !target.unreachable && !signAbort;
        if (circle) {
            circular.add(target);
            stalled.add(target);
        }
        else if (job == Job.Survey && target != null && !target.known() && !signAbort) failedSurvey.add(target);
        else if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (unconfirmed && !signAbort) {
                if (unconfirmedRuns.merge(target, 1, Integer::sum) >= 2) stalled.add(target);
            }
            else if (!progressed && !signAbort) stalled.add(target);
            else if (progressed) {
                unconfirmedRuns.remove(target);
                clearStalled();
                stalled.addAll(circular);
                stuckCarried.clear();
                stuckWarned = false;
            }
        }

        startSigSet = endSigSet = false;

        if (stash.isDirty()) stash.save();
        signAbort = false;
        job = null;
        target = null;
        wait = delay.get();
    }

    // Steps

    private void tickWalking() {
        if (inReach(target)) {
            releaseGoal();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        // Started inside the perimeter and now outside it: a way that leads out of the stash area isn't taken
        if (walkInside && stash.hasArea() && !stash.inArea(mc.player.getX(), mc.player.getY(), mc.player.getZ(), AREA_MARGIN)) {
            releaseGoal();
            reportUnreachable(target, Cause.Area);
            target.unreachable = true;
            finishJob();
            return;
        }

        boolean stopped = stepTicks > PATH_GRACE_TICKS && !BaritoneHelper.isMoving();
        boolean ourGoalWasTaken = stopped && BARITONE && ourGoal != null && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal);
        if (stopped || stepTicks > WALK_TIMEOUT) {
            releaseGoal();
            // Our path went away because somebody else cancelled or replaced it (nothing signalled it, but our goal is no
            // longer the one Baritone has): not the container's fault, plan it again. A few times only, since a path
            // that couldn't be found clears the goal too
            boolean cancelled = stopped && BARITONE && ourGoalWasTaken && cancelRetries.merge(target, 1, Integer::sum) <= 2;
            if (stopped && (cancelled || yielder.foreignRecently(stepTicks + 5))) {
                if (jobCounted) {
                    passJobs = Math.max(0, passJobs - 1);
                    boxVisits.merge(target, -1, Integer::sum);
                }
                job = null;
                target = null;
                wait = delay.get();
                return;
            }
            reportUnreachable(target, Cause.Path);
            target.unreachable = true;
            finishJob();
        }
    }

    private void tickOpening() {
        AbstractContainerMenu open = mc.player.containerMenu;
        if (open != mc.player.inventoryMenu) {
            // Only the menu our own click opened, of the size this box has. Anything else (an ender chest, a plugin
            // GUI, one left over from an earlier click) is closed and the click retried
            if (openPos != null && open != openMenuBefore && open instanceof ChestMenu && sizedForTarget(open)) {
                ownedMenu = open;
                step = Step.Working;
                stepTicks = 0;
                read(target);
                wait = delay.get();
                return;
            }

            if (!open.getCarried().isEmpty()) recoverCursor();
            mc.player.closeContainer();
        }

        if (stepTicks > OPEN_TIMEOUT) {
            reportUnreachable(target, Cause.Open);
            target.unreachable = true;
            finishJob();
            return;
        }

        if (stepTicks == 1 || stepTicks % 20 == 0) {
            BlockPos pos = nearestPart(target);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            Runnable click = () -> {
                // The rotation can finish after we have started to give way
                if (gate != Gate.Running || job == null) return;
                openPos = pos;
                openClickTick = ticks;
                openMenuBefore = mc.player.containerMenu;
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
            };

            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), click);
            else click.run();
        }
    }

    private void tickWorking() {
        // A sign on this container changed: what it takes depends on it now, so don't carry on with the old idea
        if (signPending(target)) {
            // Not the container's fault: it is looked at again once its sign has settled, so it is not stalled
            signAbort = true;
            signAborted.add(target);
            if (signAbortReported.add(target)) debug("Sign at %s reported changing, waiting for it to settle.", target.pos.toShortString());
            // The same container aborted again and again without a click: held until its sign text really changes
            if (actions == 0 && signAbortRuns.merge(target, 1, Integer::sum) >= 2) {
                signHeld.add(target);
                stalled.add(target);
            }
            finishJob();
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu) || menu.slots.size() - 36 != target.parts.size() * 27) {
            // Closed on us, or swapped for something else, by the server or the player
            if (closedEarlyReported.add(target)) {
                debug("Container at %s closed before I finished with it (%s), the server may have closed it.", target.pos.toShortString(),
                    menu == mc.player.inventoryMenu ? "window gone" : "another window opened");
            }
            finishJob();
            return;
        }

        read(target);

        // Both ends of the comparison are read through the open window, before anything is clicked and once the last
        // click is through, so they come from the same source
        if (actions == 0 && !startSigSet) {
            startSig = stateSig();
            startSigSet = true;
        }

        boolean busy = switch (job) {
            case Survey, Clean, Fill, KitUnpack, KitFill, Label, Ground, Explore, Unbundle -> false;
            case FetchSigns -> labeller.fetchSignsOne(target, actions);
            case Gather -> compactor.gatherOne(target);
            case KitTake -> kitBuilder.takeOne(target);
            case Take -> takeOne();
            case Fetch -> fetchOne();
            case Compact -> compactOne();
            case Deposit -> depositOne();
            case Dump -> dumpOne();
        };

        if (busy) {
            actions++;
            settled = false;
            wait = delay.get();
        }
        else if (actions > 0 && !settled) {
            // Give the server a moment to answer the last click, then look again before judging what the clicks did
            settled = true;
            wait = SETTLE_TICKS;
        }
        else {
            endSig = stateSig();
            endSigSet = true;
            if (actions == 0 && noOpReported.add(futileKey(target, job))) {
                debug("%s at %s did nothing: %d free carry slots, %d carried.", job, target.pos.toShortString(), freeCarryCount(), carriedSlots().size());
            }
            finishJob();
        }
    }

    // Loop protection

    private void resetLoopState() {
        stalled.clear();
        cancelRetries.clear();
        stashWatch.clear();
        signHeld.clear();
        signAborted.clear();
        signAbortRuns.clear();
        signAbortReported.clear();
        staleBoxes.clear();
        deferredAccess.clear();
        signAbort = false;
        nextSignSweep = clock + SIGN_SWEEP_TICKS;
        futile.clear();
        noOpReported.clear();
        failedSurvey.clear();
        unconfirmedRuns.clear();
        closedEarlyReported.clear();
        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        stuckCarried.clear();
        stuckWarned = false;
        lastSummary = null;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        startSigSet = endSigSet = false;
    }

    private String futileKey(Box box, Job kind) {
        return box.pos.asLong() + ":" + kind;
    }

    private static long stackSig(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 131L + stack.getCount();
    }

    private long futileSig(Box box) {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (box.known()) for (ItemStack stack : box.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * What the carry slots and the target container hold now.
     */
    private long stateSig() {
        long h = 17;
        for (int slot : carry) h = h * 31 + stackSig(mc.player.getInventory().getItem(slot));
        if (target != null && target.known()) for (ItemStack stack : target.contents) h = h * 31 + stackSig(stack);
        return h;
    }

    /**
     * Not worth a trip for this kind of job: the box is stalled, or the same trip came back empty-handed and neither
     * the carry slots nor the container have changed since.
     */
    private void clearStalled() {
        stalled.clear();
        stalled.addAll(signHeld);
    }

    /**
     * A sign on this container is being changed, so what it takes is not settled.
     */
    private boolean signPending(Box box) {
        return watchSigns.get() && mode.get() == Mode.Signs && stashWatch.pending(box);
    }

    private boolean blocked(Box box, Job kind) {
        return stalled.contains(box) || signHeld.contains(box) || blockedByFutility(box, kind);
    }

    private boolean blockedByFutility(Box box, Job kind) {
        Long was = futile.get(futileKey(box, kind));
        return was != null && was == futileSig(box);
    }

    /**
     * Chat when the debug setting is on, and always the log at debug level.
     */
    private void debug(String format, Object... args) {
        String text = String.format(format, args);
        QwerteAddon.LOG.debug("[stash-sorter] {}", text);
        if (debug.get()) info("%s", text);
    }

    /**
     * Whether the open menu is a chest-sized one for the target. A box that has become a double chest (or been halved)
     * since it was saved is brought up to date when the menu's size shows it.
     */
    private boolean sizedForTarget(AbstractContainerMenu menu) {
        int slots = menu.slots.size() - 36;
        if (slots == target.parts.size() * 27) return true;
        return refreshTargetParts() && slots == target.parts.size() * 27;
    }

    /**
     * Re-reads which blocks make up the target container, when a chest next to it was placed or broken since it was
     * saved. Its contents are read again, and a saved box that is now just its other half is dropped, keeping its marks.
     * @return true if the parts changed
     */
    private boolean refreshTargetParts() {
        if (!mc.level.isLoaded(target.pos)) return false;

        List<BlockPos> now = makeBox(target.pos).parts;
        if (now.equals(target.parts)) return false;

        if (now.size() != target.parts.size()) target.contents = null;
        target.parts.clear();
        target.parts.addAll(now);
        readSigns(target);
        for (int i = stash.boxes.size() - 1; i >= 0; i--) {
            Box other = stash.boxes.get(i);
            if (other == target || !target.has(other.pos)) continue;
            carryMarks(other, target);
            stash.boxes.remove(i);
        }
        stash.markDirty();
        return true;
    }

    /**
     * What a box that is being dropped as a duplicate was marked as carries over to the box that stays.
     */
    private static void carryMarks(Box from, Box to) {
        to.excluded |= from.excluded;
        to.dropOff |= from.dropOff;
    }

    // Moving items

    /**
     * Moves one stack that belongs somewhere else into a free carry slot.
     * @return false when there's nothing more to take, or nowhere to carry it
     */
    private boolean takeOne() {
        int into = freeCarrySlot();
        if (into == -1) return false;

        int slot = takeableSlot(target);
        if (slot == -1) return false;

        // Straight into a carry slot, so it can never land in or top up one of the player's own stacks
        stashHeld.put(into, target.contents[slot].copy());
        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, target.contents.length));
        return true;
    }

    /**
     * Takes the one shulker this trip came for into a free carry slot, to repack it.
     * @return false once it's taken, or if it's gone
     */
    private boolean fetchOne() {
        if (wantSlot < 0 || actions > 0) return false;

        int into = freeCarrySlot();
        if (into == -1 || wantSlot >= target.contents.length || !fetchWants.test(target.contents[wantSlot])) return false;

        stashHeld.put(into, target.contents[wantSlot].copy());
        InvUtils.move().fromId(wantSlot).toId(inventoryToMenu(into, target.contents.length));
        wantSlot = -1;
        return true;
    }

    /**
     * A slot in this box holding something that belongs elsewhere, where that somewhere still has room once what
     * we're already carrying is put away. -1 if there's none.
     */
    private int takeableSlot(Box source) {
        // What each destination can still take, so we don't pick up more than we can put away
        Map<Box, Integer> room = new HashMap<>();
        for (Box box : stash.boxes) {
            // Only where a deposit could go: not a container being skipped
            if (box.sortable() && !box.dropOff && box != source && !blocked(box, Job.Deposit) && !signPending(box)) room.put(box, box.free() + misplacedCount(box));
        }
        for (int carried : carriedSlots()) {
            for (Box box : destinationsFor(mc.player.getInventory().getItem(carried))) {
                if (room.containsKey(box)) {
                    room.merge(box, -1, Integer::sum);
                    break;
                }
            }
        }

        // Drop-offs are emptied even when nowhere particular has room: whatever doesn't fit goes to a misc chest,
        // or any container with space
        int spare = source.dropOff ? spareRoom(source) : 0;

        for (int i = 0; i < source.contents.length; i++) {
            ItemStack stack = source.contents[i];
            if (stack.isEmpty() || !misplaced(stack, source)) continue;
            // A bundle with things in it needs a slot for itself and one for what comes out
            if (fullBundle(stack)) {
                if (freeCarryCount() >= 2) return i;
                continue;
            }
            if (destinationsFor(stack).stream().anyMatch(b -> room.getOrDefault(b, 0) > 0)) return i;
            if (spare > 0) return i;
        }

        return -1;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne() {
        int size = target.contents.length;

        // Mid-swap: carried stack on the cursor, then into the slot, then what was there back into the carry slot
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (swapStep > 0) {
            // The cursor must hold what the last click should have left on it, otherwise a click was rejected
            ItemStack expected = swapStep == 1 ? swapStack : swapDisplaced;
            if (!ItemStack.matches(carried, expected)) {
                swapStep = 0;
                return recoverBounded();
            }

            if (swapStep == 1) InvUtils.click().slotId(swapSlot);
            else InvUtils.click().slotId(inventoryToMenu(swapCarry, size));

            swapStep = swapStep == 2 ? 0 : swapStep + 1;
            if (swapStep == 0) moved++;
            return true;
        }

        // A stack left on the cursor by an earlier hiccup goes away before anything else is clicked
        if (!carried.isEmpty()) return recoverBounded();

        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            // A bundle with things in it is emptied, never put away as it is
            if (fullBundle(stack)) continue;
            if (!destinationsFor(stack).contains(target)) continue;

            if (canFit(stack)) {
                InvUtils.shiftClick().slotId(inventoryToMenu(slot, size));
                moved++;
                return true;
            }

            for (int i = 0; i < size; i++) {
                ItemStack here = target.contents[i];
                // A click of one stack on a bundle puts it in the bundle, and a bundle on a stack takes it in: never swapped
                if (here.isEmpty() || isBundle(here) || isBundle(stack) || !misplaced(here, target) || ItemStack.isSameItemSameComponents(here, stack)) continue;

                swapCarry = slot;
                swapSlot = i;
                swapStack = stack.copy();
                swapDisplaced = here.copy();
                stashHeld.put(slot, here.copy());
                swapStep = 1;
                InvUtils.click().slotId(inventoryToMenu(slot, size));
                return true;
            }
        }

        return false;
    }

    /**
     * recoverCursor, but gives up after a few tries in one job, so a click the server keeps rejecting can't loop forever.
     * Giving up counts as getting nothing done, so the box is skipped until something else moves.
     */
    private boolean recoverBounded() {
        if (++recoveries > 3) {
            actions = 0;
            return false;
        }
        return recoverCursor();
    }

    /**
     * Puts whatever is on the cursor into an empty slot, the player's own slots first.
     * @return false if there's nothing to put it in
     */
    private boolean recoverCursor() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu.getCarried().isEmpty()) return true;
        lastOwnAct = ticks;

        int size = menu.slots.size() - 36;
        // The carry slots first, so the stack is put away with the rest, and noted as the stash's own
        for (int pass = 0; pass < 2; pass++) {
            for (int i = size; i < menu.slots.size(); i++) {
                if (!menu.getSlot(i).getItem().isEmpty()) continue;
                int slot = i - size < 27 ? 9 + i - size : i - size - 27;
                if ((pass == 0) != carry.contains(slot)) continue;

                stashHeld.put(slot, menu.getCarried().copy());
                InvUtils.click().slotId(i);
                return true;
            }
        }
        // Only into the container we're working on, never some other menu
        if (target != null && menu instanceof ChestMenu && size == target.parts.size() * 27) {
            for (int i = 0; i < size; i++) {
                if (menu.getSlot(i).getItem().isEmpty()) {
                    InvUtils.click().slotId(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Puts carried stacks down in a container with space, when nowhere they belong has room for them.
     */
    private boolean dumpOne() {
        for (int slot : carriedSlots()) {
            if (kitBuilder.holds(slot) || fullBundle(mc.player.getInventory().getItem(slot)) || !canFit(mc.player.getInventory().getItem(slot))) continue;

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

    // Planning

    /**
     * Works out where everything belongs. Signs mode needs no planning, each item is matched on its own.
     */
    private void plan() {
        tierBoxes.clear();
        if (mode.get() != Mode.Tiers) return;

        List<Box> ordered = stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff).sorted(Comparator.comparingInt(b -> b.order)).toList();

        // How many stacks of each tier there are, including drop-offs and what we're carrying
        int[] counts = new int[5];
        for (Box box : stash.boxes) {
            if (!box.sortable()) continue;
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
     * particular, in which case it's left where it is. Never an excluded or drop-off container.
     */
    private List<Box> destinationsFor(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        if (destCache == null) return computeDestinations(stack);
        return destCache.computeIfAbsent(DestKey.of(stack), k -> computeDestinations(stack));
    }

    private List<Box> computeDestinations(ItemStack stack) {
        if (mode.get() == Mode.Tiers) return tierBoxes.getOrDefault(tier(stack), List.of());

        // An empty bundle goes to a chest signed for bundles, else one signed for shulkers or containers
        if (isBundle(stack)) {
            List<Box> bundles = bundleHomes(stack);
            if (!bundles.isEmpty()) return bundles;
        }

        // A finished kit, or one part built with more than one kind of item, goes to the chest signed with its name.
        // With no such chest it's treated like any other shulker.
        Kits.Match kit = Kits.match(stack);
        Kit owner = kit != null && kit.claims() ? kit.kit() : kitBuilder.heldKit(stack);
        if (owner != null) {
            List<Box> homes = Kits.homes(owner, stash);
            if (!homes.isEmpty()) return homes;
        }

        // A shulker goes where what it's mostly full of goes: a shulker of totems belongs with the totems. Only when
        // no sign describes its contents does it fall back to signs about shulkers themselves, like "shulkers" or "kits".
        // Mixed shulkers go whole to a chest signed for kits, when there is one
        if (mixedShulker(stack)) {
            List<Box> kits = keepWholeBoxes();
            if (!kits.isEmpty()) return kits;
        }

        ItemStack inside = Categories.dominantContent(stack);
        if (inside != null) {
            List<Box> byContents = bestSigned(inside);
            if (!byContents.isEmpty()) return byContents;
        }

        return bestSigned(stack);
    }

    /**
     * The containers whose signs describe this item best, all tied, or empty if no sign describes it.
     */
    private List<Box> bestSigned(ItemStack stack) {
        int best = 0;
        List<Box> result = new ArrayList<>();
        Categories.Described item = Categories.describe(stack);
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;

            // Kit chests only take their kits, not loose items that happen to match the name
            if (isKitBox(box)) continue;

            int score = 0;
            for (String label : box.labels) score = Math.max(score, Categories.signScore(label, item));

            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        if (result.size() > 1) reportTie(stack, result);
        return result;
    }

    // Item id plus the tied containers' positions already reported as fitting two differently signed containers equally.
    // Only cleared when the module is switched on again, so each tie is said once per run (or again when the tied set changes)
    private final Set<String> tiesReported = new HashSet<>();

    /**
     * Two containers with different signs describe an item equally well: it goes to the nearest. Several containers with
     * the same sign are no tie, that is just a category with more than one chest.
     */
    private void reportTie(ItemStack stack, List<Box> tied) {
        Set<String> first = new HashSet<>(tied.getFirst().labels);
        boolean different = false;
        for (Box other : tied) if (!new HashSet<>(other.labels).equals(first)) different = true;
        if (!different) return;
        String key = idOf(stack) + "|" + tied.stream().map(b -> b.pos.toString()).sorted().collect(java.util.stream.Collectors.joining(";"));
        if (tiesReported.add(key)) {
            warning("%s fits %d containers with different signs equally well (%s), it goes to the nearest.", StackInfo.label(stack), tied.size(),
                String.join(" / ", tied.stream().map(b -> b.labels.isEmpty() ? "?" : b.labels.getFirst()).distinct().toList()));
        }
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(ItemStack stack, Box box) {
        // Nothing stays in a drop-off
        if (box.dropOff) return true;

        // A bundle with things in it is taken out to be emptied, wherever it is
        if (fullBundle(stack)) return true;

        // A kit, or part of one, in its own chest is where it belongs
        if (mode.get() == Mode.Signs) {
            Kits.Match kit = Kits.match(stack);
            if (kit != null && Kits.isHome(box, kit.kit())) return false;
        }

        List<Box> places = destinationsFor(stack);
        return !places.isEmpty() && !places.contains(box);
    }

    /**
     * A container signed for anything: misc, other, overflow and the like.
     */
    private boolean isMisc(Box box) {
        return box.labels.stream().anyMatch(Categories::isCatchAll);
    }

    /**
     * Free slots in the containers that take anything (signed misc, other, overflow, or unsigned), outside drop-offs and
     * {@code except}, less what we're already carrying.
     */
    private int spareRoom(Box except) {
        int free = 0;
        for (Box box : stash.boxes) if (box != except && (takesAnything(box, true) || takesAnything(box, false))) free += box.free();
        return free - carriedSlots().size();
    }

    /**
     * Whether a carried stack has somewhere to go right now: a container it belongs in that has room, or one that
     * takes anything.
     */
    private boolean canPlace(ItemStack stack) {
        for (Box box : destinationsFor(stack)) {
            if (!blocked(box, Job.Deposit) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) return true;
        }
        return spareBox() != null;
    }

    /**
     * Where a stack with no room in the containers it belongs in can be put down: a container signed misc, other or
     * overflow first; failing that an unsigned one with space, which is a free container waiting for a sign.
     */
    private Box spareBox() {
        Box misc = nearest(stash.boxes.stream().filter(b -> takesAnything(b, true)).toList());
        if (misc != null) return misc;

        // Unsigned: one already in use before an empty one, which a sign could still be put on
        List<Box> unsigned = stash.boxes.stream().filter(b -> takesAnything(b, false)).toList();
        Box used = nearest(unsigned.stream().filter(b -> b.free() < b.contents.length).toList());
        return used != null ? used : nearest(unsigned);
    }

    private boolean takesAnything(Box b, boolean signedMisc) {
        if (!b.sortable() || b.dropOff || b.free() <= 0 || blocked(b, Job.Dump) || signPending(b) || isKitBox(b)) return false;
        if (signedMisc) return isMisc(b);

        // Tiers has no signs to respect: any container with space will do
        if (mode.get() == Mode.Tiers) return true;

        // Only in Signs mode, and only a container whose signs can be read now: no sign, not even a blank one
        if (!unsignedOverflow.get() || mode.get() != Mode.Signs || !b.labels.isEmpty()) return false;
        return noSignNow(b);
    }

    /**
     * Whether there is no sign block at all on any side of the container (or on top of it) right now. A blank sign
     * counts: it's a sign that's about to be written. False if any of the places isn't loaded.
     */
    private boolean noSignNow(Box box) {
        for (BlockPos part : box.parts) {
            for (Direction dir : HORIZONTALS) {
                BlockPos signPos = part.relative(dir);
                if (!mc.level.isLoaded(signPos)) return false;
                // Any sign beside it counts, whichever way it faces, hanging ones too
                if (mc.level.getBlockEntity(signPos) instanceof SignBlockEntity) return false;
            }

            BlockPos above = part.above();
            if (!mc.level.isLoaded(above)) return false;
            if (mc.level.getBlockEntity(above) instanceof SignBlockEntity) return false;
        }
        return true;
    }

    /**
     * Two partial stacks of the same item in this container, {first, second}, or null if there are none to merge.
     */
    private static int[] mergeable(Box box) {
        ItemStack[] c = box.contents;
        for (int i = 0; i < c.length; i++) {
            ItemStack a = c[i];
            if (a.isEmpty() || isBundle(a) || a.getMaxStackSize() <= 1 || a.getCount() >= a.getMaxStackSize()) continue;
            for (int j = c.length - 1; j > i; j--) {
                ItemStack b = c[j];
                if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b) && b.getCount() < b.getMaxStackSize()) return new int[] {i, j};
            }
        }
        return null;
    }

    /**
     * Merges one later partial stack into an earlier one: pick it up, drop it on the earlier stack, put back what's left.
     */
    private boolean compactOne() {
        int[] pair = mergeable(target);
        if (pair == null) return false;

        InvUtils.click().slotId(pair[1]);
        InvUtils.click().slotId(pair[0]);
        if (!mc.player.containerMenu.getCarried().isEmpty()) InvUtils.click().slotId(pair[1]);
        return true;
    }

    /**
     * Chests signed for kits, which take mixed shulkers whole.
     */
    private List<Box> keepWholeBoxes() {
        return stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && b.labels.stream().anyMatch(Categories::isKeepWhole)).toList();
    }

    /**
     * A shulker holding more than one kind of item.
     */
    private static boolean mixedShulker(ItemStack stack) {
        if (stack.isEmpty() || !Utils.isShulker(stack.getItem())) return false;
        Item first = null;
        for (ItemStack s : inside(stack)) {
            if (s == null || s.isEmpty()) continue;
            if (first == null) first = s.getItem();
            else if (s.getItem() != first) return true;
        }
        return false;
    }

    private int misplacedCount(Box box) {
        if (misplacedCache == null) return countMisplaced(box);
        Integer cached = misplacedCache.get(box);
        if (cached != null) return cached;
        int count = countMisplaced(box);
        misplacedCache.put(box, count);
        return count;
    }

    private int countMisplaced(Box box) {
        int count = 0;
        for (ItemStack stack : box.contents) if (!stack.isEmpty() && misplaced(stack, box)) count++;
        return count;
    }

    /**
     * 0 filled shulkers, 1 empty shulkers, 2 stacks to 64, 3 stacks to 16, 4 everything else.
     */
    private static int tier(ItemStack stack) {
        if (Utils.isShulker(stack.getItem())) {
            ItemStack[] contents = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, contents);
            for (ItemStack s : contents) if (s != null && !s.isEmpty()) return 0;
            return 1;
        }
        if (stack.getMaxStackSize() == 64) return 2;
        if (stack.getMaxStackSize() == 16) return 3;
        return 4;
    }

    // Containers and signs

    /**
     * Adds containers around us that aren't in the stash yet, and drops ones that have been broken.
     * @return how many were added
     */
    private int addNearbyBoxes() {
        BlockPos center = mc.player.blockPosition();
        int r = radius.get(), h = height.get();
        int added = 0;

        // With a perimeter, nothing outside it belongs to the stash
        if (stash.hasArea() && stash.boxes.removeIf(box -> box.parts.stream().noneMatch(stash::inArea))) stash.markDirty();
        if (stash.boxes.removeIf(box -> mc.level.isLoaded(box.pos) && !isContainer(box.pos))) stash.markDirty();

        // A chest next to a saved one may have made it a double (or a double been halved): bring the parts up to date
        // and forget one that is now just the other half of an earlier box. Changed boxes get read again.
        for (Box box : stash.boxes) {
            if (!mc.level.isLoaded(box.pos)) continue;
            List<BlockPos> now = makeBox(box.pos).parts;
            if (now.equals(box.parts)) continue;

            boolean resized = now.size() != box.parts.size();
            box.parts.clear();
            box.parts.addAll(now);
            if (resized) box.contents = null;
            stash.markDirty();
        }
        for (int i = stash.boxes.size() - 1; i > 0; i--) {
            for (int j = 0; j < i; j++) {
                if (stash.boxes.get(j).has(stash.boxes.get(i).pos)) {
                    carryMarks(stash.boxes.get(i), stash.boxes.get(j));
                    stash.boxes.remove(i);
                    stash.markDirty();
                    break;
                }
            }
        }

        // The perimeter replaces radius and height: every container inside it, in the chunks that are loaded
        if (stash.hasArea()) return added + scanArea();

        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -h, -r), center.offset(r, h, r))) {
            if (!isContainer(p) || stash.boxAt(p) != null) continue;
            stash.boxes.add(makeBox(p.immutable()));
            added++;
        }

        if (added > 0) stash.markDirty();
        return added;
    }

    private boolean isContainer(BlockPos pos) {
        BlockEntity entity = mc.level.getBlockEntity(pos);
        return entity instanceof ChestBlockEntity || (barrels.get() && entity instanceof BarrelBlockEntity);
    }

    private Box makeBox(BlockPos pos) {
        List<BlockPos> parts = new ArrayList<>(List.of(pos));
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
        }
        return new Box(pos, parts);
    }

    private void orderBoxes() {
        // Tiers fill the nearest containers first
        Vec3 start = mc.player.position();
        List<Box> sorted = new ArrayList<>(stash.boxes);
        sorted.sort(Comparator.<Box>comparingDouble(b -> start.distanceToSqr(Vec3.atCenterOf(b.pos)))
            .thenComparingInt(b -> b.pos.getY()).thenComparingInt(b -> b.pos.getX()).thenComparingInt(b -> b.pos.getZ()));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).order = i;
    }

    /**
     * Signs on the side of a container facing away from it, or standing on top of it. Each line is a label, and so
     * are all the lines of a sign read together, for labels split across lines.
     */
    private void readSigns(Box box) {
        Containers.Signs signs = Containers.read(stash, box);
        if (signs == null) return;
        box.labels.clear();
        box.labels.addAll(signs.labels());
    }

    // Picking containers

    /**
     * @return true when picking, so the click doesn't open the container
     */
    private boolean onRightClick() {
        if (pick.get() == Pick.Off) return false;
        if (mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos pos = hit.getBlockPos();
        if (pick.get() == Pick.Corner1 || pick.get() == Pick.Corner2) {
            setCorner(pick.get() == Pick.Corner1 ? 1 : 2, pos.immutable());
            return true;
        }
        if (!isContainer(pos)) {
            warning("That isn't a chest or barrel.");
            return true;
        }

        if (ensureStash() == null) stash = new Stash(stashName.get(), Stash.currentServer());
        Box box = stash.boxAt(pos);
        if (box == null) {
            box = makeBox(pos.immutable());
            stash.boxes.add(box);
        }

        if (pick.get() == Pick.CompactSkip) {
            box.noCompact = !box.noCompact;
            info(box.noCompact ? "%s is never used to gather for compacting." : "%s can be used for compacting again.", box.pos.toShortString());
        }
        else if (pick.get() == Pick.Exclude) {
            box.excluded = !box.excluded;
            box.dropOff = false;
            info(box.excluded ? "%s won't be sorted." : "%s will be sorted again.", box.pos.toShortString());
        }
        else {
            box.dropOff = !box.dropOff;
            box.excluded = false;
            info(box.dropOff ? "%s is a drop-off, it'll be emptied into the stash." : "%s is a normal container again.", box.pos.toShortString());
        }

        stash.save();
        version++;
        return true;
    }

    // The stash perimeter

    /**
     * Makes the block the corner of the stash perimeter. The perimeter only applies once both corners are set.
     */
    private void setCorner(int which, BlockPos pos) {
        if (ensureStash() == null) stash = new Stash(stashName.get(), Stash.currentServer());
        // Corners set in another dimension don't go with this one
        String dimension = Stash.currentDimension();
        if (stash.hasCorners() || stash.corner1 != null || stash.corner2 != null) {
            if (!stash.areaDimension.isEmpty() && !stash.areaDimension.equals(dimension)) {
                stash.corner1 = null;
                stash.corner2 = null;
            }
        }
        BlockPos other = which == 1 ? stash.corner2 : stash.corner1;
        if (other != null && Stash.tooBig(pos, other)) {
            error("That area is %d\u00d7%d\u00d7%d, the largest allowed is %d\u00d7%d\u00d7%d (width \u00d7 height \u00d7 depth).",
                Math.abs(pos.getX() - other.getX()) + 1, Math.abs(pos.getY() - other.getY()) + 1, Math.abs(pos.getZ() - other.getZ()) + 1,
                Stash.MAX_X, Stash.MAX_Y, Stash.MAX_Z);
            return;
        }

        if (which == 1) stash.corner1 = pos;
        else stash.corner2 = pos;
        stash.areaDimension = dimension;
        stash.markDirty();
        stash.save();

        if (stash.hasArea()) {
            info("Stash area set: %s.", stash.areaText());
            areaChanged();
        }
        else info("Corner %d set at [%d, %d, %d]. Set corner %d to finish the stash area.", which, pos.getX(), pos.getY(), pos.getZ(), which == 1 ? 2 : 1);
    }

    private void clearArea() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null)) {
            info("There is no stash area set.");
            return;
        }
        boolean applied = stash.hasArea();
        stash.corner1 = null;
        stash.corner2 = null;
        stash.areaDimension = "";
        stash.markDirty();
        stash.save();
        info("Stash area cleared, finding containers around you again.");
        if (applied) areaChanged();
    }

    /**
     * The perimeter was set or cleared: nothing counts as explored or unreachable any more, and an exploration under
     * way ends at once. With the module on, dropping containers outside it, adding the ones inside and the new order wait
     * until between jobs, so nothing being worked on changes under it.
     */
    private void areaChanged() {
        forgetExploration();
        if (job == Job.Explore) finishExplore();
        if (isActive()) {
            areaDirty = true;
            version++;
            idle = false;
        }
        else applyArea();
    }

    private void applyArea() {
        areaDirty = false;
        if (mc.player == null || mc.level == null || stash == null) return;
        addNearbyBoxes();
        orderBoxes();
        stash.save();
        version++;
        idle = false;
    }

    /**
     * Starts the exploration over, and forgets what went wrong: containers that could not be reached or opened, surveys
     * and jobs given up on, so everything gets another try.
     */
    private void forgetExploration() {
        exploredChunks.clear();
        exploreSkipped.clear();
        exploring = false;
        exploreRan = false;
        unreachableReported.clear();
        failedSurvey.clear();
        futile.clear();
        noOpReported.clear();
        cancelRetries.clear();
        clearStalled();
        if (stash != null) {
            for (Box box : stash.boxes) {
                box.unreachable = false;
                box.labelFailed = false;
            }
        }
    }

    /**
     * Adds the containers inside the perimeter in every chunk that is loaded, and notes those chunks as seen. Goes through
     * the chunks' block entities directly.
     * @return how many containers were added
     */
    private int scanArea() {
        if (stash == null || !stash.hasArea() || mc.level == null) return 0;
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        boolean withBarrels = barrels.get();
        int added = 0;

        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                exploredChunks.add(ChunkPos.asLong(cx, cz));

                for (BlockEntity entity : mc.level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(entity instanceof ChestBlockEntity || (withBarrels && entity instanceof BarrelBlockEntity))) continue;
                    BlockPos pos = entity.getBlockPos();
                    if (!stash.inArea(pos) || stash.boxAt(pos) != null) continue;
                    stash.boxes.add(makeBox(pos.immutable()));
                    added++;
                }
            }
        }

        if (added > 0) {
            stash.markDirty();
            orderBoxes();
        }
        return added;
    }

    private int columnsTouched() {
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        return ((max.getX() >> 4) - (min.getX() >> 4) + 1) * ((max.getZ() >> 4) - (min.getZ() >> 4) + 1);
    }

    private List<Long> pendingChunks() {
        List<Long> list = new ArrayList<>();
        BlockPos min = stash.areaMin(), max = stash.areaMax();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (!exploredChunks.contains(key) && !exploreSkipped.contains(key)) list.add(key);
            }
        }
        return list;
    }

    /** The middle of the part of the chunk column inside the perimeter, in X. */
    private int columnX(long key) {
        int cx = (int) (key & 0xFFFFFFFFL);
        return (Math.max(stash.areaMin().getX(), cx * 16) + Math.min(stash.areaMax().getX(), cx * 16 + 15)) / 2;
    }

    /** The middle of the part of the chunk column inside the perimeter, in Z. */
    private int columnZ(long key) {
        int cz = (int) (key >>> 32);
        return (Math.max(stash.areaMin().getZ(), cz * 16) + Math.min(stash.areaMax().getZ(), cz * 16 + 15)) / 2;
    }

    private void warnNoBaritone() {
        if (baritoneWarned) return;
        baritoneWarned = true;
        warning("Baritone isn't installed, so I can't walk the stash area or explore it. Containers in loaded chunks are still used.");
    }

    /**
     * The checks made when turning on: enough containers, and signs on some in Signs mode. With a perimeter they wait for
     * the exploration, which is what finds the containers.
     * @return false if the module was turned off for failing one
     */
    private boolean checkStash() {
        long sortable = stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (!sortItems.get()) {
            if (stash.boxes.isEmpty()) {
                error("Found no containers within %d blocks.", radius.get());
                toggle();
                return false;
            }
        }
        else if (sortable < 2) {
            error("Found %d container%s to sort within %d blocks, need at least 2.", sortable, sortable == 1 ? "" : "s", radius.get());
            toggle();
            return false;
        }

        if (sortItems.get() && mode.get() == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            error("None of the containers have signs on them. Put signs on them, or use Tiers mode.");
            toggle();
            return false;
        }
        return true;
    }

    /**
     * With a perimeter set: walks to the nearest chunk column of it not seen loaded yet, so it loads and its containers
     * can be added. The goal is the middle of the part of the column inside the perimeter, in X and Z only. The activation
     * checks follow the exploration.
     * @return true when a trip has begun
     */
    private boolean startExplore() {
        if (stash == null || !stash.hasArea()) return false;
        scanArea();
        if (!BARITONE) {
            warnNoBaritone();
            return false;
        }

        if (!exploreRan) {
            exploreRan = true;
            exploreRunStart = ticks;
            exploring = true;
            info("Exploring the stash area: %d chunks to look at.", columnsTouched());
        }
        if (!exploring) return false;
        // One run takes half an hour at the most: whatever is still unseen is given up on, and the run is reported
        if (ticks - exploreRunStart > EXPLORE_RUN_LIMIT) {
            for (long key : pendingChunks()) exploreSkipped.add(key);
            info("Exploring the stash area took more than 30 minutes, carrying on with what was found.");
        }

        List<Long> pending = pendingChunks();
        if (pending.isEmpty()) {
            exploring = false;
            orderBoxes();
            stash.save();
            long inside = stash.boxes.stream().filter(b -> b.parts.stream().anyMatch(stash::inArea)).count();
            info("Explored the stash area: found %d containers (%d chunks couldn't be reached).", inside, exploreSkipped.size());
            checkStash();
            return false;
        }

        Vec3 here = mc.player.position();
        long best = pending.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (long key : pending) {
            double dx = columnX(key) - here.x, dz = columnZ(key) - here.z;
            if (dx * dx + dz * dz < bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = key;
            }
        }

        exploreTarget = best;
        exploreX = columnX(best);
        exploreZ = columnZ(best);
        exploreTicks = 0;
        exploreIssue = 0;
        exploreStanding = 0;
        exploreBest = Double.MAX_VALUE;
        exploreBestTick = 0;

        job = Job.Explore;
        target = null;
        handle = null;
        actions = 0;
        jobCounted = false;
        stepTicks = 0;
        jobInterruptions = 0;
        startSigSet = endSigSet = false;
        settled = false;
        openPos = null;
        openMenuBefore = null;
        walkXZ(exploreX, exploreZ);
        return true;
    }

    private void exploreTick() {
        exploreTicks++;
        if (stash == null || !stash.hasArea() || !BARITONE) {
            finishExplore();
            return;
        }
        if (exploreTicks % 5 == 0) scanArea();
        // Done as soon as the chunk is loaded
        if (exploredChunks.contains(exploreTarget)) {
            finishExplore();
            return;
        }

        Vec3 here = mc.player.position();
        double distance = Math.sqrt((here.x - exploreX) * (here.x - exploreX) + (here.z - exploreZ) * (here.z - exploreZ));
        if (distance < exploreBest - 1) {
            exploreBest = distance;
            exploreBestTick = exploreTicks;
        }
        exploreStanding = distance <= 2 ? exploreStanding + 1 : 0;

        String why = null;
        if (leftArea()) why = "the way there leaves the stash area";
        else if (exploreTicks > EXPLORE_TIMEOUT) why = "it took longer than 40 seconds";
        else if (exploreTicks - exploreBestTick > EXPLORE_STUCK) why = "no way closer for 15 seconds";
        else if (exploreStanding >= EXPLORE_STANDING) why = "it is still not loaded at the spot";
        if (why != null) {
            exploreSkipped.add(exploreTarget);
            int cx = (int) (exploreTarget & 0xFFFFFFFFL), cz = (int) (exploreTarget >>> 32);
            if (safeWalk.get() && !why.startsWith("the way")) debug("Can't reach the chunk at %d, %d without breaking or placing blocks, jumping or falling, skipping it.", cx, cz);
            else debug("Skipping the chunk at %d, %d: %s.", cx, cz, why);
            finishExplore();
            return;
        }

        // Baritone stopped short, told again
        if (exploreTicks >= 10 && exploreTicks - exploreIssue >= 60 && !BaritoneHelper.isMoving()) {
            exploreIssue = exploreTicks;
            walkXZ(exploreX, exploreZ);
        }
    }

    private void finishExplore() {
        releaseGoal();
        if (job == Job.Explore) job = null;
        wait = delay.get();
    }

    // Containers that can't be got to

    private static String at(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * What the block is called in messages: chest, trapped chest or barrel.
     */
    private String blockName(Box box) {
        if (mc.level == null) return "chest";
        var block = mc.level.getBlockState(box.pos).getBlock();
        if (block instanceof TrappedChestBlock) return "trapped chest";
        if (block instanceof BarrelBlock) return "barrel";
        return "chest";
    }

    private enum Cause { Path, Open, Area }

    /**
     * Says once per container why it is skipped.
     */
    private void reportUnreachable(Box box, Cause cause) {
        if (!unreachableReported.add(box.pos)) return;
        String name = blockName(box);
        switch (cause) {
            case Path -> {
                if (safeWalk.get()) warning("Can't reach the %s at %s without breaking or placing blocks, jumping or falling, skipping it.", name, at(box.pos));
                else warning("Can't reach the %s at %s without breaking or placing blocks, skipping it.", name, at(box.pos));
            }
            case Open -> warning("Couldn't open the %s at %s, skipping it.", name, at(box.pos));
            case Area -> warning("Skipping the %s at %s: the way there leaves the stash area.", name, at(box.pos));
        }
    }

    private String areaText() {
        if (ensureStash() == null || (stash.corner1 == null && stash.corner2 == null)) return "Stash area: none, containers are found around you (radius and height).";
        if (stash.hasCorners() && !stash.hasArea()) return "Stash area: set in another dimension, not used here.";
        if (!stash.hasArea()) return "Stash area: corner " + (stash.corner1 != null ? "1 is set at " + stash.corner1.toShortString() : "2 is set at " + stash.corner2.toShortString()) + ", the other is missing.";
        return "Stash area: " + stash.areaText() + ".";
    }

    /**
     * Outlines the stash perimeter. Also called by stash-overlay when this module is off.
     */
    public void drawArea(Render3DEvent event) {
        // The tracker keeps the stash loaded every tick, even with the module off: nothing is looked up per frame
        Stash current = stash;
        if (mc.level == null || current == null || !current.hasArea()) return;
        if (current.corner1 != boxCorner1 || current.corner2 != boxCorner2) {
            boxCorner1 = current.corner1;
            boxCorner2 = current.corner2;
            BlockPos min = current.areaMin(), max = current.areaMax();
            boxMin = new double[] { min.getX(), min.getY(), min.getZ() };
            boxMax = new double[] { max.getX() + 1, max.getY() + 1, max.getZ() + 1 };
        }
        event.renderer.box(boxMin[0], boxMin[1], boxMin[2], boxMax[0], boxMax[1], boxMax[2], areaColor.get(), areaColor.get(), ShapeMode.Lines, 0);
    }

    // The outline as last worked out, for the corners it was worked out for
    private BlockPos boxCorner1, boxCorner2;
    private double[] boxMin = new double[3], boxMax = new double[3];

    // The stash, kept up to date

    /**
     * The stash named in the settings, loaded from disk the first time it's needed on this server.
     */
    private Stash ensureStash() {
        if (mc.level == null) return null;

        String server = Stash.currentServer();
        if (stash != null && stash.name.equals(stashName.get()) && stash.server.equals(server)) return stash;

        // Only try the disk once per name and server, a missing stash isn't looked for again every tick
        String key = server + "/" + stashName.get();
        if (!key.equals(triedKey)) {
            triedKey = key;
            stash = Stash.load(stashName.get());
            // Another stash, name or server: its exploration and what failed with the last one don't carry over
            forgetExploration();
            areaDirty = false;
        }
        else stash = null;

        return stash;
    }

    /**
     * The saved stash named in the settings, for other modules like stash-overlay. Null if there isn't one here.
     */
    public Stash currentStash() {
        return ensureStash();
    }

    private class Tracker {
        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            if (stash != null && stash.isDirty()) stash.save();
            stash = null;
            triedKey = null;
            trackedMenu = null;
            trackedBox = null;
            interactPos = null;
            interactMenu = null;
            replies.clear();
            kitBuilder.reset();
            stashHeld.clear();
            labeller.reset();
            compactor.reset();
            swapBackFrom = swapBackTo = -1;
            hotbarDisplaced = ItemStack.EMPTY;
            stashWatch.clear();
            ownTouch.clear();
            readAt.clear();
            staleBoxes.clear();
            deferredAccess.clear();
            lastPlayer = null;
            ground.reset();
            forgetExploration();
            // Whatever walk was ours is over with the world, and Baritone gets its settings back
            ourGoal = null;
            if (BARITONE) BaritoneHelper.unconfine();
            ledgeGuard(false);
            Kits.clear();
        }

        @EventHandler
        private void onPacketSend(PacketEvent.Send event) {
            // Whatever the player drops or breaks is theirs, from any source: the keys, clicks, other modules
            ground.onPacket(event.packet);
        }

        @EventHandler
        private void onOpenScreen(OpenScreenEvent event) {
            if (isActive() && job == Job.Label) labeller.onScreen(event);
        }

        @EventHandler
        private void onInteract(InteractBlockEvent event) {
            if (mc.player == null) return;
            interactPos = event.result.getBlockPos();
            interactMenu = mc.player.containerMenu;
            interactClock = clock;
            // Whoever's hand it is, the lid that opens next is not another player's
            if (stash != null && stash.boxAt(interactPos) != null) ownTouch.put(stash.boxAt(interactPos), clock);
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.level == null) return;
            clock++;
            ground.update();
            // Not walking for us: Baritone's settings are the user's own
            if (BARITONE) {
                if (ourGoal == null) BaritoneHelper.unconfine();
                // The user's own Baritone run took over our goal: their settings are theirs at once, not after the yield
                else if (BaritoneHelper.isActive() && !BaritoneHelper.goalIs((baritone.api.pathing.goals.Goal) ourGoal)) BaritoneHelper.unconfine();
            }
            // Safeguard for the sneak: it never outlives the module
            if (ledgeSneaking && !isActive()) ledgeGuard(false);
            labeller.restoreHand();
            restoreHotbar();

            // A new player entity means joining, dying or changing dimension: whatever was held is not ours to claim now
            if (mc.player != lastPlayer) {
                if (lastPlayer != null) {
                    stashHeld.clear();
                    swapBackFrom = swapBackTo = -1;
                    hotbarDisplaced = ItemStack.EMPTY;
                    // Dead, or in another dimension: a sign being put up is lost with the old player
                    labeller.abandon();
                    if (job == Job.Label) {
                        job = null;
                        target = null;
                    }
                    if (job == Job.Ground || job == Job.Explore || job == Job.Unbundle) job = null;
                    // Another dimension or a new life: nothing was explored there, and the area may not be used at all
                    forgetExploration();
                    // Another world or dimension: the ground is another ground
                    ground.reset();
                }
                lastPlayer = mc.player;
            }

            if (replyCooldown > 0) replyCooldown--;
            else if (!replies.isEmpty()) {
                ChatUtils.sendPlayerMsg(replies.poll());
                replyCooldown = REPLY_GAP_TICKS;
            }

            if (ensureStash() == null) return;
            watchStash();

            // The sorter reads the containers it opens itself
            if (isActive() && job != null) return;

            AbstractContainerMenu menu = mc.player.containerMenu;
            if (menu instanceof ChestMenu) {
                if (menu != trackedMenu) {
                    trackedMenu = menu;

                    // Only a menu that opened after our last interact, from that block, and of that box's size
                    trackedBox = null;
                    if (interactPos != null && interactMenu != menu && clock - interactClock <= INTERACT_WINDOW) {
                        Box box = stash.boxAt(interactPos);
                        if (box != null && menu.slots.size() - 36 == box.parts.size() * 27) trackedBox = box;
                    }
                    interactPos = null;
                    interactMenu = null;
                }

                if (trackedBox != null && changed(trackedBox, menu)) {
                    read(trackedBox);
                    version++;
                }
            }
            else if (trackedMenu != null) {
                trackedMenu = null;
                trackedBox = null;
                if (stash.isDirty()) stash.save();
            }
        }

        @EventHandler
        private void onMessage(ReceiveMessageEvent event) {
            if (!whisperSearch.get() || mc.player == null) return;

            String text = event.getMessage().getString().replaceAll("§.", "").trim();
            for (Pattern format : WHISPER_FORMATS) {
                Matcher m = format.matcher(text);
                if (m.matches()) {
                    onWhisper(m.group(1), m.group(2).trim());
                    return;
                }
            }
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (isActive()) drawArea(event);
            if (!render.get() || stash == null) return;

            if (isActive() || pick.get() != Pick.Off) {
                for (Box box : stash.boxes) {
                    SettingColor color = box.excluded ? excludedColor.get() : box.dropOff ? dropOffColor.get() : box.noCompact ? compactSkipColor.get() : null;
                    if (color == null) continue;
                    for (BlockPos part : box.parts) event.renderer.box(part, color, color, shapeMode.get(), 0);
                }
            }

            Box working = target;
            if (isActive() && working != null) {
                for (BlockPos part : working.parts) event.renderer.box(part, workColor.get(), workColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private static boolean changed(Box box, AbstractContainerMenu menu) {
        int size = menu.slots.size() - 36;
        if (!box.known() || box.contents.length != size) return true;
        for (int i = 0; i < size; i++) {
            if (!ItemStack.matches(box.contents[i], menu.getSlot(i).getItem())) return true;
        }
        return false;
    }

    private void read(Box box) {
        readAt.put(box, clock);
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (changed(box, menu)) stash.markDirty();

        int size = menu.slots.size() - 36;
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) contents[i] = menu.getSlot(i).getItem().copy();
        box.contents = contents;
    }

    // Watching the stash

    /**
     * Every tick, even with the module off: lids other players use, and while sorting in Signs mode the text of signs.
     */
    private void watchStash() {
        // Our own and the player's opens are not other players'
        if (isActive() && target != null && job != null) ownTouch.put(target, clock);
        if (trackedBox != null) ownTouch.put(trackedBox, clock);

        if (watch.get() && clock % LID_POLL_TICKS == 0) {
            stashWatch.tickLids(stash, clock, watchDelay.get() * 20L, this::openedByUs, this::accessed);
        }

        if (isActive() && signsWatched() && clock % SIGN_POLL_TICKS == 0) {
            stashWatch.pollSigns(stash, clock, b -> job == Job.Label && b == target);
        }
    }

    /**
     * Whether the sorter or the player opened this container just now, as opposed to somebody else.
     */
    private boolean openedByUs(Box box) {
        Integer at = ownTouch.get(box);
        return at != null && clock - at <= OWN_TOUCH_TICKS;
    }

    /**
     * Another player opened this container of the stash and has closed it again. It's read again through the normal
     * jobs while sorting, between jobs. With the module off it's only forgotten, to be read the next time it runs.
     */
    private void accessed(Box box) {
        debug("Another player used the container at %s.", box.pos.toShortString());
        if (isActive()) {
            // One we've just read ourselves isn't read again for a minute: a late lid event from our own opening on a
            // laggy server would otherwise start a loop. The visit is kept, once per container, for after that
            Integer last = readAt.get(box);
            if (last != null && clock - last < REREAD_COOLDOWN_TICKS) deferredAccess.putIfAbsent(box, clock);
            else staleBoxes.add(box);
            return;
        }

        // Written by the next save, not on every lid
        box.contents = null;
        stash.markDirty();
    }

    /**
     * Visits held back by the cooldown are read once it has passed, or dropped if we have read the container again
     * since the visit, which has shown what it holds now.
     */
    private void releaseDeferred() {
        if (deferredAccess.isEmpty()) return;
        for (Map.Entry<Box, Integer> entry : new ArrayList<>(deferredAccess.entrySet())) {
            Box box = entry.getKey();
            Integer last = readAt.get(box);
            if (last != null && clock - last < REREAD_COOLDOWN_TICKS) continue;

            deferredAccess.remove(box);
            if (last == null || last <= entry.getValue()) staleBoxes.add(box);
        }
    }

    /**
     * Forgets what is known of the containers that were used, so they're read again. Between jobs, so nothing being
     * worked on changes under it.
     */
    private void applyStale() {
        if (staleBoxes.isEmpty()) return;
        for (Box box : new ArrayList<>(staleBoxes)) {
            staleBoxes.remove(box);
            if (!stash.boxes.contains(box) || box.excluded) continue;

            box.contents = null;
            stash.markDirty();
            clearStalled();
            compacted.remove(box);
            failedSurvey.remove(box);
            idle = false;
            info("A player used the chest at %s, re-checking it.", box.pos.toShortString());
        }
    }

    /**
     * Signs are watched for changes: while sorting by signs, and in scan-only mode, which keeps the saved labels current.
     */
    private boolean signsWatched() {
        return watchSigns.get() && (mode.get() == Mode.Signs || !sortItems.get());
    }

    /**
     * Between jobs: signs that have been left alone for a while are read again and what they changed is sorted, and now
     * and then all of them, which catches what was missed (a chunk loading in).
     */
    private void applySignChanges() {
        if (!signsWatched()) return;

        if (clock >= nextSignSweep) {
            nextSignSweep = clock + SIGN_SWEEP_TICKS;
            for (Box box : new ArrayList<>(stash.boxes)) {
                if (!stashWatch.pending(box)) checkSigns(box);
            }
        }

        for (Box box : stashWatch.settled(clock, signDelay.get() * 20L)) {
            signAbortRuns.remove(box);
            if (signHeld.remove(box)) stalled.remove(box);
            // A job on it was ended because its sign was unsettled, and it was left out of the plans meanwhile: it gets
            // another look even if the text turns out the same
            if (signAborted.remove(box)) {
                clearStalled();
                idle = false;
            }
            else if (!stuckCarried.isEmpty()) idle = false;
            checkSigns(box);
        }

        // A sign gone or blank for the whole grace period is really gone
        for (Box box : stashWatch.goneLongEnough(clock)) checkSigns(box);
    }

    /**
     * Compares what the signs of a container say now with what we have, and applies the difference.
     */
    private void checkSigns(Box box) {
        List<String> now = stashWatch.labelChange(box, clock);
        if (now == null) return;

        List<String> before = new ArrayList<>(box.labels);
        box.labels.clear();
        box.labels.addAll(now);

        // A player's text on a sign the sorter wrote is theirs from then on
        if (box.autoLabel != null && !now.contains(box.autoLabel)) {
            box.autoLabel = null;
            box.autoSignPos = null;
        }

        // What was sorted by the old sign is sorted again by the new one: the planner already moves whatever is in the
        // wrong container, so it only has to be told to look
        stash.markDirty();
        version++;
        seenVersion = version;
        clearStalled();
        stuckCarried.clear();
        stuckWarned = false;
        compacted.remove(box);
        signHeld.remove(box);
        signAbortRuns.remove(box);
        futile.clear();
        idle = false;

        String was = before.isEmpty() ? "no sign" : before.getFirst(), is = now.isEmpty() ? "no sign" : now.getFirst();
        if (sortItems.get()) info("Sign at %s changed: \"%s\" -> \"%s\", re-sorting.", box.pos.toShortString(), was, is);
        else info("Sign at [%d, %d, %d] changed: \"%s\" -> \"%s\" (scan only).", box.pos.getX(), box.pos.getY(), box.pos.getZ(), was, is);
    }

    // Searching

    private void onWhisper(String sender, String message) {
        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (whitelist.get().stream().noneMatch(name -> name.equalsIgnoreCase(sender))) return;

        String query = message;
        String p = prefix.get().trim();
        if (!p.isEmpty()) {
            if (!message.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT) + " ")) return;
            query = message.substring(p.length()).trim();
        }
        if (query.isEmpty() || ensureStash() == null) return;

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            replies.add(String.format("/whisper %s Couldn't find %s @ %s", sender, query, stash.name));
            return;
        }

        Stash.Result result = results.getFirst();
        Stash.Spot spot = result.best();
        BlockPos pos = spot.box().pos;
        String where = spot.inShulker() ? " (in a shulker)" : "";
        replies.add(String.format("/whisper %s %s Can be Found At %d, %d, %d @ %s%s",
            sender, StackInfo.label(result.sample), pos.getX(), pos.getY(), pos.getZ(), stash.name, where));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WLabel status = list.add(theme.label(statusText())).expandX().widget();
        list.add(theme.label("Compacting: " + (sortItems.get() ? compactor.status() : "off, scan only") + ".")).expandX();
        list.add(theme.label("Ground: " + groundStatus() + ".")).expandX();

        WLabel area = list.add(theme.label(areaText())).expandX().widget();
        WHorizontalList areaButtons = list.add(theme.horizontalList()).widget();
        WButton corner1 = areaButtons.add(theme.button("Set corner 1 here")).widget();
        corner1.tooltip = "Makes the block you are standing in corner 1 of the stash perimeter.";
        corner1.action = () -> {
            if (mc.player != null) setCorner(1, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton corner2 = areaButtons.add(theme.button("Set corner 2 here")).widget();
        corner2.tooltip = "Makes the block you are standing in corner 2 of the stash perimeter.";
        corner2.action = () -> {
            if (mc.player != null) setCorner(2, mc.player.blockPosition());
            area.set(areaText());
        };
        WButton clearArea = areaButtons.add(theme.button("Clear area")).widget();
        clearArea.tooltip = "Removes the stash perimeter, so containers are found around you again.";
        clearArea.action = () -> {
            clearArea();
            area.set(areaText());
        };

        if (ensureStash() != null) {
            for (Stash.Orphan orphan : stash.orphans) {
                list.add(theme.label(String.format("Orphaned shulker: %d stacks of %s at %s (since %s). Pick it up by hand.",
                    orphan.stacks, orphan.item, orphan.pos.toShortString(), orphan.when))).expandX();
            }
        }

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();
        WButton rescan = buttons.add(theme.button("Forget contents")).widget();
        rescan.tooltip = "Forgets what's in every container, so the next run reads them all again, nearest first. Keeps the containers, signs, excluded and drop-off marks and kits.";
        rescan.action = () -> {
            if (ensureStash() == null) return;
            for (Box box : stash.boxes) box.contents = null;
            // The perimeter is explored again too, and containers that couldn't be reached get another try
            forgetExploration();
            stash.save();
            version++;
            idle = false;
            status.set(statusText());
        };

        WButton forget = buttons.add(theme.button("Forget stash")).widget();
        forget.tooltip = "Deletes the saved stash, including excluded and drop-off marks.";
        forget.action = () -> {
            Stash.delete(stashName.get());
            stash = null;
            triedKey = null;
            status.set(statusText());
        };

        list.add(theme.horizontalSeparator("Search"));
        WTextBox search = list.add(theme.textBox("")).expandX().widget();
        WTable results = list.add(theme.table()).expandX().widget();
        search.action = () -> fillResults(theme, results, search.get());

        list.add(theme.horizontalSeparator("Labels"));
        WVerticalList report = list.add(theme.verticalList()).expandX().widget();
        WButton refreshReport = list.add(theme.button("Labels report")).widget();
        refreshReport.tooltip = "What's homeless, what the sorter has labelled itself, and the chests it gave up on.";
        refreshReport.action = () -> fillReport(theme, report);
        fillReport(theme, report);

        list.add(theme.horizontalSeparator("Kits"));
        WHorizontalList saveRow = list.add(theme.horizontalList()).expandX().widget();
        WTextBox kitName = saveRow.add(theme.textBox("", "Kit name")).expandX().widget();
        WButton saveKit = saveRow.add(theme.button("Save kit")).widget();
        saveKit.tooltip = "Saves what's in your 27 main inventory slots (not the hotbar, offhand or armor) as a kit, with the same items in the same slots and the same counts.";
        WLabel kitStatus = list.add(theme.label("Put a kit in your main inventory, name it and save it.")).expandX().widget();
        WTable kits = list.add(theme.table()).expandX().widget();

        saveKit.action = () -> {
            String name = kitName.get().trim();
            String error = Kits.saveFromInventory(name);
            if (error != null) {
                kitStatus.set(error);
                return;
            }
            kitName.set("");
            kitStatus.set("Saved kit \"" + name + "\".");
            fillKits(theme, kits, kitStatus);
        };
        fillKits(theme, kits, kitStatus);

        return list;
    }

    private void fillReport(GuiTheme theme, WVerticalList report) {
        report.clear();
        for (String line : labeller.report()) report.add(theme.label(line)).expandX();
    }

    // The kit being asked to delete, until it's clicked again
    private Kit armedDelete;

    private void fillKits(GuiTheme theme, WTable table, WLabel status) {
        table.clear();
        armedDelete = null;
        if (mc.level == null || mc.player == null) {
            table.add(theme.label("Join a server to see its kits."));
            return;
        }

        List<Kit> saved = new ArrayList<>(Kits.all());
        if (saved.isEmpty()) {
            table.add(theme.label("No kits saved on this server."));
            return;
        }

        Stash current = ensureStash();
        for (Kit kit : saved) {
            ItemStack icon = ItemStack.EMPTY;
            for (int i = 0; i < Kit.SLOTS && icon.isEmpty(); i++) icon = kit.stackAt(i);
            table.add(theme.item(icon.copyWithCount(1)));
            table.add(theme.label(kit.name)).expandX();

            int possible = current == null ? 0 : kitBuilder.possible(kit);
            String more = possible < 0 ? String.format("no chest signed \"%s\"", kit.name) : String.format("%d more possible", possible);
            table.add(theme.label(String.format("%d slots, %d built, %s", kit.slotCount(), Kits.built(kit, current), more)));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> KitPreviewScreen.open(kit);

            WMinus delete = table.add(theme.minus()).widget();
            delete.tooltip = "Click twice to delete this kit.";
            delete.action = () -> {
                if (armedDelete != kit) {
                    armedDelete = kit;
                    status.set("Click the minus again to delete \"" + kit.name + "\".");
                    return;
                }
                Kits.delete(kit);
                fillKits(theme, table, status);
                status.set("Deleted kit \"" + kit.name + "\".");
            };
            table.row();
        }
    }

    private String statusText() {
        if (mc.level == null) return "Join a server to see its stashes.";
        if (ensureStash() == null) return String.format("No stash called \"%s\" here yet. Turn the module on to scan one.", stashName.get());

        long excluded = stash.boxes.stream().filter(b -> b.excluded).count();
        long dropOffs = stash.boxes.stream().filter(b -> b.dropOff).count();
        return String.format("\"%s\": %d containers (%d read, %d excluded, %d drop-off), %,d items.%s",
            stash.name, stash.boxes.size(), stash.knownCount(), excluded, dropOffs, stash.itemCount(), sortItems.get() ? "" : " Scan only.");
    }

    private String groundStatus() {
        if (!sortItems.get()) return "off (scan only)";
        if (!collectGround.get() || !isActive()) return "off";
        if (!BARITONE) return "off, Baritone isn't installed";
        return ground.status();
    }

    private void fillResults(GuiTheme theme, WTable table, String query) {
        table.clear();
        if (ensureStash() == null) {
            table.add(theme.label("No stash loaded."));
            return;
        }

        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) {
            if (!Categories.normalize(query).isEmpty()) table.add(theme.label("Nothing found."));
            return;
        }

        for (Stash.Result result : results.subList(0, Math.min(25, results.size()))) {
            Stash.Spot best = result.best();
            BlockPos pos = best.box().pos;
            int more = result.spots.size() - 1;

            table.add(theme.item(result.sample));
            table.add(theme.label(String.format("%s x%,d", StackInfo.label(result.sample), result.total))).expandX();
            table.add(theme.label(String.format("%d, %d, %d%s%s", pos.getX(), pos.getY(), pos.getZ(),
                best.inShulker() ? " (shulker)" : "", more > 0 ? String.format(" +%d more", more) : "")));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> ItemPreviewScreen.open(result.sample, result.total, stash.name);
            table.row();
        }
    }

    // Helpers

    /**
     * Inventory slots holding stash items we're carrying: not the player's own gear, food, totems and pearls, unless the
     * sorter put them there itself.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || isOwn(slot, stack)) continue;
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Carried stacks that something could take right now, not the ones already found to have nowhere to go.
     */
    private List<Integer> activeCarried() {
        List<Integer> slots = new ArrayList<>();
        Set<Integer> setAside = compactor.reserved();
        for (int slot : carriedSlots()) {
            // Signs kept for labelling, and what compacting is gathering and packing, are not put away
            if (labeller.reservedSlot(slot) || setAside.contains(slot)) continue;
            // A bundle with things in it is emptied, not put away
            if (fullBundle(mc.player.getInventory().getItem(slot))) continue;
            if (!idOf(mc.player.getInventory().getItem(slot)).equals(stuckCarried.get(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * The carry slots, less the ones holding the player's own gear.
     */
    private Set<Integer> ownedCarry() {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int slot : carry) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !isOwn(slot, stack)) slots.add(slot);
        }
        return slots;
    }

    private boolean isOwn(int slot, ItemStack stack) {
        // Moved out of the way of a sign or a shulker
        if (labeller.displaced(slot) || (slot == swapBackFrom && swapBackFrom != -1)) return true;
        if (!ownKind(stack)) return false;
        ItemStack held = stashHeld.get(slot);
        return held == null || !ItemStack.isSameItemSameComponents(stack, held);
    }

    /**
     * Between jobs, with no screen open: a slot we filled that is now empty or holds something else is no longer ours.
     */
    private void pruneHeld() {
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
        stashHeld.entrySet().removeIf(entry -> {
            ItemStack stack = mc.player.getInventory().getItem(entry.getKey());
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, entry.getValue())) return true;
            // Keep the count as it is now, so a later start only takes back exactly what was left here
            entry.setValue(stack.copy());
            return false;
        });
    }

    /**
     * What the player keeps for themselves: tools, weapons, armor, food, totems and ender pearls.
     */
    private static boolean ownKind(ItemStack stack) {
        String id = idOf(stack);
        if (stack.has(DataComponents.FOOD) || id.equals("totem_of_undying") || id.equals("ender_pearl")) return true;
        for (String suffix : OWN_SUFFIXES) {
            if (id.endsWith(suffix)) return true;
        }
        return switch (id) {
            case "bow", "crossbow", "trident", "shield", "fishing_rod", "shears", "elytra", "mace", "flint_and_steel" -> true;
            default -> false;
        };
    }

    private static final List<String> OWN_SUFFIXES = List.of("_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots");

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private int freeCarrySlot() {
        for (int slot : carry) if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        return -1;
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
}
