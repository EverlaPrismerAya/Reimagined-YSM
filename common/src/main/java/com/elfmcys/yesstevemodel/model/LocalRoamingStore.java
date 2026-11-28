package com.elfmcys.yesstevemodel.model;

import com.google.gson.*;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only roaming values survive player replacement; persisted names never depend on StringPool IDs. */
public final class LocalRoamingStore {
    private final Path root;
    private final Map<String,Int2FloatOpenHashMap> values=new HashMap<>();
    private final Map<String,String> pending=new HashMap<>();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"YSM local roaming");t.setDaemon(true);return t;});
    public LocalRoamingStore(Path root) {
        this.root=root;writer.scheduleWithFixedDelay(this::flush,500,500,TimeUnit.MILLISECONDS);
    }
    private static String key(UUID player,String hash) {
        if(hash==null || !hash.matches("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{64})"))throw new IllegalArgumentException("Invalid model content hash");
        return player+"/"+hash.toLowerCase(Locale.ROOT)+".json";
    }
    public synchronized Int2FloatOpenHashMap load(UUID player,String hash) {
        String key=key(player,hash);
        return values.computeIfAbsent(key,k->{
            Int2FloatOpenHashMap vars=new Int2FloatOpenHashMap();
            Path file=root.resolve(k);
            try {
                if(Files.isRegularFile(file) && Files.size(file)<=1048576) {
                    JsonObject json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    for(var entry:json.entrySet()) {
                        float value=entry.getValue().getAsFloat();
                        if(entry.getKey().length()<=256 && Float.isFinite(value) && vars.size()<4096)vars.put(StringPool.computeIfAbsent(entry.getKey()),value);
                    }
                }
            } catch(Exception ex) { net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("Cannot read local roaming state {}",file,ex); }
            return vars;
        });
    }
    public synchronized void changed(UUID player,String hash,Int2FloatOpenHashMap vars) {
        JsonObject json=new JsonObject();
        for(var entry:vars.int2FloatEntrySet()) {
            String name=StringPool.getString(entry.getIntKey());float value=entry.getFloatValue();
            if(name!=null && name.length()<=256 && Float.isFinite(value) && json.size()<4096)json.addProperty(name,value);
        }
        pending.put(key(player,hash),json.toString());
    }
    public synchronized void flush() {
        var it=pending.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();Path file=root.resolve(entry.getKey());
            try {
                Files.createDirectories(file.getParent());
                Path temp=Files.createTempFile(file.getParent(),"roaming-",".tmp");
                try { Files.writeString(temp,entry.getValue());Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); }
                it.remove();
            } catch(Exception ex) { net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("Cannot save local roaming state {}",file,ex); }
        }
    }
    public void close() { writer.shutdown();flush(); }
}
