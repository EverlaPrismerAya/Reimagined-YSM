package com.elfmcys.yesstevemodel.client.texture;

import net.everla.reimaginedysm.compat.oculus.ShadersTextureType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

public class OuterFileTexture extends AbstractTexture implements ITextureMap {
    private final byte[] data;
    private final int[] pixels;
    private final int pixelWidth;
    private final int pixelHeight;

    private Map<ShadersTextureType, OuterFileTexture> suffixTextures = Reference2ReferenceMaps.emptyMap();

    public OuterFileTexture(byte[] data) {
        this.data = data;
        this.pixels = null;
        this.pixelWidth = this.pixelHeight = 0;
    }

    public OuterFileTexture(BufferedImage image) {
        this.data = null;
        this.pixelWidth = image.getWidth();
        this.pixelHeight = image.getHeight();
        this.pixels = image.getRGB(0, 0, pixelWidth, pixelHeight, null, 0, pixelWidth);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            // NativeImage packs RGBA as ABGR; preserve alpha and swap red/blue.
            pixels[i] = (argb & 0xff00ff00) | ((argb >>> 16) & 0xff) | ((argb & 0xff) << 16);
        }
    }

    private NativeImage readImage() throws IOException {
        if (pixels == null) return NativeImage.read(new ByteArrayInputStream(data));
        NativeImage image = new NativeImage(pixelWidth, pixelHeight, false);
        try {
            for (int y = 0; y < pixelHeight; y++) {
                for (int x = 0; x < pixelWidth; x++) image.setPixelRGBA(x, y, pixels[y * pixelWidth + x]);
            }
            return image;
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
    }

    @Override
    public void load(@NotNull ResourceManager resourceManager) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::doLoad);
        } else {
            doLoad();
        }
    }

    public void doLoad() {
        try (NativeImage imageIn = readImage()) {
            int width = imageIn.getWidth();
            int height = imageIn.getHeight();
            TextureUtil.prepareImage(this.getId(), 0, width, height);
            imageIn.upload(0, 0, 0, 0, 0, width, height, false, true, false, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setSuffixTextures(Map<ShadersTextureType, OuterFileTexture> map) {
        this.suffixTextures = Reference2ReferenceMaps.unmodifiable(new Reference2ReferenceOpenHashMap<>(map));
    }

    public Map<ShadersTextureType, ? extends AbstractTexture> getSuffixTextures() {
        return this.suffixTextures;
    }
}
