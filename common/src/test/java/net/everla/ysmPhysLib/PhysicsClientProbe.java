package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.config.ClientConfigScope;
import com.elfmcys.yesstevemodel.config.GeneralConfig;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Development-only mod, packaged only by -PphysicsClientProbe. Never part of the release. */
public final class PhysicsClientProbe {
    private Object scene;
    private int groupFrames;
    private final boolean worldProbe=Boolean.getBoolean("ysm.physicsWorldProbe");
    private boolean openingWorld;
    private AnimatedGeoModel owner;
    private int sceneIndex,frame,gpuFrames,cpuFrames;
    private long worldQueries,worldShapes,worldQueryNanos,gpuWorldQueries,gpuWorldShapes;
    private boolean gpuQueryPhase;
    private boolean done;
    private com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity<?> adapter;
    private com.elfmcys.yesstevemodel.client.model.ModelAssembly visualAssembly;
    private int adapterStartTick;
    private final boolean frameBenchmark=Boolean.getBoolean("ysm.physicsFrameBenchmark");
    private final boolean visualProbe=Boolean.getBoolean("ysm.physicsVisualProbe");
    private final boolean visualGpu=Boolean.getBoolean("ysm.physicsVisualGpu");
    private int visualStage=-1;
    private net.minecraft.resources.ResourceLocation visualTexture;
    private int benchmarkPhase,benchmarkRun;
    private long lastFrame;
    private final java.util.ArrayList<Long> frameTimes=new java.util.ArrayList<>();
    private final java.util.ArrayList<Long> workTimes=new java.util.ArrayList<>();
    private Matrix4f worldView;
    private float worldPartialTick;
    private boolean positionedCamera;
    private int measuredGpuFrames;
    private boolean capturedGpuFrame;

