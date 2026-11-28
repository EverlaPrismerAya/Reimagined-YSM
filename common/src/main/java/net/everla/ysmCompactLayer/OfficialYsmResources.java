package net.everla.ysmCompactLayer;

import dev.architectury.injectables.annotations.ExpectPlatform;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class OfficialYsmResources {
    private OfficialYsmResources() {}

    @ExpectPlatform
    public static void verifyTakeover() { throw new AssertionError(); }

    public static Path builtinDirectory() throws IOException {
        Path root = Platform.getMod(YsmIdentity.OFFICIAL_MOD_ID)
                .findResource("assets", YsmIdentity.OFFICIAL_MOD_ID, "builtin")
                .orElseThrow(() -> new IOException("Official YSM builtin resources are missing"));
        if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve("default/ysm.json"))) {
            throw new IOException("Incomplete official YSM builtin resources: " + root);
        }
        return root;
    }

    /** Do not prune existing models unless the complete official tree was read and copied. */
    public static void extractBuiltin(Path destination) throws IOException {
        Path root = builtinDirectory();
        Set<String> sourcePaths = new HashSet<>();
        try (var walker = Files.walk(root)) {
            for (Path source : walker.toList()) {
                String relative = root.relativize(source).toString().replace('\\', '/');
                sourcePaths.add(relative);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    // Compare contents: ZIP timestamps and file sizes alone cannot identify an update.
                    if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        try (var walker = Files.walk(destination)) {
            for (Path target : walker.sorted(Comparator.reverseOrder()).toList()) {
                String relative = destination.relativize(target).toString().replace('\\', '/');
                if (!relative.isEmpty() && !relative.equals("notice.txt") && !sourcePaths.contains(relative)) {
                    Files.delete(target);
                }
            }
        }
    }
}
