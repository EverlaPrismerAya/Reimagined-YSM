package net.everla.reimaginedysm.forge.mixin.client.goety;

import net.everla.reimaginedysm.compat.goety.FantasyEndingHaloRenderState;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.mega.uom.client.render.shader.GlowRenderLayer", remap = false)
public abstract class GlowRenderLayerMixin {
    @Inject(method = "lambda$new$1", at = @At("RETURN"), remap = false)
    private static void ysm$clearHaloDelegate(boolean glow, RenderType delegate, CallbackInfo ci) {
        // Setup always runs; the native non-glowing branch omits delegate teardown,
        // leaking polygon offset and (with Fabulous graphics) the particles target.
        if (!glow && FantasyEndingHaloRenderState.isActive()) delegate.clearRenderState();
    }
}
