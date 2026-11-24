package net.everla.ysmPhysLib;

import com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/** Per-instance CPU solver. Positions are world-oriented but rebased near the player. */
public final class PhysSimulation {
    private static final double STEP_SECONDS=1.0/120;
    public static final float STEP=(float)STEP_SECONDS;
    private double stepSeconds=STEP_SECONDS;
    private float stepDuration=STEP;
    private final PhysDefinition definition;
    private final Vector3f[] positions, velocities, targets;
    private final float[] tailPhase;
    private final Quaternionf[] rotations, previousRotations;
    private List<PhysDefinition.Joint> activeJoints;
    private boolean[] active;
    private boolean[] enabled;
    private boolean hybrid;
    private float[] base;
    private Matrix4f currentRoot;
    private boolean applied;
    private double lastTime=Double.NaN, accumulator;
    private Object world;
    private double originX,originY,originZ;
    private boolean initialized;
    private int stepsInUpdate;
    private boolean sampledFrame;
    private boolean projectHandoffDisplay;
    private float[] displayed;
    private final Matrix4f displayedRoot=new Matrix4f();
    private boolean displayedPaused;
    private final Vector3f frameVelocity=new Vector3f();
    private final List<PhysCollider> colliders=new ArrayList<>();
    private java.util.function.Function<AABB,Iterable<VoxelShape>> blockCollisions;
    private record BlockContacts(AABB bounds, List<PhysCollider> contacts) {}
    private final java.util.Map<Integer,BlockContacts> blockContacts=new java.util.HashMap<>();

    public PhysSimulation(PhysDefinition definition) {
        this.definition=definition;
        activeJoints=definition.joints;
        int count=definition.bones.size();
        tailPhase=new float[count];
        positions=new Vector3f[count];velocities=new Vector3f[count];targets=new Vector3f[count];rotations=new Quaternionf[count];
        for(int i=0;i<count;i++) {positions[i]=new Vector3f();velocities[i]=new Vector3f();targets[i]=new Vector3f();rotations[i]=new Quaternionf();}
        previousRotations=new Quaternionf[count];
        for(int i=0;i<count;i++) previousRotations[i]=new Quaternionf();
    }

    /** Must run before animation evaluation, including evaluation throttling and UI renders. */
    public void restore(float[] parameters) {
        if(!applied) return;
        for(var joint:activeJoints) System.arraycopy(base,joint.bone()*12,parameters,joint.bone()*12,3);
        applied=false;
    }

    void partition(float[] parameters, boolean hybrid) {
        if(this.hybrid==hybrid) return;
        restore(parameters);
        this.hybrid=hybrid;
        rebuildActive();
        invalidate();
    }

    public void setEnabled(float[] parameters, boolean[] enabled) {
        if(java.util.Arrays.equals(this.enabled,enabled)) return;
        restore(parameters);
        this.enabled=enabled.clone();
        rebuildActive();
        invalidate();
    }

    private void rebuildActive() {
        active=hybrid?definition.cpuCollisionIslands():new boolean[definition.bones.size()];
        if(!hybrid) java.util.Arrays.fill(active,true);
        if(enabled!=null) for(int i=0;i<active.length;i++) active[i]&=enabled[i];
        activeJoints=definition.joints.stream().filter(j->active[j.bone()]).toList();
    }

    public void invalidate() {initialized=false;lastTime=Double.NaN;accumulator=0;projectHandoffDisplay=false;displayed=null;}

