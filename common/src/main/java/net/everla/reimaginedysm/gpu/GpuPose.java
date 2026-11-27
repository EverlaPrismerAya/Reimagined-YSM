package net.everla.reimaginedysm.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/** Mutable draw data belongs to one animated instance, never to shared geometry. */
public final class GpuPose implements AutoCloseable {
    final WeakReference<float[]> owner;
    long touched = System.nanoTime();
    public final int boneSsbo;
    public final ByteBuffer boneBuffer;
    private int xformVbo, xformVao;
    private int groupVbo,groupVao;
    public int surfaceSolids,surfaceNeighbors;
    private boolean closed;

    GpuPose(float[] parameters, int boneCount) {
        owner = new WeakReference<>(parameters);
        boneBuffer = MemoryUtil.memAlloc(boneCount * 144);
        boneSsbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boneSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) boneCount * 144, GL15.GL_DYNAMIC_DRAW);
    }

    public int xformVbo() { return xformVbo; }
    public int xformVao() { return xformVao; }
    public int groupVbo() { return groupVbo; }
    public int groupVao() { return groupVao; }

    public void ensureSurfaceBuffers(GroupGpuMesh mesh) {
        if(surfaceSolids!=0)return;
        long words=(mesh.solidCount+31L)/32,bytes=Math.max(4,mesh.solidCount*words*4);
        if(bytes>GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("GPU surface adjacency exceeds device storage limit");
        surfaceSolids=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,surfaceSolids);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,Math.max(144L,mesh.solidCount*144L),GL15.GL_DYNAMIC_DRAW);
        surfaceNeighbors=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,surfaceNeighbors);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);
    }

    public void ensureGroupBuffers(GroupGpuMesh mesh) {
        if(groupVao!=0) return;
        groupVbo=GL15.glGenBuffers();groupVao=GL30.glGenVertexArrays();
        GL30.glBindVertexArray(groupVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,groupVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,(long)mesh.vertices*3*36,GL15.GL_DYNAMIC_DRAW);
        GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,36,0);
        GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,4,GL11.GL_UNSIGNED_BYTE,true,36,12);
        GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,36,16);
        GL20.glEnableVertexAttribArray(3);GL30.glVertexAttribIPointer(3,2,GL11.GL_SHORT,36,24);
        GL20.glEnableVertexAttribArray(4);GL30.glVertexAttribIPointer(4,2,GL11.GL_SHORT,36,28);
        GL20.glEnableVertexAttribArray(5);GL20.glVertexAttribPointer(5,3,GL11.GL_BYTE,true,36,32);
    }

    public void uploadBones() {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boneSsbo);
        // A queued draw can still reference the previous pose, even for this
        // instance (shadow, translucent pass or the next frame). Orphan storage
        // before uploading so the driver retains that draw's complete matrices.
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, boneBuffer.capacity(), GL15.GL_STREAM_DRAW);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, boneBuffer);
    }

    public void ensureXformBuffers(GpuMesh mesh) {
        if (xformVao != 0) return;
        xformVbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, xformVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) mesh.vertexCount * 36, GL15.GL_DYNAMIC_DRAW);
        xformVao = GL30.glGenVertexArrays();
        GlStateManager._glBindVertexArray(xformVao);
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, 36, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 36, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL15.GL_FLOAT, false, 36, 16L);
        GL20.glEnableVertexAttribArray(3);
        GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, 36, 24L);
        GL20.glEnableVertexAttribArray(4);
        GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, 36, 28L);
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5, 3, GL11.GL_BYTE, true, 36, 32L);
        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        GlStateManager._glDeleteBuffers(boneSsbo);
        if (xformVbo != 0) GlStateManager._glDeleteBuffers(xformVbo);
        if (xformVao != 0) GL30.glDeleteVertexArrays(xformVao);
        if(surfaceSolids!=0)GL15.glDeleteBuffers(surfaceSolids);
        if(surfaceNeighbors!=0)GL15.glDeleteBuffers(surfaceNeighbors);
        if(groupVbo!=0) GL15.glDeleteBuffers(groupVbo);
        if(groupVao!=0) GL30.glDeleteVertexArrays(groupVao);
        MemoryUtil.memFree(boneBuffer);
    }
}
