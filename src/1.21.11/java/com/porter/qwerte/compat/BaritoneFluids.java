package com.porter.qwerte.compat;

import baritone.api.Settings;

/**
 * Keeps Baritone from placing blocks in water, which deletes water sources, for Minecraft 1.21.11.
 * Only call this after checking Baritone is installed.
 */
public final class BaritoneFluids {
    private static Boolean placeInSource, placeInFlow;

    private BaritoneFluids() {
    }

    public static void protect(Settings settings) {
        if (placeInSource != null) return;

        placeInSource = settings.allowPlaceInFluidsSource.value;
        placeInFlow = settings.allowPlaceInFluidsFlow.value;
        settings.allowPlaceInFluidsSource.value = false;
        settings.allowPlaceInFluidsFlow.value = false;
    }

    public static void restore(Settings settings) {
        if (placeInSource == null) return;

        settings.allowPlaceInFluidsSource.value = placeInSource;
        settings.allowPlaceInFluidsFlow.value = placeInFlow;
        placeInSource = placeInFlow = null;
    }
}