    /** One-way upload at CPU -> GPU handoff; keep phase, inertia and interpolation history. */
    boolean writeGpuState(java.nio.ByteBuffer data,GpuPhysModel model,GpuPhysics.Clock clock) {
        if(!initialized || hybrid || !Double.isFinite(lastTime)) return false;
        for(int tree=0;tree<model.trees;tree++) for(int original=0;original<positions.length;original++) {
            int at=(tree*positions.length+model.sorted[original])*GpuPhysKernel.NODE_BYTES;
            positions[original].get(at,data);velocities[original].get(at+16,data);
            data.putFloat(at+28,tailPhase[original]);
            targets[original].get(at+32,data);
            for(int history=0;history<2;history++) {
                var q=history==0?rotations[original]:previousRotations[original];
                int rotationAt=at+(history==0?48:224);
                data.putFloat(rotationAt,q.x).putFloat(rotationAt+4,q.y).putFloat(rotationAt+8,q.z).putFloat(rotationAt+12,q.w);
            }
        }
        clock.last=lastTime;clock.accumulator=accumulator;clock.world=world;
        clock.originX=originX;clock.originY=originY;clock.originZ=originZ;
        clock.fps=(int)Math.round(1/stepSeconds);
        return true;
    }

    /** Only called after GPU ownership ends, before the next ordinary CPU update. */
    void readGpuState(java.nio.ByteBuffer data,GpuPhysModel model,GpuPhysics.Clock clock) {
        // Validate before replacing any CPU state; failed dispatches must reset cleanly.
        for(int original=0;original<positions.length;original++) if(model.island[original]>=0) {
            int at=(model.island[original]*positions.length+model.sorted[original])*GpuPhysKernel.NODE_BYTES;
            for(int component=0;component<3;component++)
                if(!Float.isFinite(data.getFloat(at+component*4)) || !Float.isFinite(data.getFloat(at+16+component*4)))
                    throw new IllegalArgumentException("non-finite GPU handoff state");
            if(definition.jointByBone[original]!=null) for(int history=0;history<2;history++) {
                int rotationAt=at+(history==0?48:224);float norm=0;
                for(int component=0;component<4;component++) {float value=data.getFloat(rotationAt+component*4);norm+=value*value;}
                if(!Float.isFinite(norm) || Math.abs(norm-1)>.01f)
                    throw new IllegalArgumentException("invalid GPU handoff rotation");
            }
        }
        for(int original=0;original<positions.length;original++) if(model.island[original]>=0) {
            int at=(model.island[original]*positions.length+model.sorted[original])*GpuPhysKernel.NODE_BYTES;
            positions[original].set(data.getFloat(at),data.getFloat(at+4),data.getFloat(at+8));
            velocities[original].set(data.getFloat(at+16),data.getFloat(at+20),data.getFloat(at+24));
            tailPhase[original]=data.getFloat(at+28);
            for(int history=0;history<2;history++) {
                var q=history==0?rotations[original]:previousRotations[original];
                int rotationAt=at+(history==0?48:224);
                q.set(data.getFloat(rotationAt),data.getFloat(rotationAt+4),data.getFloat(rotationAt+8),data.getFloat(rotationAt+12));
            }
        }
        lastTime=clock.last;accumulator=clock.accumulator;world=clock.world;
        originX=clock.originX;originY=clock.originY;originZ=clock.originZ;
        stepSeconds=1.0/clock.fps;stepDuration=(float)stepSeconds;initialized=true;
        projectHandoffDisplay=true;
        displayed=null;
    }

    /** Changing cadence starts from the current animation, without old-step debt. */
    public void setFrameRate(int fps) {
        if(fps<1 || fps>120) throw new IllegalArgumentException("Physics FPS must be 1..120");
        double duration=1.0/fps;
        if(duration!=stepSeconds) {
            stepSeconds=duration;stepDuration=(float)duration;
            invalidate();
        }
    }

    public void update(float[] parameters, Matrix4f root, double time, boolean paused, Object currentWorld,
                       double x, double y, double z) {
        update(parameters,root,time,paused,currentWorld,x,y,z,null);
    }

