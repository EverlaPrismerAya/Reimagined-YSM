package com.elfmcys.yesstevemodel.fabric.client;

import net.everla.reimaginedysm.core.RuntimePolicy;

import com.elfmcys.yesstevemodel.client.ClientModelManager;
import com.elfmcys.yesstevemodel.client.renderer.AnimationDebugOverlay;
import com.elfmcys.yesstevemodel.client.renderer.ExtraPlayerOverlay;
import com.elfmcys.yesstevemodel.client.renderer.ModelSyncStateOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.everla.reimaginedysm.api.client.HudOverlay;

public final class YesSteveModelFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        HudOverlay debugOverlay = AnimationDebugOverlay.createOverlay();
        HudOverlay loadingOverlay = new ExtraPlayerOverlay();
        HudOverlay syncOverlay = new ModelSyncStateOverlay();
        HudRenderCallback.EVENT.register((guiGraphics, tickDelta) -> {
            Minecraft mc = Minecraft.getInstance();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            debugOverlay.render(guiGraphics, mc.font, tickDelta, w, h);
            loadingOverlay.render(guiGraphics, mc.font, tickDelta, w, h);
            syncOverlay.render(guiGraphics, mc.font, tickDelta, w, h);
        });

        ClientModelManager.loadDefaultModel();
    }
}
