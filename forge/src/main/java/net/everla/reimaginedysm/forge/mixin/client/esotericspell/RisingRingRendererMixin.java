package net.everla.reimaginedysm.forge.mixin.client.esotericspell;

import com.elfmcys.yesstevemodel.client.entity.CustomPlayerEntity;
import com.inolia_zaicek.barrier_spell.item.RisingInoliaRingHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.inolia_zaicek.barrier_spell.client.ring.RisingRingCurioRenderer", remap = false)
public abstract class RisingRingRendererMixin {
    @Inject(method = {"renderAfterLiving", "renderForYesSteveModel"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$skipPost(LivingEntity entity, PoseStack poses, MultiBufferSource buffers,
                                     int light, float partialTick, CallbackInfo ci) {
        if (entity instanceof Player player && GoetyAccessoryLayer.rendered(player) && ysm$ownsRing(entity)) ci.cancel();
    }

    @Inject(method = "renderInsideYesSteveModel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$useLocator(Object animatable, PoseStack poses, MultiBufferSource buffers,
                                      int light, float partialTick, CallbackInfo ci) {
        // This hook can run before our layer; the layer also owns hidden/model-provided rings.
        if (animatable instanceof CustomPlayerEntity player && ysm$ownsRing(player.getEntity())) ci.cancel();
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean ysm$ownsRing(LivingEntity entity) {
        return RisingInoliaRingHelper.findEquippedRing(entity).map(stack ->
                new ResourceLocation("esoteric_spell", "rising_inolia_ring")
                        .equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))).orElse(false);
    }
}
