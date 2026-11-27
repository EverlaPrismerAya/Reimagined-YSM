package net.everla.ysmJsLib;

import com.google.gson.*;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

/** Render-thread-only texture arrays; immutable model resources are shared by digest. */
public final class CosmicTextures {
    private record Frame(int index,int duration) {}
    private record Sprite(ByteBuffer pixels,List<Frame> frames,int duration) {}
    private static final class Texture {
        int id,size;final List<Sprite> sprites=new ArrayList<>();int[] last;
        void close(){if(id!=0)GL11.glDeleteTextures(id);sprites.forEach(s->MemoryUtil.memFree(s.pixels));sprites.clear();}
    }
    private static final LinkedHashMap<String,Texture> CACHE=new LinkedHashMap<>(16,.75f,true);
    private static final Set<String> FAILED=new HashSet<>();
    public static int bind(ScriptModel model,String effect,int ticks) {
        if(model==null||effect==null||effect.isEmpty())return 0;
        String key=model.bundle.digest+":"+effect;if(FAILED.contains(key))return 0;
        Texture texture=CACHE.get(key);
        if(texture==null)try {
            texture=load(model,effect);CACHE.put(key,texture);
            if(CACHE.size()>16){var old=CACHE.entrySet().iterator();var e=old.next();e.getValue().close();old.remove();}
        }catch(Exception e){FAILED.add(key);org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Cosmic {} uses default textures: {}",key,e.toString());return 0;}
        GlStateManager._activeTexture(GL13.GL_TEXTURE0+3);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,texture.id);
        for(int i=0;i<texture.sprites.size();i++) {
            Sprite s=texture.sprites.get(i);int time=Math.floorMod(ticks,s.duration),frame=0;
            for(Frame f:s.frames){if(time<f.duration){frame=f.index;break;}time-=f.duration;}
            if(texture.last[i]!=frame){int bytes=texture.size*texture.size*4;ByteBuffer data=s.pixels.duplicate();data.position(frame*bytes).limit((frame+1)*bytes);
                GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY,0,0,0,i,texture.size,texture.size,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data.slice());texture.last[i]=frame;}
        }
        return texture.sprites.size();
    }
    private static Texture load(ScriptModel model,String effect) throws Exception {
        JsonObject config=model.cosmic.get(effect);if(config==null)throw new IllegalArgumentException("Unknown effect");
        JsonArray list=config.getAsJsonArray("textures");if(list==null||list.isEmpty()||list.size()>64)throw new IllegalArgumentException("Expected 1..64 textures");
        Texture result=new Texture();long total=0;
        try {
            for(var value:list) {
                String path="rysm/cosmic/"+effect+"/"+value.getAsString();ScriptBundle.checkPath(path);
                byte[] png=model.bundle.files.get(path);if(png==null||!path.endsWith(".png"))throw new IllegalArgumentException("Missing PNG "+path);
                java.awt.image.BufferedImage image;
                try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("Invalid PNG");var reader=readers.next();
                    try {reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
                        total+=(long)width*height*4;
                        if(width<1||width>512||height<width||height%width!=0||height/width>256||total>32*1024*1024)throw new IllegalArgumentException("Cosmic decoded image limit");
                        if(result.size!=0&&width!=result.size)throw new IllegalArgumentException("Frame sizes differ");result.size=width;image=reader.read(0);
                    }finally{reader.dispose();}
                }
                int count=image.getHeight()/result.size;var sequence=new ArrayList<Frame>();
                if(model.bundle.files.containsKey(path+".mcmeta")) {
                    var animation=JsonParser.parseString(model.bundle.text(path+".mcmeta")).getAsJsonObject().getAsJsonObject("animation");
                    int duration=animation.has("frametime")?animation.get("frametime").getAsInt():1;
                    if(duration<1||duration>12000)throw new IllegalArgumentException("Invalid frametime");
                    if(animation.has("frames")) {
                        var frames=animation.getAsJsonArray("frames");if(frames.isEmpty()||frames.size()>4096)throw new IllegalArgumentException("Invalid frames");
                        for(var f:frames){int index=f.isJsonObject()?f.getAsJsonObject().get("index").getAsInt():f.getAsInt();int time=f.isJsonObject()&&f.getAsJsonObject().has("time")?f.getAsJsonObject().get("time").getAsInt():duration;
                            if(index<0||index>=count||time<1||time>12000)throw new IllegalArgumentException("Invalid frame");sequence.add(new Frame(index,time));}
                    }else for(int f=0;f<count;f++)sequence.add(new Frame(f,duration));
                }else sequence.add(new Frame(0,1));
                ByteBuffer pixels=MemoryUtil.memAlloc(image.getWidth()*image.getHeight()*4);
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int c=image.getRGB(x,y);pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>>24));}pixels.flip();
                result.sprites.add(new Sprite(pixels,List.copyOf(sequence),sequence.stream().mapToInt(Frame::duration).sum()));
            }
            result.id=GL11.glGenTextures();GlStateManager._activeTexture(GL13.GL_TEXTURE0+3);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,result.id);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY,0,GL11.GL_RGBA8,result.size,result.size,result.sprites.size(),0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            result.last=new int[result.sprites.size()];Arrays.fill(result.last,-1);return result;
        }catch(Throwable e){result.close();throw e;}
    }
    public static void clear(){CACHE.values().forEach(Texture::close);CACHE.clear();FAILED.clear();}
}
