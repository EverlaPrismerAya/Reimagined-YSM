package com.elfmcys.yesstevemodel.client;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.model.*;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.C2SModelThumbnailsPacket;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.*;

/** Only visible page entries enter this cache. All texture ownership stays on the game thread. */
public final class ModelThumbnails {
    private static final LinkedHashMap<String,ResourceLocation> textures=new LinkedHashMap<>(16,.75f,true);
    private static final Set<String> pending=new HashSet<>();
    private static volatile List<String> visible=List.of();
    private static final Set<String> localGenerating=new HashSet<>();
    private static volatile long page;
    private static long nextRetry;
    private static final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"YSM thumbnail cache");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
    static { io.allowCoreThreadTimeOut(true); }
    public static void page(List<String> ids) {
        List<String> next=List.copyOf(ids);
        if(next.equals(visible)) return;
        visible=next;page++;pending.clear();localGenerating.clear();io.getQueue().clear();remoteSentPage=-1;nextRetry=0;
        if(NetworkHandler.supportsServerExtensions() && !ClientOnlyMode.isForced())
            NetworkHandler.sendToServer(new C2SModelThumbnailsPacket(page,List.of()));
        request();
    }
    public static void tick() { if(System.currentTimeMillis()>nextRetry) request(); }
    private static void request() {
        nextRetry=System.currentTimeMillis()+3000;
        remoteSentPage=-1;
        pending.clear();
        long token=page;
        for(String id:visible) {
            String hash=ClientModelManager.thumbnailHash(id);
            if(hash==null || textures.containsKey(hash) || localGenerating.contains(id) || !pending.add(id)) continue;
            io.execute(() -> {
                byte[] data=null;
                try {
                    data=ModelThumbnailService.readCache(ServerModelManager.CACHE_CLIENT,hash);
                } catch(Exception ignored) {}
                byte[] cached=data;
                Minecraft.getInstance().execute(() -> {
                    if(page!=token || !visible.contains(id)) return;
                    if(cached!=null && accept(hash,cached)) { pending.remove(id); if(NetworkHandler.supportsServerExtensions() && !ClientOnlyMode.isForced()) fetchRemote(token); return; }
                    pending.remove(id);
                    if(NetworkHandler.supportsServerExtensions() && !ClientOnlyMode.isForced() && !ClientModelManager.isPrivateModel(id)) {
                        // Requests are batched after all cache lookups for this page complete.
                        fetchRemote(token);
                    } else {
                        localGenerating.add(id);
                        ModelThumbnailService.request(hash,()->ClientModelManager.readThumbnailModel(id),
                                ()->page==token, png->Minecraft.getInstance().execute(()->receive(token,id,hash,png)));
                        if(NetworkHandler.supportsServerExtensions() && !ClientOnlyMode.isForced()) fetchRemote(token);
                    }
                });
            });
        }
    }
    private static long remoteSentPage=-1;
    private static void fetchRemote(long token) {
        // Allow one request per completed lookup batch, not one packet per model.
        if(remoteSentPage==token) return;
        for(String id:visible) if(pending.contains(id)) return;
        List<String> missing=visible.stream().filter(id->{String hash=ClientModelManager.thumbnailHash(id);return hash!=null&&!ClientModelManager.isPrivateModel(id)&&!textures.containsKey(hash);}).toList();
        remoteSentPage=token;
        NetworkHandler.sendToServer(new C2SModelThumbnailsPacket(token,missing));
    }
    public static ResourceLocation get(String id) {
        String hash=ClientModelManager.thumbnailHash(id);
        return hash==null ? null : textures.get(hash);
    }
    public static void receive(long token,String id,String hash,byte[] png) {
        if(token!=page || !visible.contains(id) || !Objects.equals(hash,ClientModelManager.thumbnailHash(id))) return;
        pending.remove(id);
        localGenerating.remove(id);
        if(!accept(hash,png)) { remoteSentPage=-1;return; }
        io.execute(()->{
            try {
                ModelThumbnailService.writeCache(ServerModelManager.CACHE_CLIENT,hash,png);
            } catch(Exception ex) { YesSteveModel.LOGGER.debug("Cannot cache thumbnail",ex); }
        });
    }
    private static boolean accept(String hash,byte[] png) {
        // Validate PNG dimensions before NativeImage allocates untrusted image memory.
        if(!ModelThumbnailRenderer.validHeader(png)) return false;
        if(textures.containsKey(hash)) return true;
        try {
            NativeImage image=NativeImage.read(png);
            ResourceLocation loc=Minecraft.getInstance().getTextureManager().register("ysm_thumb",new DynamicTexture(image));
            textures.put(hash,loc);
            while(textures.size()>128) {
                var it=textures.entrySet().iterator();var entry=it.next();
                Minecraft.getInstance().getTextureManager().release(entry.getValue());it.remove();
            }
            return true;
        } catch(Exception ex) { YesSteveModel.LOGGER.warn("Invalid model thumbnail",ex);return false; }
    }
    public static void reset() {
        page++;visible=List.of();pending.clear();localGenerating.clear();io.getQueue().clear();remoteSentPage=-1;
        Minecraft.getInstance().execute(()->{
            for(var loc:textures.values()) Minecraft.getInstance().getTextureManager().release(loc);
            textures.clear();
        });
    }
}
