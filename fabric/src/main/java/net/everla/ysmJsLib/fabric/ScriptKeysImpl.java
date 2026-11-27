package net.everla.ysmJsLib.fabric;
import net.minecraft.client.KeyMapping;
public final class ScriptKeysImpl {
    public static boolean matches(KeyMapping mapping,int key,int scan,int mouse){return mouse>=0?mapping.matchesMouse(mouse):mapping.matches(key,scan);}
}
