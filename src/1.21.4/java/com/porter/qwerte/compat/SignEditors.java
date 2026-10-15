package com.porter.qwerte.compat;

import meteordevelopment.meteorclient.mixin.AbstractSignEditScreenAccessor;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * Sign editor screens, for Minecraft 1.21.4. Meteor's accessor for the sign being edited is named differently between
 * versions.
 */
public final class SignEditors {
    private SignEditors() {
    }

    /**
     * The sign this editor is for.
     */
    public static SignBlockEntity signOf(AbstractSignEditScreen screen) {
        return ((AbstractSignEditScreenAccessor) screen).getSign();
    }
}
