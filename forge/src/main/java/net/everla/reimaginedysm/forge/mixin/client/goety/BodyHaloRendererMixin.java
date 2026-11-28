package net.everla.reimaginedysm.forge.mixin.client.goety;

import com.mojang.blaze3d.vertex.PoseStack;
import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.SlotContext;

@Pseudo
@Mixin(targets = {"com.mega.uom.client.render.curios.DomainOfFadeRenderer",
        "com.inolia_zaicek.barrier_spell.client.ring.RisingRingCurioRenderer"}, remap = false)
public abstract class BodyHaloRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ysm$skipDuplicate(ItemStack stack, SlotContext slot, PoseStack poses,
            RenderLayerParent<?, ?> parent, MultiBufferSource buffers, int light,
            float limbSwing, float limbAmount, float partialTick, float age, float yaw, float pitch,
            CallbackInfo ci) {
        if (!GoetyAccessoryLayer.isRendering() && slot.entity() instanceof Player player
                && GoetyAccessoryLayer.rendered(player)) ci.cancel();
    }
}
