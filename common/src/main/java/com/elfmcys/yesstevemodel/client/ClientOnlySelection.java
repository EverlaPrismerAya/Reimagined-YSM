package com.elfmcys.yesstevemodel.client;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public final class ClientOnlySelection {

    private static Path FILE = ServerModelManager.FOLDER.resolve("client_selection.json");
    private static boolean localScope;
    private static synchronized void scope() {
        boolean next=ClientOnlyMode.isActive();
        if(next==localScope)return;
        localScope=next;loaded=false;modelId=null;textureId=null;
        FILE=ServerModelManager.FOLDER.resolve(next?"client_state/selection.json":"client_selection.json");
    }
    private static final Gson GSON = new Gson();

    private static volatile String modelId;
    private static volatile String textureId;
    private static volatile boolean loaded;

    private ClientOnlySelection() {
    }

    public static synchronized void save(String model, String texture) {
        scope();
        modelId = model;
        textureId = texture;
        loaded = true;
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("model_id", model);
            json.addProperty("texture_id", texture);
            Files.write(FILE, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to save client-only model selection", e);
        }
    }

    public static synchronized void clear() {
        scope();
        modelId = null;
        textureId = null;
        loaded = true;
        try { Files.deleteIfExists(FILE); }
        catch (Exception e) { YesSteveModel.LOGGER.error("Failed to clear client-only model selection", e); }
    }

    private static void load() {
        scope();
        if (loaded) return;
        loadFromDisk();
    }

    private static synchronized void loadFromDisk() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JsonObject json = GSON.fromJson(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) return;
            if (json.has("model_id")) modelId = json.get("model_id").getAsString();
            if (json.has("texture_id")) textureId = json.get("texture_id").getAsString();
        } catch (Exception e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to read client-only model selection", e);
        }
    }

    public static String getModelId() {
        load();
        return modelId;
    }

    public static String getTextureId() {
        load();
        return textureId;
    }

    public static boolean hasSelection() {
        load();
        return modelId != null && textureId != null;
    }
}
