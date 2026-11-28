package net.everla.ysmCompactLayer.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.everla.ysmCompactLayer.YsmIdentity;
import net.minecraftforge.fml.loading.LoadingModList;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** No Minecraft/Architectury references: also called during official Mixin-plugin loading. */
public final class ForgeOfficialDisabler {
    private static final String PLUGIN = "com.elfmcys.yesstevemodel.mixin.plugin.MixinTweaker";
    private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();
    private static volatile JsonObject profile;
    private static String officialVersion;
    private static volatile boolean verified;

    private ForgeOfficialDisabler() {}

    public static void markDisabled(String name) {
        // Reject unknown layouts before Forge can construct the mod or subscribe its events.
        if (profile == null) startupProfile();
        DISABLED.add(name);
    }

    private static synchronized JsonObject startupProfile() {
        if (profile != null) return profile;
        var fileInfo = LoadingModList.get().getModFileById(YsmIdentity.OFFICIAL_MOD_ID);
        if (fileInfo == null) throw new IllegalStateException("RYSM requires official YSM 2.6.x");
        officialVersion = fileInfo.getMods().stream()
                .filter(mod -> mod.getModId().equals(YsmIdentity.OFFICIAL_MOD_ID)).findFirst().orElseThrow()
                .getVersion().toString().split("[-+]", 2)[0];
        try (var stream = ForgeOfficialDisabler.class.getResourceAsStream("/rysm-official-forge.json")) {
            if (stream == null) throw new IllegalStateException("Missing official YSM takeover profiles");
            JsonObject profiles = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject candidate = profiles.getAsJsonObject("versions").getAsJsonObject(officialVersion);
            if (candidate == null) throw new IllegalStateException("Unsupported official YSM startup layout: " + officialVersion);
            Set<String> expected = strings(candidate, "targets");
            expected.remove(PLUGIN);
            Set<String> actual = fileInfo.getFile().getScanResult().getAnnotations().stream()
                    .filter(annotation -> annotation.annotationType().getClassName().equals("net.minecraftforge.fml.common.Mod")
                            || annotation.annotationType().getClassName().equals("net.minecraftforge.fml.common.Mod$EventBusSubscriber")
                            || annotation.annotationType().getClassName().equals("snownee.jade.api.WailaPlugin"))
                    .map(annotation -> annotation.clazz().getClassName()).collect(Collectors.toSet());
            if (!actual.equals(expected)) throw new IllegalStateException("Official YSM startup classes differ from " + officialVersion);
            profile = candidate;
            return profile;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read official YSM takeover profile", e);
        }
    }

    public static void verifyDisabled() {
        if (verified) return;
        JsonObject known = startupProfile();
        Set<String> targets = strings(known, "targets");
        Set<String> clientOnly = strings(known, "clientOnly");
        JsonObject optional = known.getAsJsonObject("optional");
        try {
            for (String name : targets) {
                if (!net.minecraftforge.fml.loading.FMLLoader.getDist().isClient() && clientOnly.contains(name)) continue;
                if (optional.has(name) && LoadingModList.get().getModFileById(optional.get(name).getAsString()) == null) continue;
                // Initializing a successfully transformed class only records this marker.
                Class.forName(name, true, ForgeOfficialDisabler.class.getClassLoader());
                if (!DISABLED.contains(name)) throw new IllegalStateException("Official YSM class was not disabled: " + name);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot verify official YSM takeover", e);
        }
        verified = true;
        org.apache.logging.log4j.LogManager.getLogger("rysm").info(
                "Official YSM {} disabled ({} startup targets); its builtin resources remain available", officialVersion, targets.size());
    }

    private static Set<String> strings(JsonObject object, String key) {
        return object.getAsJsonArray(key).asList().stream().map(value -> value.getAsString()).collect(Collectors.toSet());
    }
}