    public void render() {
        var client=Minecraft.getInstance();
        if(done || client.getOverlay()!=null) return;
        try {
            if(worldProbe && client.level==null) {
                if(!openingWorld && client.screen!=null) {
                    openingWorld=true;
                    client.createWorldOpenFlows().createFreshLevel("physics-probe-"+System.currentTimeMillis(),
                            new net.minecraft.world.level.LevelSettings("Physics probe",net.minecraft.world.level.GameType.CREATIVE,
                                    false,net.minecraft.world.Difficulty.PEACEFUL,true,new net.minecraft.world.level.GameRules(),
                                    net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                            new net.minecraft.world.level.levelgen.WorldOptions(1L,false,false),
                            registries->registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).createWorldDimensions());
                }
                return;
            }
            if(worldProbe && (client.player==null || client.screen!=null)) return;
            if(!worldProbe && client.screen==null) return;
            scene=client.level;
            if(Boolean.getBoolean("ysm.groupShaderProbe") && groupFrames>=360) {
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                    image.writeToFile(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")).resolveSibling("group-shader.png"));
                }
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")),"PASS");
                done=true;client.stop();return;
            }
            if(frameBenchmark) return; // Actual world render event owns measured simulation and drawing.
            if(worldProbe && sceneIndex==3) {renderAdapter(client);return;}
            if(owner==null) {
                if(worldProbe) {
                    var ground=new net.minecraft.core.BlockPos(0,-61,0);
                    if(!client.level.hasChunkAt(ground)) return;
                    if(client.level.getBlockState(ground).isAir()) throw new AssertionError("test world's ground is absent");
                    boolean found=false;
                    for(var shape:client.level.getBlockCollisions(client.player,new net.minecraft.world.phys.AABB(-1,-61,-1,1,-59,1)))
                        found|=!shape.isEmpty();
                    if(!found) throw new AssertionError("real world query returned no ground collision");
                }
                ClientConfigScope.effective(PhysConfig.EXPERIMENTAL_GPU).set(true);
                ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER).set(true);
                ClientConfigScope.effective(GeneralConfig.USE_COMPATIBILITY_RENDERER).set(false);
                GeoModel model;
                if(sceneIndex==0) model=GpuPhysCheck.loadModel(System.getProperty("ysm.physicsModel"));
                else model=GpuPhysCheck.denseModel(GpuPhysCheck.loadModel(System.getProperty("ysm.physicsExample")),64,sceneIndex==2);
                owner=new AnimatedGeoModel(model);owner.ysmPhysics=new PhysSimulation(model.ysmPhysics);
                owner.ysmPhysics.setFrameRate(60);
                System.arraycopy(PhysCheck.parameters(model),0,owner.getMatrixData(),0,owner.getMatrixData().length);
                System.out.println("CLIENT-PROBE START scene="+sceneIndex+" joints="+model.ysmPhysics.joints.size());
            }
            var p=owner.getMatrixData();var root=new Matrix4f();double time=frame/60.0;
            var floor=net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(-32,-1,-32,32,0,32));
            double originY=worldProbe?-60:0;
            java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> blocks=
                    worldProbe?bounds->{
                        worldQueries++;if(gpuQueryPhase) gpuWorldQueries++;
                        var source=client.level.getBlockCollisions(client.player,bounds);
                        return ()->{
                            var iterator=source.iterator();
                            return new java.util.Iterator<net.minecraft.world.phys.shapes.VoxelShape>() {
                                public boolean hasNext() {
                                    long started=System.nanoTime();
                                    try {return iterator.hasNext();} finally {worldQueryNanos+=System.nanoTime()-started;}
                                }
                                public net.minecraft.world.phys.shapes.VoxelShape next() {
                                    long started=System.nanoTime();
                                    try {var shape=iterator.next();if(!shape.isEmpty()) {worldShapes++;if(gpuQueryPhase) gpuWorldShapes++;}return shape;}
                                    finally {worldQueryNanos+=System.nanoTime()-started;}
                                }
                            };
                        };
                    }:sceneIndex==2?bounds->java.util.List.of(floor):null;
            owner.ysmPhysics.restore(p);
            gpuQueryPhase=true;
            boolean gpu=GpuPhysics.prepare(owner,root,time,false,scene,0,originY,0,60,true,blocks);
            if(gpu) {
                int buffer=GpuPhysics.render(p,root,15728880);
                if(buffer==0) throw new AssertionError("dispatch fell back: "+GpuPhysics.getLastReason());
                gpuFrames++;
            } else {
                gpuQueryPhase=false;
                long started=System.nanoTime();owner.ysmPhysics.update(p,root,time,false,scene,0,originY,0,blocks);
                if(owner.ysmPhysics.sampledFrame()) GpuPhysics.recordCpu(owner.getGeoModel().ysmPhysics,scene,60,time,System.nanoTime()-started);
                cpuFrames++;
            }
            gpuQueryPhase=false;
            // Exercise the real client renderer and native mesh on both backends.
            // Discard fragments: the probe does not judge menu pixels as world visuals.
            boolean discard=GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            try {
                if(!net.everla.reimaginedysm.gpu.GpuRenderPath.tryRender(owner.getGeoModel(),
                        new com.mojang.blaze3d.vertex.PoseStack().last(),p,owner.getAbsPivotData(),0,0,
                        15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,1,1,1,1,
                        net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS))
                    throw new AssertionError("real client mesh renderer rejected the draw");
            } finally {if(!discard) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);}
            int error=GL11.glGetError();if(error!=GL11.GL_NO_ERROR) throw new AssertionError("client GL error: "+error);
            // Normal frame presentation flushes queries; no glFinish or test-forced backend.
            if(++frame<180) return;
            var cost=GpuPhysics.timing(owner.getGeoModel().ysmPhysics,scene,60);
            if(cost.cpuCount!=8 || cost.gpuCount!=8 || gpuFrames==0 || cpuFrames==0)
                throw new AssertionError("calibration incomplete: "+GpuPhysics.getLastReason());
            if(gpu!=cost.useGpu()) throw new AssertionError("coordinator ignored cost decision");
            if(worldProbe && sceneIndex==2 && (worldQueries==0 || worldShapes==0)) throw new AssertionError("terrain workload contained no collision shapes");
            System.out.println("CLIENT-PROBE PASS scene="+sceneIndex+" cpuFrames="+cpuFrames+" gpuFrames="+gpuFrames
                    +" CPUmedianNs="+cost.cpuMedian+" GPUmedianNs="+cost.gpuMedian+" selected="+(gpu?"GPU":"CPU")
                    +" rejected="+cost.rejectedCost+" worldQueries="+worldQueries+" nonemptyShapes="+worldShapes
                    +" gpuWorldQueries="+gpuWorldQueries+" gpuWorldShapes="+gpuWorldShapes
                    +" rejectionCPUmedianNs="+cost.rejectedCpuMedian+" rejectionGPUmedianNs="+cost.rejectedGpuMedian
                    +" queryIteratorNs="+worldQueryNanos+" GL="+GL11.glGetString(GL11.GL_RENDERER));
            GpuPhysics.disposeModel(owner.getGeoModel());
            net.everla.reimaginedysm.gpu.GpuRenderPath.disposeMesh(owner.getGeoModel());
            owner=null;frame=gpuFrames=cpuFrames=0;
            worldQueries=worldShapes=worldQueryNanos=gpuWorldQueries=gpuWorldShapes=0;
            if(++sceneIndex==3 && !worldProbe) {
                done=true;System.out.println("CLIENT-PROBE ALL PASS (worldQueries="+worldProbe+"; no entity adapter or pixel acceptance)");
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")),"PASS");client.stop();
            }
        } catch(Throwable failure) {
            done=true;failure.printStackTrace();System.out.println("CLIENT-PROBE FAIL: "+failure);client.stop();
        }
    }

    public void renderWorld(com.mojang.blaze3d.vertex.PoseStack pose,float partialTick) {
        if(!frameBenchmark || done) return;
        var client=Minecraft.getInstance();
        if(client.level==null || client.player==null || client.screen!=null || client.getOverlay()!=null) return;
        scene=client.level;worldView=new Matrix4f(pose.last().pose());worldPartialTick=partialTick;
        try {renderAdapter(client);groupFrames++;}
        catch(Throwable failure) {done=true;failure.printStackTrace();System.out.println("CLIENT-PROBE FAIL: "+failure);client.stop();}
        finally {worldView=null;}
    }

    private void renderAdapter(Minecraft client) throws Exception {
        if(owner==null) {
            if(frameBenchmark) benchmarkPhase=Boolean.getBoolean("ysm.physicsFrameReverse")?benchmarkRun^1:benchmarkRun;
            GeoModel geometry;
            if(visualProbe) {
                try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(java.nio.file.Path.of(System.getProperty("ysm.physicsModel")))) {
                    var raw=reader.deserialize();
                    var parsed=com.elfmcys.yesstevemodel.resource.YSMClientMapper.buildParsedBundle(raw,"physics_visual_probe");
                    visualAssembly=com.elfmcys.yesstevemodel.client.model.ModelAssemblyFactory.buildAssembly(parsed,false,false);
                    geometry=parsed.getMainModelData().getModels().get(0);
                    String name=parsed.getInfo().getModelProperties().getDefaultTexture();
                    var textures=parsed.getMainModelData().getTextureMap();
                    var texture=textures.containsKey(name)?textures.get(name):textures.getValueAt(0);
                    visualTexture=new net.minecraft.resources.ResourceLocation("yes_steve_model","physics_visual_probe");
                    client.getTextureManager().register(visualTexture,texture);
                    // Only this selected texture is uploaded. Other parsed textures
                    // retain encoded bytes and never allocate GL resources.
                }
            } else geometry=GpuPhysCheck.denseModel(GpuPhysCheck.loadModel(System.getProperty("ysm.physicsExample")),64,!frameBenchmark || benchmarkPhase<2);
            owner=new AnimatedGeoModel(geometry);owner.ysmPhysics=new PhysSimulation(geometry.ysmPhysics);
            System.arraycopy(PhysCheck.parameters(geometry),0,owner.getMatrixData(),0,owner.getMatrixData().length);
            var entity=new net.minecraft.world.entity.decoration.ArmorStand(client.level,0,-60,0);
            entity.xo=0;entity.yo=-60;entity.zo=0;
            adapter=new com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity<>(entity) {
                public net.minecraft.resources.ResourceLocation getTextureLocation() {return net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS;}
                public boolean isModelReady() {return true;}
                public float getHeightScale() {return 1;}
                public float getWidthScale() {return 1;}
                public com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation getAnimation(String name) {return null;}
                public void registerAnimationControllers() {}
            };
            if(visualProbe) {
                var player=new net.minecraft.client.player.RemotePlayer(client.level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"PhysicsProbe"));
                player.setPos(0,-60,0);player.xo=0;player.yo=-60;player.zo=0;
                var animated=new com.elfmcys.yesstevemodel.client.entity.CustomPlayerEntity(player,false,false) {
                    public com.elfmcys.yesstevemodel.client.entity.GeoEntity.ModelWrapper buildRenderShape(com.elfmcys.yesstevemodel.client.model.ModelAssembly assembly,boolean fallback) {return null;}
                    public boolean isModelReady() {return true;}
                    public net.minecraft.resources.ResourceLocation getTextureLocation() {return visualTexture;}
                    public boolean shouldSkipAnimation(com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent<?> event) {return false;}
                    public java.util.Optional<com.elfmcys.yesstevemodel.audio.IAudioStreamFactory> getAudioStreamFactory(String name) {return java.util.Optional.empty();}
                };
                // Test-only injection avoids registering a model in global player
                // selection, while retaining the production player controllers.
                var assemblyField=com.elfmcys.yesstevemodel.client.entity.GeoEntity.class.getDeclaredField("modelAssembly");
                assemblyField.setAccessible(true);assemblyField.set(animated,visualAssembly);
                animated.initAnimationControllers(geometry,visualAssembly.getExpressionCache().getEvents());
                adapter=animated;owner=animated.getCurrentModel();owner.ysmPhysics=new PhysSimulation(geometry.ysmPhysics);
            }
            ClientConfigScope.effective(PhysConfig.ENABLED).set(true);
            ClientConfigScope.effective(PhysConfig.FPS).set(60);
            if(frameBenchmark) {
                if(!positionedCamera) {
                    positionedCamera=true;
                    var playerId=client.player.getUUID();
                    client.getSingleplayerServer().execute(()->{
                        if(Boolean.getBoolean("ysm.groupShaderProbe")) {
                            var level=client.getSingleplayerServer().overworld();
                            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,client.getSingleplayerServer());
                            level.setDayTime(18000);
                            level.setWeatherParameters(6000,0,false,false);
                        }
                        var player=client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                        if(player!=null) player.connection.teleport(0,-60,4,180,10);
                    });
                }
                ClientConfigScope.effective(PhysConfig.EXPERIMENTAL_GPU).set(benchmarkPhase%2==1);
                ClientConfigScope.effective(GeneralConfig.USE_GPU_RENDERER).set(true);
                ClientConfigScope.effective(GeneralConfig.USE_COMPATIBILITY_RENDERER).set(false);
                client.options.enableVsync().set(false);client.options.framerateLimit().set(Boolean.getBoolean("ysm.groupShaderProbe")?60:Integer.getInteger("ysm.physicsFrameLimit",260));
                frameTimes.clear();workTimes.clear();lastFrame=0;measuredGpuFrames=0;capturedGpuFrame=false;
            }
            adapterStartTick=client.player.tickCount;
            System.out.println("CLIENT-PROBE START entity adapter");
        }
        long workStarted=System.nanoTime();
        adapter.entity.tickCount=client.player.tickCount;
        if(visualProbe) {
            double elapsed=(client.player.tickCount+worldPartialTick-adapterStartTick)/20.0;
            adapter.entity.xo=adapter.entity.getX();adapter.entity.yo=adapter.entity.getY();adapter.entity.zo=adapter.entity.getZ();
            adapter.entity.setPos(Math.sin(elapsed*2)*.5,-60,Math.sin(elapsed)*.75);
        }
        var camera=client.gameRenderer.getMainCamera();var position=camera.getPosition();
        var view=worldView!=null?new Matrix4f(worldView):new Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate());
        var pose=new Matrix4f(view).translate((float)(adapter.entity.getX()-position.x),(float)(adapter.entity.getY()-position.y),(float)(adapter.entity.getZ()-position.z));
        if(visualProbe) pose.scale(adapter.getWidthScale(),adapter.getHeightScale(),adapter.getWidthScale());
        PlayerPhysics.beforeAnimation(owner);
        if(visualProbe) adapter.processAnimationImpl(worldPartialTick,false);
        if(visualGpu && visualProbe && benchmarkPhase%2==1) {
            var cost=GpuPhysics.timing(owner.getGeoModel().ysmPhysics,scene,60);
            cost.rejectedCost=false;
            for(int i=0;i<8;i++) cost.cpu(Long.MAX_VALUE/4);
        }
        WorldPhysicsContext.begin(view);
        try {PlayerPhysics.render(owner,adapter,worldView!=null?worldPartialTick:client.getFrameTime(),pose);}
        finally {WorldPhysicsContext.end();}
        // The native render path dispatches physics itself. Do not dispatch a
        // second matrices-only pass merely to count the selected backend.
        boolean gpu=frameBenchmark?GpuPhysics.active(owner.getMatrixData()):GpuPhysics.render(owner.getMatrixData(),pose,15728880)!=0;
        if(frameBenchmark) {
            var drawPose=new com.mojang.blaze3d.vertex.PoseStack();drawPose.mulPoseMatrix(pose);
            if(Boolean.getBoolean("ysm.groupShaderProbe")) {
                WorldPhysicsContext.begin(view);
                try {
                    com.elfmcys.yesstevemodel.geckolib3.geo.NativeModelRenderer.renderMesh(
                            new com.mojang.blaze3d.vertex.BufferBuilder(256),drawPose.last(),owner.getGeoModel(),owner.getMatrixData(),
                            owner.getAbsPivotData(),0,0,net.minecraft.client.renderer.LevelRenderer.getLightColor(client.level,adapter.entity.blockPosition()),net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                            1,1,1,1,visualTexture);
                    int error=GL11.glGetError();
                    if(error!=GL11.GL_NO_ERROR) throw new AssertionError("Group shader GL error 0x"+Integer.toHexString(error));
                } finally {WorldPhysicsContext.end();}
            } else {
            if(!net.everla.reimaginedysm.gpu.GpuRenderPath.tryRender(owner.getGeoModel(),drawPose.last(),owner.getMatrixData(),
                    owner.getAbsPivotData(),0,0,net.minecraft.client.renderer.LevelRenderer.getLightColor(client.level,adapter.entity.blockPosition()),net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    1,1,1,1,visualProbe?visualTexture:new net.minecraft.resources.ResourceLocation("minecraft","textures/block/white_concrete.png")))
                throw new AssertionError("world mesh draw rejected");
            }
            gpu=GpuPhysics.active(owner.getMatrixData());
            if(gpu && !capturedGpuFrame) {
                capturedGpuFrame=true;
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                    image.writeToFile(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")).resolveSibling("gpu-trial-"+benchmarkPhase+".png"));
                }
            }
        }
        if(gpu) gpuFrames++;else cpuFrames++;
        if(visualGpu && visualProbe && benchmarkPhase%2==1) {
            if(!gpu) throw new AssertionError("forced visual GPU unavailable: "+GpuPhysics.getLastReason());
            validateGpuGeometry(pose,view,position);
        }
        if(visualProbe) {
            int stage=(client.player.tickCount-adapterStartTick)/20;
            if(stage!=visualStage && stage>=2 && stage<=8) {
                visualStage=stage;
                try(var image=net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                    image.writeToFile(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")).resolveSibling("actual-motion-"+benchmarkPhase+"-"+stage+".png"));
                }
            }
        }
        for(float value:owner.getMatrixData()) if(!Float.isFinite(value)) throw new AssertionError("non-finite adapter pose");
        if(GL11.glGetError()!=GL11.GL_NO_ERROR) throw new AssertionError("adapter GL error");
        frame++;
        if(client.player.tickCount-adapterStartTick<100) return;
        if(frameBenchmark) {
            long now=System.nanoTime();if(lastFrame!=0) {frameTimes.add(now-lastFrame);workTimes.add(now-workStarted);if(gpu) measuredGpuFrames++;}lastFrame=now;
            if(frameTimes.size()<600) return;
            var sorted=frameTimes.stream().mapToLong(Long::longValue).sorted().toArray();
            var work=workTimes.stream().mapToLong(Long::longValue).sorted().toArray();
            var cost=GpuPhysics.timing(owner.getGeoModel().ysmPhysics,scene,60);
            System.out.println((visualGpu?"VISUAL-CHECK (forced backend; synchronous readback; NOT performance evidence)":"FRAME-BENCH")+" phase="+benchmarkPhase+" scene="+(visualProbe?"actual moving model":benchmarkPhase<2?"terrain":"body")
                    +" requested="+(benchmarkPhase%2==0?"CPU":visualGpu?"forced GPU validation":"adaptive GPU")+" samples="+sorted.length
                    +" meanMs="+java.util.Arrays.stream(sorted).average().orElseThrow()/1e6
                    +" medianMs="+sorted[sorted.length/2]/1e6+" p95Ms="+sorted[(int)(sorted.length*.95)]/1e6
                    +" workMeanMs="+java.util.Arrays.stream(work).average().orElseThrow()/1e6
                    +" workMedianMs="+work[work.length/2]/1e6+" workP95Ms="+work[(int)(work.length*.95)]/1e6
                    +" frameLimit="+Integer.getInteger("ysm.physicsFrameLimit",260)
                    +" cpuFrames="+cpuFrames+" gpuFrames="+gpuFrames+" measuredGpuFrames="+measuredGpuFrames
                    +" CPUmedianNs="+cost.cpuMedian+" GPUmedianNs="+cost.gpuMedian
                    +" rejectionCPUmedianNs="+cost.rejectedCpuMedian+" rejectionGPUmedianNs="+cost.rejectedGpuMedian
                    +" rejectionPreparationMedianNs="+cost.rejectedPreparationMedian+" rejectionDeviceMedianNs="+cost.rejectedDeviceMedian
                    +" rejectionSubmissionMedianNs="+cost.rejectedSubmissionMedian
                    +" window="+client.getWindow().getWidth()+"x"+client.getWindow().getHeight());
            // Capture after measurement so synchronous pixel readback is excluded.
            try(var image=net.minecraft.client.Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                image.writeToFile(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")).resolveSibling("frame-bench-"+benchmarkPhase+".png"));
            }
            GpuPhysics.disposeModel(owner.getGeoModel());net.everla.reimaginedysm.gpu.GpuRenderPath.disposeMesh(owner.getGeoModel());
            if(visualTexture!=null) {client.getTextureManager().release(visualTexture);visualTexture=null;}
            owner=null;adapter=null;frame=cpuFrames=gpuFrames=0;
            visualStage=-1;
            if(++benchmarkRun<(visualProbe?2:4)) return;
            done=true;java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")),"PASS");
            System.out.println("FRAME-BENCH ALL PASS (world event + native raster draws; no pixel acceptance)");client.stop();return;
        }
        var cost=GpuPhysics.timing(owner.getGeoModel().ysmPhysics,scene,60);
        if(cost.cpuCount!=8 || cost.gpuCount!=8 || cpuFrames==0 || gpuFrames==0)
            throw new AssertionError("entity adapter did not calibrate: "+GpuPhysics.getLastReason());
        System.out.println("CLIENT-PROBE PASS entity adapter frames="+frame+" cpuFrames="+cpuFrames+" gpuFrames="+gpuFrames
                +" CPUmedianNs="+cost.cpuMedian+" GPUmedianNs="+cost.gpuMedian);
        GpuPhysics.disposeModel(owner.getGeoModel());
        net.everla.reimaginedysm.gpu.GpuRenderPath.disposeMesh(owner.getGeoModel());
        done=true;
        System.out.println("CLIENT-PROBE ALL PASS (world queries + PlayerPhysics entity/camera adapter; no pixel acceptance)");
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ysm.physicsProbeResult")),"PASS");
        client.stop();
    }

    private void validateGpuGeometry(Matrix4f pose,Matrix4f view,net.minecraft.world.phys.Vec3 camera) throws Exception {
        var field=GpuPhysics.class.getDeclaredField("instances");field.setAccessible(true);
        var instances=(java.util.Map<?,?>)field.get(null);
        var instance=(GpuPhysics.Instance)instances.get(owner.getMatrixData());
        var d=owner.getGeoModel().ysmPhysics;
        var data=org.lwjgl.system.MemoryUtil.memAlloc(d.bones.size()*144);
        try {
            org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER,instance.kernel.output);
            org.lwjgl.opengl.GL15.glGetBufferSubData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER,0,data);
            var root=new Matrix4f().translation((float)(camera.x-adapter.entity.getX()),(float)(camera.y-adapter.entity.getY()),(float)(camera.z-adapter.entity.getZ()))
                    .mul(new Matrix4f(view).invert().mul(pose));
            var toWorld=new Matrix4f(root).mul(new Matrix4f(pose).invert());
            var animated=new Matrix4f[d.bones.size()];var actual=new Matrix4f[d.bones.size()];
            var p=owner.getMatrixData();
            for(int i:d.order) {
                int parent=d.bones.get(i).parentIdx;
                animated[i]=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(d.bones.get(i),new Matrix4f(parent<0?root:animated[parent]),p,i*12);
                actual[i]=new Matrix4f(toWorld).mul(new Matrix4f(data.asFloatBuffer().position(i*36)));
                if(!actual[i].isFinite()) throw new AssertionError("non-finite GPU visual matrix");
            }
            var contacts=new GpuCollisionSnapshot(d).collect(p,animated,adapter.entity.getX(),adapter.entity.getY(),adapter.entity.getZ(),
                    bounds->adapter.entity.level().getBlockCollisions(adapter.entity,bounds));
            for(var joint:d.joints) {
                var anchor=actual[joint.bone()].transformPosition(d.pivot(joint.bone()));
                float radius=joint.parameters().c()/16f*animated[joint.bone()].getScale(new org.joml.Vector3f()).x;
                for(int end:joint.outputs()) {
                    var tip=actual[end].transformPosition(d.pivot(end));
                    float length=animated[end].transformPosition(d.pivot(end)).distance(animated[joint.bone()].transformPosition(d.pivot(joint.bone())));
                    if(Math.abs(tip.distance(anchor)-length)>.001f) throw new AssertionError("animated GPU bone length error");
                    if(frame>5) for(var contact:contacts) if(!contact.worldBlock || joint.parameters().bc())
                        if(contact.resolveSegment(new org.joml.Vector3f(anchor),new org.joml.Vector3f(tip),new org.joml.Vector3f(),new org.joml.Vector3f(),Math.max(0,radius-.002f),true))
                            throw new AssertionError("animated GPU visible penetration > .002 blocks: "+d.bones.get(joint.bone()).getName());
                }
            }
        } finally {org.lwjgl.system.MemoryUtil.memFree(data);}
    }
}
