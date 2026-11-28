package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.resource.*;
import com.elfmcys.yesstevemodel.resource.models.GeometryDescription;
import net.everla.ysmJsLib.*;
import org.joml.Matrix4f;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PhysicsChecks {
    public static void main(String[] args) throws Exception {
        repeatedPasses();
        if(args.length==0)return;
        try(var reader=new YSMFolderDeserializer(Path.of(args[0]))) {
            var raw=reader.deserialize();ScriptResources.prepare(raw);
            ScriptModel.BUILDING.set(ScriptResources.compile(raw).forModel("player.main"));
            var method=java.util.Arrays.stream(YSMClientMapper.class.getDeclaredMethods())
                    .filter(m->m.getName().equals("buildScopedMesh")).findFirst().orElseThrow();
            method.setAccessible(true);
            GeoModel model;
            try {model=(GeoModel)method.invoke(null,raw.mainEntity.mainModel,
                    new GeometryDescription("physics-check",640,640,4,4,new double[3]),1,null,false);}
            finally {ScriptModel.BUILDING.remove();}
            for(int hz:new int[]{30,60,144}) {
                var sim=new PhysSimulation(model.ysmPhysics);sim.setFrameRate(60);
                float[] p=model.boneTransformData.clone(),previous=p.clone();
                float max=0;int worst=-1;
                for(int frame=0;frame<hz*12;frame++) {
                    sim.restore(p);
                    sim.update(p,new Matrix4f(),frame/(double)hz,false,model,0,0,0);
                    if(frame>hz*8)for(var joint:model.ysmPhysics.joints) {
                        int i=joint.bone()*12;
                        var a=new org.joml.Quaternionf().rotationZYX(p[i+2],p[i+1],p[i]);
                        var b=new org.joml.Quaternionf().rotationZYX(previous[i+2],previous[i+1],previous[i]);
                        float angle=2*(float)Math.acos(Math.min(1,Math.abs(a.dot(b))));
                        if(angle>max){max=angle;worst=joint.bone();}
                    }
                    System.arraycopy(p,0,previous,0,p.length);
                }
                System.out.println("Stationary "+hz+"Hz max delta="+Math.toDegrees(max)+" bone="+(worst<0?"none":model.bones.get(worst).getName()));
            }
        }
    }

    private static void repeatedPasses() throws Exception {
        var joint=new com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone("joint",false,false,false,0,0,0,0,0,0);
        var end=new com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone("tip",false,false,false,8,-16,0,0,0,0);
        end.parentIdx=0;
        String[][] names=new String[35][];Arrays.setAll(names,i->new String[0]);
        var model=new GeoModel(new com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone[]{joint,end},names,new boolean[3],
                new GeometryDescription("physics-check",16,16,4,4,new double[3]),new boolean[1]);
        model.scripts=new ScriptModel(new ScriptBundle(Map.of("rysm/manifest.json","{\"version\":1,\"scripts\":[\"main.js\"]}".getBytes(StandardCharsets.UTF_8),
                "rysm/main.js","Rysm.model('group_modifier',e=>{e.modify('joint').physics({});e.modify('tip').end();});".getBytes(StandardCharsets.UTF_8))));
        var definition=new PhysDefinition(model,message->{throw new AssertionError(message);});
        if(definition.joints.size()!=1)throw new AssertionError("Physics fixture has no joint");
        for(int hz:new int[]{30,60,144}) {
            var single=new PhysSimulation(definition);var multi=new PhysSimulation(definition);
            single.setFrameRate(60);multi.setFrameRate(60);
            float[] a=model.boneTransformData.clone(),b=a.clone();
            for(int frame=0;frame<hz*2;frame++) {
                double time=frame/(double)hz;
                var root=new Matrix4f().rotateY((float)(.2*Math.sin(time)));
                single.restore(a);multi.restore(b);
                single.update(a,root,time,false,model,time,0,0);
                multi.update(b,root,time,false,model,time,0,0);
                if(!Arrays.equals(a,b))throw new AssertionError("Extra pass altered next frame at "+hz+"Hz");
                var position=multi.position(1);
                // Another draw can use a different root/animation/visibility. It
                // must retain its animation and flags without mutating particles.
                multi.restore(b);b[0]=.3f;b[9]=1;
                multi.update(b,new Matrix4f().translation(8,0,0),time,false,model,time,0,0);
                if(!position.equals(multi.position(1)) || multi.stepsInUpdate()!=0 || multi.sampledFrame() || b[9]!=1)
                    throw new AssertionError("Extra pass advanced/reset simulation");
                multi.restore(b);
                if(b[0]!=.3f)throw new AssertionError("Extra-pass animation was not restored");
                b[0]=0;b[9]=0;
            }
        }
        System.out.println("Physics checks passed: repeated world passes preserve particles and subsequent frames at 30/60/144 Hz");
    }
}
