package com.porter.qwerte.modules;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The containers a module restocks from. The list itself is kept in a (hidden) string list setting as "x,y,z" so it's
 * saved with the module's settings. On top of that it remembers, which storages turned out to be empty or
 * unusable, and picks which one to try next.
 */
class StorageList {
    /** How long a storage that couldn't be used is left alone before it's tried again. */
    static final int RETRY_TICKS = 20 * 60;

    private final Setting<List<String>> setting;
    private final Runnable onChanged;

    // Storages seen empty (until the list changes, the module is reactivated or forgetEmpty), and those that couldn't be
    // opened or reached (until the tick given, then they're tried again)
    private final Set<BlockPos> empty = new HashSet<>();
    private final Map<BlockPos, Integer> bad = new HashMap<>();
    // Storages already tried during the restock attempt that's running
    private final Set<BlockPos> tried = new HashSet<>();
    private BlockPos current;
    private boolean skippedFar;
    private int now;

    /**
     * @param onChanged runs whenever the list is changed by the player
     */
    StorageList(Setting<List<String>> setting, Runnable onChanged) {
        this.setting = setting;
        this.onChanged = onChanged;
    }

    // The list

    List<BlockPos> all() {
        List<BlockPos> result = new ArrayList<>();
        for (String s : setting.get()) {
            BlockPos pos = parse(s);
            if (pos != null && !result.contains(pos)) result.add(pos);
        }
        return result;
    }

    boolean isEmpty() {
        return all().isEmpty();
    }

    int size() {
        return all().size();
    }

    /**
     * Adds the storage, or removes it if it's already listed.
     * @return true if it was added
     */
    boolean toggle(BlockPos pos) {
        pos = pos.immutable();
        List<BlockPos> list = all();
        boolean added = !list.remove(pos);
        if (added) list.add(pos);
        store(list);
        return added;
    }

    void remove(BlockPos pos) {
        List<BlockPos> list = all();
        if (list.remove(pos)) store(list);
    }

    void clear() {
        store(new ArrayList<>());
    }

    /**
     * Folds the old single-storage setting into the list, once. The old setting is reset so it isn't folded again.
     */
    void migrate(Setting<BlockPos> old) {
        BlockPos pos = old.get();
        if (pos == null || pos.equals(BlockPos.ZERO)) return;

        List<BlockPos> list = all();
        if (!list.contains(pos)) {
            list.add(pos.immutable());
            setting.set(toStrings(list));
        }
        old.set(BlockPos.ZERO);
    }

    private void store(List<BlockPos> list) {
        setting.set(toStrings(list));
        bad.keySet().retainAll(list);
        tried.retainAll(list);
        empty.clear();
        onChanged.run();
    }

    private static List<String> toStrings(List<BlockPos> list) {
        List<String> strings = new ArrayList<>();
        for (BlockPos pos : list) strings.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        return strings;
    }

    private static BlockPos parse(String s) {
        try {
            String[] parts = s.trim().split(",");
            if (parts.length != 3) return null;
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    // Session state

    /**
     * Forgets everything learned about the storages, for when the module is turned on.
     */
    void resetSession() {
        empty.clear();
        bad.clear();
        tried.clear();
        current = null;
        skippedFar = false;
    }

    /**
     * Call once a tick, it's what marks wear off by.
     */
    void tick() {
        now++;
    }

    /**
     * Starts a new restock attempt, in which every storage may be tried once.
     */
    void beginAttempt() {
        tried.clear();
        current = null;
        skippedFar = false;
    }

    /**
     * The storage the attempt is on now, or null.
     */
    BlockPos current() {
        return current;
    }

    /**
     * Empty marks stay until the list changes, the module is turned on again, or this is called.
     */
    void forgetEmpty() {
        empty.clear();
    }

    void markEmpty(BlockPos pos) {
        if (pos != null) empty.add(pos);
    }

    void markBad(BlockPos pos) {
        if (pos != null) bad.put(pos, now + RETRY_TICKS);
    }

    private boolean marked(Map<BlockPos, Integer> marks, BlockPos pos) {
        Integer until = marks.get(pos);
        if (until == null) return false;
        if (until <= now) {
            marks.remove(pos);
            return false;
        }
        return true;
    }

    /**
     * True when there are storages and every one of them is currently known to be empty.
     */
    boolean allEmpty() {
        List<BlockPos> list = all();
        if (list.isEmpty()) return false;
        for (BlockPos pos : list) {
            if (!empty.contains(pos)) return false;
        }
        return true;
    }

    /**
     * True if the last attempt passed over a storage only because it was out of reach and walking wasn't possible.
     */
    boolean skippedOutOfReach() {
        return skippedFar;
    }

    /**
     * Picks the next storage to try in this attempt: in reach first, then nearest, leaving out the ones tried already
     * and the ones known to be empty or unusable. Out-of-reach ones are left out too when we can't walk to them.
     * The pick becomes {@link #current()}.
     * @return the storage, or null when there's none left to try
     */
    BlockPos next(Vec3 eyes, Predicate<BlockPos> inReach, boolean canWalk) {
        BlockPos best = null;
        boolean bestInReach = false;
        double bestDist = 0;

        for (BlockPos pos : all()) {
            if (tried.contains(pos) || empty.contains(pos) || marked(bad, pos)) continue;

            boolean reachable = inReach.test(pos);
            if (!reachable && !canWalk) {
                skippedFar = true;
                continue;
            }

            double dist = eyes.distanceToSqr(Vec3.atCenterOf(pos));
            if (best == null || (reachable && !bestInReach) || (reachable == bestInReach && dist < bestDist)) {
                best = pos;
                bestInReach = reachable;
                bestDist = dist;
            }
        }

        current = best;
        if (best != null) tried.add(best);
        return best;
    }

    // GUI

    WWidget widget(GuiTheme theme, String title, BooleanSupplier active) {
        return widget(theme, title, "pick-storage", active);
    }

    /**
     * @param pickName the name of the setting that adds storages, shown in the hint
     */
    WWidget widget(GuiTheme theme, String title, String pickName, BooleanSupplier active) {
        WVerticalList list = theme.verticalList();
        if (!active.getAsBoolean()) {
            list.add(theme.label(title + " are used when their restock is on and the restock mode is Storage."));
            return list;
        }

        WLabel heading = list.add(theme.label("")).expandX().widget();
        WTable table = list.add(theme.table()).expandX().widget();
        WButton clear = list.add(theme.button("Clear")).widget();
        clear.tooltip = "Removes every storage from the list.";

        clear.action = () -> {
            clear();
            fill(theme, title, pickName, heading, table);
        };
        fill(theme, title, pickName, heading, table);
        return list;
    }

    private void fill(GuiTheme theme, String title, String pickName, WLabel heading, WTable table) {
        table.clear();
        List<BlockPos> list = all();
        heading.set(title + " (" + list.size() + ")");

        if (list.isEmpty()) {
            table.add(theme.label("None yet, turn on " + pickName + " and right-click a container."));
            return;
        }

        for (BlockPos pos : list) {
            table.add(theme.label(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())).expandX();

            String distance = mc.player == null ? "?" : String.format("%.1f m", Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(pos))));
            table.add(theme.label(distance));

            WMinus minus = table.add(theme.minus()).widget();
            minus.tooltip = "Remove this storage.";
            minus.action = () -> {
                remove(pos);
                fill(theme, title, pickName, heading, table);
            };
            table.row();
        }
    }
}
