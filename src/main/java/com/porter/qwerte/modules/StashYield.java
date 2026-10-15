package com.porter.qwerte.modules;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Options;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Tells when someone else is using the client, so the sorter can give way: an open screen that isn't its own container,
 * the player's movement keys, Baritone doing something it didn't ask for, other modules that work on their own, and
 * inventory changes it didn't make. Also keeps the quiet time to wait before carrying on, which doubles for each
 * further interruption in the last few minutes.
 */
final class StashYield {
    enum Reason {
        Screen("an open screen"),
        Input("your movement input"),
        Baritone("Baritone"),
        Module("another module"),
        Eating("auto eat"),
        Inventory("an inventory change");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /**
     * What the check needs to know about the sorter.
     */
    interface Owner {
        /** The menu is a container window the sorter opened, or is opening. */
        boolean ourMenu(AbstractContainerMenu menu);

        /** Baritone is busy with something that isn't the sorter's own goal. */
        boolean baritoneForeign();

        /** Baritone is pathing or has a process active, whoever's. */
        boolean baritoneActive();

        /** The sorter is holding a key down itself (sneaking to put up a sign). */
        boolean drivingInput();

        /** The sorter let go of its own Baritone goal a moment ago. */
        boolean justReleased();

        /** The sorter's own Baritone goal is being walked. */
        boolean walkingOurs();

        /** The sorter has been moving items in the inventory itself, so changes there are its own. */
        boolean ownInventoryActivity();

        Set<Integer> carry();

        int quietSeconds();

        void say(String text);
    }

    private static final int INTERRUPTION_WINDOW = 20 * 60 * 5;
    private static final int MAX_QUIET_TICKS = 20 * 60;

    private final Owner owner;
    private long now;
    private long lastForeign = Long.MIN_VALUE / 4;
    private Reason lastReason;
    private final ArrayDeque<Long> interruptions = new ArrayDeque<>();

    // The slots outside the carry slots as last seen, to tell when something else changed them
    private boolean invSigSet;

    private long waitStart = -1;

    StashYield(Owner owner) {
        this.owner = owner;
    }

    void reset() {
        interruptions.clear();
        lastForeign = Long.MIN_VALUE / 4;
        lastReason = null;
        invSigSet = false;
        waitStart = -1;
    }

    /**
     * Looks at what else is going on, once a tick.
     * @return why someone else is using the client, or null if nobody is
     */
    Reason check() {
        now++;
        while (!interruptions.isEmpty() && now - interruptions.peekFirst() > INTERRUPTION_WINDOW) interruptions.pollFirst();

        Reason reason = detect();
        if (reason != null) {
            lastForeign = now;
            lastReason = reason;
        }
        return reason;
    }

    private Reason detect() {
        if (mc.player == null || mc.level == null) return null;

        // Any screen that isn't the container the sorter has open: chat, the inventory, menus, a chest the player opened
        if (mc.screen != null) {
            boolean ours = mc.screen instanceof AbstractContainerScreen<?> screen && owner.ourMenu(screen.getMenu());
            if (!ours) {
                invSigSet = false;
                return Reason.Screen;
            }
        }

        // Looked at every tick, so what changed while someone else was busy is not held against anyone later
        boolean taken = snapshot();

        boolean baritone = owner.baritoneActive();
        if (baritone && owner.baritoneForeign()) return Reason.Baritone;

        // The keys as they are physically held, not as the game sees them: Baritone's forced inputs show up in the
        // options' own state, but never in this. While the sorter walks with its own Baritone goal, pressing keys is
        // allowed and doesn't pause it
        if (!owner.walkingOurs() && !owner.justReleased() && !owner.drivingInput() && keysDown()) return Reason.Input;

        Modules modules = Modules.get();
        AutoEat eat = modules.get(AutoEat.class);
        if (eat != null && eat.isActive() && eat.eating) return Reason.Eating;
        // Only while they are actually working, not just switched on
        AutoTill till = modules.get(AutoTill.class);
        if (till != null && till.working()) return Reason.Module;
        AutoDirt dirt = modules.get(AutoDirt.class);
        if (dirt != null && dirt.working()) return Reason.Module;

        // Something was taken, moved or swapped outside the carry slots and the sorter didn't do it. Items turning up (picked
        // up off the ground) don't count
        if (taken && !owner.ownInventoryActivity() && !owner.walkingOurs()) return Reason.Inventory;

        return null;
    }

    private static boolean keysDown() {
        Options o = mc.options;
        return Input.isPressed(o.keyUp) || Input.isPressed(o.keyDown) || Input.isPressed(o.keyLeft) || Input.isPressed(o.keyRight)
            || Input.isPressed(o.keyJump) || Input.isPressed(o.keyShift) || Input.isPressed(o.keyAttack) || Input.isPressed(o.keyUse);
    }

    /**
     * Compares the slots outside the carry slots with how they were last tick, and remembers them as they are now.
     * @return true if any lost items or changed to something else
     */
    private boolean snapshot() {
        Set<Integer> carry = owner.carry();
        boolean first = !invSigSet;
        boolean taken = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack now = carry.contains(slot) ? ItemStack.EMPTY : mc.player.getInventory().getItem(slot);
            ItemStack before = seen[slot];
            if (!first && before != null && !before.isEmpty() && !carry.contains(slot)) {
                // Wear on a tool is not a change: only the item and the count are compared
                boolean same = !now.isEmpty() && before.getItem() == now.getItem();
                if (!same || now.getCount() < before.getCount()) taken = true;
            }
            seen[slot] = now.isEmpty() ? ItemStack.EMPTY : now.copy();
        }
        invSigSet = true;
        return taken;
    }

    private final ItemStack[] seen = new ItemStack[36];

    /**
     * Forgets the baseline, after the sorter changed the inventory itself.
     */
    void inventoryChangedByUs() {
        invSigSet = false;
    }

    // Quiet time

    /**
     * How long others must have been quiet before we carry on: the setting, doubled for each further interruption in
     * the last few minutes so a module that keeps coming back isn't raced, up to a minute.
     */
    int quietTicks() {
        int base = Math.max(0, owner.quietSeconds()) * 20;
        if (base == 0) return 0;

        int recent = interruptions.size();
        long scaled = recent > 1 ? (long) base << Math.min(recent - 1, 10) : base;
        return (int) Math.min(scaled, MAX_QUIET_TICKS);
    }

    boolean quietElapsed() {
        return now - lastForeign >= quietTicks();
    }

    Reason lastReason() {
        return lastReason;
    }

    /**
     * The sorter was interrupted in the middle of a job.
     */
    void interrupted() {
        interruptions.addLast(now);
    }

    // Saying so

    /**
     * Says once that the sorter is held up and by what.
     */
    void startWaiting(Reason reason) {
        if (waitStart >= 0) return;
        waitStart = now;
        owner.say(String.format("Waiting for you/another module (%s).", reason == null ? "a stack on the cursor" : reason.label));
    }

    void endWaiting() {
        if (waitStart >= 0 && now - waitStart > quietTicks()) owner.say("Resuming.");
        waitStart = -1;
    }

    /**
     * Someone else was busy within the last {@code ticks} ticks.
     */
    boolean foreignRecently(int ticks) {
        return now - lastForeign <= ticks;
    }

    boolean waiting() {
        return waitStart >= 0;
    }
}
