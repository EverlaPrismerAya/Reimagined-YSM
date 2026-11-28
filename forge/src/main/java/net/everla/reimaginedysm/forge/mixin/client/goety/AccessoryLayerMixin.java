package net.everla.reimaginedysm.forge.mixin.client.goety;

import com.elfmcys.yesstevemodel.client.renderer.CustomPlayerRenderer;
import net.everla.reimaginedysm.compat.goety.GoetyAccessoryLayer;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomPlayerRenderer.class)
public abstract class AccessoryLayerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void ysm$accessories(CallbackInfo ci) {
        if (ModList.get().isLoaded("youkaishomecoming")) {
            ((CustomPlayerRenderer) (Object) this).addLayerRenderer(
                    new net.everla.reimaginedysm.compat.youkai.YoukaiHeadLayer());
        }
        if (ModList.get().isLoaded("curios") && (ModList.get().isLoaded("goety")
                || ModList.get().isLoaded("goety_revelation") || ModList.get().isLoaded("starfantasy_goety")
                || ModList.get().isLoaded("esoteric_spell") || ModList.get().isLoaded("fantasy_ending"))) {
            ((CustomPlayerRenderer) (Object) this).addLayerRenderer(new GoetyAccessoryLayer());
        }
    }
}
