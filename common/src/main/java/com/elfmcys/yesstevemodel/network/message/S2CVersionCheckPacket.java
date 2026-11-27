package com.elfmcys.yesstevemodel.network.message;

import com.elfmcys.yesstevemodel.client.ClientModelManager;
import com.elfmcys.yesstevemodel.client.ClientOnlyMode;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.everla.reimaginedysm.api.network.PacketContext;

public class S2CVersionCheckPacket {

    private static final String REIMAGINED_YSM_BRAND = "reimagined_ysm:v1";
    private static final String MODEL_SYNC_FRAGMENT_BRAND = "reimagined_ysm:model_sync_fragments_v1";

    private final String version;
    private final boolean supportsModelSyncFragments;

    public S2CVersionCheckPacket() {
        this(NetworkHandler.VERSION, true);
    }

    private S2CVersionCheckPacket(String version, boolean supportsModelSyncFragments) {
        this.version = version;
        this.supportsModelSyncFragments = supportsModelSyncFragments;
    }

    public static S2CVersionCheckPacket decode(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        boolean supportsModelSyncFragments = false;
        if(buf.readableBytes() > 0){
            String brand = buf.readUtf();
            if(brand.equals(REIMAGINED_YSM_BRAND)){
                ClientModelManager.setReimaginedYsmServer(true);
                ClientModelManager.setAllowUpload(buf.readBoolean());
            } else if (brand.equals(MODEL_SYNC_FRAGMENT_BRAND)) {
                supportsModelSyncFragments = true;
                if (buf.isReadable()) { ClientModelManager.setReimaginedYsmServer(true); ClientModelManager.setAllowUpload(buf.readBoolean()); }
            }
        }
        net.everla.ysmJsLib.ScriptTransfer.serverSupportsScripts=NetworkHandler.VERSION.equals(version) && buf.isReadable() && buf.readBoolean();
        net.everla.ysmJsLib.ScriptSync.supported=net.everla.ysmJsLib.ScriptTransfer.serverSupportsScripts && buf.isReadable() && buf.readBoolean();
        return new S2CVersionCheckPacket(version, supportsModelSyncFragments);
    }

    public static void encode(S2CVersionCheckPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.version);
        if (message.supportsModelSyncFragments) {
            buf.writeUtf(MODEL_SYNC_FRAGMENT_BRAND);
            buf.writeBoolean(true);
            buf.writeBoolean(true);
            buf.writeBoolean(true);
        }
    }

    public static void handle(S2CVersionCheckPacket message, PacketContext ctx) {
        if (!NetworkHandler.VERSION.equals(message.version) && !NetworkHandler.OFFICIAL_VERSION.equals(message.version)) {
            ctx.enqueueWork(() -> ctx.getConnection().disconnect(net.minecraft.network.chat.Component.literal("Reimagined YSM protocol mismatch: expected " + NetworkHandler.VERSION + ", received " + message.version)));
            return;
        }
        NetworkHandler.selectClientProtocol(ctx.getConnection());
        NetworkHandler.setServerSupportsModelSyncFragments(NetworkHandler.VERSION.equals(message.version) && message.supportsModelSyncFragments);
        boolean first = NetworkHandler.setChannelVersion(ctx.getConnection(), message.version);
        ctx.enqueueWork(() -> {
            ClientOnlyMode.leaveStandalone();
            if (first) {
                ClientModelManager.onSyncConnected();
                if (NetworkHandler.OFFICIAL_VERSION.equals(message.version)) {
                    Minecraft.getInstance().gui.getChat().addMessage(Component.translatable(
                            "message.yes_steve_model.official_environment").withStyle(ChatFormatting.YELLOW));
                }
            }
            NetworkHandler.markClientHandshakeComplete();
            NetworkHandler.sendVersionCheck(ctx.getConnection());
        });
    }
}
