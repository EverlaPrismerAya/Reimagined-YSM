package net.everla.reimaginedysm.server;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.*;
import com.elfmcys.yesstevemodel.resource.*;
import com.elfmcys.yesstevemodel.util.DigestUtil;
import net.everla.reimaginedysm.legacy.YesModelUtils;
import net.everla.reimaginedysm.security.YsmCrypt;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded sequential upload per administrator. Disk validation/reload never blocks the game tick. */
public final class ModelUploadReceiver {
    public static final int LIMIT = 16 * 1024 * 1024, CHUNK = 32_000;
    private static final Map<UUID, Upload> uploads = new ConcurrentHashMap<>();
    private static final AtomicLong ids = new AtomicLong();
    private static final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "YSM-Upload-Reload"); thread.setDaemon(true); return thread;
    });
    private static final class Upload {
        final long id = ids.incrementAndGet();
        final String modelId, hash; final int size;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long updated = System.nanoTime();
        Upload(C2SModelUploadStartPacket packet) { modelId = packet.modelId(); hash = packet.sha256(); size = packet.totalBytes(); }
    }
    public static boolean allowed(ServerPlayer player) {
        return player.hasPermissions(2) || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }
    public static Path target(String id) {
        String name = id.endsWith(".ysm") ? id : id + ".ysm";
        if (name.length() > 200 || !name.matches("[a-zA-Z0-9_./-]+") || name.contains("..") || name.startsWith("/")
                || name.startsWith("client/") || name.contains("//")) throw new IllegalArgumentException("Invalid upload model id");
        Path root = ServerModelManager.CUSTOM.toAbsolutePath().normalize();
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid upload path");
        return path;
    }
    public static void start(ServerPlayer player, C2SModelUploadStartPacket packet) {
        uploads.entrySet().removeIf(entry -> System.nanoTime() - entry.getValue().updated > TimeUnit.MINUTES.toNanos(2));
        try {
            if (!allowed(player)) throw new IllegalArgumentException("Only server administrators may upload shared models");
            target(packet.modelId());
            if (packet.totalBytes() <= 0 || packet.totalBytes() > LIMIT || !packet.sha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid upload size/hash");
            Upload upload = new Upload(packet);
            if (uploads.putIfAbsent(player.getUUID(), upload) != null) throw new IllegalArgumentException("Upload already active");
            NetworkHandler.sendToClientPlayer(new S2CModelUploadStartPacket(upload.id, (byte) 0, CHUNK, LIMIT, 4, ""), player);
        } catch (Exception e) {
            NetworkHandler.sendToClientPlayer(new S2CModelUploadStartPacket(0, (byte) 1, CHUNK, LIMIT, 4, e.getMessage()), player);
        }
    }
    public static void chunk(ServerPlayer player, C2SModelUploadChunkPacket packet) {
        Upload upload = uploads.get(player.getUUID());
        if (upload == null || upload.id != packet.uploadId()) return;
        if (!allowed(player) || packet.offset() != upload.bytes.size() || packet.data().length == 0
                || packet.data().length > CHUNK || packet.data().length > upload.size - upload.bytes.size()) {
            uploads.remove(player.getUUID(), upload); result(player, upload, false, "Invalid upload chunk"); return;
        }
        upload.bytes.writeBytes(packet.data()); upload.updated = System.nanoTime();
    }
    public static void finish(ServerPlayer player, C2SModelUploadFinishPacket packet) {
        Upload upload = uploads.get(player.getUUID());
        if (upload == null || upload.id != packet.uploadId() || !uploads.remove(player.getUUID(), upload)) return;
        if (!allowed(player) || upload.bytes.size() != upload.size) { result(player, upload, false, "Incomplete upload"); return; }
        byte[] bytes = upload.bytes.toByteArray();
        io.execute(() -> {
            try {
                if (!DigestUtil.sha256Hex(bytes).equals(upload.hash)) throw new IllegalArgumentException("Upload checksum mismatch");
                int crypto = YesModelUtils.getYsmCryptoVersion(bytes);
                if (crypto == 1 || crypto == 2) {
                    try (var reader = new YSMFolderDeserializer(YesModelUtils.input(bytes))) { reader.deserialize(); }
                } else {
                    try (var reader = new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(bytes))) { reader.deserializeKeepOpen(); }
                }
                Path target = target(upload.modelId);
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
                try { Files.write(temp, bytes); Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); }
                boolean reloaded = ServerModelManager.loadModels(loaded -> player.getServer().execute(() ->
                        result(player, upload, loaded.isSuccess(), loaded.isSuccess() ? "Model reloaded; visible models load on demand" : "Model reload failed")), null);
                if (!reloaded) throw new IllegalStateException("Model reload failed");
            } catch (Exception e) {
                YesSteveModel.LOGGER.warn("Model upload failed", e);
                player.getServer().execute(() -> result(player, upload, false, e.getMessage() == null ? "Upload failed" : e.getMessage()));
            }
        });
    }
    private static void result(ServerPlayer player, Upload upload, boolean success, String message) {
        if (player.hasDisconnected()) return;
        NetworkHandler.sendToClientPlayer(new S2CModelUploadResultPacket(upload.id, (byte) (success ? 0 : 1), upload.modelId, 0, 0, message), player);
    }
    public static void cancel(UUID player) { uploads.remove(player); }
}
