package net.everla.reimaginedysm.forge.mixin.client.goety;

import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.starfantasy.goety.client.HaloYsmEvents", remap = false)
public abstract class StarFantasyHaloEventsMixin {
    @Inject(method = "post", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$skipDuplicate(RenderLivingEvent.Post<?, ?> event, CallbackInfo ci) {
        if (event.getEntity() instanceof Player player && GoetyAccessoryLayer.rendered(player)) ci.cancel();
    }
}