    /** Block queries are supplied only by the world-player adapter, never by UI previews. */
    public void update(float[] parameters, Matrix4f root, double time, boolean paused, Object currentWorld,
                       double x, double y, double z, java.util.function.Function<AABB,Iterable<VoxelShape>> blockCollisions) {
        stepsInUpdate=0;
        sampledFrame=false;
        if(activeJoints.isEmpty()) return;
        restore(parameters);
        // Extra render passes can restore animation between identical world draws.
        // Reuse only the displayed rotations, leaving caller-owned visibility/light intact.
        if(projectHandoffDisplay && displayed!=null && time==lastTime && currentWorld==world
                && x==originX && y==originY && z==originZ && paused==displayedPaused
                && displayedRoot.equals(root) && java.util.Arrays.equals(base,parameters)) {
            for(var joint:activeJoints) System.arraycopy(displayed,joint.bone()*12,parameters,joint.bone()*12,3);
            applied=true;return;
        }
        base=parameters.clone();
        currentRoot=root;
        if(!root.isFinite() || Math.abs(root.determinant())<1e-12f) {invalidate();return;}
        Matrix4f[] matrices=new Matrix4f[positions.length];
        for(int i:definition.order) {
            int parent=definition.bones.get(i).parentIdx;
            matrices[i]=RenderUtils.prepMatrixForBone(definition.bones.get(i),new Matrix4f(parent<0?root:matrices[parent]),base,i*12);
        }
        for(int i=0;i<targets.length;i++) matrices[i].transformPosition(definition.pivot(i),targets[i]);
        Vector3f shift=new Vector3f((float)(originX-x),(float)(originY-y),(float)(originZ-z));
        boolean reset=!initialized || world!=currentWorld || !Double.isFinite(time) || time<lastTime || time-lastTime>.25 || shift.lengthSquared()>16;
        if(initialized && !reset) {
            // Root-relative state moves with its reference frame; absolute velocity
            // below preserves inertia without applying the full frame jump per substep.
            for(var joint:activeJoints) if(joint.parent()<0 && positions[joint.bone()].distanceSquared(targets[joint.bone()])>16) reset=true;
        }
        sampledFrame=!reset && !paused && time>lastTime;
        originX=x;originY=y;originZ=z;world=currentWorld;
        if(reset) {
            java.util.Arrays.fill(tailPhase,0);
            for(int i=0;i<positions.length;i++) {positions[i].set(targets[i]);velocities[i].zero();rotations[i].identity();previousRotations[i].identity();}
            accumulator=0;initialized=true;
        }
        double elapsed=Double.isFinite(lastTime)?Math.max(0,time-lastTime):0;
        if(!reset && !paused && elapsed>1e-9) frameVelocity.set(shift).mul((float)(-1/elapsed));
        else frameVelocity.zero();
        lastTime=time;
        if(reset || !paused && elapsed>0) {
            this.blockCollisions=blockCollisions;
            blockContacts.clear();colliders.clear();
            boolean[] visible=visibility(base);
            for(var cube:definition.cubes) if(visible[cube.bone()]) colliders.add(new PhysCollider(cube.faces(),matrices[cube.bone()]));
            if(reset) {
                // Establish a feasible rigid pose before seeding display history.
                // The authored rest pose may already overlap a body or terrain.
                constrain();
                for(var joint:activeJoints) previousRotations[joint.bone()].set(rotations[joint.bone()]);
            } else accumulator=Math.min(accumulator+elapsed,stepSeconds*8.0);
            for(int step=0;!reset && step<8 && accumulator+1e-9>=stepSeconds;step++) {
                stepsInUpdate++;
                for(var joint:activeJoints) previousRotations[joint.bone()].set(rotations[joint.bone()]);
                integrate();
                for(int iteration=0;iteration<8;iteration++) {
                    // Contact belongs to the length-preserving rigid pose. A second
                    // free-particle projection moves shared endpoints incompatibly
                    // with that pose and repeatedly launches resting chains.
                    constrain();
                }
                // Positions and velocities are projected separately. Geometric
                // correction must never be converted into an outward impulse.
                constrain();
                accumulator-=stepSeconds;
            }
        }
        reconstruct(parameters);
        if(projectHandoffDisplay) {
            this.blockCollisions=blockCollisions;
            blockContacts.clear();colliders.clear();
            boolean[] visible=visibility(base);
            for(var cube:definition.cubes) if(visible[cube.bone()]) colliders.add(new PhysCollider(cube.faces(),matrices[cube.bone()]));
            projectDisplayedContacts(parameters,root);
            if(displayed==null) displayed=new float[parameters.length];
            System.arraycopy(parameters,0,displayed,0,parameters.length);
            displayedRoot.set(root);displayedPaused=paused;
        }
        this.blockCollisions=null;
        blockContacts.clear();
        applied=true;
    }

