package net.everla.ysmJsLib;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/** Model resources only. Never loads client classes or executes JavaScript. */
public final class ScriptBundle {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_FILES = 512;
    public final Map<String, byte[]> files;
    public final String digest;
    public ScriptBundle(Map<String, byte[]> input) throws IOException {
        var copy = new TreeMap<String, byte[]>();
        long bytes = 0;
        for (var entry : input.entrySet()) {
            String path = entry.getKey();
            checkPath(path);
            bytes += entry.getValue().length;
            if (bytes > MAX_BYTES || copy.size() >= MAX_FILES) throw new IOException("RYSM resource limit exceeded");
            copy.put(path, entry.getValue().clone());
        }
        files = Collections.unmodifiableMap(copy);
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (var e : files.entrySet()) {
                hash.update(e.getKey().getBytes(StandardCharsets.UTF_8)); hash.update((byte)0);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(e.getValue().length).array());hash.update(e.getValue());
            }
            digest = HexFormat.of().formatHex(hash.digest());
        } catch (Exception e) { throw new IOException(e); }
        if (!files.containsKey("rysm/manifest.json")) throw new IOException("Missing rysm/manifest.json");
        if (manifest().get("version").getAsInt() != 1) throw new IOException("Unsupported RYSM version");
    }
    public static void checkPath(String path) throws IOException {
        if (!path.startsWith("rysm/") || path.contains("\\") || path.contains(":") ||
                Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals("..") || p.equals(".")))
            throw new IOException("Invalid RYSM resource path: " + path);
    }
    public JsonObject manifest() { return JsonParser.parseString(text("rysm/manifest.json")).getAsJsonObject(); }
    public String text(String path) {
        byte[] data=files.get(path); if(data==null)throw new IllegalArgumentException("Missing resource: "+path);
        if(data.length>1024*1024)throw new IllegalArgumentException("Text resource exceeds 1 MiB");
        return new String(data,StandardCharsets.UTF_8);
    }
    public static ScriptBundle directory(Path root) throws IOException {
        if (!Files.isDirectory(root.resolve("rysm"))) return null;
        var files = new TreeMap<String,byte[]>();long size=0;
        try(var paths=Files.walk(root.resolve("rysm"))) {
            for(Path p:paths.toList()) {
                if(Files.isSymbolicLink(p))throw new IOException("Symlinks are not allowed in RYSM resources");
                if(!Files.isRegularFile(p))continue;
                size+=Files.size(p);
                if(size>MAX_BYTES || files.size()>=MAX_FILES)throw new IOException("RYSM resource limit exceeded");
                files.put(root.relativize(p).toString().replace('\\','/'),Files.readAllBytes(p));
            }
        }
        return new ScriptBundle(files);
    }
    public static ScriptBundle read(byte[] archive) throws IOException {
        if(archive.length>MAX_BYTES)throw new IOException("RYSM archive too large");
        var files=new TreeMap<String,byte[]>(); long total=0;
        try(var channel=new SeekableInMemoryByteChannel(archive);
            var in=SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKb(65536).get()) {
            SevenZArchiveEntry e;
            while((e=in.getNextEntry())!=null) {
                if(e.isDirectory())continue;
                checkPath(e.getName());total+=e.getSize();
                if(e.getSize()<0 || total>MAX_BYTES || files.size()>=MAX_FILES || files.containsKey(e.getName()))
                    throw new IOException("Invalid RYSM archive size or duplicate entry");
                byte[] bytes=new byte[(int)e.getSize()];int offset=0;
                while(offset<bytes.length) {int n=in.read(bytes,offset,bytes.length-offset);if(n<=0)throw new EOFException();offset+=n;}
                files.put(e.getName(),bytes);
            }
        }
        return new ScriptBundle(files);
    }
    public byte[] archive() throws IOException {
        try(var channel=new SeekableInMemoryByteChannel()) {
            try(var out=new SevenZOutputFile(channel)) {
                // Startup may prepare multiple models concurrently; the default encoder dictionary is unnecessarily large.
                var options=new org.tukaani.xz.LZMA2Options(1);options.setDictSize(1024*1024);
                out.setContentMethods(List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2,options)));
                for(var e:files.entrySet()) {
                    var entry=new SevenZArchiveEntry();entry.setName(e.getKey());entry.setSize(e.getValue().length);
                    out.putArchiveEntry(entry);out.write(e.getValue());out.closeArchiveEntry();
                }
                out.finish();
                if(channel.size()>MAX_BYTES)throw new IOException("RYSM archive too large");
                return Arrays.copyOf(channel.array(),(int)channel.size());
            }
        }
    }
}
