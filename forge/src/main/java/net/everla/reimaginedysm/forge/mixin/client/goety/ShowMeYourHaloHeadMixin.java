package net.everla.reimaginedysm.forge.mixin.client.goety;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.everla.showmeurhalo.client.YsmHeadAttachment", remap = false)
public abstract class ShowMeYourHaloHeadMixin {
    @Inject(method = {"beforeRender", "afterRender"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysm$useNativeLayer(CallbackInfo ci) {
        ci.cancel();
    }
}
