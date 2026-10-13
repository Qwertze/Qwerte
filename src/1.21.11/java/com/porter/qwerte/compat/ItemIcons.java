package com.porter.qwerte.compat;

import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Drawing items on the HUD, which differs between Minecraft versions, for Minecraft 1.21.11.
 */
public final class ItemIcons {
    private ItemIcons() {
    }

    /**
     * Draws an item inside {@code NametagUtils.begin}, the same way Meteor's nametags do. {@code count} is drawn in the
     * corner like a stack size, or nothing if null.
     */
    public static void draw(GuiGraphics graphics, ItemStack stack, int x, int y, float scale, String count) {
        RenderUtils.drawItem(graphics, stack, x, y, scale, count != null, count, false);
    }
}
