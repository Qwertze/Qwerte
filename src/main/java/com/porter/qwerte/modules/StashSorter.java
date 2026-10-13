package com.porter.qwerte.modules;

import com.porter.qwerte.QwerteAddon;
import com.porter.qwerte.compat.RightClick;
import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.ItemPreviewScreen;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import com.porter.qwerte.utils.BaritoneHelper;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
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
    private final SettingGroup sgShulkers = settings.createGroup("Shulkers");
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
        DropOff("Drop-off");

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

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Tiers: filled shulkers, empty shulkers, 64-stackables, 16-stackables, then singles, filling the nearest containers first. Signs: each item goes to the container whose signs describe it best.")
        .defaultValue(Mode.Tiers)
        .build()
    );

    private final Setting<Pick> pick = sgGeneral.add(new EnumSetting.Builder<Pick>()
        .name("pick")
        .description("Right-click containers to mark them. Exclude: never sorted into or out of. Drop-off: emptied into the stash, for others to drop items in. Right-click again to unmark. Works while the module is off.")
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

    // Shulkers

    private final Setting<Boolean> repack = sgShulkers.add(new BoolSetting.Builder()
        .name("repack")
        .description("Signs mode: takes items that belong elsewhere out of mixed shulkers, and packs loose items into shulkers that already hold only that item. Places the shulker beside you to do it, then breaks it and picks it back up. Named shulkers, and shulkers when a sign asks for them whole (like \"kits\"), are left as they are.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> newBulkAt = sgShulkers.add(new IntSetting.Builder()
        .name("new-bulk-at")
        .description("Starts a new bulk shulker from an empty one once carrying this many stacks of one item. 0 only tops up shulkers that already exist.")
        .defaultValue(9)
        .range(0, 27)
        .sliderRange(0, 27)
        .visible(repack::get)
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

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up
    private enum Handle { Place, Open, Work, Break, Collect }

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 15;

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
    private boolean usedBaritone;
    private int moved;

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private final Set<Box> compacted = new HashSet<>();
    private int actions;

    // Repacking
    private int wantSlot = -1;
    private int handledSlot;
    private Item fillItem;
    private Item mainItem;
    private Handle handle;
    private int handleTicks;
    private BlockPos placed;
    private int swapBackFrom = -1, swapBackTo = -1;
    private boolean pickaxeSwapped;
    private int shulkersBefore;
    private final Set<Integer> shulkerSlotsBefore = new HashSet<>();
    private boolean repackFailed;
    private final Set<Item> packTried = new HashSet<>();

    // Bot mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;
    private int version;

    // Containers the player opens themselves
    private AbstractContainerMenu trackedMenu;
    private Box trackedBox;

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
        moved = 0;
        wait = 0;
        idle = false;
        stalled.clear();
        compacted.clear();
        tierBoxes.clear();
        handle = null;
        repackFailed = false;
        packTried.clear();

        if (!pickCarrySlots()) {
            toggle();
            return;
        }

        boolean existed = ensureStash() != null;
        if (stash == null) stash = new Stash(stashName.get(), Stash.currentServer());

        int added = addNearbyBoxes();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            readSigns(box);
        }
        orderBoxes();

        long sortable = stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (sortable < 2) {
            error("Found %d container%s to sort within %d blocks, need at least 2.", sortable, sortable == 1 ? "" : "s", radius.get());
            toggle();
            return;
        }

        if (mode.get() == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            error("None of the containers have signs on them. Put signs on them, or use Tiers mode.");
            toggle();
            return;
        }

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!existed) info("New stash \"%s\": scanning %d containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0) info("Stash \"%s\": %d containers, scanning %d new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"%s\": %d containers, sorting by %s.", stash.name, stash.boxes.size(), mode.get() == Mode.Tiers ? "tier" : "sign");

        if (added > 0) stash.save();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();

        if (usedBaritone) {
            BaritoneHelper.stop();
            BaritoneHelper.unprotect();
            usedBaritone = false;
        }

        if (pickaxeSwapped) {
            InvUtils.swapBack();
            pickaxeSwapped = false;
        }
        if (handle != null && placed != null && mc.level != null && !mc.level.getBlockState(placed).isAir()) {
            warning("Stopped while repacking, there's a shulker left at %s.", placed.toShortString());
        }
        handle = null;

        if (stash != null && stash.isDirty()) stash.save();

        if (mc.player != null && !carriedSlots().isEmpty()) {
            warning("Stopped while carrying %d stacks from the stash, they're in your inventory.", carriedSlots().size());
        }

        job = null;
        target = null;
    }

    /**
     * Works out which slots to carry stash items in, from the settings or, by default, every empty slot.
     */
    private boolean pickCarrySlots() {
        carry.clear();
        Set<Integer> keep = parseSlots(keepSlots.get());
        Set<Integer> chosen = parseSlots(carrySlots.get());
        boolean designated = !carrySlots.get().isBlank();

        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            if (designated && !chosen.contains(i)) continue;
            if (mc.player.getInventory().getItem(i).isEmpty()) carry.add(i);
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

        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null) {
            // Never start while a container is open, it might be one the player is using
            if (mc.player.containerMenu != mc.player.inventoryMenu) return;
            if (idle && !wakeUp()) return;
            if (!nextJob()) return;
        }

        if (handle != null) {
            tickHandling();
            return;
        }
        stepTicks++;

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
    }

    /**
     * Bot mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        if (idleTicks >= recheck.get() * 20) {
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

        if (version != seenVersion) {
            seenVersion = version;
            stalled.clear();
            compacted.clear();
            packTried.clear();
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
        Box unread = nearest(stash.boxes.stream().filter(b -> !b.known() && !b.unreachable).toList());
        if (unread != null) return start(Job.Survey, unread);

        plan();

        if (repackOn() && nextRepackJob()) return true;

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
            List<Box> roomy = stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && b.free() > 0 && !stalled.contains(b)).toList();
            Box spare = nearest(roomy.stream().filter(this::isMisc).toList());
            if (spare == null) spare = nearest(roomy);
            if (spare != null) return start(Job.Dump, spare);

            warning("Found nowhere with room for what I'm carrying, stopping. You have %d stacks from the stash in your inventory.", carried.size());
            toggle();
            return false;
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || stalled.contains(box)) continue;
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

        Box source = nearest(stash.boxes.stream().filter(b -> b.sortable() && !stalled.contains(b) && takeableSlot(b) != -1).toList());
        if (source != null) return start(Job.Take, source);

        // Downstack: merge partial stacks of the same item, once per container per run
        Box messy = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !stalled.contains(b) && !compacted.contains(b) && mergeable(b) != null).toList());
        if (messy != null) {
            compacted.add(messy);
            return start(Job.Compact, messy);
        }

        finishSorting();
        return false;
    }

    // Repacking shulkers

    private boolean repackOn() {
        return repack.get() && mode.get() == Mode.Signs && !repackFailed;
    }

    /**
     * Cleans a carried mixed shulker, or packs carried items into a bulk shulker, fetching one if needed.
     * @return true when a job was started
     */
    private boolean nextRepackJob() {
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
            if (Utils.isShulker(loose.getItem()) || packTried.contains(loose.getItem())) continue;
            Item item = loose.getItem();
            int stacks = carriedStacksOf(item);

            for (int s : carriedSlots()) {
                ItemStack shulker = mc.player.getInventory().getItem(s);
                if (bulkWithRoom(shulker, item) || (newBulkAt.get() > 0 && stacks >= newBulkAt.get() && emptyShulker(shulker))) {
                    return startHandling(Job.Fill, s, item);
                }
            }

            if (freeCarrySlot() != -1) {
                Box from = null;
                int fromSlot = -1;
                for (Box box : stash.boxes) {
                    if (!box.sortable() || box.dropOff || stalled.contains(box)) continue;
                    for (int i = 0; i < box.contents.length; i++) {
                        ItemStack s = box.contents[i];
                        boolean usable = bulkWithRoom(s, item) || (newBulkAt.get() > 0 && stacks >= newBulkAt.get() && emptyShulker(s));
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
            packTried.add(item);
        }

        return false;
    }

    private boolean fetch(Box box, int slot) {
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

        // A chest signed for kits takes mixed shulkers whole instead
        if (!keepWholeBoxes().isEmpty()) return List.of();

        List<Integer> out = new ArrayList<>();
        ItemStack[] inside = inside(shulker);
        for (int i = 0; i < inside.length; i++) {
            ItemStack s = inside[i];
            if (s == null || s.isEmpty() || s.is(main.getItem())) continue;
            if (!bestSigned(s).isEmpty()) out.add(i);
        }
        return out;
    }

    /**
     * A shulker holding only {@code item}, with room for more.
     */
    private static boolean bulkWithRoom(ItemStack shulker, Item item) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME)) return false;

        boolean any = false, room = false;
        for (ItemStack s : inside(shulker)) {
            if (s == null || s.isEmpty()) {
                room = true;
                continue;
            }
            if (!s.is(item)) return false;
            any = true;
            if (s.getCount() < s.getMaxStackSize()) room = true;
        }
        return any && room;
    }

    private static boolean emptyShulker(ItemStack shulker) {
        if (shulker == null || shulker.isEmpty() || !Utils.isShulker(shulker.getItem()) || shulker.has(DataComponents.CUSTOM_NAME)) return false;
        for (ItemStack s : inside(shulker)) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    private static ItemStack[] inside(ItemStack shulker) {
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(shulker, contents);
        return contents;
    }

    private int carriedStacksOf(Item item) {
        int count = 0;
        for (int slot : carriedSlots()) if (mc.player.getInventory().getItem(slot).is(item)) count++;
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

    private boolean startHandling(Job next, int slot, Item fill) {
        job = next;
        target = null;
        handledSlot = slot;
        fillItem = fill;
        handle = Handle.Place;
        handleTicks = 0;
        placed = null;
        actions = 0;
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
        }
    }

    private void handlePlace() {
        ItemStack shulker = mc.player.getInventory().getItem(handledSlot);
        if (!Utils.isShulker(shulker.getItem())) {
            endHandling();
            return;
        }

        BlockPos spot = placeSpot();
        if (spot == null) {
            warning("No room to put a shulker down beside me, so I'm not repacking shulkers this run.");
            repackFailed = true;
            endHandling();
            return;
        }

        // It has to be in the hotbar to place it
        int hotbar = handledSlot;
        if (hotbar >= 9) {
            hotbar = -1;
            Set<Integer> keep = parseSlots(keepSlots.get());
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && mc.player.getInventory().getItem(i).isEmpty()) hotbar = i;
            if (hotbar == -1) {
                for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
                swapBackFrom = handledSlot;
                swapBackTo = hotbar;
            }
            if (hotbar == -1) {
                warning("No hotbar slot I'm allowed to use to place shulkers, so I'm not repacking shulkers this run.");
                repackFailed = true;
                endHandling();
                return;
            }
            InvUtils.move().from(handledSlot).to(hotbar);
            handledSlot = hotbar;
            wait = delay.get();
            return;
        }

        shulkersBefore = countShulkers();
        if (!BlockUtils.place(spot, new FindItemResult(hotbar, shulker.getCount()), rotate.get(), 50, true, true, true)) {
            if (handleTicks > HANDLE_TIMEOUT) failHandling("Couldn't put the shulker down.");
            return;
        }

        placed = spot;
        handle = Handle.Open;
        handleTicks = 0;
        wait = delay.get();
    }

    private void handleOpen() {
        // Put back whatever we swapped out of the hotbar to place it
        if (swapBackFrom != -1 && mc.player.getInventory().getItem(swapBackTo).isEmpty()) {
            InvUtils.move().from(swapBackFrom).to(swapBackTo);
            swapBackFrom = swapBackTo = -1;
            wait = delay.get();
            return;
        }

        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
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
            if (mc.level.getBlockState(placed).isAir()) {
                // Never got placed after all, it's still in the inventory
                endHandling();
                return;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placed), Direction.UP, placed, false);
            BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
        }
    }

    private void handleWork() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        boolean busy = job == Job.Clean ? takeStray(menu) : packOne(menu);
        if (busy) {
            actions++;
            wait = delay.get();
            return;
        }

        if (job == Job.Fill && actions == 0) packTried.add(fillItem);
        // Nothing to take out after all: stop trying, or we'd keep reopening the same shulker
        if (job == Job.Clean && actions == 0) repackFailed = true;
        mc.player.closeContainer();
        handle = Handle.Break;
        handleTicks = 0;
        wait = delay.get();
    }

    /**
     * Takes one stack that belongs elsewhere out of the open shulker, into a free carry slot.
     */
    private boolean takeStray(AbstractContainerMenu menu) {
        int into = freeCarrySlot();
        if (into == -1) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || s.is(mainItem) || bestSigned(s).isEmpty()) continue;
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
            if (!stack.is(fillItem)) continue;

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

    private void handleBreak() {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
            return;
        }

        if (mc.level.getBlockState(placed).isAir()) {
            if (pickaxeSwapped) {
                InvUtils.swapBack();
                pickaxeSwapped = false;
            }
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT * 2) {
            failHandling("Couldn't break the shulker at " + placed.toShortString() + ", it's still there.");
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

    private void handleCollect() {
        if (countShulkers() >= shulkersBefore) {
            // Picked up: make sure it's in a carry slot, so it gets put away
            for (int i = 0; i < 36; i++) {
                if (shulkerSlotsBefore.contains(i) || !Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) continue;
                if (!carry.contains(i)) {
                    int free = freeCarrySlot();
                    if (free != -1) InvUtils.move().from(i).to(free);
                    else warning("The repacked shulker ended up outside the carry slots, in slot %d.", i + 1);
                }
                break;
            }
            endHandling();
            return;
        }

        if (handleTicks > COLLECT_TIMEOUT) {
            failHandling("Couldn't pick the shulker back up, it's on the ground near " + placed.toShortString() + ".");
            return;
        }

        // Dropped out of reach: walk over it
        if (handleTicks == 40 && walk.get() && BARITONE) {
            ItemEntity drop = null;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4))) {
                if (Utils.isShulker(e.getItem().getItem())) {
                    drop = e;
                    break;
                }
            }
            if (drop != null) {
                BaritoneHelper.protect(List.of());
                usedBaritone = true;
                BaritoneHelper.moveNear(drop.blockPosition(), 0);
            }
        }
    }

    private void endHandling() {
        handle = null;
        placed = null;
        if (stash.isDirty()) stash.save();
        job = null;
        wait = delay.get();
    }

    private void failHandling(String why) {
        warning("%s Stopping.", why);
        handle = null;
        job = null;
        toggle();
    }

    private int countShulkers() {
        int count = 0;
        for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) count++;
        return count;
    }

    /**
     * An empty spot beside us on solid ground, with air above for the lid, to put a shulker down.
     */
    private BlockPos placeSpot() {
        BlockPos feet = mc.player.blockPosition();
        for (int dy = 0; dy >= -1; dy--) {
            for (Direction dir : HORIZONTALS) {
                BlockPos p = feet.relative(dir).above(dy);
                if (!BlockUtils.canPlace(p, true)) continue;
                if (!mc.level.getBlockState(p.above()).isAir()) continue;
                if (!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), Direction.UP)) continue;
                if (stash.boxAt(p) != null) continue;
                return p;
            }
        }
        return null;
    }

    private void finishSorting() {
        if (stash.isDirty()) stash.save();

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        if (stayOn.get()) {
            if (moved > 0 || !idle) info("Sorted, moved %d stacks.%s Waiting for more to sort.", moved, extra);
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
            target = null;
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

        if (stash.isDirty()) stash.save();
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
            case Survey, Clean, Fill -> false;
            case Take -> takeOne();
            case Fetch -> fetchOne();
            case Compact -> compactOne();
            case Deposit -> depositOne();
            case Dump -> dumpOne();
        };

        if (busy) {
            actions++;
            wait = delay.get();
        }
        else finishJob();
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
        if (into == -1 || wantSlot >= target.contents.length || !Utils.isShulker(target.contents[wantSlot].getItem())) return false;

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
            if (box.sortable() && !box.dropOff && box != source) room.put(box, box.free() + misplacedCount(box));
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
    private boolean dumpOne() {
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

        if (mode.get() == Mode.Tiers) return tierBoxes.getOrDefault(tier(stack), List.of());

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
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;

            int score = 0;
            for (String label : box.labels) score = Math.max(score, Categories.signScore(label, stack));

            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        return result;
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(ItemStack stack, Box box) {
        // Nothing stays in a drop-off
        if (box.dropOff) return true;

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
     * Free slots across the stash, outside drop-offs and {@code except}, less what we're already carrying.
     */
    private int spareRoom(Box except) {
        int free = 0;
        for (Box box : stash.boxes) if (box != except && box.sortable() && !box.dropOff) free += box.free();
        return free - carriedSlots().size();
    }

    /**
     * Two partial stacks of the same item in this container, {first, second}, or null if there are none to merge.
     */
    private static int[] mergeable(Box box) {
        ItemStack[] c = box.contents;
        for (int i = 0; i < c.length; i++) {
            ItemStack a = c[i];
            if (a.isEmpty() || a.getMaxStackSize() <= 1 || a.getCount() >= a.getMaxStackSize()) continue;
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

        if (stash.boxes.removeIf(box -> mc.level.isLoaded(box.pos) && !isContainer(box.pos))) stash.markDirty();

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
        box.labels.clear();
        if (!mc.level.isLoaded(box.pos)) return;

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
            StringBuilder joined = new StringBuilder();
            int lines = 0;
            for (int i = 0; i < 4; i++) {
                String line = text.getMessage(i, false).getString().trim();
                if (line.isEmpty()) continue;
                box.labels.add(line);
                if (!joined.isEmpty()) joined.append(' ');
                joined.append(line);
                lines++;
            }
            if (lines > 1) box.labels.add(joined.toString());
        }
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

        if (pick.get() == Pick.Exclude) {
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
            replies.clear();
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.level == null) return;

            if (replyCooldown > 0) replyCooldown--;
            else if (!replies.isEmpty()) {
                ChatUtils.sendPlayerMsg(replies.poll());
                replyCooldown = REPLY_GAP_TICKS;
            }

            if (ensureStash() == null) return;

            // The sorter reads the containers it opens itself
            if (isActive() && job != null) return;

            AbstractContainerMenu menu = mc.player.containerMenu;
            if (menu instanceof ChestMenu) {
                if (menu != trackedMenu) {
                    trackedMenu = menu;
                    trackedBox = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? stash.boxAt(hit.getBlockPos()) : null;
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
            if (!render.get() || stash == null) return;

            if (isActive() || pick.get() != Pick.Off) {
                for (Box box : stash.boxes) {
                    SettingColor color = box.excluded ? excludedColor.get() : box.dropOff ? dropOffColor.get() : null;
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
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (changed(box, menu)) stash.markDirty();

        int size = menu.slots.size() - 36;
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) contents[i] = menu.getSlot(i).getItem().copy();
        box.contents = contents;
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
            sender, result.sample.getHoverName().getString(), pos.getX(), pos.getY(), pos.getZ(), stash.name, where));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WLabel status = list.add(theme.label(statusText())).expandX().widget();

        WHorizontalList buttons = list.add(theme.horizontalList()).widget();
        WButton rescan = buttons.add(theme.button("Rescan")).widget();
        rescan.tooltip = "Forgets what's in every container, so the next run reads them all again.";
        rescan.action = () -> {
            if (ensureStash() == null) return;
            for (Box box : stash.boxes) box.contents = null;
            stash.save();
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

        return list;
    }

    private String statusText() {
        if (mc.level == null) return "Join a server to see its stashes.";
        if (ensureStash() == null) return String.format("No stash called \"%s\" here yet. Turn the module on to scan one.", stashName.get());

        long excluded = stash.boxes.stream().filter(b -> b.excluded).count();
        long dropOffs = stash.boxes.stream().filter(b -> b.dropOff).count();
        return String.format("\"%s\": %d containers (%d read, %d excluded, %d drop-off), %,d items.",
            stash.name, stash.boxes.size(), stash.knownCount(), excluded, dropOffs, stash.itemCount());
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
            table.add(theme.label(String.format("%s x%,d", result.sample.getHoverName().getString(), result.total))).expandX();
            table.add(theme.label(String.format("%d, %d, %d%s%s", pos.getX(), pos.getY(), pos.getZ(),
                best.inShulker() ? " (shulker)" : "", more > 0 ? String.format(" +%d more", more) : "")));

            WButton view = table.add(theme.button("View")).widget();
            view.action = () -> ItemPreviewScreen.open(result.sample, result.total, stash.name);
            table.row();
        }
    }

    // Helpers

    /**
     * Inventory slots holding stash items we're carrying.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) {
            if (!mc.player.getInventory().getItem(slot).isEmpty()) slots.add(slot);
        }
        return slots;
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
