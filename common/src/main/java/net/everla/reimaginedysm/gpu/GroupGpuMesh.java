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

    GroupGpuMesh(ByteBuffer source,ByteBuffer indices) {
        vertices=indices.remaining()/4;
        var runs=new ArrayList<Range>();
        ByteBuffer data=MemoryUtil.memAlloc(vertices*32).order(ByteOrder.nativeOrder());
        try {
            int start=0,previous=-1;
            for(int i=0;i<vertices;i++) {
                int offset=indices.getInt(i*4)*32;
                int bone=Short.toUnsignedInt(source.getShort(offset+24));
                if(bone!=previous) {
                    if(i>start) runs.add(new Range(previous,start,i-start));
                    start=i;previous=bone;
                }
                for(int word=0;word<8;word++) data.putInt(source.getInt(offset+word*4));
            }
            if(vertices>start) runs.add(new Range(previous,start,vertices-start));
            data.flip();ranges=List.copyOf(runs);
            vao=GL30.glGenVertexArrays();vbo=GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,data,GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0);
            GL20.glEnableVertexAttribArray(1);GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,32,12);
            GL20.glEnableVertexAttribArray(2);GL20.glVertexAttribPointer(2,4,GL33.GL_INT_2_10_10_10_REV,true,32,20);
            GL20.glEnableVertexAttribArray(3);GL30.glVertexAttribIPointer(3,1,GL11.GL_UNSIGNED_SHORT,32,24);
            GL20.glEnableVertexAttribArray(4);GL20.glVertexAttribPointer(4,1,GL11.GL_UNSIGNED_BYTE,false,32,27);
        } finally {MemoryUtil.memFree(data);}
    }

    public void close() {GL15.glDeleteBuffers(vbo);GL30.glDeleteVertexArrays(vao);}
}
