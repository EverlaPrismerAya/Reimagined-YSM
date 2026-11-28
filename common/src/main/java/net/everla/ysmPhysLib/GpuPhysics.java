package net.everla.ysmPhysLib;

import net.everla.reimaginedysm.core.YesSteveModel;
import com.elfmcys.yesstevemodel.config.ClientConfigScope;
import com.elfmcys.yesstevemodel.config.GeneralConfig;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import net.everla.reimaginedysm.compat.optifine.OptiFineDetector;
import net.everla.reimaginedysm.gpu.BoneSkinShader;
import net.everla.reimaginedysm.gpu.GpuCapability;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import java.lang.ref.WeakReference;
import java.util.*;

/** Render-thread coordinator. A failed/unsupported request always resolves on CPU before fallback drawing. */
public final class GpuPhysics {
    private static final Map<float[],Instance> instances=new WeakHashMap<>();
    private static final Set<Instance> resources=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<PhysDefinition,GpuPhysModel> models=new IdentityHashMap<>();
    private static final Map<PhysDefinition,String> rejected=new WeakHashMap<>();
    private static final Set<String> logged=new HashSet<>();
    private static final Map<PhysDefinition,Timings> timings=new WeakHashMap<>();
    private static String shaderFailure;
    private static String lastReason="GPU physics disabled";
    private GpuPhysics() {}

    public static String getLastReason() {return lastReason;}
    static boolean active(float[] parameters) {return instances.containsKey(parameters);}

