package net.everla.reimaginedysm.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadLocalRandom;

/** Applied only when RuntimePolicy detects needsofnature. */
@Mixin(Minecraft.class)
public abstract class RandomFramerateMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void ysm$randomFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ThreadLocalRandom.current().nextInt(3, 68));
    }
}
