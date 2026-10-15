package com.porter.qwerte.utils;

import com.porter.qwerte.compat.Players;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Keeps the tools and resources a module works with in the hotbar. An item missing from the hotbar is pulled out of the
 * main inventory, and a stack that runs low is topped up from it. Nothing is ever dropped: a slot that has to be freed
 * is swapped with the item's old inventory slot, and a slot holding something else the module needs is never touched.
 */
public class AutoHotbar {
    /**
     * Something the module wants in its hotbar.
     */
    public static final class Need {
        private final String name;
        private final Predicate<ItemStack> test;
        private final IntSupplier slot;
        private final boolean stackable;
        private final BooleanSupplier active;
        private Predicate<ItemStack> mate;

        /**
         * @param name      what it's called in warnings
         * @param test      matches the items that will do
         * @param slot      0 for any free slot, 1-9 for that hotbar slot
         * @param stackable whether a low stack should be topped up from the inventory
         * @param active    whether the module needs it right now
         */
        public Need(String name, Predicate<ItemStack> test, IntSupplier slot, boolean stackable, BooleanSupplier active) {
            this.name = name;
            this.test = test;
            this.slot = slot;
            this.stackable = stackable;
            this.active = active;
        }

        public Predicate<ItemStack> test() {
            return test;
        }

        /**
         * Items that may be swapped out of this need's assigned slot even though they're protected, like the empty
         * bucket a water bucket turns into.
         */
        public Need sharing(Predicate<ItemStack> mate) {
            this.mate = mate;
            return this;
        }
    }

    // Ticks between two proactive moves, so the inventory isn't churned and the module gets ticks to do its work in
    private static final int MOVE_GAP = 3;

    private final BooleanSupplier enabled;
    private final Predicate<ItemStack> keep;
    private final Consumer<String> warn;
    private final Consumer<String> info;
    private final List<Need> needs;
    private final Set<String> warned = new HashSet<>();
    private int cooldown;

