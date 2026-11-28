package com.elfmcys.yesstevemodel.event;

import net.everla.reimaginedysm.core.YesSteveModel;
import net.everla.reimaginedysm.compat.touhoulittlemaid.TouhouMaidCompat;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import dev.architectury.event.events.common.LifecycleEvent;

public final class CommonEvent {

    private CommonEvent() {
    }

    public static void register() {
        LifecycleEvent.SETUP.register(() -> {
            if (!YesSteveModel.isAvailable()) {
                YesSteveModel.LOGGER.error(YesSteveModel.getErrorMessage());
                return;
            }
            NetworkHandler.init();
            TouhouMaidCompat.init();
        });
    }
}
