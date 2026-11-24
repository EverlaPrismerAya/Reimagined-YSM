package com.elfmcys.yesstevemodel.mixin.plugin;

import com.elfmcys.yesstevemodel.util.obfuscate.Keep;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MixinTweaker implements IMixinConfigPlugin {

    @Keep
    public void onLoad(String str) {
    }

    @Keep
    public String getRefMapperConfig() {
        return null;
    }

    @Keep
    public boolean shouldApplyMixin(String str, String str2) {
        // This bridge removes foreign references to official YSM's obfuscated
        // classes. It must remain installed even when our renderer is disabled.
        if (str2.equals("com.elfmcys.yesstevemodel.forge.mixin.client.esotericspell.RisingRingYsmCompatMixin")) {
            return true;
        }
        if (str2.equals("com.elfmcys.yesstevemodel.mixin.client.RandomFramerateMixin")) {
            return com.elfmcys.yesstevemodel.RuntimePolicy.isDisabled();
        }
        return !com.elfmcys.yesstevemodel.RuntimePolicy.isDisabled();
    }

    @Keep
    public void acceptTargets(Set<String> set, Set<String> set2) {
    }

    @Keep
    public List<String> getMixins() {
        return null;
    }

    @Keep
    public void preApply(String str, ClassNode classNode, String str2, IMixinInfo iMixinInfo) {
    }

    @Keep
    public void postApply(String str, ClassNode classNode, String str2, IMixinInfo iMixinInfo) {
    }
}