    /**
     * @param keep items that are never swapped out of the way on top of {@link #isProtected}, whether or not they are a
     *             need (a shovel for digging, say)
     */
    public AutoHotbar(BooleanSupplier enabled, Predicate<ItemStack> keep, Consumer<String> warn, Consumer<String> info, List<Need> needs) {
        this.enabled = enabled;
        this.keep = keep;
        this.warn = warn;
        this.info = info;
        this.needs = needs;
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    public void reset() {
        cooldown = 0;
        warned.clear();
    }

    /**
     * Checks every need in order and makes the first move one of them calls for. Meant to be called once a tick while the
     * module is idle; returns true if it moved something, in which case the module should leave the rest of the tick.
     */
    public boolean tick() {
        if (!enabled.getAsBoolean() || !canMove()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        List<Need> active = active();
        int[] preferred = resolve(active);
        boolean[] reserved = reserved(preferred);

        for (int i = 0; i < active.size(); i++) {
            Need need = active.get(i);
            if (place(need, preferred[i], reserved, active) || topUp(need, preferred[i])) {
                cooldown = MOVE_GAP;
                return true;
            }
        }
        return false;
    }

    /**
     * Pulls the item into the hotbar right now if it isn't in there already, for when the module is about to use it.
     * Returns true if it moved something.
     */
    public boolean ensure(Need need) {
        if (!enabled.getAsBoolean() || !need.active.getAsBoolean()) return false;
        if (!canMove()) {
            // Only worth saying if the item is actually stuck in the inventory
            if (!present(need) && inInventory(need) != -1 && warned.add("blocked:" + need.name)) {
                warn.accept("Can't move the " + need.name + " into your hotbar while a screen is open or the cursor holds an item.");
            }
            return false;
        }

        List<Need> active = active();
        int[] resolved = resolve(active);
        int index = active.indexOf(need);
        int preferred = index >= 0 ? resolved[index] : need.slot.getAsInt() - 1;
        return place(need, preferred, reserved(resolved), active);
    }

    // Moving items around while a container is open or the cursor holds something would click the wrong slots
    private boolean canMove() {
        return mc.player != null && mc.gameMode != null && mc.screen == null
            && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty();
    }

    private List<Need> active() {
        List<Need> active = new ArrayList<>();
        for (Need need : needs) {
            if (need.active.getAsBoolean()) active.add(need);
        }
        return active;
    }

    /**
     * The hotbar slot (0-8) each need asked for, or -1 for any free slot. When two want the same slot the later one
     * goes without and the player is told, once.
     */
    private int[] resolve(List<Need> active) {
        int[] preferred = new int[active.size()];
        boolean[] claimed = new boolean[9];
        for (int i = 0; i < preferred.length; i++) {
            Need need = active.get(i);
            int slot = need.slot.getAsInt() - 1;
            if (slot < 0 || slot > 8) {
                preferred[i] = -1;
            }
            else if (claimed[slot]) {
                preferred[i] = -1;
                String message = "Hotbar slot " + (slot + 1) + " is assigned to more than one item, using any free slot for the " + need.name + ".";
                if (warned.add(message)) warn.accept(message);
            }
            else {
                claimed[slot] = true;
                preferred[i] = slot;
            }
        }
        return preferred;
    }

    private boolean[] reserved(int[] preferred) {
        boolean[] reserved = new boolean[9];
        for (int slot : preferred) {
            if (slot >= 0) reserved[slot] = true;
        }
        return reserved;
    }

    /**
     * Things a player keeps in their hotbar for themselves, which are never moved out of the way: weapons, shields,
     * totems, food and other consumables, ender pearls, fireworks, arrows, fishing rods, wind charges, flint and steel,
     * lava buckets, spears, armor and elytra, and shulker boxes.
     */
    public static boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
            || stack.is(Items.MACE) || stack.is(Items.TRIDENT) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW)
            || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.SHIELD)
            || stack.has(DataComponents.FOOD) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.ENDER_PEARL) || stack.is(Items.FIREWORK_ROCKET) || stack.is(Items.ELYTRA)
            || stack.has(DataComponents.EQUIPPABLE)
            || stack.is(ItemTags.ARROWS) || stack.is(Items.FISHING_ROD) || stack.is(Items.WIND_CHARGE)
            || stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.LAVA_BUCKET) || Players.isSpear(stack)
            // Potions, milk and the like. A need's own item is never judged by this, only what's in its way.
            || stack.has(DataComponents.CONSUMABLE)
            || Utils.isShulker(stack.getItem());
    }

    private boolean neededByOther(ItemStack stack, Need except, List<Need> active) {
        if (stack.isEmpty()) return false;
        if (keep.test(stack) || isProtected(stack)) return true;
        for (Need other : active) {
            if (other != except && other.test.test(stack)) return true;
        }
        return false;
    }

    /**
     * Moves the item into the hotbar if it's not there yet.
     */
    private boolean place(Need need, int preferred, boolean[] reserved, List<Need> active) {
        if (present(need)) return false;

        int from = inInventory(need);
        if (from == -1) return false;

        int to = pickSlot(need, preferred, reserved, active);
        if (to == -1) {
            if (warned.add("full:" + need.name)) {
                warn.accept("No hotbar slot to put the " + need.name + " in, your hotbar is full of items I won't move. Free up a slot.");
            }
            return false;
        }

        // Swapping out the held item mid-swing or mid-use would break what it's doing
        if (to == Players.selectedSlot() && (mc.gameMode.isDestroying() || mc.player.isUsingItem())) return false;

        ItemStack evicted = mc.player.getInventory().getItem(to);
        if (!evicted.isEmpty() && warned.add("evict")) {
            info.accept("Moved " + evicted.getHoverName().getString() + " out of hotbar slot " + (to + 1) + " into your inventory to make room for the " + need.name + ".");
        }

        // Moving onto another item swaps them, so what was in the way ends up where the item came from
        InvUtils.move().from(from).toHotbar(to);
        return true;
    }

    private boolean present(Need need) {
        if (need.test.test(mc.player.getOffhandItem())) return true;
        for (int i = 0; i < 9; i++) {
            if (need.test.test(mc.player.getInventory().getItem(i))) return true;
        }
        return false;
    }

    private int inInventory(Need need) {
        for (int i = 9; i <= 35; i++) {
            if (need.test.test(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int pickSlot(Need need, int preferred, boolean[] reserved, List<Need> active) {
        if (preferred >= 0) {
            ItemStack there = mc.player.getInventory().getItem(preferred);
            if (!neededByOther(there, need, active) || (need.mate != null && need.mate.test(there))) return preferred;
        }

        for (int i = 0; i < 9; i++) {
            if (!reserved[i] && mc.player.getInventory().getItem(i).isEmpty()) return i;
        }

        // Nothing free: swap out something the module has no use for, leaving the selected slot for last
        int selected = Players.selectedSlot();
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (reserved[i] || need.test.test(stack)) continue;
            if (neededByOther(stack, need, active) && !(need.mate != null && need.mate.test(stack))) continue;
            if (i != selected) return i;
            fallback = i;
        }
        return fallback;
    }

    /**
     * Merges more of a stackable item into the hotbar when what's there has dropped under a quarter of a stack.
     */
    private boolean topUp(Need need, int preferred) {
        if (!need.stackable) return false;

        int target = -1;
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!need.test.test(stack)) continue;
            total += stack.getCount();
            if (target == -1 || i == preferred) target = i;
        }
        if (target == -1) return false;

        ItemStack held = mc.player.getInventory().getItem(target);
        if (held.getMaxStackSize() <= 1 || total >= Math.max(2, held.getMaxStackSize() / 4)) return false;

        // Only the same kind merges, anything else would just swap places
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
                InvUtils.move().from(i).toHotbar(target);
                return true;
            }
        }
        return false;
    }
}
