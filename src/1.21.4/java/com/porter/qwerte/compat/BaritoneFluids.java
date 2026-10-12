package com.porter.qwerte.compat;

import baritone.api.Settings;

/**
 * Baritone for Minecraft 1.21.4 has no settings for placing blocks in water, so there's nothing to change here.
 * Standing on the bank while filling buckets is what keeps pools intact on this version.
 */
public final class BaritoneFluids {
    private BaritoneFluids() {
    }

    public static void protect(Settings settings) {
    }

    public static void restore(Settings settings) {
    }
}
