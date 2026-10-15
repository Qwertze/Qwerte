package com.porter.qwerte.compat;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Player helpers that differ between Minecraft versions, for Minecraft 1.21.11.
 */
public final class Players {
    private Players() {
    }

    /**
     * The hotbar slot the player has selected.
     */
    public static int selectedSlot() {
        return mc.player.getInventory().getSelectedSlot();
    }

    public static boolean isSpear(ItemStack stack) {
        return stack.is(ItemTags.SPEARS);
    }
}
