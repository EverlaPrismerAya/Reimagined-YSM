package net.everla.reimaginedysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Immutable expanded native mesh. Triangle IDs also address the material atlas. */
public final class GroupGpuMesh implements AutoCloseable {
    public record Range(int bone,int first,int count) {}
    public final List<Range> ranges;
    public final int vao,vbo,vertices;

    public final int solids,solidCount;
    public final boolean hasTranslucentFaces;
    private final float[] textureCoordinates;
    private MaterialAtlasLayout atlasLayout;
    private int atlasWidth,atlasHeight;
    GroupGpuMesh(GeoModel model) {
        var runs=new ArrayList<Range>();
        int count=0,cubes=0;
        for(var bone:model.bakedBones)for(var cube:bone.acceleratedCubes()){count+=cube.quads.size()*6;cubes++;}
        vertices=count;solidCount=cubes;
        textureCoordinates=new float[vertices*2];
        hasTranslucentFaces=model.bakedBones.stream().flatMap(b->b.acceleratedCubes().stream()).flatMap(c->c.quads.stream()).anyMatch(q->q.isTranslucent);
        ByteBuffer data=MemoryUtil.memAlloc(vertices*32).order(ByteOrder.nativeOrder());
        ByteBuffer bounds=MemoryUtil.memCalloc(Math.max(1,cubes)*144).order(ByteOrder.nativeOrder());
        try {
            int first=0,cubeId=0;
            for(int boneId=0;boneId<model.bakedBones.size();boneId++) {
                var bone=model.bakedBones.get(boneId);int start=first;
                for(var cube:bone.acceleratedCubes()) {
                    float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
                    float[] hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
                    for(var q:cube.quads)for(int v=0;v<4;v++)for(int axis=0;axis<3;axis++) {
                        lo[axis]=Math.min(lo[axis],q.positions[v*3+axis]);hi[axis]=Math.max(hi[axis],q.positions[v*3+axis]);
                    }
                    float[] center={(lo[0]+hi[0])/2,(lo[1]+hi[1])/2,(lo[2]+hi[2])/2};
                    boolean closed=cube.closedVolume&&cube.quads.size()==6;
                    if(closed)for(var q:cube.quads) {
                        float nx=q.normal[0],ny=q.normal[1],nz=q.normal[2];
                        if(nx*(q.positions[0]-center[0])+ny*(q.positions[1]-center[1])+nz*(q.positions[2]-center[2])<0){nx=-nx;ny=-ny;nz=-nz;}
                        bounds.putFloat(nx).putFloat(ny).putFloat(nz).putFloat(nx*q.positions[0]+ny*q.positions[1]+nz*q.positions[2]);
                    }else bounds.position(bounds.position()+96);
                    for(float v:lo)bounds.putFloat(v);bounds.putFloat(0);
                    for(float v:hi)bounds.putFloat(v);bounds.putFloat(0);
                    bounds.putInt(boneId).putInt(bone.surfaceGroup).putInt(closed?1:0).putInt(bone.partMask);
                    for(var q:cube.quads) {
                        int normal=0;for(int axis=0;axis<3;axis++)normal|=(Math.round(q.normal[axis]*511)&1023)<<(axis*10);
                        for(int v:new int[]{0,1,2,0,2,3}) {
                            textureCoordinates[first*2]=q.uvs[v*2];textureCoordinates[first*2+1]=q.uvs[v*2+1];
                            for(int axis=0;axis<3;axis++)data.putFloat(q.positions[v*3+axis]);
                            data.putFloat(q.uvs[v*2]).putFloat(q.uvs[v*2+1]).putInt(normal)
                                .putInt(boneId|(cube.cullable?1<<24:0)).putInt(cubeId);first++;
                        }
                    }
                    cubeId++;
                }
                if(first>start)runs.add(new Range(boneId,start,first-start));
            }
            data.flip();bounds.flip();ranges=List.copyOf(runs);
            solids=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,solids);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bounds,GL15.GL_STATIC_DRAW);
            vao=GL30.glGenVertexArrays();vbo=GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0);
            GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,32,12);
            GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,4,GL33.GL_INT_2_10_10_10_REV,true,32,20);
            GL20.glEnableVertexAttribArray(3);GL30.glVertexAttribIPointer(3,1,GL11.GL_UNSIGNED_SHORT,32,24);
            GL20.glEnableVertexAttribArray(4);GL20.glVertexAttribPointer(4,1,GL11.GL_UNSIGNED_BYTE,false,32,27);
        } finally {MemoryUtil.memFree(data);MemoryUtil.memFree(bounds);}
    }

    public MaterialAtlasLayout atlasLayout(int width,int height) {
        if(atlasLayout==null || atlasWidth!=width || atlasHeight!=height) {
            if(atlasLayout!=null)atlasLayout.close();
            atlasLayout=new MaterialAtlasLayout(textureCoordinates,width,height,GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
            atlasWidth=width;atlasHeight=height;
        }
        atlasLayout.bind();return atlasLayout;
    }
    public void close() {if(atlasLayout!=null)atlasLayout.close();GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(solids);GL30.glDeleteVertexArrays(vao);}
}
