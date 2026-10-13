package com.porter.qwerte.modules;

import com.porter.qwerte.stash.Categories;
import com.porter.qwerte.stash.Kit;
import com.porter.qwerte.stash.Kit.Key;
import com.porter.qwerte.stash.Kit.Need;
import com.porter.qwerte.stash.Kits;
import com.porter.qwerte.stash.Stash;
import com.porter.qwerte.stash.Stash.Box;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Builds saved kits out of the stash for the stash sorter: takes an empty shulker (or a half built kit from the kit's
 * own chest), gathers what the kit needs from loose stacks and from other shulkers, and puts it into the shulker slot
 * for slot with exactly the saved counts. The sorter runs the world side (walking, opening, placing and breaking
 * shulkers); this decides what to do next and does the item moves inside menus.
 *
 * Every decision is made again from the live inventory and menu, so a click the server rejected, or a job that was
 * interrupted, only costs a retry.
 */
final class KitBuilder {
    /**
     * What the builder needs from the sorter.
     */
    interface Owner {
        Stash stash();

        /** The inventory slots the sorter carries stash items in. */
        Set<Integer> carry();

        int freeCarrySlot();

        int freeCarryCount();

        /** Not skipped for having got nothing done, and reachable. */
        boolean usable(Box box);

        /** A chest signed for mixed shulkers whole, like "kits". */
        boolean keepWhole(Box box);

        double distance(Box box);

        void say(String text);

        void warn(String text);
    }

    sealed interface Intent permits Take, Fetch, Unpack, Fill, Done, Abort {}

    /** Open this container and take what the kit needs from it. */
    record Take(Box box) implements Intent {}

    /** Take this shulker out of the container: the one to build in, or one with items for the kit inside. */
    record Fetch(Box box, int slot) implements Intent {}

    /** Place the shulker in this carry slot and take what the kit needs out of it. */
    record Unpack(int slot) implements Intent {}

    /** Place the kit shulker in this carry slot and put the carried items into it. */
    record Fill(int slot) implements Intent {}

    record Done() implements Intent {}

    record Abort(String reason) implements Intent {}

    private static final long TIME_CAP_MS = 10 * 60 * 1000L;
    private static final int MAX_STALLS = 2;
    private static final int CLICKS_PER_ACTION = 2;
    private static final int STUCK_ACTIONS = 6;
    private static final int MAX_HANDLE_ACTIONS = 150;
    private static final int MAX_CURSOR_FIXES = 4;

    private final Owner owner;

    // The session: the kit being built and where its shulker is
    private Kit kit;
    private int baseSlot = -1;
    private boolean needBase;
    private boolean pendingBase;
    private boolean hadBase;
    private final Set<Integer> shulkersBefore = new HashSet<>();
    private long startedAt;
    private int stalls;
    private long lastSignature = -1;
    private int cursorFixes;

    // Guards inside one job: the same screen over and over means the server is undoing our clicks
    private long lastScreen;
    private int sameScreen;
    private int handleActions;

    // A plan being carried out two clicks at a time, and the state each step should leave behind
    private Clicks pending;
    private int pendingAt, pendingFirst, pendingSecond, pendingMenu;

    // The kit of a shulker we gave up on, so what is left of it is filed in the kit's own chest
    private Kit heldKit;

    // Kits given up on until something changes, and kits we've already said have no chest
    private final Set<Kit> tried = new HashSet<>();
    private final Set<String> warnedNoChest = new HashSet<>();

    KitBuilder(Owner owner) {
        this.owner = owner;
    }

    // Session state

    boolean active() {
        return kit != null;
    }

    boolean holds(int slot) {
        return kit != null && slot == baseSlot;
    }

    Kit kit() {
        return kit;
    }

    /** Forgets everything, on turning the module on or leaving the world. */
    void reset() {
        heldKit = null;
        clearSession();
        tried.clear();
        warnedNoChest.clear();
    }

    /** Something in the stash changed: kits that were given up on may work now. */
    void wake() {
        tried.clear();
    }

    private void clearSession() {
        kit = null;
        baseSlot = -1;
        needBase = false;
        pendingBase = false;
        hadBase = false;
        shulkersBefore.clear();
        stalls = 0;
        lastSignature = -1;
        cursorFixes = 0;
        pending = null;
        sameScreen = 0;
        handleActions = 0;
    }