    private boolean[] visibility(float[] p) {
        boolean[] visible=new boolean[positions.length], children=new boolean[positions.length];
        for(int i:definition.order) {
            int parent=definition.bones.get(i).parentIdx;
            boolean ancestor=parent<0 || children[parent];
            boolean scaled=p[i*12+6]!=0 && p[i*12+7]!=0 && p[i*12+8]!=0;
            visible[i]=ancestor && p[i*12+9]==0 && scaled;
            children[i]=ancestor && p[i*12+10]==0 && scaled;
        }
        return visible;
    }

    private void integrate() {
        for(var joint:activeJoints) {
            if(joint.parent()<0) {positions[joint.bone()].set(targets[joint.bone()]);velocities[joint.bone()].set(frameVelocity);}
            PhysParameters p=joint.parameters();
            int bone=joint.bone();
            float motion=Math.min(1,(float)Math.hypot(frameVelocity.x,frameVelocity.z)/2);
            if(definition.tails[bone]) tailPhase[bone]=(tailPhase[bone]+stepDuration*motion*8)%(float)(Math.PI*2);
            for(int end:joint.outputs()) {
                Vector3f displacement=new Vector3f(targets[end]).sub(positions[end]);
                Vector3f acceleration=new Vector3f(displacement).mul(p.s());
                if(p.gw()==null) acceleration.y-=p.g();
                else {
                    // Smooth the well's cusp over one model pixel: zero force at the target,
                    // constant requested acceleration outside this core, also for repulsive wells.
                    acceleration.fma(p.gw()/Math.max(1/16f,displacement.length()),displacement);
                }
                if(definition.tails[bone] && motion>0) {
                    float phase=tailPhase[bone]-definition.tailDepth[bone]*0.75f;
                    Vector3f side=currentRoot.transformDirection(new Vector3f(1,0,0)).normalize();
                    Vector3f up=currentRoot.transformDirection(new Vector3f(0,1,0)).normalize();
                    acceleration.fma(6*motion*(float)Math.sin(phase),side).fma(1.5f*motion*(float)Math.sin(phase*2),up);
                }
                velocities[end].sub(frameVelocity).mul((float)Math.exp(-p.r()*stepDuration)).add(frameVelocity).fma(stepDuration,acceleration);
                applyAirDrag(velocities[end],p.air(),stepDuration);
                positions[end].fma(stepDuration,new Vector3f(velocities[end]).sub(frameVelocity));
                if(!positions[end].isFinite() || !velocities[end].isFinite()) {positions[end].set(targets[end]);velocities[end].zero();}
            }
        }
    }

    /** Exact velocity update for dv/dt = -air * |v| * v in still world-space air. */
    public static void applyAirDrag(Vector3f velocity, float air, float dt) {
        double speed=Math.sqrt((double)velocity.x*velocity.x+(double)velocity.y*velocity.y+(double)velocity.z*velocity.z);
        velocity.mul((float)(1/(1+(double)air*speed*dt)));
    }

    private void constrain() {
        pose(base.clone(),currentRoot);
    }

    /** Body colliders share the player's translating frame; terrain does not. */
    static void resolveContact(PhysCollider collider, Vector3f start, Vector3f end, Vector3f startVelocity,
                               Vector3f endVelocity, float radius, boolean fixedStart, Vector3f frameVelocity) {
        if(!collider.worldBlock) {startVelocity.sub(frameVelocity);endVelocity.sub(frameVelocity);}
        collider.resolveSegment(start,end,startVelocity,endVelocity,radius,fixedStart);
        if(!collider.worldBlock) {startVelocity.add(frameVelocity);endVelocity.add(frameVelocity);}
    }

