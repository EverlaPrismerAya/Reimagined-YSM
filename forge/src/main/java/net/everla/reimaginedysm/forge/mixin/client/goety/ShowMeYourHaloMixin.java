package net.everla.reimaginedysm.forge.mixin.client.goety;

import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

@Pseudo
@Mixin(targets = "net.everla.showmeurhalo.client.HaloRenderer", remap = false)
public abstract class ShowMeYourHaloMixin {
    @Inject(method = "renderCurioContents", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$skipNativeCurio(ICurioRenderer renderer, ItemStack stack, SlotContext slot,
            PoseStack poses, RenderLayerParent<?, ?> parent, MultiBufferSource buffers, int light,
            @Coerce Object parameters, CallbackInfo ci) {
        if (slot.entity() instanceof Player player && GoetyAccessoryLayer.rendered(player)
                && GoetyAccessoryLayer.isSupported(stack)) ci.cancel();
    }

    @Inject(method = "isReplaying", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$allowNativeCurios(CallbackInfoReturnable<Boolean> ci) {
        if (GoetyAccessoryLayer.isRendering()) ci.setReturnValue(true);
    }
}