    /**
     * The kit this shulker is the leftover of, if a build was given up on with it in hand: a part built shulker of it.
     */
    Kit heldKit(ItemStack stack) {
        if (heldKit == null || stack.isEmpty() || !Utils.isShulker(stack.getItem()) || stack.has(DataComponents.CUSTOM_NAME)) return null;
        return heldKit.matchesPartial(Kits.inside(stack)) ? heldKit : null;
    }

    /** The module was turned off in the middle of building. */
    void deactivate() {
        if (kit != null && hadBase) owner.warn("Stopped while building kit \"" + kit.name + "\", the unfinished kit shulker is in your inventory.");
        reset();
    }

    /** Gives up on the kit being built, for now. */
    void suspend(String why) {
        if (kit == null) return;
        tried.add(kit);
        owner.warn("Not building kit \"" + kit.name + "\" for now: " + why);
        if (hadBase) heldKit = kit;
        clearSession();
    }

    /** The kit shulker is finished. */
    void finish() {
        if (kit != null) owner.say("Built kit \"" + kit.name + "\".");
        clearSession();
    }

    /** A new kit job starts, so the cursor repair budget starts over. */
    void jobStarted() {
        cursorFixes = 0;
        pending = null;
        sameScreen = 0;
        handleActions = 0;
    }

    /**
     * Checked at the start of every action inside a job. Gives up on the kit if it was deleted, took too long, did
     * too many actions, or the screen has not changed for several actions in a row.
     * @return true when the kit was given up on
     */
    private boolean stuck(AbstractContainerMenu menu) {
        if (kit == null) return true;
        if (!Kits.all().contains(kit)) {
            suspend("the kit was deleted.");
            return true;
        }
        if (System.currentTimeMillis() - startedAt > TIME_CAP_MS) {
            suspend("it took too long.");
            return true;
        }
        if (++handleActions > MAX_HANDLE_ACTIONS) {
            suspend("it took too many moves.");
            return true;
        }

        ItemStack held = menu.getCarried();
        long screen = held.isEmpty() ? 0 : held.getCount() * 31L + held.getItem().hashCode();
        for (Slot slot : menu.slots) {
            ItemStack s = slot.getItem();
            screen = screen * 31 + (s.isEmpty() ? 0 : s.getItem().hashCode() * 64L + s.getCount());
        }
        if (screen == lastScreen) {
            if (++sameScreen >= STUCK_ACTIONS) {
                suspend("the server keeps undoing my clicks.");
                return true;
            }
        }
        else sameScreen = 0;
        lastScreen = screen;
        return false;
    }

    /**
     * A shulker job (unpack or fill) is over. {@code slotNow} is where the shulker that was put down ended up.
     */
    void handled(boolean fill, int slotNow) {
        if (kit == null) return;
        if (fill) baseSlot = isShulker(slotNow) ? slotNow : -1;
        else if (!isShulker(baseSlot)) baseSlot = -1;
    }

    private static boolean isShulker(int slot) {
        return slot >= 0 && slot < 36 && Utils.isShulker(mc.player.getInventory().getItem(slot).getItem());
    }

    // Planning

    private record Loose(Box box, int slot, Key key, int count) {}

    private record Entry(Key key, int count) {}

    private record Packed(Box box, int slot, List<Entry> entries) {}

    private record Spot(Box box, int slot) {}

    private record Plan(Kit kit, Spot base, double distance) {}

    /**
     * What the stash has to build with: loose stacks and shulkers of the right items, and empty shulkers. Chests
     * signed for a kit, and chests signed for whole shulkers, are never taken from.
     */
    private final class Scan {
        final List<Loose> loose = new ArrayList<>();
        final List<Packed> packed = new ArrayList<>();
        final List<Spot> empties = new ArrayList<>();

