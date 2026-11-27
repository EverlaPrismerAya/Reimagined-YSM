package com.elfmcys.yesstevemodel.util;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipException;

/** Shares filename decoding between public, private and upgrade ZIP sources. */
public final class ModelArchives {
    private ModelArchives() {}

    public static FileSystem openZip(Path file) throws IOException {
        try {
            return FileSystems.newFileSystem(file, Map.of());
        } catch (ZipException original) {
            if (original.getMessage() != null && original.getMessage().contains("encrypted entry"))
                throw new IOException("Encrypted model ZIP requires a password; extract it to a folder before importing", original);
            if (original.getMessage() == null || !original.getMessage().contains("bad entry name")) throw original;
            // Old Chinese ZIP tools commonly use GBK/GB18030 without the UTF-8 flag.
            for (String encoding : new String[]{"GB18030", "IBM437"}) {
                try {
                    return FileSystems.newFileSystem(file, Map.of("encoding", encoding));
                } catch (ZipException invalid) {
                    if (invalid.getMessage() == null || !invalid.getMessage().contains("bad entry name")) throw invalid;
                    original.addSuppressed(invalid);
                }
            }
            throw original;
        }
    }
}
