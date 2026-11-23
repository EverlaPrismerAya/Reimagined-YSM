package net.everla.ysmPhysLib;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** Transformed convex cube: planes for inside tests, triangles for rounded sphere contact. */
public final class PhysCollider {
    private record Face(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Vector3f normal) {}
    private final List<Face> faces=new ArrayList<>();
    private final Vector3f min=new Vector3f(Float.POSITIVE_INFINITY), max=new Vector3f(Float.NEGATIVE_INFINITY);
    final boolean worldBlock;

    /** World VoxelShape box, already rebased in double precision near the player. */
    public PhysCollider(net.minecraft.world.phys.AABB box) {
        worldBlock=true;
        min.set((float)box.minX,(float)box.minY,(float)box.minZ);
        max.set((float)box.maxX,(float)box.maxY,(float)box.maxZ);
        Vector3f a=new Vector3f(min.x,min.y,min.z), b=new Vector3f(max.x,min.y,min.z),
                c=new Vector3f(max.x,max.y,min.z), d=new Vector3f(min.x,max.y,min.z),
                e=new Vector3f(min.x,min.y,max.z), f=new Vector3f(max.x,min.y,max.z),
                g=new Vector3f(max.x,max.y,max.z), h=new Vector3f(min.x,max.y,max.z);
        faces.add(new Face(a,b,c,d,new Vector3f(0,0,-1)));
        faces.add(new Face(e,f,g,h,new Vector3f(0,0,1)));
        faces.add(new Face(a,d,h,e,new Vector3f(-1,0,0)));
        faces.add(new Face(b,c,g,f,new Vector3f(1,0,0)));
        faces.add(new Face(a,b,f,e,new Vector3f(0,-1,0)));
        faces.add(new Face(d,c,g,h,new Vector3f(0,1,0)));
    }

    /** std430 Collider: bounds, face count, six complete quads (no geometry truncation). */
    void writeGpu(java.nio.ByteBuffer out) {
        if(faces.size()>6) throw new IllegalArgumentException("GPU collider has more than six faces");
        int start=out.position();
        put(out,min);put(out,max);out.putInt(faces.size()).putInt(worldBlock?1:0).putInt(0).putInt(0);
        for(Face f:faces) {put(out,f.a);put(out,f.b);put(out,f.c);put(out,f.d);put(out,f.normal);}
        out.position(start+GpuPhysKernel.COLLIDER_BYTES);
    }
    private static void put(java.nio.ByteBuffer out,Vector3f v) {out.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(0);}

    /** Inclusive broad phase: touching faces and zero-thickness surfaces remain candidates. */
    boolean overlaps(net.minecraft.world.phys.AABB bounds) {
        return !faces.isEmpty() && max.x>=bounds.minX && min.x<=bounds.maxX
                && max.y>=bounds.minY && min.y<=bounds.maxY && max.z>=bounds.minZ && min.z<=bounds.maxZ;
    }