        Scan(List<Kit> kits) {
            Stash stash = owner.stash();
            if (stash == null) return;

            for (Box box : stash.boxes) {
                if (!source(box)) continue;

                for (int i = 0; i < box.contents.length; i++) {
                    ItemStack s = box.contents[i];
                    if (s.isEmpty()) continue;

                    if (Utils.isShulker(s.getItem())) {
                        if (s.has(DataComponents.CUSTOM_NAME)) continue;

                        boolean any = false;
                        List<Entry> entries = new ArrayList<>();
                        for (ItemStack in : Kits.inside(s)) {
                            if (in == null || in.isEmpty()) continue;
                            any = true;
                            if (relevant(kits, in)) entries.add(new Entry(Kit.keyOf(in), in.getCount()));
                        }

                        if (!any) empties.add(new Spot(box, i));
                        else if (!entries.isEmpty() && !Kits.claims(s) && heldKit(s) == null) packed.add(new Packed(box, i, entries));
                    }
                    else if (relevant(kits, s)) loose.add(new Loose(box, i, Kit.keyOf(s), s.getCount()));
                }
            }
        }

        Map<Key, Integer> available(Kit kit) {
            Map<Key, Integer> avail = new HashMap<>();
            for (Loose l : loose) if (kit.need(l.key) != null) avail.merge(l.key, l.count, Integer::sum);
            for (Packed p : packed) for (Entry e : p.entries) if (kit.need(e.key) != null) avail.merge(e.key, e.count, Integer::sum);
            return avail;
        }
    }

    private static boolean relevant(List<Kit> kits, ItemStack stack) {
        for (Kit k : kits) if (k.hasItem(stack.getItem())) return true;
        return false;
    }

    private boolean source(Box box) {
        return box.sortable() && !box.dropOff && owner.usable(box) && !Kits.isKitBox(box) && !owner.keepWhole(box);
    }

    /**
     * Whether the kit can be built once more right now, and with which shulker.
     */
    private Plan plan(Kit k, Scan scan) {
        Stash stash = owner.stash();
        List<Box> homes = Kits.homes(k, stash);
        if (homes.isEmpty()) return null;

        // A kit part built, in its own chest, is carried on with first
        Spot base = null;
        Map<Key, Integer> have = Map.of();
        int most = 0;
        boolean room = false;
        for (Box home : homes) {
            if (home.free() > 0) room = true;
            for (int i = 0; i < home.contents.length; i++) {
                ItemStack s = home.contents[i];
                if (s.isEmpty() || !Utils.isShulker(s.getItem()) || s.has(DataComponents.CUSTOM_NAME)) continue;
                ItemStack[] in = Kits.inside(s);
                if (!k.matchesPartial(in)) continue;

                Map<Key, Integer> totals = Kit.totals(in);
                int count = totals.values().stream().mapToInt(Integer::intValue).sum();
                if (count > most) {
                    most = count;
                    base = new Spot(home, i);
                    have = totals;
                }
            }
        }

        // The finished kit needs a place to go
        if (base == null && !room) return null;

        if (base == null) {
            for (Spot spot : scan.empties) {
                if (base == null || emptyRank(spot) < emptyRank(base)) base = spot;
            }
            if (base == null) return null;
        }

        Map<Key, Integer> avail = scan.available(k);
        for (Need need : k.needs()) {
            int want = need.total - Math.min(have.getOrDefault(need.key, 0), need.total);
            if (avail.getOrDefault(need.key, 0) < want) return null;
        }

        double distance = Double.MAX_VALUE;
        for (Box home : homes) distance = Math.min(distance, owner.distance(home));
        return new Plan(k, base, distance);
    }

    /** Boxes signed for empty shulkers first, then the nearest. */
    private double emptyRank(Spot spot) {
        boolean signed = spot.box.labels.stream().anyMatch(l -> Categories.normalize(l).startsWith("empty shulker"));
        return (signed ? 0 : 1_000_000_000.0) + owner.distance(spot.box);
    }