    static boolean prepare(AnimatedGeoModel owner,Matrix4f root,double time,boolean paused,Object world,
                           double x,double y,double z,int fps,boolean textured,java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> blockCollisions) {
        float[] parameters=owner.getMatrixData();
        if(!PhysConfig.gpuEnabled()) {lastReason="GPU physics disabled";deactivate(parameters);return false;}
        String reason=null;
        if(net.everla.reimaginedysm.compat.firstperson.FirstPersonCompat.isFirstPersonActive()) reason="FirstPerson body uses CPU rendering and physics";
        else if(!textured || ClientConfigScope.effective(GeneralConfig.USE_COMPATIBILITY_RENDERER).get()
                || !ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER).get()) reason="GPU renderer required";
        else if(OptiFineDetector.isOptifinePresent()) reason="OptiFine is unsupported";
        else if(shaderFailure!=null) reason=shaderFailure;
        else if(!GpuCapability.isAvailable()) reason=GpuCapability.getReason();
        else if(!GL.getCapabilities().OpenGL43) reason="OpenGL 4.3 required";
        else if(!root.isFinite() || Math.abs(root.determinant())<1e-12f) reason="singular/non-finite model transform";
        else if(!supportsDefinition(owner.getGeoModel().ysmPhysics)) reason="model has no physical joints";
        else if(!supportedScale(owner.getGeoModel().ysmPhysics,parameters,root)) reason="nonuniform/singular collision transforms require CPU physics";
        else if(requiresCpuPose(owner.getGeoModel(),gpuAffected(owner.getGeoModel().ysmPhysics),parameters)) reason="physical subtree requires a CPU pose (attachment, locator, bone query or FacingCamera)";
        else if(rejected.containsKey(owner.getGeoModel().ysmPhysics)) reason=rejected.get(owner.getGeoModel().ysmPhysics);
        if(reason!=null) {report(reason);deactivate(parameters);return false;}
        var cost=timing(owner.getGeoModel().ysmPhysics,world,fps);
        var existing=instances.get(parameters);
        if(existing!=null) existing.pollTimers();
        if(!cost.useGpu()) {
            report(cost.cpuCount<8?"sampling CPU physics cost":"CPU physics selected by measured solve cost");
            deactivate(parameters);return false;
        }
        RenderSystem.assertOnRenderThread();
        try(var saved=new GpuPhysKernel.SavedState()) {
            reason=GpuPhysKernel.capabilityReason();
            if(reason!=null) {report(reason);deactivate(parameters);return false;}
            if(!BoneSkinShader.ensureCompiled()) {report("GPU skin shader unavailable");deactivate(parameters);return false;}
            try {GpuPhysKernel.compile();}
            catch(Exception e) {shaderFailure="GPU physics shader unavailable: "+e.getMessage();throw e;}
            var geometry=owner.getGeoModel();
            var model=models.get(geometry.ysmPhysics);
            boolean created=false;
            if(model==null) {
                boolean[] glowing=new boolean[geometry.bakedBones.size()];
                for(int i=0;i<glowing.length;i++) glowing[i]=geometry.bakedBones.get(i).glow;
                model=new GpuPhysModel(geometry.ysmPhysics,glowing);models.put(geometry.ysmPhysics,model);created=true;
            }
            var instance=instances.get(parameters);
            if(instance==null) {
                try {instance=new Instance(owner,model);}
                catch(RuntimeException | Error e) {if(created) {models.remove(geometry.ysmPhysics);model.close();}throw e;}
                instances.put(parameters,instance);resources.add(instance);model.users++;
                instance.kernel.seed(owner.ysmPhysics,instance.clock);
                owner.ysmPhysics.partition(parameters,true);
            }
            // A shadow/main/extra draw at the same simulation time must not replace
            // the first pass's world-space targets with a different camera's pose.
            if(instance.time!=time || instance.world!=world || instance.fps!=fps) {
                instance.root.set(root);instance.time=time;instance.paused=paused;instance.world=world;
                instance.x=x;instance.y=y;instance.z=z;instance.fps=fps;
                instance.blockCollisions=blockCollisions;
            }
            instance.touched=System.nanoTime();
            owner.ysmPhysics.update(parameters,instance.root,time,paused,world,instance.x,instance.y,instance.z,instance.blockCollisions);
            lastReason="GPU physics active: integration, constraints, model and terrain contacts";return true;
        } catch(Exception | LinkageError e) {
            report("GPU physics initialization failed: "+e.getMessage());
            rejected.put(owner.getGeoModel().ysmPhysics,lastReason);deactivate(parameters);return false;
        } catch(OutOfMemoryError e) {
            report("GPU physics allocation failed");rejected.put(owner.getGeoModel().ysmPhysics,lastReason);deactivate(parameters);return false;
        }
    }

    /** Returns the instance's final bone SSBO, or 0 after applying CPU fallback to parameters. */
    public static int render(float[] parameters,Matrix4f renderRoot,int packedLight) {
        var instance=instances.get(parameters);
        if(instance==null) return 0;
        try(var saved=new GpuPhysKernel.SavedState()) {
            instance.draw(parameters,renderRoot,packedLight);return instance.kernel.output;
        } catch(GpuCollisionSnapshot.Limit e) {
            report(e.getMessage());fallback(parameters);return 0;
        } catch(Exception | LinkageError | OutOfMemoryError e) {
            report("GPU physics dispatch failed: "+e.getMessage());
            rejected.put(instance.kernel.model.definition,lastReason);fallback(parameters);return 0;
        }
    }

    public static void fallback(float[] parameters) {
        var instance=instances.get(parameters);
        if(instance==null) return;
        var owner=instance.owner.get();
        // Even buffered draws must advance an instance that just acquired GPU ownership.
        // Otherwise prepare invalidates CPU state every frame and fallback only draws reset poses.
        if(!Double.isFinite(instance.drawn)) try(var saved=new GpuPhysKernel.SavedState()) {
            instance.draw(parameters,new Matrix4f(),0);
        } catch(Exception | LinkageError e) {report("GPU buffered handoff dispatch failed: "+e.getMessage());}
        deactivate(parameters);
        if(owner!=null) {
            owner.ysmPhysics.setFrameRate(instance.fps);
            owner.ysmPhysics.update(parameters,instance.root,instance.time,instance.paused,instance.world,instance.x,instance.y,instance.z,instance.blockCollisions);
        }
    }

    /** Material clipping needs CPU-readable current matrices, but the solver retains GPU ownership. */
    public static boolean readRenderMatrices(float[] parameters,Matrix4f root,int light,
                                              Matrix4f[] matrices,boolean[] visible) {
        if(!active(parameters)) return false;
        int buffer=render(parameters,root,light);
        if(buffer==0) return false;
        var instance=instances.get(parameters);
        var data=org.lwjgl.system.MemoryUtil.memAlloc(matrices.length*144);
        int previous=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER,buffer);
            GL15.glGetBufferSubData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER,0,data);
            for(int sorted=0;sorted<matrices.length;sorted++) {
                int original=instance.kernel.model.original[sorted];
                matrices[original]=new Matrix4f(data.asFloatBuffer().position(original*36));
                visible[original]=data.getInt(original*144+132)==0;
            }
            return true;
        } finally {
            GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER,previous);
            org.lwjgl.system.MemoryUtil.memFree(data);
        }
    }

    static void deactivate(float[] parameters) {
        var instance=instances.remove(parameters);
        if(instance!=null) {
            var owner=instance.owner.get();
            try {
                if(owner!=null) {
                    owner.ysmPhysics.partition(parameters,false);
                    if(Double.isFinite(instance.drawn)) try(var saved=new GpuPhysKernel.SavedState()) {
                        instance.kernel.handoff(owner.ysmPhysics,instance.clock);
                    }
                }
            } catch(RuntimeException | OutOfMemoryError e) {
                if(owner!=null) owner.ysmPhysics.invalidate();
                report("GPU physics state handoff failed: "+e.getMessage());
            } finally {release(instance);}
        }
    }
    private static void release(Instance instance) {
        if(!resources.remove(instance)) return;
        var owner=instance.owner.get();
        if(owner!=null) owner.ysmPhysics.partition(owner.getMatrixData(),false);
        instance.kernel.close();
        instance.closeTimers();
        var model=instance.kernel.model;
        if(--model.users==0) {models.remove(model.definition);model.close();}
    }
    public static void disposeModel(GeoModel model) {
        var iterator=instances.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();
            if(entry.getValue().kernel.model.definition==model.ysmPhysics) {release(entry.getValue());iterator.remove();}
        }
        rejected.remove(model.ysmPhysics);
        timings.remove(model.ysmPhysics);
    }
    /** Called every client tick, including world unloads and frames without any rendered players. */
    public static void tick() {
        if(resources.isEmpty()) return;
        RenderSystem.assertOnRenderThread();
        var world=Minecraft.getInstance().level;
        long now=System.nanoTime();
        var iterator=instances.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var instance=entry.getValue();
            if(instance.owner.get()==null || instance.world!=world || now-instance.touched>5_000_000_000L || !PhysConfig.gpuEnabled() || PhysConfig.frameRate()==0) {
                var owner=instance.owner.get();if(owner!=null) owner.ysmPhysics.partition(entry.getKey(),false);
                release(instance);iterator.remove();
            }
        }
        // WeakHashMap expunges dead animation arrays; retain GL objects until explicitly deleted here.
        for(var instance:new ArrayList<>(resources)) if(instance.owner.get()==null) release(instance);
    }
    public static void shutdown() {
        for(var instance:new ArrayList<>(resources)) release(instance);
        instances.clear();rejected.clear();timings.clear();GpuPhysKernel.shutdown();shaderFailure=null;
    }
    /** CPU baseline uses the actual solver at the actual cadence, never a bone-count guess. */
    static void recordCpu(PhysDefinition definition,Object world,int fps,double time,long elapsed) {
        if(!PhysConfig.gpuEnabled()) return;
        timing(definition,world,fps).cpu(time,elapsed);
    }
    static Timings timing(PhysDefinition definition,Object world,int fps) {
        var cost=timings.computeIfAbsent(definition,ignored->new Timings());cost.context(world,fps);return cost;
    }

    static final class Timings {
        final long[] cpu=new long[8],gpu=new long[8];
        final long[] preparations=new long[8],devices=new long[8],submissions=new long[8];
        int cpuCount,gpuCount,cpuCursor,gpuCursor,fps,generation;
        long cpuMedian,gpuMedian,rejectedCpuMedian,rejectedGpuMedian;
        long rejectedPreparationMedian,rejectedDeviceMedian,rejectedSubmissionMedian;
        boolean rejectedCost;
        double cpuWarmupStart=Double.NaN;
        WeakReference<Object> world=new WeakReference<>(null);
        void context(Object world,int fps) {
            if(this.world.get()!=world || this.fps!=fps) {
                this.world=new WeakReference<>(world);this.fps=fps;cpuCount=gpuCount=cpuCursor=gpuCursor=0;
                generation++;
                rejectedCost=false;rejectedCpuMedian=rejectedGpuMedian=0;
                rejectedPreparationMedian=rejectedDeviceMedian=rejectedSubmissionMedian=0;
                cpuWarmupStart=Double.NaN;
            }
        }
        void cpu(long elapsed) {cpu[cpuCursor]=elapsed;cpuCursor=(cpuCursor+1)&7;cpuCount=Math.min(8,cpuCount+1);if(cpuCount==8) cpuMedian=median(cpu);}
        void cpu(double time,long elapsed) {
            if(!Double.isFinite(time)) return;
            if(!Double.isFinite(cpuWarmupStart) || time<cpuWarmupStart) cpuWarmupStart=time;
            // Initial falling poses can be much cheaper than settled contact.
            // Use simulation time so high display rates cannot skip warmup.
            if(time-cpuWarmupStart>=1) cpu(elapsed);
        }
        void gpu(long elapsed) {gpu[gpuCursor]=elapsed;gpuCursor=(gpuCursor+1)&7;gpuCount=Math.min(8,gpuCount+1);if(gpuCount==8) gpuMedian=median(gpu);}
        void gpu(long preparation,long device,long submission) {
            preparations[gpuCursor]=preparation;devices[gpuCursor]=device;submissions[gpuCursor]=submission;
            gpu(preparation+Math.max(device,submission));
        }
        boolean useGpu() {
            if(!rejectedCost && cpuCount==8 && gpuCount==8 && gpuMedian>=cpuMedian*.9) {
                rejectedCost=true;rejectedCpuMedian=cpuMedian;rejectedGpuMedian=gpuMedian;
                rejectedPreparationMedian=median(preparations);rejectedDeviceMedian=median(devices);rejectedSubmissionMedian=median(submissions);
            }
            return !rejectedCost && cpuCount==8 && (gpuCount<8 || gpuMedian<cpuMedian*.9);
        }
        private static long median(long[] values) {long[] sorted=values.clone();Arrays.sort(sorted);return sorted[4];}
    }
    private static void report(String reason) {
        lastReason=reason;
        if(logged.add(reason)) YesSteveModel.LOGGER.info("GPU physics -> CPU: {}",reason);
    }

    private static boolean[] gpuAffected(PhysDefinition definition) {
        boolean[] result=definition.affected.clone(),cpu=definition.cpuCollisionIslands();
        for(int i=0;i<result.length;i++) result[i] &= !cpu[i];
        return result;
    }

    static boolean requiresCpuPose(GeoModel model,boolean[] affected,float[] parameters) {
        var ids=new HashSet<Integer>();
        for(var list:List.of(model.leftHandIds,model.rightHandIds,model.elytraIds,model.tacPistolIds,model.tacRifleIds,
                model.leftWaistIds,model.rightWaistIds,model.leftShoulderIds,model.rightShoulderIds,model.bladeIds,
                model.sheathIds,model.headIds,model.backpackIds)) for(int id:list) ids.add(id);
        for(var lists:List.of(model.extraLeftHandGroups,model.extraRightHandGroups,model.passengerGroups))
            for(var list:lists) for(int id:list) ids.add(id);
        for(int i=0;i<affected.length;i++) if(affected[i]) {
            var bone=model.topLevelBones().get(i);
            // The Java billboard draw needs CPU-readable transforms for this subtree.
            if(model.bakedBones.get(i).facingCameraSubtree || ids.contains(bone.getBoneId()) || bone.getName().equals("ViewLocator") || bone.getName().equals("AllHead")
                    || parameters[i*12+11]!=0) return true;
        }
        return false;
    }

    /** Scene snapshots allow the complete island, including terrain contacts, to stay on GPU. */
    static boolean supportsDefinition(PhysDefinition definition) {
        return definition!=null && !definition.joints.isEmpty();
    }

    /** Contact geometry under anisotropic physical transforms remains unsupported. */
    static boolean supportedScale(PhysDefinition definition,float[] p,Matrix4f root) {
        // Decorative branches never enter inverse(parent) or contact fitting.
        // Their finite zero/nonuniform scales are valid for matrix generation.
        for(int i=0;i<definition.bones.size();i++) for(int component=6;component<9;component++)
            if(!Float.isFinite(p[i*12+component]) || definition.physicsScale[i] && Math.abs(p[i*12+component])<1e-6f) return false;
        if(definition.cubes.isEmpty() && definition.joints.stream().noneMatch(j->j.parameters().bc())) return true;
        var scale=root.getScale(new Vector3f());
        if(root.determinant()<=0 || !uniform(scale.x,scale.y,scale.z)) return false;
        for(int i=0;i<definition.bones.size();i++) if(definition.physicsScale[i] && !uniform(p[i*12+6],p[i*12+7],p[i*12+8])) return false;
        return true;
    }
    private static boolean uniform(float x,float y,float z) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && x>1e-6f && y>1e-6f && z>1e-6f
                && Math.abs(x-y)<1e-5f && Math.abs(x-z)<1e-5f;
    }

    static final class Instance {
        final WeakReference<AnimatedGeoModel> owner;
        final GpuPhysKernel kernel;
        final Matrix4f root=new Matrix4f();
        final Clock clock=new Clock();
        final GpuCollisionSnapshot collisions;
        final Matrix4f[] animated;
        final int[] timers=new int[4];
        final long[] submissions=new long[4];
        final long[] preparations=new long[4];
        final int[] generations=new int[4];
        int activeTimer=-1,sampleFrame;
        long submissionStart;
        double time=Double.NaN,x,y,z,drawn=Double.NaN;
        Object world;
        int fps;
        boolean paused;
        java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> blockCollisions;
        long touched;
        Instance(AnimatedGeoModel owner,GpuPhysModel model) {
            this.owner=new WeakReference<>(owner);kernel=new GpuPhysKernel(model,1);
            collisions=new GpuCollisionSnapshot(model.definition);
            animated=new Matrix4f[model.original.length];Arrays.setAll(animated,i->new Matrix4f());
        }
        void pollTimers() {
            var cost=timings.get(kernel.model.definition);if(cost==null) return;
            for(int i=0;i<timers.length;i++) if(timers[i]!=0
                    && GL15.glGetQueryObjecti(timers[i],GL15.GL_QUERY_RESULT_AVAILABLE)!=0) {
                if(generations[i]==cost.generation)
                    cost.gpu(preparations[i],GL33.glGetQueryObjectui64(timers[i],GL15.GL_QUERY_RESULT),submissions[i]);
                GL15.glDeleteQueries(timers[i]);timers[i]=0;
            }
        }
        void beginTimer(long started) {
            pollTimers();
            var cost=timings.get(kernel.model.definition);
            if(cost==null || cost.gpuCount==8 && ++sampleFrame%30!=0
                    || GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)!=0) return;
            for(int i=0;i<timers.length;i++) if(timers[i]==0) {
                timers[i]=GL15.glGenQueries();activeTimer=i;submissionStart=System.nanoTime();
                preparations[i]=submissionStart-started;generations[i]=cost.generation;
                GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,timers[i]);return;
            }
        }
        void endTimer() {
            if(activeTimer<0) return;
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            submissions[activeTimer]=System.nanoTime()-submissionStart;activeTimer=-1;
        }
        void closeTimers() {
            endTimer();for(int i=0;i<timers.length;i++) if(timers[i]!=0) {GL15.glDeleteQueries(timers[i]);timers[i]=0;}
        }
        void draw(float[] parameters,Matrix4f renderRoot,int light) {
            if(time==drawn && clock.world==world && clock.fps==fps) {kernel.matrices(parameters,renderRoot,light);return;}
            long started=System.nanoTime();
            var definition=kernel.model.definition;
            boolean rootJump=false;
            for(int i:definition.order) {
                int parent=definition.bones.get(i).parentIdx;
                RenderUtils.prepMatrixForBone(definition.bones.get(i),animated[i].set(parent<0?root:animated[parent]),parameters,i*12);
            }
            // Root target jumps are CPU-visible without reading simulated state back.
            for(var joint:definition.joints) if(joint.parent()<0) {
                var point=animated[joint.bone()].transformPosition(definition.pivot(joint.bone()));
                var previous=clock.roots.put(joint.bone(),point);
                if(previous!=null && previous.distanceSquared(point)>16) rootJump=true;
            }
            clock.advance(time,paused,world,x,y,z,fps,rootJump);
            boolean sample=!paused && !clock.reset;
            try {
                // The displayed pose must use today's scene even between fixed steps.
                var contacts=collisions.collect(parameters,animated,x,y,z,blockCollisions);
                // Scene extraction precedes this instance's device work. Charge it
                // separately instead of allowing device time to hide that CPU cost.
                if(sample) beginTimer(started);
                kernel.solve(parameters,root,renderRoot,contacts,clock.steps,clock.reset,1f/fps,clock.velocity,light,clock.alpha());
                drawn=time;
            } finally {if(sample) endTimer();}
        }
    }

    /** CPU schedules time; the GPU owns all persistent physical positions/velocities. */
    static final class Clock {
        double last=Double.NaN,accumulator,originX,originY,originZ;
        Object world;
        int fps,steps;
        boolean reset;
        final Vector3f velocity=new Vector3f();
        final Map<Integer,Vector3f> roots=new HashMap<>();
        float alpha() {return (float)Math.max(0,Math.min(1,accumulator*fps));}
        void advance(double time,boolean paused,Object world,double x,double y,double z,int fps,boolean rootJump) {
            double elapsed=Double.isFinite(last)?Math.max(0,time-last):0;
            Vector3f shift=new Vector3f((float)(originX-x),(float)(originY-y),(float)(originZ-z));
            reset=!Double.isFinite(last) || !Double.isFinite(time) || time<last || elapsed>.25
                    || this.world!=world || this.fps!=fps || shift.lengthSquared()>16 || rootJump;
            if(reset) accumulator=0;
            velocity.zero();if(!reset && !paused && elapsed>1e-9) velocity.set(shift).mul((float)(-1/elapsed));
            steps=0;
            if(!reset && !paused && elapsed>0) {
                double dt=1.0/fps;accumulator=Math.min(accumulator+elapsed,dt*8);
                while(steps<8 && accumulator+1e-9>=dt) {steps++;accumulator-=dt;}
            }
            this.last=time;this.world=world;this.fps=fps;originX=x;originY=y;originZ=z;
        }
    }
}
