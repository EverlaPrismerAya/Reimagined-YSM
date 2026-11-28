package net.everla.reimaginedysm.forge.mixin.client.goety;

import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.everla.showmeurhalo.client.RevelationHaloRenderer", remap = false)
public abstract class ShowMeYourRevelationHaloMixin {
    @Redirect(method = "renderPlayerHalos", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;isSpectator()Z", remap = true), remap = false)
    private static boolean ysm$skipNativePlayer(AbstractClientPlayer player) {
        return player.isSpectator() || GoetyAccessoryLayer.rendered(player);
    }
}
