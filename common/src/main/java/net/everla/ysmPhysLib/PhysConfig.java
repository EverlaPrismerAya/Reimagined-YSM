package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.config.ClientConfigScope;
import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings, isolated between normal and client-only profiles. */
public final class PhysConfig {
    public static ForgeConfigSpec.BooleanValue ENABLED;
    public static ForgeConfigSpec.IntValue FPS;
    public static ForgeConfigSpec.BooleanValue EXPERIMENTAL_GPU;
    public static final class Local {
        public static ForgeConfigSpec.BooleanValue ENABLED;
        public static ForgeConfigSpec.IntValue FPS;
        public static ForgeConfigSpec.BooleanValue EXPERIMENTAL_GPU;
    }
    private PhysConfig() {}

    public static void define(ForgeConfigSpec.Builder builder, boolean local) {
        builder.push("physics_groups");
        var enabled=builder.comment("Enable ysmPhys simulation for world entities using YSM models. UI previews are excluded.").define("Enabled",true);
        var fps=builder.comment("Physics steps per second (1-120). Recommended: 60. Lower rates lose subtle player movement input and may cause counterintuitive movement and visible jumping.")
                .defineInRange("SimulationFPS",60,1,120);
        var gpu=builder.comment("OpenGL 4.3 compute/SSBO physics: GPU integration, rigid constraints, model contacts and terrain contacts. CPU extracts VoxelShape snapshots. Measured CPU/GPU costs select the backend after calibration. Supports Oculus/Iris; unsupported transforms and CPU pose consumers use CPU. Benchmark the actual workload.")
                .define("ExperimentalGPUAcceleration",true);
        if(local) {Local.ENABLED=enabled;Local.FPS=fps;Local.EXPERIMENTAL_GPU=gpu;}
        else {ENABLED=enabled;FPS=fps;EXPERIMENTAL_GPU=gpu;}
        builder.pop();
    }
    public static int frameRate() {
        return ClientConfigScope.effective(ENABLED).get()?ClientConfigScope.effective(FPS).get():0;
    }
    public static boolean gpuEnabled() {return ClientConfigScope.effective(EXPERIMENTAL_GPU).get();}
}
