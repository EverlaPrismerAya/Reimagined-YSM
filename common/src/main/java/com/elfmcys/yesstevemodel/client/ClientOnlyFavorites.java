package com.elfmcys.yesstevemodel.client;

import com.elfmcys.yesstevemodel.capability.StarModelsCapability;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import net.minecraft.world.entity.player.Player;
import java.nio.file.*;
import java.util.*;

public final class ClientOnlyFavorites {
    private static StarModelsCapability local;
    public static Optional<StarModelsCapability> get(Player player) {
        if(!ClientOnlyMode.isActive())return StarModelsCapability.get(player);
        if(local==null) {
            local=new StarModelsCapability() {
                @Override public void addModel(String id) { super.addModel(id);save(); }
                @Override public void removeModel(String id) { super.removeModel(id);save(); }
            };
            try {
                Path file=file();if(Files.isRegularFile(file))local.setStarModels(new HashSet<>(Files.readAllLines(file)));
            } catch(Exception ex) { net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("Cannot read local favorites",ex); }
        }
        return Optional.of(local);
    }
    private static Path file() { return ServerModelManager.FOLDER.resolve("client_state/favorites.txt"); }
    private static void save() {
        try {
            Path file=file();Files.createDirectories(file.getParent());
            Path temp=Files.createTempFile(file.getParent(),"favorites-",".tmp");
            try { Files.write(temp,local.getStarModels().stream().sorted().toList());Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch(Exception ex) { net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("Cannot save local favorites",ex); }
    }
}
