package net.everla.ysmJsLib.forge;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
public final class ScriptKeysImpl {
    public static boolean matches(KeyMapping mapping,int key,int scan,int mouse){
        return mapping.isActiveAndMatches(mouse>=0?InputConstants.Type.MOUSE.getOrCreate(mouse):InputConstants.getKey(key,scan));
    }
}
