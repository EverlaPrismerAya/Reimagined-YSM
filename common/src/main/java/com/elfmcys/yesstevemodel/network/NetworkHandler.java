package com.elfmcys.yesstevemodel.network;

import com.elfmcys.yesstevemodel.RuntimePolicy;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.client.ClientModelManager;
import com.elfmcys.yesstevemodel.mixin.ConnectionAccessor;
import com.elfmcys.yesstevemodel.mixin.ServerCommonPacketListenerImplAccessor;
import com.elfmcys.yesstevemodel.network.message.*;
import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import net.everla.reimaginedysm.api.network.PacketDirection;
import net.everla.reimaginedysm.api.network.YSMChannel;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkHandler {

    private static final Set<UUID> clientsSupportingModelSyncFragments = ConcurrentHashMap.newKeySet();

    public static final String VERSION = "2.7.1";
    public static final String OFFICIAL_VERSION = "2.6.0";
    public static final ResourceLocation OFFICIAL_CHANNEL_ID = new ResourceLocation(YesSteveModel.MOD_ID, "2_6_0");
    private static volatile Connection clientProtocolConnection;

    public static boolean isOfficialServer() { return isOfficialServer(clientProtocolConnection); }

    public static boolean isOfficialServer(@Nullable Connection connection) {
        return connection != null && ((ConnectionAccessor) connection).ysm$getChannel() != null
                && OFFICIAL_VERSION.equals(((ConnectionAccessor) connection).ysm$getChannel().attr(CHANNEL_VERSION_KEY).get());
    }

    public static void selectClientProtocol(Connection connection) { clientProtocolConnection = connection; }

    public static boolean supportsServerExtensions() { return isClientConnected() && !isOfficialServer(); }

    public static boolean isOfficialPacket(int id) {
        return (id >= 1 && id <= 9) || (id >= 15 && id <= 19) || (id >= 21 && id <= 23) || id == 51 || id == 52;
    }

    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(YesSteveModel.MOD_ID, VERSION.replace('.', '_'));

    private static final AttributeKey<String> CHANNEL_VERSION_KEY = AttributeKey.valueOf("yes_steve_model_channel_version");

    private static volatile boolean clientHandshakeComplete = false;
    private static volatile boolean serverSupportsModelSyncFragments = false;

    public static boolean setChannelVersion(Connection connection, String str) {
        return ((ConnectionAccessor) connection).ysm$getChannel().attr(CHANNEL_VERSION_KEY).compareAndSet(null, str);
    }

    public static void markClientHandshakeComplete() {
        clientHandshakeComplete = true;
    }

    public static void resetClientHandshake() {
        clientHandshakeComplete = false;
        clientProtocolConnection = null;
        serverSupportsModelSyncFragments = false;
        net.everla.ysmJsLib.ScriptTransfer.clear();
        net.everla.ysmJsLib.ScriptSync.supported=false;net.everla.ysmJsLib.ScriptSync.enabled=false;net.everla.ysmJsLib.ScriptSync.Client.clear();
    }

    public static void setServerSupportsModelSyncFragments(boolean supported) {
        serverSupportsModelSyncFragments = supported;
    }

    public static boolean serverSupportsModelSyncFragments() {
        return serverSupportsModelSyncFragments;
    }

    public static boolean isPlayerConnected(ServerPlayer serverPlayer) {
        if (RuntimePolicy.isDisabled()) {
            return false;
        }
        return serverPlayer.connection != null && isConnectionValid(((ServerCommonPacketListenerImplAccessor) serverPlayer.connection).ysm$getConnection());
    }

    public static boolean isClientConnected() {
        if(com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive())return false;
        if (RuntimePolicy.isDisabled()) {
            return false;
        }
        if (clientHandshakeComplete) {
            return true;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        return isConnectionValid(connection.getConnection());
    }

    public static boolean isConnectionValid(@Nullable Connection connection) {
        if (RuntimePolicy.isDisabled()) {
            return false;
        }
        return connection != null && ((ConnectionAccessor) connection).ysm$getChannel() != null && (VERSION.equals(((ConnectionAccessor) connection).ysm$getChannel().attr(CHANNEL_VERSION_KEY).get())
                || connection == clientProtocolConnection && isOfficialServer(connection));
    }

    private static <T> void register(int id, Class<T> type, java.util.function.BiConsumer<T,net.minecraft.network.FriendlyByteBuf> encode,
            java.util.function.Function<net.minecraft.network.FriendlyByteBuf,T> decode,java.util.function.BiConsumer<T,net.everla.reimaginedysm.api.network.PacketContext> handler,PacketDirection direction) {
        YSMChannel.register(id,type,encode,decode,(packet,context)-> {
            if(context.isClientSide() && com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive()
                    && (!(packet instanceof S2CVersionCheckPacket) || com.elfmcys.yesstevemodel.client.ClientOnlyMode.isForced())) return;
            handler.accept(packet,context);
        },direction);
    }

    public static void init() {
        if (RuntimePolicy.isDisabled()) {
            return;
        }
        YSMChannel.init(CHANNEL_ID, VERSION);
        register(85, net.everla.ysmJsLib.ScriptSync.Submit.class, net.everla.ysmJsLib.ScriptSync.Submit::encode, net.everla.ysmJsLib.ScriptSync.Submit::decode, net.everla.ysmJsLib.ScriptSync.Submit::handle, PacketDirection.PLAY_TO_SERVER);
        register(86, net.everla.ysmJsLib.ScriptSync.Values.class, net.everla.ysmJsLib.ScriptSync.Values::encode, net.everla.ysmJsLib.ScriptSync.Values::decode, net.everla.ysmJsLib.ScriptSync.Values::handle, PacketDirection.PLAY_TO_CLIENT);
        register(87, net.everla.ysmJsLib.ScriptSync.Policy.class, net.everla.ysmJsLib.ScriptSync.Policy::encode, net.everla.ysmJsLib.ScriptSync.Policy::decode, net.everla.ysmJsLib.ScriptSync.Policy::handle, PacketDirection.PLAY_TO_CLIENT);
        register(84, net.everla.ysmJsLib.ScriptTransfer.Request.class, net.everla.ysmJsLib.ScriptTransfer.Request::encode, net.everla.ysmJsLib.ScriptTransfer.Request::decode, net.everla.ysmJsLib.ScriptTransfer.Request::handle, PacketDirection.PLAY_TO_SERVER);
        register(83, net.everla.ysmJsLib.ScriptTransfer.Chunk.class, net.everla.ysmJsLib.ScriptTransfer.Chunk::encode, net.everla.ysmJsLib.ScriptTransfer.Chunk::decode, net.everla.ysmJsLib.ScriptTransfer.Chunk::handle, PacketDirection.PLAY_TO_CLIENT);
        register(81, C2SServerConfigPacket.class, C2SServerConfigPacket::encode, C2SServerConfigPacket::decode, C2SServerConfigPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(82, S2CServerConfigPacket.class, S2CServerConfigPacket::encode, S2CServerConfigPacket::decode, S2CServerConfigPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(80, S2CCommonPolicyPacket.class, S2CCommonPolicyPacket::encode, S2CCommonPolicyPacket::decode, S2CCommonPolicyPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(79, S2CModelRequestCancelledPacket.class, S2CModelRequestCancelledPacket::encode, S2CModelRequestCancelledPacket::decode, S2CModelRequestCancelledPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(76, S2CLoadingModePacket.class, S2CLoadingModePacket::encode, S2CLoadingModePacket::decode, S2CLoadingModePacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(77, C2SModelThumbnailsPacket.class, C2SModelThumbnailsPacket::encode, C2SModelThumbnailsPacket::decode, C2SModelThumbnailsPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(78, S2CModelThumbnailPacket.class, S2CModelThumbnailPacket::encode, S2CModelThumbnailPacket::decode, S2CModelThumbnailPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(1, S2CModelSyncPayload.class, S2CModelSyncPayload::encode, S2CModelSyncPayload::decode, S2CModelSyncPayload::handle, PacketDirection.PLAY_TO_CLIENT);
        register(2, C2SModelSyncPayload.class, C2SModelSyncPayload::encode, C2SModelSyncPayload::decode, C2SModelSyncPayload::handle, PacketDirection.PLAY_TO_SERVER);
        register(3, S2CExecuteMolangPacket.class, S2CExecuteMolangPacket::encode, S2CExecuteMolangPacket::decode, S2CExecuteMolangPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(4, S2CSetModelAndTexturePacket.class, S2CSetModelAndTexturePacket::encode, S2CSetModelAndTexturePacket::decode, S2CSetModelAndTexturePacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(5, C2SRequestSwitchModelPacket.class, C2SRequestSwitchModelPacket::encode, C2SRequestSwitchModelPacket::decode, C2SRequestSwitchModelPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(6, S2CSyncAuthModelsPacket.class, S2CSyncAuthModelsPacket::encode, S2CSyncAuthModelsPacket::decode, S2CSyncAuthModelsPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(7, C2SPlayAnimationPacket.class, C2SPlayAnimationPacket::encode, C2SPlayAnimationPacket::decode, C2SPlayAnimationPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(8, S2CSyncStarModelsPacket.class, S2CSyncStarModelsPacket::encode, S2CSyncStarModelsPacket::decode, S2CSyncStarModelsPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(9, C2SSetStarModelPacket.class, C2SSetStarModelPacket::encode, C2SSetStarModelPacket::decode, C2SSetStarModelPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(15, C2SCompleteFeedbackPacket.class, C2SCompleteFeedbackPacket::encode, C2SCompleteFeedbackPacket::decode, C2SCompleteFeedbackPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(16, S2CSyncProjectileModelPacket.class, S2CSyncProjectileModelPacket::encode, S2CSyncProjectileModelPacket::decode, S2CSyncProjectileModelPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(17, C2SRequestExecuteMolangPacket.class, C2SRequestExecuteMolangPacket::encode, C2SRequestExecuteMolangPacket::decode, C2SRequestExecuteMolangPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(18, C2SSyncAnimationExpressionPacket.class, C2SSyncAnimationExpressionPacket::encode, C2SSyncAnimationExpressionPacket::decode, C2SSyncAnimationExpressionPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(19, S2CSyncAnimationExpressionPacket.class, S2CSyncAnimationExpressionPacket::encode, S2CSyncAnimationExpressionPacket::decode, S2CSyncAnimationExpressionPacket::handleCapability, PacketDirection.PLAY_TO_CLIENT);
        register(21, S2CSyncPlayerStatePacket.class, S2CSyncPlayerStatePacket::encode, S2CSyncPlayerStatePacket::decode, S2CSyncPlayerStatePacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(22, S2CSyncVehicleModelPacket.class, S2CSyncVehicleModelPacket::encode, S2CSyncVehicleModelPacket::decode, S2CSyncVehicleModelPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(23, C2SSwingArmPacket.class, C2SSwingArmPacket::encode, C2SSwingArmPacket::decode, C2SSwingArmPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(51, S2CVersionCheckPacket.class, S2CVersionCheckPacket::encode, S2CVersionCheckPacket::decode, S2CVersionCheckPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(52, C2SVersionCheckPacket.class, C2SVersionCheckPacket::encode, C2SVersionCheckPacket::decode, C2SVersionCheckPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(70, C2SModelUploadStartPacket.class, C2SModelUploadStartPacket::encode, C2SModelUploadStartPacket::decode, C2SModelUploadStartPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(71, S2CModelUploadStartPacket.class, S2CModelUploadStartPacket::encode, S2CModelUploadStartPacket::decode, S2CModelUploadStartPacket::handle, PacketDirection.PLAY_TO_CLIENT);
        register(72, C2SModelUploadChunkPacket.class, C2SModelUploadChunkPacket::encode, C2SModelUploadChunkPacket::decode, C2SModelUploadChunkPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(73, C2SModelUploadFinishPacket.class, C2SModelUploadFinishPacket::encode, C2SModelUploadFinishPacket::decode, C2SModelUploadFinishPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(75, C2SUsePrivateModelPacket.class, C2SUsePrivateModelPacket::encode, C2SUsePrivateModelPacket::decode, C2SUsePrivateModelPacket::handle, PacketDirection.PLAY_TO_SERVER);
        register(74, S2CModelUploadResultPacket.class, S2CModelUploadResultPacket::encode, S2CModelUploadResultPacket::decode, S2CModelUploadResultPacket::handle, PacketDirection.PLAY_TO_CLIENT);
    }

    public static void sendToServer(Object obj) {
        if(com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive())return;
        if (obj instanceof C2SRequestSwitchModelPacket selection) {
            if (ClientModelManager.isPrivateModel(selection.getModelId())) {
                ClientModelManager.selectPrivateModel(selection.getModelId(), selection.getTextureId());
                return;
            }
            ClientModelManager.clearPrivateSelection();
        }
        if (obj instanceof C2SSetStarModelPacket star && ClientModelManager.isPrivateModel(star.getModelId())) return;
        if (ClientModelManager.isPrivateSelection()) {
            int localId = Minecraft.getInstance().player == null ? -1 : Minecraft.getInstance().player.getId();
            if (isPrivateModelStatePacket(obj, localId)) return;
        }
        if (isOfficialServer() && obj instanceof C2SUsePrivateModelPacket) {
            obj = new C2SRequestSwitchModelPacket("default", "default");
        }
        if (isClientConnected()) {
            YSMChannel.sendToServer(obj);
        }
    }

    /** Model state targeting the local player must not describe a private model to the server. */
    public static boolean isPrivateModelStatePacket(Object packet, int localId) {
        return (packet instanceof C2SCompleteFeedbackPacket feedback && feedback.feedbackData().flags() == localId)
                || packet instanceof C2SSyncAnimationExpressionPacket
                || (packet instanceof C2SPlayAnimationPacket animation && (animation.getEntityId() == -1 || animation.getEntityId() == localId))
                || (packet instanceof C2SRequestExecuteMolangPacket expression && expression.getEntityId() == localId);
    }

    /** Explicitly rebuild the model session after local-only mode without replacing the game connection. */
    public static void restartModelSync(Connection connection) {
        if(!com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive() && connection!=null && connection.isConnected()) {
            if (isOfficialServer(connection)) {
                selectClientProtocol(connection);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(
                            net.minecraft.network.chat.Component.translatable("message.yes_steve_model.official_reconnect"));
                });
                return;
            }
            connection.send(toServerboundPacket(new C2SVersionCheckPacket(true)));
        }
    }

    public static void sendVersionCheck(Connection connection) {
        if(com.elfmcys.yesstevemodel.client.ClientOnlyMode.isActive())return;
        if (connection != null && connection.isConnected()) {
            connection.send(toServerboundPacket(new C2SVersionCheckPacket()));
        }
    }

    public static void sendToClientPlayer(Object obj, Player player) {
        YSMChannel.sendToClientPlayer(obj, (ServerPlayer) player);
    }

    public static void sendToAll(Object obj) {
        YSMChannel.sendToAll(obj);
    }

    public static void sendToTrackingEntity(Object obj, Entity entity) {
        YSMChannel.sendToTrackingEntity(obj, entity);
    }

    public static void sendToTrackingEntityAndSelf(Object obj, Player player) {
        YSMChannel.sendToTrackingEntityAndSelf(obj, player);
    }

    public static Packet<?> toClientboundPacket(Object obj) {
        return YSMChannel.toClientboundPacket(obj);
    }

    public static List<Packet<?>> toClientboundPackets(Object obj, UUID receiver) {
        if (!clientsSupportingModelSyncFragments.contains(receiver)) {
            return List.of(YSMChannel.toClientboundPacket(obj));
        }
        return YSMChannel.toClientboundPackets(obj);
    }

    public static void setClientSupportsModelSyncFragments(UUID uuid, boolean supported) {
        if (supported) {
            clientsSupportingModelSyncFragments.add(uuid);
        } else {
            clientsSupportingModelSyncFragments.remove(uuid);
        }
    }

    public static void clearClientModelSyncFragments(UUID uuid) {
        clientsSupportingModelSyncFragments.remove(uuid);
    }

    public static Packet<?> toServerboundPacket(Object obj) {
        return YSMChannel.toServerboundPacket(obj);
    }
}
