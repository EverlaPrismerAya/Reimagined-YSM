package com.elfmcys.yesstevemodel.network.message;

import com.elfmcys.yesstevemodel.capability.AuthModelsCapability;
import com.elfmcys.yesstevemodel.capability.ModelInfoCapability;
import com.elfmcys.yesstevemodel.capability.StarModelsCapability;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.everla.reimaginedysm.api.network.PacketContext;

public class C2SVersionCheckPacket {

    private static final String MODEL_SYNC_FRAGMENT_BRAND = "reimagined_ysm:model_sync_fragments_v1";

    private final String version;
    private final boolean restart;
    private final boolean supportsModelSyncFragments;
    private boolean scripts=true;
    private boolean scriptSync=true;

    public C2SVersionCheckPacket() {
        this(NetworkHandler.VERSION, true);
    }

    public C2SVersionCheckPacket(String version) {
        this(version, false);
    }

    private C2SVersionCheckPacket(String version, boolean supportsModelSyncFragments) {
        this(version,supportsModelSyncFragments,false);
    }

    public C2SVersionCheckPacket(boolean restart) { this(NetworkHandler.VERSION,true,restart); }

    private C2SVersionCheckPacket(String version,boolean supportsModelSyncFragments,boolean restart) {
        this.version=version;this.supportsModelSyncFragments=supportsModelSyncFragments;this.restart=restart;
    }

    public boolean restartsSession() { return restart; }

    public static C2SVersionCheckPacket decode(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        boolean supportsModelSyncFragments = buf.readableBytes() > 0 && MODEL_SYNC_FRAGMENT_BRAND.equals(buf.readUtf());
        var packet=new C2SVersionCheckPacket(version, supportsModelSyncFragments,buf.isReadable() && buf.readBoolean());
        packet.scripts=buf.isReadable() && buf.readBoolean();
        packet.scriptSync=packet.scripts && buf.isReadable() && buf.readBoolean();
        return packet;
    }

    public static void encode(C2SVersionCheckPacket message, FriendlyByteBuf buf) {
        if (NetworkHandler.isOfficialServer()) {
            buf.writeUtf(NetworkHandler.OFFICIAL_VERSION);
            return;
        }
        buf.writeUtf(message.version);
        if (message.supportsModelSyncFragments) {
            buf.writeUtf(MODEL_SYNC_FRAGMENT_BRAND);
            buf.writeBoolean(message.restart);
            buf.writeBoolean(message.scripts);
            buf.writeBoolean(message.scriptSync);
        }
    }

    public static void handle(C2SVersionCheckPacket message, PacketContext ctx) {
        if (!NetworkHandler.VERSION.equals(message.version)) {
            ctx.enqueueWork(() -> ctx.getConnection().disconnect(net.minecraft.network.chat.Component.literal("Reimagined YSM protocol mismatch: expected " + NetworkHandler.VERSION + ", received " + message.version)));
            return;
        }
        ServerPlayer sender = ctx.getSender();
        if(sender==null)return;
        boolean first=NetworkHandler.setChannelVersion(ctx.getConnection(),message.version);
        if (first || (message.restart && NetworkHandler.isConnectionValid(ctx.getConnection()))) {
            ctx.enqueueWork(()-> {
            NetworkHandler.setClientSupportsModelSyncFragments(sender.getUUID(), message.supportsModelSyncFragments);
            net.everla.ysmJsLib.ScriptTransfer.capability(sender.getUUID(),ctx.getConnection(),message.scripts);
            net.everla.ysmJsLib.ScriptSync.capability(sender,ctx.getConnection(),message.scriptSync);
            ServerModelManager.validatePlayerModel(sender);
            ModelInfoCapability.get(sender).ifPresent(cap -> {
                cap.setMandatory(false);
                cap.stopAnimation(sender);
            });
            AuthModelsCapability.get(sender).ifPresent(cap -> {
                NetworkHandler.sendToClientPlayer(new S2CSyncAuthModelsPacket(cap.getAuthModels()), sender);
            });
            StarModelsCapability.get(sender).ifPresent(cap -> {
                NetworkHandler.sendToClientPlayer(new S2CSyncStarModelsPacket(cap.getStarModels()), sender);
            });
            ServerModelManager.requestPlayerAuth(sender, null);
            });
        }
    }
}