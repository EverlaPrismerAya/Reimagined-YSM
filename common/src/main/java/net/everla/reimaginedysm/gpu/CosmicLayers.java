package net.everla.reimaginedysm.gpu;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
/** Evaluate layer-only GLSL math once on the same device; retain the exact 16-layer fragment algorithm. */
public final class CosmicLayers {
    private static float[] rotations,values;
    private static final Set<Integer> uploaded=new HashSet<>();
    private static boolean failed;
    public static void bind(int shader) {
        if(failed||!GL.getCapabilities().OpenGL43)return;
        if(rotations==null)try{prepare();}catch(Exception|LinkageError failure){failed=true;return;}
        if(uploaded.add(shader)) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shader,"cosmicRotation[0]"),false,rotations);
            GL20.glUniform4fv(GL20.glGetUniformLocation(shader,"cosmicLayer[0]"),values);
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(shader,"precomputedLayers"),1);
    }
    private static void prepare() throws Exception {
        int program=0,buffer=0;var data=MemoryUtil.memAlloc(16*80);
        try(var saved=new ShaderUtil.SavedComputeState(1)) {
            program=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL43.GL_COMPUTE_SHADER,"/group_cosmic_layers.csh"));buffer=GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,data.capacity(),GL15.GL_STATIC_READ);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);GL20.glUseProgram(program);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);rotations=new float[16*16];values=new float[16*4];
            for(int i=0;i<16;i++){for(int j=0;j<16;j++)rotations[i*16+j]=data.getFloat(i*80+j*4);for(int j=0;j<4;j++)values[i*4+j]=data.getFloat(i*80+64+j*4);}
        }finally{if(program!=0)GL20.glDeleteProgram(program);if(buffer!=0)GL15.glDeleteBuffers(buffer);MemoryUtil.memFree(data);}
    }
    public static void clear(){rotations=values=null;uploaded.clear();failed=false;}
}
