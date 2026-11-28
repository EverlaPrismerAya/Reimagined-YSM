package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Independent solvers and ownership per dependency island; one merged SSBO for all draw paths. */
final class IslandPhysics implements AutoCloseable {
    private static final Map<float[],IslandPhysics> OWNERS=new WeakHashMap<>();
    private static final Set<IslandPhysics> RESOURCES=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<PhysDefinition,List<GeoModel>> TOPOLOGIES=new WeakHashMap<>();
    private final WeakReference<AnimatedGeoModel> owner;
    final GeoModel geometry;
    private final List<AnimatedGeoModel> islands=new ArrayList<>();
    private final float[] base;
    private boolean applied,closed;
    private double time=Double.NaN;
    private long version,renderedVersion=-1;
    private final Matrix4f renderedRoot=new Matrix4f();
    private int renderedLight;
    private final float[] renderedParameters;
    private final Matrix4f[] readMatrices;
    private final boolean[] readValid,readVisible;
    private boolean[] renderedBackends;
    private int output;
    private final java.nio.ByteBuffer baseMatrices;
    private long touched=System.nanoTime();
    private Object world;
    private final java.nio.ByteBuffer matrixReadback=org.lwjgl.system.MemoryUtil.memAlloc(144);
    private IslandPhysics(AnimatedGeoModel owner) {
        this.owner=new WeakReference<>(owner);geometry=owner.getGeoModel();base=new float[owner.getMatrixData().length];
        baseMatrices=org.lwjgl.system.MemoryUtil.memAlloc(base.length/12*144);renderedParameters=new float[base.length];readMatrices=new Matrix4f[base.length/12];readValid=new boolean[readMatrices.length];readVisible=new boolean[readMatrices.length];
        var views=TOPOLOGIES.computeIfAbsent(geometry.ysmPhysics,definition->{
            int count=java.util.Arrays.stream(definition.islands()).max().orElse(-1)+1;var list=new ArrayList<GeoModel>();
            for(int i=0;i<count;i++)list.add(geometry.physicsView(new PhysDefinition(definition,i)));return List.copyOf(list);
        });
        for(var view:views)islands.add(new AnimatedGeoModel(view));RESOURCES.add(this);
    }
    static IslandPhysics find(float[] parameters){return OWNERS.get(parameters);}
    static void update(AnimatedGeoModel owner,AnimatableEntity<?> animatable,float partial,Matrix4f root) {
        var coordinator=OWNERS.computeIfAbsent(owner.getMatrixData(),p->new IslandPhysics(owner));
        coordinator.update(animatable,partial,root);
    }
    private void update(AnimatableEntity<?> animatable,float partial,Matrix4f root) {
        var current=owner.get();if(current==null)return;float[] p=current.getMatrixData();
        restore(p);System.arraycopy(p,0,base,0,p.length);
        for(var island:islands) {
            PlayerPhysics.beforeAnimation(island);System.arraycopy(base,0,island.getMatrixData(),0,base.length);
            PlayerPhysics.render(island,animatable,partial,root);
            for(var joint:island.getGeoModel().ysmPhysics.joints)System.arraycopy(island.getMatrixData(),joint.bone()*12,p,joint.bone()*12,3);
        }
        double next=((double)animatable.entity.tickCount+partial)/20;
        if(next!=time || world!=animatable.entity.level()){version++;time=next;}
        applied=true;touched=System.nanoTime();world=animatable.entity.level();
    }
    void restore(float[] p){if(!applied)return;for(var joint:geometry.ysmPhysics.joints)System.arraycopy(base,joint.bone()*12,p,joint.bone()*12,3);applied=false;}
    boolean active(){for(var island:islands)if(GpuPhysics.active(island.getMatrixData()))return true;return false;}
    int render(float[] parameters,Matrix4f root,int light) {
        if(!active())return 0;
        if(renderedBackends==null)renderedBackends=new boolean[islands.size()];
        for(int i=0;i<islands.size();i++){boolean gpu=GpuPhysics.active(islands.get(i).getMatrixData());if(renderedBackends[i]!=gpu){renderedVersion=-1;renderedBackends[i]=gpu;}}
        if(renderedVersion==version && renderedLight==light && renderedRoot.equals(root) && Arrays.equals(parameters,renderedParameters))return output;
        int previousRead=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER),previousWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        try {
            // Resolve failures before initializing the merged matrices, so a CPU island's correction is never lost.
            var active=new LinkedHashMap<AnimatedGeoModel,Integer>();
            for(var island:islands)if(GpuPhysics.active(island.getMatrixData())) {
                System.arraycopy(parameters,0,island.getMatrixData(),0,parameters.length);
                int buffer=GpuPhysics.render(island.getMatrixData(),root,light);
                if(buffer!=0)active.put(island,buffer);
                else {for(var joint:island.getGeoModel().ysmPhysics.joints)System.arraycopy(island.getMatrixData(),joint.bone()*12,parameters,joint.bone()*12,3);}
            }
            if(active.isEmpty())return 0;
            // Only the merged base traverses the full skeleton. Each solver writes its own island plus ancestors.
            var mesh=net.everla.reimaginedysm.gpu.GpuRenderPath.getOrBuildMesh(geometry);
            var cpu=PlayerPhysics.hideEndpoints(geometry,parameters);baseMatrices.clear();
            GeoModel.nComputeBoneMatrices(mesh.pointer,root.get(new float[16]),root.normal(new org.joml.Matrix3f()).get(new float[9]),cpu,light,baseMatrices);
            net.everla.reimaginedysm.gpu.GpuRenderPath.updateBoneVisibility(geometry.bakedBones,cpu,baseMatrices);baseMatrices.position(0).limit(baseMatrices.capacity());
            if(output==0)output=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,output);GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,baseMatrices,GL15.GL_STREAM_DRAW);
            for(var entry:active.entrySet()) {
                var island=entry.getKey();int source=entry.getValue();
                GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,output);
                var affected=island.getGeoModel().ysmPhysics.affected;
                for(int first=0;first<affected.length;) {
                    if(!affected[first]){first++;continue;}int end=first+1;while(end<affected.length&&affected[end])end++;
                    GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,(long)first*144,(long)first*144,(long)(end-first)*144);first=end;
                }
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);net.everla.reimaginedysm.gpu.GpuPose.changed(output);Arrays.fill(readValid,false);
            renderedVersion=version;renderedLight=light;renderedRoot.set(root);System.arraycopy(parameters,0,renderedParameters,0,parameters.length);return output;
        }finally{GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,previousRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,previousWrite);}
    }

    void disable(float[] parameters){fallback(parameters);restore(parameters);for(var island:islands)if(island.ysmPhysics!=null)island.ysmPhysics.invalidate();version++;}
    void fallback(float[] parameters){version++;for(var island:islands){GpuPhysics.fallback(island.getMatrixData());for(var joint:island.getGeoModel().ysmPhysics.joints)System.arraycopy(island.getMatrixData(),joint.bone()*12,parameters,joint.bone()*12,3);}}
    boolean read(float[] parameters,Matrix4f root,int light,Matrix4f[] matrices,boolean[] visible,boolean[] needed) {
        int buffer=render(parameters,root,light);if(buffer==0)return false;
        int previous=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
            for(int i=0;i<matrices.length;i++)if(needed==null||needed[i]) {
                if(!readValid[i]) {
                    matrixReadback.clear();GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)i*144,matrixReadback);
                    if(readMatrices[i]==null)readMatrices[i]=new Matrix4f();readMatrices[i].set(matrixReadback.asFloatBuffer());readVisible[i]=matrixReadback.getInt(132)==0;readValid[i]=true;
                    net.everla.reimaginedysm.gpu.RenderMetrics.add("poseReadbackBytes",144);
                }
                matrices[i]=readMatrices[i];visible[i]=readVisible[i];
            }
        }finally{GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,previous);}

        return true;
    }
    static void restore(AnimatedGeoModel owner){var coordinator=find(owner.getMatrixData());if(coordinator!=null)coordinator.restore(owner.getMatrixData());}
    static void dispose(GeoModel model){var iterator=OWNERS.values().iterator();while(iterator.hasNext()){var coordinator=iterator.next();if(coordinator.geometry==model){coordinator.close();iterator.remove();}}}
    static void tick(){Object currentWorld=net.minecraft.client.Minecraft.getInstance().level;long now=System.nanoTime();for(var coordinator:new ArrayList<>(RESOURCES))if(coordinator.owner.get()==null||coordinator.world!=currentWorld||now-coordinator.touched>5_000_000_000L){var current=coordinator.owner.get();if(current!=null)OWNERS.remove(current.getMatrixData());coordinator.close();}}
    static void shutdown(){for(var coordinator:new ArrayList<>(RESOURCES))coordinator.close();OWNERS.clear();TOPOLOGIES.clear();}
    public void close(){if(closed)return;var current=owner.get();if(current!=null)restore(current.getMatrixData());closed=true;RESOURCES.remove(this);for(var island:islands)GpuPhysics.deactivate(island.getMatrixData());if(output!=0){net.everla.reimaginedysm.gpu.GpuPose.forget(output);GL15.glDeleteBuffers(output);}org.lwjgl.system.MemoryUtil.memFree(matrixReadback);org.lwjgl.system.MemoryUtil.memFree(baseMatrices);output=0;}
}
