package com.elfmcys.yesstevemodel.model;

import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.*;
import com.elfmcys.yesstevemodel.molang.MolangEngine;
import com.elfmcys.yesstevemodel.molang.runtime.*;
import com.elfmcys.yesstevemodel.molang.runtime.binding.*;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.builtin.MathBinding;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Headless software rasterizer. No Minecraft client or OpenGL classes. */
public final class ModelThumbnailRenderer {
    public static final int VERSION = 6, WIDTH = 52, HEIGHT = 70, MAX_BYTES = 65536;
    public static boolean validHeader(byte[] png) {
        if (png.length < 24 || png.length > MAX_BYTES) return false;
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(png);
        return header.getLong() == 0x89504e470d0a1a0aL && header.getInt(16) == WIDTH && header.getInt(20) == HEIGHT;
    }
    private record Triangle(Vector3f[] p, float[] u, float[] v, float light) {}

    public static byte[] render(RawYsmModel raw) throws IOException {
        RawGeometry geo = raw.mainEntity.mainModel;
        if (geo == null || geo.bones.size() > 16384) throw new IOException("Invalid thumbnail geometry");
        RawTexture texture = raw.mainEntity.textures.get(raw.properties.defaultTexture);
        if (texture == null) texture = raw.mainEntity.textures.values().stream().findFirst().orElseThrow(() -> new IOException("No texture"));
        BufferedImage tex;
        try { tex = decode(texture); } catch (Exception ex) { throw new IOException("Cannot decode thumbnail texture",ex); }
        if (tex == null) throw new IOException("Cannot decode default texture");
        var poses = finalPose(raw);
        Map<String, RawBone> bones = new HashMap<>();
        for (RawBone bone : geo.bones) bones.put(bone.name, bone);
        Map<String, Matrix4f> matrices = new HashMap<>();
        List<Triangle> triangles = new ArrayList<>();
        // Same 52x70 logical preview viewport, 30 GUI pixels per model unit.
        Matrix4f camera = new Matrix4f().rotateX(raw.properties.disablePreviewRotation ? 0 : (float)Math.toRadians(-10))
                .rotateY(raw.properties.disablePreviewRotation ? 0 : (float)Math.toRadians(-20))
                .scale(raw.properties.heightScale, raw.properties.widthScale, raw.properties.heightScale);
        for (RawBone bone : geo.bones) {
            Matrix4f matrix = new Matrix4f(camera).mul(matrix(bone, bones, poses, matrices, new HashSet<>()));
            for (RawCube cube : bone.cubes) for (RawFace face : cube.faces) {
                Vector3f[] points = new Vector3f[4];
                for (int i = 0; i < 4; i++) {
                    var p = face.positions[i];
                    points[i] = matrix.transformPosition(new Vector3f(p[0], p[1], p[2]));
                    // Raw face positions are already in model units; pivots and animated offsets are in pixels.
                    points[i].set(WIDTH / 2f - points[i].x * 30f,
                            65f + (raw.properties.disablePreviewRotation ? 5.5f : 0) - points[i].y * 30f,
                            -points[i].z);
                }
                // Hidden (zero-scale), edge-on and offscreen faces consume no raster budget.
                // Check before allocating retained triangles, especially for dense alternate outfits.
                if(Arrays.stream(points).anyMatch(p -> !p.isFinite())) continue;
                float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
                for(Vector3f point:points) { minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minY=Math.min(minY,point.y);maxY=Math.max(maxY,point.y); }
                if(maxX<=0 || minX>=WIDTH || maxY<=0 || minY>=HEIGHT) continue;
                // Use authored normals in the preview pose, before projection/pixel scaling.
                Vector3f normal = new org.joml.Matrix3f(matrix).invert().transpose()
                        .transform(new Vector3f(face.normal)).normalize();
                normal.x = -normal.x;
                normal.y = -normal.y; // Preview PoseStack rotates 180 degrees around Z.
                float light = inventoryLight(normal);
                for (int[] idx : new int[][]{{0,1,2},{0,2,3}}) {
                    float area=edge(points[idx[0]],points[idx[1]],points[idx[2]].x,points[idx[2]].y);
                    if(!Float.isFinite(area) || Math.abs(area)<.00001f) continue;
                    triangles.add(new Triangle(new Vector3f[]{points[idx[0]],points[idx[1]],points[idx[2]]},
                            new float[]{face.u[idx[0]],face.u[idx[1]],face.u[idx[2]]},
                            new float[]{face.v[idx[0]],face.v[idx[1]],face.v[idx[2]]}, light));
                }
                if (triangles.size() > 200000) throw new IOException("Thumbnail visible triangle limit exceeded: " + triangles.size());
            }
        }
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        compositeGuiImage(raw, image, "gui_background");
        float[] depth = new float[WIDTH * HEIGHT];
        Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        // Opaque pass writes depth; translucent pass blends back-to-front against opaque depth.
        for (Triangle t : triangles) raster(t, tex, image, depth, false, raw.properties.allCutout);
        triangles.sort(Comparator.comparingDouble(t -> (t.p[0].z + t.p[1].z + t.p[2].z) / 3));
        for (Triangle t : triangles) raster(t, tex, image, depth, true, raw.properties.allCutout);
        compositeGuiImage(raw, image, "gui_foreground");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        if (out.size() > MAX_BYTES) throw new IOException("Thumbnail exceeds packet limit");
        return out.toByteArray();
    }

