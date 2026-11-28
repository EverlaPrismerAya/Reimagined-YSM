package net.everla.ysmPhysLib;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/** CPU scene extraction only. Contact detection/projection stays on the GPU. */
final class GpuCollisionSnapshot {
    static final int MAX_COLLIDERS=4096;
    private final PhysDefinition definition;
    private final int[] islands;
    private final float[] reach, radius;
    private final Vector3f[] points;
    private final boolean[] visible, children;
    private final List<PhysCollider> contacts=new ArrayList<>();
    private final List<AABB> queries=new ArrayList<>();
    private final boolean[] terrain;
    private final int[] roots;
    private final float[] padding;
    private final HashSet<AABB> seen=new HashSet<>();
    private final Vector3f scale=new Vector3f();
    private final java.util.IdentityHashMap<PhysDefinition.Cube,PhysCollider> modelPool=new java.util.IdentityHashMap<>();
    private final List<PhysCollider> terrainPool=new ArrayList<>();
    private int terrainCursor;

    GpuCollisionSnapshot(PhysDefinition definition) {
        this.definition=definition;islands=definition.islands();
        int count=definition.bones.size();
        reach=new float[count];radius=new float[count];points=new Vector3f[count];
        visible=new boolean[count];children=new boolean[count];terrain=new boolean[count];roots=new int[count];padding=new float[count];
        java.util.Arrays.setAll(points,i->new Vector3f());
    }

    List<PhysCollider> collect(float[] parameters,Matrix4f[] animated,double x,double y,double z,
                              Function<AABB,Iterable<VoxelShape>> blocks) {
        contacts.clear();terrainCursor=0;
        for(int i:definition.workOrder) {
            int parent=definition.bones.get(i).parentIdx;
            animated[i].transformPosition(definition.pivot(i),points[i]);
            boolean ancestor=parent<0 || children[parent];
            int p=i*12;
            boolean scaled=parameters[p+6]!=0 && parameters[p+7]!=0 && parameters[p+8]!=0;
            visible[i]=ancestor && scaled && parameters[p+9]==0;
            children[i]=ancestor && scaled && parameters[p+10]==0;
            reach[i]=parent>=0 && islands[i]>=0 && islands[parent]==islands[i]
                    ?reach[parent]+points[parent].distance(points[i]):0;
        }
        for(var cube:definition.cubes)if(visible[cube.bone()]){var collider=modelPool.get(cube);if(collider==null){collider=new PhysCollider(cube.faces(),animated[cube.bone()]);modelPool.put(cube,collider);}else collider.reset(cube.faces(),animated[cube.bone()]);add(collider);}
        if(blocks==null) return contacts;
        java.util.Arrays.fill(radius,-1);
        java.util.Arrays.fill(terrain,false);java.util.Arrays.fill(padding,0);
        for(int i:definition.workOrder) if(islands[i]>=0) {
            int group=islands[i],parent=definition.bones.get(i).parentIdx;
            if(parent<0 || islands[parent]!=group) roots[group]=i;
            radius[group]=Math.max(radius[group],reach[i]);
        }
        for(var joint:definition.joints) if(joint.parameters().bc()) {
            int group=islands[joint.bone()];terrain[group]=true;
            animated[joint.bone()].getScale(scale);
            padding[group]=Math.max(padding[group],joint.parameters().c()/16f*Math.max(scale.x,Math.max(scale.y,scale.z)));
        }
        seen.clear();
        queries.clear();
        for(int group=0;group<radius.length;group++) if(terrain[group]) {
            var anchor=points[roots[group]];
            // Every allowed rotation is inside the sum of the hierarchy's edge lengths.
            // Include the whole island, not just the current endpoints: no GPU readback needed.
            double bound=radius[group]+padding[group]+.01;
            if(!Double.isFinite(bound) || bound>32) throw new Limit("GPU terrain reach exceeds 32 blocks");
            AABB query=new AABB(anchor.x,anchor.y,anchor.z,anchor.x,anchor.y,anchor.z).inflate(bound).move(x,y,z);
            // Adjacent islands often query the same terrain. Merge only overlapping
            // bounds without increasing scanned volume beyond their separate sum.
            // Looking only at the last bound keeps preparation linear in island count.
            if(!queries.isEmpty()) {
                var previous=queries.get(queries.size()-1);var merged=previous.minmax(query);
                if(previous.intersects(query) && merged.getXsize()<=64 && merged.getYsize()<=64 && merged.getZsize()<=64
                        && volume(merged)<=volume(previous)+volume(query)) {
                    queries.set(queries.size()-1,merged);continue;
                }
            }
            queries.add(query);
        }
        for(AABB query:queries) {
            for(VoxelShape shape:blocks.apply(query)) for(AABB box:shape.toAabbs())
                if(box.getXsize()>0 && box.getYsize()>0 && box.getZsize()>0 && seen.add(box))
                    {var rebased=box.move(-x,-y,-z);PhysCollider collider;if(terrainCursor==terrainPool.size()){collider=new PhysCollider(rebased);terrainPool.add(collider);}else{collider=terrainPool.get(terrainCursor);collider.reset(rebased);}terrainCursor++;add(collider);}
        }
        return contacts;
    }

    private static double volume(AABB bounds) {return bounds.getXsize()*bounds.getYsize()*bounds.getZsize();}

    private void add(PhysCollider collider) {
        if(contacts.size()==MAX_COLLIDERS) throw new Limit("GPU collision snapshot exceeds "+MAX_COLLIDERS+" colliders");
        contacts.add(collider);
    }
    static final class Limit extends RuntimeException { Limit(String reason) {super(reason);} }
}
