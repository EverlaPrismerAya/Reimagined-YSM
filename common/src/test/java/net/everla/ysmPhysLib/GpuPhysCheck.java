package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import net.everla.reimaginedysm.gpu.ShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;
import static net.everla.ysmPhysLib.PhysCheck.*;

/** Opt-in real driver check. Per-frame readbacks are test-only; production reads only at CPU handoff. */
public final class GpuPhysCheck {
    private static final Object WORLD=new Object();
    private static final Vector3f ZERO=new Vector3f();
    public static void main(String[] args) throws Exception {
        clock();
        var callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        if(!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        long window=0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
            window=GLFW.glfwCreateWindow(64,64,"ysmPhys offscreen verification",0,0);
            if(window==0) throw new IllegalStateException("OpenGL 4.3 context unavailable");
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("GPU physics verification: "+GL11.glGetString(GL11.GL_VENDOR)+" / "+GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
            String reason=GpuPhysKernel.capabilityReason();if(reason!=null) throw new AssertionError(reason);
            GpuPhysKernel.compile();
            if("bufferedOwnership".equals(System.getProperty("ysm.physicsCase"))) {bufferedOwnership();return;}
            if("settling".equals(System.getProperty("ysm.physicsCase"))) {wellSettling();return;}
            if("poseHandoff".equals(System.getProperty("ysm.physicsCase"))) {
                var demo=loadModel(System.getProperty("ysm.physicsExample"));
                samePoseHandoff(denseModel(demo,64,false),false);
                samePoseHandoff(denseModel(demo,64,true),true);
                samePoseHandoff(loadModel(Objects.requireNonNull(System.getProperty("ysm.physicsModel"))),false);return;
            }
            if("actualContacts".equals(System.getProperty("ysm.physicsCase"))) {
                var actual=loadModel(Objects.requireNonNull(System.getProperty("ysm.physicsModel"),"physicsModel required"));
                physicalContacts(actual,2);physicalContacts(actual,4);return;
            }
            if("handoff".equals(System.getProperty("ysm.physicsCase"))) {cpuHandoff();contactHandoff();return;}
            if("switchCost".equals(System.getProperty("ysm.physicsCase"))) {
                var demo=loadModel(System.getProperty("ysm.physicsExample"));
                switchCost(demo,"demo");switchCost(denseModel(demo,64,false),"64-chain body");
                if(System.getProperty("ysm.physicsModel")!=null) switchCost(loadModel(System.getProperty("ysm.physicsModel")),"actual");
                return;
            }
            if("slide".equals(System.getProperty("ysm.physicsCase"))) {contactSliding();thinObstacleSweep();return;}
            if("sweep".equals(System.getProperty("ysm.physicsCase"))) {thinObstacleSweep();return;}
            if("terrain".equals(System.getProperty("ysm.physicsCase"))) {hybrid();visibilityAndState();return;}
            if("adaptive".equals(System.getProperty("ysm.physicsCase"))) {adaptiveTiming();return;}
            if("snapshot".equals(System.getProperty("ysm.physicsCase"))) {contactSnapshot();return;}
            if(Boolean.getBoolean("ysm.physicsBenchmark")) {
                com.elfmcys.yesstevemodel.NativeLibLoader.init();
                var demo=loadModel(System.getProperty("ysm.physicsExample"));
                for(int count:new int[]{1,16}) productionBenchmark(demo,count,false,"demo");
                for(boolean terrain:new boolean[]{false,true})
                    productionBenchmark(denseModel(demo,64,terrain),1,terrain,terrain?"terrain":"body");
                if(System.getProperty("ysm.physicsModel")!=null)
                    productionBenchmark(loadModel(System.getProperty("ysm.physicsModel")),1,false,"actual");
                return;
            }
            for(int fps:new int[]{1,15,30,60,120}) for(int scenario=0;scenario<11;scenario++) {
                System.out.println("GPU parity: fps="+fps+", scenario="+scenario);
                var fixture=fixture(scenario);
                if(GpuPhysics.supportsDefinition(fixture.ysmPhysics)) parity(fixture,fps,scenario);
                else assert !fixture.ysmPhysics.cubes.isEmpty() : "unexpected CPU fallback";
            }
            GeoModel demo=loadModel(System.getProperty("ysm.physicsExample"));
            sharedNativeMesh(demo);
            if(System.getProperty("ysm.physicsModel")!=null) {
                var actual=loadModel(System.getProperty("ysm.physicsModel"));
                sharedNativeMesh(actual);physicalContacts(actual,2);physicalContacts(actual,4);samePoseHandoff(actual,false);
            }
            for(int fps:new int[]{1,15,30,60,120}) for(int scenario=2;scenario<=4;scenario++) parity(demo,fps,scenario);
            irisMatrices();
            bufferedOwnership();
            wellSettling();
            queuedPhysicsMatrices();
            adaptiveTiming();
            contactSnapshot();
            thinObstacleSweep();
            contactSliding();
            cpuHandoff();
            contactHandoff();
            samePoseHandoff(denseModel(demo,64,false),false);
            samePoseHandoff(denseModel(demo,64,true),true);
            for(int scenario=2;scenario<5;scenario++) physicalContacts(fixture(scenario),scenario);
            physicalContacts(demo,4);
            hybrid();
            visibilityAndState();
            System.out.println("PASS: GPU physical contacts, free-motion regression, cadence, lifecycle, visibility, repeated draws, terrain and GL state restoration");
        } catch(Exception | Error ex) {
            // A driver can block during GLFW teardown; report the actual failure before cleanup.
            ex.printStackTrace();throw ex;
        } finally {
            if(window!=0) {GpuPhysKernel.shutdown();GLFW.glfwDestroyWindow(window);}
            GLFW.glfwTerminate();GLFW.glfwSetErrorCallback(null);callback.free();
        }
    }
    private static void wellSettling() {
        var geometry=model(bone("ysmPhys#gw=4.5,s=0,r=4,air=0,a=180@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
        var definition=definition(geometry);float[] parameters=parameters(geometry);
        var root=new Matrix4f().rotateZ(.6f);var target=root.transformPosition(new Vector3f(1,0,0));
        try(var topology=new GpuPhysModel(definition,new boolean[2]);var kernel=new GpuPhysKernel(topology,1)) {
            kernel.solve(parameters,new Matrix4f(),new Matrix4f(),List.of(),0,true,1/120f,ZERO,0);
            for(int frame=0;frame<180;frame++) kernel.solve(parameters,root,new Matrix4f(),List.of(),8,false,1/120f,ZERO,0);
            Vector3f previous=null;float jitter=0,error=0;
            for(int frame=0;frame<60;frame++) {
                kernel.solve(parameters,root,new Matrix4f(),List.of(),1,false,1/120f,ZERO,0);
                var data=read(kernel.state,kernel.nodeCount*GpuPhysKernel.NODE_BYTES);
                try {
                    int at=topology.sorted[1]*GpuPhysKernel.NODE_BYTES;
                    var point=new Vector3f(data.getFloat(at),data.getFloat(at+4),data.getFloat(at+8));
                    if(previous!=null) jitter=Math.max(jitter,point.distance(previous));
                    error=Math.max(error,point.distance(target));previous=point;
                } finally {MemoryUtil.memFree(data);}
            }
            assert jitter<1e-5f && error<1e-4f:"GPU well never settled: jitter="+jitter+", error="+error;
        }
        System.out.println("PASS: GPU gravity well settles without a sustained near-target limit cycle");
    }
    @SuppressWarnings("unchecked")
    private static void bufferedOwnership() throws Exception {
        var geometry=fixture(0);
        var owner=new com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel(geometry);
        owner.ysmPhysics=new PhysSimulation(geometry.ysmPhysics);
        float[] parameters=owner.getMatrixData();System.arraycopy(parameters(geometry),0,parameters,0,parameters.length);
        var instancesField=GpuPhysics.class.getDeclaredField("instances");instancesField.setAccessible(true);
        var resourcesField=GpuPhysics.class.getDeclaredField("resources");resourcesField.setAccessible(true);
        var instances=(Map<float[],GpuPhysics.Instance>)instancesField.get(null);
        var resources=(Set<GpuPhysics.Instance>)resourcesField.get(null);
        Matrix4f[] matrices=new Matrix4f[geometry.bakedBones.size()];boolean[] visible=new boolean[matrices.length];
        Vector3f initial=null;float moved=0;
        for(int frame=0;frame<=180;frame++) {
            owner.ysmPhysics.restore(parameters);
            double time=frame/120.0;
            var instance=instances.get(parameters);
            if(instance==null) {
                var topology=new GpuPhysModel(geometry.ysmPhysics,new boolean[matrices.length]);
                instance=new GpuPhysics.Instance(owner,topology);topology.users++;
                instance.kernel.seed(owner.ysmPhysics,instance.clock);
                owner.ysmPhysics.partition(parameters,true);
                instances.put(parameters,instance);resources.add(instance);
            }
            instance.root.identity().rotateX(.6f);instance.time=time;instance.world=WORLD;instance.fps=120;
            instance.blockCollisions=bounds->List.of();
            if(frame<90) {
                assert GpuPhysics.readRenderMatrices(parameters,new Matrix4f(),0,matrices,visible):"material draw lost GPU ownership";
                assert GpuPhysics.active(parameters):"GPU disabled by material draw";
                Vector3f point=matrices[2].transformPosition(geometry.ysmPhysics.pivot(2));
                if(initial==null) initial=new Vector3f(point);
                moved=Math.max(moved,point.distance(initial));
            } else {
                // Reproduce a new owner being prepared but consumed only by a buffered draw.
                GpuPhysics.fallback(parameters);
                assert !GpuPhysics.active(parameters);
            }
        }
        assert moved>.01f:"plain physical groups frozen with GPU enabled: "+moved;
        assert owner.ysmPhysics.position(2).isFinite();
        System.out.println("PASS: plain GPU groups move through material-matrix draws and repeated prepare/buffered handoff");
    }
    /** A world entity and HUD player reuse one mesh, but supply different poses and roots. */
    private static void sharedNativeMesh(GeoModel geometry) throws Exception {
        if(geometry.ysmPhysics==null) geometry.ysmPhysics=definition(geometry);
        com.elfmcys.yesstevemodel.NativeLibLoader.init();
        assert com.elfmcys.yesstevemodel.NativeLibLoader.isLoaded() : "native renderer unavailable";
        var mesh=net.everla.reimaginedysm.gpu.GpuRenderPath.getOrBuildMesh(geometry);
        assert mesh!=null;
        var first=parameters(geometry);var second=parameters(geometry);
        var firstPose=mesh.pose(first);var secondPose=mesh.pose(second);
        assert firstPose==mesh.pose(first) && firstPose!=secondPose : "pose ownership is not instance-specific";
        assert firstPose.boneSsbo!=secondPose.boneSsbo && firstPose.boneBuffer!=secondPose.boneBuffer : "instances share mutable bone data";
        firstPose.ensureXformBuffers(mesh);secondPose.ensureXformBuffers(mesh);
        assert firstPose.xformVbo()!=secondPose.xformVbo() && firstPose.xformVao()!=secondPose.xformVao() : "Iris instances share transformed vertices";
        var worldRoot=new Matrix4f().translate(2,3,-4).rotateXYZ(.2f,.7f,-.1f);
        var hudRoot=new Matrix4f().translate(20,40,1000).scale(35,-35,35);
        float[] poseScratch=new float[16],normalScratch=new float[9];
        var visibility=net.everla.reimaginedysm.gpu.GpuRenderPath.class.getDeclaredMethod("updateBoneVisibility",List.class,float[].class,ByteBuffer.class);
        visibility.setAccessible(true);
        int shader=ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/bone_skin.vsh");
        int program=ShaderUtil.linkProgramWith(p->GL30.glTransformFeedbackVaryings(p,new CharSequence[]{"gl_Position"},GL30.GL_INTERLEAVED_ATTRIBS),shader);
        int feedback=GL15.glGenBuffers();
        var vertices=read(mesh.vbo,mesh.vertexCount*32);
        int draws=30*4,bytesPerDraw=mesh.vertexCount*16;
        var captured=MemoryUtil.memAlloc(bytesPerDraw*draws);
        var expectedDraws=new ArrayList<Matrix4f[]>();
        var drawnParameters=new ArrayList<float[]>();
        try {
            GL20.glUseProgram(program);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,"u_proj"),false,new Matrix4f().get(new float[16]));
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
            GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,(long)bytesPerDraw*draws,GL15.GL_STREAM_READ);
            GL30.glBindVertexArray(mesh.vao);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            for(int frame=0;frame<30;frame++) {
                for(int bone=0;bone<geometry.bakedBones.size();bone++) {
                    int at=bone*12;
                    for(int axis=0;axis<3;axis++) {
                        first[at+axis]=.15f*(float)Math.sin(frame*.13+bone*.7+axis);
                        second[at+axis]=-.2f*(float)Math.cos(frame*.19+bone*.3+axis);
                        first[at+3+axis]=.4f*(float)Math.sin(frame*.1+bone+axis);
                        second[at+3+axis]=-.3f*(float)Math.cos(frame*.2+bone+axis);
                    }
                }
                second[3]=12;
                // Alternate the FirstPerson AllHead override when the fixture has one.
                int hiddenBone=0;
                for(int bone=0;bone<geometry.topLevelBones().size();bone++)
                    if(geometry.topLevelBones().get(bone).getName().equals("AllHead")) {hiddenBone=bone;break;}
                first[hiddenBone*12+9]=first[hiddenBone*12+10]=frame%2;
                // Queue alternating instances and repeated passes without readbacks/waits.
                for(int draw=0;draw<4;draw++) {
                    var p=draw==1?second:first;
                    var instance=mesh.pose(p);
                    instance.boneBuffer.clear();
                    var root=draw==1?hudRoot:worldRoot;
                    var before=p.clone();
                    GeoModel.nComputeBoneMatrices(mesh.pointer,root.get(poseScratch),root.normal(new org.joml.Matrix3f()).get(normalScratch),
                            p,1234,instance.boneBuffer);
                    visibility.invoke(null,geometry.bakedBones,p,instance.boneBuffer);
                    var expected=matrices(geometry.ysmPhysics,p,root);
                    for(int bone=0;bone<expected.length;bone++) {
                        var actual=new Matrix4f(instance.boneBuffer.asFloatBuffer().position(bone*36));
                        assert actual.equals(expected[bone],.002f) : "shared native mesh retained another instance's transform";
                    }
                    assert Arrays.equals(before,p) : "native draw changed instance animation";
                    instance.uploadBones();
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,instance.boneSsbo);
                    GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback,(long)(frame*4+draw)*bytesPerDraw,bytesPerDraw);
                    GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                    GL11.glDrawArrays(GL11.GL_POINTS,0,mesh.vertexCount);
                    GL30.glEndTransformFeedback();
                    expectedDraws.add(expected);drawnParameters.add(before);
                }
            }
            // One read only after every draw has been submitted. Per-draw readbacks
            // serialize the GPU and conceal in-flight buffer reuse regressions.
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
            GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,captured);
            for(int draw=0;draw<draws;draw++) {
                var expected=expectedDraws.get(draw);var p=drawnParameters.get(draw);
                for(int vertex=0;vertex<mesh.vertexCount;vertex++) {
                        int at=vertex*32,bone=Short.toUnsignedInt(vertices.getShort(at+24));
                        assert bone<expected.length : "invalid packed bone index";
                        boolean hidden=p[bone*12+9]!=0;
                        for(int ancestor=bone;ancestor>=0;ancestor=geometry.bakedBones.get(ancestor).parentIdx)
                            hidden |= p[ancestor*12+6]==0 || p[ancestor*12+7]==0 || p[ancestor*12+8]==0
                                    || (ancestor!=bone && p[ancestor*12+10]!=0);
                        var wanted=hidden?new org.joml.Vector4f(2,2,2,1):expected[bone].transform(new org.joml.Vector4f(
                                vertices.getFloat(at),vertices.getFloat(at+4),vertices.getFloat(at+8),1));
                        var actual=new org.joml.Vector4f(captured.asFloatBuffer().position(draw*mesh.vertexCount*4+vertex*4));
                        assert actual.equals(wanted,.003f) : "shared mesh vertex visibility/pose mismatch: draw="+draw+" vertex="+vertex+" bone="+bone+" actual="+actual+" expected="+wanted;
                }
            }
            var cleanup=mesh.getClass().getDeclaredMethod("cleanupPoses",boolean.class);cleanup.setAccessible(true);
            assert GL15.glIsBuffer(firstPose.boneSsbo) && GL15.glIsBuffer(secondPose.boneSsbo);
            cleanup.invoke(mesh,true);
            assert !GL15.glIsBuffer(firstPose.boneSsbo) && !GL15.glIsBuffer(secondPose.boneSsbo) : "pose SSBO leaked on unload";
            assert !GL15.glIsBuffer(firstPose.xformVbo()) && !GL15.glIsBuffer(secondPose.xformVbo()) : "Iris pose VBO leaked on unload";
        } finally {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);GL30.glBindVertexArray(0);GL20.glUseProgram(0);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,0);
            GL15.glDeleteBuffers(feedback);GL20.glDeleteProgram(program);
            MemoryUtil.memFree(vertices);MemoryUtil.memFree(captured);
            net.everla.reimaginedysm.gpu.GpuRenderPath.disposeMesh(geometry);
        }
        System.out.println("PASS: 120 queued production skin draws, "+geometry.bakedBones.size()+" bones / "+mesh.vertexCount+" vertices, isolated SSBO/Iris outputs, poses, visibility and unload cleanup");
    }
    private static void hybrid() {
        var geometry=model(bone("ysmPhys#bc=true,s=0,r=3,air=0,a=180@cpu",-1,0,0,0),
                bone("ysmPhysEnd@cpu",0,16,0,0),bone("decoration",0,8,4,0),
                bone("ysmPhys#s=0,r=3,air=0,a=180@gpu",-1,32,0,0),bone("ysmPhysEnd@gpu",3,48,0,0),
                bone("decoration",3,40,4,0));
        var d=definition(geometry);
        assert GpuPhysics.supportsDefinition(d);
        for(int fps:new int[]{30,60,120}) {
            var full=new PhysSimulation(d);var partial=new PhysSimulation(d);full.setFrameRate(fps);partial.setFrameRate(fps);
            float[] expectedPose=parameters(geometry),pose=parameters(geometry);partial.partition(pose,true);
            var clock=new GpuPhysics.Clock();
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                assert topology.trees==2;
                for(int frame=0;frame<150;frame++) {
                    double time=frame/60.0,x=time*3;
                    double y=frame<60?Math.max(0,4*time-5*time*time):0;
                    var root=new Matrix4f().translate(0,.25f,0);
                    var floor=net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(-100,-1,-100,100,0,100));
                    boolean paused=frame>=90 && frame<95;
                    full.update(expectedPose,root,time,paused,WORLD,x,y,0,bounds->List.of(floor));
                    partial.update(pose,root,time,paused,WORLD,x,y,0,bounds->List.of(floor));
                    clock.advance(time,paused,WORLD,x,y,0,fps,false);
                    var snapshot=new GpuCollisionSnapshot(d);
                    var contacts=snapshot.collect(pose,matrices(d,pose,root),x,y,0,bounds->List.of(floor));
                    kernel.solve(pose,root,root,contacts,clock.steps,clock.reset,1f/fps,clock.velocity,1234,clock.alpha());
                    var expected=matrices(d,expectedPose,root);
                    var data=read(kernel.output,d.bones.size()*144);
                    try {
                        for(int i=0;i<d.bones.size();i++) {
                            var actual=new Matrix4f(data.asFloatBuffer().position(i*36));
                            assert actual.isFinite();
                            if(i>=3) for(var point:List.of(d.pivot(i),d.pivot(i).add(.2f,.3f,.1f)))
                                assert actual.transformPosition(new Vector3f(point)).distance(expected[i].transformPosition(new Vector3f(point)))<.001f
                                        : "unrelated GPU tree changed frame="+frame+" bone="+i;
                            if(i==1 && frame>2) assert actual.transformPosition(d.pivot(i)).y+y>=1f/16-.002f
                                    : "visible GPU terrain penetration: fps="+fps+" frame="+frame+" originY="+y+" tipY="+actual.transformPosition(d.pivot(i)).y;
                        }
                    } finally {MemoryUtil.memFree(data);}
                    kernel.matrices(pose,root,1234);
                }
            }
            partial.partition(pose,false);
            full.update(expectedPose,new Matrix4f(),3,false,WORLD,0,0,0);
            partial.update(pose,new Matrix4f(),3,false,WORLD,0,0,0);
            for(int i=0;i<pose.length;i++) assert Math.abs(pose[i]-expectedPose[i])<1e-5 : "hybrid fallback reset mismatch";
        }
        System.out.println("PASS: GPU terrain + independent tree, ordinary descendants, sprint/jump, pause, repeated matrices and CPU fallback");
    }

    private static void clock() {
        var builder=new net.minecraftforge.common.ForgeConfigSpec.Builder();PhysConfig.define(builder,false);builder.build();
        var localBuilder=new net.minecraftforge.common.ForgeConfigSpec.Builder();PhysConfig.define(localBuilder,true);localBuilder.build();
        assert PhysConfig.EXPERIMENTAL_GPU.getDefault() && PhysConfig.Local.EXPERIMENTAL_GPU.getDefault();
        assert PhysConfig.EXPERIMENTAL_GPU!=PhysConfig.Local.EXPERIMENTAL_GPU;
        var clock=new GpuPhysics.Clock();
        clock.advance(0,false,WORLD,0,0,0,60,false);assert clock.reset && clock.steps==0;
        clock.advance(1.0/120,false,WORLD,0,0,0,60,false);assert !clock.reset && clock.steps==0;
        clock.advance(1.0/60,false,WORLD,0,0,1,60,false);assert clock.steps==1 && Math.abs(clock.velocity.z-120)<1e-4;
        clock.advance(1.0/60,false,WORLD,0,0,1,60,false);assert clock.steps==0;
        clock.advance(.1,true,WORLD,0,0,1,60,false);assert clock.steps==0 && clock.velocity.lengthSquared()==0;
        clock.advance(.11,false,WORLD,0,0,1,120,false);assert clock.reset;
        clock.advance(.12,false,new Object(),0,0,1,120,false);assert clock.reset;
        clock.advance(.13,false,WORLD,100,0,1,120,false);assert clock.reset;
        clock.advance(1,false,WORLD,100,0,1,120,false);assert clock.reset;
        clock.advance(.5,false,WORLD,100,0,1,120,false);assert clock.reset;
    }
    private static void switchCost(GeoModel geometry,String name) {
        var d=geometry.ysmPhysics;var p=parameters(geometry);var root=new Matrix4f();
        var contacts=contacts(d,p,root);var clock=new GpuPhysics.Clock();
        var cpu=new PhysSimulation(d);cpu.setFrameRate(60);
        double[] upload=new double[40],download=new double[40];
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
            cpu.update(p,root,0,false,WORLD,0,0,0);
            for(int sample=-10;sample<40;sample++) {
                long start=System.nanoTime();
                try(var saved=new GpuPhysKernel.SavedState()) {assert kernel.seed(cpu,clock);}
                double sent=(System.nanoTime()-start)/1e6;
                cpu.restore(p);
                // Read back immediately after actual queued collision work, with no
                // artificial glFinish that would hide the transfer's implicit wait.
                clock.advance(clock.last+1.0/60,false,WORLD,0,0,0,60,false);
                kernel.solve(p,root,root,contacts,clock.steps,false,1f/60,ZERO,1,clock.alpha());
                start=System.nanoTime();
                try(var saved=new GpuPhysKernel.SavedState()) {kernel.handoff(cpu,clock);}
                double received=(System.nanoTime()-start)/1e6;
                if(sample>=0) {upload[sample]=sent;download[sample]=received;}
                cpu.update(p,root,clock.last,false,WORLD,0,0,0);
            }
        }
        Arrays.sort(upload);Arrays.sort(download);
        System.out.printf(Locale.ROOT,"SWITCH %s: %d bones; CPU->GPU median/p95 %.3f/%.3f ms, queued solve + GPU->CPU wait/read median/p95 %.3f/%.3f ms%n",
                name,d.bones.size(),upload[20],upload[38],download[20],download[38]);
    }
    private static void contactHandoff() {
        var geometry=model(bone("ysmPhys#bc=true,s=0,r=0,air=0,a=180,c=1@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
        var d=definition(geometry);var p=parameters(geometry);var floor=new net.minecraft.world.phys.AABB(-3,-2,-3,3,0,3);
        var shape=net.minecraft.world.phys.shapes.Shapes.create(floor);
        for(int fps:new int[]{15,60,120}) {
            var clock=new GpuPhysics.Clock();
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                for(int frame=0;frame<90;frame++) {
                    double time=frame/157.0;
                    var root=new Matrix4f().translate(0,.5f-frame*.004f,0);
                    clock.advance(time,false,WORLD,0,0,0,fps,false);
                    kernel.solve(p,root,root,List.of(new PhysCollider(floor)),clock.steps,clock.reset,1f/fps,ZERO,1,clock.alpha());
                    if(frame<20) continue;
                    var resumed=new PhysSimulation(d);var resumedP=parameters(geometry);
                    kernel.handoff(resumed,clock);
                    int[] queries={0};
                    java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> source=bounds->{queries[0]++;return List.of(shape);};
                    resumed.update(resumedP,root,time,false,WORLD,0,0,0,source);
                    var tip=matrices(d,resumedP,root)[1].transformPosition(d.pivot(1));
                    assert tip.y>=.0625f-.002f : "GPU -> CPU contact handoff penetrated floor: fps="+fps+" frame="+frame+" tip="+tip;
                    int queryCount=queries[0];var drawn=resumedP.clone();
                    resumed.restore(resumedP);
                    resumed.update(resumedP,root,time,false,WORLD,0,0,0,source);
                    assert queries[0]==queryCount && Arrays.equals(drawn,resumedP) : "repeated CPU fallback draw rebuilt collision snapshot";
                    resumed.restore(resumedP);resumedP[3]=.1f;
                    resumed.update(resumedP,root,time,false,WORLD,0,0,0,source);
                    assert queries[0]>queryCount : "changed animation reused stale CPU fallback display";
                    queryCount=queries[0];
                    resumed.update(resumedP,new Matrix4f(root).translate(0,.01f,0),time,false,WORLD,0,0,0,source);
                    assert queries[0]>queryCount : "changed root reused stale CPU fallback display";
                    resumed.update(resumedP,new Matrix4f(root).translate(0,-.004f,0),time+1.0/157,false,WORLD,0,0,0,bounds->List.of(shape));
                    tip=matrices(d,resumedP,new Matrix4f(root).translate(0,-.004f,0))[1].transformPosition(d.pivot(1));
                    assert tip.y>=.0625f-.002f : "CPU continuation lost displayed ground contact: "+tip;
                }
            }
        }
        System.out.println("PASS: GPU -> CPU handoff retains nonpenetrating displayed ground contact during descent");
    }
    private static void cpuHandoff() {
        for(int scenario:new int[]{1,5,10}) for(int fps:new int[]{15,60,120}) {
            var geometry=fixture(scenario);var d=geometry.ysmPhysics;
            var cpu=new PhysSimulation(d);cpu.setFrameRate(fps);
            var p=parameters(geometry);var root=new Matrix4f().translate(2,3,-4).rotateXYZ(.2f,.3f,-.1f);
            double time=0;
            for(int frame=0;frame<47;frame++) {time=frame/157.0;cpu.update(p,root,time,false,WORLD,123,64,-456+(scenario==10?time*2:0));}
            var expected=matrices(d,p,root);cpu.restore(p);
            var clock=new GpuPhysics.Clock();
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                assert kernel.seed(cpu,clock) : "initialized CPU state was not transferred";
                clock.advance(time,false,WORLD,123,64,-456+(scenario==10?time*2:0),fps,false);
                assert !clock.reset && clock.steps==0 : "handoff lost clock context";
                kernel.solve(p,root,root,List.of(),0,false,1f/fps,ZERO,1,clock.alpha());
                var state=read(kernel.state,kernel.nodeCount*256);
                var output=read(kernel.output,d.bones.size()*144);
                try {
                    for(int i=0;i<d.bones.size();i++) {
                        var actual=new Matrix4f(output.asFloatBuffer().position(i*36));
                        assert actual.equals(expected[i],.0001f) : "CPU -> GPU displayed pose jumped, fps="+fps+" bone="+i;
                    }
                    clock.advance(time+1.0/fps,false,WORLD,123,64,-456+(scenario==10?(time+1.0/fps)*2:0),fps,false);
                    assert !clock.reset && clock.steps==1;
                    kernel.solve(p,root,root,List.of(),clock.steps,false,1f/fps,clock.velocity,1,clock.alpha());
                    var after=read(kernel.state,kernel.nodeCount*256);
                    cpu.update(p,root,time+1.0/fps,false,WORLD,123,64,-456+(scenario==10?(time+1.0/fps)*2:0));
                    try {
                        assert !after.equals(state) : "transferred simulation stopped advancing";
                        for(var joint:d.joints) for(int end:joint.outputs()) {
                            int at=(topology.island[joint.bone()]*d.bones.size()+topology.sorted[end])*256;
                            var position=new Vector3f(after.getFloat(at),after.getFloat(at+4),after.getFloat(at+8));
                            assert position.distance(cpu.position(end))<.0003f : "handoff lost inertia: "+position+" / "+cpu.position(end);
                        }
                    } finally {MemoryUtil.memFree(after);}
                    var resumed=new PhysSimulation(d);var resumedP=parameters(geometry);
                    kernel.handoff(resumed,clock);
                    resumed.update(resumedP,root,clock.last,false,WORLD,123,64,-456+(scenario==10?clock.last*2:0));
                    var resumedMatrices=matrices(d,resumedP,root);
                    var rendered=read(kernel.output,d.bones.size()*144);
                    try {
                        for(int i=0;i<d.bones.size();i++) assert resumedMatrices[i].equals(
                                new Matrix4f(rendered.asFloatBuffer().position(i*36)),.0001f)
                                : "GPU -> CPU displayed pose jumped, fps="+fps+" bone="+i;
                    } finally {MemoryUtil.memFree(rendered);}
                    resumed.update(resumedP,root,clock.last+1.0/fps,false,WORLD,123,64,-456+(scenario==10?(clock.last+1.0/fps)*2:0));
                    cpu.update(p,root,clock.last+1.0/fps,false,WORLD,123,64,-456+(scenario==10?(clock.last+1.0/fps)*2:0));
                    for(var joint:d.joints) for(int end:joint.outputs())
                        assert resumed.position(end).distance(cpu.position(end))<.0005f : "GPU -> CPU lost inertia";
                } finally {MemoryUtil.memFree(state);MemoryUtil.memFree(output);}
            }
        }
        System.out.println("PASS: CPU <-> GPU preserves displayed pose, clock phase and inertia across branches/islands");
    }

    /** Same state and time: this tests a render handoff, not CPU/GPU trajectory equality. */
    private static void samePoseHandoff(GeoModel geometry,boolean terrain) {
        var d=geometry.ysmPhysics;
        for(int fps:new int[]{15,60,120}) {
            var cpu=new PhysSimulation(d);cpu.setFrameRate(fps);var p=parameters(geometry);
            var root=new Matrix4f().rotateX(.2f).scale(.7f);
            var floor=net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(-32,-1,-32,32,0,32));
            java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> blocks=terrain?bounds->List.of(floor):null;
            double time=0;
            for(int frame=0;frame<80;frame++) {time=frame/157.0;cpu.update(p,root,time,false,WORLD,0,0,0,blocks);}
            var expected=matrices(d,p,root);cpu.restore(p);
            var snapshot=new GpuCollisionSnapshot(d);var contacts=snapshot.collect(p,matrices(d,p,root),0,0,0,blocks);
            var clock=new GpuPhysics.Clock();
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                assert kernel.seed(cpu,clock);
                clock.advance(time,false,WORLD,0,0,0,fps,false);
                assert !clock.reset && clock.steps==0;
                kernel.solve(p,root,root,contacts,0,false,1f/fps,ZERO,1234,clock.alpha());
                var output=read(kernel.output,d.bones.size()*GpuPhysKernel.RESULT_BYTES);
                float maximum=0;
                try {
                    for(int bone=0;bone<d.bones.size();bone++) {
                        var actual=new Matrix4f(output.asFloatBuffer().position(bone*36));
                        for(var point:List.of(d.pivot(bone),d.pivot(bone).add(.25f,.125f,.25f)))
                            maximum=Math.max(maximum,expected[bone].transformPosition(new Vector3f(point)).distance(actual.transformPosition(new Vector3f(point))));
                    }
                } finally {MemoryUtil.memFree(output);}
                System.out.println("SAME-POSE HANDOFF joints="+d.joints.size()+" terrain="+terrain+" fps="+fps+" maxPointDelta="+maximum);
                assert maximum<.002f : "same-state contact handoff changed displayed geometry";
            }
        }
    }
    private static void contactSliding() {
        for(int count:new int[]{1,33,65}) for(float tilt:new float[]{0,.7f}) for(int motion=0;motion<3;motion++) {
            boolean departure=motion==1;
            var geometry=model(bone("ysmPhys#bc=true,s=0,g=0,r=0,air=0,a=180,c=1@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
            var d=definition(geometry);var p=parameters(geometry);var basis=new Matrix4f().rotateZ(tilt);
            var root=new Matrix4f(basis).translate(0,motion==2?.2f:.062502f,0);
            var contacts=new ArrayList<PhysCollider>();
            for(int i=1;i<count;i++) contacts.add(new PhysCollider(new net.minecraft.world.phys.AABB(100+i,-1,0,100.5+i,0,1)));
            if(tilt==0) contacts.add(new PhysCollider(new net.minecraft.world.phys.AABB(-2,-1,-2,2,0,2)));
            else contacts.add(new PhysCollider(new float[]{-2,0,-2,-2,0,2,2,0,2,2,0,-2},basis));
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                kernel.solve(p,root,root,contacts,0,true,1f/60,ZERO,1);
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,kernel.state);
                var velocity=basis.transformDirection(motion==2?new Vector3f(0,-120,0):departure?new Vector3f(0,2.1f,0):new Vector3f(0,0,2.1f));
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)topology.sorted[1]*256+16,new float[]{velocity.x,velocity.y,velocity.z});
                kernel.solve(p,root,root,contacts,1,false,1f/60,ZERO,1,1);
                var data=read(kernel.output,d.bones.size()*144);
                try {
                    var tip=new Matrix4f(data.asFloatBuffer().position(36)).transformPosition(d.pivot(1));
                    new Matrix4f(basis).invert().transformPosition(tip);
                    assert tip.y>=.0625f-1e-5 : "sliding segment entered floor: "+tip;
                    if(motion!=2) assert departure?tip.y>.08f:tip.z>.02f : "sweep froze a clear contact slide/departure: "+tip;
                    else assert tip.y<.15f : "inward motion failed to approach floor: "+tip;
                } finally {MemoryUtil.memFree(data);}
            }
        }
        System.out.println("PASS: contact permits tangential sliding and departure without floor penetration");
    }
    private static void thinObstacleSweep() {
        for(float length:new float[]{1,4}) for(float speed:new float[]{2.1f,120}) for(int sign:new int[]{-1,1}) {
        var geometry=model(bone("ysmPhys#bc=true,s=0,g=0,r=0,air=0,a=180,c=0@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16*length,0,0));
        var d=definition(geometry);var p=parameters(geometry);var root=new Matrix4f();
        var contacts=List.of(new PhysCollider(new net.minecraft.world.phys.AABB(.499*length,(sign<0?-.0044:.0042)*length,-.1,.501*length,(sign<0?-.0042:.0044)*length,.1)));
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
            kernel.solve(p,root,root,contacts,0,true,1f/60,ZERO,1);
            // Both end poses are clear, but the swept segment crosses the thin box.
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,kernel.state);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)topology.sorted[1]*256+16,new float[]{0,sign*speed*length,0});
            kernel.solve(p,root,root,contacts,1,false,1f/60,ZERO,1,1);
            var data=read(kernel.output,d.bones.size()*144);
            try {
                var tip=new Matrix4f(data.asFloatBuffer().position(36)).transformPosition(d.pivot(1));
                assert sign*tip.y>=0 && sign*tip.y<.009f*length : "thin obstacle tunneled between angular samples: "+tip+" length="+length+" speed="+speed;
            } finally {MemoryUtil.memFree(data);}
        }
        }
        System.out.println("PASS: thin obstacle blocks a swept segment even when both end poses are clear");
    }
    private static void contactSnapshot() {
        var grouped=model(bone("ysmPhys#bc=true@a",-1,0,4,0),bone("ysmPhysEnd@a",0,16,4,0),
                bone("ysmPhys#bc=true@b",-1,8,4,0),bone("ysmPhysEnd@b",2,24,4,0),
                bone("ysmPhys#bc=true@far",-1,160,4,0),bone("ysmPhysEnd@far",4,176,4,0));
        var groupedDefinition=definition(grouped);var groupedParameters=parameters(grouped);
        var groupedQueries=new ArrayList<net.minecraft.world.phys.AABB>();
        double origin=10000000.125;
        var near=new net.minecraft.world.phys.AABB(-2,-1,-2,3,0,2).move(origin,0,0);
        var far=new net.minecraft.world.phys.AABB(9,-1,-2,12,0,2).move(origin,0,0);
        var groupedContacts=new GpuCollisionSnapshot(groupedDefinition).collect(groupedParameters,
                matrices(groupedDefinition,groupedParameters,new Matrix4f()),origin,0,0,bounds->{
                    groupedQueries.add(bounds);
                    return List.of(near,far).stream().filter(bounds::intersects).map(net.minecraft.world.phys.shapes.Shapes::create).toList();
                });
        assert groupedQueries.size()==2 : "overlapping island queries were not merged or distant islands were bridged";
        assert groupedContacts.size()==2 : "merged query lost a near or distant terrain collider";
        for(double anchor:new double[]{0,.5,10}) {
            var required=new net.minecraft.world.phys.AABB(origin+anchor,.25,0,origin+anchor,.25,0).inflate(1.06);
            assert groupedQueries.stream().anyMatch(q->q.minX<=required.minX && q.minY<=required.minY && q.minZ<=required.minZ
                    && q.maxX>=required.maxX && q.maxY>=required.maxY && q.maxZ>=required.maxZ) : "merge dropped rotational reach";
        }
        System.out.println("PASS: merged overlapping terrain queries retain full island reach, far-origin precision and distant colliders");
        for(boolean enabled:new boolean[]{false,true}) {
            var geometry=model(bone("ysmPhys#bc="+enabled+",s=0,r=3,air=0,a=180@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
            var d=definition(geometry);var p=parameters(geometry);var root=new Matrix4f().translate(0,.25f,0);
            var floor=new net.minecraft.world.phys.AABB(-2,-1,-2,2,0,2);
            for(int count:new int[]{1,32,33,64,65}) {
                var contacts=new ArrayList<PhysCollider>();
                for(int i=1;i<count;i++) contacts.add(new PhysCollider(new net.minecraft.world.phys.AABB(100+i,-1,0,100.5+i,0,1)));
                contacts.add(new PhysCollider(floor)); // The only relevant collider is the last one.
                try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                    kernel.solve(p,root,root,contacts,0,true,1f/60,ZERO,1);
                    for(int frame=0;frame<90;frame++) kernel.solve(p,root,root,contacts,1,false,1f/60,ZERO,1,.5f);
                    var data=read(kernel.output,d.bones.size()*144);
                    try {
                        var tip=new Matrix4f(data.asFloatBuffer().position(36)).transformPosition(d.pivot(1));
                        assert enabled?tip.y>=1f/16-.002f:tip.y<0 : "world contact mask/capacity failed: enabled="+enabled+" colliders="+count+" tip="+tip;
                    } finally {MemoryUtil.memFree(data);}
                }
            }
            double x=10000000.125,y=64,z=-10000000.125;
            int[] queries={0};var snapshot=new GpuCollisionSnapshot(d);
            var shape=net.minecraft.world.phys.shapes.Shapes.create(floor.move(x,y,z));
            var contacts=snapshot.collect(p,matrices(d,p,root),x,y,z,bounds->{
                queries[0]++;
                assert bounds.minX<x && bounds.maxX>x+1 && bounds.minY<y && bounds.maxY>y+1 : "snapshot does not enclose rotational reach in world coordinates";
                return List.of(shape,shape);
            });
            assert enabled?queries[0]==1 && contacts.size()==1:queries[0]==0 && contacts.isEmpty() : "snapshot query/dedup/bc gating failed";
            if(enabled) {
                var boxes=new ArrayList<net.minecraft.world.phys.AABB>();
                for(int i=0;i<=GpuCollisionSnapshot.MAX_COLLIDERS;i++) boxes.add(new net.minecraft.world.phys.AABB(i*.001,-1,0,i*.001+.0005,0,1));
                try {
                    snapshot.collect(p,matrices(d,p,root),0,0,0,bounds->boxes.stream().map(net.minecraft.world.phys.shapes.Shapes::create).toList());
                    throw new AssertionError("overflow silently truncated the terrain snapshot");
                } catch(GpuCollisionSnapshot.Limit expected) {}
            }
        }
        var geometry=fixture(2);var d=geometry.ysmPhysics;var p=parameters(geometry);
        var snapshot=new GpuCollisionSnapshot(d);
        assert snapshot.collect(p,matrices(d,p,new Matrix4f()),0,0,0,null).size()==1;
        p[4*12+9]=1;
        assert snapshot.collect(p,matrices(d,p,new Matrix4f()),0,0,0,null).isEmpty() : "hidden body collider remained active";
        System.out.println("PASS: GPU contacts at 1/32/33/64/65 colliders, last-collider coverage, bc isolation, far-origin snapshot reach, deduplication, visibility and overflow fallback");
    }
    private static void adaptiveTiming() {
        var warming=new GpuPhysics.Timings();warming.context(WORLD,60);
        for(int i=0;i<1000;i++) warming.cpu(i/1000.0,1);
        assert warming.cpuCount==0 && !warming.useGpu() : "high display cadence bypassed physical warmup";
        for(int i=0;i<8;i++) warming.cpu(1+i/60.0,100);
        assert warming.cpuCount==8 && warming.cpuMedian==100 && warming.useGpu() : "warmup contaminated the CPU baseline";
        warming.context(WORLD,120);
        warming.cpu(10,1);
        assert warming.cpuCount==0 : "cadence change skipped warmup";
        var geometry=fixture(0);var d=geometry.ysmPhysics;
        var owner=new com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel(geometry);
        System.arraycopy(parameters(geometry),0,owner.getMatrixData(),0,d.bones.size()*12);
        var cost=GpuPhysics.timing(d,WORLD,60);
        assert !cost.useGpu() : "GPU selected without a CPU baseline";
        for(int i=0;i<8;i++) cost.cpu(1); // A deliberately impossible GPU budget tests rejection on any device.
        assert cost.useGpu() : "GPU profiling phase never starts";
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()])) {
            var instance=new GpuPhysics.Instance(owner,topology);
            try {
                instance.fps=60;instance.world=WORLD;
                for(int batch=0;batch<3;batch++) {
                    for(int frame=0;frame<8;frame++) {
                        instance.time=(batch*8+frame)/60.0;
                        instance.draw(owner.getMatrixData(),new Matrix4f(),1);
                        // Repeated passes must not add a simulation sample.
                        int pending=(int)Arrays.stream(instance.timers).filter(id->id!=0).count();
                        instance.draw(owner.getMatrixData(),new Matrix4f(),1);
                        assert pending==Arrays.stream(instance.timers).filter(id->id!=0).count();
                    }
                    // A wait is allowed only in this offscreen test, never in the coordinator.
                    GL11.glFinish();instance.pollTimers();
                }
                assert cost.gpuCount==8 && !cost.useGpu() : "real GPU timers did not reject an unprofitable budget";
                long rejectedGpu=cost.rejectedGpuMedian;
                assert cost.rejectedCpuMedian==1 && rejectedGpu>0 : "missing rejection-time costs";
                for(int i=0;i<8;i++) cost.cpu(Long.MAX_VALUE/4);
                assert !cost.useGpu() : "CPU fallback overhead caused another GPU trial after rejection";
                assert cost.rejectedCpuMedian==1 && cost.rejectedGpuMedian==rejectedGpu : "rejection evidence changed after fallback";
                instance.sampleFrame=29;
                int external=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,external);
                try {
                    instance.time+=1.0/60;instance.draw(owner.getMatrixData(),new Matrix4f(),1);
                    assert GL15.glGetQueryi(GL33.GL_TIME_ELAPSED,GL15.GL_CURRENT_QUERY)==external : "profiler replaced another renderer's timer";
                } finally {GL15.glEndQuery(GL33.GL_TIME_ELAPSED);GL15.glDeleteQueries(external);}
                instance.sampleFrame=29;instance.time+=1.0/60;
                instance.draw(owner.getMatrixData(),new Matrix4f(),1);
                assert Arrays.stream(instance.timers).anyMatch(id->id!=0) : "test did not queue an old-cadence timer";
                int generation=cost.generation;
                cost.context(WORLD,120);
                assert generation!=cost.generation && cost.cpuCount==0 && cost.gpuCount==0 && !cost.useGpu();
                assert cost.rejectedCpuMedian==0 && cost.rejectedGpuMedian==0 : "old rejection evidence survived context reset";
                GL11.glFinish();
                instance.pollTimers();assert cost.gpuCount==0 : "old-cadence query contaminated the new baseline";
                int[] pending=instance.timers.clone();instance.closeTimers();
                for(int query:pending) if(query!=0) assert !GL15.glIsQuery(query) : "GPU timer leaked";
            } finally {instance.closeTimers();instance.kernel.close();}
        }
        System.out.println("PASS: asynchronous GPU timing, CPU warmup, measured rejection, repeated passes, external timer preservation, cadence reset and query cleanup");
    }
    private static GeoModel fixture(int scenario) {
        // Non-parent-first import order and a plain descendant are intentional.
        var m=scenario==1?model(bone("ysmPhys@a",2,-4,-8,0),bone("ysmPhys@b",2,4,-8,0),
                bone("ysmPhys@fork",-1,0,0,0),bone("decoration",0,-4,-18,0),
                bone("ysmPhysEnd@a",0,-4,-16,0),bone("ysmPhysEnd@b",1,4,-16,0)):
                model(bone("ysmPhysEnd@end",2,0,-20,0),bone("ysmPhys@root",-1,0,0,0),
                        bone("ysmPhys@child",1,0,-10,0),bone("decoration",2,0,-24,0),bone("ysmPhysC@body",-1,0,0,0));
        if(scenario==5) {
            m=model(bone("ysmPhysEnd@a",2,0,-20,0),bone("ysmPhys@root",-1,0,0,0),
                    bone("ysmPhys@child",1,0,-10,0),bone("decoration",2,0,-24,0),
                    bone("ysmPhys@other",-1,16,0,0),bone("ysmPhysEnd@other",4,16,-16,0));
        }
        if(scenario>=7) {
            float strength=scenario==7?4.5f:scenario==8?0:-4.5f;
            m=model(bone("ysmPhysEnd@end",2,16,8,4),bone("ysmPhys#gw="+strength+",s=0,r=4,air=0,a=180@root",-1,0,0,0),
                    bone("ysmPhys@child",1,8,0,0),bone("decoration",2,16,12,4),
                    bone("ysmPhys#g=0@falling",-1,32,0,0),bone("ysmPhysEnd@falling",4,48,0,0));
        }
        if(scenario==10) m=model(bone("ysmTail@root",-1,0,0,0),bone("ysmTail@child",0,0,0,8),bone("ysmPhysEnd@end",1,0,0,16));
        if(scenario>=2 && scenario<=4) {
            float[] faces=cube();for(int i=0;i<faces.length;i+=3) {faces[i]*=.2f;faces[i+1]=faces[i+1]*.2f-.65f;faces[i+2]=faces[i+2]*.2f+.15f;}
            m.bakedBones.get(4).physicsFaces.add(faces);
        }
        m.ysmPhysics=definition(m);return m;
    }
    static GeoModel loadModel(String path) throws Exception {
        try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(java.nio.file.Path.of(path))) {
            var geometry=reader.deserialize().mainEntity.mainModel;
            var method=com.elfmcys.yesstevemodel.resource.YSMClientMapper.class.getDeclaredMethod("buildMesh",
                    com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry.class,
                    com.elfmcys.yesstevemodel.resource.models.GeometryDescription.class,int.class,
                    com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner.class,boolean.class);
            method.setAccessible(true);
            return (GeoModel)method.invoke(null,geometry,com.elfmcys.yesstevemodel.resource.YSMClientMapper.buildContext(geometry),1,null,false);
        }
    }
    private static List<PhysCollider> contacts(PhysDefinition d,float[] p,Matrix4f root) {
        var matrices=matrices(d,p,root);var result=new ArrayList<PhysCollider>();
        for(var cube:d.cubes) result.add(new PhysCollider(cube.faces(),matrices[cube.bone()]));
        return result;
    }
    private static Matrix4f[] matrices(PhysDefinition d,float[] p,Matrix4f root) {
        Matrix4f[] out=new Matrix4f[d.bones.size()];
        for(int i:d.order) {int parent=d.bones.get(i).parentIdx;out[i]=RenderUtils.prepMatrixForBone(d.bones.get(i),new Matrix4f(parent<0?root:out[parent]),p,i*12);}
        return out;
    }
    private static ByteBuffer read(int buffer,int bytes) {
        ByteBuffer out=MemoryUtil.memAlloc(bytes);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,out);return out;
    }
    private static void parity(GeoModel geometry,int fps,int scenario) {
        var d=geometry.ysmPhysics;
        var cpu=new PhysSimulation(d);cpu.setFrameRate(fps);
        var gpuParameters=parameters(geometry);var cpuParameters=gpuParameters.clone();
        var clock=new GpuPhysics.Clock();
        float worst=0;
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
            for(int frame=0;frame<100;frame++) {
                Matrix4f root=new Matrix4f();
                if(scenario>=3) root.rotateX(.4f).scale(.7f);
                if(scenario==6) root.scale(1,1.2f,.9f);
                if(scenario>=7) root.rotateY(.4f*(float)Math.cos(frame*.11)).rotateZ(.6f*(float)Math.sin(frame*.08));
                cpu.restore(cpuParameters);
                for(var cube:d.cubes) {
                    int at=cube.bone()*12;
                    cpuParameters[at+2]=gpuParameters[at+2]=.15f*(float)Math.sin(frame*.1);
                    cpuParameters[at+9]=gpuParameters[at+9]=frame>=20 && frame<27?1:0;
                }
                // Pausing, repeated drawing, teleports, and backwards movement.
                boolean paused=frame>=30 && frame<35;double time=frame/60.0,z=scenario>=4?-4*time:0;
                if(scenario>=4 && frame>50) z=-4*(100-frame)/60.0;
                if(frame>=70) z+=100;
                Object world=frame<80?WORLD:geometry;
                clock.advance(time,paused,world,0,0,z,fps,false);
                cpu.update(cpuParameters,root,time,paused,world,0,0,z);
                var contacts=new GpuCollisionSnapshot(d).collect(gpuParameters,matrices(d,gpuParameters,root),0,0,z,null);
                kernel.solve(gpuParameters,root,root,contacts,clock.steps,clock.reset,1f/fps,clock.velocity,1234,clock.alpha());
                var expected=matrices(d,cpuParameters,root);
                ByteBuffer output=read(kernel.output,d.bones.size()*GpuPhysKernel.RESULT_BYTES);
                try {
                    Matrix4f[] actualMatrices=new Matrix4f[d.bones.size()];
                    for(int i=0;i<d.bones.size();i++) {
                        Matrix4f actual=new Matrix4f(output.asFloatBuffer().position(i*36));
                        actualMatrices[i]=actual;
                        Vector3f p=actual.transformPosition(d.pivot(i)),q=expected[i].transformPosition(d.pivot(i));
                        assert p.isFinite() : "non-finite GPU pose";
                        float error=p.distance(q);worst=Math.max(worst,error);
                        // Unconstrained motion remains a useful regression oracle. Contact solutions
                        // are checked against geometry/constraints below, not an arbitrary CPU pose.
                        if(d.cubes.isEmpty()) assert error<.001f : "GPU pose mismatch fps="+fps+" scenario="+scenario+" frame="+frame+" bone="+i+" error="+error;
                        assert output.getInt(i*144+128)==1234;
                    }
                    for(var joint:d.joints) for(int end:joint.outputs()) {
                        float expectedLength=expected[joint.bone()].transformPosition(d.pivot(joint.bone())).distance(expected[end].transformPosition(d.pivot(end)));
                        float actualLength=actualMatrices[joint.bone()].transformPosition(d.pivot(joint.bone())).distance(actualMatrices[end].transformPosition(d.pivot(end)));
                        assert Math.abs(expectedLength-actualLength)<.001f : "GPU bone stretched";
                    }
                } finally {MemoryUtil.memFree(output);}
                if(frame==25) {
                    ByteBuffer before=read(kernel.state,kernel.nodeCount*GpuPhysKernel.NODE_BYTES);
                    kernel.matrices(gpuParameters,new Matrix4f().translate(1,2,3),4321);
                    ByteBuffer after=read(kernel.state,kernel.nodeCount*GpuPhysKernel.NODE_BYTES);
                    try {
                        for(int i=0;i<kernel.nodeCount;i++) for(int j=0;j<16;j++) // position, velocity, target and correction
                            assert before.getInt(i*256+j*4)==after.getInt(i*256+j*4) : "repeated draw advanced simulation";
                    } finally {MemoryUtil.memFree(before);MemoryUtil.memFree(after);}
                }
            }
        }
        System.out.printf(Locale.ROOT,"%s: %d joints, %d cubes, %d Hz, scenario %d, max CPU trajectory difference %.6f blocks%n",d.cubes.isEmpty()?"FREE MOTION REGRESSION":"CONTACT TRAJECTORY (diagnostic only)",d.joints.size(),d.cubes.size(),fps,scenario,worst);
    }
    /** Acceptance is expressed in world geometry, independently of the CPU trajectory. */
    private static void physicalContacts(GeoModel geometry,int scenario) {
        var d=geometry.ysmPhysics;
        boolean stable=true;
        for(int fps:new int[]{60,120,15}) {
            float[] p=parameters(geometry);
            var root=new Matrix4f();if(scenario>=3) root.rotateX(.4f).scale(.7f);
            var animated=matrices(d,p,root);
            var snapshot=new GpuCollisionSnapshot(d);
            var contacts=snapshot.collect(p,animated,0,0,0,null);
            Vector3f[] previous=new Vector3f[d.bones.size()];
            Vector3f[] previousState=new Vector3f[d.bones.size()];
            float lateMovement=0,lateStateMovement=0;
            try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
                kernel.solve(p,root,root,contacts,0,true,1f/fps,ZERO,1234);
                for(int frame=1;frame<=fps*15;frame++) {
                    // Translate the reference frame, then allow enough stationary time to settle.
                    var velocity=frame<fps*2?new Vector3f(0,0,scenario>=4?-4:0):ZERO;
                    kernel.solve(p,root,root,contacts,1,false,1f/fps,velocity,1234);
                    var out=read(kernel.output,d.bones.size()*GpuPhysKernel.RESULT_BYTES);
                    try {
                        Matrix4f[] actual=new Matrix4f[d.bones.size()];
                        for(int i=0;i<actual.length;i++) {
                            actual[i]=new Matrix4f(out.asFloatBuffer().position(i*36));
                            assert actual[i].isFinite() : "non-finite GPU matrix";
                        }
                        for(var joint:d.joints) {
                            Vector3f scale=animated[joint.bone()].getScale(new Vector3f());
                            float radius=joint.parameters().c()/16f*Math.max(scale.x,Math.max(scale.y,scale.z));
                            var anchor=actual[joint.bone()].transformPosition(d.pivot(joint.bone()));
                            for(int end:joint.outputs()) {
                                var tip=actual[end].transformPosition(d.pivot(end));
                                float length=animated[end].transformPosition(d.pivot(end)).distance(animated[joint.bone()].transformPosition(d.pivot(joint.bone())));
                                assert Math.abs(tip.distance(anchor)-length)<.001f : "GPU segment length constraint failed";
                                if(frame>fps*12 && previous[end]!=null) lateMovement=Math.max(lateMovement,previous[end].distance(tip));
                                previous[end]=tip;
                                if(frame>4) for(var collider:contacts)
                                    assert !collider.resolveSegment(new Vector3f(anchor),new Vector3f(tip),new Vector3f(),new Vector3f(),Math.max(0,radius-.002f),true)
                                            : "GPU visible penetration > .002 blocks: fps="+fps+" frame="+frame+" joint="+d.bones.get(joint.bone()).getName()+" tip="+tip;
                            }
                            int sorted=d.islands()[joint.bone()]*d.bones.size()+topology.sorted[joint.bone()];
                            // Test reads every pose; production only reads on GPU -> CPU handoff.
                            var state=read(kernel.state,kernel.nodeCount*GpuPhysKernel.NODE_BYTES);
                            try {
                                for(int end:joint.outputs()) {
                                    int at=(topology.island[end]*d.bones.size()+topology.sorted[end])*GpuPhysKernel.NODE_BYTES;
                                    var point=new Vector3f(state.getFloat(at),state.getFloat(at+4),state.getFloat(at+8));
                                    if(frame>fps*12 && previousState[end]!=null) lateStateMovement=Math.max(lateStateMovement,point.distance(previousState[end]));
                                    previousState[end]=point;
                                }
                                var rotation=new org.joml.Quaternionf(state.getFloat(sorted*256+48),state.getFloat(sorted*256+52),state.getFloat(sorted*256+56),state.getFloat(sorted*256+60));
                                assert rotation.isFinite() && Math.abs(rotation.lengthSquared()-1)<.001 : "GPU correction is not a unit quaternion";
                                assert 2*Math.acos(Math.min(1,Math.abs(rotation.w)))<=Math.toRadians(joint.parameters().a())+.001 : "GPU angular limit failed";
                            } finally {MemoryUtil.memFree(state);}
                        }
                    } finally {MemoryUtil.memFree(out);}
                }
            }
            // 1/16 of one model pixel (16 pixels/block), below a visible pixel step.
            stable &= lateMovement<1f/256;
            System.out.printf(Locale.ROOT,"PHYSICAL: %d joints / %d colliders, %d Hz, scenario %d; penetration < .002 blocks, length < .001, late movement %.6f, state movement %.6f%n",d.joints.size(),contacts.size(),fps,scenario,lateMovement,lateStateMovement);
        }
        assert stable : "stationary GPU contact moves more than 1/16 model pixel/frame; see cadence results";
    }
    private static void visibilityAndState() throws Exception {
        var contactModel=fixture(3);
        assert GpuPhysics.supportsDefinition(contactModel.ysmPhysics) : "body contacts disabled";
        assert !GpuPhysics.supportedScale(contactModel.ysmPhysics,parameters(contactModel),new Matrix4f().scale(.7f,1.2f,.9f));
        assert GpuPhysics.supportedScale(contactModel.ysmPhysics,parameters(contactModel),new Matrix4f().scale(.7f));
        assert !GpuPhysics.supportedScale(contactModel.ysmPhysics,parameters(contactModel),new Matrix4f().scale(-1,1,1));
        var scalePose=parameters(contactModel);
        scalePose[3*12+6]=0;scalePose[3*12+7]=2;
        assert GpuPhysics.supportedScale(contactModel.ysmPhysics,scalePose,new Matrix4f()) : "decorative scale rejected physical solver";
        scalePose[1*12+6]=0;
        assert !GpuPhysics.supportedScale(contactModel.ysmPhysics,scalePose,new Matrix4f()) : "singular physical ancestor accepted";
        scalePose=parameters(contactModel);scalePose[4*12+7]=2;
        assert !GpuPhysics.supportedScale(contactModel.ysmPhysics,scalePose,new Matrix4f()) : "anisotropic collider accepted";
        boolean[] contacts=contactModel.ysmPhysics.cpuCollisionIslands();
        for(var joint:contactModel.ysmPhysics.joints) assert !contacts[joint.bone()] : "GPU contacts silently run on CPU";
        var terrainOnly=model(bone("ysmPhys#bc=true@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
        assert GpuPhysics.supportsDefinition(definition(terrainOnly)) : "terrain model must support GPU physics";
        var geometry=fixture(0);var d=geometry.ysmPhysics;float[] p=parameters(geometry);
        p[3*12+6]=0;p[3*12+7]=2;
        p[2*12+9]=1; // hide self, keep ordinary child visible
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(topology,1)) {
            kernel.solve(p,new Matrix4f(),new Matrix4f(),List.of(),0,true,1f/60,ZERO,1);
            ByteBuffer out=read(kernel.output,d.bones.size()*144);
            try {
                assert out.getInt(2*144+132)==1 && out.getInt(3*144+132)==1 && out.getInt(132)==1;
                for(int bone=0;bone<d.bones.size();bone++) for(int component=0;component<32;component++)
                    assert Float.isFinite(out.getFloat(bone*144+component*4)) : "decorative zero scale produced a non-finite matrix";
            }
            finally {MemoryUtil.memFree(out);}
            int sentinel=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,sentinel);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,4096,GL15.GL_DYNAMIC_COPY);
            int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,2,sentinel,alignment,256);
            try(var saved=new GpuPhysKernel.SavedState()) {kernel.matrices(p,new Matrix4f(),2);}
            assert GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,2)==sentinel;
            assert GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,2)==alignment;
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,0);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,0);GL15.glDeleteBuffers(sentinel);
            assert !GpuPhysics.requiresCpuPose(geometry,topology.affected,p);
            geometry.bakedBones.get(2).facingCameraSubtree=true;
            assert GpuPhysics.requiresCpuPose(geometry,topology.affected,p) : "billboard consumed stale GPU-only pose";
            assert !GpuPhysics.requiresCpuPose(geometry,new boolean[topology.affected.length],p)
                    : "unrelated billboard disabled GPU physics";
            geometry.bakedBones.get(2).facingCameraSubtree=false;
            p[2*12+11]=1;assert GpuPhysics.requiresCpuPose(geometry,topology.affected,p);
        }
    }
    /** Exercise the real Iris vertex-transform shader with GPU-local physics matrices. */
    private static void irisMatrices() throws Exception {
        var geometry=fixture(1);var d=geometry.ysmPhysics;var p=parameters(geometry);
        var root=new Matrix4f().translate(2,3,-4).rotateXYZ(.3f,-.5f,.2f).scale(.7f);
        int shader=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL43.GL_COMPUTE_SHADER,"/bone_xform.csh"));
        int input=GL15.glGenBuffers(),output=GL15.glGenBuffers();
        ByteBuffer vertices=MemoryUtil.memCalloc(32),local=null,world=null,result=null;
        try(var saved=new GpuPhysKernel.SavedState();var model=new GpuPhysModel(d,new boolean[d.bones.size()]);var kernel=new GpuPhysKernel(model,1)) {
            vertices.putFloat(.2f).putFloat(.3f).putFloat(.1f).putFloat(0).putFloat(0)
                    .putInt(511<<10).putInt(0).putInt(0).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,vertices,GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,output);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,36,GL15.GL_DYNAMIC_COPY);
            kernel.solve(p,root,root,List.of(),0,true,1f/60,ZERO,1234);
            for(int frame=0;frame<10;frame++) kernel.solve(p,root,root,List.of(),1,false,1f/60,ZERO,1234);
            world=read(kernel.output,d.bones.size()*144);
            kernel.matrices(p,new Matrix4f(),1234);
            local=read(kernel.output,d.bones.size()*144);
            for(int i=0;i<d.bones.size();i++) {
                var actual=new Matrix4f(local.asFloatBuffer().position(i*36));
                var expected=new Matrix4f(world.asFloatBuffer().position(i*36));
                var point=new Vector3f(.2f,.3f,.1f);
                assert root.transformPosition(actual.transformPosition(new Vector3f(point)))
                        .distance(expected.transformPosition(new Vector3f(point)))<.001f : "Iris applies root twice";
            }
            GL20.glUseProgram(shader);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader,"u_color"),1,1,1,1);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader,"u_packedOverlay"),0);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shader,"u_modelView"),false,root.get(new float[16]));
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,output);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,kernel.output);
            GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
            result=read(output,36);
            var actual=new Vector3f(result.getFloat(0),result.getFloat(4),result.getFloat(8));
            var expected=new Matrix4f(local.asFloatBuffer()).transformPosition(new Vector3f(.2f,.3f,.1f));
            assert actual.distance(expected)<.001f : "Iris vertex transform differs from local physical pose";
            assert GL11.glGetError()==GL11.GL_NO_ERROR : "Iris compute GL error";
        } finally {
            MemoryUtil.memFree(vertices);if(local!=null) MemoryUtil.memFree(local);if(world!=null) MemoryUtil.memFree(world);if(result!=null) MemoryUtil.memFree(result);
            GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(output);GL20.glDeleteProgram(shader);
        }
        System.out.println("PASS: Iris local physics SSBO, actual vertex-transform shader and root applied once");
    }

    /** GPU physics outputs are also reused by main/shadow draws without CPU waits. */
    private static void queuedPhysicsMatrices() {
        var geometry=fixture(1);var d=geometry.ysmPhysics;
        int draws=120,bytesPerDraw=d.bones.size()*16;
        int program=ShaderUtil.linkProgramWith(p->GL30.glTransformFeedbackVaryings(p,new CharSequence[]{"gl_Position"},GL30.GL_INTERLEAVED_ATTRIBS),
                ShaderUtil.compileShader(GL20.GL_VERTEX_SHADER,"""
                    #version 430 core
                    struct Bone {mat4 transform;mat4 normal;ivec4 flags;};
                    layout(std430,binding=0) readonly buffer Bones {Bone bones[];};
                    void main(){gl_Position=bones[gl_VertexID].transform*vec4(.2,.3,.1,1);}
                    ""","queued physics consumer"));
        int vao=GL30.glGenVertexArrays(),feedback=GL15.glGenBuffers();
        var captured=MemoryUtil.memAlloc(draws*bytesPerDraw);
        var expected=new ArrayList<Matrix4f[]>();
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()]);
            var first=new GpuPhysKernel(topology,1);var second=new GpuPhysKernel(topology,1)) {
            var parameters=parameters(geometry);
            first.solve(parameters,new Matrix4f(),new Matrix4f(),List.of(),0,true,1f/60,ZERO,1);
            second.solve(parameters,new Matrix4f(),new Matrix4f(),List.of(),0,true,1f/60,ZERO,1);
            GL30.glBindVertexArray(vao);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
            GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,(long)draws*bytesPerDraw,GL15.GL_STREAM_READ);
            for(int draw=0;draw<draws;draw++) {
                var kernel=draw%3==0?second:first;
                for(int bone=0;bone<d.bones.size();bone++) parameters[bone*12+2]=.3f*(float)Math.sin(draw*.17+bone);
                var root=new Matrix4f().translate(draw*.1f,draw%3,-2).rotateY(draw*.03f);
                expected.add(matrices(d,parameters,root));
                kernel.matrices(parameters,root,1);
                GL20.glUseProgram(program);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,kernel.output);
                GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback,(long)draw*bytesPerDraw,bytesPerDraw);
                GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,d.bones.size());GL30.glEndTransformFeedback();
            }
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);
            GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,captured);
            for(int draw=0;draw<draws;draw++) for(int bone=0;bone<d.bones.size();bone++) {
                var wanted=expected.get(draw)[bone].transform(new org.joml.Vector4f(.2f,.3f,.1f,1));
                var actual=new org.joml.Vector4f(captured.asFloatBuffer().position((draw*d.bones.size()+bone)*4));
                assert actual.equals(wanted,.001f) : "queued physics output was overwritten draw="+draw+" bone="+bone;
            }
            assert GL11.glGetError()==GL11.GL_NO_ERROR;
        } finally {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);GL30.glBindVertexArray(0);GL20.glUseProgram(0);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,0);
            GL15.glDeleteBuffers(feedback);GL30.glDeleteVertexArrays(vao);GL20.glDeleteProgram(program);MemoryUtil.memFree(captured);
        }
        System.out.println("PASS: 120 queued GPU physics matrix/consumer draws, two instances and repeated passes, no intermediate readbacks");
    }

    static GeoModel denseModel(GeoModel source,int count,boolean terrain) {
        var bones=new ArrayList<com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone>();
        for(int i=0;i<count;i++) {
            bones.add(bone("ysmPhys#bc="+terrain+"@root"+i,-1,i*2,4,0));
            bones.add(bone("ysmPhysEnd@end"+i,i*2,i*2+16,4,0));
        }
        bones.add(bone("ysmPhysC@body",-1,0,0,0));
        var geometry=model(bones.toArray(com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone[]::new));
        var visual=source.bakedBones.stream().filter(b->!b.cubes.isEmpty()).findFirst().orElseThrow().cubes;
        for(int i=0;i<geometry.bakedBones.size();i++) {
            var baked=geometry.bakedBones.get(i);var bone=bones.get(i);
            baked.pivotX=bone.getPivotX();baked.pivotY=bone.getPivotY();baked.pivotZ=bone.getPivotZ();
            if(i%2==0 && i<count*2) baked.cubes.addAll(visual);
        }
        if(!terrain) for(int body=0;body<16;body++) {
            float[] faces=cube();for(int i=0;i<faces.length;i+=3) {
                faces[i]=faces[i]*.15f+body*.5f;faces[i+1]=faces[i+1]*.15f-.3f;faces[i+2]*=.15f;
            }
            geometry.bakedBones.get(count*2).physicsFaces.add(faces);
        }
        geometry.ysmPhysics=definition(geometry);return geometry;
    }

    /** Sequential instance dispatches, real native matrices/SSBO uploads and production skin shader. */
    private static void productionBenchmark(GeoModel geometry,int count,boolean terrain,String name) throws Exception {
        var d=geometry.ysmPhysics;
        var mesh=net.everla.reimaginedysm.gpu.GpuRenderPath.getOrBuildMesh(geometry);
        assert mesh!=null;
        int draw=ShaderUtil.linkProgram(ShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER,"/bone_skin.vsh"),
                ShaderUtil.compileShader(GL20.GL_FRAGMENT_SHADER,"#version 430 core\nout vec4 color;void main(){color=vec4(1);}","benchmark fragment"));
        GL20.glUseProgram(draw);GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(draw,"u_proj"),false,new Matrix4f().get(new float[16]));
        var root=new Matrix4f();float[] rootFloats=root.get(new float[16]),normal=new org.joml.Matrix3f().get(new float[9]);
        var floor=net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(-32,-1,-32,32,0,32));
        java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> blocks=terrain?bounds->List.of(floor):null;
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        var visibility=net.everla.reimaginedysm.gpu.GpuRenderPath.class.getDeclaredMethod("updateBoneVisibility",List.class,float[].class,ByteBuffer.class);visibility.setAccessible(true);
        try(var topology=new GpuPhysModel(d,new boolean[d.bones.size()])) {
            for(int backend=0;backend<3;backend++) {
                boolean gpu=backend==1,returned=backend==2;
                var kernels=new GpuPhysKernel[count];var cpu=new PhysSimulation[count];
                var snapshots=new GpuCollisionSnapshot[count];float[][] poses=new float[count][];
                int frames=90;int[] queries=new int[frames];
                try {
                    for(int i=0;i<count;i++) {
                        poses[i]=parameters(geometry);cpu[i]=new PhysSimulation(d);cpu[i].setFrameRate(60);
                        if(gpu) {kernels[i]=new GpuPhysKernel(topology,1);snapshots[i]=new GpuCollisionSnapshot(d);}
                        if(returned) try(var seed=new GpuPhysKernel(topology,1)) {
                            var clock=new GpuPhysics.Clock();clock.advance(0,false,WORLD,0,0,0,60,false);
                            seed.solve(poses[i],root,root,contacts(d,poses[i],root),0,true,1f/60,ZERO,1234);
                            seed.handoff(cpu[i],clock);
                        }
                    }
                    GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                    long started=0,allocated=0;double submit=0,total=0;
                    for(int frame=0;frame<60+frames;frame++) {
                        if(frame==60) {GL11.glFinish();started=System.nanoTime();allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId());}
                        int q=frame-60;
                        if(q>=0) {queries[q]=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[q]);}
                        for(int i=0;i<count;i++) {
                            int buffer;
                            if(gpu) {
                                // Scene extraction, allocation, both state-save scopes and the draw's
                                // upload/dispatch are timed. No artificial multi-instance batch.
                                try(var saved=new GpuPhysKernel.SavedState()) {GpuPhysKernel.compile();}
                                var contacts=snapshots[i].collect(poses[i],matrices(d,poses[i],root),0,0,0,blocks);
                                try(var saved=new GpuPhysKernel.SavedState()) {
                                    kernels[i].solve(poses[i],root,root,contacts,frame==0?0:1,frame==0,1f/60,ZERO,1234);
                                }
                                buffer=kernels[i].output;
                            } else {
                                cpu[i].update(poses[i],root,frame/60.0,false,WORLD,0,0,0,blocks);
                                var pose=mesh.pose(poses[i]);pose.boneBuffer.clear();
                                GeoModel.nComputeBoneMatrices(mesh.pointer,rootFloats,normal,PlayerPhysics.hideEndpoints(geometry,poses[i]),1234,pose.boneBuffer);
                                visibility.invoke(null,geometry.bakedBones,poses[i],pose.boneBuffer);
                                pose.uploadBones();buffer=pose.boneSsbo;
                            }
                            GL20.glUseProgram(draw);GL30.glBindVertexArray(mesh.vao);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);
                            GL11.glDrawElements(GL11.GL_TRIANGLES,mesh.indexCount,GL11.GL_UNSIGNED_INT,0L);
                        }
                        if(q>=0) GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    }
                    submit=(System.nanoTime()-started)/frames/1e6;
                    double kib=(bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated)/frames/1024.0;
                    GL11.glFinish();total=(System.nanoTime()-started)/frames/1e6;
                    double[] times=new double[frames];for(int i=0;i<frames;i++) times[i]=GL33.glGetQueryObjectui64(queries[i],GL15.GL_QUERY_RESULT)/1e6;
                    Arrays.sort(times);
                    System.out.printf(Locale.ROOT,"PRODUCTION %s %s: %d sequential instances x %d joints / %d body cubes, native mesh %d vertices; wall %.3f ms/frame, CPU submission %.3f ms/frame, GPU median/p95 %.3f/%.3f ms, Java allocation %.1f KiB/frame%n",
                            name,gpu?"GPU":returned?"CPU-returned":"CPU",count,d.joints.size(),d.cubes.size(),mesh.vertexCount,total,submit,times[frames/2],times[(int)(frames*.95)],kib);
                } finally {
                    for(var kernel:kernels) if(kernel!=null) kernel.close();
                    for(int query:queries) if(query!=0) GL15.glDeleteQueries(query);
                }
            }
        } finally {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);GL20.glUseProgram(0);GL30.glBindVertexArray(0);
            GL20.glDeleteProgram(draw);net.everla.reimaginedysm.gpu.GpuRenderPath.disposeMesh(geometry);
        }
    }

}
