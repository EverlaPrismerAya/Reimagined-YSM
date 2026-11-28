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
    public final SurfaceBvh bvh;
    private final float[] textureCoordinates;
    private MaterialAtlasLayout atlasLayout;
    private int atlasWidth,atlasHeight;
    public GroupGpuMesh(GeoModel model) {
        var runs=new ArrayList<Range>();
        int count=0;
        for(var bone:model.bakedBones)for(var cube:bone.acceleratedCubes())count+=cube.quads.size()*6;
        vertices=count;
        textureCoordinates=new float[vertices*2];
        hasTranslucentFaces=model.bakedBones.stream().flatMap(b->b.acceleratedCubes().stream()).flatMap(c->c.quads.stream()).anyMatch(q->q.isTranslucent);
        GeoModel source=model.renderSource==null?model:model.renderSource;
        var groups=new java.util.HashSet<Integer>();
        for(var bone:model.bakedBones)if(!bone.acceleratedCubes().isEmpty())groups.add(bone.surfaceGroup);
        var ids=new java.util.IdentityHashMap<GeoModel.BakedCube,Integer>();
        var leaves=new ArrayList<SurfaceBvh.Leaf>();
        var sourceCubes=new ArrayList<GeoModel.BakedCube>();var sourceBones=new ArrayList<Integer>();
        for(int i=0;i<source.bakedBones.size();i++) {
            var bone=source.bakedBones.get(i);if(!groups.contains(bone.surfaceGroup))continue;
            for(var cube:bone.acceleratedCubes()) {
                ids.put(cube,sourceCubes.size());sourceCubes.add(cube);sourceBones.add(i);
                leaves.add(new SurfaceBvh.Leaf(leaves.size(),bone.surfaceGroup,bounds(cube)));
            }
        }
        solidCount=sourceCubes.size();bvh=new SurfaceBvh(leaves);
        ByteBuffer data=MemoryUtil.memAlloc(Math.max(1,vertices*32)).order(ByteOrder.nativeOrder());
        ByteBuffer bounds=null;int[] allocated=new int[3];boolean complete=false;
        try {
            bounds=MemoryUtil.memCalloc(Math.max(1,solidCount)*144).order(ByteOrder.nativeOrder());
            for(int cubeId=0;cubeId<sourceCubes.size();cubeId++) {
                var cube=sourceCubes.get(cubeId);int boneId=sourceBones.get(cubeId);var bone=source.bakedBones.get(boneId);
                float[] box=bounds(cube);float[] center={(box[0]+box[3])/2,(box[1]+box[4])/2,(box[2]+box[5])/2};
                boolean closed=cube.closedVolume&&cube.quads.size()==6;
                if(closed)for(var q:cube.quads) {
                    float nx=q.normal[0],ny=q.normal[1],nz=q.normal[2];
                    if(nx*(q.positions[0]-center[0])+ny*(q.positions[1]-center[1])+nz*(q.positions[2]-center[2])<0){nx=-nx;ny=-ny;nz=-nz;}
                    bounds.putFloat(nx).putFloat(ny).putFloat(nz).putFloat(nx*q.positions[0]+ny*q.positions[1]+nz*q.positions[2]);
                }else bounds.position(bounds.position()+96);
                bounds.putFloat(box[0]).putFloat(box[1]).putFloat(box[2]).putInt(bvh.roots[cubeId]);
                bounds.putFloat(box[3]).putFloat(box[4]).putFloat(box[5]).putFloat(0);
                bounds.putInt(boneId).putInt(bone.surfaceGroup).putInt(closed?1:0).putInt(bone.partMask);
            }
            int first=0;
            for(int boneId=0;boneId<model.bakedBones.size();boneId++) {
                var bone=model.bakedBones.get(boneId);int start=first;
                for(var cube:bone.acceleratedCubes()) {
                    int cubeId=ids.get(cube.sourceCube==null?cube:cube.sourceCube);
                    for(var q:cube.quads) {
                        int normal=0;for(int axis=0;axis<3;axis++)normal|=(Math.round(q.normal[axis]*511)&1023)<<(axis*10);
                        for(int v:new int[]{0,1,2,0,2,3}) {
                            textureCoordinates[first*2]=q.uvs[v*2];textureCoordinates[first*2+1]=q.uvs[v*2+1];
                            for(int axis=0;axis<3;axis++)data.putFloat(q.positions[v*3+axis]);
                            data.putFloat(q.uvs[v*2]).putFloat(q.uvs[v*2+1]).putInt(normal)
                                .putInt(boneId|(cube.cullable?1<<24:0)).putInt(cubeId);first++;
                        }
                    }
                }
                if(first>start)appendRange(runs,model,boneId,start,first-start);
            }
            data.flip();bounds.flip();ranges=List.copyOf(runs);
            solids=GL15.glGenBuffers();allocated[0]=solids;GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,solids);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bounds,GL15.GL_STATIC_DRAW);ShaderUtil.requireBuffer(GL43.GL_SHADER_STORAGE_BUFFER,bounds.remaining());
            vao=GL30.glGenVertexArrays();allocated[1]=vao;vbo=GL15.glGenBuffers();allocated[2]=vbo;
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);ShaderUtil.requireBuffer(GL15.GL_ARRAY_BUFFER,data.remaining());
            GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0);
            GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,32,12);
            GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,4,GL33.GL_INT_2_10_10_10_REV,true,32,20);
            GL20.glEnableVertexAttribArray(3);GL30.glVertexAttribIPointer(3,1,GL11.GL_UNSIGNED_SHORT,32,24);
            GL20.glEnableVertexAttribArray(4);GL20.glVertexAttribPointer(4,1,GL11.GL_UNSIGNED_BYTE,false,32,27);
            complete=true;
        } finally {MemoryUtil.memFree(data);if(bounds!=null)MemoryUtil.memFree(bounds);if(!complete){if(allocated[0]!=0)GL15.glDeleteBuffers(allocated[0]);if(allocated[1]!=0)GL30.glDeleteVertexArrays(allocated[1]);if(allocated[2]!=0)GL15.glDeleteBuffers(allocated[2]);}}
    }

    static void appendRange(List<Range> ranges,GeoModel model,int boneId,int first,int count) {
        if(!ranges.isEmpty()) {
            var previous=ranges.get(ranges.size()-1);
            var a=model.bakedBones.get(previous.bone());var b=model.bakedBones.get(boneId);
            // Bone transforms/visibility and clipping IDs remain per vertex. Only
            // uniforms and part selection must agree before merging adjacent draws.
            if(previous.first()+previous.count()==first && a.partMask==b.partMask && a.glow==b.glow
                    && a.style.equals(b.style) && a.outlineGroup==b.outlineGroup
                    && a.cosmicEffect.equals(b.cosmicEffect)) {
                ranges.set(ranges.size()-1,new Range(previous.bone(),previous.first(),previous.count()+count));
                return;
            }
        }
        ranges.add(new Range(boneId,first,count));
    }

    private static float[] bounds(GeoModel.BakedCube cube) {
        float[] box={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(var q:cube.quads)for(int v=0;v<4;v++)for(int a=0;a<3;a++){float p=q.positions[v*3+a];box[a]=Math.min(box[a],p);box[a+3]=Math.max(box[a+3],p);}return box;
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
