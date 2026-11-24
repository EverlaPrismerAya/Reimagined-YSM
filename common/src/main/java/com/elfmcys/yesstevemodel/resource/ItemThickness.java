package com.elfmcys.yesstevemodel.resource;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawFace;
import org.joml.Vector3f;

/** Item-style one-pixel extrusion, baked once for all render backends. */
public final class ItemThickness {
    private ItemThickness() {}

    public static boolean hasUv(RawFace face) {
        for(int i=0;i<4;i++) if(face.u[i]!=-1 || face.v[i]!=-1) return true;
        return false;
    }

    static GeoModel.BakedCube bake(RawFace face,YSMClientMapper.TranslucencyScanner scanner,float textureWidth,float textureHeight) {
        var cube=new GeoModel.BakedCube();cube.cullable=true;
        float du1=face.u[1]-face.u[0],dv1=face.v[1]-face.v[0];
        float du3=face.u[3]-face.u[0],dv3=face.v[3]-face.v[0];
        float determinant=du1*dv3-du3*dv1;
        if(!Float.isFinite(determinant) || Math.abs(determinant)<1e-12f) return cube;
        float minU=face.u[0],maxU=minU,minV=face.v[0],maxV=minV;
        for(int i=1;i<4;i++) {minU=Math.min(minU,face.u[i]);maxU=Math.max(maxU,face.u[i]);minV=Math.min(minV,face.v[i]);maxV=Math.max(maxV,face.v[i]);}
        int width=Math.max(1,scanner!=null && scanner.itemWidth()>0?scanner.itemWidth():(int)textureWidth);
        int height=Math.max(1,scanner!=null && scanner.itemHeight()>0?scanner.itemHeight():(int)textureHeight);
        int x0=(int)Math.floor(minU*width+.001f),x1=(int)Math.ceil(maxU*width-.001f);
        int y0=(int)Math.floor(minV*height+.001f),y1=(int)Math.ceil(maxV*height-.001f);
        if(x1<=x0 || y1<=y0) return cube;
        boolean[][] solid=new boolean[y1-y0][x1-x0];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            float u=(Math.max(minU,x/(float)width)+Math.min(maxU,(x+1f)/width))*.5f;
            float v=(Math.max(minV,y/(float)height)+Math.min(maxV,(y+1f)/height))*.5f;
            solid[y-y0][x-x0]=scanner==null || scanner.itemPixel(u,v);
        }
        int flags=scanner==null?YSMClientMapper.TranslucencyScanner.FLAG_VISIBLE:scanner.scan(face);
        if((flags&YSMClientMapper.TranslucencyScanner.FLAG_VISIBLE)==0) return cube;
        boolean translucent=(flags&YSMClientMapper.TranslucencyScanner.FLAG_TRANSLUCENT)!=0;
        Vector3f normal=new Vector3f(face.normal).normalize();
        if(!normal.isFinite()) return cube;
        var back=new GeoModel.BakedQuad();var front=new GeoModel.BakedQuad();
        front.isTranslucent=back.isTranslucent=translucent;
        put(normal,front.normal,0);put(new Vector3f(normal).negate(),back.normal,0);
        for(int i=0;i<4;i++) {
            put(new Vector3f(face.positions[i]).fma(1/32f,normal),front.positions,i*3);
            int j=3-i;
            put(new Vector3f(face.positions[j]).fma(-1/32f,normal),back.positions,i*3);
            front.uvs[i*2]=face.u[i];front.uvs[i*2+1]=face.v[i];back.uvs[i*2]=face.u[j];back.uvs[i*2+1]=face.v[j];
        }
        cube.quads.add(front);cube.quads.add(back);
        Vector3f alongU=new Vector3f(face.positions[1]).sub(new Vector3f(face.positions[0])).mul(dv3/determinant)
                .fma(-dv1/determinant,new Vector3f(face.positions[3]).sub(new Vector3f(face.positions[0])));
        Vector3f alongV=new Vector3f(face.positions[3]).sub(new Vector3f(face.positions[0])).mul(du1/determinant)
                .fma(-du3/determinant,new Vector3f(face.positions[1]).sub(new Vector3f(face.positions[0])));
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) if(solid[y-y0][x-x0]) {
            float loU=Math.max(minU,x/(float)width),hiU=Math.min(maxU,(x+1f)/width);
            float loV=Math.max(minV,y/(float)height),hiV=Math.min(maxV,(y+1f)/height);
            for(int edge=0;edge<4;edge++) {
                int nx=x+(edge==0?-1:edge==1?1:0),ny=y+(edge==2?-1:edge==3?1:0);
                if(nx>=x0 && nx<x1 && ny>=y0 && ny<y1 && solid[ny-y0][nx-x0]) continue;
                float au=edge==0?loU:edge==1?hiU:loU,av=edge==2?loV:edge==3?hiV:loV;
                float bu=edge<2?au:hiU,bv=edge<2?hiV:av;
                Vector3f a=point(face,alongU,alongV,au,av),b=point(face,alongU,alongV,bu,bv);
                Vector3f outward=new Vector3f(edge==0||edge==1?alongV:alongU).cross(normal).normalize();
                Vector3f wanted=new Vector3f(edge<2?alongU:alongV).mul(edge==0||edge==2?-1:1);
                if(outward.dot(wanted)<0) outward.negate();
                Vector3f[] vertices={new Vector3f(a).fma(1/32f,normal),new Vector3f(b).fma(1/32f,normal),new Vector3f(b).fma(-1/32f,normal),new Vector3f(a).fma(-1/32f,normal)};
                if(new Vector3f(vertices[1]).sub(vertices[0]).cross(new Vector3f(vertices[2]).sub(vertices[0])).dot(outward)<0) {
                    var swap=vertices[1];vertices[1]=vertices[3];vertices[3]=swap;
                }
                var quad=new GeoModel.BakedQuad();quad.isTranslucent=translucent;put(outward,quad.normal,0);
                for(int i=0;i<4;i++) {put(vertices[i],quad.positions,i*3);quad.uvs[i*2]=(loU+hiU)*.5f;quad.uvs[i*2+1]=(loV+hiV)*.5f;}
                cube.quads.add(quad);
            }
        }
        return cube;
    }

    private static Vector3f point(RawFace face,Vector3f u,Vector3f v,float a,float b) {
        return new Vector3f(face.positions[0]).fma(a-face.u[0],u).fma(b-face.v[0],v);
    }
    private static void put(Vector3f v,float[] output,int at) {output[at]=v.x;output[at+1]=v.y;output[at+2]=v.z;}
}
