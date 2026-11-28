package com.elfmcys.yesstevemodel.resource;

import net.everla.reimaginedysm.core.NativeLibLoader;
import com.elfmcys.yesstevemodel.audio.AudioCodec;
import com.elfmcys.yesstevemodel.audio.AudioTrackData;
import com.elfmcys.yesstevemodel.client.ClientModelInfo;
import net.everla.reimaginedysm.compat.oculus.ShadersTextureType;
import com.elfmcys.yesstevemodel.client.gui.custom.AbstractConfig;
import com.elfmcys.yesstevemodel.client.gui.custom.ExtraAnimationButtons;
import com.elfmcys.yesstevemodel.client.gui.custom.configs.CheckboxConfig;
import com.elfmcys.yesstevemodel.client.gui.custom.configs.RadioConfig;
import com.elfmcys.yesstevemodel.client.gui.custom.configs.RangeConfig;
import com.elfmcys.yesstevemodel.client.model.MainModelData;
import com.elfmcys.yesstevemodel.client.texture.OuterFileTexture;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.AnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.AnimationState;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType;
import com.elfmcys.yesstevemodel.geckolib3.core.event.ParticleEventKeyFrame;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.BoneAnimation;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.bone.BoneKeyFrameProcessor;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.bone.EasingType;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.bone.RawBoneKeyFrame;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.bone.Vector3v;
import com.elfmcys.yesstevemodel.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.value.FloatValue;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.value.IValue;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.MolangParser;
import com.elfmcys.yesstevemodel.geckolib3.file.*;
import com.elfmcys.yesstevemodel.geckolib3.resource.GeckoLibCache;
import com.elfmcys.yesstevemodel.geckolib3.util.IInterpolable;
import com.elfmcys.yesstevemodel.geckolib3.util.LinearKeyframeInterpolator;
import com.elfmcys.yesstevemodel.geckolib3.util.TicksInterpolator;
import com.elfmcys.yesstevemodel.model.format.ServerModelInfo;
import com.elfmcys.yesstevemodel.resource.models.*;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import com.elfmcys.yesstevemodel.util.data.OrderedStringMap;
import com.elfmcys.yesstevemodel.util.data.StringMapPair;
import com.elfmcys.yesstevemodel.util.data.StringPair;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.apache.commons.lang3.tuple.Pair;
import org.gagravarr.ogg.OggFile;
import org.gagravarr.ogg.OggPacketReader;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.vorbis.VorbisFile;
import rip.ysm.imagestream.avif.AvifDecoder;
import rip.ysm.imagestream.webp.WebpDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;

public class YSMClientMapper {
    private static final OrderedStringMap<String, String> DEFAULT_EXTRA_ANIM;
    static {
        Object2ObjectArrayMap<String,String> object2ObjectArrayMap = new Object2ObjectArrayMap<>();
        for (int i = 0; i < 10; i++) {
            object2ObjectArrayMap.put("extra" + i, String.valueOf(i));
        }
        DEFAULT_EXTRA_ANIM = new OrderedStringMap<>(object2ObjectArrayMap);
    }

    public static class TranslucencyScanner {
        private static final int TILE_SIZE = 8;
        private final AlphaIndex[] indexes;
        private final boolean[] results;
        private final java.util.BitSet lastTranslucent=new java.util.BitSet();
        public java.util.BitSet lastTranslucent(){return (java.util.BitSet)lastTranslucent.clone();}
        private BufferedImage[] colorImages;

        public static final int FLAG_VISIBLE = 1;
        public static final int FLAG_HAS_HOLE = 2;
        public static final int FLAG_TRANSLUCENT = 4;
        public static final int FLAG_EMPTY = 8;

        public TranslucencyScanner(BufferedImage[] images, int expectedCount) {
            this.colorImages = images;
            this.indexes = new AlphaIndex[images.length];
            for (int i = 0; i < images.length; i++) {
                if (images[i] != null) {
                    this.indexes[i] = new AlphaIndex(images[i]);
                }
            }
            this.results = new boolean[Math.max(expectedCount, indexes.length)];
        }

        private TranslucencyScanner(AlphaIndex[] indexes, int expectedCount) {
            this.indexes = indexes;
            this.results = new boolean[Math.max(expectedCount, indexes.length)];
        }

        public TranslucencyScanner fork(int expectedCount) {
            var scanner = new TranslucencyScanner(indexes, expectedCount);
            scanner.colorImages = colorImages;
            return scanner;
        }

        int[] averageColors(List<GeoModel.BakedBone> bones, int group) {
            int[] colors=new int[results.length];
            java.util.Arrays.fill(colors,0x181818);
            if(colorImages==null) return colors;
            for(int t=0;t<colorImages.length;t++) {
                BufferedImage image=colorImages[t]; if(image==null) continue;
                double r=0,g=0,b=0,weight=0;
                for(var bone:bones) if(bone.outlineGroup==group) for(var cube:bone.cubes) for(var q:cube.quads) {
                    var p=q.positions;
                    var e1=new org.joml.Vector3f(p[3]-p[0],p[4]-p[1],p[5]-p[2]);
                    var e2=new org.joml.Vector3f(p[9]-p[0],p[10]-p[1],p[11]-p[2]);
                    double area=e1.cross(e2).length()/64.0;
                    for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
                        float u=(x+.5f)/8,v=(y+.5f)/8;
                        float tu=q.uvs[0]+u*(q.uvs[2]-q.uvs[0])+v*(q.uvs[6]-q.uvs[0]);
                        float tv=q.uvs[1]+u*(q.uvs[3]-q.uvs[1])+v*(q.uvs[7]-q.uvs[1]);
                        int rgb=image.getRGB(Math.floorMod((int)Math.floor(tu*image.getWidth()),image.getWidth()),
                                Math.floorMod((int)Math.floor(tv*image.getHeight()),image.getHeight()));
                        double w=(rgb>>>24)/255.0*area;
                        weight+=w;r+=((rgb>>16)&255)*w;g+=((rgb>>8)&255)*w;b+=(rgb&255)*w;
                    }
                }
                if(weight>0) {
                    double red=r/weight,green=g/weight,blue=b/weight;
                    double gray=.2126*red+.7152*green+.0722*blue;
                    colors[t]=((int)((gray+(red-gray)*.55)*.25)<<16)
                            |((int)((gray+(green-gray)*.55)*.25)<<8)
                            |(int)((gray+(blue-gray)*.55)*.25);
                }
            }
            return colors;
        }

        public boolean[] getResults() {
            return results;
        }

        int itemWidth() { return java.util.Arrays.stream(indexes).filter(java.util.Objects::nonNull).mapToInt(i->i.width).max().orElse(0); }
        int itemHeight() { return java.util.Arrays.stream(indexes).filter(java.util.Objects::nonNull).mapToInt(i->i.height).max().orElse(0); }
        boolean itemPixel(float u,float v) {
            boolean valid=false;
            for(var index:indexes) if(index!=null) {
                valid=true;
                int x=Math.max(0,Math.min(index.width-1,(int)(u*index.width)));
                int y=Math.max(0,Math.min(index.height-1,(int)(v*index.height)));
                if((index.pixelFlags[y*index.width+x]&FLAG_VISIBLE)!=0) return true;
            }
            return !valid;
        }

