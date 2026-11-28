package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.capability.ModelInfoCapability;
import com.elfmcys.yesstevemodel.config.CommonConfig;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.everla.reimaginedysm.api.network.PacketContext;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** The server relays declared values; it never executes model JavaScript. All state is server-thread confined. */
public final class ScriptSync {
    public static final int MAX_VALUES=64;
    public static volatile boolean supported, enabled;
    private static final Map<UUID,Connection> CAPABLE=new HashMap<>();
    private static final Map<UUID,State> STATES=new HashMap<>();
    private static final Map<UUID,Integer> LAST_TICK=new HashMap<>();
    private static final Map<String,Set<String>> DECLARATIONS=new HashMap<>();
    private static final Map<UUID,Map<UUID,ServerPlayer>> TRACKING=new HashMap<>();
    @dev.architectury.injectables.annotations.ExpectPlatform
    public static void register(){throw new AssertionError();}
    public static void track(ServerPlayer observer,net.minecraft.world.entity.Entity target,boolean tracking) {
        if(!(target instanceof ServerPlayer))return;
        if(tracking)TRACKING.computeIfAbsent(target.getUUID(),id->new HashMap<>()).put(observer.getUUID(),observer);
        else {
            var observers=TRACKING.get(target.getUUID());if(observers!=null){observers.remove(observer.getUUID());if(observers.isEmpty())TRACKING.remove(target.getUUID());}
            State state=STATES.get(target.getUUID());
            if(state!=null && state.recipients.remove(observer.getUUID())!=null && capable(observer))
                NetworkHandler.sendToClientPlayer(new Values(target.getUUID(),state.data.model,"","",Map.of()),observer);
        }
    }
    private static boolean lastPolicy=true;
    private static final class State {
        final Submit data;final Map<UUID,Connection> recipients=new HashMap<>();boolean dirty=true;
        State(Submit data){this.data=data;}
    }
    public static boolean serverEnabled(){return CommonConfig.RYSM_JS_SYNC_ENABLED!=null && CommonConfig.RYSM_JS_SYNC_ENABLED.get();}
    public static void capability(ServerPlayer player,Connection connection,boolean capable) {
        CAPABLE.remove(player.getUUID());STATES.remove(player.getUUID());LAST_TICK.remove(player.getUUID());
        if(capable){CAPABLE.put(player.getUUID(),connection);NetworkHandler.sendToClientPlayer(new Policy(serverEnabled()),player);}
    }
    public static void forget(UUID id){
        State state=STATES.remove(id);
        if(state!=null)for(var observer:TRACKING.getOrDefault(id,Map.of()).values())if(capable(observer))
            NetworkHandler.sendToClientPlayer(new Values(id,state.data.model,"","",Map.of()),observer);
        CAPABLE.remove(id);LAST_TICK.remove(id);TRACKING.remove(id);TRACKING.values().forEach(s->s.remove(id));STATES.values().forEach(s->s.recipients.remove(id));
    }
    public static void clearServer(){CAPABLE.clear();STATES.clear();LAST_TICK.clear();DECLARATIONS.clear();TRACKING.clear();}
    private static boolean capable(ServerPlayer player) {
        return CAPABLE.get(player.getUUID())==((net.everla.reimaginedysm.mixin.ServerCommonPacketListenerImplAccessor)player.connection).ysm$getConnection()
            && NetworkHandler.isPlayerConnected(player);
    }
    private static String modelHash(ServerPlayer player,String model) {
        if(!ModelInfoCapability.get(player).map(c->model.equals(c.getModelId())).orElse(false))return "";
        return ServerModelManager.getModelDefinition(model).map(m->m.getLoadedModelData().getModelHash()).orElse("");
    }
    public static boolean validValues(Map<String,Double> values,Set<String> declared) {
        return values.size()<=MAX_VALUES && values.keySet().equals(declared)
            && values.entrySet().stream().allMatch(e->e.getKey().matches("[a-z_][a-z0-9_]{0,255}") && e.getValue()!=null && Double.isFinite(e.getValue()));
    }
    private static void accept(ServerPlayer player,Submit data) {
        if(!serverEnabled() || !capable(player) || ServerModelManager.shouldHideModelsFrom(player.getUUID()))return;
        int now=player.server.getTickCount();
        if(Objects.equals(LAST_TICK.put(player.getUUID(),now),now))return;
        if(!data.hash.equals(modelHash(player,data.model)))return;
        ScriptBundle bundle=ScriptResources.get(data.hash);
        if(bundle==null || !bundle.digest.equals(data.script))return;
        Set<String> declared;
        try {
            if(DECLARATIONS.size()>256)DECLARATIONS.clear();
            declared=DECLARATIONS.computeIfAbsent(bundle.digest,key->{
                Set<String> names=new HashSet<>();ScriptDeclarations.read(bundle).forEach((name,entry)->{if(entry.sync())names.add(name);});return Set.copyOf(names);
            });
        }catch(RuntimeException e){return;}
        if(!validValues(data.values,declared))return;
        State previous=STATES.get(player.getUUID());
        if(previous!=null && previous.data.equals(data))return;
        State next=new State(data);
        if(previous!=null)next.recipients.putAll(previous.recipients);
        STATES.put(player.getUUID(),next);
    }
    public static void tick(MinecraftServer server) {
        boolean policy=serverEnabled();
        for(var player:server.getPlayerList().getPlayers())if(capable(player) && policy!=lastPolicy)NetworkHandler.sendToClientPlayer(new Policy(policy),player);
        lastPolicy=policy;
        if(!policy){STATES.clear();return;}
        var iterator=STATES.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var owner=server.getPlayerList().getPlayer(entry.getKey());State state=entry.getValue();
            if(owner==null || !capable(owner) || !state.data.hash.equals(modelHash(owner,state.data.model))) {
                if(owner!=null)for(var id:state.recipients.keySet()){var recipient=server.getPlayerList().getPlayer(id);if(recipient!=null&&capable(recipient))NetworkHandler.sendToClientPlayer(new Values(entry.getKey(),state.data.model,"","",Map.of()),recipient);}
                iterator.remove();continue;
            }
            Set<UUID> watching=new HashSet<>();
            for(var recipient:TRACKING.getOrDefault(owner.getUUID(),Map.of()).values()) {
                if(recipient==owner || recipient.level()!=owner.level() || !capable(recipient) || ServerModelManager.shouldHideModelsFrom(recipient.getUUID()))continue;
                watching.add(recipient.getUUID());Connection connection=CAPABLE.get(recipient.getUUID());
                if(state.dirty || state.recipients.get(recipient.getUUID())!=connection)
                    NetworkHandler.sendToClientPlayer(new Values(owner.getUUID(),state.data.model,state.data.hash,state.data.script,state.data.values),recipient);
                state.recipients.put(recipient.getUUID(),connection);
            }
            state.recipients.keySet().retainAll(watching);state.dirty=false;
        }
    }
    private static void write(FriendlyByteBuf b,String model,String hash,String script,Map<String,Double> values) {
        b.writeUtf(model,256);b.writeUtf(hash,64);b.writeUtf(script,64);b.writeVarInt(values.size());
        values.forEach((name,value)->{b.writeUtf(name,256);b.writeDouble(value);});
    }
    private static Submit read(FriendlyByteBuf b) {
        String model=b.readUtf(256),hash=b.readUtf(64),script=b.readUtf(64);int count=b.readVarInt();
        if(count<0||count>MAX_VALUES)throw new IllegalArgumentException("Too many synchronized values");
        Map<String,Double> values=new LinkedHashMap<>();
        for(int i=0;i<count;i++){String name=b.readUtf(256);double value=b.readDouble();if(!Double.isFinite(value)||values.putIfAbsent(name,value)!=null)throw new IllegalArgumentException("Invalid synchronized value");}
        return new Submit(model,hash,script,Map.copyOf(values));
    }
    public record Submit(String model,String hash,String script,Map<String,Double> values) {
        public Submit{values=Map.copyOf(values);}
        public static void encode(Submit p,FriendlyByteBuf b){write(b,p.model,p.hash,p.script,p.values);}
        public static Submit decode(FriendlyByteBuf b){return read(b);}
        public static void handle(Submit p,PacketContext c){if(c.isServerSide()&&c.getSender()!=null)c.enqueueWork(()->accept(c.getSender(),p));}
    }
    public record Values(UUID player,String model,String hash,String script,Map<String,Double> values) {
        public static void encode(Values p,FriendlyByteBuf b){b.writeUUID(p.player);write(b,p.model,p.hash,p.script,p.values);}
        public static Values decode(FriendlyByteBuf b){UUID id=b.readUUID();Submit p=read(b);return new Values(id,p.model,p.hash,p.script,p.values);}
        public static void handle(Values p,PacketContext c){if(c.isClientSide())c.enqueueWork(()->Client.receive(p));}
    }
    public record Policy(boolean enabled) {
        public static void encode(Policy p,FriendlyByteBuf b){b.writeBoolean(p.enabled);}
        public static Policy decode(FriendlyByteBuf b){return new Policy(b.readBoolean());}
        public static void handle(Policy p,PacketContext c){if(c.isClientSide())c.enqueueWork(()->{ScriptSync.enabled=p.enabled;Client.clear();});}
    }
    /** Kept separate so dedicated servers never resolve client runtime classes. */
    public static final class Client {
        private static final Map<UUID,Values> REMOTE=new HashMap<>();
        private static Submit sent;
        private static long sentTick=Long.MIN_VALUE;
        public static void clearLocal(){sent=null;}
        public static void clear(){REMOTE.clear();clearLocal();sentTick=Long.MIN_VALUE;}
        static void receive(Values data){if(!supported||!enabled)return;if(data.hash.isEmpty())REMOTE.remove(data.player);else if(REMOTE.size()<1024||REMOTE.containsKey(data.player))REMOTE.put(data.player,data);}
        public static double value(UUID player,String model,String hash,String script,String name,double fallback) {
            Values data=REMOTE.get(player);
            return supported&&enabled&&data!=null&&data.model.equals(model)&&data.hash.equals(hash)&&data.script.equals(script)?data.values.getOrDefault(name,fallback):fallback;
        }
        public static void send(String model,String hash,String script,Map<String,Double> values) {
            if(!supported||!enabled||!NetworkHandler.isClientConnected()||com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive()||com.elfmcys.yesstevemodel.client.ClientModelManager.isPrivateSelection())return;
            Submit data=new Submit(model,hash,script,values);
            if(data.equals(sent)||sentTick==ScriptInput.tick)return;
            NetworkHandler.sendToServer(data);sent=data;sentTick=ScriptInput.tick;
        }
    }
}
