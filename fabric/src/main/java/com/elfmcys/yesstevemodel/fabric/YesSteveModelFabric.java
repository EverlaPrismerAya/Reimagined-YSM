package com.elfmcys.yesstevemodel.fabric;

import net.everla.reimaginedysm.core.RuntimePolicy;

import net.everla.reimaginedysm.core.YesSteveModel;
import net.fabricmc.api.ModInitializer;

public final class YesSteveModelFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        YesSteveModel.init();
        FabricCapabilityHooks.register();
    }
}
