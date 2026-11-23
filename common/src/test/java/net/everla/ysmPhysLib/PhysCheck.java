package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.resource.models.GeometryDescription;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Run with :common:checkYsmPhysics. No Minecraft window or native library required. */
public final class PhysCheck {
    private static final Object WORLD=new Object();
    public static void main(String[] args) throws Exception {
        parser(); conditionalGroups(); movingContacts(); hybridPartition(); airDrag(); topology(); rotations(); collision(); simulation(); cadence(); bodyCollision(); blockCollision(); context(); example(); actualModel(); benchmark();
        System.out.println("PASS: ysmPhys parser, topology, rigid branches, convex contacts, fixed steps, lifecycle, UI gate and isolation");
    }

    private static void conditionalGroups() {
        var wing=PhysParameters.parse("ysmPhys#enable=(v.roaming.wings_phys>=1,variable.roaming.wings_phys_enable=1),g=0@leftwing",PhysParameters.DEFAULT);
        assert wing.enabled(n->false,n->1);
        var processor=new com.elfmcys.yesstevemodel.geckolib3.core.processor.AnimationProcessor<>(null);
        var roaming=new com.elfmcys.yesstevemodel.molang.runtime.HashMapStruct(true);
        int phys=com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool.computeIfAbsent("wings_phys");
        int enable=com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool.computeIfAbsent("wings_phys_enable");
        roaming.putProperty(phys,2f);roaming.putProperty(enable,1f);processor.setRoamingProperties(roaming);
        assert wing.enabled(n->false,processor::physicsVariable);
        roaming.putProperty(enable,0f);
        assert !wing.enabled(n->false,processor::physicsVariable) : "runtime roaming changes were not read";
        assert processor.physicsVariable("v.roaming.missing")==0 && processor.physicsVariable("variable.missing")==0;
        assert !wing.enabled(n->false,n->n.endsWith("wings_phys")?2:0) : "whitelist must require both variables";
        assert !wing.enabled(n->false,n->n.endsWith("wings_phys")?.5:1);
        for(String op:List.of("=",">=","<=",">","<","!=")) {
            var p=PhysParameters.parse("ysmPhys#enable=(v.x"+op+"-1.5e0)@test",PhysParameters.DEFAULT);
            assert p.enabled(n->false,n->-1.5)==List.of("=",">=","<=").contains(op) : op;
            assert p.enabled(n->false,n->-2)==List.of("<=","<","!=").contains(op) : op;
            assert p.enabled(n->false,n->0)==List.of(">=",">","!=").contains(op) : op;
        }
        var robe=PhysParameters.parse("ysmPhys#disable=(jump,fly),enable=(v.on=1)@robe",PhysParameters.DEFAULT);
        assert !robe.enabled("jump"::equals,n->1) && !robe.enabled("fly"::equals,n->1);
        assert robe.enabled("idle"::equals,n->1) && !robe.enabled("idle"::equals,n->0);
        var blacklist=PhysParameters.parse("ysmPhys#disable=(v.x>=1,v.y!=0)@test",PhysParameters.DEFAULT);
        assert !blacklist.enabled(n->false,n->1) && blacklist.enabled(n->false,n->n.equals("v.x")?1:0);
        assert PhysParameters.parse("ysmPhys@child",wing).equals(wing);
        var override=PhysParameters.parse("ysmPhys#enable=(jump)@child",wing);
        assert override.enabled("jump"::equals,n->0) && !override.enabled(n->false,n->1);
        for(String entry:List.of("enable=()","disable=(jump,)","enable=(v.x==1)","enable=(v.x=NaN)",
                "enable=(v.x>1e99)","enable=(v.x=1,jump)","enable=(jump","disable=((jump))","enable=(v.x!1)")) {
            try { PhysParameters.parse("ysmPhys#"+entry+"@test",PhysParameters.DEFAULT); throw new AssertionError(entry); }
            catch(IllegalArgumentException expected) {}
        }
        var geometry=model(bone("ysmPhys#disable=(jump)@robe",-1,0,0,0),bone("ysmPhys@tip",0,8,0,0),
                bone("ysmPhysEnd@end",1,16,0,0),bone("ysmPhys@independent",-1,32,0,0),bone("ysmPhysEnd@other",3,48,0,0));
        var d=definition(geometry);var sim=new PhysSimulation(d);var p=parameters(geometry);
        var on=PlayerPhysics.enabled(d,n->false,n->0);var off=PlayerPhysics.enabled(d,"jump"::equals,n->0);
        assert !off[0]&&!off[1]&&off[3] : "unrelated chain must remain enabled";
        sim.setEnabled(p,on);
        for(int i=0;i<60;i++) sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
        assert Math.abs(p[2])>.01 : "fixture has no physics";
        sim.setEnabled(p,off);
        assert p[0]==0&&p[1]==0&&p[2]==0&&p[12]==0&&p[13]==0&&p[14]==0 : "disabled chain retained physics rotation";
        for(int i=60;i<120;i++) {sim.restore(p);sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);}
        assert p[2]==0&&Math.abs(p[3*12+2])>.01;
        sim.setEnabled(p,on);
        sim.update(p,new Matrix4f(),2,false,WORLD,0,0,0);
        assert p[2]==0 : "re-enable must start at animation pose";
        for(int i=121;i<180;i++) {sim.restore(p);sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);}
        assert Math.abs(p[2])>.01 : "re-enabled chain did not resume";
        // Disabling the last chain must also restore an already-applied correction.
        sim.setEnabled(p,new boolean[d.bones.size()]);
        sim.update(p,new Matrix4f(),3,false,WORLD,0,0,0);
        assert p[2]==0&&p[3*12+2]==0;
        System.out.println("PASS: conditional physics comparisons, animation lists, inheritance, blacklist priority, restore/resume and independent chains");
    }

    private static void movingContacts() {
        var collider=new PhysCollider(cube(),new Matrix4f());
        // The former world-velocity call spuriously stopped a comoving falling tip.
        var oldTipVelocity=new Vector3f(4,-8,0);
        collider.resolveSegment(new Vector3f(0,1,0),new Vector3f(.2f,.45f,0),new Vector3f(oldTipVelocity),oldTipVelocity,.0625f,true);
        assert oldTipVelocity.y>-7 : "fixture no longer reproduces the old artificial rebound";
        for(float speed:new float[]{-8,-2,2,8}) for(boolean fixed:new boolean[]{false,true}) {
            Vector3f frame=new Vector3f(4,speed,0),va=new Vector3f(frame),vb=new Vector3f(frame);
            PhysSimulation.resolveContact(collider,new Vector3f(0,1,0),new Vector3f(.2f,.45f,0),va,vb,.0625f,fixed,frame);
            assert va.distance(frame)<1e-5 && vb.distance(frame)<1e-5 : "comoving body contact injected rebound: "+vb;
        }
        var floor=new PhysCollider(new net.minecraft.world.phys.AABB(-2,-1,-2,2,0,2));
        var va=new Vector3f(0,-4,0);var vb=new Vector3f(0,-4,0);
        PhysSimulation.resolveContact(floor,new Vector3f(0,1,0),new Vector3f(.2f,.01f,0),va,vb,.0625f,true,new Vector3f(0,-4,0));
        assert vb.y>=-1e-5 : "world contact incorrectly used moving body frame";
        System.out.println("PASS: moving body contact preserves comoving velocity; stationary terrain removes inward velocity");
    }

    private static void hybridPartition() {
        var m=model(bone("ysmPhys@root",-1,0,0,0),bone("ysmPhys#bc=true@child",0,0,-8,0),
                bone("ysmPhysEnd@end",1,0,-16,0),bone("ordinary",0,0,0,0),
                bone("ysmPhys#bc=false@nested",3,8,0,0),bone("ysmPhysEnd@nested",4,16,0,0),
                bone("ysmPhys@independent",-1,32,0,0),bone("ysmPhysEnd@independent",6,48,0,0));
        var d=definition(m);boolean[] cpu=d.blockCollisionIslands();
        assert cpu[0] && cpu[1] && cpu[3] && cpu[4] && !cpu[6] && !cpu[7];
        assert GpuPhysics.supportsDefinition(d);
        var sim=new PhysSimulation(d);var pose=parameters(m);sim.partition(pose,true);
        for(int i=0;i<60;i++) sim.update(pose,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
        assert pose[6*12]==0 && pose[6*12+1]==0 && pose[6*12+2]==0 : "CPU changed GPU joint";
        sim.partition(pose,false);
        for(int i=60;i<120;i++) sim.update(pose,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
        assert Math.abs(pose[6*12+2])>.01 : "fallback left CPU partition enabled";
    }

    private static void parser() {
        assert PhysParameters.parse("ysmPhys@hair",PhysParameters.DEFAULT).equals(PhysParameters.DEFAULT);
        var p=PhysParameters.parse("ysmPhys#r=2, g=-3, s=4, a=45, c=1@hair",PhysParameters.DEFAULT);
        var inherited=PhysParameters.parse("ysmPhys#g=0@tip",p);
        assert inherited.r()==2 && inherited.g()==0 && inherited.a()==45;
        assert !PhysParameters.DEFAULT.bc();
        var block=PhysParameters.parse("ysmPhys#bc=true@root",PhysParameters.DEFAULT);
        assert block.bc() && PhysParameters.parse("ysmPhys@child",block).bc();
        assert !PhysParameters.parse("ysmPhys#bc=false@child",block).bc();
        assert PhysParameters.parse("ysmPhys#bc = true, c=2@root",PhysParameters.DEFAULT).c()==2;
        for(String invalid:List.of("ysmPhys", "ysmPhys@", "ysmPhys#@hair", "ysmPhys#x=1@hair", "ysmPhys#r=1,r=2@hair",
                "ysmPhys#r=NaN@hair", "ysmPhys#g=Infinity@hair", "ysmPhys#c=-1@hair", "ysmPhys#air=-1@hair", "ysmPhys#air=NaN@hair", "ysmPhys#a=181@hair",
                "ysmPhys#r=1,@hair", "ysmPhys@hair@bad", "ysmPhys#bc=1@hair", "ysmPhys#bc=0@hair",
                "ysmPhys#bc=yes@hair", "ysmPhys#bc=@hair", "ysmPhys#bc=true,bc=false@hair")) {
            try {PhysParameters.parse(invalid,p);throw new AssertionError("accepted "+invalid);}
            catch(IllegalArgumentException expected) {}
        }
    }

    private static void airDrag() {
        Vector3f velocity=new Vector3f(10,0,0);
        PhysSimulation.applyAirDrag(velocity,1,.1f);
        assert Math.abs(velocity.x-5)<1e-6;
        Vector3f split=new Vector3f(10,0,0);
        for(int i=0;i<10;i++) PhysSimulation.applyAirDrag(split,1,.01f);
        assert split.distance(velocity)<1e-5 : "air drag depends on timestep";
        PhysSimulation.applyAirDrag(velocity,0,1);assert velocity.x==5;
        var inherited=PhysParameters.parse("ysmPhys@tip",PhysParameters.parse("ysmPhys#air=2@root",PhysParameters.DEFAULT));
        assert inherited.air()==2 && !PhysParameters.isCandidate("ysmPhysC@body") && PhysParameters.isCollider("ysmPhysC@body");
        PhysSimulation.applyAirDrag(velocity,Float.MAX_VALUE,1);assert velocity.isFinite();
        assert run(60,12,"g=9.8,s=0,r=0,air=10,a=180").y>run(60,12,"g=9.8,s=0,r=0,air=0,a=180").y : "air has no simulation effect";
    }

    private static void blockCollision() {
        for(boolean enabled:new boolean[]{false,true}) for(double origin:new double[]{0,10000000.125,-10000000.125}) {
            var m=model(bone("ysmPhys#bc="+enabled+",s=0,r=3,air=0,a=180@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
            var d=definition(m);var sim=new PhysSimulation(d);sim.setFrameRate(60);var p=parameters(m);
            assert GpuPhysics.supportsDefinition(d) : "terrain topology must be supported";
            var root=new Matrix4f().translate(0,.25f,0);
            var floor=new net.minecraft.world.phys.AABB(-2,-1,-2,2,0,2);
            var shape=net.minecraft.world.phys.shapes.Shapes.create(floor.move(origin,64,origin));
            int[] queries={0};boolean[] removed={false};
            java.util.function.Function<net.minecraft.world.phys.AABB,Iterable<net.minecraft.world.phys.shapes.VoxelShape>> source=bounds->{
                queries[0]++;
                assert Math.abs(bounds.getCenter().x-origin)<4 && Math.abs(bounds.getCenter().z-origin)<4 : "block query not in world coordinates";
                return removed[0]?List.of():List.of(shape);
            };
            for(int frame=0;frame<=180;frame++) sim.update(p,root,frame/60.0,false,WORLD,origin,64,origin,source);
            Vector3f tip=sim.position(1);
            if(enabled) {
                assert queries[0]>0;
                assert tip.y>=1f/16-.002 : "world floor penetrated: "+tip;
                assert Math.abs(tip.distance(sim.position(0))-1)<1e-5 : "block contact stretched bone";
            } else {assert queries[0]==0 : "bc=false queried world";assert tip.y<-.5 : "bc=false hit floor";}
            int before=queries[0];float[] drawn=p.clone();
            sim.update(p,root,3,false,WORLD,origin,64,origin,source);
            assert Arrays.equals(drawn,p) && queries[0]==before : "repeated draw queried/advanced world contacts";
            sim.update(p,root,3.1,true,WORLD,origin,64,origin,source);
            assert queries[0]==before : "paused physics queried blocks";
            removed[0]=true;
            for(int frame=187;frame<=300;frame++) sim.update(p,root,frame/60.0,false,WORLD,origin,64,origin,source);
            assert sim.position(1).y<-.5 : "removed world block retained cached collision";
        }
        // VoxelShape components stay separate: a lower slab must not become a full block.
        var lower=new PhysCollider(new net.minecraft.world.phys.AABB(0,0,0,1,.5,1));
        Vector3f point=new Vector3f(.5f,.75f,.5f);
        assert !lower.resolve(point,new Vector3f(),.05f) : "slab expanded to full block";
        point.set(.5f,.49f,.5f);assert lower.resolve(point,new Vector3f(),.05f) && point.y>.549f;
        // Whole-segment contact, with endpoints outside the block's X extent.
        var wall=new PhysCollider(new net.minecraft.world.phys.AABB(-.1,-.5,-.5,.1,.5,.5));
        Vector3f a=new Vector3f(-1,0,0), b=new Vector3f(1,0,0);
        assert wall.resolveSegment(a,b,new Vector3f(),new Vector3f(),.0625f,true) : "world block missed segment midpoint";
        var mixed=model(bone("ysmPhys#bc=true,s=0,r=3,air=0,a=180@enabled",-1,0,0,0),bone("ysmPhysEnd@enabled",0,16,0,0),
                bone("ysmPhys#bc=false,s=0,r=3,air=0,a=180@disabled",-1,32,0,0),bone("ysmPhysEnd@disabled",2,48,0,0));
        var mixedSimulation=new PhysSimulation(definition(mixed));mixedSimulation.setFrameRate(60);var mixedPose=parameters(mixed);
        var floorShape=net.minecraft.world.phys.shapes.Shapes.create(new net.minecraft.world.phys.AABB(-2,-1,-2,5,0,2));
        for(int frame=0;frame<=180;frame++) mixedSimulation.update(mixedPose,new Matrix4f().translate(0,.25f,0),frame/60.0,false,WORLD,0,0,0,bounds->List.of(floorShape));
        assert mixedSimulation.position(1).y>=1f/16-.002 && mixedSimulation.position(3).y<-.5 : "bc leaked into a disabled branch";
        System.out.println("PASS: bc inheritance/default/validation, world floor, slab, whole segment, far-origin rebasing, removal, pause and GPU fallback");
    }

    static GeoBone bone(String name,int parent,float x,float y,float z) {
        var b=new GeoBone(name,false,false,false,x,y,z,0,0,0);b.parentIdx=parent;return b;
    }
    static GeoModel model(GeoBone... bones) {
        String[][] names=new String[35][];Arrays.setAll(names,i->new String[0]);
        var model=new GeoModel(bones,names,new boolean[3],new GeometryDescription("physics_check",16,16,4,4,new double[3]),new boolean[1]);
        model.bakedBones=new ArrayList<>();
        for(var b:bones) {var baked=new GeoModel.BakedBone();baked.name=b.getName();baked.parentIdx=b.parentIdx;model.bakedBones.add(baked);}
        return model;
    }
    static PhysDefinition definition(GeoModel m) {
        return new PhysDefinition(m,message->{throw new AssertionError(message);});
    }
    static float[] parameters(GeoModel model) {return model.boneTransformData.clone();}

    private static void topology() {
        var m=model(bone("ysmPhys#r=3@root",-1,0,0,0),bone("ysmPhys@child",0,0,-8,0),bone("ysmPhysEnd@end",1,0,-16,0));
        var d=definition(m);
        assert d.joints.size()==2 && d.joints.get(1).parameters().r()==3;
        assert d.joints.get(1).parent()==0 && d.ends.length==1;
        m=model(bone("ysmPhys@root",-1,0,0,0),bone("ordinary",0,0,-8,0),bone("ysmPhys@independent",1,0,-12,0),bone("ysmPhysEnd@end",2,0,-16,0));
        List<String> warnings=new ArrayList<>();d=new PhysDefinition(m,warnings::add);
        assert d.joints.size()==1 && d.joints.get(0).parent()==-1 && warnings.size()==1;
        m=model(bone("ysmPhys@root",-1,0,0,0),bone("ysmPhysEnd@a",0,0,-8,0),bone("ysmPhysEnd@b",0,0,-16,0));
        warnings.clear();assert new PhysDefinition(m,warnings::add).joints.isEmpty() && warnings.size()==1;
        m=model(bone("ysmPhys@root",-1,0,0,0),bone("ysmPhysEnd@zero",0,0,0,0));
        warnings.clear();assert new PhysDefinition(m,warnings::add).joints.isEmpty();
        m=model(bone("ysmPhysEnd@end",1,0,-16,0),bone("ysmPhys@root",-1,0,0,0));
        d=definition(m);assert d.order[0]==1 && d.order[1]==0;
        var sim=new PhysSimulation(d);var p=parameters(m);
        sim.update(p,new Matrix4f(),0,false,WORLD,0,0,0);
        assert sim.position(0).y==-1;
        m=model(bone("ysmPhys@root",-1,0,0,0),bone("ysmPhysEnd@notEmpty",0,0,-16,0));
        m.bakedBones.get(1).physicsFaces.add(cube());warnings.clear();
        assert new PhysDefinition(m,warnings::add).joints.isEmpty();
        m=model(bone("ysmPhys@root",-1,0,0,0),bone("ysmPhys@zero",0,0,0,0),bone("ysmPhysEnd@end",1,0,-8,0));
        warnings.clear();assert new PhysDefinition(m,warnings::add).joints.isEmpty();
    }

    private static void rotations() {
        var from=List.of(new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,2));
        var expected=new Quaternionf().rotationXYZ(.4f,-.7f,1.2f);
        var to=from.stream().map(v->new Vector3f(v).rotate(expected)).toList();
        var fit=PhysMath.fit(from,to,new Quaternionf());
        for(int i=0;i<from.size();i++) assert new Vector3f(from.get(i)).rotate(fit).distance(to.get(i))<1e-5 : "least squares rotation";
        var limited=PhysMath.limit(new Quaternionf().rotationX(2),30);
        assert Math.abs(limited.angle()-(float)Math.PI/6)<1e-5;
        var q=new Quaternionf().rotationZYX(.7f,.4f,-.2f);
        var angles=PhysMath.eulerZYX(q);
        assert Math.abs(q.dot(new Quaternionf().rotationZYX(angles.z,angles.y,angles.x)))>.99999;
        for(float y:new float[]{-(float)Math.PI/2,(float)Math.PI/2}) {
            q.rotationZYX(.7f,y,-.2f);angles=PhysMath.eulerZYX(q);
            assert Math.abs(q.dot(new Quaternionf().rotationZYX(angles.z,angles.y,angles.x)))>.99999 : "gimbal lock";
        }
    }

    /** Cube [-.5,.5], with deliberately mixed face winding. */
    static float[] cube() {
        return new float[]{-.5f,-.5f,-.5f, -.5f,.5f,-.5f, .5f,.5f,-.5f, .5f,-.5f,-.5f,
                -.5f,-.5f,.5f, -.5f,.5f,.5f, .5f,.5f,.5f, .5f,-.5f,.5f,
                -.5f,-.5f,-.5f, -.5f,-.5f,.5f, -.5f,.5f,.5f, -.5f,.5f,-.5f,
                .5f,-.5f,-.5f, .5f,-.5f,.5f, .5f,.5f,.5f, .5f,.5f,-.5f,
                -.5f,.5f,-.5f, -.5f,.5f,.5f, .5f,.5f,.5f, .5f,.5f,-.5f,
                -.5f,-.5f,-.5f, -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,-.5f,-.5f};
    }
    private static void collision() {
        var transform=new Matrix4f().translate(2,3,4).rotateY(.6f).scale(2,1,.5f);
        var collider=new PhysCollider(cube(),transform);
        Vector3f p=transform.transformPosition(new Vector3f(.54f,0,0));
        Vector3f normal=transform.transformDirection(new Vector3f(1,0,0)).normalize();
        Vector3f velocity=new Vector3f(normal).negate();
        assert collider.resolve(p,velocity,.1f);
        assert velocity.length()<1e-5 && p.distance(transform.transformPosition(new Vector3f(.5f,0,0)))>.099f;
        p.set(2,3,4);assert collider.resolve(p,velocity,.1f) && p.isFinite();
        assert !collider.resolve(new Vector3f(20,20,20),velocity,.1f);
        // AABB overlap at a corner is not sufficient: require actual triangle distance.
        collider=new PhysCollider(cube(),new Matrix4f());
        p.set(.59f,.59f,.59f);assert !collider.resolve(p,velocity,.1f);
        p.set(.54f,.54f,.54f);assert collider.resolve(p,velocity,.1f);
        Vector3f start=new Vector3f(-2,0,0),end=new Vector3f(2,0,0),v0=new Vector3f(),v1=new Vector3f();
        assert collider.resolveSegment(start,end,v0,v1,.1f,false) : "missed middle with both endpoints outside";
        assert Math.abs(start.y)>=.59f || Math.abs(start.z)>=.59f : "crossing segment not expelled";
        start.set(-2,0,0);end.set(2,0,0);
        assert collider.resolveSegment(start,end,v0,v1,.1f,true);
        assert end.x<-.59f && start.x==-2 : "fixed segment ejected through far wall";
        start.set(-2,.65f,0);end.set(2,.65f,0);
        assert !collider.resolveSegment(start,end,v0,v1,.1f,true);
        assert collider.resolveSegment(start,end,v0,v1,.2f,true) : "radius ignored";
        assert start.equals(new Vector3f(-2,.65f,0)) : "fixed root moved";
        start.set(-2,2,0);end.set(2,2,0);
        assert !collider.resolveSegment(start,end,v0,v1,.1f,false);
        // An almost tangential contact at an overlapping fixed cap has a tiny
        // lever arm. Resolving its cap overlap must not launch the whole segment.
        start.set(.55f,.5f,0);end.set(.54f,1.5f,0);
        Vector3f before=new Vector3f(end);
        float length=start.distance(end);
        collider.resolveSegment(start,end,new Vector3f(),new Vector3f(-1,0,0),1/16f,true);
        assert end.distance(before)<.02f : "near-anchor contact caused an angular snap";
        assert Math.abs(start.distance(end)-length)<1e-5f : "contact changed rigid length";
        before.set(end);
        collider.resolveSegment(start,end,new Vector3f(),new Vector3f(),1/16f,true);
        assert end.distance(before)<1e-5f : "stationary tangent contact did not settle";
        var inherited=PhysParameters.parse("ysmPhys@tip",PhysParameters.parse("ysmPhys#c=2@root",PhysParameters.DEFAULT));
        assert inherited.c()==2 && PhysParameters.DEFAULT.c()==1;
        try {PhysParameters.parse("ysmPhys#c=-1@root",PhysParameters.DEFAULT);throw new AssertionError("negative c");}
        catch(IllegalArgumentException expected) {}
    }

    private static GeoModel single(String options) {
        return model(bone("ysmPhys#"+options+"@root",-1,0,0,0),bone("ysmPhysEnd@end",0,16,0,0));
    }
    private static Vector3f run(int fps, int frames, String options) {
        var m=single(options);var p=parameters(m);var sim=new PhysSimulation(definition(m));
        for(int i=0;i<=frames;i++) sim.update(p,new Matrix4f(),(double)i/fps,false,WORLD,0,0,0);
        assert Math.abs(sim.position(0).distance(sim.position(1))-1)<2e-4 : "length drift";
        return sim.position(1);
    }
    private static void simulation() {
        var down=run(60,60,"g=9.8,s=0,a=180");assert down.y<-.2f;
        var up=run(60,60,"g=-9.8,s=0,a=180");assert up.y>.2f;
        assert run(30,30,"g=9.8,s=0,a=180").distance(run(120,120,"g=9.8,s=0,a=180"))<1e-4 : "frame dependence";
        var limited=run(60,60,"g=9.8,s=0,a=10");assert limited.angle(new Vector3f(1,0,0))<=Math.toRadians(10)+1e-4;
        assert run(60,60,"g=9.8,s=0,r=100,a=180").y>down.y : "damping has no effect";
        var m=single("g=9.8,s=0,a=180");var d=definition(m);var sim=new PhysSimulation(d);var p=parameters(m);
        var other=new PhysSimulation(d);var otherP=parameters(m);other.update(otherP,new Matrix4f(),0,false,WORLD,0,0,0);
        for(int i=0;i<=30;i++) sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
        assert other.position(1).equals(new Vector3f(1,0,0)) : "shared instance state";
        Vector3f before=sim.position(1);float[] rendered=p.clone();
        sim.update(p,new Matrix4f(),.5,false,WORLD,0,0,0);
        assert before.distance(sim.position(1))<1e-7 && Arrays.equals(rendered,p) : "duplicate rendering advanced physics";
        sim.restore(p);assert p[0]==0 && p[1]==0 && p[2]==0 : "animation feedback";
        sim.update(p,new Matrix4f(),.6,true,WORLD,0,0,0);assert before.distance(sim.position(1))<1e-7 : "paused physics moved";
        sim.update(p,new Matrix4f(),2,false,WORLD,0,0,0);assert sim.position(1).distance(new Vector3f(1,0,0))<1e-7 : "long gap";
        sim.update(p,new Matrix4f(),2.01,false,WORLD,8,0,0);assert sim.position(1).distance(new Vector3f(1,0,0))<1e-7 : "teleport";
        sim.update(p,new Matrix4f(),2.02,false,new Object(),8,0,0);assert sim.position(1).distance(new Vector3f(1,0,0))<1e-7 : "world change";
        sim.update(p,new Matrix4f(),1,false,WORLD,8,0,0);assert sim.position(1).distance(new Vector3f(1,0,0))<1e-7 : "time reversal";
        // Rigid fork keeps its original angle while all branches bend together.
        m=model(bone("ysmPhys#s=0,a=180@fork",-1,0,0,0),bone("ysmPhys@left",0,8,-8,0),bone("ysmPhysEnd@l",1,8,-16,0),
                bone("ysmPhys@right",0,-8,-8,0),bone("ysmPhysEnd@r",3,-8,-16,0));
        sim=new PhysSimulation(definition(m));p=parameters(m);
        for(int i=0;i<=60;i++) sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
        Vector3f a=sim.position(1).sub(sim.position(0)),b=sim.position(3).sub(sim.position(0));
        assert Math.abs(a.dot(b))<1e-4 && Math.abs(a.length()-(float)Math.sqrt(.5))<1e-4 : "fork rigidity";
        // Collision geometry comes from nonphysical bones, even with no rendered quads.
        m=single("g=9.8,s=0,a=180");m.bakedBones.get(0).physicsFaces.add(cube());
        assert definition(m).cubes.isEmpty() : "physical subtree became body collider";
        // Fast steady flight must remain bounded and approach a steady trailing pose.
        for(int fps:new int[]{30,60,120}) {
            var flying=single("g=0,s=20,r=2,air=0.5,a=90");
            var flight=new PhysSimulation(definition(flying));var fp=parameters(flying);
            Vector3f previous=null;
            float maxLateChange=0;
            for(int frame=0;frame<=fps*4;frame++) {
                double time=frame/(double)fps;
                flight.update(fp,new Matrix4f(),time,false,WORLD,0,0,time*60);
                Vector3f tip=flight.position(1);
                assert tip.isFinite() && tip.length()<1.01f : "high speed instability";
                if(frame>fps*3 && previous!=null) maxLateChange=Math.max(maxLateChange,tip.distance(previous));
                previous=tip;
            }
            assert maxLateChange<.05f : "steady flight twitch: "+fps+" FPS "+maxLateChange;
        }
        // Rendering interpolates the previous/current solved corrections without advancing state.
        m=single("g=9.8,s=0,a=180");d=definition(m);sim=new PhysSimulation(d);p=parameters(m);
        sim.setFrameRate(60);
        var root=new Matrix4f().rotateY(.4f).scale(1,1.5f,.8f);
        for(int i=0;i<30;i++) sim.update(p,root,i/60.0,false,WORLD,0,0,0);
        var previousTip=sim.position(1);
        sim.update(p,root,.5,false,WORLD,0,0,0);
        var actual=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(d.bones.get(0),new Matrix4f(root),p,0);
        var renderedTip=actual.transformPosition(d.pivot(1));
        assert renderedTip.distance(previousTip)<.002 : "alpha zero did not display previous solved pose";
        var currentTip=sim.position(1);
        sim.update(p,root,.5+1/120.0,false,WORLD,0,0,0);
        var midpoint=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(d.bones.get(0),new Matrix4f(root),p,0).transformPosition(d.pivot(1));
        assert sim.position(1).distance(currentTip)<1e-7 : "interpolation advanced solver";
        assert midpoint.distance(previousTip)>1e-5 && midpoint.distance(currentTip)>1e-5 : "no intermediate rendered pose";
        assert midpoint.distance(previousTip)<previousTip.distance(currentTip) && midpoint.distance(currentTip)<previousTip.distance(currentTip) : "interpolation overshot";
        // Translation creates inertia in world space without accumulating huge coordinates.
        m=single("g=0,s=0,a=180");sim=new PhysSimulation(definition(m));p=parameters(m);
        sim.update(p,new Matrix4f(),0,false,WORLD,10000000,0,0);
        sim.update(p,new Matrix4f(),1/60.0,false,WORLD,10000000,0,.1);
        assert sim.position(1).z<-.01 : "no movement inertia";
    }

    private static void cadence() {
        for(int fps:new int[]{1,15,30,60,120}) {
            var m=single("g=9.8,s=20,a=90");var p=parameters(m);
            var sim=new PhysSimulation(definition(m));sim.setFrameRate(fps);
            sim.update(p,new Matrix4f(),0,false,WORLD,0,0,0);
            assert !sim.sampledFrame() : "initial reset entered cost calibration";
            sim.update(p,new Matrix4f(),1.0/240,false,WORLD,0,0,0);
            assert sim.sampledFrame() && sim.stepsInUpdate()==0 : "display-only frame missing from cost calibration";
            sim.update(p,new Matrix4f(),1.0/240,false,WORLD,0,0,0);
            assert !sim.sampledFrame() : "repeated pass entered cost calibration";
            assert sim.position(1).distance(new Vector3f(1,0,0))<1e-6 : "stepped before configured cadence";
            for(int frame=2;frame<=1200;frame++) sim.update(p,new Matrix4f(),frame/240.0,false,WORLD,0,0,0);
            assert sim.position(1).isFinite() && Math.abs(sim.position(1).length()-1)<1e-4 : "invalid low-rate chain";
            sim.setFrameRate(fps==60?120:60);
            sim.update(p,new Matrix4f(),5.01,false,WORLD,0,0,0);
            assert sim.position(1).distance(new Vector3f(1,0,0))<1e-6 : "FPS change retained old state";
        }
        for(int fps:new int[]{0,121}) {
            try {new PhysSimulation(definition(single("g=0"))).setFrameRate(fps);throw new AssertionError("invalid rate accepted");}
            catch(IllegalArgumentException expected) {}
        }
    }

    private static void bodyCollision() {
        // Check final renderer matrices, not merely collision-projected particles.
        var crossing=model(bone("ysmPhysC@obstacle",-1,0,0,0),
                bone("ysmPhys#g=0,s=0,a=180@chain",-1,0,0,0),bone("ysmPhysEnd@end",1,16,0,0));
        float[] obstacle=cube();
        for(int i=0;i<obstacle.length;i+=3) {obstacle[i]=obstacle[i]*.25f+.6f;obstacle[i+1]*=.25f;obstacle[i+2]*=.25f;}
        crossing.bakedBones.get(0).physicsFaces.add(obstacle);
        var crossingDefinition=definition(crossing);
        var crossingSimulation=new PhysSimulation(crossingDefinition);
        var crossingPose=parameters(crossing);
        for(int frame=0;frame<10;frame++) crossingSimulation.update(crossingPose,new Matrix4f(),frame/60.0,false,WORLD,0,0,0);
        var rendered=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(crossingDefinition.bones.get(1),new Matrix4f(),crossingPose,12);
        Vector3f renderedStart=rendered.transformPosition(crossingDefinition.pivot(1)),renderedEnd=rendered.transformPosition(crossingDefinition.pivot(2));
        assert !new PhysCollider(obstacle,new Matrix4f()).resolveSegment(renderedStart,renderedEnd,new Vector3f(),new Vector3f(),1/16f-.0002f,true)
                : "render reconstruction discarded segment collision";
        // Rearward/forward movement repeatedly drives a rooted chain into a thin wall.
        // Inspect every final pose so an endpoint on the far side cannot masquerade
        // as a successful contact resolution.
        for(int fps:new int[]{30,60,120}) {
            var walking=model(bone("ysmPhysC@wall",-1,0,0,0),
                    bone("ysmPhys#g=0,s=10,a=180@chain",-1,0,0,0),bone("ysmPhysEnd@end",1,16,0,0));
            float[] wall=cube();
            for(int v=0;v<wall.length;v+=3) {wall[v]*=4;wall[v+1]*=4;wall[v+2]=wall[v+2]*.08f+.3f;}
            walking.bakedBones.get(0).physicsFaces.add(wall);
            var wd=definition(walking);var ws=new PhysSimulation(wd);var wp=parameters(walking);
            for(int frame=0;frame<fps*2;frame++) {
                double t=frame/(double)fps;
                ws.update(wp,new Matrix4f(),t,false,WORLD,0,0,-t*8);
                Matrix4f boneMatrix=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(wd.bones.get(1),new Matrix4f(),wp,12);
                Vector3f tip=boneMatrix.transformPosition(wd.pivot(2));
                assert tip.z<.26f-1/16f+.002f : "backwards movement crossed wall at "+fps+" FPS frame "+frame+": "+tip;
            }
        }
        var m=model(bone("ysmPhysC@body",-1,0,0,0),bone("ysmPhys#g=0,s=0,a=180@root",0,0,0,0),bone("ysmPhysEnd@end",1,16,0,0));
        float[] faces=cube();
        var cubeTransform=new Matrix4f().translate(1,0,0).rotateY(.4f).scale(.5f);
        for(int i=0;i<faces.length;i+=3) {
            Vector3f v=cubeTransform.transformPosition(new Vector3f(faces[i],faces[i+1],faces[i+2]));
            faces[i]=v.x;faces[i+1]=v.y;faces[i+2]=v.z;
        }
        m.bakedBones.get(0).physicsFaces.add(faces);
        var d=definition(m);assert d.cubes.size()==1;
        var unmarked=model(bone("ordinary",-1,0,0,0),bone("ysmPhys@generic",0,0,0,0),bone("ysmPhysEnd@end",1,16,0,0));
        unmarked.bakedBones.get(0).physicsFaces.add(faces);
        assert definition(unmarked).cubes.isEmpty() : "unmarked body generated collisions";
        var directOnly=model(bone("ysmPhysC@collider",-1,0,0,0),bone("unmarkedChild",0,0,0,0),
                bone("ysmPhys@chain",-1,0,0,0),bone("ysmPhysEnd@end",2,16,0,0));
        directOnly.bakedBones.get(0).physicsFaces.add(faces);
        directOnly.bakedBones.get(1).physicsFaces.add(faces);
        assert definition(directOnly).cubes.size()==1 : "collider marker leaked to ordinary children";
        var sim=new PhysSimulation(d);var p=parameters(m);
        sim.update(p,new Matrix4f(),0,false,WORLD,0,0,0);
        sim.update(p,new Matrix4f(),1/60.0,false,WORLD,0,0,0);
        Vector3f end=sim.position(2), copy=new Vector3f(end);
        assert !new PhysCollider(faces,new Matrix4f()).resolve(copy,new Vector3f(),.5f/16-.0001f) : "node remained in body";
        // Hide-self alone keeps children visible, but excludes the body's own cubes.
        sim=new PhysSimulation(d);p=parameters(m);p[9]=1;p[10]=0;
        sim.update(p,new Matrix4f(),0,false,WORLD,0,0,0);
        sim.update(p,new Matrix4f(),1/60.0,false,WORLD,0,0,0);
        assert sim.position(2).distance(new Vector3f(1,0,0))<1e-6 : "hidden cube collided";
        m=model(bone("ordinary_root",-1,0,0,0),bone("ysmPhysC@body",0,0,0,0),
                bone("ysmPhys#g=0,s=0,a=180@independent",-1,0,0,0),bone("ysmPhysEnd@end",2,16,0,0));
        m.bakedBones.get(1).physicsFaces.add(faces);d=definition(m);
        for(int flag:new int[]{10,6}) {
            sim=new PhysSimulation(d);p=parameters(m);p[flag]=flag==10?1:0;
            sim.update(p,new Matrix4f(),0,false,WORLD,0,0,0);
            sim.update(p,new Matrix4f(),1/60.0,false,WORLD,0,0,0);
            assert sim.position(3).distance(new Vector3f(1,0,0))<1e-6 : "hidden/scaled ancestor collider survived";
        }
        // A new model creates independent topology/state, not stale pointers into the previous rig.
        var next=new PhysSimulation(definition(single("g=0")));
        var nextP=parameters(single("g=0"));next.update(nextP,new Matrix4f(),10,false,WORLD,0,0,0);
        assert next.position(1).equals(new Vector3f(1,0,0));
    }

    private static void context() {
        assert !WorldPhysicsContext.allows(false) : "UI without level pass";
        var view=new Matrix4f().rotateX(.3f).rotateY(.7f);
        WorldPhysicsContext.begin(view);
        assert WorldPhysicsContext.allows(false) : "world entity excluded";
        assert !WorldPhysicsContext.allows(true) : "inventory/model selector/paper doll simulated";
        var model=new Matrix4f().translate(2,3,4).rotateZ(.2f);
        assert WorldPhysicsContext.modelToWorld(new Matrix4f(view).mul(model)).equals(model,1e-5f) : "camera entered physics";
        Matrix4f shadowView=new Matrix4f().rotateX(1.2f).rotateY(-.7f).translate(0,0,-12);
        WorldPhysicsContext.begin(shadowView);
        assert WorldPhysicsContext.modelToWorld(new Matrix4f(shadowView).mul(model)).equals(model,1e-5f) : "shadow camera entered physics";
        WorldPhysicsContext.end();
        assert WorldPhysicsContext.modelToWorld(new Matrix4f(view).mul(model)).equals(model,1e-5f) : "shadow pass corrupted main view";
        WorldPhysicsContext.end();assert !WorldPhysicsContext.allows(false);
    }

    private static void benchmark() {
        for(int count:new int[]{1,64}) for(int bodyCount:new int[]{0,16}) {
            var bones=new ArrayList<GeoBone>();
            for(int i=0;i<count;i++) {
                bones.add(bone("ysmPhys@root"+i,-1,i*2,0,0));bones.add(bone("ysmPhysEnd@end"+i,i*2,i*2+16,0,0));
            }
            bones.add(bone("ysmPhysC@body",-1,0,0,0));
            var m=model(bones.toArray(GeoBone[]::new));
            for(int body=0;body<bodyCount;body++) {
                float[] faces=cube();
                for(int i=0;i<faces.length;i+=3) {faces[i]=faces[i]*.15f+body*.125f;faces[i+1]=faces[i+1]*.15f-.6f;faces[i+2]*=.15f;}
                m.bakedBones.get(bones.size()-1).physicsFaces.add(faces);
            }
            var sim=new PhysSimulation(definition(m));var p=parameters(m);
            for(int i=0;i<120;i++) sim.update(p,new Matrix4f(),i/60.0,false,WORLD,0,0,0);
            double[] samples=new double[5];
            for(int batch=0;batch<samples.length;batch++) {
                long start=System.nanoTime();
                for(int i=0;i<120;i++) sim.update(p,new Matrix4f(),(120+batch*120+i)/60.0,false,WORLD,0,0,0);
                samples[batch]=(System.nanoTime()-start)/120e6;
            }
            Arrays.sort(samples);System.out.printf("CPU benchmark: %d chains, %d body cubes, median %.3f ms/frame%n",count,bodyCount,samples[2]);
        }
    }

    private static void example() throws Exception {
        var path=java.nio.file.Path.of(System.getProperty("ysm.physicsExample"));
        try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(path)) {
            var raw=reader.deserialize();
            try(var serialized=com.elfmcys.yesstevemodel.resource.YSMBinarySerializer.serialize(raw,32,true);
                var binary=new com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer(serialized.toArray(),32)) {
                var decoded=binary.deserialize();
                for(var bone:decoded.mainEntity.mainModel.bones) if(PhysParameters.isCollider(bone.name)) {
                    assert !bone.cubes.isEmpty();
                    assert bone.cubes.stream().allMatch(cube->cube.faces.size()==6) : "no-UV collision faces lost in server/client payload";
                }
            }
            var geometry=raw.mainEntity.mainModel;
            var context=com.elfmcys.yesstevemodel.resource.YSMClientMapper.buildContext(geometry);
            var method=com.elfmcys.yesstevemodel.resource.YSMClientMapper.class.getDeclaredMethod("buildMesh",
                    com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry.class,
                    GeometryDescription.class,int.class,com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner.class,boolean.class);
            method.setAccessible(true);
            var mesh=(GeoModel)method.invoke(null,geometry,context,1,null,false);
            assert mesh.ysmPhysics.joints.size()==6 && mesh.ysmPhysics.ends.length==4 && mesh.ysmPhysics.cubes.size()==2 : "demo topology";
            var clear=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var scanner=new com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner(new java.awt.image.BufferedImage[]{clear},1);
            mesh=(GeoModel)method.invoke(null,geometry,context,1,scanner,false);
            assert mesh.bakedBones.stream().allMatch(b->b.cubes.isEmpty()) : "transparent fixture did not cull rendered cubes";
            assert mesh.ysmPhysics.cubes.size()==2 : "texture transparency removed body collisions";
            var render=PlayerPhysics.hideEndpoints(mesh,mesh.boneTransformData);
            for(int end:mesh.ysmPhysics.ends) assert render[end*12+6]==0 && mesh.boneTransformData[end*12+6]==1 : "endpoint mask modified base pose";
        }
    }

    private static void actualModel() throws Exception {
        String path=System.getProperty("ysm.physicsModel");
        if(path==null) return;
        try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(java.nio.file.Path.of(path))) {
            var raw=reader.deserialize();var geometry=raw.mainEntity.mainModel;
            java.nio.file.Path config=java.nio.file.Path.of(path).getParent().getParent().getParent();
            var index=config.resolve("cache/server_index");
            if(java.nio.file.Files.isRegularFile(index)) {
                var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(index)).getAsJsonObject();
                byte[] key=java.util.Base64.getDecoder().decode(json.get("server_key").getAsString());
                long[] hashes=net.everla.reimaginedysm.security.YsmCrypt.calculateModelHashes(raw.properties.sha256,key);
                var cache=config.resolve("cache/server/"+String.format("%016x%016x",hashes[0],hashes[1]));
                if(java.nio.file.Files.isRegularFile(cache)) {
                    var payload=net.everla.reimaginedysm.security.YsmCrypt.readInPlace(java.nio.file.Files.readAllBytes(cache),key);
                    try(var binary=new com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer(payload.data(),payload.formatVersion())) {
                        var cached=binary.deserialize();
                        for(var bone:cached.mainEntity.mainModel.bones) if(PhysParameters.isCollider(bone.name))
                            System.out.println("Cached collider "+bone.name+": "+bone.cubes.stream().mapToInt(c->c.faces.size()).sum()+" faces");
                    }
                }
            }
            var method=com.elfmcys.yesstevemodel.resource.YSMClientMapper.class.getDeclaredMethod("buildMesh",
                    com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry.class,
                    GeometryDescription.class,int.class,com.elfmcys.yesstevemodel.resource.YSMClientMapper.TranslucencyScanner.class,boolean.class);
            method.setAccessible(true);
            var mesh=(GeoModel)method.invoke(null,geometry,com.elfmcys.yesstevemodel.resource.YSMClientMapper.buildContext(geometry),1,null,false);
            var d=mesh.ysmPhysics;
            System.out.printf("Actual model: %d joints, %d collision cubes%n",d.joints.size(),d.cubes.size());
            for(var joint:d.joints) System.out.println("Actual joint: "+d.bones.get(joint.bone()).getName()+" outputs="+joint.outputs().length);
            boolean stable=true;
            for(int scenario=0;scenario<22;scenario++) {
                int solverFps=scenario<11?120:60;
                int poseScenario=scenario%11;
                float speed=poseScenario<4?new float[]{0,-4,4,-20}[poseScenario]:0;
                float tilt=poseScenario<4?0:new float[]{0,-60,-30,30,60,90,180}[poseScenario-4];
                Matrix4f root=new Matrix4f().rotateX((float)Math.toRadians(tilt));
                if(poseScenario>=4) root.scale(.7f);
                var simulation=new PhysSimulation(d);var p=parameters(mesh);
                simulation.setFrameRate(solverFps);
                int contacts=0;
                float lateMovement=0;
                String lateJoint="";
                Vector3f[] previousTips=new Vector3f[d.joints.size()];
                int frames=speed==0?900:120;
                for(int frame=0;frame<frames;frame++) {
                    simulation.update(p,root,frame/60.0,false,WORLD,0,0,speed*frame/60.0);
                    float[] firstDraw=p.clone();
                    simulation.update(p,root,frame/60.0,false,WORLD,0,0,speed*frame/60.0);
                    assert Arrays.equals(firstDraw,p) : "repeated render changed solved bone angles";
                    Matrix4f[] transforms=new Matrix4f[d.bones.size()];
                    for(int bone:d.order) {
                        int parent=d.bones.get(bone).parentIdx;
                        transforms[bone]=com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils.prepMatrixForBone(d.bones.get(bone),new Matrix4f(parent<0?root:transforms[parent]),p,bone*12);
                    }
                    for(int j=0;j<d.joints.size();j++) {
                        var joint=d.joints.get(j);
                        int end=joint.outputs()[0];
                        Vector3f tip=transforms[end].transformPosition(d.pivot(end));
                        // Display interpolates rotation history, so its endpoint is
                        // intentionally different from the current integration state.
                        Vector3f pivot=transforms[joint.bone()].transformPosition(d.pivot(joint.bone()));
                        float length=d.pivot(joint.bone()).distance(d.pivot(end))*(poseScenario>=4?.7f:1);
                        assert tip.isFinite() && simulation.position(end).isFinite() : "non-finite actual-model state";
                        assert Math.abs(tip.distance(pivot)-length)<2e-5f : "displayed actual-model bone length changed";
                        if(frame>600 && previousTips[j]!=null && tip.distance(previousTips[j])>lateMovement) {
                            lateMovement=tip.distance(previousTips[j]);lateJoint=d.bones.get(joint.bone()).getName()+" frame="+frame;
                        }
                        previousTips[j]=tip;
                    }
                    for(var joint:d.joints) for(int end:joint.outputs()) for(var cube:d.cubes) {
                        Vector3f a=transforms[joint.bone()].transformPosition(d.pivot(joint.bone()));
                        Vector3f b=transforms[end].transformPosition(d.pivot(end));
                        var collider=new PhysCollider(cube.faces(),transforms[cube.bone()]);
                        if(collider.resolveSegment(a,b,new Vector3f(),new Vector3f(),joint.parameters().c()/16f*(poseScenario>=4?.7f:1)-.001f,true)) {
                            contacts++;
                            if(contacts==1) System.out.println("First overlap speed="+speed+" frame="+frame+" joint="+d.bones.get(joint.bone()).getName()+" collider="+d.bones.get(cube.bone()).getName());
                        }
                    }
                }
                System.out.println("Actual rendered contacts at speed "+speed+", tilt "+tilt+", scaled="+(poseScenario>=4)+", FPS="+solverFps+": "+contacts);
                stable &= contacts==0;
                if(speed==0) {
                    System.out.println("Static actual-model maximum late frame displacement: "+lateMovement+" joint="+lateJoint);
                    stable &= lateMovement<.003f;
                }
            }
            assert stable : "stationary chain keeps being launched";
        }
    }
}
