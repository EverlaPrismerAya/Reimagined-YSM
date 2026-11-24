package net.everla.reimaginedysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

public final class GpuMesh {
    public final long pointer;
    public final int vao;
    public final int vbo;
    public final int ibo;
    public final int vertexCount;
    public final int indexCount;
    public final int boneCount;
    public final int partMask1Start, partMask1Count;
    public final int partMask2Start, partMask2Count;
    public final int partMask3Start, partMask3Count;
    private final WeakHashMap<float[], GpuPose> poses = new WeakHashMap<>();
    private final Set<GpuPose> poseResources = new HashSet<>();
    private boolean disposed = false;
    public GroupGpuMesh groups;

    GpuMesh(long pointer, int vao, int vbo, int ibo, int vertexCount, int indexCount, int boneCount, int pm1s, int pm1c, int pm2s, int pm2c, int pm3s, int pm3c) {
        this.pointer = pointer;
        this.vao = vao;
        this.vbo = vbo;
        this.ibo = ibo;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.boneCount = boneCount;
        this.partMask1Start = pm1s;
        this.partMask1Count = pm1c;
        this.partMask2Start = pm2s;
        this.partMask2Count = pm2c;
        this.partMask3Start = pm3s;
        this.partMask3Count = pm3c;
    }

    public int indexOffsetBytes(int renderPartMask) {
        if (renderPartMask == 0 || renderPartMask == 3) return 0;
        if (renderPartMask == 1) return partMask1Start * Integer.BYTES;
        if (renderPartMask == 2) return partMask2Start * Integer.BYTES;
        return 0;
    }

    public int indexDrawCount(int renderPartMask) {
        if (renderPartMask == 0) return indexCount;
        if (renderPartMask == 3) return indexCount;
        int self = (renderPartMask == 1) ? partMask1Count : (renderPartMask == 2) ? partMask2Count : 0;
        return self + partMask3Count;
    }

    public GpuPose pose(float[] parameters) {
        GpuPose pose = poses.get(parameters);
        if (pose == null) {
            pose = new GpuPose(parameters, boneCount);
            poses.put(parameters, pose);
            poseResources.add(pose);
        }
        pose.touched = System.nanoTime();
        return pose;
    }

    void cleanupPoses(boolean clear) {
        long now = System.nanoTime();
        var iterator = poseResources.iterator();
        while (iterator.hasNext()) {
            GpuPose pose = iterator.next();
            float[] owner = pose.owner.get();
            if (clear || owner == null || now - pose.touched > 5_000_000_000L) {
                if (owner != null) poses.remove(owner);
                pose.close();
                iterator.remove();
            }
        }
        // Expunge weak keys whose GL allocations were released above.
        poses.size();
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        if(groups!=null) groups.close();
        GlStateManager._glDeleteBuffers(vbo);
        GlStateManager._glDeleteBuffers(ibo);
        GL30.glDeleteVertexArrays(vao);
        cleanupPoses(true);
        if (pointer != 0) {
            GeoModel.nFreeGpuMesh(pointer);
        }
    }
}
