package net.everla.ysmPhysLib;

import com.mojang.blaze3d.platform.GlStateManager;
import net.everla.reimaginedysm.gpu.ShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** GL-only compute backend; state readback occurs only when handing ownership back to CPU. */
final class GpuPhysKernel implements AutoCloseable {
    // Islands have long, divergent contact loops. Separate groups let the GPU
    // schedule them independently instead of confining 64 islands to two groups.
    static final int NODE_BYTES=256, RESULT_BYTES=144, COLLIDER_BYTES=528, LANES=1;
    private static int program;
    private static final Map<String,Integer> uniforms=new HashMap<>();
    final GpuPhysModel model;
    final int instances;
    final int nodeCount;
    final int input, state, output, contacts;
    private final ByteBuffer inputData;
    private ByteBuffer contactData;
    private final float[] matrix=new float[16];
    private boolean closed;
    private float renderAlpha=1;

    static String capabilityReason() {
        var c=GL.getCapabilities();
        if(!c.OpenGL43) return "OpenGL 4.3 compute/SSBO required";
        if(GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)<6
                || GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)<6
                || GL11.glGetInteger(GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)<LANES
                || GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE,0)<LANES) return "insufficient compute/SSBO limits";
        return null;
    }

    static void compile() throws java.io.IOException {
        if(program==0) program=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL43.GL_COMPUTE_SHADER,"/ysm_physics.comp"));
    }

    GpuPhysKernel(GpuPhysModel model,int instances) {
        this.model=model;this.instances=instances;
        long bones=(long)model.original.length*instances;
        long nodes=bones*model.trees;
        long maximum=GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        long contactBytes=Math.max(COLLIDER_BYTES,(long)model.definition.cubes.size()*COLLIDER_BYTES);
        if(instances<1 || ((long)instances*model.trees+LANES-1)/LANES>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || nodes*NODE_BYTES>maximum || bones*RESULT_BYTES>maximum || bones*48>maximum
                || contactBytes>maximum || bones*48>Integer.MAX_VALUE || nodes>Integer.MAX_VALUE || contactBytes>Integer.MAX_VALUE)
            throw new IllegalArgumentException("model exceeds GPU physics buffer/workgroup limits");
        int[] allocated=new int[4];
        ByteBuffer animation=null,collisions=null;
        try {
            animation=MemoryUtil.memAlloc((int)bones*48);collisions=MemoryUtil.memCalloc((int)contactBytes);
            for(int i=0;i<4;i++) allocated[i]=GL15.glGenBuffers();
            allocate(allocated[0],bones*48);allocate(allocated[1],nodes*NODE_BYTES);
            allocate(allocated[2],bones*RESULT_BYTES);allocate(allocated[3],contactBytes);
        } catch(RuntimeException | Error e) {
            for(int id:allocated) if(id!=0) GL15.glDeleteBuffers(id);
            if(animation!=null) MemoryUtil.memFree(animation);if(collisions!=null) MemoryUtil.memFree(collisions);throw e;
        }
        input=allocated[0];state=allocated[1];output=allocated[2];contacts=allocated[3];
        nodeCount=(int)nodes;
        inputData=animation;contactData=collisions;
    }

    boolean seed(PhysSimulation simulation,GpuPhysics.Clock clock) {
        if(instances!=1) throw new IllegalStateException("CPU handoff requires one instance");
        var data=MemoryUtil.memCalloc(Math.multiplyExact(nodeCount,NODE_BYTES));
        try {
            if(!simulation.writeGpuState(data,model,clock)) return false;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
            return true;
        } finally {MemoryUtil.memFree(data);}
    }

    /** One synchronization on backend switch, never during steady GPU simulation. */
    void handoff(PhysSimulation simulation,GpuPhysics.Clock clock) {
        var data=MemoryUtil.memAlloc(Math.multiplyExact(nodeCount,NODE_BYTES));
        try {
            GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state);
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
            simulation.readGpuState(data,model,clock);
        } finally {MemoryUtil.memFree(data);}
    }

    private static void allocate(int buffer,long size) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,size,GL15.GL_DYNAMIC_COPY);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=size)
            throw new IllegalStateException("GPU physics buffer allocation failed ("+size+" bytes)");
    }

    /** Call with the render GL state saved; the offline harness also uses this entry point. */
    void solve(float[] parameters,Matrix4f root,Matrix4f renderRoot,java.util.List<PhysCollider> colliders,
               int steps,boolean reset,float dt,Vector3f frameVelocity,int packedLight) {
        solve(parameters,root,renderRoot,colliders,steps,reset,dt,frameVelocity,packedLight,1);
    }

    void solve(float[] parameters,Matrix4f root,Matrix4f renderRoot,java.util.List<PhysCollider> colliders,
               int steps,boolean reset,float dt,Vector3f frameVelocity,int packedLight,float alpha) {
        uploadAnimation(parameters);
        if(colliders.size()>4096) throw new IllegalArgumentException("GPU collision snapshot exceeds 4096 boxes");
        int required=Math.max(COLLIDER_BYTES,colliders.size()*COLLIDER_BYTES);
        if(required>contactData.capacity()) {
            int capacity=Math.max(required,Math.min(4096*COLLIDER_BYTES,contactData.capacity()*2));
            if(capacity>GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
                throw new IllegalArgumentException("GPU collision snapshot exceeds SSBO limit");
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,contacts);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,capacity,GL15.GL_DYNAMIC_COPY);
            contactData=MemoryUtil.memRealloc(contactData,capacity);
        }
        contactData.clear();
        for(var collider:colliders) collider.writeGpu(contactData);
        contactData.flip();
        if(contactData.hasRemaining()) {GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,contacts);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,contactData);}
        bind();
        integer("matrixOnly",0);integer("boneCount",model.original.length);integer("treeCount",model.trees);integer("colliderCount",colliders.size());
        integer("steps",steps);integer("resetState",reset?1:0);integer("packedLight",packedLight);
        GL20.glUniform1f(uniform("dt"),dt);GL20.glUniform3f(uniform("frameVelocity"),frameVelocity.x,frameVelocity.y,frameVelocity.z);
        renderAlpha=alpha;
        GL20.glUniform1f(uniform("renderAlpha"),renderAlpha);
        matrix("root",root);matrix("renderRoot",renderRoot);
        dispatch();
    }

    private void uploadAnimation(float[] parameters) {
        inputData.clear();
        for(int instance=0;instance<instances;instance++) for(int i:model.original)
            for(int j=0;j<12;j++) inputData.putFloat(parameters[i*12+j]);
        inputData.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,inputData);
    }

    void matrices(float[] parameters,Matrix4f renderRoot,int light) {
        uploadAnimation(parameters);
        bind();integer("matrixOnly",1);integer("boneCount",model.original.length);integer("treeCount",model.trees);integer("packedLight",light);
        GL20.glUniform1f(uniform("renderAlpha"),renderAlpha);
        matrix("renderRoot",renderRoot);dispatch();
    }

    private void bind() {
        GlStateManager._glUseProgram(program);
        integer("instanceCount",instances);
        int[] buffers={output,model.buffer,input,state,contacts,model.schedule};
        for(int i=0;i<buffers.length;i++) GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
    }
    private void dispatch() {
        // Do not overwrite matrices while a previous pass still reads this instance.
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL43.glDispatchCompute((instances*model.trees+LANES-1)/LANES,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        int error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL compute error 0x"+Integer.toHexString(error));
    }
    private static int uniform(String name) {return uniforms.computeIfAbsent(name,n->GL20.glGetUniformLocation(program,n));}
    private static void integer(String name,int value) {GL20.glUniform1i(uniform(name),value);}
    private void matrix(String name,Matrix4f value) {value.get(matrix);GL20.glUniformMatrix4fv(uniform(name),false,matrix);}

    static final class SavedState extends ShaderUtil.SavedComputeState {
        SavedState() {super(6);}
    }

    @Override public void close() {
        if(closed) return;closed=true;
        GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(state);GL15.glDeleteBuffers(output);GL15.glDeleteBuffers(contacts);
        MemoryUtil.memFree(inputData);MemoryUtil.memFree(contactData);
    }
    static void shutdown() {if(program!=0) GL20.glDeleteProgram(program);program=0;uniforms.clear();}
}
