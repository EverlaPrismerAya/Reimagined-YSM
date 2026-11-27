package com.elfmcys.yesstevemodel.client;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.resource.*;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import net.everla.reimaginedysm.legacy.YesModelUtils;
import net.everla.reimaginedysm.security.YSMByteBuf;
import net.everla.reimaginedysm.security.YsmCrypt;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Never registers private models in ServerModelManager's shared catalog. */
final class PrivateModelLoader {
    static final Path FOLDER = ServerModelManager.FOLDER.resolve("client");

    static void load(BiConsumer<String, RawYsmModel> consumer) throws Exception {
        Files.createDirectories(FOLDER);
        scan(FOLDER, "client/", consumer);
    }

    private static void scan(Path root, String prefix, BiConsumer<String, RawYsmModel> consumer) throws Exception {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(p -> Files.isRegularFile(p) && (p.getFileName().toString().equals("ysm.json")
                    || p.toString().toLowerCase(Locale.ROOT).endsWith(".ysm") || p.toString().toLowerCase(Locale.ROOT).endsWith(".zip"))).sorted().toList();
        }
        for (Path file : sources) {
            try {
                if (file.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    try (FileSystem zip = com.elfmcys.yesstevemodel.util.ModelArchives.openZip(file)) {
                        scan(zip.getPath("/"), prefix + root.relativize(file).toString().replace('\\', '/') + "/", consumer);
                    }
                    continue;
                }
                boolean directory = file.getFileName().toString().equals("ysm.json");
                Path source = directory ? file.getParent() : file;
                String id = prefix + root.relativize(source).toString().replace('\\', '/');
                RawYsmModel raw;
                if (directory) {
                    try (YSMFolderDeserializer reader = new YSMFolderDeserializer(source)) { raw = reader.deserialize(); }
                } else {
                    byte[] bytes = Files.readAllBytes(file);
                    int cryptoVersion = YesModelUtils.getYsmCryptoVersion(bytes);
                    if (cryptoVersion == 1 || cryptoVersion == 2) {
                        try (YSMFolderDeserializer reader = new YSMFolderDeserializer(YesModelUtils.input(bytes))) { raw = reader.deserialize(); }
                    } else {
                        try (YSMBinaryDeserializer reader = new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(bytes))) {
                            raw = reader.deserializeKeepOpen();
                            reader.parseYSMFooter(raw);
                        }
                    }
                }
                if(!directory)net.everla.ysmJsLib.ScriptResources.companion(raw,file);
                net.everla.ysmJsLib.ScriptResources.prepare(raw);
                try (YSMByteBuf serialized = YSMBinarySerializer.serialize(raw, 32, true)) {
                    SharedModelCache.store(raw.properties.sha256, new YsmCrypt.CachePayload(serialized.toArray(), 32));
                }
                consumer.accept(id, raw);
            } catch (Exception e) {
                YesSteveModel.LOGGER.warn("Skipped client-only model {}: {}", file, e.getMessage());
                YesSteveModel.LOGGER.debug("Client-only model failure: " + file, e);
            }
        }
    }
}
