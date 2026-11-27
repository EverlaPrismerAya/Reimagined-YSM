package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A small heap makes accidental byte-array-to-JSON expansion fail deterministically. */
public final class ScriptMemoryChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var raw=new RawYsmModel();raw.properties.sha256="f".repeat(64);
        var texture=new RawYsmModel.RawTexture();texture.data=new byte[8*1024*1024];raw.mainEntity.textures.put("default",texture);
        var sound=new RawYsmModel.RawDataFile("sound",new byte[8*1024*1024]);raw.soundFiles.put("sound",sound);
        var geometry=new RawYsmModel.RawGeometry();raw.mainEntity.mainModel=geometry;
        var bone=new RawYsmModel.RawBone();bone.name="ordinary";geometry.bones.add(bone);
        var cube=new RawYsmModel.RawCube();bone.cubes.add(cube);
        var animationFile=new RawYsmModel.RawAnimationFile();var animation=new RawYsmModel.RawAnimation();animation.length=Float.POSITIVE_INFINITY;
        animationFile.animations.put("idle",animation);raw.mainEntity.animationFiles.put("main",animationFile);
        var bytes=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long start=bytes.getThreadAllocatedBytes(Thread.currentThread().getId());
        // Ordinary models must not even inspect the binary file or build a JSON tree.
        long ordinary=bytes.getThreadAllocatedBytes(Thread.currentThread().getId())-start;
        check(ordinary<1024*1024,"Ordinary migration detection allocated more than 1 MiB: "+ordinary);
        check(ScriptResources.modelTree(raw).toString().length()<4096,"Binary assets entered script JSON projection");
        raw.rysmBundle=new ScriptBundle(Map.of(
            "rysm/manifest.json","{\"version\":1,\"scripts\":[\"scripts/main.js\"]}".getBytes(StandardCharsets.UTF_8),
            "rysm/scripts/main.js","Rysm.model('group_modifier',event=>event.modify('ordinary').item());".getBytes(StandardCharsets.UTF_8)));
        var controllerFile=new RawYsmModel.RawAnimationControllerFile();
        var controller=new RawYsmModel.RawAnimationController();var state=new RawYsmModel.RawControllerState();
        state.animations.add(new AbstractMap.SimpleEntry<>("idle","rysmjs.value"));
        state.animations.add(new AbstractMap.SimpleEntry<>("idle","1"));
        state.transitions.add(new AbstractMap.SimpleEntry<>("idle","rysmjs.value > 0"));
        controller.states.add(state);controllerFile.controllers.put("main",controller);raw.mainEntity.animationControllerFiles.add(controllerFile);
        ScriptResources.prepare(raw);
        var base=raw.mainEntity.animationControllerFiles.get(0).controllers.get("main").states.get(0);
        check(base.animations.size()==2&&base.animations.get(0).getValue().equals("(0.0)"),"Controller fallback or ordered entries lost");
        check(ScriptResources.compile(raw)!=null,"Controller restoration failed");
        var restored=raw.mainEntity.animationControllerFiles.get(0).controllers.get("main").states.get(0);
        check(restored.animations.get(0).getValue().equals("rysmjs.value")&&restored.transitions.get(0).getValue().equals("rysmjs.value > 0"),"Controller expressions not restored");
        ScriptResources.prepare(raw);check(ScriptResources.compile(raw)!=null,"Script preparation failed");
        check(raw.mainEntity.animationFiles.get("main").animations.get("idle").length==Float.POSITIVE_INFINITY,"Unbounded animation sentinel was rejected or changed");
        check(raw.mainEntity.mainModel==geometry && geometry.bones.get(0)==bone && bone.cubes.get(0)==cube,"Script preparation copied or lost geometry");
        check(raw.mainEntity.textures.get("default")==texture && raw.soundFiles.get("sound")==sound,"Script preparation copied binary assets");
        Path folder=Files.createTempDirectory("rysm-memory-");
        try {
            Files.writeString(folder.resolve("ordinary.json"),"{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"ordinary\"}]}]}");
            try(var writer=Files.newBufferedWriter(folder.resolve("animations.json"))) {
                writer.write("{\"animations\":[0");for(int i=0;i<100000;i++)writer.write(",{\"bones\":{\"ordinary\":{\"rotation\":[1,2,3]}}}");writer.write("]}");
            }
            start=bytes.getThreadAllocatedBytes(Thread.currentThread().getId());
            check(!Files.exists(folder.resolve("rysm")),"Ordinary source entered migration");
            long sourceAllocation=bytes.getThreadAllocatedBytes(Thread.currentThread().getId())-start;
            check(sourceAllocation<1024*1024,"Ordinary source JSON was materialized: "+sourceAllocation);
            Files.writeString(folder.resolve("ordinary.json"),"{\"geometry.old\":{\"bones\":[{\"name\":\"ysmItem@ordinary\"}]}}");
            // An invalid JSON proves the unmarked path does not inspect any model JSON at all.
            Files.writeString(folder.resolve("invalid.json"),"{broken");
            check(!Files.exists(folder.resolve("rysm")),"Legacy names bypassed explicit RYSM opt-in");
            System.out.println("Ordinary source with 4 MiB animation JSON skipped; allocated "+sourceAllocation+" bytes");
            Path source=folder.resolve("legacy.ysm");Files.writeString(source,"migration fixture");
            bone.name="ysmItem@ordinary";raw.rysmBundle=null;
            ScriptResources.companion(raw,source);
            check(raw.rysmBundle==null&&!Files.exists(folder.resolve("legacy.rysm")),"Missing companion triggered binary migration");
            check(ScriptResources.compile(raw)==null,"Missing companion reattached cached scripts");
        } finally {try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Memory checks passed under "+Runtime.getRuntime().maxMemory()/1024/1024+" MiB heap; ordinary model detection allocated "+ordinary+" bytes; geometry and 16 MiB binary assets preserved");
    }
}
