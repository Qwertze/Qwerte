package com.porter.qwerte;

import com.mojang.logging.LogUtils;
import com.porter.qwerte.modules.AutoDirt;
import com.porter.qwerte.modules.AutoTill;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class QwerteAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Farming");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Qwerte Addon");

        // Modules
        Modules.get().add(new AutoTill());
        Modules.get().add(new AutoDirt());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.porter.qwerte";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("DustinFoes", "Qwerte");
    }
}
