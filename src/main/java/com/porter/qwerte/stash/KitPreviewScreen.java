package com.porter.qwerte.stash;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shows a saved kit in a vanilla chest screen, so hovering an item gives its full tooltip. Look only: every click is
 * ignored, and the screen never talks to the server.
 */
public class KitPreviewScreen extends ContainerScreen {
    // Not a container id the server will ever have open for us, in case anything slips through
    private static final int FAKE_CONTAINER_ID = 254;

    private KitPreviewScreen(ChestMenu menu, Component title) {
        super(menu, mc.player.getInventory(), title);
    }

    public static void open(Kit kit) {
        SimpleContainer container = new SimpleContainer(Kit.SLOTS);
        for (int i = 0; i < Kit.SLOTS; i++) container.setItem(i, kit.stackAt(i).copy());

        ChestMenu menu = ChestMenu.threeRows(FAKE_CONTAINER_ID, mc.player.getInventory(), container);
        mc.setScreen(new KitPreviewScreen(menu, Component.literal(kit.name)));
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