    /** Capsule/convex contact, with inverse-mass weighted endpoint projection. */
    public boolean resolveSegment(Vector3f start, Vector3f end, Vector3f startVelocity, Vector3f endVelocity,
                                  float radius, boolean fixedStart) {
        if(faces.isEmpty() || Math.max(start.x,end.x)<min.x-radius || Math.min(start.x,end.x)>max.x+radius
                || Math.max(start.y,end.y)<min.y-radius || Math.min(start.y,end.y)>max.y+radius
                || Math.max(start.z,end.z)<min.z-radius || Math.min(start.z,end.z)>max.z+radius) return false;
        Vector3f direction=new Vector3f(end).sub(start);
        float length2=direction.lengthSquared();
        if(length2<1e-14f) return fixedStart?resolve(end,endVelocity,radius):resolve(start,startVelocity,radius);
        // Clip the axis against all convex half-spaces: catches axes crossing the
        // entire cube even when both endpoints lie outside and radius is zero.
        float enter=0,leave=1;
        boolean intersects=faces.size()==6;
        for(Face face:faces) {
            float d=face.normal.dot(new Vector3f(start).sub(face.a)), slope=face.normal.dot(direction);
            if(Math.abs(slope)<1e-10f) {if(d>0) intersects=false;}
            else if(slope<0) enter=Math.max(enter,-d/slope);
            else leave=Math.min(leave,-d/slope);
        }
        intersects &= enter<=leave && enter<=1 && leave>=0;
        if(intersects && fixedStart) {
            // Keep a rooted segment on the side its anchor occupies. Choosing the
            // nearest face to an interior midpoint can eject it through the far wall,
            // which looks like collision was ignored while walking backwards.
            Face anchorFace=null;
            float clearance=-Float.MAX_VALUE;
            for(Face face:faces) {
                float distance=face.normal.dot(new Vector3f(start).sub(face.a));
                if(distance>clearance) {clearance=distance;anchorFace=face;}
            }
            if(clearance>=radius-1e-6f) {
                float length=(float)Math.sqrt(length2);
                Vector3f normal=anchorFace.normal;
                float required=Math.max(-1,Math.min(1,(radius+1e-5f-clearance)/length));
                Vector3f tangent=new Vector3f(direction).fma(-direction.dot(normal),normal);
                if(tangent.lengthSquared()<1e-12f) {
                    tangent.set(Math.abs(normal.y)<.9f?new Vector3f(0,1,0):new Vector3f(1,0,0));
                    tangent.fma(-tangent.dot(normal),normal);
                }
                tangent.normalize().mul((float)Math.sqrt(Math.max(0,1-required*required)));
                end.set(start).fma(length,tangent).fma(length*required,normal);
                float inward=endVelocity.dot(anchorFace.normal);
                if(inward<0) endVelocity.fma(-inward,anchorFace.normal);
                return true;
            }
        }
        Vector3f axis;
        if(intersects) axis=new Vector3f(start).fma((Math.max(0,enter)+Math.min(1,leave))*.5f,direction);
        else {
            Vector3f[] nearest={new Vector3f(start),new Vector3f()};
            float best=Float.POSITIVE_INFINITY;
            for(Face face:faces) {
                for(Vector3f[] triangle:new Vector3f[][]{{face.a,face.b,face.c},{face.a,face.c,face.d}}) {
                    Vector3f[] pair=closestSegmentTriangle(start,end,triangle[0],triangle[1],triangle[2]);
                    float distance=pair[0].distanceSquared(pair[1]);
                    if(distance<best) {best=distance;nearest=pair;}
                }
            }
            if(best>radius*radius+1e-12f) return false;
            axis=nearest[0];
        }
        float t=Math.max(0,Math.min(1,new Vector3f(axis).sub(start).dot(direction)/length2));
        Vector3f corrected=new Vector3f(axis), velocity=new Vector3f(startVelocity).lerp(endVelocity,t);
        if(!resolve(corrected,velocity,Math.max(radius,1e-6f))) return false;
        Vector3f correction=corrected.sub(axis);
        if(fixedStart) {
            // Rotating cannot remove the anchor sphere's own overlap. In particular,
            // a nearly tangent contact has t -> 0: dividing that unavoidable overlap
            // by its vanishing lever arm used to launch the entire bone.
            Vector3f anchor=new Vector3f(start);
            if(resolve(anchor,new Vector3f(),Math.max(radius,1e-6f))) {
                Vector3f anchorNormal=anchor.sub(start).normalize();
                float inward=direction.dot(anchorNormal);
                float speed=endVelocity.dot(anchorNormal);
                if(inward<=1e-6f && speed<0) endVelocity.fma(-speed,anchorNormal);
                if(inward>=-1e-6f) return false;
                Vector3f tangent=new Vector3f(direction).fma(-inward,anchorNormal);
                if(tangent.lengthSquared()>1e-12f) {
                    end.set(start).add(tangent.normalize().mul((float)Math.sqrt(length2)));
                    return true;
                }
                return false;
            }
        }
        float w0=fixedStart?0:1-t, w1=t, denominator=w0*w0+w1*w1;
        // A fixed root inside a collider is an unsatisfiable constraint.
        if(denominator<1e-8f) return false;
        Vector3f normal=new Vector3f(correction).normalize();
        if(fixedStart) {
            float length=(float)Math.sqrt(length2);
            // Use the separating plane at the contact, not correction/t. The
            // latter is singular at the anchor and can overshoot by many degrees.
            float required=Math.max(-1,Math.min(1,(direction.dot(normal)*t+correction.length())/length));
            Vector3f tangent=new Vector3f(direction).fma(-direction.dot(normal),normal);
            if(tangent.lengthSquared()<1e-12f) {
                tangent.set(Math.abs(normal.y)<.9f?new Vector3f(0,1,0):new Vector3f(1,0,0));
                tangent.fma(-tangent.dot(normal),normal);
            }
            tangent.normalize().mul((float)Math.sqrt(Math.max(0,1-required*required)));
            end.set(start).fma(length,tangent).fma(length*required,normal);
        } else {
            start.fma(w0/denominator,correction);end.fma(w1/denominator,correction);
        }
        float inward=new Vector3f(startVelocity).lerp(endVelocity,t).dot(normal);
        if(inward<0) {startVelocity.fma(-inward*w0/denominator,normal);endVelocity.fma(-inward*w1/denominator,normal);}
        return true;
    }

