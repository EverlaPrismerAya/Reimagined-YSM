package com.elfmcys.yesstevemodel.event;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import dev.architectury.event.events.common.LifecycleEvent;

public final class ServerStartupEvent {

    private ServerStartupEvent() {
    }

    public static void register() {
        LifecycleEvent.SERVER_STOPPED.register(server -> net.everla.ysmJsLib.ScriptSync.clearServer());
        LifecycleEvent.SERVER_BEFORE_START.register(server -> {
            if (!YesSteveModel.isAvailable()) {
                return;
            }
            if (ServerModelManager.canReuseLoadedModels()) {
                return;
            }
            ServerModelManager.loadModels(result -> {
                if (!result.isSuccess()) {
                    server.execute(() -> {
                        throw new RuntimeException("YSM Loading Failed: " + result.getErrorMessage().getString(256));
                    });
                }
            }, null);
        });
    }
}