    private void reconstruct(float[] output) {
        // Interpolate only the displayed correction; never feed it back into constraints.
        float alpha=(float)Math.max(0,Math.min(1,accumulator/stepSeconds));
        for(var joint:activeJoints) {
            int i=joint.bone();
            Quaternionf local=new Quaternionf().rotationZYX(base[i*12+2],base[i*12+1],base[i*12]);
            Vector3f euler=PhysMath.eulerZYX(new Quaternionf(previousRotations[i]).nlerp(rotations[i],alpha).mul(local).normalize());
            if(euler.isFinite()) {output[i*12]=euler.x;output[i*12+1]=euler.y;output[i*12+2]=euler.z;}
        }
    }

    /** Project only CPU display after GPU handoff; persistent integration state is untouched. */
    private void projectDisplayedContacts(float[] output,Matrix4f root) {
        Matrix4f[] actual=new Matrix4f[positions.length];
        for(int i:definition.order) {
            int parent=definition.bones.get(i).parentIdx;
            Matrix4f before=parent<0?root:actual[parent];
            var joint=active==null || active[i]?definition.jointByBone[i]:null;
            if(joint!=null) {
                var animated=RenderUtils.prepMatrixForBone(definition.bones.get(i),new Matrix4f(before),base,i*12);
                var pivot=animated.transformPosition(definition.pivot(i),new Vector3f());
                var inverse=new Matrix4f(before).invert();
                var from=new ArrayList<Vector3f>();float reach=0;
                for(int end:joint.outputs()) {
                    var child=RenderUtils.prepMatrixForBone(definition.bones.get(end),new Matrix4f(animated),base,end*12);
                    var source=child.transformPosition(definition.pivot(end),new Vector3f()).sub(pivot);
                    reach=Math.max(reach,source.length());from.add(inverse.transformDirection(source));
                }
                var scale=animated.getScale(new Vector3f());
                float radius=joint.parameters().c()/16f*Math.max(scale.x,Math.max(scale.y,scale.z));
                var contacts=contacts(joint,new AABB(pivot.x,pivot.y,pivot.z,pivot.x,pivot.y,pivot.z).inflate(reach+radius));
                if(!contacts.isEmpty()) {
                    var local=new Quaternionf().rotationZYX(base[i*12+2],base[i*12+1],base[i*12]);
                    var correction=new Quaternionf().rotationZYX(output[i*12+2],output[i*12+1],output[i*12]).mul(new Quaternionf(local).invert());
                    for(int pass=0;pass<24;pass++) {
                        var desired=new ArrayList<Vector3f>();boolean hit=false;
                        for(var source:from) {
                            var endpoint=before.transformDirection(new Vector3f(source).rotate(correction)).add(pivot);
                            for(var collider:contacts) hit=collider.resolveSegment(new Vector3f(pivot),endpoint,new Vector3f(),new Vector3f(),radius,true)||hit;
                            desired.add(inverse.transformDirection(endpoint.sub(pivot)));
                        }
                        if(!hit) break;
                        correction=PhysMath.limit(PhysMath.fit(from,desired,correction),joint.parameters().a());
                    }
                    var euler=PhysMath.eulerZYX(correction.mul(local).normalize());
                    if(euler.isFinite()) {output[i*12]=euler.x;output[i*12+1]=euler.y;output[i*12+2]=euler.z;}
                }
            }
            actual[i]=RenderUtils.prepMatrixForBone(definition.bones.get(i),new Matrix4f(before),output,i*12);
        }
    }

