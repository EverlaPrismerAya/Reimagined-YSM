package net.everla.reimaginedysm.mixin.client;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.KeyboardHandler.class)
public class ScriptKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"))
    private void rysm$key(long window,int key,int scan,int action,int mods,CallbackInfo ci) {
        if(window==net.minecraft.client.Minecraft.getInstance().getWindow().getWindow())net.everla.ysmJsLib.ScriptInput.key(key,scan,action);
    }
}