        public int scan(RawYsmModel.RawFace face) {
            lastTranslucent.clear();
            float minU = face.u[0], maxU = face.u[0];
            float minV = face.v[0], maxV = face.v[0];
            for (int i = 1; i < 4; i++) {
                minU = Math.min(minU, face.u[i]);
                maxU = Math.max(maxU, face.u[i]);
                minV = Math.min(minV, face.v[i]);
                maxV = Math.max(maxV, face.v[i]);
            }

            boolean hasValidImage = false;
            boolean hasVisible = false;
            boolean hasHole = false;
            boolean hasTranslucent = false;
            boolean hasEmpty = false;

            for (int i = 0; i < indexes.length; i++) {
                AlphaIndex index = indexes[i];
                if (index == null) continue;
                hasValidImage = true;

                int imgW = index.width;
                int imgH = index.height;

                int startX = (int) Math.floor(minU * imgW + 0.01f);
                int endX = (int) Math.floor(maxU * imgW - 0.01f);
                if (endX < startX) endX = startX;

                int startY = (int) Math.floor(minV * imgH + 0.01f);
                int endY = (int) Math.floor(maxV * imgH - 0.01f);
                if (endY < startY) endY = startY;

                startX = Math.max(0, Math.min(startX, imgW - 1));
                endX = Math.max(0, Math.min(endX, imgW - 1));
                startY = Math.max(0, Math.min(startY, imgH - 1));
                endY = Math.max(0, Math.min(endY, imgH - 1));

                int flags = index.query(startX, endX, startY, endY);
                hasVisible |= (flags & FLAG_VISIBLE) != 0;
                hasHole |= (flags & FLAG_HAS_HOLE) != 0;
                hasTranslucent |= (flags & FLAG_TRANSLUCENT) != 0;
                hasEmpty |= (flags & FLAG_EMPTY) != 0;

                if ((flags & FLAG_TRANSLUCENT)!=0) {results[i]=true;lastTranslucent.set(i);}
            }

            if (!hasValidImage) return FLAG_VISIBLE;

            int mask = 0;
            if (hasVisible) mask |= FLAG_VISIBLE;
            if (hasHole) mask |= FLAG_HAS_HOLE;
            if (hasTranslucent) mask |= FLAG_TRANSLUCENT;
            if (hasEmpty) mask |= FLAG_EMPTY;
            return mask;
        }

        private static final class AlphaIndex {
            private final int width;
            private final int height;
            private final byte[] pixelFlags;
            private final int tileColumns;
            private final int prefixStride;
            private final int[] visiblePrefix;
            private final int[] holePrefix;
            private final int[] translucentPrefix;
            private final int[] emptyPrefix;

            private AlphaIndex(BufferedImage image) {
                this.width = image.getWidth();
                this.height = image.getHeight();
                this.pixelFlags = new byte[width * height];
                this.tileColumns = (width + TILE_SIZE - 1) / TILE_SIZE;
                int tileRows = (height + TILE_SIZE - 1) / TILE_SIZE;
                this.prefixStride = tileColumns + 1;

                byte[] tileFlags = new byte[tileColumns * tileRows];
                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
                for (int y = 0; y < height; y++) {
                    int tileRowOffset = (y / TILE_SIZE) * tileColumns;
                    int pixelRowOffset = y * width;
                    for (int x = 0; x < width; x++) {
                        byte flags = (byte) flagsForAlpha((pixels[pixelRowOffset + x] >>> 24) & 0xFF);
                        pixelFlags[pixelRowOffset + x] = flags;
                        tileFlags[tileRowOffset + x / TILE_SIZE] |= flags;
                    }
                }

                int prefixSize = prefixStride * (tileRows + 1);
                this.visiblePrefix = new int[prefixSize];
                this.holePrefix = new int[prefixSize];
                this.translucentPrefix = new int[prefixSize];
                this.emptyPrefix = new int[prefixSize];

                for (int tileY = 0; tileY < tileRows; tileY++) {
                    int visibleCount = 0;
                    int holeCount = 0;
                    int translucentCount = 0;
                    int emptyCount = 0;
                    int tileRowOffset = tileY * tileColumns;
                    int prefixRowOffset = (tileY + 1) * prefixStride;
                    int previousPrefixRowOffset = tileY * prefixStride;

                    for (int tileX = 0; tileX < tileColumns; tileX++) {
                        int flags = tileFlags[tileRowOffset + tileX];
                        if ((flags & FLAG_VISIBLE) != 0) visibleCount++;
                        if ((flags & FLAG_HAS_HOLE) != 0) holeCount++;
                        if ((flags & FLAG_TRANSLUCENT) != 0) translucentCount++;
                        if ((flags & FLAG_EMPTY) != 0) emptyCount++;

                        int prefixIndex = prefixRowOffset + tileX + 1;
                        visiblePrefix[prefixIndex] = visiblePrefix[previousPrefixRowOffset + tileX + 1] + visibleCount;
                        holePrefix[prefixIndex] = holePrefix[previousPrefixRowOffset + tileX + 1] + holeCount;
                        translucentPrefix[prefixIndex] = translucentPrefix[previousPrefixRowOffset + tileX + 1] + translucentCount;
                        emptyPrefix[prefixIndex] = emptyPrefix[previousPrefixRowOffset + tileX + 1] + emptyCount;
                    }
                }
            }

            private int query(int startX, int endX, int startY, int endY) {
                int fullStartX = ((startX + TILE_SIZE - 1) / TILE_SIZE) * TILE_SIZE;
                int fullEndX = ((endX + 1) / TILE_SIZE) * TILE_SIZE;
                int fullStartY = ((startY + TILE_SIZE - 1) / TILE_SIZE) * TILE_SIZE;
                int fullEndY = ((endY + 1) / TILE_SIZE) * TILE_SIZE;

                if (fullStartX >= fullEndX || fullStartY >= fullEndY) {
                    return scanPixels(startX, endX, startY, endY, 0);
                }

                int flags = queryFullTiles(
                        fullStartX / TILE_SIZE,
                        fullEndX / TILE_SIZE,
                        fullStartY / TILE_SIZE,
                        fullEndY / TILE_SIZE
                );
                if (flags == FLAG_VISIBLE + FLAG_HAS_HOLE + FLAG_TRANSLUCENT + FLAG_EMPTY) return flags;

                flags = scanPixels(startX, endX, startY, fullStartY - 1, flags);
                if (flags == FLAG_VISIBLE + FLAG_HAS_HOLE + FLAG_TRANSLUCENT + FLAG_EMPTY) return flags;

                flags = scanPixels(startX, endX, fullEndY, endY, flags);
                if (flags == FLAG_VISIBLE + FLAG_HAS_HOLE + FLAG_TRANSLUCENT + FLAG_EMPTY) return flags;

                flags = scanPixels(startX, fullStartX - 1, fullStartY, fullEndY - 1, flags);
                if (flags == FLAG_VISIBLE + FLAG_HAS_HOLE + FLAG_TRANSLUCENT + FLAG_EMPTY) return flags;

                return scanPixels(fullEndX, endX, fullStartY, fullEndY - 1, flags);
            }

            private int queryFullTiles(int startTileX, int endTileX, int startTileY, int endTileY) {
                int flags = 0;
                if (prefixCount(visiblePrefix, startTileX, endTileX, startTileY, endTileY) > 0) {
                    flags |= FLAG_VISIBLE;
                }
                if (prefixCount(holePrefix, startTileX, endTileX, startTileY, endTileY) > 0) {
                    flags |= FLAG_HAS_HOLE;
                }
                if (prefixCount(translucentPrefix, startTileX, endTileX, startTileY, endTileY) > 0) {
                    flags |= FLAG_TRANSLUCENT;
                }
                if (prefixCount(emptyPrefix, startTileX, endTileX, startTileY, endTileY) > 0) flags |= FLAG_EMPTY;
                return flags;
            }

