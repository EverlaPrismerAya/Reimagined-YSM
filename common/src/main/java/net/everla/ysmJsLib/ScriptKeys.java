package net.everla.ysmJsLib;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.client.KeyMapping;
public final class ScriptKeys {
    @ExpectPlatform
    public static boolean matches(KeyMapping mapping,int key,int scan,int mouse){throw new AssertionError();}
}
