package net.everla.reimaginedysm.core;

/** Loader-only policy: safe before Minecraft, Architectury and native initialization. */
public final class RuntimePolicy {
    private static final String BLOCKED_MOD = "needsofnature";
    private static volatile Boolean disabled;

    private RuntimePolicy() {
    }

    public static boolean isDisabled() {
        Boolean result = disabled;
        if (result != null) {
            return result;
        }
        // Reflection keeps loader-specific classes out of the shared class's linkage.
        // In particular, do not use Architectury's runtime platform bridge in Mixin plugins.
        try {
            Class<?> loader = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
            Object mods = loader.getMethod("get").invoke(null);
            if (mods == null) {
                return false; // Discovery has not completed: never cache this answer.
            }
            result = loader.getMethod("getModFileById", String.class).invoke(mods, BLOCKED_MOD) != null;
        } catch (ClassNotFoundException notForge) {
            try {
                Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object instance = loader.getMethod("getInstance").invoke(null);
                result = (Boolean) loader.getMethod("isModLoaded", String.class).invoke(instance, BLOCKED_MOD);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot query Fabric's mod list", e);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot query Forge's mod list", e);
        }
        disabled = result;
        return result;
    }
}