            private int prefixCount(int[] prefix, int startTileX, int endTileX, int startTileY, int endTileY) {
                int topLeft = startTileY * prefixStride + startTileX;
                int topRight = startTileY * prefixStride + endTileX;
                int bottomLeft = endTileY * prefixStride + startTileX;
                int bottomRight = endTileY * prefixStride + endTileX;
                return prefix[bottomRight] - prefix[topRight] - prefix[bottomLeft] + prefix[topLeft];
            }

            private int scanPixels(int startX, int endX, int startY, int endY, int flags) {
                if (startX > endX || startY > endY) return flags;

                for (int y = startY; y <= endY; y++) {
                    int rowOffset = y * width;
                    for (int x = startX; x <= endX; x++) {
                        flags |= pixelFlags[rowOffset + x];
                        if (flags == FLAG_VISIBLE + FLAG_HAS_HOLE + FLAG_TRANSLUCENT + FLAG_EMPTY) return flags;
                    }
                }
                return flags;
            }

            private static int flagsForAlpha(int alpha) {
                if (alpha == 0) return FLAG_HAS_HOLE | FLAG_EMPTY;
                if (alpha == 255) return FLAG_VISIBLE;
                return FLAG_VISIBLE | FLAG_HAS_HOLE | FLAG_TRANSLUCENT;
            }
        }
    }

    private static BufferedImage decodeToImage(byte[] data, int imageFormat, int width, int height) {
        if (data == null || data.length == 0) {
            return null;
        }

        if (imageFormat == 0) {
            imageFormat = YSMFolderDeserializer.detectFormat(data);
            if (imageFormat == 0) {
                imageFormat = 1;
            }
        }

        try {
            if (imageFormat == -1) {
                if (width > 0 && height > 0 && data.length >= width * height * 4) {
                    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    int[] pixels = new int[width * height];
                    for (int i = 0; i < pixels.length; i++) {
                        int r = data[i * 4] & 0xFF;
                        int g = data[i * 4 + 1] & 0xFF;
                        int b = data[i * 4 + 2] & 0xFF;
                        int a = data[i * 4 + 3] & 0xFF;
                        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    img.setRGB(0, 0, width, height, pixels, 0, width);
                    return img;
                } else throw new RuntimeException("Invalid RGBA texture");
            } else {
                switch (imageFormat) {
                    case 1:
                    case 2:
                    case 3:
                        return ImageIO.read(new ByteArrayInputStream(data));
                    case 4: return new WebpDecoder().read(data);
                    case 5: return new AvifDecoder().read(data);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static byte[] encodeToPng(BufferedImage img, byte[] fallbackData) {
        if (img != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                return baos.toByteArray();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return fallbackData;
    }

    public static byte[] toPng(byte[] data, int imageFormat, int width, int height) {
        if (imageFormat == 2) {
            return data;
        }
        BufferedImage img = decodeToImage(data, imageFormat, width, height);
        return encodeToPng(img, data);
    }

    public static OuterFileTexture toTexture(byte[] data, int imageFormat, int width, int height) {
        return toTexture(data, imageFormat, width, height, null);
    }

    private static OuterFileTexture toTexture(byte[] data, int imageFormat, int width, int height, BufferedImage decodedImage) {
        int resolvedFormat = imageFormat == 0 ? YSMFolderDeserializer.detectFormat(data) : imageFormat;
        if (resolvedFormat >= 1 && resolvedFormat <= 3) {
            return new OuterFileTexture(data);
        }
        BufferedImage image = decodedImage != null ? decodedImage : decodeToImage(data, resolvedFormat, width, height);
        return image != null ? new OuterFileTexture(image) : new OuterFileTexture(data);
    }

    public static ClientModelInfo buildParsedBundle(RawYsmModel raw, String modelId) {
        net.everla.ysmJsLib.ScriptModel.BUILDING.set(net.everla.ysmJsLib.ScriptResources.compile(raw));
        Vector3v.beginConstantPooling();
        try {
        Map<String, OuterFileTexture> mainTextures = new LinkedHashMap<>();
        int textureCount = Math.max(1, raw.mainEntity.textures.size());

        List<BufferedImage> imagesList = new ArrayList<>();

        for (RawYsmModel.RawTexture rt : raw.mainEntity.textures.values()) {
            BufferedImage img = decodeToImage(rt.data, rt.imageFormat, rt.width, rt.height);
            imagesList.add(img);

            OuterFileTexture tex = toTexture(rt.data, rt.imageFormat, rt.width, rt.height, img);
            Map<ShadersTextureType, OuterFileTexture> suffixTextures = new LinkedHashMap<>();
            for (RawYsmModel.RawTexture.SubTexture sub : rt.subTextures) {
                if (sub.data == null) continue;
                if (sub.specularType == 1) {
                    suffixTextures.put(ShadersTextureType.NORMAL, toTexture(sub.data, sub.imageFormat, sub.width, sub.height));
                } else if (sub.specularType == 2) {
                    suffixTextures.put(ShadersTextureType.SPECULAR, toTexture(sub.data, sub.imageFormat, sub.width, sub.height));
                }
            }
            tex.setSuffixTextures(suffixTextures);
            mainTextures.put(rt.name, tex);
        }
        Map<String, OuterFileTexture> avatarTextures = new LinkedHashMap<>();
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (author.avatarImage == null) continue;
            OuterFileTexture tex = toTexture(author.avatarImage.data, author.avatarImage.format, author.avatarImage.width, author.avatarImage.height);
            avatarTextures.put(author.avatarImage.name, tex);
        }
        OrderedStringMap<String, OuterFileTexture> textureMap = buildTextureMap(mainTextures);

        GeometryDescription context = buildContext(raw.mainEntity.mainModel);

        BufferedImage[] imagesArray = imagesList.toArray(new BufferedImage[0]);
        TranslucencyScanner mainScanner = raw.mainEntity.mainModel != null ?
                new TranslucencyScanner(imagesArray, textureCount) : null;
        TranslucencyScanner armScanner = raw.mainEntity.armModel != null ?
                mainScanner != null ? mainScanner.fork(textureCount) : new TranslucencyScanner(imagesArray, textureCount) : null;

        GeoModel mainMesh = buildMesh(raw.mainEntity.mainModel, context, textureCount, mainScanner, raw.properties.allCutout, "player.main");
        GeoModel armMesh = raw.mainEntity.armModel != null ? buildMesh(raw.mainEntity.armModel, context, textureCount, armScanner, raw.properties.allCutout, "player.arm") : mainMesh;

//        System.out.println(modelId + Arrays.toString(mainMesh.translucentTexture));

        GeoModel[] meshes = new GeoModel[]{mainMesh, armMesh};

        Map<String, AnimationFile> animations = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> entry : raw.mainEntity.animationFiles.entrySet()) {
            animations.put(entry.getKey(), new AnimationFile(buildAnimations(entry.getValue(), raw.properties.mergeMultilineExpr)));
        }

        List<AnimationControllerFile> controllersList = new ArrayList<>();
        if (raw.mainEntity.animationControllerFiles != null) {
            for (RawYsmModel.RawAnimationControllerFile file : raw.mainEntity.animationControllerFiles) {
                Map<String, AnimationController> controllerMap = buildControllers(file.controllers, raw.properties.mergeMultilineExpr);
                if (!controllerMap.isEmpty()) {
                    controllersList.add(new AnimationControllerFile(controllerMap));
                }
            }
        }

        MainModelData mainModelData = new MainModelData(meshes, animations, controllersList.toArray(new AnimationControllerFile[0]), textureMap);

        ServerModelInfo modelInfo = buildModelInfo(raw);
        ModelExtraResourcesFile extraResources = buildExtraResources(raw);
        ProjectileModelFiles[] extraItemModels = buildExtraItemModels(raw, context, raw.properties.mergeMultilineExpr);
        VehicleModelFiles[] extraEntityModels = buildExtraEntityModels(raw, context, raw.properties.mergeMultilineExpr);
        Map<String, OuterFileTexture> extraTextures = buildExtraTextures(raw);

        return new ClientModelInfo(mainModelData, extraItemModels, extraEntityModels, extraResources, modelInfo, avatarTextures, extraTextures);
        } finally {
            net.everla.ysmJsLib.ScriptModel.BUILDING.remove();
            Vector3v.endConstantPooling();
        }
    }

    private static GeoModel buildMesh(RawYsmModel.RawGeometry rawGeo, GeometryDescription context, int textureCount, TranslucencyScanner scanner, boolean allCutout, String role) {
        var shared=net.everla.ysmJsLib.ScriptModel.BUILDING.get();
        if(shared!=null)net.everla.ysmJsLib.ScriptModel.BUILDING.set(shared.forModel(role));
        try{return buildScopedMesh(rawGeo,context,textureCount,scanner,allCutout);}
        finally{if(shared!=null)net.everla.ysmJsLib.ScriptModel.BUILDING.set(shared);}
    }
    private static GeoModel buildScopedMesh(RawYsmModel.RawGeometry rawGeo, GeometryDescription context, int textureCount, TranslucencyScanner scanner, boolean allCutout) {
        if (rawGeo == null || rawGeo.bones.isEmpty()) {
            boolean[] fallbackArray = scanner != null ? scanner.getResults() : new boolean[Math.max(1, textureCount)];
            return buildMesh(new GeoBone[0], new HashMap<>(), context, fallbackArray);
        }

        var scripts=net.everla.ysmJsLib.ScriptModel.BUILDING.get();
        boolean hasPhysics = scripts != null;
        List<GeoBone> geoBones = new ArrayList<>();
        List<GeoModel.BakedBone> bakedBones = new ArrayList<>();
        Map<String, String> parentMap = new HashMap<>();

        for (RawYsmModel.RawBone rb : rawGeo.bones) {
            parentMap.put(rb.name, rb.parentName);
            geoBones.add(new GeoBone(rb.name, false, false, false, rb.pivot[0], rb.pivot[1], rb.pivot[2], rb.rotation[0], rb.rotation[1], rb.rotation[2]));

            GeoModel.BakedBone bb = new GeoModel.BakedBone();
            bb.name = rb.name;
            bb.facingCamera = scripts!=null && scripts.flag(rb.name,"facingCamera");
            if(scripts!=null && scripts.flag(rb.name,"glow"))bb.glow=true;
            String frameName=scripts==null?"":scripts.encoded(rb.name,"frameAnimation","FrameAnimation");
            if (rb.name.startsWith("ysmGlow")) bb.glow = true;
            bb.pivotX = rb.pivot[0];
            bb.pivotY = rb.pivot[1];
            bb.pivotZ = rb.pivot[2];
            bb.rotX = rb.rotation[0];
            bb.rotY = rb.rotation[1];
            bb.rotZ = rb.rotation[2];
            bb.parentIdx = -1;

            if (frameName.startsWith("FrameAnimation")) {
                try {
                    bb.frameAnimation = net.everla.reimaginedysm.gpu.FrameAnimation.parse(frameName);
                    if (rb.cubes.size() != 1 || rb.cubes.get(0).faces.stream().filter(ItemThickness::hasUv).count() != 1)
                        throw new IllegalArgumentException("expected exactly one cube with one UV face");
                    var face = rb.cubes.get(0).faces.stream().filter(ItemThickness::hasUv).findFirst().orElseThrow();
                    float minV=face.v[0], maxV=minV;
                    for (float v : face.v) { minV=Math.min(minV,v); maxV=Math.max(maxV,v); }
                    bb.frameHeight=maxV-minV;
                    if (!Float.isFinite(bb.frameHeight) || bb.frameHeight <= 0 || minV < 0
                            || minV + bb.frameHeight * bb.frameAnimation.count() > 1.00001f)
                        throw new IllegalArgumentException("frame strip must have positive height and fit inside the texture");
                } catch (IllegalArgumentException ex) {
                    bb.frameAnimation=null;
                    net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("[FrameAnimation] {}: {}", rb.name, ex.getMessage());
                }
            }
            boolean forceCull = allCutout;

            boolean item=scripts!=null && scripts.flag(rb.name,"item");
            RawYsmModel.RawFace itemFace=null;
            if(item && rb.cubes.size()==1) {
                var faces=rb.cubes.get(0).faces.stream().filter(ItemThickness::hasUv).toList();
                if(faces.size()==1) itemFace=faces.get(0);
            }
            if(item && itemFace==null) net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("[ysmItem] {}: expected exactly one cube with one UV face",rb.name);
            for (RawYsmModel.RawCube rc : rb.cubes) {
                if (hasPhysics) {
                    float[] physicsFaces = new float[rc.faces.size() * 12];
                    int physicsOffset = 0;
                    for (RawYsmModel.RawFace face : rc.faces) {
                        for (int v = 0; v < 4; v++) {
                            System.arraycopy(face.positions[v], 0, physicsFaces, physicsOffset, 3);
                            physicsOffset += 3;
                        }
                    }
                    bb.physicsFaces.add(physicsFaces);
                }
                GeoModel.BakedCube bc = new GeoModel.BakedCube();
                if(itemFace!=null) {
                    bc=ItemThickness.bake(itemFace,scanner,rawGeo.textureWidth,rawGeo.textureHeight);
                    if(!bc.quads.isEmpty()) bb.cubes.add(bc);
                    continue;
                }
                int validFaceCount = 0;
                boolean cubeHasHole = false;
                boolean cubeHasEmpty = false;

                for (RawYsmModel.RawFace rf : rc.faces) {
                    if((item || bb.frameAnimation != null) && !ItemThickness.hasUv(rf)) continue;
                    if (java.util.Arrays.stream(new double[]{rf.u[0],rf.u[1],rf.u[2],rf.u[3],rf.v[0],rf.v[1],rf.v[2],rf.v[3]}).allMatch(uv -> uv == -1)) continue;
                    int faceState = scanner != null ? scanner.scan(rf) : TranslucencyScanner.FLAG_VISIBLE;
                    if (bb.frameAnimation != null && scanner != null) {
                        // Scan the complete strip: a transparent first frame must not discard later frames.
                        var strip = new RawYsmModel.RawFace();
                        strip.u = rf.u;
                        float minV=rf.v[0],maxV=minV;
                        for(float v:rf.v) { minV=Math.min(minV,v); maxV=Math.max(maxV,v); }
                        float end=maxV+bb.frameHeight*(bb.frameAnimation.count()-1);
                        strip.v = new float[]{minV,minV,end,end};
                        faceState = scanner.scan(strip);
                    }
                    cubeHasEmpty |= (faceState & TranslucencyScanner.FLAG_EMPTY) != 0;

                    if ((faceState & TranslucencyScanner.FLAG_VISIBLE) == 0) {
                        continue;
                    }

                    if ((faceState & TranslucencyScanner.FLAG_HAS_HOLE) != 0) {
                        cubeHasHole = true;
                    }

                    boolean isTranslucent = (faceState & TranslucencyScanner.FLAG_TRANSLUCENT) != 0;

                    if (!forceCull && isNegativeSizedFace(rf)) {
                        forceCull = true;
                    }

                    GeoModel.BakedQuad bq = new GeoModel.BakedQuad();
                    bq.isTranslucent = isTranslucent;
                    if(scanner!=null)bq.translucentTextures=scanner.lastTranslucent();
                    System.arraycopy(rf.normal, 0, bq.normal, 0, 3);
                    for (int i = 0; i < 4; i++) {
                        int positionOffset = i * 3;
                        int uvOffset = i * 2;
                        System.arraycopy(rf.positions[i], 0, bq.positions, positionOffset, 3);
                        bq.uvs[uvOffset] = rf.u[i];
                        bq.uvs[uvOffset + 1] = rf.v[i];
                    }
                    bc.quads.add(bq);
                    validFaceCount++;
                }

                boolean isZeroThickness = true;
                if (!bc.quads.isEmpty()) {
                    float[] baseNormal = bc.quads.get(0).normal;
                    float[] basePositions = bc.quads.get(0).positions;

                    for (GeoModel.BakedQuad q : bc.quads) {
                        for (int i = 0; i < 4; i++) {
                            int offset = i * 3;
                            float dx = q.positions[offset] - basePositions[0];
                            float dy = q.positions[offset + 1] - basePositions[1];
                            float dz = q.positions[offset + 2] - basePositions[2];
                            if (Math.abs(dx * baseNormal[0] + dy * baseNormal[1] + dz * baseNormal[2]) > 1e-3f) {
                                isZeroThickness = false;
                                break;
                            }
                        }
                        if (!isZeroThickness) break;
                    }
                } else {
                    isZeroThickness = false;
                }

                if (forceCull) {
                    bc.cullable = true;
                } else if (cubeHasHole) {
                    bc.cullable = false;
                } else if (isZeroThickness && validFaceCount > 1) {
                    bc.cullable = true;
                } else {
                    bc.cullable = validFaceCount >= 5;
                }

                bc.closedVolume = validFaceCount == 6 && !isZeroThickness && !cubeHasEmpty;
                // Semi-transparent coverage is not a hole: closed solids only show their front shell.
                bc.cullable |= bc.closedVolume;

                if (!bc.quads.isEmpty()) {
                    bb.cubes.add(bc);
                }
            }
            bakedBones.add(bb);
        }

        for (GeoModel.BakedBone b : bakedBones) {
            String parentName = parentMap.get(b.name);
            if (parentName != null && !parentName.isEmpty()) {
                for (int i = 0; i < bakedBones.size(); i++) {
                    if (bakedBones.get(i).name.equals(parentName)) {
                        b.parentIdx = i;
                        break;
                    }
                }
            }
            if (b.name.equals("LeftArm")) b.partMask = 1;
            else if (b.name.equals("RightArm")) b.partMask = 2;
            else if (b.name.equals("Background")) b.partMask = 3;
            else if (b.parentIdx != -1) b.partMask = bakedBones.get(b.parentIdx).partMask;
            else b.partMask = 0;
        }

        boolean[] translucencyArray = scanner != null ? scanner.getResults() : new boolean[Math.max(1, textureCount)];
        GeoModel mesh = buildMesh(geoBones.toArray(new GeoBone[0]), parentMap, context, translucencyArray);

        mesh.scripts=scripts;
        if(scripts!=null)net.everla.ysmJsLib.CosmicTextures.prepare(scripts);
        mesh.bakedBones = bakedBones;
        mesh.hasFacingCameraBonesOverride(bakedBones.stream().anyMatch(b -> b.facingCamera));
        mesh.hasFrameAnimations = bakedBones.stream().anyMatch(b -> b.frameAnimation != null);
        boolean[] styled = new boolean[bakedBones.size()];
        for(int i=0;i<bakedBones.size();i++) resolveGroupStyle(bakedBones,i,styled);
        mesh.hasGroupStyles=bakedBones.stream().anyMatch(b -> b.style.cosmic() || b.style.outline());
        if(bakedBones.stream().anyMatch(b->b.style.cosmic()))com.elfmcys.yesstevemodel.geckolib3.geo.GroupEffectRenderer.prepareStars();
        if(scanner!=null) for(int i=0;i<bakedBones.size();i++) {
            var bone=bakedBones.get(i);
            if(bone.outlineGroup==i) bone.averageColors=scanner.averageColors(bakedBones,i);
        }
        GeoModel.classifyFacingCameraBones(bakedBones);
        if (hasPhysics) mesh.ysmPhysics = new net.everla.ysmPhysLib.PhysDefinition(mesh,
                message -> net.everla.reimaginedysm.core.YesSteveModel.LOGGER.warn("[ysmPhys] {}: {}", rawGeo.identifier, message));
        if (NativeLibLoader.isLoaded()) mesh.buildNativeCache();
        return mesh;
    }

    private static void resolveGroupStyle(List<GeoModel.BakedBone> bones,int i,boolean[] done) {
        if(done[i]) return;
        var bone=bones.get(i);
        var parent=net.everla.reimaginedysm.gpu.GroupStyle.NONE;
        bone.surfaceGroup=i;
        if(bone.parentIdx>=0) {
            resolveGroupStyle(bones,bone.parentIdx,done);
            var p=bones.get(bone.parentIdx);parent=p.style;
            bone.outlineGroup=p.outlineGroup;
            if(parent.cosmic() || parent.outline()) bone.surfaceGroup=p.surfaceGroup;
        }
        bone.style=parent;
        var scripts=net.everla.ysmJsLib.ScriptModel.BUILDING.get();
        if(scripts!=null) {
            bone.style=scripts.style(bone.name,bone.style);
            bone.cosmicEffect=scripts.effect(bone.name,bone.parentIdx<0?"":bones.get(bone.parentIdx).cosmicEffect);
        }
        if(scripts!=null && scripts.group(bone.name).has("outline") && bone.style!=parent) bone.outlineGroup=i;
        if(bone.style!=parent) bone.surfaceGroup=i;
        done[i]=true;
    }

    private static Map<String, Animation> buildAnimations(RawYsmModel.RawAnimationFile animFile, boolean mergeMultilineExpr) {
        Map<String, Animation> result = new LinkedHashMap<>();
        for (RawYsmModel.RawAnimation ra : animFile.animations.values()) {
            ILoopType loopMode = ILoopType.EDefaultLoopTypes.PLAY_ONCE;
            if (ra.loopMode == 1) loopMode = ILoopType.EDefaultLoopTypes.LOOP;
            else if (ra.loopMode == 3) loopMode = ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;

            List<BoneAnimation> boneAnims = new ArrayList<>();
            for (RawYsmModel.RawBoneAnimation rba : ra.boneAnimations) {
//                if (rba.boneName.equals("gun")) {
//                    "".hashCode();
//                }
                List<BoneKeyFrame> rotFrames = parseKeyframes(rba.rotation, true);
                List<BoneKeyFrame> posFrames = parseKeyframes(rba.position, false);
                List<BoneKeyFrame> scaleFrames = parseKeyframes(rba.scale, false);
                boneAnims.add(new BoneAnimation(rba.boneName, rotFrames, posFrames, scaleFrames));
            }

            List<EventKeyFrame<String>> soundEffects = new ArrayList<>();
            for (RawYsmModel.RawSoundEffect rse : ra.soundEffects) {
                soundEffects.add(new EventKeyFrame<>(rse.timestamp * 20.0f, rse.effectName));
            }

            List<EventKeyFrame<IValue[]>> timelineEvents = new ArrayList<>();
            for (RawYsmModel.RawTimelineEvent rte : ra.timelineEvents) {
                List<IValue> values = parse(rte.events, mergeMultilineExpr);
                timelineEvents.add(new EventKeyFrame<>(rte.timestamp * 20.0f, values.toArray(new IValue[0])));
            }

            IValue blendWeight;
            if (ra.blendWeight instanceof Float)
                blendWeight = new FloatValue((Float) ra.blendWeight);
            else if (ra.blendWeight instanceof String)
                try {
                    blendWeight = parse((String) ra.blendWeight);
                } catch (Exception e) {
                    blendWeight = null;
                }
            else blendWeight = null;

            Animation anim = new Animation(ra.name, ra.length * 20.0f, loopMode, blendWeight, null, null, null, boneAnims.toArray(new BoneAnimation[0]), soundEffects.toArray(new EventKeyFrame[0]), new ParticleEventKeyFrame[0], timelineEvents.toArray(new EventKeyFrame[0]));
            result.put(ra.name, anim);
        }
        return result;
    }

    private static List<BoneKeyFrame> parseKeyframes(List<RawYsmModel.RawKeyframe> frames, boolean isRotation) {
        List<RawBoneKeyFrame> builders = new ArrayList<>();
        for (RawYsmModel.RawKeyframe rk : frames) {
            RawBoneKeyFrame builder = new RawBoneKeyFrame();
            builder.startTick = rk.timestamp * 20.0f;
            builder.easingType = rk.interpolationMode == 2 ? EasingType.CATMULLROM : EasingType.LINEAR;
            builder.contiguous = !rk.hasPreData;

            if (rk.hasPreData) {
                assignToBuilder(builder, rk.preData, true);
                assignToBuilder(builder, rk.postData, false);
            } else {
                assignToBuilder(builder, rk.postData, true);
            }
            builders.add(builder);
        }
        return BoneKeyFrameProcessor.process(builders, isRotation);
    }

    private static void assignToBuilder(RawBoneKeyFrame builder, Object[] data, boolean isPre) {
        for (int axis = 0; axis < 3; axis++) {
            double dVal = 0.0;
            IValue iVal = null;
            Object val = data[axis];
            if (val instanceof Float) dVal = (Float) val;
            else if (val instanceof String) {
                try {
                    iVal = parse((String) val);
                } catch (Exception ignore) {
                }
            }
            if (isPre) {
                if (axis == 0) {
                    builder.preX = dVal;
                    builder.preXValue = iVal;
                } else if (axis == 1) {
                    builder.preY = dVal;
                    builder.preYValue = iVal;
                } else if (axis == 2) {
                    builder.preZ = dVal;
                    builder.preZValue = iVal;
                }
            } else {
                if (axis == 0) {
                    builder.postX = dVal;
                    builder.postXValue = iVal;
                } else if (axis == 1) {
                    builder.postY = dVal;
                    builder.postYValue = iVal;
                } else if (axis == 2) {
                    builder.postZ = dVal;
                    builder.postZValue = iVal;
                }
            }
        }
    }

    private static Map<String, AnimationController> buildControllers(Map<String, RawYsmModel.RawAnimationController> rawControllers, boolean mergeMultilineExpr) {
        Map<String, AnimationController> result = new LinkedHashMap<>();
        for (RawYsmModel.RawAnimationController rac : rawControllers.values()) {
            List<AnimationState> states = new ArrayList<>();
            for (RawYsmModel.RawControllerState rs : rac.states) {
                List<Pair<String, IValue>> animations = new ArrayList<>();
                for (Map.Entry<String, String> e : rs.animations) {
                    IValue blend = null;
                    if (!e.getValue().isEmpty()) {
                        try {
                            blend = parse(e.getValue());
                        } catch (Exception ignore) {
                        }
                    }
                    animations.add(Pair.of(e.getKey(), blend));
                }


                List<Pair<String, IValue>> transitions = new ArrayList<>();
                for (Map.Entry<String, String> e : rs.transitions) {
                    IValue condition = parse(e.getValue());
                    transitions.add(Pair.of(e.getKey(), condition));
                }

                List<IValue> onEntry = parse(rs.onEntry, mergeMultilineExpr);
                List<IValue> onExit = parse(rs.onExit, mergeMultilineExpr);

                IInterpolable blendTransition;
                if (!rs.blendTransitions.isEmpty()) {
                    float[] keys = new float[rs.blendTransitions.size()];
                    float[] values = new float[rs.blendTransitions.size()];
                    int i = 0;
                    for (Map.Entry<Float, Float> e : rs.blendTransitions.entrySet()) {
                        keys[i] = e.getKey();
                        values[i] = e.getValue();
                        i++;
                    }
                    blendTransition = new LinearKeyframeInterpolator(keys, values);
                } else {
                    blendTransition = new TicksInterpolator(rs.blendTransitionValue);
                }

                states.add(new AnimationState(rs.name, animations.toArray(new Pair[0]), transitions.toArray(new Pair[0]), rs.soundEffects.toArray(new String[0]), onEntry.toArray(new IValue[0]), onExit.toArray(new IValue[0]), blendTransition, rs.blendViaShortestPath));
            }
            result.put(rac.animationName,
                    new AnimationController(
                            rac.initialState.isEmpty() ? "default" : rac.initialState,
                            states.toArray(new AnimationState[0])
                    )
            );
        }
        return result;
    }

    public static ServerModelInfo buildModelInfo(RawYsmModel raw/*, String modelId*/) {
        int bones = 0;
        int cubes = 0;
        int faces = 0;
        if (raw.mainEntity.mainModel != null) {
            bones = raw.mainEntity.mainModel.bones.size();
            for (RawYsmModel.RawBone bone : raw.mainEntity.mainModel.bones) {
                cubes += bone.cubes.size();
                for (RawYsmModel.RawCube cube : bone.cubes) {
                    faces += cube.faces.size();
                }
            }
        }
        return buildModelInfo(raw, new MainModelInfo(bones, cubes, faces));
    }

    public static ServerModelInfo buildModelInfo(RawYsmModel raw, MainModelInfo stats) {
        RawYsmModel.RawMetadata rm = raw.metadata;
        List<AuthorInfo> authors = new ArrayList<>();
        for (RawYsmModel.RawMetadata.Author a : rm.authors) {
            authors.add(new AuthorInfo(a.name, a.role, new OrderedStringMap<>(new Object2ObjectArrayMap<>(a.contacts)), a.comment));
        }

        Metadata extraInfo = new Metadata(rm.name, rm.tips, new StringPair(rm.licenseType, rm.licenseDescription), authors.toArray(new AuthorInfo[0]), new OrderedStringMap<>(new Object2ObjectArrayMap<>(rm.links)));

        RawYsmModel.RawProperties rp = raw.properties;
        List<StringMapPair> classifyList = new ArrayList<>();
        for (RawYsmModel.ExtraAnimationClassify rCls : rp.extraAnimationClassifies) {
            classifyList.add(new StringMapPair(rCls.id, new OrderedStringMap<>(new Object2ObjectArrayMap<>(rCls.extras))));
        }

        List<ExtraAnimationButtons> buttonsList = new ArrayList<>();
        for (RawYsmModel.ExtraAnimationButton rBtn : rp.extraAnimationButtons) {
            List<AbstractConfig> metaList = new ArrayList<>();
            for (RawYsmModel.ConfigForm form : rBtn.forms) {
                if ("checkbox".equals(form.type)) {
                    metaList.add(new CheckboxConfig(form.title, form.description, form.defaultValue));
                } else if ("radio".equals(form.type)) {
                    metaList.add(new RadioConfig(form.title, form.description, form.defaultValue, new OrderedStringMap<>(new Object2ObjectArrayMap<>(form.labels))));
                } else if ("range".equals(form.type)) {
                    metaList.add(new RangeConfig(form.title, form.description, form.defaultValue, form.step, form.min, form.max));
                }
            }
            buttonsList.add(new ExtraAnimationButtons(rBtn.id, rBtn.name, rBtn.description, metaList.toArray(new AbstractConfig[0])));
        }
        ModelProperties properties = new ModelProperties(rp.heightScale,
                rp.widthScale,
                rp.defaultTexture,
                rp.previewAnimation,
                rp.extraAnimations.isEmpty() ? DEFAULT_EXTRA_ANIM : new OrderedStringMap<>(new Object2ObjectArrayMap<>(rp.extraAnimations)),
                buttonsList.toArray(new ExtraAnimationButtons[0]),
                classifyList.toArray(new StringMapPair[0]),
                rp.isFree,
                rp.renderLayersFirst,
                rp.disablePreviewRotation);

        RawYsmModel.RawFooter footer = raw.footer;
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawLanguageFile> entry : raw.languageFiles.entrySet()) {
            translations.put(entry.getKey(), entry.getValue().data);
        }
        return new ServerModelInfo(extraInfo,
                properties,
                stats,
                footer.version,
                rp.sha256 != null ? rp.sha256 : "",
                footer.extra, footer.time, footer.rand,
                translations);
    }

    private static ModelExtraResourcesFile buildExtraResources(RawYsmModel raw) {
        Map<String, AudioTrackData> sounds = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawDataFile> entry : raw.soundFiles.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue().data;
            AudioTrackData track = parseAudioTrackData(data);
            if (track != null) sounds.put(name, track);
        }

        Map<String, IValue> functions = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawDataFile> entry : raw.functionFiles.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue().data;
            String molangScript = new String(data, StandardCharsets.UTF_8);
            try {
                functions.put(name, parseMolang(molangScript, true));
            } catch (Exception e) {
            }
        }

        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawLanguageFile> entry : raw.languageFiles.entrySet()) {
            translations.put(entry.getKey(), entry.getValue().data);
        }

        return new ModelExtraResourcesFile(sounds, functions, translations);
    }

    private static AudioTrackData parseAudioTrackData(byte[] oggData) {
        if (oggData == null || oggData.length < 8) return null;
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(oggData);
            OggFile oggFile = new OggFile(bais);
            String header = new String(oggData, 0, Math.min(oggData.length, 100), StandardCharsets.US_ASCII);
            boolean isOpus = header.contains("OpusHead");

            AudioCodec codec = isOpus ? AudioCodec.OPUS : AudioCodec.VORBIS;
            int sampleRate;
            if (isOpus) {
                OpusFile opus = new OpusFile(oggFile);
                sampleRate = (int) opus.getInfo().getRate();
            } else {
                VorbisFile vorbis = new VorbisFile(oggFile);
                sampleRate = (int) vorbis.getInfo().getRate();
            }

            OggPacketReader reader = oggFile.getPacketReader();
            long durationSamples = 0;
            var packet = reader.getNextPacket();
            while (packet != null) {
                long granule = packet.getGranulePosition();
                if (granule > 0) durationSamples = granule;
                packet = reader.getNextPacket();
            }

            ByteBuffer directBuf = ByteBuffer.allocateDirect(oggData.length);
            directBuf.put(oggData);
            directBuf.flip();

            return new AudioTrackData(directBuf, codec.ordinal(), sampleRate, durationSamples);
        } catch (Exception e) {
            return null;
        }
    }

    private static ProjectileModelFiles[] buildExtraItemModels(RawYsmModel raw, GeometryDescription context, boolean mergeMultilineExpr) {
        List<ProjectileModelFiles> list = new ArrayList<>();
        for (RawYsmModel.RawSubEntity sub : raw.projectiles) {
            ProjectileModelFiles holder = buildSubEntityHolder(sub, context, 1, mergeMultilineExpr, "projectiles."+list.size());
            list.add(holder);
        }
        return list.toArray(new ProjectileModelFiles[0]);
    }

    private static VehicleModelFiles[] buildExtraEntityModels(RawYsmModel raw, GeometryDescription context, boolean mergeMultilineExpr) {
        List<VehicleModelFiles> list = new ArrayList<>();
        for (RawYsmModel.RawSubEntity sub : raw.vehicles) {
            VehicleModelFiles wrapper = buildSubEntityWrapper(sub, context, 1, mergeMultilineExpr, "vehicles."+list.size());
            list.add(wrapper);
        }
        return list.toArray(new VehicleModelFiles[0]);
    }

    private static ProjectileModelFiles buildSubEntityHolder(RawYsmModel.RawSubEntity sub, GeometryDescription context, int textureCount, boolean mergeMultilineExpr, String role) {
        OuterFileTexture texture = null;
        TranslucencyScanner subScanner = null;

        if (!sub.textures.isEmpty()) {
            List<BufferedImage> imgList = new ArrayList<>();
            for(RawYsmModel.RawTexture rt : sub.textures.values()) {
                BufferedImage img = decodeToImage(rt.data, rt.imageFormat, rt.width, rt.height);
                imgList.add(img);
                if (texture == null) {
                    texture = toTexture(rt.data, rt.imageFormat, rt.width, rt.height, img);
                }
            }
            if (sub.model != null) {
                subScanner = new TranslucencyScanner(imgList.toArray(new BufferedImage[0]), textureCount);
            }
        }

        GeoModel mesh = buildMesh(sub.model, context, textureCount, subScanner, false, role);

        Map<String, Animation> allAnimations = new LinkedHashMap<>();
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> entry : sub.animationFiles.entrySet()) {
            Map<String, Animation> fileAnims = buildAnimations(entry.getValue(), mergeMultilineExpr);
            allAnimations.putAll(fileAnims);
        }
        AnimationFile combinedAnim = new AnimationFile(allAnimations);

        Map<String, AnimationController> controllerMap = new LinkedHashMap<>();
        if (sub.animationControllerFiles != null) {
            for (RawYsmModel.RawAnimationControllerFile file : sub.animationControllerFiles) {
                if (file.controllers != null && !file.controllers.isEmpty()) {
                    controllerMap.putAll(buildControllers(file.controllers, mergeMultilineExpr));
                }
            }
        }
        AnimationControllerFile controllers = new AnimationControllerFile(controllerMap);

        String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0 ? sub.matchIds : new String[]{"unknown"};
        return new ProjectileModelFiles(matchIds, mesh, combinedAnim, controllers, texture);
    }

    private static VehicleModelFiles buildSubEntityWrapper(RawYsmModel.RawSubEntity sub, GeometryDescription context, int textureCount, boolean mergeMultilineExpr, String role) {
        OuterFileTexture texture = null;
        TranslucencyScanner subScanner = null;

        if (!sub.textures.isEmpty()) {
            List<BufferedImage> imgList = new ArrayList<>();
            for(RawYsmModel.RawTexture rt : sub.textures.values()) {
                BufferedImage img = decodeToImage(rt.data, rt.imageFormat, rt.width, rt.height);
                imgList.add(img);
                if (texture == null) {
                    texture = toTexture(rt.data, rt.imageFormat, rt.width, rt.height, img);
                }
            }
            if (sub.model != null) {
                subScanner = new TranslucencyScanner(imgList.toArray(new BufferedImage[0]), textureCount);
            }
        }

        GeoModel mesh = buildMesh(sub.model, context, textureCount, subScanner, false, role);

        Map<String, Animation> allAnimations = new LinkedHashMap<>();
        for (RawYsmModel.RawAnimationFile animFile : sub.animationFiles.values()) {
            Map<String, Animation> fileAnims = buildAnimations(animFile, mergeMultilineExpr);
            allAnimations.putAll(fileAnims);
        }
        AnimationFile combinedAnim = new AnimationFile(allAnimations);

        Map<String, AnimationController> controllerMap = new LinkedHashMap<>();
        if (sub.animationControllerFiles != null) {
            for (RawYsmModel.RawAnimationControllerFile file : sub.animationControllerFiles) {
                if (file.controllers != null && !file.controllers.isEmpty()) {
                    controllerMap.putAll(buildControllers(file.controllers, mergeMultilineExpr));
                }
            }
        }
        AnimationControllerFile controllers = new AnimationControllerFile(controllerMap);

        String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0 ? sub.matchIds : new String[]{"unknown"};
        return new VehicleModelFiles(matchIds, mesh, combinedAnim, controllers, texture);
    }

    private static Map<String, OuterFileTexture> buildExtraTextures(RawYsmModel raw) {
        Map<String, OuterFileTexture> result = new LinkedHashMap<>();
        for (RawYsmModel.RawImage img : raw.properties.backgroundImages) {
            if (img.name != null && !img.name.isEmpty()) {
                result.put(img.name, toTexture(img.data, img.format, img.width, img.height));
            }
        }
        return result;
    }

    public static List<IValue> parse(List<String> array, boolean mergeMultilineExpr) {
        List<IValue> values = new ArrayList<>();

        if (!mergeMultilineExpr) {
            for (String expr : array) values.add(parse(expr));
            return values;
        }

        try {
            StringBuilder parserText = new StringBuilder();

            for (int i = 0; i < array.size(); i++) {
                parserText.append(array.get(i));
                if (i < array.size() - 1) {
                    parserText.append("\n");
                }
            }

            values.add(parse(parserText.toString()));
        } catch (Throwable ex) {
            values.add(FloatValue.ZERO);
        }
        return values;
    }

    public static IValue parse(String str) {
        try {
            return parseMolang(str, false);
        } catch (Throwable ex) {
            return FloatValue.ZERO;
        }
    }

    private static IValue parseMolang(String expression, boolean script) {
        MolangParser parser = GeckoLibCache.getMolangParser();
        try {
            return parser.parseExpression(expression, script);
        } finally {
            GeckoLibCache.releaseParser(parser);
        }
    }

    private static String[] buildPath(String targetBone, Map<String, String> parentMap) {
        if (!parentMap.containsKey(targetBone)) {
            return new String[0];
        }
        List<String> path = new ArrayList<>();
        String current = targetBone;
        while (current != null && !current.isEmpty()) {
            path.add(current);
            current = parentMap.get(current);
        }
        Collections.reverse(path);
        return path.toArray(new String[0]);
    }

    private static String[][] buildBoneNameArrays(Map<String, String> parentMap) {
        String[][] arrays = new String[35][];

        // 模型骨骼大全
        String[] targetLocators = new String[]{
                "LeftHandLocator",
                "RightHandLocator",
                "ElytraLocator",
                "PistolLocator",
                "RifleLocator",
                "LeftWaistLocator",
                "RightWaistLocator",
                "LeftShoulderLocator",
                "RightShoulderLocator",
                "BladeLocator",
                "SheathLocator",
                "Head",
                "BackpackLocator",
                "LeftHandLocator2",
                "LeftHandLocator3",
                "LeftHandLocator4",
                "LeftHandLocator5",
                "LeftHandLocator6",
                "LeftHandLocator7",
                "LeftHandLocator8",
                "RightHandLocator2",
                "RightHandLocator3",
                "RightHandLocator4",
                "RightHandLocator5",
                "RightHandLocator6",
                "RightHandLocator7",
                "RightHandLocator8",
                "PassengerLocator",
                "PassengerLocator2",
                "PassengerLocator3",
                "PassengerLocator4",
                "PassengerLocator5",
                "PassengerLocator6",
                "PassengerLocator7",
                "PassengerLocator8"
        };

        for (int i = 0; i < arrays.length; i++) {
            if (targetLocators[i] != null && !targetLocators[i].isEmpty()) {
                arrays[i] = buildPath(targetLocators[i], parentMap);
            } else {
                arrays[i] = new String[0];
            }
        }

        return arrays;
    }

    public static GeoModel buildMesh(GeoBone[] bones, Map<String, String> parentMap, GeometryDescription context, boolean[] translucencyArray) {
        String[][] boneNameArrays = buildBoneNameArrays(parentMap);
        boolean[] flags = new boolean[]{parentMap.containsKey("LeftArm"), parentMap.containsKey("RightArm"), parentMap.containsKey("Background")};
        return new GeoModel(bones, boneNameArrays, flags, context, translucencyArray);
    }

    public static OrderedStringMap<String, OuterFileTexture> buildTextureMap(Map<String, OuterFileTexture> textures) {
        if (textures.isEmpty()) {
            return new OrderedStringMap<>(new String[0], new OuterFileTexture[0]);
        }
        String[] keys = textures.keySet().toArray(new String[0]);
        OuterFileTexture[] values = textures.values().toArray(new OuterFileTexture[0]);
        return new OrderedStringMap<>(keys, values);
    }

    public static GeometryDescription buildContext(RawYsmModel.RawGeometry model) {
        return new GeometryDescription(
                model.identifier,
                model.textureWidth, // default texture width ratio
                model.textureHeight, // default texture height ratio
                model.visibleBoundsWidth, // offset X
                model.visibleBoundsHeight, // offset Y
                IntStream.range(0, model.visibleBoundsOffset.length)
                        .mapToDouble(i -> model.visibleBoundsOffset[i])
                        .toArray()
        );
    }

    private static boolean isNegativeSizedFace(RawYsmModel.RawFace f) {
        float[] p0 = f.positions[0];
        float[] p1 = f.positions[1];
        float[] p2 = f.positions[2];

        float ax = p1[0] - p0[0];
        float ay = p1[1] - p0[1];
        float az = p1[2] - p0[2];

        float bx = p2[0] - p0[0];
        float by = p2[1] - p0[1];
        float bz = p2[2] - p0[2];

        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        float len2 = nx * nx + ny * ny + nz * nz;
        if (len2 <= 1e-10f) {
            float[] p3 = f.positions[3];

            bx = p3[0] - p0[0];
            by = p3[1] - p0[1];
            bz = p3[2] - p0[2];

            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;

            len2 = nx * nx + ny * ny + nz * nz;
            if (len2 <= 1e-10f) {
                return false;
            }
        }

        float dot = nx * f.normal[0] + ny * f.normal[1] + nz * f.normal[2];
        return dot < -1e-5f;
    }
}
