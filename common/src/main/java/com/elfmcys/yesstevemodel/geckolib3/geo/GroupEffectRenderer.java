package com.elfmcys.yesstevemodel.geckolib3.geo;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.everla.reimaginedysm.gpu.GroupStyle;
import net.everla.reimaginedysm.gpu.ShaderUtil;
import net.everla.reimaginedysm.gpu.SurfaceUnion;
import net.everla.reimaginedysm.gpu.GpuCapability;
import net.everla.reimaginedysm.gpu.GpuMesh;
import net.everla.reimaginedysm.gpu.GpuPose;
import net.everla.reimaginedysm.gpu.GpuRenderPath;
import net.everla.reimaginedysm.gpu.GroupGpuMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.util.*;

/** One transformed surface stream for preview, native and GPU entry points. */
public final class GroupEffectRenderer {
    private static int surface, cosmic, vao, vbo, stars;
    private static int material, materialCosmic, atlas, atlasFbo, atlasSize, entityVao, entityVbo;
    private static final Matrix4f entityIdentity=new Matrix4f();
    private static int currentProgram;
    private static int skinSurface,skinCosmic,skinMaterial,entityCompute;
    private static final Map<Integer,Map<String,Integer>> uniformLocations=new HashMap<>();
    private static boolean failed;
    private static final List<int[]> frames=new ArrayList<>();
    private static final List<ByteBuffer> pixels=new ArrayList<>();
    private static final int[] lastFrames=new int[10];
    private record Face(GeoModel.BakedQuad quad,List<float[]> polygon) {}
    private record Solid(int bone, GeoModel.BakedCube source,List<Face> faces,
                         List<SurfaceUnion.Plane> planes,float[] bounds) {}
    private record Batch(int bone,List<float[]> triangles,int first) {
        Batch(int bone,List<float[]> triangles) { this(bone,triangles,0); }
    }

