package net.everla.reimaginedysm.compat.oculus;

import java.lang.reflect.Method;
import java.util.Optional;

/** Read effective pack options, including defaults and profiles, after a reload. */
public final class ShaderOutlineCompat {
    private static Method currentPack;
    private static boolean initialized;
    private static Object lastPack;
    private static boolean enabled;

    private ShaderOutlineCompat() {}

    public static boolean isEnabled() {
        if (!OculusCompat.isShaderPackInUse()) return false;
        try {
            if (!initialized) {
                initialized = true;
                for (String name : new String[]{"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"}) {
                    try {
                        currentPack = Class.forName(name).getMethod("getCurrentPack");
                        break;
                    } catch (ClassNotFoundException | NoSuchMethodException ignored) {}
                }
            }
            if (currentPack == null) return false;
            Object pack = ((Optional<?>) currentPack.invoke(null)).orElse(null);
            if (pack == lastPack) return enabled;
            lastPack = pack;
            enabled = false;
            if (pack == null) return false;
            Object options = pack.getClass().getMethod("getShaderPackOptions").invoke(pack);
            Object values = options.getClass().getMethod("getOptionValues").invoke(options);
            String prefix = currentPack.getDeclaringClass().getPackageName();
            Class<?> api = Class.forName(prefix + ".shaderpack.option.values.OptionValues");
            Object set = api.getMethod("getOptionSet").invoke(values);
            var strings = (java.util.Map<?, ?>) set.getClass().getMethod("getStringOptions").invoke(set);
            var booleans = (java.util.Map<?, ?>) set.getClass().getMethod("getBooleanOptions").invoke(set);
            // BSL and derivatives expose numeric OUTLINE; Complementary exposes
            // WORLD_OUTLINE plus an independent entity inclusion switch.
            String outline = strings.containsKey("OUTLINE")
                    ? (String) api.getMethod("getStringValueOrDefault", String.class).invoke(values, "OUTLINE") : null;
            boolean bsl = outline != null && outline.matches("[1-4]");
            Method bool = api.getMethod("getBooleanValueOrDefault", String.class);
            boolean world = booleans.containsKey("WORLD_OUTLINE") && booleans.containsKey("WORLD_OUTLINE_ON_ENTITIES")
                    && (boolean) bool.invoke(values, "WORLD_OUTLINE")
                    && (boolean) bool.invoke(values, "WORLD_OUTLINE_ON_ENTITIES");
            enabled = bsl || world;
            return enabled;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