    /**
     * Picks a kit that can be built and starts on it: the one with the fewest finished copies, then the nearest.
     * @return true when a kit was started
     */
    boolean begin() {
        if (kit != null) return true;
        Stash stash = owner.stash();
        if (stash == null || mc.player == null) return false;

        List<Kit> kits = Kits.all();
        if (kits.isEmpty()) return false;

        Scan scan = null;
        Plan best = null;
        int bestBuilt = 0;
        for (Kit k : kits) {
            if (tried.contains(k)) continue;

            if (Kits.homes(k, stash).isEmpty()) {
                if (warnedNoChest.add(Categories.normalize(k.name))) {
                    owner.say("Kit \"" + k.name + "\" has no chest signed \"" + k.name + "\" (or \"" + k.name + " kit\"), so I'm not building it.");
                }
                continue;
            }

            if (scan == null) scan = new Scan(kits);
            Plan plan = plan(k, scan);
            if (plan == null) continue;

            int built = Kits.built(k, stash);
            if (best == null || built < bestBuilt || (built == bestBuilt && plan.distance < best.distance)) {
                best = plan;
                bestBuilt = built;
            }
        }

        if (best == null) return false;

        kit = best.kit;
        baseSlot = -1;
        needBase = true;
        pendingBase = false;
        hadBase = false;
        heldKit = null;
        startedAt = System.currentTimeMillis();
        stalls = 0;
        lastSignature = -1;
        cursorFixes = 0;
        return true;
    }

    /**
     * How many more copies of a kit the stash could build right now, by its items and its empty shulkers. -1 if the
     * kit has no chest to go in.
     */
    int possible(Kit k) {
        Stash stash = owner.stash();
        if (stash == null || mc.player == null) return 0;
        if (Kits.homes(k, stash).isEmpty()) return -1;

        Scan scan = new Scan(List.of(k));
        Map<Key, Integer> avail = scan.available(k);
        int copies = Integer.MAX_VALUE;
        for (Need need : k.needs()) copies = Math.min(copies, avail.getOrDefault(need.key, 0) / need.total);

        int shulkers = scan.empties.size();
        for (Box home : Kits.homes(k, stash)) {
            for (ItemStack s : home.contents) {
                if (!s.isEmpty() && Utils.isShulker(s.getItem()) && !s.has(DataComponents.CUSTOM_NAME) && k.matchesPartial(Kits.inside(s))) shulkers++;
            }
        }
        return Math.max(0, Math.min(copies, shulkers));
    }

    // The next step

    /**
     * What to do next, worked out from what's in the inventory right now.
     */
    Intent next() {
        if (kit == null) return new Done();
        if (System.currentTimeMillis() - startedAt > TIME_CAP_MS) return new Abort("it took too long");
        if (!Kits.all().contains(kit)) return new Abort("the kit was deleted");
        if (owner.stash() == null) return new Abort("no stash is loaded");

        if (pendingBase) {
            pendingBase = false;
            baseSlot = -1;
            Inventory inv = mc.player.getInventory();
            for (int slot : owner.carry()) {
                if (shulkersBefore.contains(slot)) continue;
                ItemStack s = inv.getItem(slot);
                if (Utils.isShulker(s.getItem())) {
                    baseSlot = slot;
                    break;
                }
            }
            if (baseSlot == -1) return new Abort("couldn't take a shulker out of the stash");

            ItemStack s = mc.player.getInventory().getItem(baseSlot);
            if (!baseLike(s)) {
                // Not the shulker we went for: the stash moved on. It's put away like anything else carried.
                baseSlot = -1;
                return new Abort("the shulker I took wasn't an empty one or part of the kit");
            }
            hadBase = true;
            needBase = false;
        }

        if (needBase) {
            if (owner.freeCarryCount() < 2) return new Abort("not enough free carry slots");

            Scan scan = new Scan(List.of(kit));
            Plan plan = plan(kit, scan);
            if (plan == null) return new Abort("the items or shulkers aren't there anymore");

            shulkersBefore.clear();
            for (int i = 0; i < 36; i++) if (Utils.isShulker(mc.player.getInventory().getItem(i).getItem())) shulkersBefore.add(i);
            pendingBase = true;
            return new Fetch(plan.base.box, plan.base.slot);
        }

        if (!isShulker(baseSlot)) return new Abort("lost the kit shulker");

        Live live = live();
        if (live.layoutOk) return new Done();

        // Two steps in a row that changed nothing: stuck
        long signature = (long) live.placed * 100_000 + live.useful;
        if (signature == lastSignature) stalls++;
        else stalls = 0;
        lastSignature = signature;
        if (stalls >= MAX_STALLS) return new Abort("it isn't getting anywhere");

        if (live.gather > 0) {
            int free = owner.freeCarryCount();

            int unpack = unpackCandidate(live);
            if (unpack != -1) return new Unpack(unpack);

            Intent gather = gather(live, free);
            if (gather != null) return gather;
        }

        if (live.useful > 0 || live.gather == 0) return new Fill(baseSlot);

        return new Abort(owner.freeCarryCount() < 1 ? "not enough free carry slots to gather the rest" : "can't find the rest of the items");
    }

