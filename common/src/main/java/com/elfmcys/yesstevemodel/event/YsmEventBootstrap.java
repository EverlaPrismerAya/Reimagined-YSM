package com.elfmcys.yesstevemodel.event;

import com.elfmcys.yesstevemodel.RuntimePolicy;

import com.elfmcys.yesstevemodel.client.event.*;
import com.elfmcys.yesstevemodel.client.input.*;
import com.elfmcys.yesstevemodel.client.renderer.RendererManager;
import net.everla.reimaginedysm.api.PlatformAPI;

public final class YsmEventBootstrap {

    private YsmEventBootstrap() {
    }

    public static void register() {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        ServerStartupEvent.register();
        EnterServerEvent.register();
        PlayerLogoutEvent.register();
        CommonEvent.register();
        CommandRegistry.register();
        CapabilityEvent.register();
        net.everla.ysmJsLib.ScriptSync.register();
        if (!PlatformAPI.isServer()) {
            EntityJoinCallbackEvent.register();
            ClientSetupEvent.register();
            ClientTickEvent.register();
            ClientPlayerJoinNotification.register();
            ClientPlayerCloneEvent.register();
            AnimationLockEvent.register();
            PlayerSkinTextureManager.register();
            RendererManager.register();
            PlayerModelToggleKey.register();
            AnimationRouletteKey.register();
            DebugAnimationKey.register();
            ExtraPlayerRenderKey.register();
            ExtraAnimationKey.register();
            InputStateKey.register();
        }
    }
}
