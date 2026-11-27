package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoBone;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Immutable model topology. Owned by GeoModel, never stores entity state. */
public final class PhysDefinition {
    public record Joint(int bone, int parent, PhysParameters parameters, int[] outputs) {}
    public record Cube(int bone, float[] faces) {}
    public final List<Joint> joints;
    public final List<Cube> cubes;
    public final int[] ends;
    public final List<GeoBone> bones;
    public final int[] order;
    public final Joint[] jointByBone;
    public final boolean[] affected;
    final boolean[] physicsScale;
    final boolean[] tails;
    final int[] tailDepth;

    public PhysDefinition(GeoModel model, Consumer<String> warning) {
        List<Joint> joints=new ArrayList<>();
        List<Cube> cubes=new ArrayList<>();
        List<GeoBone> imported = model.topLevelBones();
        List<GeoBone> hierarchy = new ArrayList<>();
        for (int i=0;i<imported.size();i++) {
            GeoBone original=imported.get(i);
            GeoBone copy=new GeoBone(model.scripts==null?"":model.scripts.physicsName(original.getName()),original.isHidden(),original.cubesAreHidden(),original.childBonesAreHiddenToo(),
                    original.getPivotX(),original.getPivotY(),original.getPivotZ(),original.getRotX(),original.getRotY(),original.getRotZ());
            copy.parentIdx=model.bakedBones==null?original.parentIdx:model.bakedBones.get(i).parentIdx;
            hierarchy.add(copy);
        }
        bones=List.copyOf(hierarchy);
        var warned=new java.util.HashSet<String>();
        Consumer<String> uniqueWarning=message -> {
            String group=message.substring(0,message.indexOf(':'));
            if(warned.add(group)) warning.accept(message);
        };
        int size=bones.size();
        tails=new boolean[size];tailDepth=new int[size];
        PhysParameters[] parameters=new PhysParameters[size];
        boolean[] physical=new boolean[size], excluded=new boolean[size];
        List<Integer> endList=new ArrayList<>();
        List<List<Integer>> children=new ArrayList<>();
        for(int i=0;i<size;i++) children.add(new ArrayList<>());
        for(int i=0;i<size;i++) {
            int parent=bones.get(i).parentIdx;
            if(parent>=0 && parent<size) children.get(parent).add(i);
        }
        // Resolve inheritance recursively: imported arrays need not be parent-first.
        byte[] visited=new byte[size];
        for(int i=0;i<size;i++) parse(i,parameters,visited,uniqueWarning);
        for(int i=0;i<size;i++) {
            String name=bones.get(i).getName();
            physical[i]=parameters[i]!=null;
            if(PhysParameters.isEnd(name)) endList.add(i);
            else if(name.startsWith("ysmPhysEnd")) uniqueWarning.accept(name+": invalid endpoint name");
        }
        for(int i=0;i<size;i++) {
            int cursor=i;
            while(cursor>=0 && cursor<size) {
                if(physical[cursor] || PhysParameters.isEnd(bones.get(cursor).getName())) {excluded[i]=true;break;}
                cursor=bones.get(cursor).parentIdx;
            }
        }
        boolean[] valid=new boolean[size];
        for(int i=0;i<size;i++) if(physical[i] && (bones.get(i).parentIdx<0 || !physical[bones.get(i).parentIdx]))
            validate(i,physical,valid,children,model,uniqueWarning);
        for(int i=0;i<size;i++) if(valid[i]) {
            List<Integer> outputs=new ArrayList<>();
            for(int child:children.get(i)) if(valid[child] || PhysParameters.isEnd(bones.get(child).getName()))
                outputs.add(child);
            int parent=bones.get(i).parentIdx;
            joints.add(new Joint(i,parent>=0 && valid[parent]?parent:-1,parameters[i],outputs.stream().mapToInt(Integer::intValue).toArray()));
        }
        // Solver and reconstruction must always run in parent-before-child order.
        joints.sort(java.util.Comparator.comparingInt(j -> depth(j.bone)));
        order=java.util.stream.IntStream.range(0,size).boxed().sorted(java.util.Comparator.comparingInt(this::depth)).mapToInt(Integer::intValue).toArray();
        for(int i:order) {
            tails[i]=bones.get(i).getName().startsWith("ysmTail");
            int parent=bones.get(i).parentIdx;
            if(tails[i] && parent>=0 && tails[parent]) tailDepth[i]=tailDepth[parent]+1;
        }
        jointByBone=new Joint[size];
        for(Joint joint:joints) jointByBone[joint.bone]=joint;
        affected=new boolean[size];
        for(int i:order) {
            int parent=bones.get(i).parentIdx;
            affected[i]=jointByBone[i]!=null || parent>=0 && affected[parent];
        }
        ends=endList.stream().mapToInt(Integer::intValue).toArray();
        if(!joints.isEmpty() && model.bakedBones!=null && model.scripts!=null) for(int i=0;i<size;i++) if(!excluded[i] && model.scripts.flag(imported.get(i).getName(),"collider")) {
            for(float[] faces:model.bakedBones.get(i).physicsFaces) cubes.add(new Cube(i,faces));
        }
        this.joints=List.copyOf(joints);
        this.cubes=List.copyOf(cubes);
        physicsScale=new boolean[size];
        for(var joint:joints) {
            requireScale(joint.bone());
            for(int end:joint.outputs()) requireScale(end);
        }
        for(var cube:cubes) requireScale(cube.bone());
    }