    /** Empty, or part of this kit. */
    private boolean baseLike(ItemStack s) {
        if (s.isEmpty() || !Utils.isShulker(s.getItem()) || s.has(DataComponents.CUSTOM_NAME)) return false;
        ItemStack[] in = Kits.inside(s);
        boolean any = false;
        for (ItemStack i : in) if (i != null && !i.isEmpty()) any = true;
        return !any || kit.matchesPartial(in);
    }

    private final class Live {
        final Map<Key, Integer> deficit = new HashMap<>();
        final Map<Key, Integer> looseStacks = new HashMap<>();
        int placed;
        int useful;
        int gather;
        boolean layoutOk;
    }

    /**
     * Where the kit stands: what the shulker holds, what's carried loose that it still needs, and what's missing.
     */
    private Live live() {
        Inventory inv = mc.player.getInventory();
        Live live = new Live();

        ItemStack[] base = Kits.inside(inv.getItem(baseSlot));
        Map<Key, Integer> inBase = Kit.totals(base);
        Map<Key, Integer> loose = new HashMap<>();
        for (int slot : owner.carry()) {
            if (slot == baseSlot) continue;
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !kit.hasItem(s.getItem())) continue;
            Key key = Kit.keyOf(s);
            if (kit.need(key) == null) continue;
            loose.merge(key, s.getCount(), Integer::sum);
            live.looseStacks.merge(key, 1, Integer::sum);
        }

        for (Need need : kit.needs()) {
            int placed = Math.min(inBase.getOrDefault(need.key, 0), need.total);
            int missing = need.total - placed;
            int carried = loose.getOrDefault(need.key, 0);
            live.placed += placed;
            live.useful += Math.min(carried, missing);
            if (missing > carried) {
                live.deficit.put(need.key, missing - carried);
                live.gather += missing - carried;
            }
        }

