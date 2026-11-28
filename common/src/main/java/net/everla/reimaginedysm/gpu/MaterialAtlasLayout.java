package net.everla.reimaginedysm.gpu;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Packs source-texel-aligned triangle bounds without resampling the skin. */
public final class MaterialAtlasLayout implements AutoCloseable {
    public final int size;
    private final float[] mappings;
    private final int buffer, texture;

    public MaterialAtlasLayout(float[] uvs,int width,int height,int limit) {
        if(width<1 || height<1 || uvs.length==0 || uvs.length%6!=0)throw new IllegalArgumentException("Invalid material texture or triangle UVs");
        int triangles=uvs.length/6;
        int[] widths=new int[triangles],heights=new int[triangles];
        float[] source=new float[triangles*4];
        long area=0;int largest=1;
        for(int t=0;t<triangles;t++) {
            int i=t*6;
            float minU=Math.min(uvs[i],Math.min(uvs[i+2],uvs[i+4]))*width;
            float maxU=Math.max(uvs[i],Math.max(uvs[i+2],uvs[i+4]))*width;
            float minV=Math.min(uvs[i+1],Math.min(uvs[i+3],uvs[i+5]))*height;
            float maxV=Math.max(uvs[i+1],Math.max(uvs[i+3],uvs[i+5]))*height;
            int loU=(int)Math.floor(minU+1e-4f),loV=(int)Math.floor(minV+1e-4f);
            int w=Math.max(3,(int)Math.ceil(maxU-1e-4f)-loU+2);
            int h=Math.max(3,(int)Math.ceil(maxV-1e-4f)-loV+2);
            float det=(uvs[i+2]-uvs[i])*(uvs[i+5]-uvs[i+1])-(uvs[i+4]-uvs[i])*(uvs[i+3]-uvs[i+1]);
            boolean degenerate=Math.abs(det*(double)width*height)<1e-6;
            source[t*4]=degenerate?-w:width;source[t*4+1]=degenerate?h:height;
            source[t*4+2]=degenerate?0:-loU;source[t*4+3]=degenerate?0:-loV;
            widths[t]=w;heights[t]=h;area+=(long)w*h*3;largest=Math.max(largest,Math.max(w,h));
        }
        Integer[] order=new Integer[triangles*3];Arrays.setAll(order,i->i);
        Arrays.sort(order,(a,b)->Integer.compare(heights[b%triangles],heights[a%triangles]));
        mappings=new float[triangles*3*4];
        int side=32;
        while(side<largest || (long)side*side<area) {
            if(side>=limit)throw new IllegalStateException("Source-resolution material atlas exceeds GPU texture limit");
            side*=2;
        }
        for(;;side*=2) {
            if(side>limit)throw new IllegalStateException("Source-resolution material atlas exceeds GPU texture limit");
            int x=0,y=0,row=0;
            for(int id:order) {
                int t=id%triangles,w=widths[t],h=heights[t];
                if(x+w>side){x=0;y+=row;row=0;}
                mappings[id*4]=source[t*4];mappings[id*4+1]=source[t*4+1];
                mappings[id*4+2]=source[t*4+2]+x+1;mappings[id*4+3]=source[t*4+3]+y+1;
                x+=w;row=Math.max(row,h);
            }
            if(y+row<=side)break;
        }
        size=side;
        buffer=GL15.glGenBuffers();texture=GL11.glGenTextures();
        int previous=GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER);
        ByteBuffer data=MemoryUtil.memAlloc(mappings.length*4);
        try {
            for(float value:mappings)data.putFloat(value);data.flip();
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,buffer);GL15.glBufferData(GL31.GL_TEXTURE_BUFFER,data,GL15.GL_STATIC_DRAW);
            bind();GL13.glActiveTexture(GL13.GL_TEXTURE5);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_RGBA32F,buffer);
        } finally {MemoryUtil.memFree(data);GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER,previous);GL13.glActiveTexture(GL13.GL_TEXTURE0);}
    }

    public void bind() {
        GL13.glActiveTexture(GL13.GL_TEXTURE5);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public float u(int triangle,int band,int corner,float u) {
        int i=(band*mappings.length/12+triangle)*4;
        return (mappings[i]<0?(corner==1?-mappings[i]-2:0):u*mappings[i])+mappings[i+2];
    }
    public float v(int triangle,int band,int corner,float v) {
        int i=(band*mappings.length/12+triangle)*4;
        return (mappings[i]<0?(corner==2?mappings[i+1]-2:0):v*mappings[i+1])+mappings[i+3];
    }
    public void close() {GL15.glDeleteBuffers(buffer);GL11.glDeleteTextures(texture);}
}