    private void pose(float[] output, Matrix4f root) {
        Matrix4f[] actual=new Matrix4f[positions.length];
        for(int i:definition.order) {
            int parent=definition.bones.get(i).parentIdx;
            Matrix4f before=parent<0?root:actual[parent];
            PhysDefinition.Joint joint=active==null || active[i]?definition.jointByBone[i]:null;
            if(joint!=null) {
                Matrix4f animated=RenderUtils.prepMatrixForBone(definition.bones.get(i),new Matrix4f(before),base,i*12);
                Vector3f pivot=animated.transformPosition(definition.pivot(i),new Vector3f());
                List<Vector3f> from=new ArrayList<>(),to=new ArrayList<>();
                for(int end:joint.outputs()) {
                    Matrix4f child=RenderUtils.prepMatrixForBone(definition.bones.get(end),new Matrix4f(animated),base,end*12);
                    from.add(child.transformPosition(definition.pivot(end),new Vector3f()).sub(pivot));
                    to.add(new Vector3f(positions[end]).sub(pivot));
                }
                // Fit in the parent's pre-scale coordinates: nonuniform model scaling must
                // not be misinterpreted as a bone rotation.
                Matrix4f inverseParent=new Matrix4f(before).invert();
                for(Vector3f v:from) inverseParent.transformDirection(v);
                for(Vector3f v:to) inverseParent.transformDirection(v);
                Quaternionf correction=PhysMath.limit(PhysMath.fit(from,to,rotations[i]),joint.parameters().a());
                // Resolve contacts on the actual, length-preserving bone pose. Pushing
                // free particle endpoints alone is insufficient: reconstruction used to
                // project that displacement away and put the visible segment back inside.
                Vector3f scale=animated.getScale(new Vector3f());
                float radius=joint.parameters().c()/16f*Math.max(scale.x,Math.max(scale.y,scale.z));
                List<PhysCollider> contacts;
                {
                    float reach=0;
                    for(Vector3f source:from) reach=Math.max(reach,source.length());
                    // Frobenius norm bounds every rotated direction, including nonuniform parent scaling.
                    float bound=before.getScale(new Vector3f()).length()*reach+radius;
                    contacts=contacts(joint,new AABB(pivot.x,pivot.y,pivot.z,pivot.x,pivot.y,pivot.z).inflate(bound));
                }
                // A discrete solver may propose a clear pose on the opposite side
                // of a thin body. Stop at the first contact along the rotation from
                // the previous pose, rather than accepting that tunneled endpoint.
                Quaternionf previous=rotations[i];
                if(!contacts.isEmpty() && clearPose(from,previous,before,pivot,radius,contacts)) {
                    Quaternionf safe=new Quaternionf(previous);
                    float angle=2*(float)Math.acos(Math.min(1,Math.abs(previous.dot(correction))));
                    int samples=Math.max(1,Math.min(180,(int)Math.ceil(angle/Math.toRadians(1))));
                    for(int sample=1;sample<=samples;sample++) {
                        Quaternionf candidate=new Quaternionf(previous).slerp(correction,sample/(float)samples);
                        if(!clearPose(from,candidate,before,pivot,radius,contacts)) {
                            Quaternionf blocked=candidate;
                            for(int refinement=0;refinement<10;refinement++) {
                                Quaternionf midpoint=new Quaternionf(safe).slerp(blocked,.5f);
                                if(clearPose(from,midpoint,before,pivot,radius,contacts)) safe.set(midpoint);
                                else blocked.set(midpoint);
                            }
                            correction=safe;
                            break;
                        }
                        safe.set(candidate);
                    }
                }
                for(int contactPass=0;contactPass<24 && !contacts.isEmpty();contactPass++) {
                    List<Vector3f> desired=new ArrayList<>();
                    boolean contact=false;
                    int outputIndex=0;
                    for(Vector3f source:from) {
                        int end=joint.outputs()[outputIndex++];
                        Vector3f endpoint=new Vector3f(source).rotate(correction);
                        before.transformDirection(endpoint).add(pivot);
                        Vector3f contactVelocity=new Vector3f(velocities[end]).sub(frameVelocity);
                        for(PhysCollider collider:contacts) {
                            // Body blocks move with the player; terrain is stationary in world space.
                            if(collider.worldBlock) contactVelocity.add(frameVelocity);
                            contact |= collider.resolveSegment(new Vector3f(pivot),endpoint,new Vector3f(),contactVelocity,radius,true);
                            if(collider.worldBlock) contactVelocity.sub(frameVelocity);
                        }
                        velocities[end].set(contactVelocity).add(frameVelocity);
                        desired.add(inverseParent.transformDirection(endpoint.sub(pivot)));
                    }
                    if(!contact) break;
                    correction=PhysMath.limit(PhysMath.fit(from,desired,correction),joint.parameters().a());
                }
                rotations[i].set(correction);
                Quaternionf local=new Quaternionf().rotationZYX(base[i*12+2],base[i*12+1],base[i*12]);
                correction.mul(local).normalize();
                Vector3f euler=PhysMath.eulerZYX(correction);
                if(euler.isFinite()) {output[i*12]=euler.x;output[i*12+1]=euler.y;output[i*12+2]=euler.z;}
            }
            actual[i]=RenderUtils.prepMatrixForBone(definition.bones.get(i),new Matrix4f(before),output,i*12);
            if(joint!=null) {
                actual[i].transformPosition(definition.pivot(i),positions[i]);
                if(joint.parent()<0) velocities[i].set(frameVelocity);
                for(int end:joint.outputs()) {
                    Matrix4f child=RenderUtils.prepMatrixForBone(definition.bones.get(end),new Matrix4f(actual[i]),base,end*12);
                    child.transformPosition(definition.pivot(end),positions[end]);
                    Vector3f direction=new Vector3f(positions[end]).sub(positions[i]);
                    if(direction.lengthSquared()>1e-12f) {
                        direction.normalize();
                        float radial=new Vector3f(velocities[end]).sub(velocities[i]).dot(direction);
                        velocities[end].fma(-radial,direction);
                    }
                    if(!positions[end].isFinite()) {positions[end].set(targets[end]);velocities[end].zero();}
                }
            }
        }
    }

