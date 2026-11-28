package com.elfmcys.yesstevemodel.geckolib3.geo;

import com.mojang.blaze3d.systems.RenderSystem;
import net.everla.reimaginedysm.gpu.MaterialAtlasLayout;
import net.everla.reimaginedysm.gpu.ShaderUtil;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;

/** Compare the production CPU/GPU material bake against the original texels. */
public final class MaterialAtlasChecks {
    public static void run() {
        float[] uvs={0,0,1,0,0,1, 1,1,0,1,1,0, 0,1,0,0,1,1, .1f,.2f,.8f,.1f,.3f,.9f,
                .5f,.5f,.5f,.5f,.5f,.5f, .1f,.4f,.8f,.4f,.4f,.4f};
        int fbo=GL30.glGenFramebuffers(),target=GL11.glGenTextures(),skin=GL11.glGenTextures();
        int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers(),bones=GL15.glGenBuffers(),source=GL15.glGenBuffers();
        try(var layout=new MaterialAtlasLayout(uvs,64,64,4096)) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,layout.size,layout.size,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,target,0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Material atlas framebuffer");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,skin);
            ByteBuffer pixels=MemoryUtil.memAlloc(64*64*4);
            try {
                for(int y=0;y<64;y++)for(int x=0;x<64;x++)pixels.put((byte)(x*3)).put((byte)((y&1)*255)).put((byte)(y*3)).put((byte)alpha(x));
                pixels.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            } finally {MemoryUtil.memFree(pixels);}
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,uvs,GL15.GL_STATIC_DRAW);
            ByteBuffer boneData=MemoryUtil.memCalloc(144),sourceData=MemoryUtil.memCalloc(uvs.length/2*32);
            try {
                new Matrix4f().get(0,boneData);new Matrix4f().get(64,boneData);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bones);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,boneData,GL15.GL_STATIC_DRAW);
                for(int i=0;i<uvs.length/2;i++){sourceData.putFloat(i*32+12,uvs[i*2]);sourceData.putFloat(i*32+16,uvs[i*2+1]);}
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,source);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,sourceData,GL15.GL_STATIC_DRAW);
            } finally {MemoryUtil.memFree(boneData);MemoryUtil.memFree(sourceData);}
            GL11.glViewport(0,0,layout.size,layout.size);RenderSystem.disableDepthTest();RenderSystem.disableBlend();RenderSystem.disableCull();RenderSystem.colorMask(true,true,true,true);
            for(boolean gpu:new boolean[]{false,true}) {
                var compile=GroupEffectRenderer.class.getDeclaredMethod(gpu?"skinProgram":"materialProgram",String.class);compile.setAccessible(true);
                int program=(int)compile.invoke(null,"/group_material.fsh");
                try {
                    GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
                    GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,8,0);
                    GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bones);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,source);
                    GL20.glUseProgram(program);uniform(program,"atlasLayout",5);uniform(program,"atlasSize",layout.size);
                    uniform(program,"atlasTriangles",uvs.length/6);uniform(program,"materialPass",1);
                    GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    for(int band=0;band<3;band++) {
                        uniform(program,"atlasBand",band);uniform(program,"alphaMode",band==2?0:band+1);uniform(program,"maskOnly",band==2?1:0);
                        GL11.glDrawArrays(GL11.GL_TRIANGLES,0,uvs.length/2);
                    }
                    if(GL11.glGetError()!=0)throw new AssertionError("Material draw GL error; GPU="+gpu);
                    compare(layout,uvs,gpu);
                } finally {GL20.glDeleteProgram(program);}
            }
            compute(layout,uvs,source,bones);
            if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("Material atlas GL error");
            System.out.println("Source texels preserved: CPU/GPU striped albedo, mirrored/rotated/fractional UVs, alpha bands and compute mapping");
        } catch(ReflectiveOperationException|java.io.IOException ex) {throw new AssertionError(ex);}
        finally {
            GL20.glUseProgram(0);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glBindVertexArray(0);
            GL30.glDeleteFramebuffers(fbo);GL30.glDeleteVertexArrays(vao);GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(bones);GL15.glDeleteBuffers(source);GL11.glDeleteTextures(target);GL11.glDeleteTextures(skin);
        }
    }
    private static int alpha(int x) {return new int[]{0,25,26,128,252,253,255,255}[x/8];}
    private static void uniform(int program,String name,int value) {GL20.glUniform1i(GL20.glGetUniformLocation(program,name),value);}
    private static void compare(MaterialAtlasLayout layout,float[] uvs,boolean gpu) {
        ByteBuffer pixels=MemoryUtil.memAlloc(layout.size*layout.size*4);
        try {
            GL11.glReadPixels(0,0,layout.size,layout.size,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            for(int t=0;t<uvs.length/6;t++)for(int band=0;band<3;band++)for(int y=13;y<51;y++)for(int x=1;x<63;x++) {
                // Only sample points inside this triangle's UV bounding rectangle.
                float u=(x+.5f)/64,v=(y+.5f)/64;
                int i=t*6;float loU=Math.min(uvs[i],Math.min(uvs[i+2],uvs[i+4])),hiU=Math.max(uvs[i],Math.max(uvs[i+2],uvs[i+4]));
                float loV=Math.min(uvs[i+1],Math.min(uvs[i+3],uvs[i+5])),hiV=Math.max(uvs[i+1],Math.max(uvs[i+3],uvs[i+5]));
                if(u<loU || u>hiU || v<loV || v>hiV)continue;
                int px=(int)layout.u(t,band,0,u),py=(int)layout.v(t,band,0,v),offset=(py*layout.size+px)*4;
                int a=alpha(x);boolean visible=a>=26 && (band==2 || (band==0?a>=253:a<253));
                int[] expected=visible?(band==2?new int[]{255,255,255,a}:new int[]{x*3,(y&1)*255,y*3,a}):new int[4];
                for(int c=0;c<4;c++)if((pixels.get(offset+c)&255)!=expected[c])throw new AssertionError("Resampled atlas texel: gpu="+gpu+" tri="+t+" band="+band+" source="+x+","+y+" channel="+c+" got="+(pixels.get(offset+c)&255)+" expected="+expected[c]);
            }
        } finally {MemoryUtil.memFree(pixels);}
    }
    private static void compute(MaterialAtlasLayout layout,float[] uvs,int source,int bones) throws java.io.IOException {
        int program=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL43.GL_COMPUTE_SHADER,"/group_entity.csh")),output=GL15.glGenBuffers();
        ByteBuffer data=MemoryUtil.memAlloc(uvs.length/2*3*36);
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,output);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data.capacity(),GL15.GL_STREAM_READ);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,source);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,bones);
            GL20.glUseProgram(program);uniform(program,"atlasLayout",5);uniform(program,"atlasSize",layout.size);
            GL43.glDispatchCompute(1,1,1);GL43.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,output);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
            for(int band=0;band<3;band++)for(int id=0;id<uvs.length/2;id++) {
                int offset=(band*uvs.length/2+id)*36;
                if(Math.abs(data.getFloat(offset+16)-layout.u(id/3,band,id%3,uvs[id*2])/layout.size)>1e-6 || Math.abs(data.getFloat(offset+20)-layout.v(id/3,band,id%3,uvs[id*2+1])/layout.size)>1e-6)
                    throw new AssertionError("GPU/CPU atlas UV mismatch");
            }
        } finally {MemoryUtil.memFree(data);GL15.glDeleteBuffers(output);GL20.glDeleteProgram(program);}
    }
}