        live.layoutOk = kit.exactLayout(base);
        return live;
    }

    /** A carried shulker nobody claims that holds something the kit still needs. */
    private int unpackCandidate(Live live) {
        Inventory inv = mc.player.getInventory();
        for (int slot : owner.carry()) {
            if (slot == baseSlot) continue;
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !Utils.isShulker(s.getItem()) || s.has(DataComponents.CUSTOM_NAME) || Kits.claims(s) || heldKit(s) != null) continue;

            for (ItemStack in : Kits.inside(s)) {
                if (in == null || in.isEmpty() || !kit.hasItem(in.getItem())) continue;
                if (live.deficit.containsKey(Kit.keyOf(in))) return slot;
            }
        }
        return -1;
    }

    /**
     * Where to get more of what's missing: loose stacks first, then shulkers. One kind of item at a time, the one
     * we're carrying the fewest stacks of, so the first trip already brings a bit of everything.
     */
    private Intent gather(Live live, int free) {
        if (free < 1) return null;

        List<Key> keys = new ArrayList<>(live.deficit.keySet());
        keys.sort(Comparator.comparingInt(k -> live.looseStacks.getOrDefault(k, 0)));
        Scan scan = new Scan(List.of(kit));

        for (Key key : keys) {
            Need need = kit.need(key);
            int deficit = live.deficit.get(key);

            Loose bestLoose = null;
            long bestScore = Long.MAX_VALUE;
            for (Loose l : scan.loose) {
                if (!l.key.equals(key)) continue;
                long score = score(need, l.count, deficit) * 1_000_000_000L + (long) owner.distance(l.box);
                if (score < bestScore) {
                    bestScore = score;
                    bestLoose = l;
                }
            }
            if (bestLoose != null) return new Take(bestLoose.box);

            if (free < 2) continue;
            Packed bestPacked = null;
            bestScore = Long.MAX_VALUE;
            for (Packed p : scan.packed) {
                for (Entry e : p.entries) {
                    if (!e.key.equals(key)) continue;
                    long score = score(need, e.count, deficit) * 1_000_000_000L + (long) owner.distance(p.box);
                    if (score < bestScore) {
                        bestScore = score;
                        bestPacked = p;
                    }
                }
            }
            if (bestPacked != null) return new Fetch(bestPacked.box, bestPacked.slot);
        }
        return null;
    }

    /**
     * Lower is better: a stack that's exactly the count of one of the kit's slots, then the biggest that doesn't
     * overshoot what's missing, then the smallest that does.
     */
    private static int score(Need need, int count, int deficit) {
        if (need.counts.contains(count)) return 0;
        if (count <= deficit) return 100 + (100 - count);
        return 1000 + count;
    }

    // Taking items

    /**
     * Takes one stack the kit needs out of the open container into a free carry slot.
     * @return false when there's nothing more to take, or nowhere to carry it
     */
    boolean takeOne(Box box) {
        if (kit == null || !isShulker(baseSlot) || stuck(mc.player.containerMenu)) return false;
        int into = owner.freeCarrySlot();
        if (into == -1) return false;

        Live live = live();
        int slot = bestStack(live, box.contents.length, i -> box.contents[i]);
        if (slot == -1) return false;

        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, box.contents.length));
        return true;
    }

    /**
     * Takes one stack the kit needs out of the open shulker into a free carry slot.
     */
    boolean unpackOne(AbstractContainerMenu menu) {
        if (kit == null || !isShulker(baseSlot) || stuck(menu)) return false;
        if (!menu.getCarried().isEmpty()) return settleCursor(menu);

        int into = owner.freeCarrySlot();
        if (into == -1) return false;

        Live live = live();
        int slot = bestStack(live, 27, i -> menu.getSlot(i).getItem());
        if (slot == -1) return false;

        InvUtils.move().fromId(slot).toId(inventoryToMenu(into, 27));
        return true;
    }

    private interface Contents {
        ItemStack at(int slot);
    }

    private int bestStack(Live live, int size, Contents contents) {
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            ItemStack s = contents.at(i);
            if (s.isEmpty() || !kit.hasItem(s.getItem())) continue;

            Key key = Kit.keyOf(s);
            Integer deficit = live.deficit.get(key);
            if (deficit == null) continue;

            // Fewest carried of this kind first, then the best count
            long score = live.looseStacks.getOrDefault(key, 0) * 10_000L + score(kit.need(key), s.getCount(), deficit);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // Filling the shulker

    /**
     * One step of putting the carried items into the open kit shulker, in the saved slots with the saved counts.
     * @return false when it's all in place, or there's nothing to put in and nowhere to put what's wrong
     */
    boolean fillOne(AbstractContainerMenu menu) {
        if (kit == null || menu.slots.size() != 27 + 36 || stuck(menu)) return false;
        if (continuePending(menu)) return true;
        if (!menu.getCarried().isEmpty()) return settleCursor(menu);

        Inventory inv = mc.player.getInventory();

        // Anything in the wrong slot, or too many of it, comes out first
        for (int s = 0; s < Kit.SLOTS; s++) {
            ItemStack cur = menu.getSlot(s).getItem();
            if (cur.isEmpty()) continue;

            Key want = kit.keyAt(s);
            if (want == null || !want.equals(Kit.keyOf(cur))) {
                int into = freeSlot();
                if (into == -1) return false;
                InvUtils.move().fromId(s).toId(inventoryToMenu(into, 27));
                return true;
            }

            if (cur.getCount() > kit.countAt(s)) {
                int into = spillSlot(cur);
                if (into == -1) return false;

                ItemStack there = inv.getItem(into);
                Clicks clicks = clicksFor(cur.getMaxStackSize(), there.isEmpty() ? 0 : there.getCount(), cur.getCount(), kit.countAt(s));
                if (clicks == null || clicks.ops.length == 0) return false;
                run(menu, clicks, inventoryToMenu(into, 27), s);
                return true;
            }
        }

        // Then each slot gets what it's missing, from the carried stack that takes the fewest clicks
        for (int s : kit.fillOrder()) {
            ItemStack cur = menu.getSlot(s).getItem();
            int have = cur.isEmpty() ? 0 : cur.getCount();
            int want = kit.countAt(s);
            if (have >= want) continue;

            int need = want - have;
            Key key = kit.keyAt(s);
            int bestSlot = -1;
            Clicks bestClicks = null;
            long bestScore = Long.MAX_VALUE;

            for (int slot : owner.carry()) {
                if (slot == baseSlot) continue;
                ItemStack src = inv.getItem(slot);
                if (src.isEmpty() || !kit.hasItem(src.getItem()) || !key.equals(Kit.keyOf(src))) continue;

                int n = src.getCount();
                Clicks clicks = clicksFor(src.getMaxStackSize(), n, have, have + Math.min(n, need));
                if (clicks == null || clicks.ops.length == 0) continue;

                // Fewest clicks, then the most moved, so fewer stacks are needed for one slot
                long score = clicks.ops.length * 10_000L + (100 - Math.min(n, need)) * 100L + slot;
                if (score < bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                    bestClicks = clicks;
                }
            }

            if (bestSlot != -1) {
                run(menu, bestClicks, inventoryToMenu(bestSlot, 27), s);
                return true;
            }
        }

        return false;
    }

    /** An empty carry slot that isn't the kit shulker's. */
    private int freeSlot() {
        Inventory inv = mc.player.getInventory();
        for (int slot : owner.carry()) if (slot != baseSlot && inv.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /** Where to put surplus items of this kind: a carried stack of it with room, or an empty carry slot. */
    private int spillSlot(ItemStack like) {
        Inventory inv = mc.player.getInventory();
        for (int slot : owner.carry()) {
            ItemStack s = inv.getItem(slot);
            if (slot != baseSlot && !s.isEmpty() && s.getCount() < s.getMaxStackSize() && ItemStack.isSameItemSameComponents(s, like)) return slot;
        }
        return freeSlot();
    }

    /**
     * Puts a stray stack on the cursor back: onto a carried stack it fits on, or into an empty carry slot.
     */
    private boolean settleCursor(AbstractContainerMenu menu) {
        if (++cursorFixes > MAX_CURSOR_FIXES) return false;

        Inventory inv = mc.player.getInventory();
        ItemStack held = menu.getCarried();
        int size = menu.slots.size() - 36;

        for (int slot : owner.carry()) {
            ItemStack s = inv.getItem(slot);
            if (slot != baseSlot && !s.isEmpty() && s.getCount() < s.getMaxStackSize() && ItemStack.isSameItemSameComponents(s, held)) {
                click(menu, inventoryToMenu(slot, size), 0);
                return true;
            }
        }

        int free = freeSlot();
        if (free == -1) return false;
        click(menu, inventoryToMenu(free, size), 0);
        return true;
    }

    // Exact counts

    /**
     * Clicks to move items between two slots of the same kind of item (or empty), ending with the cursor empty and
     * the second slot holding exactly the goal. Each op is 0 left on the first slot, 1 right on the first, 2 left on
     * the second, 3 right on the second. {@code after[i]} is the cursor, first slot and second slot counts after op i.
     */
    private record Clicks(int[] ops, int[][] after) {}

    private static final Map<Integer, Clicks> PLANS = new HashMap<>();
    private static final Clicks UNREACHABLE = new Clicks(new int[0], new int[0][]);

    /**
     * The fewest clicks, by searching every sequence of the four clicks under vanilla's rules: a left click picks a
     * stack up whole, puts the cursor down whole, or tops a matching stack up to its maximum; a right click picks up
     * the larger half, or puts down one item. So a full stack is two clicks, half of a stack is two clicks, and a
     * small number is picked up and put down one at a time (or the other way round, if the remainder is smaller).
     */
    private static Clicks clicksFor(int max, int p0, int q0, int goal) {
        int key = (max << 24) | (p0 << 16) | (q0 << 8) | goal;
        Clicks cached = PLANS.get(key);
        if (cached != null) return cached == UNREACHABLE ? null : cached;

        Clicks found = search(max, p0, q0, goal);
        PLANS.put(key, found == null ? UNREACHABLE : found);
        return found;
    }

    private static Clicks search(int max, int p0, int q0, int goal) {
        int total = p0 + q0;
        int w = total + 1;
        int[] prev = new int[w * w];
        int[] how = new int[w * w];
        java.util.Arrays.fill(prev, -1);

        int start = p0;
        prev[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            int state = queue.poll();
            int cur = state / w, p = state % w, q = total - cur - p;

            if (cur == 0 && q == goal) {
                List<Integer> ops = new ArrayList<>();
                for (int s = state; s != start; s = prev[s]) ops.add(how[s]);

                int n = ops.size();
                int[] result = new int[n];
                int[][] after = new int[n][];
                int c = 0, pp = p0, qq = q0;
                for (int i = 0; i < n; i++) {
                    result[i] = ops.get(n - 1 - i);
                    int[] r = click(c, result[i] < 2 ? pp : qq, max, result[i] % 2 == 1);
                    c = r[0];
                    if (result[i] < 2) pp = r[1];
                    else qq = r[1];
                    after[i] = new int[] {c, pp, qq};
                }
                return new Clicks(result, after);
            }

            for (int op = 0; op < 4; op++) {
                int[] next = click(cur, op < 2 ? p : q, max, op % 2 == 1);
                if (next == null) continue;

                int nc = next[0], np, nq;
                if (op < 2) {
                    np = next[1];
                    nq = total - nc - np;
                }
                else {
                    nq = next[1];
                    np = total - nc - nq;
                }
                if (np < 0 || nq < 0) continue;

                int ns = nc * w + np;
                if (prev[ns] != -1) continue;
                prev[ns] = state;
                how[ns] = op;
                queue.add(ns);
            }
        }
        return null;
    }

    /**
     * One click on a slot holding {@code x} with {@code cur} on the cursor: {cursor, slot} afterwards, or null if
     * nothing changes.
     */
    private static int[] click(int cur, int x, int max, boolean right) {
        if (cur == 0) {
            if (x == 0) return null;
            int take = right ? (x + 1) / 2 : x;
            return new int[] {take, x - take};
        }

        if (!right) {
            if (x == 0) return new int[] {0, cur};
            int move = Math.min(cur, max - x);
            if (move <= 0) return null;
            return new int[] {cur - move, x + move};
        }

        if (x >= max) return null;
        return new int[] {cur - 1, x + 1};
    }

    /**
     * Starts carrying out a plan, two clicks at a time like everything else here.
     */
    private void run(AbstractContainerMenu menu, Clicks clicks, int firstId, int secondId) {
        pending = clicks;
        pendingAt = 0;
        pendingFirst = firstId;
        pendingSecond = secondId;
        pendingMenu = menu.containerId;
        sendPending(menu);
    }

    /**
     * The next clicks of the plan in progress, if the screen is exactly as the last click should have left it.
     * Otherwise the plan is dropped and the next step starts from what is really there.
     */
    private boolean continuePending(AbstractContainerMenu menu) {
        if (pending == null) return false;
        if (menu.containerId != pendingMenu || pendingAt <= 0 || pendingAt >= pending.ops.length) {
            pending = null;
            return false;
        }

        int[] expected = pending.after[pendingAt - 1];
        ItemStack held = menu.getCarried();
        if ((held.isEmpty() ? 0 : held.getCount()) != expected[0]
            || menu.getSlot(pendingFirst).getItem().getCount() != expected[1]
            || menu.getSlot(pendingSecond).getItem().getCount() != expected[2]) {
            pending = null;
            return false;
        }

        sendPending(menu);
        return true;
    }

    private void sendPending(AbstractContainerMenu menu) {
        int end = Math.min(pending.ops.length, pendingAt + CLICKS_PER_ACTION);
        for (; pendingAt < end; pendingAt++) {
            int op = pending.ops[pendingAt];
            click(menu, op < 2 ? pendingFirst : pendingSecond, op % 2);
        }
        if (pendingAt >= pending.ops.length) pending = null;
    }

    private static void click(AbstractContainerMenu menu, int slotId, int button) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slotId, button, ClickType.PICKUP, mc.player);
    }

    /**
     * Where inventory slot {@code index} sits in a container menu: the main inventory follows the container's slots,
     * then the hotbar.
     */
    private static int inventoryToMenu(int index, int containerSize) {
        return index < 9 ? containerSize + 27 + index : containerSize + index - 9;
    }
}
