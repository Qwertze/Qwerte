package com.porter.qwerte.stash;

import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shows one item from the stash in a vanilla container screen, so hovering it gives the full tooltip: name, enchants,
 * and everything else the game shows (all components with F3+H advanced tooltips on). Look only: every click is
 * ignored, and the screen never talks to the server.
 */
public class ItemPreviewScreen extends DispenserScreen {
    // Not a container id the server will ever have open for us, in case anything slips through
    private static final int FAKE_CONTAINER_ID = 254;

    private ItemPreviewScreen(DispenserMenu menu, Component title) {
        super(menu, mc.player.getInventory(), title);
    }

    public static void open(ItemStack stack, int total, String stashName) {
        SimpleContainer container = new SimpleContainer(9);
        ItemStack shown = stack.copyWithCount(Math.max(1, Math.min(total, stack.getMaxStackSize())));
        container.setItem(4, shown);

        DispenserMenu menu = new DispenserMenu(FAKE_CONTAINER_ID, mc.player.getInventory(), container);
        Component title = Component.literal(String.format("%s x%,d @ %s", stack.getHoverName().getString(), total, stashName));
        mc.setScreen(new ItemPreviewScreen(menu, title));
    }

    @Override
    public void onClose() {
        // The default also tells the server to close a container it never opened for us
        mc.setScreen(null);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        // Look, don't touch
    }
}
