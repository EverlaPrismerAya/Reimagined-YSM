package net.everla.reimaginedysm.mixin.client;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.client.entity.EntityRenderCache;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public class WorldRendererMixin {
    // Iris/Oculus calls renderEntity with a sun-view PoseStack during its shadow pass.
    // Capture that pass's view before the dispatcher adds the entity/model transforms;
    // the outer renderLevel scope still contains the main camera view at that point.
    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void ysmPhys$beginEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float partialTick,
                                     PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        net.everla.ysmPhysLib.WorldPhysicsContext.begin(poseStack.last().pose());
    }

    @Inject(method = "renderEntity", at = @At("RETURN"))
    private void ysmPhys$endEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float partialTick,
                                   PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        net.everla.ysmPhysLib.WorldPhysicsContext.end();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ysmPhys$begin(PoseStack poseStack, float partialTick, long finishNanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        net.everla.ysmPhysLib.WorldPhysicsContext.begin(poseStack.last().pose());
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ysmPhys$end(PoseStack poseStack, float partialTick, long finishNanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        net.everla.ysmPhysLib.WorldPhysicsContext.end();
    }

    @Inject(method = {"renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V")})
    private void renderLevel(PoseStack poseStack, float partialTick, long finishNanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            ModelPreviewRenderer.setFirstPersonMode(true);
            EntityRenderCache.tick(partialTick);
        }
    }

    @Inject(method = {"renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"}, at = {@At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;entitySolid(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;")})
    private void renderLevelPost(PoseStack poseStack, float partialTick, long finishNanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            EntityRenderCache.clear();
            ModelPreviewRenderer.setFirstPersonMode(false);
        }
    }
}
