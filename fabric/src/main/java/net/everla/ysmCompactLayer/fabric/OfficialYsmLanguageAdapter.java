package net.everla.ysmCompactLayer.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.everla.ysmCompactLayer.YsmIdentity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fabric constructs language adapters before registering entrypoints or initializing Mixin. */
public final class OfficialYsmLanguageAdapter implements LanguageAdapter {
    private static boolean disabled;

    public OfficialYsmLanguageAdapter() {
        if (disabled) return;
        ModContainer official = FabricLoader.getInstance().getModContainer(YsmIdentity.OFFICIAL_MOD_ID)
                .orElseThrow(() -> new IllegalStateException("RYSM requires official YSM 2.6.x"));
        LoaderModMetadata original = (LoaderModMetadata) official.getMetadata();
        if (!original.getVersion().getFriendlyString().matches("2\\.6\\.\\d+(?:[-+].*)?")) {
            throw new IllegalStateException("Unsupported official YSM: " + original.getVersion());
        }
        if (!original.getLanguageAdapterDefinitions().isEmpty()) {
            throw new IllegalStateException("Cannot safely disable official YSM language adapters");
        }
        LoaderModMetadata inert = (LoaderModMetadata) Proxy.newProxyInstance(
                LoaderModMetadata.class.getClassLoader(), new Class<?>[]{LoaderModMetadata.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getEntrypointKeys" -> Set.of();
                        case "getEntrypoints", "getOldInitializers", "getMixinConfigs" -> List.of();
                        case "getLanguageAdapterDefinitions" -> Map.of();
                        case "getAccessWidener" -> null;
                        // Cardinal Components also reads registrations directly from metadata.
                        case "getCustomValue" -> "cardinal-components".equals(args[0])
                                ? null : original.getCustomValue((String) args[0]);
                        case "containsCustomValue" -> !"cardinal-components".equals(args[0])
                                && original.containsCustomValue((String) args[0]);
                        case "getCustomValues" -> original.getCustomValues().entrySet().stream()
                                .filter(entry -> !entry.getKey().equals("cardinal-components"))
                                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
                        default -> {
                            try { yield method.invoke(original, args); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        }
                    };
                });
        try {
            Field metadata = official.getClass().getDeclaredField("info");
            metadata.setAccessible(true);
            metadata.set(official, inert);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot disable official YSM before Fabric initialization", e);
        }
        if (official.getMetadata() != inert) throw new IllegalStateException("Official YSM takeover failed");
        disabled = true;
        System.getLogger("rysm").log(System.Logger.Level.INFO,
                "Official YSM {0} disabled; its builtin resources remain available", original.getVersion());
    }

    public static void verifyDisabled() {
        if (!disabled) throw new IllegalStateException("Official YSM was not disabled before Fabric startup");
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        throw new LanguageAdapterException("RYSM takeover adapter is bootstrap-only");
    }
}