    private static Vector3f[] closestSegmentTriangle(Vector3f start,Vector3f end,Vector3f a,Vector3f b,Vector3f c) {
        Vector3f direction=new Vector3f(end).sub(start), normal=new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        float denominator=normal.dot(direction);
        if(Math.abs(denominator)>1e-12f) {
            float t=normal.dot(new Vector3f(a).sub(start))/denominator;
            if(t>=0 && t<=1) {
                Vector3f p=new Vector3f(start).fma(t,direction);
                if(PhysMath.closest(p,a,b,c).distanceSquared(p)<1e-12f) return new Vector3f[]{p,new Vector3f(p)};
            }
        }
        Vector3f[] best={new Vector3f(start),PhysMath.closest(start,a,b,c)};
        Vector3f q=PhysMath.closest(end,a,b,c);
        if(end.distanceSquared(q)<best[0].distanceSquared(best[1])) best=new Vector3f[]{new Vector3f(end),q};
        for(Vector3f[] edge:new Vector3f[][]{{a,b},{b,c},{c,a}}) {
            Vector3f[] pair=closestSegments(start,end,edge[0],edge[1]);
            if(pair[0].distanceSquared(pair[1])<best[0].distanceSquared(best[1])) best=pair;
        }
        return best;
    }

    private static Vector3f[] closestSegments(Vector3f p,Vector3f q,Vector3f a,Vector3f b) {
        Vector3f u=new Vector3f(q).sub(p),v=new Vector3f(b).sub(a),w=new Vector3f(p).sub(a);
        float uu=u.dot(u),vv=v.dot(v),uv=u.dot(v),uw=u.dot(w),vw=v.dot(w),s=0,t=0;
        if(uu<=1e-14f) t=vv>1e-14f?Math.max(0,Math.min(1,vw/vv)):0;
        else if(vv<=1e-14f) s=Math.max(0,Math.min(1,-uw/uu));
        else {
            float d=uu*vv-uv*uv;
            if(d>1e-14f) s=Math.max(0,Math.min(1,(uv*vw-uw*vv)/d));
            t=(uv*s+vw)/vv;
            if(t<0) {t=0;s=Math.max(0,Math.min(1,-uw/uu));}
            else if(t>1) {t=1;s=Math.max(0,Math.min(1,(uv-uw)/uu));}
        }
        return new Vector3f[]{new Vector3f(p).fma(s,u),new Vector3f(a).fma(t,v)};
    }

    public PhysCollider(float[] vertices, Matrix4f transform) {
        worldBlock=false;
        List<Vector3f> points=new ArrayList<>();
        Vector3f center=new Vector3f();
        for(int i=0;i+2<vertices.length;i+=3) {
            Vector3f p=transform.transformPosition(new Vector3f(vertices[i],vertices[i+1],vertices[i+2]));
            points.add(p);center.add(p);min.min(p);max.max(p);
        }
        if(points.isEmpty()) return;
        center.div(points.size());
        for(int i=0;i+3<points.size();i+=4) {
            Vector3f a=points.get(i),b=points.get(i+1),c=points.get(i+2),d=points.get(i+3);
            Vector3f normal=new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            if(normal.lengthSquared()<1e-15f) continue;
            normal.normalize();
            if(normal.dot(new Vector3f(center).sub(a))>0) normal.negate();
            faces.add(new Face(a,b,c,d,normal));
        }
    }

    /** Returns true on contact, and removes only inward velocity. */
    public boolean resolve(Vector3f p, Vector3f velocity, float radius) {
        if(faces.isEmpty() || p.x<min.x-radius || p.x>max.x+radius || p.y<min.y-radius || p.y>max.y+radius || p.z<min.z-radius || p.z>max.z+radius) return false;
        // An explicitly open/flat cube is a surface, not an infinite half-space.
        boolean inside=faces.size()==6;
        float nearestPlane=-Float.MAX_VALUE, closest2=Float.POSITIVE_INFINITY;
        Face exit=null;
        Vector3f closest=null;
        for(Face face:faces) {
            float distance=face.normal.dot(new Vector3f(p).sub(face.a));
            inside &= distance<=0;
            if(distance>nearestPlane) {nearestPlane=distance;exit=face;}
            Vector3f q=PhysMath.closest(p,face.a,face.b,face.c);
            float d=q.distanceSquared(p);
            if(d<closest2) {closest2=d;closest=q;}
            q=PhysMath.closest(p,face.a,face.c,face.d);d=q.distanceSquared(p);
            if(d<closest2) {closest2=d;closest=q;}
        }
        Vector3f normal;
        float push;
        if(inside) {normal=new Vector3f(exit.normal);push=radius-nearestPlane;}
        else {
            if(closest2>=radius*radius) return false;
            float distance=(float)Math.sqrt(closest2);
            normal=distance>1e-7f?new Vector3f(p).sub(closest).div(distance):new Vector3f(exit.normal);
            push=radius-distance;
        }
        p.fma(push+1e-6f,normal);
        float inward=velocity.dot(normal);
        if(inward<0) velocity.fma(-inward,normal);
        return true;
    }
}
