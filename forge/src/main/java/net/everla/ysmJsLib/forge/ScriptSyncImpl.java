package net.everla.ysmJsLib.forge;

import net.everla.ysmJsLib.ScriptSync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

public final class ScriptSyncImpl {
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.StartTracking event)->{
            if(event.getEntity() instanceof ServerPlayer player)ScriptSync.track(player,event.getTarget(),true);
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.StopTracking event)->{
            if(event.getEntity() instanceof ServerPlayer player)ScriptSync.track(player,event.getTarget(),false);
        });
    }
}
