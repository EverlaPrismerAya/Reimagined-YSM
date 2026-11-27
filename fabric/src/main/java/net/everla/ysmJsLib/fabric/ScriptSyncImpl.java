package net.everla.ysmJsLib.fabric;

import net.everla.ysmJsLib.ScriptSync;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;

public final class ScriptSyncImpl {
    public static void register() {
        EntityTrackingEvents.START_TRACKING.register((entity,player)->ScriptSync.track(player,entity,true));
        EntityTrackingEvents.STOP_TRACKING.register((entity,player)->ScriptSync.track(player,entity,false));
    }
}
