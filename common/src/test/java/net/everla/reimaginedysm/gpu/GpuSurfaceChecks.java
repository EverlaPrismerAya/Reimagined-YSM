package net.everla.reimaginedysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.GroupEffectRenderer;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.resource.models.GeometryDescription;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/** Executes the production GPU mesh, union compute shader and silhouette mask. */
public final class GpuSurfaceChecks {
    public static void run() throws Exception {
        String[][] names=new String[35][];Arrays.setAll(names,i->new String[0]);
        var model=new GeoModel(new GeoBone[0],names,new boolean[3],new GeometryDescription("gpu-check",16,16,4,4,new double[3]),new boolean[]{true});
        var source=new GeoModel.BakedBone();source.name="source";source.style=GroupStyle.parse("ysmOutline#color=00FF00@source",GroupStyle.NONE);
        var blocker=new GeoModel.BakedBone();blocker.name="blocker";
        var panel=new GeoModel.BakedCube();panel.quads.add(face(2,1,new float[]{-.5f,-.5f,-3},new float[]{.5f,.5f,-3}));source.cubes.add(panel);
        var box=new GeoModel.BakedCube();box.closedVolume=true;
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1})box.quads.add(face(axis,sign,new float[]{-1,-1,-4},new float[]{1,1,-2}));
        blocker.cubes.add(box);model.bakedBones=List.of(source,blocker);
        int fbo=GL30.glGenFramebuffers(),target=GL11.glGenTextures(),skin=GL11.glGenTextures(),program=0;
        var oldProjection=new Matrix4f(RenderSystem.getProjectionMatrix());
        try(var mesh=new GroupGpuMesh(model);var pose=new GpuPose(new float[24],2);var saved=new ShaderUtil.SavedComputeState(6)) {
            var shaderSource=GroupEffectRenderer.class.getDeclaredMethod("gpuFragment",String.class);shaderSource.setAccessible(true);
            for(String fragment:new String[]{"/group_surface.fsh","/group_cosmic.fsh","/group_material.fsh"}) {
                int p=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/group_skin.vsh"),ShaderUtil.compileShader(GL20.GL_FRAGMENT_SHADER,(String)shaderSource.invoke(null,fragment),fragment));
                if(fragment.equals("/group_surface.fsh"))program=p;else GL20.glDeleteProgram(p);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,32,32,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,target,0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("GPU test framebuffer");
            GL11.glViewport(0,0,32,32);RenderSystem.disableDepthTest();RenderSystem.disableBlend();RenderSystem.disableCull();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,skin);var white=MemoryUtil.memAlloc(16*16*4);
            try {while(white.hasRemaining())white.put((byte)255);for(int i=3;i<white.capacity();i+=4)white.put(i,(byte)128);white.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,white);}finally{MemoryUtil.memFree(white);}
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            // Lighting/overlay use an independent opaque white texture.
            int light=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL11.GL_TEXTURE_2D,light);
            byte[] opaque=new byte[16*16*4];Arrays.fill(opaque,(byte)255);var lightData=MemoryUtil.memAlloc(opaque.length);lightData.put(opaque).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,lightData);MemoryUtil.memFree(lightData);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL13.glActiveTexture(GL13.GL_TEXTURE0);
            var projection=new Matrix4f().perspective((float)Math.toRadians(60),1,.05f,100);RenderSystem.setProjectionMatrix(projection,VertexSorting.DISTANCE_TO_ORIGIN);
            var prepare=GroupEffectRenderer.class.getDeclaredMethod("prepareGpuSurfaces",GroupGpuMesh.class,GpuPose.class,int.class,int.class);prepare.setAccessible(true);
            for(boolean moved:new boolean[]{false,true}) {
                pose.boneBuffer.clear();for(int i=0;i<2;i++){new Matrix4f().translation(moved&&i==1?10:0,0,0).get(i*144,pose.boneBuffer);new Matrix4f().get(i*144+64,pose.boneBuffer);pose.boneBuffer.putInt(i*144+128,15728880).putInt(i*144+132,0);}pose.boneBuffer.position(0).limit(288);pose.uploadBones();
                prepare.invoke(null,mesh,pose,pose.boneSsbo,0);
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pose.boneSsbo);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,mesh.vbo);
                GL20.glUseProgram(program);GL30.glBindVertexArray(mesh.vao);
                uniforms(program,projection);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
                float[] pixel=new float[4];GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
                if(moved?pixel[0]<.9:pixel[0]>.01)throw new AssertionError("GPU union did not track the animated blocker: "+moved+" "+Arrays.toString(pixel));
            }
            // Use the real mask pass, then deliberately disable depth. Only the
            // silhouette exclusion can keep the transparent panel's center red.
            var field=GroupEffectRenderer.class.getDeclaredField("skinSurface");field.setAccessible(true);int previous=field.getInt(null);field.setInt(null,program);
            try {
                var mask=GroupEffectRenderer.class.getDeclaredMethod("prepareGpuSilhouette",GeoModel.class,GroupGpuMesh.class,int.class);mask.setAccessible(true);mask.invoke(null,model,mesh,0);
                var maskUniforms=GroupEffectRenderer.class.getDeclaredMethod("outlineMaskUniforms",int.class,boolean.class,Matrix4f.class);maskUniforms.setAccessible(true);maskUniforms.invoke(null,program,false,projection);
                var shifted=new Matrix4f().translation(.25f,0,0).mul(projection);
                for(boolean atlas:new boolean[]{false,true}) {
                    GL20.glUseProgram(program);uniforms(program,projection);
                    GL20.glUniform1i(GL20.glGetUniformLocation(program,"silhouetteMaskEnabled"),0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
                    GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"Projection"),false,shifted.get(new float[16]));
                    GL20.glUniform1i(GL20.glGetUniformLocation(program,"mode"),1);GL20.glUniform4f(GL20.glGetUniformLocation(program,"tint"),0,1,0,1);
                    maskUniforms.invoke(null,program,atlas,shifted);
                    GL30.glBindVertexArray(mesh.vao);RenderSystem.disableDepthTest();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);
                    float[] pixel=new float[4];GL11.glReadPixels(16,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
                    if(pixel[0]<.9 || pixel[1]>.01)throw new AssertionError("GPU outline filled transparent model surface: "+atlas+" "+Arrays.toString(pixel));
                    GL11.glReadPixels(23,16,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
                    if(pixel[1]<.1)throw new AssertionError("GPU outline exterior disappeared: "+atlas+" "+Arrays.toString(pixel));
                }
            }finally{field.setInt(null,previous);}
            GL11.glDeleteTextures(light);
            System.out.println("Production GPU shaders: animated solid union, transparent exterior and silhouette-only outline passed");
        } finally {
            RenderSystem.setProjectionMatrix(oldProjection,VertexSorting.DISTANCE_TO_ORIGIN);GL20.glUseProgram(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);
            GL20.glDeleteProgram(program);GL11.glDeleteTextures(skin);GL11.glDeleteTextures(target);GL30.glDeleteFramebuffers(fbo);
        }
    }
    private static void uniforms(int p,Matrix4f projection) {
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,"Projection"),false,projection.get(new float[16]));
        GL20.glUniform4f(GL20.glGetUniformLocation(p,"color"),1,0,0,1);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"texture0"),0);GL20.glUniform1i(GL20.glGetUniformLocation(p,"lightmap"),1);GL20.glUniform1i(GL20.glGetUniformLocation(p,"overlayMap"),1);
        GL20.glUniform3f(GL20.glGetUniformLocation(p,"light0"),0,0,1);GL20.glUniform3f(GL20.glGetUniformLocation(p,"light1"),0,0,1);
        GL20.glUniform1f(GL20.glGetUniformLocation(p,"fogStart"),100);GL20.glUniform1f(GL20.glGetUniformLocation(p,"fogEnd"),101);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"surfaceClipping"),1);GL20.glUniform1i(GL20.glGetUniformLocation(p,"solidCount"),2);
        GL20.glUniform1i(GL20.glGetUniformLocation(p,"materialPass"),0);GL20.glUniform1i(GL20.glGetUniformLocation(p,"mode"),0);GL20.glUniform1i(GL20.glGetUniformLocation(p,"alphaMode"),0);
    }
    private static GeoModel.BakedQuad face(int axis,int sign,float[] lo,float[] hi) {
        var q=new GeoModel.BakedQuad();q.isTranslucent=true;q.normal[axis]=sign;
        int a=(axis+1)%3,b=(axis+2)%3;
        for(int v=0;v<4;v++){q.positions[v*3+axis]=sign<0?lo[axis]:hi[axis];q.positions[v*3+a]=(v==1||v==2)?hi[a]:lo[a];q.positions[v*3+b]=v>=2?hi[b]:lo[b];q.uvs[v*2]=.5f;q.uvs[v*2+1]=.5f;}
        return q;
    }
}