    private static final Vector3f LIGHT_0 = new Vector3f(.2f, -1, -1).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-.2f, -1, 0).normalize();

    // Lighting.setupForEntityInInventory + minecraft_mix_light (also used by bone_skin.vsh).
    static float inventoryLight(Vector3f normal) {
        if (!normal.isFinite()) return .4f;
        return Math.min(1, .4f + .6f * (Math.max(0, normal.dot(LIGHT_0)) + Math.max(0, normal.dot(LIGHT_1))));
    }

    private static void compositeGuiImage(RawYsmModel raw, BufferedImage image, String name) throws IOException {
        RawImage source = raw.properties.backgroundImages.stream().filter(i -> name.equals(i.name)).reduce((a,b) -> b).orElse(null);
        if (source == null) return;
        RawTexture texture = new RawTexture();
        texture.width = source.width; texture.height = source.height;
        texture.imageFormat = source.format; texture.data = source.data;
        try {
            BufferedImage decoded = decode(texture);
            if (decoded == null) throw new IOException("Cannot decode " + name);
            java.awt.Graphics2D graphics = image.createGraphics();
            try {
                // The live button stretches GUI layers over 52x90; our image crops its top 52x70.
                graphics.drawImage(decoded, 0, 0, WIDTH, 90, null);
            } finally { graphics.dispose(); }
        } catch (Exception ex) { throw new IOException("Cannot decode thumbnail " + name, ex); }
    }

    private static BufferedImage decode(RawTexture t) throws Exception {
        if (t.width < 1 || t.height < 1 || (long)t.width * t.height > 16777216) throw new IOException("Texture dimensions exceed limit");
        if (t.imageFormat == -1) {
            if ((long)t.width * t.height * 4 > t.data.length) throw new IOException("Truncated RGBA texture");
            BufferedImage image = new BufferedImage(t.width, t.height, BufferedImage.TYPE_INT_ARGB);
            for (int y=0; y<t.height; y++) for (int x=0; x<t.width; x++) {
                int i=(y*t.width+x)*4;
                image.setRGB(x,y,(t.data[i+3]&255)<<24 | (t.data[i]&255)<<16 | (t.data[i+1]&255)<<8 | (t.data[i+2]&255));
            }
            return image;
        }
        if (t.imageFormat == 4) return new rip.ysm.imagestream.webp.WebpDecoder().read(t.data);
        if (t.imageFormat == 5) return new rip.ysm.imagestream.avif.AvifDecoder().read(t.data);
        return ImageIO.read(new ByteArrayInputStream(t.data));
    }

    /** Resolve UI controllers and parallel layers in the same order as the animated preview. */
    public static Map<String, float[][]> finalPose(RawYsmModel raw) throws IOException {
        PreviewPose preview=new PreviewPose(raw);
        preview.event("player_init");
        preview.parallel("pre_parallel",false);
        preview.apply(raw.properties.previewAnimation,1,false);
        preview.parallel("parallel",true);
        return preview.poses;
    }

    private static final class PreviewPose {
        final RawYsmModel raw;
        final Map<String,RawAnimation> animations=new LinkedHashMap<>();
        final Map<String,RawAnimationController> controllers=new LinkedHashMap<>();
        final Map<String,float[][]> poses=new HashMap<>();
        final Map<String,String> functions=new HashMap<>();
        final Map<String,List<String>> events=new HashMap<>();
        final MolangEngine engine;
        final ExpressionEvaluator<Object> evaluator=ExpressionEvaluator.evaluator(null);
        float time;
        int calls;
        PreviewPose(RawYsmModel raw) {
            this.raw=raw;
            for(var file:raw.mainEntity.animationFiles.values()) if(file.animType==1) animations.putAll(file.animations);
            for(var file:raw.mainEntity.animationControllerFiles) for(var entry:file.controllers.entrySet())
                controllers.put(entry.getValue().animationName==null ? entry.getKey() : entry.getValue().animationName,entry.getValue());
            for(var entry:raw.functionFiles.entrySet()) {
                String name=entry.getKey().replaceFirst("\\.molang$","");
                String[] parts=name.split("@",2);
                String script=new String(entry.getValue().data,java.nio.charset.StandardCharsets.UTF_8);
                functions.put(parts[0],script);
                if(parts.length==2) events.computeIfAbsent(parts[1],k->new ArrayList<>()).add(script);
            }
            ObjectBinding vars=variables(), temp=variables(), context=variables();
            ObjectBinding query=key -> (Variable)ctx -> switch(key) {
                case "anim_time", "life_time" -> time;
                case "is_in_ui", "is_alive", "is_on_ground", "all_animations_finished", "any_animation_finished" -> 1f;
                case "health", "max_health" -> 20f;
                case "delta_time" -> 1f/60;
                default -> 0f;
            };
            ObjectBinding ysm=key -> switch(key) {
                case "texture_name" -> (Variable)ctx -> raw.properties.defaultTexture.isEmpty()
                        ? raw.mainEntity.textures.keySet().stream().findFirst().orElse("") : raw.properties.defaultTexture;
                case "is_player", "rendering_in_inventory" -> 1f;
                case "time_delta" -> 1f/60;
                case "entity_type" -> new com.elfmcys.yesstevemodel.molang.parser.ast.StringExpression("player");
                case "first_order", "second_order" -> (Function)(ctx,args) -> args.size()>1 ? args.getValue(ctx,1) : 0f;
                case "bone_rot", "bone_pos", "bone_scale", "bone_pivot_abs" -> (Function)(ctx,args) -> boneValue(key,args.getAsString(ctx,0));
                case "keyboard", "mouse", "effect_level", "equipped_enchantment_level", "relative_block_name_any", "mod_version",
                     "play_sound", "stop_sound", "stop_all_sounds", "particle", "abs_particle", "sync", "defer" -> Function.NOOP;
                default -> 0f;
            };
            ObjectBinding fn=key -> (Function)(ctx,args) -> {
                try { return script(functions.get(key)); } catch(IOException ex) { throw new IllegalArgumentException(ex); }
            };
            engine=MolangEngine.fromCustomBinding(key -> switch(key) {
                case "math" -> MathBinding.INSTANCE;
                case "query", "q" -> query;
                case "variable", "v" -> vars;
                case "temp", "t" -> temp;
                case "context", "c", "ctrl" -> context;
                case "ysm" -> ysm;
                case "fn" -> fn;
                case "loop" -> StandardBindings.LOOP_FUNC;
                case "for_each" -> StandardBindings.FOR_EACH_FUNC;
                default -> null;
            });
        }
        // Struct is a runtime value, not a parser binding. Each name needs an assignable variable.
        private static ObjectBinding variables() {
            Map<String,Object> values=new HashMap<>();
            return name -> new AssignableVariable() {
                public Object evaluate(ExecutionContext<?> ctx) { return values.get(name); }
                public void assign(ExecutionContext<?> ctx,Object value) { values.put(name,value); }
            };
        }
        private Struct boneValue(String channel,String name) {
            float[] value=new float[3];
            if(channel.equals("bone_scale")) Arrays.fill(value,1);
            for(RawBone bone:raw.mainEntity.mainModel.bones) if(Objects.equals(bone.name,name)) {
                if(channel.equals("bone_pivot_abs")) value=bone.pivot.clone();
                else {
                    int index=channel.equals("bone_rot") ? 0 : channel.equals("bone_pos") ? 1 : 2;
                    if(poses.containsKey(name)) value=poses.get(name)[index].clone();
                    if(index==0) for(int i=0;i<3;i++) value[i]+=(float)Math.toDegrees(bone.rotation[i])*(i==2?1:-1);
                }
                break;
            }
            HashMapStruct result=new HashMapStruct();
            for(int i=0;i<3;i++) result.putProperty(com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool.computeIfAbsent(new String[]{"x","y","z"}[i]),value[i]);
            return result;
        }
        Object script(String script) throws IOException {
            if(script==null || script.isBlank()) return 0f;
            if(++calls>512) { calls--; throw new IOException("Preview script recursion limit"); }
            try { return evaluator.evalAll(engine.parse(checkedExpression(script)),true); }
            catch(com.elfmcys.yesstevemodel.molang.parser.ParseException ex) {
                // The live mapper also ignores invalid legacy timeline statements (for example "[;").
                net.everla.reimaginedysm.core.YesSteveModel.LOGGER.debug("Invalid thumbnail timeline statement: {}",script,ex);
                return 0f;
            }
            finally { calls--; }
        }
        void scripts(List<String> scripts) throws IOException {
            if(raw.properties.mergeMultilineExpr) script(String.join("\n",scripts));
            else for(String expression:scripts) script(expression);
        }
        void event(String name) throws IOException {
            for(String script:events.getOrDefault(name,List.of())) script(script);
        }
        void parallel(String prefix,boolean additive) throws IOException {
            SortedMap<String,String> slots=new TreeMap<>();
            for(int i=0;i<8;i++) if(animations.containsKey(prefix+i)) slots.put("player."+prefix+"_"+i,prefix+i);
            for(String name:controllers.keySet()) if(name.startsWith("player."+prefix+"_")) slots.putIfAbsent(name,null);
            for(String name:events.keySet()) if(name.startsWith("player_ctrl_"+prefix+"_")) slots.putIfAbsent(name.replace("_ctrl_","."),null);
            for(var entry:slots.entrySet()) {
                event(entry.getKey().replace("player.","player_ctrl_"));
                RawAnimationController controller=controllers.get(entry.getKey());
                if(controller==null) apply(entry.getValue(),1,additive);
                else controller(controller,entry.getValue(),additive);
            }
        }
        void controller(RawAnimationController controller,String builtin,boolean additive) throws IOException {
            Map<String,RawControllerState> states=new HashMap<>();
            for(var state:controller.states) states.put(state.name,state);
            RawControllerState state=states.get(controller.initialState==null || controller.initialState.isEmpty()?"default":controller.initialState);
            Set<String> visited=new HashSet<>();
            while(state!=null && visited.add(state.name)) {
                scripts(state.onEntry);
                RawControllerState next=null;
                for(var transition:state.transitions) if(value(transition.getValue(),engine,evaluator,0)!=0) {
                    next=states.get(transition.getKey()); break;
                }
                if(next==null || visited.contains(next.name)) break;
                scripts(state.onExit);
                state=next;
            }
            if(state!=null) for(var animation:state.animations) apply(animation.getKey().equals("[builtin]")?builtin:animation.getKey(),
                    animation.getValue().isEmpty()?1:value(animation.getValue(),engine,evaluator,1),additive);
        }
        void apply(String name,float controllerWeight,boolean additive) throws IOException {
            RawAnimation animation=animations.get(name);
            if(animation==null) return;
            // Unspecified lengths are infinite in YSM. Use half the last authored timestamp, never Infinity in Molang.
            time=Float.isFinite(animation.length) ? animation.length : 0;
            if(!Float.isFinite(animation.length)) {
                for(var bone:animation.boneAnimations) for(var channel:List.of(bone.rotation,bone.position,bone.scale))
                    for(var key:channel) if(Float.isFinite(key.timestamp)) time=Math.max(time,key.timestamp);
                for(var timeline:animation.timelineEvents) if(Float.isFinite(timeline.timestamp)) time=Math.max(time,timeline.timestamp);
            }
            time *= .5f;
            for(var timeline:animation.timelineEvents) if(timeline.timestamp<=time) scripts(timeline.events);
            float weight=controllerWeight*value(animation.blendWeight,engine,evaluator,1);
            for(RawBoneAnimation bone:animation.boneAnimations) {
                float[][] pose=poses.computeIfAbsent(bone.boneName,k->new float[][]{new float[3],new float[3],{1,1,1}});
                List<List<RawKeyframe>> channels=List.of(bone.rotation,bone.position,bone.scale);
                for(int channel=0;channel<3;channel++) {
                    if(channels.get(channel).isEmpty()) continue;
                    float[] sampled=sample(channels.get(channel),time,channel==2?1:0,engine,evaluator);
                    for(int axis=0;axis<3;axis++) {
                        if(additive && channel==0) pose[channel][axis]+=sampled[axis]*weight;
                        else pose[channel][axis]+=(sampled[axis]-pose[channel][axis])*weight;
                    }
                }
            }
        }
    }

    private static float[] sample(List<RawKeyframe> frames, float time, float fallback,
                                  MolangEngine engine, ExpressionEvaluator<Object> evaluator) throws IOException {
        if (frames.isEmpty()) return new float[]{fallback,fallback,fallback};
        RawKeyframe left=frames.get(0), right=left;
        for (RawKeyframe f : frames) {
            if (f.timestamp <= time) left=f;
            if (f.timestamp >= time) { right=f; break; }
            right=f;
        }
        float blend = right.timestamp > left.timestamp ? Math.max(0, Math.min(1,(time-left.timestamp)/(right.timestamp-left.timestamp))) : 0;
        float[] v=new float[3];
        for (int i=0;i<3;i++) {
            float a=value(left.postData[i], engine, evaluator, fallback);
            float z=right==left ? a : value((right.hasPreData ? right.preData : right.postData)[i], engine, evaluator, fallback);
            v[i]=a+(z-a)*blend;
        }
        return v;
    }
    private static String checkedExpression(String text) throws IOException {
        if(text.length()>262144) throw new IOException("Preview expression exceeds limit");
        return text;
    }
    private static float value(Object value, MolangEngine engine, ExpressionEvaluator<Object> evaluator, float fallback) throws IOException {
        if (value == null) return fallback;
        try {
            float number = value instanceof Number n ? n.floatValue() : ValueConversions.asFloat(evaluator.evalAll(engine.parse(checkedExpression(value.toString())), true));
            if (!Float.isFinite(number)) {
                net.everla.reimaginedysm.core.YesSteveModel.LOGGER.debug("Non-finite thumbnail channel: {}",value);
                return fallback;
            }
            return number;
        } catch (com.elfmcys.yesstevemodel.molang.parser.ParseException ex) {
            // Match YSMClientMapper.parse: a malformed legacy channel does not discard the model.
            net.everla.reimaginedysm.core.YesSteveModel.LOGGER.debug("Invalid thumbnail channel: {}",value,ex);
            return fallback;
        } catch (Exception ex) { throw new IOException("Cannot evaluate preview animation: " + value, ex); }
    }

    private static Matrix4f matrix(RawBone bone, Map<String,RawBone> bones, Map<String,float[][]> poses,
                                   Map<String,Matrix4f> cache, Set<String> visiting) throws IOException {
        if (cache.containsKey(bone.name)) return cache.get(bone.name);
        if (visiting.size() >= 256 || !visiting.add(bone.name)) throw new IOException("Cyclic/deep bone hierarchy");
        RawBone parent=bones.get(bone.parentName);
        Matrix4f m=parent == null ? new Matrix4f() : new Matrix4f(matrix(parent,bones,poses,cache,visiting));
        float[][] pose=poses.get(bone.name);
        float[] r=bone.rotation.clone(), pos=new float[3], scale={1,1,1};
        if (pose != null) {
            // Same Bedrock animation sign convention as RawBoneKeyFrame.init.
            r[0]-=(float)Math.toRadians(pose[0][0]); r[1]-=(float)Math.toRadians(pose[0][1]); r[2]+=(float)Math.toRadians(pose[0][2]);
            pos=pose[1]; scale=pose[2];
        }
        float[] p=bone.pivot;
        m.translate(-pos[0]/16f,pos[1]/16f,pos[2]/16f).translate(p[0]/16f,p[1]/16f,p[2]/16f)
                .rotateZ(r[2]).rotateY(r[1]).rotateX(r[0]).scale(scale[0],scale[1],scale[2]).translate(-p[0]/16f,-p[1]/16f,-p[2]/16f);
        visiting.remove(bone.name); cache.put(bone.name,m); return m;
    }
    private static boolean topLeft(Vector3f a, Vector3f b, float area) {
        if(area<0) { Vector3f t=a;a=b;b=t; }
        return b.y>a.y || (b.y==a.y && b.x<a.x);
    }
    private static float edge(Vector3f a, Vector3f b, float x, float y) { return (x-a.x)*(b.y-a.y)-(y-a.y)*(b.x-a.x); }
    private static void raster(Triangle t, BufferedImage tex, BufferedImage image, float[] depth, boolean translucent, boolean cutout) {
        Vector3f a=t.p[0],b=t.p[1],c=t.p[2];
        float area=edge(a,b,c.x,c.y);
        if (!Float.isFinite(area) || Math.abs(area)<.00001f) return;
        int x0=Math.max(0,(int)Math.floor(Math.min(a.x,Math.min(b.x,c.x)))), x1=Math.min(WIDTH-1,(int)Math.ceil(Math.max(a.x,Math.max(b.x,c.x))));
        int y0=Math.max(0,(int)Math.floor(Math.min(a.y,Math.min(b.y,c.y)))), y1=Math.min(HEIGHT-1,(int)Math.ceil(Math.max(a.y,Math.max(b.y,c.y))));
        for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
            float w0=edge(b,c,x+.5f,y+.5f)/area,w1=edge(c,a,x+.5f,y+.5f)/area,w2=1-w0-w1;
            if(w0<0 || w1<0 || w2<0) continue;
            if ((w0==0 && !topLeft(b,c,area)) || (w1==0 && !topLeft(c,a,area)) || (w2==0 && !topLeft(a,b,area))) continue;
            float z=w0*a.z+w1*b.z+w2*c.z;
            int i=y*WIDTH+x;
            if(z<depth[i]) continue;
            int tx=Math.max(0,Math.min(tex.getWidth()-1,(int)((w0*t.u[0]+w1*t.u[1]+w2*t.u[2])*tex.getWidth())));
            int ty=Math.max(0,Math.min(tex.getHeight()-1,(int)((w0*t.v[0]+w1*t.v[1]+w2*t.v[2])*tex.getHeight())));
            int color=tex.getRGB(tx,ty), alpha=color>>>24;
            if(cutout) alpha=alpha<26 ? 0 : 255;
            if(alpha==0 || (translucent ? alpha==255 : alpha!=255)) continue;
            int r=(int)(((color>>16)&255)*t.light), g=(int)(((color>>8)&255)*t.light), bl=(int)((color&255)*t.light);
            if(!translucent) { image.setRGB(x,y,0xFF000000|r<<16|g<<8|bl); depth[i]=z; }
            else {
                int old=image.getRGB(x,y), oa=old>>>24, outA=alpha+oa*(255-alpha)/255;
                if(outA==0) continue;
                int nr=(r*alpha+((old>>16)&255)*oa*(255-alpha)/255)/outA;
                int ng=(g*alpha+((old>>8)&255)*oa*(255-alpha)/255)/outA;
                int nb=(bl*alpha+(old&255)*oa*(255-alpha)/255)/outA;
                image.setRGB(x,y,outA<<24|nr<<16|ng<<8|nb);
            }
        }
    }
}