    private void requireScale(int bone) {
        for(int i=bone;i>=0 && !physicsScale[i];i=bones.get(i).parentIdx) physicsScale[i]=true;
    }

    /** Physical ancestors and ordinary descendants form an indivisible dependency island. */
    int[] islands() {
        int[] islands=new int[bones.size()];java.util.Arrays.fill(islands,-1);
        int count=0;
        for(int i:order) {
            int parent=bones.get(i).parentIdx;
            if(parent>=0) islands[i]=islands[parent];
            if(jointByBone[i]!=null && islands[i]<0) islands[i]=count++;
        }
        return islands;
    }

    boolean[] blockCollisionIslands() {
        int[] islands=islands();
        boolean[] blocked=new boolean[bones.size()],result=new boolean[bones.size()];
        for(var joint:joints) if(joint.parameters().bc()) blocked[islands[joint.bone()]]=true;
        for(int i:order) result[i]=islands[i]>=0 && blocked[islands[i]];
        return result;
    }

    /** Supported instances solve complete dependency islands on GPU, including contacts. */
    boolean[] cpuCollisionIslands() {
        return new boolean[bones.size()];
    }

    private int depth(int i) {
        int d=0; while((i=bones.get(i).parentIdx)>=0) d++; return d;
    }

    private void parse(int i, PhysParameters[] parameters, byte[] visited, Consumer<String> warning) {
        if(visited[i]==2) return;
        if(visited[i]==1) throw new IllegalArgumentException("cyclic model hierarchy");
        visited[i]=1;
        int parent=bones.get(i).parentIdx;
        if(parent>=0) parse(parent,parameters,visited,warning);
        String name=bones.get(i).getName();
        if(PhysParameters.isCandidate(name)) try {
            boolean tail=name.startsWith("ysmTail");
            boolean sameKind=parent>=0 && bones.get(parent).getName().startsWith("ysmTail")==tail;
            parameters[i]=PhysParameters.parse(name,sameKind && parameters[parent]!=null?parameters[parent]:tail?PhysParameters.TAIL_DEFAULT:PhysParameters.DEFAULT);
        } catch(IllegalArgumentException e) {warning.accept(name+": "+e.getMessage());}
        visited[i]=2;
    }

    private boolean validate(int i, boolean[] physical, boolean[] valid, List<List<Integer>> children,
                             GeoModel model, Consumer<String> warning) {
        int count=0, endCount=0;
        for(int child:children.get(i)) if(physical[child]) {
            if(validate(child,physical,valid,children,model,warning)) {
                if(pivot(i).distanceSquared(pivot(child))>1e-12f) count++;
                else {disable(child,valid,children);warning.accept(bones.get(child).getName()+": zero-length segment");}
            }
        } else if(PhysParameters.isEnd(bones.get(child).getName())) {
            endCount++;
            boolean empty=children.get(child).isEmpty() && (model.bakedBones==null || model.bakedBones.get(child).physicsFaces.isEmpty());
            if(!empty || pivot(i).distanceSquared(pivot(child))<=1e-12f) {
                warning.accept(bones.get(i).getName()+": endpoint must be empty and have nonzero distance");
                disable(i,valid,children);
                return false;
            }
        }
        if((count==0 && endCount!=1) || (count>0 && endCount!=0)) {
            warning.accept(bones.get(i).getName()+": expected physical children OR exactly one endpoint");
            disable(i,valid,children);
            return false;
        }
        return valid[i]=true;
    }

    private void disable(int i, boolean[] valid, List<List<Integer>> children) {
        valid[i]=false;
        for(int child:children.get(i)) disable(child,valid,children);
    }

    public Vector3f pivot(int i) {
        GeoBone b=bones.get(i);
        return new Vector3f(b.getPivotX(),b.getPivotY(),b.getPivotZ()).mul(1/16f);
    }
}
