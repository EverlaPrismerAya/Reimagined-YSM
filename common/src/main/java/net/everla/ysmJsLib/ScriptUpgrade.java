package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit, serial import queue; normal model loading never initiates migration. */
public final class ScriptUpgrade {
    private ScriptUpgrade() {}

    public static synchronized void process(Path inbox, Path custom) {
        try {
            Files.createDirectories(inbox);
            Files.createDirectories(custom);
            try (var entries = Files.list(inbox)) {
                for (Path source : entries.sorted().toList()) {
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                            || source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        try { upgrade(source, custom); }
                        catch (Exception e) { org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Upgrade retained input {}: {}", source, e.toString()); }
                    }
                }
            }
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Cannot process upgrade inbox {}", inbox, e);
        }
    }

    private static void upgrade(Path source, Path custom) throws Exception {
        if (Files.isSymbolicLink(source)) throw new IOException("Symbolic links are not supported");
        Path target = custom.resolve(source.getFileName());
        if (Files.exists(target)) throw new IOException("Output already exists: " + target);
        Path staging = Files.createTempDirectory(custom.getParent(), "rysm-upgrade-");
        Path staged = staging.resolve(source.getFileName());
        try {
            copy(source, staged);
            if (Files.isDirectory(staged)) migrateModels(staged);
            else try (var zip = com.elfmcys.yesstevemodel.util.ModelArchives.openZip(staged)) { migrateModels(zip.getPath("/")); }
            // Retain the complete original, then publish. Restore it if publication fails.
            Path backups = custom.getParent().resolve("rysm-backups");
            Files.createDirectories(backups);
            Path backup = backups.resolve(source.getFileName() + "-" + UUID.randomUUID());
            Files.move(source, backup);
            try { Files.move(staged, target); }
            catch (Exception e) {
                try { Files.move(backup, source); } catch (Exception restore) { e.addSuppressed(restore); }
                throw e;
            }
            org.slf4j.LoggerFactory.getLogger("rysmjs").info("Upgraded {} to {} (original {})", source, target, backup);
        } finally {
            try (var files = Files.walk(staging)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void migrateModels(Path root) throws Exception {
        List<Path> models;
        models = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (YSMFolderDeserializer.isModelFolder(dir)) {
                    models.add(dir);
                    // main.json/arm.json inside a model's assets are not another legacy model.
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (models.isEmpty()) throw new IOException("No source models found");
        for (Path model : models) {
            ScriptSyntaxMigration.migrate(model);
            try (var reader = new YSMFolderDeserializer(model)) {
                var raw = reader.deserialize();
                if (raw.mainEntity.mainModel == null || raw.rysmBundle == null)
                    throw new IOException("Invalid migrated model: " + model);
                new ScriptModel(raw.rysmBundle);
            }
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symbolic links are not supported");
                Path dest = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(dest); else Files.copy(file, dest);
            }
        }
    }
}