    public Vector3f position(int bone) {return new Vector3f(positions[bone]);}
    int stepsInUpdate() {return stepsInUpdate;}
    boolean sampledFrame() {return sampledFrame;}

    private List<PhysCollider> contacts(PhysDefinition.Joint joint,AABB bounds) {
        var cached=blockContacts.get(joint.bone());
        if(cached!=null && contains(cached.bounds,bounds)) return cached.contacts;
        // Cache per joint and simulation frame. Enclose all candidate rotations, not just the free tip.
        AABB query=(cached==null?bounds:cached.bounds.minmax(bounds)).inflate(.01);
        if(!Double.isFinite(query.getSize())) return colliders;
        var result=new ArrayList<PhysCollider>();
        for(var collider:colliders) if(collider.overlaps(query)) result.add(collider);
        if(joint.parameters().bc() && blockCollisions!=null) for(VoxelShape shape:blockCollisions.apply(query.move(originX,originY,originZ)))
            for(AABB box:shape.toAabbs()) if(box.getXsize()>0 && box.getYsize()>0 && box.getZsize()>0)
                result.add(new PhysCollider(box.move(-originX,-originY,-originZ)));
        blockContacts.put(joint.bone(),new BlockContacts(query,result));
        return result;
    }

    private static boolean contains(AABB outer,AABB inner) {
        return outer.minX<=inner.minX && outer.minY<=inner.minY && outer.minZ<=inner.minZ
                && outer.maxX>=inner.maxX && outer.maxY>=inner.maxY && outer.maxZ>=inner.maxZ;
    }

    private boolean clearPose(List<Vector3f> sources, Quaternionf rotation, Matrix4f parent, Vector3f pivot, float radius,List<PhysCollider> contacts) {
        for(Vector3f source:sources) {
            Vector3f end=parent.transformDirection(new Vector3f(source).rotate(rotation)).add(pivot);
            for(PhysCollider collider:contacts)
                if(collider.resolveSegment(new Vector3f(pivot),new Vector3f(end),new Vector3f(),new Vector3f(),radius,true)) return false;
        }
        return true;
    }
}
