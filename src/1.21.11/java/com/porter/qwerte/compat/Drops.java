package com.porter.qwerte.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.inventory.ClickType;

/**
 * Tells from the packets the client sends when the player is dropping items or breaking blocks, for Minecraft 1.21.11.
 */
public final class Drops {
    private static final int OUTSIDE_WINDOW = -999;

    private Drops() {
    }

    /**
     * The packet drops or throws an item: the drop keys, a throw click in a container, a click outside the window with a
     * stack on the cursor, or dropping in creative.
     */
    public static boolean isDrop(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return action.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM
                || action.getAction() == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS;
        }
        if (packet instanceof ServerboundContainerClickPacket click) {
            ClickType type = click.clickType();
            return type == ClickType.THROW || (type == ClickType.PICKUP && click.slotNum() == OUTSIDE_WINDOW);
        }
        if (packet instanceof ServerboundSetCreativeModeSlotPacket creative) return creative.slotNum() < 0;
        return false;
    }

    /**
     * The block the packet starts or finishes breaking, or null if it isn't about breaking.
     */
    public static BlockPos brokenBlock(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
            || action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
            || action.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)) {
            return action.getPos();
        }
        return null;
    }
}
