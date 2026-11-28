package net.everla.ysmJsLib;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.everla.reimaginedysm.api.network.PacketContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.io.*;

/** Extension packets are sent only after explicit capability opt-in. */
public final class ScriptTransfer {
    private static final Map<UUID,net.minecraft.network.Connection> CAPABLE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,ByteArrayOutputStream> RECEIVING=new HashMap<>();
    public static void capability(UUID player,net.minecraft.network.Connection connection,boolean enabled){if(enabled)CAPABLE.put(player,connection);else CAPABLE.remove(player);}
    public static volatile boolean serverSupportsScripts;
    private static final Map<String,java.util.concurrent.CompletableFuture<Boolean>> WAITING=new java.util.concurrent.ConcurrentHashMap<>();
    public static void clear(){RECEIVING.clear();WAITING.values().forEach(f->f.complete(false));WAITING.clear();serverSupportsScripts=false;}
    public record Request(String hash) {
        public static void encode(Request p,FriendlyByteBuf b){b.writeUtf(p.hash,64);}
        public static Request decode(FriendlyByteBuf b){return new Request(b.readUtf(64));}
        public static void handle(Request p,PacketContext c){if(!c.isClientSide() && p.hash.matches("[a-fA-F0-9]{64}"))c.enqueueWork(()->com.elfmcys.yesstevemodel.model.ServerModelManager.requestScriptExtension(c.getSender(),p.hash));}
    }
    public static void ensure(String hash) {
        if(!serverSupportsScripts || ScriptResources.get(hash)!=null || net.minecraft.client.Minecraft.getInstance().isSameThread())return;
        var future=WAITING.computeIfAbsent(hash,h->{
            var f=new java.util.concurrent.CompletableFuture<Boolean>();
            net.minecraft.client.Minecraft.getInstance().execute(()->NetworkHandler.sendToServer(new Request(h)));return f;
        });
        try{future.get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Extension unavailable for {}; loading base model",hash);}
    }
    public record Chunk(String hash,int total,int offset,byte[] data) {
        public static void encode(Chunk p,FriendlyByteBuf b){b.writeUtf(p.hash,64);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeByteArray(p.data);}
        public static Chunk decode(FriendlyByteBuf b){return new Chunk(b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(24576));}
        public static void handle(Chunk p,PacketContext c){if(c.isClientSide())c.enqueueWork(()->receive(p));}
    }
    public static void send(ServerPlayer player,String hash) throws IOException {
        var connection=CAPABLE.get(player.getUUID());if(connection==null||!connection.isConnected()||connection!=((net.everla.reimaginedysm.mixin.ServerCommonPacketListenerImplAccessor)player.connection).ysm$getConnection())return;
        ScriptBundle bundle=ScriptResources.get(hash);if(bundle==null){NetworkHandler.sendToClientPlayer(new Chunk(hash,0,0,new byte[0]),player);return;}byte[] archive=bundle.archive();
        for(int offset=0;offset<archive.length;offset+=24576) {
            NetworkHandler.sendToClientPlayer(new Chunk(hash,archive.length,offset,Arrays.copyOfRange(archive,offset,Math.min(offset+24576,archive.length))),player);
        }
    }
    private static void receive(Chunk p) {
        try {
            if(p.hash.matches("[a-fA-F0-9]{64}") && p.total==0 && p.offset==0 && p.data.length==0){WAITING.computeIfAbsent(p.hash,h->new java.util.concurrent.CompletableFuture<>()).complete(false);return;}
            if(!p.hash.matches("[a-fA-F0-9]{64}")||p.total<1||p.total>ScriptBundle.MAX_BYTES||p.offset<0||p.offset>p.total-p.data.length)throw new IOException("Invalid extension chunk");
            if(p.offset==0){if(RECEIVING.size()>=4)throw new IOException("Too many extension transfers");RECEIVING.put(p.hash,new ByteArrayOutputStream());}
            var out=RECEIVING.get(p.hash);if(out==null||out.size()!=p.offset)throw new IOException("Out-of-order extension chunk");
            out.write(p.data);
            if(out.size()==p.total){RECEIVING.remove(p.hash);ScriptResources.install(p.hash,ScriptBundle.read(out.toByteArray()));WAITING.computeIfAbsent(p.hash,h->new java.util.concurrent.CompletableFuture<>()).complete(true);}
        }catch(Exception e){RECEIVING.remove(p.hash);org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Rejected network extension",e);}
    }
}
