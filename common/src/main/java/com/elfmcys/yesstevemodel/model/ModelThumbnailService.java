package com.elfmcys.yesstevemodel.model;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One bounded worker shared by integrated and dedicated servers. */
public final class ModelThumbnailService {
    @FunctionalInterface public interface Source { RawYsmModel read() throws Exception; }
    private record Subscriber(BooleanSupplier current, Consumer<byte[]> result) {}
    private static final Map<UUID,Long> pages=new ConcurrentHashMap<>();
    private static final Map<String,Job> jobs=new HashMap<>();
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),r -> {Thread t=new Thread(r,"YSM thumbnail CPU");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static { worker.allowCoreThreadTimeOut(true); }
    public static void page(UUID player,long page) {
        pages.put(player,page);
        synchronized(jobs) {
            var it=jobs.entrySet().iterator();
            while(it.hasNext()) {
                Job job=it.next().getValue();
                job.listeners.removeIf(s -> !s.current.getAsBoolean());
                if(job.listeners.isEmpty() && worker.remove(job)) it.remove();
            }
        }
    }
    public static boolean visible(UUID player,long page) { return Objects.equals(pages.get(player),page); }
    public static void forget(UUID player) { pages.remove(player); }
    public static Path cache(Path root,String hash) {
        if(!hash.matches("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{64})")) throw new IllegalArgumentException("Invalid model hash");
        return root.resolve("thumbnails-v"+ModelThumbnailRenderer.VERSION).resolve(hash.toLowerCase(Locale.ROOT)+".ysth");
    }
    /** Lightweight disk obfuscation only; network and renderer still use validated PNG bytes. */
    private static byte[] mask(String hash,byte[] bytes) {
        byte[] key;
        try { key=java.security.MessageDigest.getInstance("SHA-256").digest(
                ("Reimagined YSM thumbnail cache v1:"+hash.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        byte[] result=bytes.clone();
        for(int i=0;i<result.length;i++) result[i]^=key[i%key.length];
        return result;
    }
    public static byte[] readCache(Path root,String hash) throws java.io.IOException {
        Path path=cache(root,hash);
        if(Files.isRegularFile(path) && Files.size(path)<=ModelThumbnailRenderer.MAX_BYTES)
            return mask(hash,Files.readAllBytes(path));
        // Upgrade existing PNG caches without downloading or rendering again.
        Path legacy=path.resolveSibling(hash.toLowerCase(Locale.ROOT)+".png");
        if(Files.isRegularFile(legacy) && Files.size(legacy)<=ModelThumbnailRenderer.MAX_BYTES) {
            byte[] png=Files.readAllBytes(legacy);
            if(ModelThumbnailRenderer.validHeader(png)) { writeCache(root,hash,png);return png; }
        }
        return new byte[0];
    }
    public static void writeCache(Path root,String hash,byte[] png) throws java.io.IOException {
        if(!ModelThumbnailRenderer.validHeader(png)) throw new java.io.IOException("Invalid thumbnail cache image");
        Path path=cache(root,hash);
        Files.createDirectories(path.getParent());
        Path temp=Files.createTempFile(path.getParent(),"thumb-",".tmp");
        try { Files.write(temp,mask(hash,png));Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
        Files.deleteIfExists(path.resolveSibling(hash.toLowerCase(Locale.ROOT)+".png"));
    }
    public static void request(String hash,Source source,BooleanSupplier current,Consumer<byte[]> result) {
        synchronized(jobs) {
            Job job=jobs.get(hash);
            if(job!=null) { if(job.listeners.size()<256) job.listeners.add(new Subscriber(current,result)); return; }
            job=new Job(hash,source);job.listeners.add(new Subscriber(current,result));jobs.put(hash,job);
            try { worker.execute(job); }
            catch(RejectedExecutionException ex) { jobs.remove(hash); result.accept(new byte[0]); }
        }
    }
    private static final class Job implements Runnable {
        final String hash; final Source source; final List<Subscriber> listeners=new ArrayList<>();
        Job(String hash,Source source) { this.hash=hash;this.source=source; }
        @Override public void run() {
            byte[] png=new byte[0];
            try {
                synchronized(jobs) { if(listeners.stream().noneMatch(s->s.current.getAsBoolean())) return; }
                png=readCache(ServerModelManager.CACHE,hash);
                if (png.length > 0) {
                    try {
                        if (!ModelThumbnailRenderer.validHeader(png) || javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)) == null) png = new byte[0];
                    } catch (java.io.IOException invalid) { png = new byte[0]; }
                }
                if(png.length==0) {
                    png=ModelThumbnailRenderer.render(source.read());
                    writeCache(ServerModelManager.CACHE,hash,png);
                }
            } catch(Exception ex) { YesSteveModel.LOGGER.warn("Failed to generate model thumbnail {}",hash,ex); }
            finally {
                List<Subscriber> notify;
                synchronized(jobs) { jobs.remove(hash,this);notify=List.copyOf(listeners); }
                for(Subscriber s:notify) if(s.current.getAsBoolean()) s.result.accept(png);
            }
        }
    }
}
