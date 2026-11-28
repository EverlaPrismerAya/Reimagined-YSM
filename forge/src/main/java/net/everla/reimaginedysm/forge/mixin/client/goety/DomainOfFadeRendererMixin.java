package net.everla.reimaginedysm.forge.mixin.client.goety;

import net.everla.reimaginedysm.compat.goety.FantasyEndingHaloRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.mega.uom.client.render.curios.DomainOfFadeRenderer", remap = false)
public abstract class DomainOfFadeRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderBuffers;bufferSource()Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;",
            remap = true), remap = false)
    private MultiBufferSource.BufferSource ysm$isolateHaloBuffers(RenderBuffers buffers) {
        return FantasyEndingHaloRenderState.bufferSource(buffers);
    }
}
