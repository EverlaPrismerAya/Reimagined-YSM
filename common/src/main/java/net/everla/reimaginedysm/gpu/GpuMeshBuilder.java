package net.everla.reimaginedysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class GpuMeshBuilder {
    public static GpuMesh build(GeoModel model) {
        if (model.bakedBones == null || model.bakedBones.isEmpty()) return null;
        RenderSystem.assertOnRenderThread();
        if(model.renderSource!=null) {
            GpuMesh source=GpuRenderPath.getOrBuildMesh(model.renderSource);
            GpuMesh derived=source==null?null:derive(model,source);
            if(derived!=null)return derived;
        }
        ByteBuffer modelBuf = serializeModel(model);
        int[] meta = new int[9];
        long handle = GeoModel.nBuildGpuMesh(modelBuf, meta);
        if (handle == 0) {
            return null;
        }
        int vertexCount = meta[0];
        int indexCount = meta[1];
        int boneCount = meta[2];

        ByteBuffer vbuf = GeoModel.nGetGpuMeshVertexBuffer(handle);
        ByteBuffer ibuf = GeoModel.nGetGpuMeshIndexBuffer(handle);
        if (vbuf == null || ibuf == null) {
            GeoModel.nFreeGpuMesh(handle);
            return null;
        }
        vbuf.order(ByteOrder.nativeOrder());
        ibuf.order(ByteOrder.nativeOrder());

        int vao = GL30.glGenVertexArrays();
        int vbo = GlStateManager._glGenBuffers();
        int ibo = GlStateManager._glGenBuffers();

        boolean complete=false;
        try {
        GL30.glBindVertexArray(vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vbuf, GL15.GL_STATIC_DRAW);
        ShaderUtil.requireBuffer(GL15.GL_ARRAY_BUFFER,vbuf.remaining());
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ibuf, GL15.GL_STATIC_DRAW);
        ShaderUtil.requireBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibuf.remaining());

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, 32, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL15.GL_FLOAT, false, 32, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL33.GL_INT_2_10_10_10_REV, true, 32, 20L);
        GL20.glEnableVertexAttribArray(3);
        GL30.glVertexAttribIPointer(3, 1, GL15.GL_UNSIGNED_SHORT, 32, 24L);

        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 1, GL11.GL_UNSIGNED_BYTE, false, 32, 27L);

        GL30.glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        GpuMesh mesh=new GpuMesh(handle, vao, vbo, ibo, vertexCount, indexCount, boneCount, meta[3], meta[4], meta[5], meta[6], meta[7], meta[8]);
        mesh.faceIndices=faceIndices(model,vbuf,vertexCount);
        if(model.renderSource!=null && model.hasGroupStyles) mesh.groups=new GroupGpuMesh(model);
        complete=true;return mesh;
        }finally{GeoModel.nReleaseGpuMeshScratch(handle);if(!complete){GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(ibo);GL30.glDeleteVertexArrays(vao);GeoModel.nFreeGpuMesh(handle);}}
    }

    private record Vertex(int x,int y,int z,int u,int v,int normal,int bone,boolean cull) {}
    private static Vertex key(float x,float y,float z,float u,float v,int normal,int bone,boolean cull){return new Vertex(Float.floatToIntBits(x==0?0:x),Float.floatToIntBits(y==0?0:y),Float.floatToIntBits(z==0?0:z),Float.floatToIntBits(u==0?0:u),Float.floatToIntBits(v==0?0:v),normal&0x3fffffff,bone,cull);}
    private static java.util.List<java.util.IdentityHashMap<GeoModel.BakedQuad,int[]>> faceIndices(GeoModel model,ByteBuffer vertices,int count) {
        var lookup=new java.util.HashMap<Vertex,Integer>();
        for(int i=0;i<count;i++){int o=i*32;lookup.putIfAbsent(key(vertices.getFloat(o),vertices.getFloat(o+4),vertices.getFloat(o+8),vertices.getFloat(o+12),vertices.getFloat(o+16),vertices.getInt(o+20),Short.toUnsignedInt(vertices.getShort(o+24)),vertices.get(o+27)!=0),i);}
        var result=new java.util.ArrayList<java.util.IdentityHashMap<GeoModel.BakedQuad,int[]>>();
        for(int i=0;i<model.bakedBones.size();i++) {
            var faces=new java.util.IdentityHashMap<GeoModel.BakedQuad,int[]>();result.add(faces);
            for(var cube:model.bakedBones.get(i).acceleratedCubes())for(var face:cube.quads) {
                int normal=0;for(int axis=0;axis<3;axis++)normal|=(Math.round(face.normal[axis]*511)&1023)<<(axis*10);
                int[] indices=new int[6];int n=0;boolean found=true;
                for(int v:new int[]{0,1,2,0,2,3}){Integer index=lookup.get(key(face.positions[v*3],face.positions[v*3+1],face.positions[v*3+2],face.uvs[v*2],face.uvs[v*2+1],normal,i,cube.cullable));if(index==null){found=false;break;}indices[n++]=index;}
                if(found)faces.put(face,indices);
            }
        }
        return result;
    }
    private static GpuMesh derive(GeoModel model,GpuMesh source) {
        if(source.faceIndices==null)return null;
        var all=new java.util.ArrayList<Integer>();var first=new java.util.ArrayList<Integer>();var second=new java.util.ArrayList<Integer>();
        for(int i=0;i<model.bakedBones.size();i++) {
            var bone=model.bakedBones.get(i);
            for(var cube:bone.acceleratedCubes())for(var face:cube.quads) {
                int[] indices=source.faceIndices.get(i).get(face);if(indices==null)return null;
                for(int index:indices){all.add(index);if(bone.partMask==1||bone.partMask==3)first.add(index);if(bone.partMask==2||bone.partMask==3)second.add(index);}
            }
        }
        int vao=GL30.glGenVertexArrays(),ibo=GL15.glGenBuffers();
        try {
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,source.vbo);GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);
            var indices=org.lwjgl.system.MemoryUtil.memAllocInt(Math.max(1,all.size()+first.size()+second.size()));
            try{for(var list:java.util.List.of(all,first,second))for(int index:list)indices.put(index);indices.flip();GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);ShaderUtil.requireBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,indices.remaining()*4L);}finally{org.lwjgl.system.MemoryUtil.memFree(indices);}
            GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0L);
            GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,32,12L);
            GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,4,GL33.GL_INT_2_10_10_10_REV,true,32,20L);
            GL20.glEnableVertexAttribArray(3);GL30.glVertexAttribIPointer(3,1,GL11.GL_UNSIGNED_SHORT,32,24L);
            GL20.glEnableVertexAttribArray(4);GL20.glVertexAttribPointer(4,1,GL11.GL_UNSIGNED_BYTE,false,32,27L);
            var mesh=new GpuMesh(source.pointer,vao,source.vbo,ibo,source.vertexCount,all.size(),source.boneCount,all.size(),first.size(),all.size()+first.size(),second.size(),0,0);mesh.parent=source;
            if(model.hasGroupStyles)mesh.groups=new GroupGpuMesh(model);
            RenderMetrics.add("derivedIndexAllocations",1);return mesh;
        }catch(RuntimeException|Error error){GL15.glDeleteBuffers(ibo);GL30.glDeleteVertexArrays(vao);throw error;}
    }

    private static ByteBuffer serializeModel(GeoModel model) {
        int totalBones = model.bakedBones.size();
        int totalCubes = 0;
        int totalQuads = 0;
        for (GeoModel.BakedBone bone : model.bakedBones) {
            totalCubes += bone.acceleratedCubes().size();
            for (GeoModel.BakedCube cube : bone.acceleratedCubes()) {
                totalQuads += cube.quads.size();
            }
        }
        int sz = 4 + (totalBones * 25) + (totalCubes * 5) + (totalQuads * 92);
        ByteBuffer buf = ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder());
        buf.putInt(totalBones);
        for (GeoModel.BakedBone bone : model.bakedBones) {
            buf.putInt(bone.parentIdx);
            buf.putInt(bone.partMask);
            buf.put((byte) (bone.glow ? 1 : 0));
            buf.putFloat(bone.pivotX);
            buf.putFloat(bone.pivotY);
            buf.putFloat(bone.pivotZ);
            buf.putInt(bone.acceleratedCubes().size());
            for (GeoModel.BakedCube cube : bone.acceleratedCubes()) {
                buf.put((byte) (cube.cullable ? 1 : 0));
                buf.putInt(cube.quads.size());
                for (GeoModel.BakedQuad quad : cube.quads) {
                    for (float position : quad.positions) {
                        buf.putFloat(position);
                    }
                    for (float uv : quad.uvs) {
                        buf.putFloat(uv);
                    }
                    buf.putFloat(quad.normal[0]);
                    buf.putFloat(quad.normal[1]);
                    buf.putFloat(quad.normal[2]);
                }
            }
        }
        buf.position(0);
        return buf;
    }
}
