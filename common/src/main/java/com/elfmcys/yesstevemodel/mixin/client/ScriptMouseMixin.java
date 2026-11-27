package com.elfmcys.yesstevemodel.mixin.client;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.MouseHandler.class)
public class ScriptMouseMixin {
    @Inject(method="onPress",at=@At("HEAD"))
    private void rysm$mouse(long window,int button,int action,int mods,CallbackInfo ci) {
        if(window==net.minecraft.client.Minecraft.getInstance().getWindow().getWindow())net.everla.ysmJsLib.ScriptInput.mouse(button,action);
    }
}
