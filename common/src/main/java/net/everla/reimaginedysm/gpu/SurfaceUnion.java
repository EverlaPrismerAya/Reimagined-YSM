package net.everla.reimaginedysm.gpu;

import java.util.ArrayList;
import java.util.List;

/** Convex subtraction retains interpolated UVs/normals; works after bone/cube rotation. */
public final class SurfaceUnion {
    private SurfaceUnion() {}
    public record Plane(float x, float y, float z, float d) {
        float distance(float[] v) { return x*v[0]+y*v[1]+z*v[2]-d; }
    }
    public static List<List<float[]>> subtract(List<float[]> polygon, List<Plane> solid) {
        var outside=new ArrayList<List<float[]>>();
        List<float[]> inside=polygon;
        for (Plane plane:solid) {
            if (inside.size()<3) break;
            List<float[]> out=clip(inside,plane,false);
            if(out.size()>=3) outside.add(out);
            inside=clip(inside,plane,true);
        }
        return outside;
    }
    private static List<float[]> clip(List<float[]> polygon, Plane p, boolean inside) {
        var result=new ArrayList<float[]>();
        for(int i=0;i<polygon.size();i++) {
            float[] a=polygon.get(i), b=polygon.get((i+1)%polygon.size());
            float da=p.distance(a), db=p.distance(b);
            boolean keepA=inside?da<=0:da>0, keepB=inside?db<=0:db>0;
            if(keepA) result.add(a);
            if(keepA!=keepB) {
                float t=da/(da-db); float[] v=new float[a.length];
                for(int j=0;j<v.length;j++) v[j]=a[j]+(b[j]-a[j])*t;
                result.add(v);
            }
        }
        return result;
    }
}