    private static boolean compile() {
        if(failed) return false;
        if(surface!=0) return true;
        try {
            surface=program("/group_surface.fsh");cosmic=program("/group_cosmic.fsh");
            vao=GL30.glGenVertexArrays();vbo=GL15.glGenBuffers();
            return true;
        } catch(Exception ex) {
            failed=true;YesSteveModel.LOGGER.error("Cannot initialize group effects",ex);return false;
        }
    }
    private static int program(String fragment) throws Exception {
        return ShaderUtil.linkProgramWith(p->{
            GL20.glBindAttribLocation(p,0,"Position");GL20.glBindAttribLocation(p,1,"UV0");
            GL20.glBindAttribLocation(p,2,"Color");
            GL20.glBindAttribLocation(p,3,"Normal");
            GL30.glBindFragDataLocation(p,0,"fragColor");
        },ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_effect.vsh"),
                ShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER,fragment));
    }

    public static boolean render(PoseStack.Pose pose,GeoModel model,float[] params,float[] state,
                                 int textureIndex,int part,int light,int overlay,float r,float g,float b,float alpha,
                                 ResourceLocation texture,boolean preview) {
        return render(pose,model,params,state,textureIndex,part,light,overlay,r,g,b,alpha,texture,preview,false);
    }
    private static boolean render(PoseStack.Pose pose,GeoModel model,float[] params,float[] state,
                                  int textureIndex,int part,int light,int overlay,float r,float g,float b,float alpha,
                                  ResourceLocation texture,boolean preview,boolean dynamicOnly) {
        if(model.bakedBones==null || !compile()) return false;
        if(!dynamicOnly && GpuCapability.isAvailable() && com.elfmcys.yesstevemodel.NativeLibLoader.isLoaded()) {
            try(var saved=new DrawState();var compute=new ShaderUtil.SavedComputeState(3)) {
                if(renderGpu(pose,model,params,textureIndex,part,light,overlay,r,g,b,alpha,texture,preview)) {
                    if(model.hasFacingCameraBones || model.hasFrameAnimations) render(pose,model,params,state,textureIndex,part,light,overlay,r,g,b,alpha,texture,preview,true);
                    return true;
                }
            }
        }
        var mc=Minecraft.getInstance();
        var transforms=new Matrix4f[model.bakedBones.size()];
        var visible=new boolean[transforms.length];
        boolean gpuPose=!dynamicOnly && !preview && net.everla.ysmPhysLib.GpuPhysics.readRenderMatrices(params,pose.pose(),light,transforms,visible);
        if(!gpuPose) params=net.everla.ysmPhysLib.PlayerPhysics.hideEndpoints(model,params);
        Matrix4f camera=FacingCameraTransform.previewToCamera(pose.pose(),RenderSystem.getProjectionMatrix().m11());
        if(model.hasFacingCameraBones && net.everla.ysmPhysLib.WorldPhysicsContext.allows(preview)) {
            camera=net.everla.ysmPhysLib.WorldPhysicsContext.modelToWorld(pose.pose());
            new Matrix4f().rotation(new org.joml.Quaternionf(mc.getEntityRenderDispatcher().cameraOrientation()).conjugate()).mul(camera,camera);
        }
        var solids=new ArrayList<Solid>();
        for(int i=0;i<transforms.length;i++) {
            var bone=model.bakedBones.get(i);
            if(dynamicOnly && !bone.dynamicGeometry()) continue;
            Matrix4f local=gpuPose?transforms[i]:NativeModelRenderer.calculateBoneMatrix(i,model.bakedBones,params,transforms,visible,new Matrix4f(),state,camera);
            if(!visible[i] || params[i*12+9]!=0 || (part!=0 && bone.partMask!=part && bone.partMask!=3)) continue;
            Matrix4f matrix=gpuPose?new Matrix4f(local):new Matrix4f(pose.pose()).mul(local);
            Matrix3f normal=matrix.normal(new Matrix3f());
            float frameV=NativeModelRenderer.frameOffset(bone);
            for(var cube:bone.cubes) {
                var faces=new ArrayList<Face>();
                float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
                Vector3f center=new Vector3f();int count=0;
                for(var q:cube.quads) {
                    var poly=new ArrayList<float[]>();
                    Vector3f n=new Vector3f(q.normal).mul(normal).normalize();
                    for(int v=0;v<4;v++) {
                        Vector4f p=new Vector4f(q.positions[v*3],q.positions[v*3+1],q.positions[v*3+2],1).mul(matrix);
                        poly.add(new float[]{p.x,p.y,p.z,q.uvs[v*2],q.uvs[v*2+1]+frameV,n.x,n.y,n.z});
                        center.add(p.x,p.y,p.z);count++;
                        for(int axis=0;axis<3;axis++) {bounds[axis]=Math.min(bounds[axis],poly.get(v)[axis]);bounds[axis+3]=Math.max(bounds[axis+3],poly.get(v)[axis]);}
                    }
                    faces.add(new Face(q,poly));
                }
                if(count==0) continue;
                center.div(count);
                var planes=new ArrayList<SurfaceUnion.Plane>();
                // Open/flat/cutout geometry does not define a filled volume.
                if(cube.closedVolume && faces.size()==6) for(var face:faces) {
                    float[] v=face.polygon.get(0);Vector3f n=new Vector3f(v[5],v[6],v[7]);
                    if(n.dot(new Vector3f(v[0],v[1],v[2]).sub(center))<0) n.negate();
                    if(Math.abs(n.dot(new Vector3f(v[0],v[1],v[2]).sub(center)))<1e-6f) {planes.clear();break;}
                    planes.add(new SurfaceUnion.Plane(n.x,n.y,n.z,n.x*v[0]+n.y*v[1]+n.z*v[2]));
                }
                solids.add(new Solid(i,cube,faces,planes,bounds));
            }
        }
        var batches=new ArrayList<Batch>();
        // ponytail: pairwise broad phase is quadratic; use a spatial index if dense models make clipping costly.
        for(int s=0;s<solids.size();s++) {
            var solid=solids.get(s);var vertices=new ArrayList<float[]>();
            for(var face:solid.faces) {
                if(solid.source.cullable && !frontFacing(face.polygon)) continue;
                List<List<float[]>> pieces=new ArrayList<>();pieces.add(face.polygon);
                if(face.quad.isTranslucent) for(int t=0;t<solids.size() && !pieces.isEmpty();t++) {
                    var other=solids.get(t);
                    if(s==t || other.planes.isEmpty() || model.bakedBones.get(other.bone).surfaceGroup!=model.bakedBones.get(solid.bone).surfaceGroup
                            || !overlap(solid.bounds,other.bounds)) continue;
                    // Keep one copy of coincident exterior faces; remove shared interior faces.
                    var planes=new ArrayList<SurfaceUnion.Plane>();
                    for(var p:other.planes) {
                        float[] v=face.polygon.get(0);
                        float dot=p.x()*v[5]+p.y()*v[6]+p.z()*v[7];
                        float bias=dot>.999f && t>s ? -1e-6f : 1e-6f;
                        planes.add(new SurfaceUnion.Plane(p.x(),p.y(),p.z(),p.d()+bias));
                    }
                    var next=new ArrayList<List<float[]>>();
                    for(var piece:pieces) next.addAll(SurfaceUnion.subtract(piece,planes));
                    pieces=next;
                }
                for(var poly:pieces) for(int j=1;j+1<poly.size();j++) {
                    vertices.add(poly.get(0));vertices.add(poly.get(j));vertices.add(poly.get(j+1));
                }
            }
            if(!vertices.isEmpty()) batches.add(new Batch(solid.bone,vertices));
        }
        int first=0;
        for(int i=0;i<batches.size();i++) {
            var batch=batches.get(i);
            batches.set(i,new Batch(batch.bone,batch.triangles,first));
            first+=batch.triangles.size();
        }
        draw(model,batches,textureIndex,light,overlay,r,g,b,alpha,texture,preview);
        return true;
    }
    private static boolean overlap(float[] a,float[] b) {
        for(int i=0;i<3;i++) if(a[i]>b[i+3]+1e-6f || b[i]>a[i+3]+1e-6f) return false;
        return true;
    }

    private static boolean renderGpu(PoseStack.Pose pose,GeoModel model,float[] params,int textureIndex,int part,
                                     int light,int overlay,float r,float g,float b,float alpha,ResourceLocation texture,boolean preview) {
        GpuMesh mesh=GpuRenderPath.getOrBuildMesh(model);
        if(mesh==null || mesh.groups==null) return false;
        if(skinSurface==0) {
            skinSurface=skinProgram("/group_surface.fsh");
            skinCosmic=skinProgram("/group_cosmic.fsh");
            skinMaterial=skinProgram("/group_material.fsh");
            try {entityCompute=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL43.GL_COMPUTE_SHADER,"/group_entity.csh"));}
            catch(Exception ex) {throw new IllegalStateException("Cannot compile GPU group entity transform",ex);}
        }
        GpuPose instance=mesh.pose(params);
        int bones=preview?0:net.everla.ysmPhysLib.GpuPhysics.render(params,pose.pose(),light);
        if(bones==0) {
            float[] cpu=net.everla.ysmPhysLib.PlayerPhysics.hideEndpoints(model,params);
            instance.boneBuffer.clear();
            GeoModel.nComputeBoneMatrices(mesh.pointer,pose.pose().get(new float[16]),pose.normal().get(new float[9]),cpu,light,instance.boneBuffer);
            GpuRenderPath.updateBoneVisibility(model.bakedBones,cpu,instance.boneBuffer);
            instance.boneBuffer.position(0).limit(mesh.boneCount*144);instance.uploadBones();bones=instance.boneSsbo;
        }
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bones);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,mesh.groups.vbo);
        var mc=Minecraft.getInstance();
        mc.gameRenderer.lightTexture().turnOnLightLayer();mc.gameRenderer.overlayTexture().setupOverlayColor();
        for(int unit=1;unit<=2;unit++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0+unit);GlStateManager._bindTexture(RenderSystem.getShaderTexture(unit));
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);GlStateManager._bindTexture(mc.getTextureManager().getTexture(texture).getId());
        RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.disableCull();
        if(net.everla.reimaginedysm.compat.oculus.OculusCompat.isShaderPackInUse())
            drawGpuMaterials(model,mesh.groups,instance,bones,textureIndex,part,light,overlay,r,g,b,alpha,texture,preview);
        else {
            GL30.glBindVertexArray(mesh.groups.vao);
            for(int pass=1;pass<=2;pass++) {
                if(pass==2 && !model.isTranslucentTexture(textureIndex)) continue;
                RenderSystem.depthMask(pass==1);
                if(pass==1) RenderSystem.disableBlend();else {RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();}
                for(var range:mesh.groups.ranges) {
                    var bone=model.bakedBones.get(range.bone());
                    if(part!=0 && bone.partMask!=part && bone.partMask!=3) continue;
                    int program=bone.style.cosmic()?skinCosmic:skinSurface;
                    bind(program);uniform(program,"materialPass",0);uniform(program,"alphaMode",pass);
                    GL20.glUniform4f(location(program,"color"),r,g,b,alpha);
                    if(bone.style.cosmic()) cosmicUniforms(program,bone.style,preview,light,model,bone.cosmicEffect);
                    else {surfaceUniforms(program,light,overlay);uniform(program,"mode",0);uniform(program,"glowing",bone.glow?1:0);}
                    GL11.glDrawArrays(GL11.GL_TRIANGLES,range.first(),range.count());
                }
                if(pass==1) drawGpuOutlines(model,mesh.groups,part,textureIndex,light,overlay,alpha);
            }
        }
        return true;
    }

    private static int skinProgram(String fragment) {
        try {return ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_skin.vsh"),
                ShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER,fragment));}
        catch(Exception ex) {throw new IllegalStateException("Cannot compile GPU group shader",ex);}
    }

    private static void drawGpuOutlines(GeoModel model,GroupGpuMesh mesh,int part,int textureIndex,int light,int overlay,float alpha) {
        bind(skinSurface);uniform(skinSurface,"materialPass",0);uniform(skinSurface,"mode",1);uniform(skinSurface,"alphaMode",0);
        surfaceUniforms(skinSurface,light,overlay);GL20.glUniform4f(location(skinSurface,"color"),1,1,1,alpha);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        for(int pass=1;pass<=2;pass++) {
            if(pass==2 && !model.isTranslucentTexture(textureIndex)) continue;
            outlineDepth(pass==1);uniform(skinSurface,"alphaMode",pass);
            for(var range:mesh.ranges) {
                var bone=model.bakedBones.get(range.bone());
                if((part!=0 && bone.partMask!=part && bone.partMask!=3) || !bone.style.outline() || bone.style.outlineWidth()==0) continue;
                rgb(skinSurface,"tint",outlineColor(model,range.bone(),textureIndex),1);uniform(skinSurface,"glowing",bone.glow?1:0);
                float width=bone.style.outlineWidth();int steps=(int)Math.ceil(width);
                for(int dy=-steps;dy<=steps;dy++) for(int dx=-steps;dx<=steps;dx++) if(dx!=0 || dy!=0) {
                    matrix(skinSurface,new Matrix4f().translation(dx*width/steps*2f/viewport[2],dy*width/steps*2f/viewport[3],.00001f).mul(RenderSystem.getProjectionMatrix()));
                    GL11.glDrawArrays(GL11.GL_TRIANGLES,range.first(),range.count());
                }
            }
        }
    }

    // Opaque silhouette copies must occlude later copies and deferred geometry.
    // Transparent copies keep blending without claiming opaque depth coverage.
    static void outlineDepth(boolean write) {
        RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.depthMask(write);
    }

    private static int outlineColor(GeoModel model,int index,int textureIndex) {
        var bone=model.bakedBones.get(index);int color=bone.style.outlineColor();
        if(color>=0) return color;
        int[] colors=model.bakedBones.get(bone.outlineGroup).averageColors;
        return colors==null?0x181818:colors[Math.min(textureIndex,colors.length-1)];
    }

    private static void drawGpuMaterials(GeoModel model,GroupGpuMesh mesh,GpuPose instance,int bones,int textureIndex,int part,
                                         int light,int overlay,float r,float g,float b,float alpha,ResourceLocation texture,boolean preview) {
        int triangles=mesh.vertices/3;if(triangles==0) return;
        int columns=(int)Math.ceil(Math.sqrt(triangles*3.0));
        int tile=Math.min(128,Math.min(4096,GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))/columns);
        if(tile<4) throw new IllegalStateException("Too many GPU group triangles: "+triangles);
        int framebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        ensureMaterialResources(columns*tile);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);GlStateManager._bindTexture(Minecraft.getInstance().getTextureManager().getTexture(texture).getId());
        GL11.glViewport(0,0,atlasSize,atlasSize);RenderSystem.disableDepthTest();RenderSystem.disableBlend();RenderSystem.disableCull();
        RenderSystem.colorMask(true,true,true,true);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
        GL30.glBindVertexArray(mesh.vao);
        for(int band=0;band<3;band++) for(var range:mesh.ranges) {
            var bone=model.bakedBones.get(range.bone());
            if(part!=0 && bone.partMask!=part && bone.partMask!=3) continue;
            int program=band!=2 && bone.style.cosmic()?skinCosmic:skinMaterial;
            bind(program);uniform(program,"materialPass",1);uniform(program,"atlasColumns",columns);
            uniform(program,"atlasTriangles",triangles);uniform(program,"atlasBand",band);uniform(program,"atlasTileSize",tile);
            uniform(program,"alphaMode",band==2?0:band+1);GL20.glUniform4f(location(program,"color"),1,1,1,1);
            if(program==skinCosmic) {cosmicUniforms(program,bone.style,preview,light,model,bone.cosmicEffect);scalar(program,"lightmix",0);}
            else uniform(program,"maskOnly",band==2?1:0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,range.first(),range.count());
        }
        instance.ensureGroupBuffers(mesh);
        GlStateManager._glUseProgram(entityCompute);
        uniform(entityCompute,"columns",columns);uniform(entityCompute,"tileSize",tile);
        uniform(entityCompute,"atlasSize",atlasSize);uniform(entityCompute,"overlay",overlay);uniform(entityCompute,"outlineCount",0);
        matrix(entityCompute,RenderSystem.getProjectionMatrix());
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,mesh.vbo);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,instance.groupVbo());
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,bones);
        dispatchGroupEntity(mesh.vertices);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        gpuEntityPass(model,mesh,instance,part,0,texture,true,true,r,g,b,alpha);
        if(!net.everla.reimaginedysm.compat.oculus.OculusCompat.isRenderingShadowPass()
                && !net.everla.reimaginedysm.compat.oculus.ShaderOutlineCompat.isEnabled()) {
            for(var range:mesh.ranges) {
                var bone=model.bakedBones.get(range.bone());
                if((part!=0 && bone.partMask!=part && bone.partMask!=3) || !bone.style.outline() || bone.style.outlineWidth()==0) continue;
                float width=bone.style.outlineWidth();int steps=(int)Math.ceil(width),color=outlineColor(model,range.bone(),textureIndex);
                for(int dy=-steps;dy<=steps;dy++) for(int dx=-steps;dx<=steps;dx++) if(dx!=0 || dy!=0) {
                    Matrix4f shifted=new Matrix4f(RenderSystem.getProjectionMatrix()).invert()
                            .mul(new Matrix4f().translation(dx*width/steps*2f/viewport[2],dy*width/steps*2f/viewport[3],.00001f))
                            .mul(RenderSystem.getProjectionMatrix());
                    GlStateManager._glUseProgram(entityCompute);uniform(entityCompute,"outlineFirst",range.first());uniform(entityCompute,"outlineCount",range.count());
                    GL20.glUniformMatrix4fv(location(entityCompute,"outlineTransform"),false,shifted.get(new float[16]));dispatchGroupEntity(mesh.vertices);
                    gpuEntityRange(instance,mesh.vertices,range,2,texture,false,true,((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,alpha);
                }
            }
        }
        gpuEntityPass(model,mesh,instance,part,0,texture,false,true,r,g,b,alpha);
        if(model.isTranslucentTexture(textureIndex)) gpuEntityPass(model,mesh,instance,part,1,texture,false,false,r,g,b,alpha);
    }

    private static void dispatchGroupEntity(int count) {
        GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL43.glDispatchCompute((count+63)/64,1,1);
        GL43.glMemoryBarrier(GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static void gpuEntityPass(GeoModel model,GroupGpuMesh mesh,GpuPose instance,int part,int band,ResourceLocation texture,
                                      boolean depthOnly,boolean depthWrite,float r,float g,float b,float a) {
        // One state setup and one draw per contiguous bone range, never per triangle.
        gpuEntityRange(instance,mesh.vertices,null,band,texture,depthOnly,depthWrite,r,g,b,a,model,mesh,part);
    }
    private static void gpuEntityRange(GpuPose instance,int count,GroupGpuMesh.Range range,int band,ResourceLocation texture,
                                       boolean depthOnly,boolean depthWrite,float r,float g,float b,float a) {
        gpuEntityRange(instance,count,range,band,texture,depthOnly,depthWrite,r,g,b,a,null,null,0);
    }
    private static void gpuEntityRange(GpuPose instance,int count,GroupGpuMesh.Range range,int band,ResourceLocation texture,
                                       boolean depthOnly,boolean depthWrite,float r,float g,float b,float a,GeoModel model,GroupGpuMesh mesh,int part) {
        var type=band==1?net.minecraft.client.renderer.RenderType.entityTranslucent(texture):net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture);
        net.minecraft.client.renderer.ShaderInstance shader=null;
        try {
            type.setupRenderState();RenderSystem.setShaderTexture(0,atlas);
            shader=Objects.requireNonNull(RenderSystem.getShader());net.everla.reimaginedysm.gpu.IrisRenderPath.setupShader(shader,entityIdentity);shader.apply();
            if(band==2) outlineDepth(depthWrite);else RenderSystem.depthMask(depthWrite);
            RenderSystem.colorMask(!depthOnly,!depthOnly,!depthOnly,!depthOnly);
            GL30.glBindVertexArray(instance.groupVao());GL20.glDisableVertexAttribArray(1);GL20.glVertexAttrib4f(1,r,g,b,a);
            if(range!=null) GL11.glDrawArrays(GL11.GL_TRIANGLES,band*count+range.first(),range.count());
            else for(var run:mesh.ranges) {
                var bone=model.bakedBones.get(run.bone());
                if(part==0 || bone.partMask==part || bone.partMask==3) GL11.glDrawArrays(GL11.GL_TRIANGLES,band*count+run.first(),run.count());
            }
        } finally {try {if(shader!=null) shader.clear();} finally {type.clearRenderState();}}
    }
    private static boolean frontFacing(List<float[]> polygon) {
        Vector4f[] p=new Vector4f[3];
        for(int i=0;i<3;i++) {float[] v=polygon.get(i);p[i]=new Vector4f(v[0],v[1],v[2],1).mul(RenderSystem.getProjectionMatrix());}
        return p[0].x*(p[1].y*p[2].w-p[2].y*p[1].w)-p[1].x*(p[0].y*p[2].w-p[2].y*p[0].w)
                +p[2].x*(p[0].y*p[1].w-p[1].y*p[0].w)>0;
    }

    private static void draw(GeoModel model,List<Batch> batches,int textureIndex,int light,int overlay,
                             float r,float g,float b,float alpha,ResourceLocation texture,boolean preview) {
        try(var saved=new DrawState()) {
            if(net.everla.reimaginedysm.compat.oculus.OculusCompat.isShaderPackInUse()) {
                drawMaterials(model,batches,textureIndex,light,overlay,r,g,b,alpha,texture,preview);
            } else drawSurface(model,batches,textureIndex,light,overlay,r,g,b,alpha,texture,preview);
        }
    }

    /** Bake albedo on GPU; only the pack's entity shader may encode its G-buffer. */
    private static void drawMaterials(GeoModel model,List<Batch> batches,int textureIndex,int light,int overlay,
                                      float r,float g,float b,float alpha,ResourceLocation texture,boolean preview) {
        int count=batches.stream().mapToInt(batch->batch.triangles.size()).sum();
        if(count==0) return;
        int triangles=count/3;
        int columns=(int)Math.ceil(Math.sqrt(triangles*3.0));
        // Bound atlas storage even on dense models; ordinary GPU models never use it.
        int tile=Math.min(128,Math.min(4096,GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))/columns);
        if(tile<4) throw new IllegalStateException("Too many group material triangles: "+triangles);
        int size=columns*tile;
        if(size>GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))
            throw new IllegalStateException("Group material atlas exceeds GPU texture limit: "+size);
        int previousDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] previousViewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,previousViewport);
        ensureMaterialResources(size);
        // The source stream is shared by all three atlas bands and uploaded once.
        GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        for(int i=0;i<4;i++) GL20.glEnableVertexAttribArray(i);
        GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,48,0);
        GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,48,12);
        GL20.glVertexAttribPointer(2,4,GL11.GL_FLOAT,false,48,20);
        GL20.glVertexAttribPointer(3,3,GL11.GL_FLOAT,false,48,36);
        upload(batches,1,1,1,1);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,atlasFbo);
        GL11.glViewport(0,0,size,size);
        RenderSystem.disableDepthTest();RenderSystem.disableCull();RenderSystem.disableBlend();
        RenderSystem.colorMask(true,true,true,true);
        GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(Minecraft.getInstance().getTextureManager().getTexture(texture).getId());
        for(int band=0;band<3;band++) for(var batch:batches) {
            var style=model.bakedBones.get(batch.bone).style;
            int p=band!=2 && style.cosmic()?materialCosmic:material;
            GlStateManager._glUseProgram(p);
            uniform(p,"texture0",0);uniform(p,"atlasColumns",columns);
            uniform(p,"atlasTriangles",triangles);uniform(p,"atlasBand",band);uniform(p,"atlasTileSize",tile);
            uniform(p,"alphaMode",band==2?0:band+1);
            if(p==materialCosmic) {
                cosmicUniforms(p,style,preview,light,model,model.bakedBones.get(batch.bone).cosmicEffect);
                // This texture is albedo, not a second lit/fogged framebuffer.
                scalar(p,"lightmix",0);
            } else uniform(p,"maskOnly",band==2?1:0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,batch.first,batch.triangles.size());
        }
        // Entity vertices remain in view space. Normals, overlay and light are
        // supplied in Minecraft's format, so Iris generates every pack output.
        uploadEntityMaterials(model,batches,count,triangles,columns,light,overlay);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,previousDraw);
        // Restore the world viewport before applying the pack's entity program.
        GL11.glViewport(previousViewport[0],previousViewport[1],previousViewport[2],previousViewport[3]);
        var translucent=new ArrayList<Batch>();
        for(var batch:batches) for(int i=0;i<batch.triangles.size();i+=3)
            translucent.add(new Batch(batch.bone,batch.triangles.subList(i,i+3),batch.first+i));
        translucent.sort(Comparator.comparingDouble(batch->batch.triangles.stream().mapToDouble(v->v[2]).average().orElse(0)));
        // A depth prepass keeps expanded outline copies behind all opaque parts.
        entityPass(model,batches,count,0,texture,true,true,r,g,b,alpha);
        if(!net.everla.reimaginedysm.compat.oculus.OculusCompat.isRenderingShadowPass()
                && !net.everla.reimaginedysm.compat.oculus.ShaderOutlineCompat.isEnabled()) {
            int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            for(var batch:batches) {
                var bone=model.bakedBones.get(batch.bone);
                if(!bone.style.outline() || bone.style.outlineWidth()==0) continue;
                int color=bone.style.outlineColor();
                if(color<0) {
                    int[] colors=model.bakedBones.get(bone.outlineGroup).averageColors;
                    color=colors==null?0x181818:colors[Math.min(textureIndex,colors.length-1)];
                }
                int steps=(int)Math.ceil(bone.style.outlineWidth());
                for(int dy=-steps;dy<=steps;dy++) for(int dx=-steps;dx<=steps;dx++) if(dx!=0 || dy!=0) {
                    Matrix4f shifted=new Matrix4f().translation(dx*bone.style.outlineWidth()/steps*2f/viewport[2],
                            dy*bone.style.outlineWidth()/steps*2f/viewport[3],.00001f).mul(RenderSystem.getProjectionMatrix());
                    shiftOutlineVertices(model,batch,count,columns,light,overlay,shifted);
                    entityPass(model,List.of(batch),count,2,texture,false,true,
                            ((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,alpha);
                }
            }
        }
        entityPass(model,batches,count,0,texture,false,true,r,g,b,alpha);
        entityPass(model,translucent,count,1,texture,false,false,r,g,b,alpha);
    }

    private static void ensureMaterialResources(int size) {
        if(material==0) {
            try {
                material=materialProgram("/group_material.fsh");materialCosmic=materialProgram("/group_cosmic.fsh");
            } catch(Exception ex) {throw new IllegalStateException("Cannot initialize group materials",ex);}
            atlas=GL11.glGenTextures();atlasFbo=GL30.glGenFramebuffers();
            entityVao=GL30.glGenVertexArrays();entityVbo=GL15.glGenBuffers();
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);GlStateManager._bindTexture(atlas);
        if(atlasSize!=size) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,size,size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            atlasSize=size;
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,atlasFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,atlas,0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete group material framebuffer");
    }

    private static int materialProgram(String fragment) throws Exception {
        return ShaderUtil.linkProgramWith(p->{
            GL20.glBindAttribLocation(p,0,"Position");GL20.glBindAttribLocation(p,1,"UV0");
            GL20.glBindAttribLocation(p,2,"Color");GL20.glBindAttribLocation(p,3,"Normal");
            GL30.glBindFragDataLocation(p,0,"fragColor");
        },ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_material.vsh"),
                ShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER,fragment));
    }

    private static void uploadEntityMaterials(GeoModel model,List<Batch> batches,int count,int triangles,int columns,int light,int overlay) {
        ByteBuffer data=MemoryUtil.memAlloc(count*3*36);
        try {
            for(int band=0;band<3;band++) for(var batch:batches) for(int i=0;i<batch.triangles.size();i++) {
                float[] v=batch.triangles.get(i);int index=batch.first+i;
                int tile=index/3+band*triangles,corner=index%3,edge=atlasSize/columns;
                float u=(tile%columns*edge+(corner==1?edge-1:1))/(float)atlasSize;
                float w=(tile/columns*edge+(corner==2?edge-1:1))/(float)atlasSize;
                data.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putInt(-1).putFloat(u).putFloat(w);
                var bone=model.bakedBones.get(batch.bone);
                data.putInt(overlay).putInt(bone.glow?15728880:light);
                for(int axis=5;axis<8;axis++) data.put((byte)Math.round(Math.max(-1,Math.min(1,v[axis]))*127));
                data.put((byte)0);
            }
            data.flip();GL30.glBindVertexArray(entityVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,entityVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STREAM_DRAW);
            for(int i=0;i<6;i++) GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,36,0);
            GL20.glVertexAttribPointer(1,4,GL11.GL_UNSIGNED_BYTE,true,36,12);
            GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,36,16);
            GL30.glVertexAttribIPointer(3,2,GL11.GL_SHORT,36,24);
            GL30.glVertexAttribIPointer(4,2,GL11.GL_SHORT,36,28);
            GL20.glVertexAttribPointer(5,3,GL11.GL_BYTE,true,36,32);
            GL20.glDisableVertexAttribArray(1);
        } finally {MemoryUtil.memFree(data);}
    }

    private static void entityPass(GeoModel model,List<Batch> batches,int count,int band,ResourceLocation texture,
                                   boolean depthOnly,boolean depthWrite,float r,float g,float b,float a) {
        var type=band!=1?net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture)
                :net.minecraft.client.renderer.RenderType.entityTranslucent(texture);
        net.minecraft.client.renderer.ShaderInstance shader=null;
        try {
            type.setupRenderState();RenderSystem.setShaderTexture(0,atlas);
            shader=Objects.requireNonNull(RenderSystem.getShader(),"Missing group entity shader");
            net.everla.reimaginedysm.gpu.IrisRenderPath.setupShader(shader,entityIdentity);
            shader.apply();
            if(band==2) outlineDepth(depthWrite);else RenderSystem.depthMask(depthWrite);
            RenderSystem.colorMask(!depthOnly,!depthOnly,!depthOnly,!depthOnly);
            GL30.glBindVertexArray(entityVao);GL20.glVertexAttrib4f(1,r,g,b,a);
            for(var batch:batches) GL11.glDrawArrays(GL11.GL_TRIANGLES,band*count+batch.first,batch.triangles.size());
        } finally {
            try {if(shader!=null) shader.clear();}
            finally {type.clearRenderState();}
        }
    }

    private static void shiftOutlineVertices(GeoModel model,Batch batch,int count,int columns,int light,int overlay,Matrix4f projection) {
        // Shader packs can reconstruct position with their own projection uniforms.
        // Move the actual view-space positions instead of changing ProjMat alone.
        Matrix4f transform=new Matrix4f(RenderSystem.getProjectionMatrix()).invert().mul(projection);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,entityVbo);
        ByteBuffer data=MemoryUtil.memAlloc(batch.triangles.size()*36);
        try {
            for(int i=0;i<batch.triangles.size();i++) {
                float[] v=batch.triangles.get(i);
                Vector3f moved=transform.transformProject(new Vector3f(v[0],v[1],v[2]));
                int index=batch.first+i,tile=index/3+2*count/3,corner=index%3,edge=atlasSize/columns;
                data.putFloat(moved.x).putFloat(moved.y).putFloat(moved.z).putInt(-1);
                data.putFloat((tile%columns*edge+(corner==1?edge-1:1))/(float)atlasSize);
                data.putFloat((tile/columns*edge+(corner==2?edge-1:1))/(float)atlasSize);
                data.putInt(overlay).putInt(model.bakedBones.get(batch.bone).glow?15728880:light);
                for(int axis=5;axis<8;axis++) data.put((byte)Math.round(Math.max(-1,Math.min(1,v[axis]))*127));
                data.put((byte)0);
            }
            data.flip();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,(long)(2*count+batch.first)*36,data);
        } finally {MemoryUtil.memFree(data);}
    }

    private static void drawSurface(GeoModel model,List<Batch> batches,int textureIndex,int light,int overlay,
                             float r,float g,float b,float alpha,ResourceLocation texture,boolean preview) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(Minecraft.getInstance().getTextureManager().getTexture(texture).getId());
            var mc=Minecraft.getInstance();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            mc.gameRenderer.overlayTexture().setupOverlayColor();
            for(int unit=1;unit<=2;unit++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0+unit);
                GlStateManager._bindTexture(RenderSystem.getShaderTexture(unit));
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(mc.getTextureManager().getTexture(texture).getId());
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableCull();RenderSystem.disableBlend();RenderSystem.depthMask(true);
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            for(int i=0;i<4;i++) GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,48,0);
            GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,48,12);
            GL20.glVertexAttribPointer(2,4,GL11.GL_FLOAT,false,48,20);
            GL20.glVertexAttribPointer(3,3,GL11.GL_FLOAT,false,48,36);
            upload(batches,r,g,b,alpha);
            bind(surface);surfaceUniforms(light,overlay);uniform(surface,"mode",0);uniform(surface,"alphaMode",1);
            // Opaque geometry establishes occlusion before expanded silhouettes are drawn.
            RenderSystem.colorMask(false,false,false,false);
            for(var batch:batches) emit(batch,r,g,b,alpha,light,model);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(false);
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            uniform(surface,"mode",1);
            uniform(surface,"alphaMode",0);
            int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            for(int outlinePass=1;outlinePass<=2;outlinePass++) {
                if(outlinePass==2 && !model.isTranslucentTexture(textureIndex)) continue;
                outlineDepth(outlinePass==1);uniform(surface,"alphaMode",outlinePass);
                for(var batch:batches) {
                    var bone=model.bakedBones.get(batch.bone);if(!bone.style.outline()) continue;
                    float width=bone.style.outlineWidth();
                    if(width==0) continue;
                    int color=bone.style.outlineColor();
                    if(color<0) {
                        int[] colors=model.bakedBones.get(bone.outlineGroup).averageColors;
                        color=colors==null?0x181818:colors[Math.min(textureIndex,colors.length-1)];
                    }
                    rgb(surface,"tint",color,1);
                    int steps=(int)Math.ceil(width);
                    for(int dy=-steps;dy<=steps;dy++) for(int dx=-steps;dx<=steps;dx++) if(dx!=0 || dy!=0) {
                        Matrix4f shifted=new Matrix4f().translation(dx*width/steps*2f/viewport[2],dy*width/steps*2f/viewport[3],.00001f)
                                .mul(RenderSystem.getProjectionMatrix());
                        matrix(surface,shifted);emit(batch,1,1,1,alpha,light,model);
                    }
                }
            }
            // Opaque first; translucent exterior triangles back-to-front, without depth writes.
            var translucent=new ArrayList<Batch>();
            for(var batch:batches) for(int i=0;i<batch.triangles.size();i+=3)
                translucent.add(new Batch(batch.bone,batch.triangles.subList(i,i+3),batch.first+i));
            translucent.sort(Comparator.comparingDouble(batch->batch.triangles.stream().mapToDouble(v->v[2]).average().orElse(0)));
            for(int pass=1;pass<=2;pass++) {
                RenderSystem.depthMask(pass==1);
                GroupStyle previousStyle=null;
                int previousProgram=0;
                for(var batch:pass==1?batches:translucent) {
                    var style=model.bakedBones.get(batch.bone).style;
                    int program=style.cosmic()?cosmic:surface;
                    if(program!=previousProgram) {
                        bind(program);
                        uniform(program,"alphaMode",pass);
                        if(program==surface) uniform(surface,"mode",0);
                    }
                    if(style.cosmic()) {
                        cosmicUniforms(cosmic,style,preview,light,model,model.bakedBones.get(batch.bone).cosmicEffect);
                    }
                    emit(batch,r,g,b,alpha,light,model);
                    previousProgram=program;previousStyle=style;
                }
            }
    }
    private static void emit(Batch batch,float r,float g,float b,float a,int light,GeoModel model) {
        if(currentProgram==surface) uniform(surface,"glowing",model.bakedBones.get(batch.bone).glow?1:0);
        GL11.glDrawArrays(GL11.GL_TRIANGLES,batch.first,batch.triangles.size());
    }
    private static void upload(List<Batch> batches,float r,float g,float b,float a) {
        int vertices=0;
        for(var batch:batches) vertices+=batch.triangles.size();
        ByteBuffer data=MemoryUtil.memAlloc(vertices*48);
        try {
            for(var batch:batches) for(float[] v:batch.triangles) {
                for(int j=0;j<5;j++) data.putFloat(v[j]);
                data.putFloat(r).putFloat(g).putFloat(b).putFloat(a);
                data.putFloat(v[5]).putFloat(v[6]).putFloat(v[7]);
            }
            data.flip();GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STREAM_DRAW);
        } finally {MemoryUtil.memFree(data);}
    }
    private static void bind(int p) {
        currentProgram=p;
        GlStateManager._glUseProgram(p);uniform(p,"texture0",0);matrix(p,RenderSystem.getProjectionMatrix());
    }
    private static void surfaceUniforms(int light,int overlay) {surfaceUniforms(surface,light,overlay);}
    private static void surfaceUniforms(int program,int light,int overlay) {
        uniform(program,"lightmap",2);uniform(program,"overlayMap",1);
        uniform(program,"packedLight",light);uniform(program,"packedOverlay",overlay);
        var lights=com.elfmcys.yesstevemodel.mixin.client.RenderSystemAccessor.ysm$getShaderLightDirections();
        for(int i=0;i<2;i++) {
            Vector3f v=lights!=null && lights[i]!=null?lights[i]:new Vector3f(i==0?.2f:-.2f,1,-.7f);
            GL20.glUniform3f(location(program,"light"+i),v.x,v.y,v.z);
        }
        float[] fog=RenderSystem.getShaderFogColor();
        GL20.glUniform4f(location(program,"fogColor"),fog[0],fog[1],fog[2],fog[3]);
        scalar(program,"fogStart",RenderSystem.getShaderFogStart());scalar(program,"fogEnd",RenderSystem.getShaderFogEnd());
        uniform(program,"fogShape",RenderSystem.getShaderFogShape().getIndex());
    }
    private static int location(int program,String name) {
        return uniformLocations.computeIfAbsent(program,key->new HashMap<>())
                .computeIfAbsent(name,key->GL20.glGetUniformLocation(program,key));
    }
    private static void matrix(int p,Matrix4f matrix) {GL20.glUniformMatrix4fv(location(p,"Projection"),false,matrix.get(new float[16]));}
    private static void uniform(int p,String name,int n) {GL20.glUniform1i(location(p,name),n);}
    private static void scalar(int p,String name,float n) {GL20.glUniform1f(location(p,name),n);}
    private static void rgb(int p,String name,int color,float a) {
        GL20.glUniform4f(location(p,name),color<0?-1:((color>>16)&255)/255f,
                color<0?-1:((color>>8)&255)/255f,color<0?-1:(color&255)/255f,a);
    }
    private static void cosmicUniforms(int program,GroupStyle style,boolean preview,int light,GeoModel model,String effect) {
        var mc=Minecraft.getInstance();
        int ticks=mc.level==null?(int)(net.minecraft.Util.getMillis()/50): (int)mc.level.getGameTime();
        int count=net.everla.ysmJsLib.CosmicTextures.bind(model.scripts,effect,ticks);
        if(count==0){updateStars(ticks);count=10;}
        uniform(program,"cosmiccount",count);
        scalar(program,"cosmicoutof",count*10.1f);
        uniform(program,"stars",3);uniform(program,"time",ticks);
        var camera=mc.gameRenderer.getMainCamera();
        scalar(program,"yaw",preview?0:(float)Math.toRadians(camera.getYRot()));
        scalar(program,"pitch",preview?0:(float)Math.toRadians(-camera.getXRot()));
        scalar(program,"externalScale",preview?25:1);scalar(program,"lightmix",.2f);scalar(program,"opacity",1);
        float brightness=Math.max(.2f,Math.max(light&65535,light>>>16)/240f);
        GL20.glUniform3f(location(program,"lightlevel"),brightness,brightness,brightness);
        rgb(program,"spaceTint",style.spaceColor(),style.spaceAlpha());
        rgb(program,"astralTint",style.astralColor(),style.astralAlpha());
    }

    private static void updateStars(int ticks) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0+3);
        if(stars==0) {
            stars=GL11.glGenTextures();GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,stars);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY,0,GL11.GL_RGBA8,16,16,10,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            Arrays.fill(lastFrames,-1);
            try {
                for(int i=0;i<10;i++) {
                    String path="/assets/yes_steve_model/textures/cosmic/cosmic_"+i+".png";
                    java.awt.image.BufferedImage image;
                    try(var in=GroupEffectRenderer.class.getResourceAsStream(path)) {image=ImageIO.read(in);}
                    ByteBuffer data=MemoryUtil.memAlloc(image.getWidth()*image.getHeight()*4);
                    for(int y=0;y<image.getHeight();y++) for(int x=0;x<16;x++) {
                        int c=image.getRGB(x,y);data.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>>24));
                    }
                    data.flip();pixels.add(data);
                    var meta=com.google.gson.JsonParser.parseString(ShaderUtil.loadResource(path+".mcmeta")).getAsJsonObject().getAsJsonObject("animation");
                    int duration=meta.has("frametime")?meta.get("frametime").getAsInt():1;
                    var sequence=new ArrayList<Integer>();
                    if(meta.has("frames")) for(var f:meta.getAsJsonArray("frames")) {
                        int index=f.isJsonObject()?f.getAsJsonObject().get("index").getAsInt():f.getAsInt();
                        int time=f.isJsonObject()?f.getAsJsonObject().get("time").getAsInt():duration;
                        for(int n=0;n<time;n++) sequence.add(index);
                    } else for(int f=0;f<image.getHeight()/16;f++) for(int n=0;n<duration;n++) sequence.add(f);
                    frames.add(sequence.stream().mapToInt(Integer::intValue).toArray());
                }
            } catch(Exception ex) {throw new IllegalStateException("Cannot load Avaritia cosmic sprites",ex);}
        } else GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,stars);
        for(int i=0;i<10;i++) {
            int[] seq=frames.get(i);int frame=seq[Math.floorMod(ticks,seq.length)];
            if(frame==lastFrames[i]) continue;
            ByteBuffer data=pixels.get(i).duplicate();data.position(frame*1024).limit((frame+1)*1024);
            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY,0,0,0,i,16,16,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
            lastFrames[i]=frame;
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    public static void shutdown() {
        for(int program:new int[]{surface,cosmic,material,materialCosmic,skinSurface,skinCosmic,skinMaterial,entityCompute}) if(program!=0) GL20.glDeleteProgram(program);
        for(int buffer:new int[]{vbo,entityVbo}) if(buffer!=0) GL15.glDeleteBuffers(buffer);
        for(int array:new int[]{vao,entityVao}) if(array!=0) GL30.glDeleteVertexArrays(array);
        if(atlasFbo!=0) GL30.glDeleteFramebuffers(atlasFbo);
        if(atlas!=0) GlStateManager._deleteTexture(atlas);
        if(stars!=0) GL11.glDeleteTextures(stars);
        net.everla.ysmJsLib.CosmicTextures.clear();
        pixels.forEach(MemoryUtil::memFree);pixels.clear();frames.clear();
        surface=cosmic=material=materialCosmic=vbo=entityVbo=vao=entityVao=atlasFbo=atlas=atlasSize=stars=0;
        skinSurface=skinCosmic=skinMaterial=entityCompute=0;
        uniformLocations.clear();
        failed=false;
    }

    /** Raw GL bindings and Minecraft's cached toggles must both be restored. */
    private static final class DrawState implements AutoCloseable {
        final net.minecraft.client.renderer.ShaderInstance shader=RenderSystem.getShader();
        final int drawFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                readFramebuffer=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int[] viewport=new int[4];
        final float[] attributeColor=new float[4];
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),array=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                buffer=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),src=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dst=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),
                depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final byte[] mask=new byte[4];final int starTex;
        final int[] textures=new int[3],shaderTextures=new int[3];
        DrawState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            GL20.glGetVertexAttribfv(1,GL20.GL_CURRENT_VERTEX_ATTRIB,attributeColor);
            ByteBuffer bytes=MemoryUtil.memAlloc(4);
            try {GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,bytes);bytes.get(mask);} finally {MemoryUtil.memFree(bytes);}
            for(int i=0;i<3;i++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                shaderTextures[i]=RenderSystem.getShaderTexture(i);
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0+3);starTex=GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFramebuffer);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL20.glVertexAttrib4f(1,attributeColor[0],attributeColor[1],attributeColor[2],attributeColor[3]);
            RenderSystem.setShader(()->shader);
            GlStateManager._glUseProgram(program);GL30.glBindVertexArray(array);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            for(int i=0;i<3;i++) {
                RenderSystem.setShaderTexture(i,shaderTextures[i]);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);GlStateManager._bindTexture(textures[i]);
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0+3);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,starTex);GlStateManager._activeTexture(active);
            if(blend) RenderSystem.enableBlend();else RenderSystem.disableBlend();
            if(depth) RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull) RenderSystem.enableCull();else RenderSystem.disableCull();
            RenderSystem.depthMask(depthMask);RenderSystem.depthFunc(depthFunc);
            GlStateManager._blendFuncSeparate(src,dst,srcA,dstA);
            RenderSystem.colorMask(mask[0]!=0,mask[1]!=0,mask[2]!=0,mask[3]!=0);
        }
    }
}

