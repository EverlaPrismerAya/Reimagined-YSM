package com.elfmcys.yesstevemodel.forge.mixin.client.goety;

import com.mojang.blaze3d.vertex.PoseStack;
import net.everla.reimaginedysm.compat.goety.HeadWearRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

@Pseudo
@Mixin(targets = "com.Polarice3.Goety.client.render.WearRenderer", remap = false)
public abstract class WearRendererMixin implements HeadWearRenderer {
    @Unique private boolean ysm$renderingHead;
    @Shadow(remap = false)
    private HumanoidModel<LivingEntity> getModel() { throw new AssertionError(); }

    @Shadow(remap = false)
    private void render(LivingEntity entity, ItemStack stack, PoseStack poses,
                        MultiBufferSource buffers, int light) { throw new AssertionError(); }

    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/Polarice3/Goety/client/render/ModRenderType;wraith(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"), remap = false)
    private RenderType ysm$headGlowDepth(ResourceLocation texture, Operation<RenderType> original) {
        // Only adapt our relocated head layer; do not change Goety's other wraith effects.
        if (ysm$renderingHead
                && net.everla.reimaginedysm.compat.oculus.OculusCompat.isShaderPackInUse())
            return RenderType.entityTranslucent(texture);
        return original.call(texture);
    }

    @Override
    public void ysm$renderHead(LivingEntity entity, ItemStack stack, PoseStack poses,
                              MultiBufferSource buffers, int light, float age) {
        var model = getModel();
        model.setupAnim(entity, 0, 0, age, 0, 0);
        // The YSM locator already contains the animated head pose and player scale.
        model.head.resetPose();
        model.young = false;
        boolean previous = ysm$renderingHead;
        ysm$renderingHead = true;
        try { render(entity, stack, poses, buffers, light); }
        finally { ysm$renderingHead = previous; }
    }
}
