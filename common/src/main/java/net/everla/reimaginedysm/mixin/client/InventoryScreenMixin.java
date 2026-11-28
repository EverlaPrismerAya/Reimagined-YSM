package net.everla.reimaginedysm.mixin.client;

import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {
    // Cover both the mouse-following helper and direct inventory preview calls.
    @WrapOperation(method = "renderEntityInInventory", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;runAsFancy(Ljava/lang/Runnable;)V",
            remap = false))
    private static void ysm$renderPreview(Runnable render, Operation<Void> original) {
        boolean wasPreview = ModelPreviewRenderer.isPreview();
        ModelPreviewRenderer.setPreviewMode(true);
        try {
            original.call(render);
        } finally {
            ModelPreviewRenderer.setPreviewMode(wasPreview);
        }
    }
}
