package com.porter.qwerte.compat;

import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Player helpers that differ between Minecraft versions, for Minecraft 1.21.4.
 */
public final class Players {
    private Players() {
    }

    /**
     * The hotbar slot the player has selected.
     */
    public static int selectedSlot() {
        return mc.player.getInventory().selected;
    }

    /**
     * Spears don't exist in this version.
     */
    public static boolean isSpear(ItemStack stack) {
        return false;
    }
}
