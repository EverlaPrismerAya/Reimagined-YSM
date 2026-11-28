package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.context.IContext;
import com.elfmcys.yesstevemodel.geckolib3.core.processor.IBone;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.util.StringPool;
import com.elfmcys.yesstevemodel.geckolib3.resource.GeckoLibCache;
import com.elfmcys.yesstevemodel.molang.runtime.ExpressionEvaluator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.mozilla.javascript.*;
import java.util.*;

public final class ScriptInstance {
    private static final java.util.regex.Pattern QUERY=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*(?:\\([^;{}]*\\))?");
    private static final java.util.regex.Pattern NAMESPACE=java.util.regex.Pattern.compile("(?:q|query|math|v|variable|c|context|rysmjs)\\..*");
    private static final java.util.regex.Pattern MUTATING=java.util.regex.Pattern.compile("(?s).*(?:[=;{}]|\\+\\+|--|\\b(?:fn|ctrl|loop|for_each)\\b).*");
    public final ScriptRuntime runtime;
    private final AnimatedGeoModel model;
    private long tick=Long.MIN_VALUE;
    private long printTick=Long.MIN_VALUE;
    private int printed;
    private IContext<?> lastContext;
    private ScriptableObject tickContext,poseContext;
    private final Map<String,Double> synchronizedValues=new LinkedHashMap<>();
    private final PoseCache poses=new PoseCache();
    private boolean interpolatePose;
    private final Map<String,ScriptableObject> groupObjects=new HashMap<>();
    private final Map<String,com.elfmcys.yesstevemodel.geckolib3.core.molang.value.IValue> expressions=new HashMap<>();
    public ScriptInstance(AnimatedGeoModel model) {this.model=model;runtime=new ScriptRuntime(model.getGeoModel().scripts.bundle);}
    public static ScriptInstance get(AnimatedGeoModel model) {
        if(model==null||model.getGeoModel().scripts==null||model.rysmScriptFailed)return null;
        if(model.rysmScript==null)try{model.rysmScript=new ScriptInstance(model);}
        catch(ScriptRuntime.BudgetExceeded|RuntimeException|LinkageError e){model.rysmScriptFailed=true;org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Cannot instantiate model script {}",model.getGeoModel().scripts.bundle.digest,e);}
        return model.rysmScript;
    }
    public static void restore(AnimatedGeoModel model) {
        if(model==null||model.rysmScript==null)return;
        model.rysmScript.poses.restore();
    }
    public double value(String name) {
        return lastContext==null?0:evaluate(name,new Object[0],lastContext);
    }
    public double evaluate(String name,Object[] args,IContext<?> ctx) {
        var declaration=runtime.molang.get(name);
        if(declaration==null)return 0;
        if(!declaration.sync())return runtime.evaluateQuery(name,args,ctx.entity()==Minecraft.getInstance().player,cx->context(ctx,false));
        if(args.length!=0)return declaration.fallback();
        var mc=Minecraft.getInstance();
        if(ctx.entity()!=mc.player) {
            if(ctx.entity() instanceof Entity entity && ctx.geoInstance() instanceof com.elfmcys.yesstevemodel.capability.PlayerCapability cap && cap.getModelAssembly()!=null)
                return ScriptSync.Client.value(entity.getUUID(),cap.getModelId(),cap.getModelAssembly().getModelData().getModelHash(),runtime.bundle.digest,name,declaration.fallback());
            return declaration.fallback();
        }
        Double cached=synchronizedValues.get(name);if(cached!=null)return cached;
        double value=runtime.evaluate(name,args,cx->context(ctx,false));synchronizedValues.put(name,value);return value;
    }
    /** Runs even when the local player is not visible in first person. */
    public static void clientTick(Minecraft mc) {
        if(mc.player==null){ScriptSync.Client.clearLocal();return;}
        com.elfmcys.yesstevemodel.capability.PlayerCapability.get(mc.player).ifPresent(cap->{
            if(com.elfmcys.yesstevemodel.client.ClientModelManager.isPrivateSelection())ScriptSync.Client.clearLocal();
            var i=get(cap.getCurrentModel());if(i!=null)cap.processAnimation(0);else ScriptSync.Client.clearLocal();
        });
    }
    public static void begin(IContext<?> context) {
        var i=get(context.geoInstance().getCurrentModel());if(i==null)return;i.lastContext=context;
        if(i.tick!=ScriptInput.tick){
            i.tick=ScriptInput.tick;i.synchronizedValues.clear();
            i.runtime.tick(context.entity()==Minecraft.getInstance().player,cx->i.context(context,false));
            if(context.entity()==Minecraft.getInstance().player && context.geoInstance() instanceof com.elfmcys.yesstevemodel.capability.PlayerCapability cap && cap.getModelAssembly()!=null) {
                i.runtime.molang.forEach((name,entry)->{if(entry.sync())i.evaluate(name,new Object[0],context);});
                if(!i.synchronizedValues.isEmpty())ScriptSync.Client.send(cap.getModelId(),cap.getModelAssembly().getModelData().getModelHash(),i.runtime.bundle.digest,i.synchronizedValues);
            }
        }
    }
    public static void animate(IContext<?> context) {
        var i=get(context.geoInstance().getCurrentModel());if(i==null)return;
        begin(context);
        Object world=context.entity() instanceof Entity e?e.level():null;
        if(i.poses.sample(ScriptInput.tick,world)) {
            i.runtime.poses(cx->i.context(context,true),interpolate->i.interpolatePose=interpolate);i.poses.finish();
            net.everla.reimaginedysm.gpu.RenderMetrics.add("poseSamples",1);
        }
        i.poses.apply(context.animationEvent().getFrameTime());
    }
    private Entity entity(){return lastContext!=null&&lastContext.entity() instanceof Entity e?e:null;}
    private boolean local(){return entity()!=null&&entity()==Minecraft.getInstance().player;}
    private ScriptableObject vector(java.util.function.Supplier<net.minecraft.world.phys.Vec3> supplier) {
        var object=runtime.object(Context.getCurrentContext());
        runtime.readonly(object,"x",()->supplier.get().x);runtime.readonly(object,"y",()->supplier.get().y);runtime.readonly(object,"z",()->supplier.get().z);object.sealObject();return object;
    }
    public Scriptable context(IContext<?> ctx,boolean pose) {
        lastContext=ctx;
        ScriptableObject cached=pose?poseContext:tickContext;if(cached!=null)return cached;
        var mc=Minecraft.getInstance();
        var entity=runtime.object(Context.getCurrentContext());
        runtime.readonly(entity,"health",()->entity() instanceof LivingEntity living?(double)living.getHealth():0.0);
        runtime.readonly(entity,"maxHealth",()->entity() instanceof LivingEntity living?(double)living.getMaxHealth():0.0);
        ScriptableObject.defineProperty(entity,"position",vector(()->entity()==null?net.minecraft.world.phys.Vec3.ZERO:entity().position()),ScriptableObject.READONLY|ScriptableObject.PERMANENT);
        ScriptableObject.defineProperty(entity,"velocity",vector(()->entity()==null?net.minecraft.world.phys.Vec3.ZERO:entity().getDeltaMovement()),ScriptableObject.READONLY|ScriptableObject.PERMANENT);
        runtime.readonly(entity,"pose",()->entity()==null?"preview":entity().getPose().name().toLowerCase(Locale.ROOT));
        runtime.readonly(entity,"yaw",()->entity()==null?0:entity().getYRot());runtime.readonly(entity,"pitch",()->entity()==null?0:entity().getXRot());runtime.readonly(entity,"local",this::local);
        ScriptAttributes.bindLive(runtime,entity,()->entity() instanceof LivingEntity living?living:null);entity.sealObject();
        var input=runtime.object(Context.getCurrentContext());
        runtime.define(input,"key",args->runtime.snapshot(ScriptInput.get(Context.toString(args[0]),false,local()).values()));
        runtime.define(input,"binding",args->runtime.snapshot(ScriptInput.get(Context.toString(args[0]),true,local()).values()));
        runtime.define(input,"bindings",args->{String filter=args.length==0?"":Context.toString(args[0]);Object[] bindings=ScriptKeyLogger.bindings(filter).stream().map(runtime::snapshot).toArray();return Context.getCurrentContext().newArray(input,bindings);});input.sealObject();
        var time=runtime.object(Context.getCurrentContext());
        java.util.function.Supplier<Object> ticks=()->(entity()==null?(double)ScriptInput.tick:(double)entity().tickCount)+(pose?0:lastContext.animationEvent().getFrameTime());
        runtime.readonly(time,"ticks",ticks);runtime.readonly(time,"seconds",()->((Number)ticks.get()).doubleValue()/20);time.sealObject();
        var camera=runtime.object(Context.getCurrentContext());runtime.readonly(camera,"yaw",()->mc.gameRenderer.getMainCamera().getYRot());runtime.readonly(camera,"pitch",()->mc.gameRenderer.getMainCamera().getXRot());camera.sealObject();
        var out=runtime.game(message->{if(local()){if(printTick!=ScriptInput.tick){printTick=ScriptInput.tick;printed=0;}if(printed++<16)mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(message));}});
        ScriptableObject.putProperty(out,"entity",entity);ScriptableObject.putProperty(out,"input",input);ScriptableObject.putProperty(out,"time",time);ScriptableObject.putProperty(out,"camera",camera);
        runtime.define(out,"molang",args->{
            if(args.length!=1)throw new IllegalArgumentException("molang expects one query string");
            String text=Context.toString(args[0]);if(text.length()>4096)throw new IllegalArgumentException("Molang bridge limit");
            var value=expressions.computeIfAbsent(text,s->{
                if(expressions.size()>=256)throw new IllegalArgumentException("Molang bridge limit");
                if(!QUERY.matcher(s).matches() || !NAMESPACE.matcher(s).matches() || MUTATING.matcher(s).matches())throw new IllegalArgumentException("Only read-only Molang queries are permitted");
                return GeckoLibCache.getMolangParser().parseExpression(s,false);
            });return (double)value.evalAsFloat(ExpressionEvaluator.evaluator(lastContext));
        });
        runtime.define(out,"group",args->{
            if(!pose)throw new IllegalArgumentException("Group transforms are only available in Rysm.model('pose', callback)");
            if(args.length!=1)throw new IllegalArgumentException("Expected one qualified group name");
            String target=Context.toString(args[0]);if(target.length()>256)throw new IllegalArgumentException("Group name too long");
            var existing=groupObjects.get(target);if(existing!=null)return existing;
            String localName=model.getGeoModel().scripts.localGroupName(target);IBone bone=localName==null?null:model.bones().get(StringPool.computeIfAbsent(localName));
            if(localName!=null&&bone==null)throw new IllegalArgumentException("Unknown group: "+target);
            var group=runtime.object(Context.getCurrentContext());
            for(String op:List.of("move","rotate","scale"))runtime.define(group,op,values->{
                if(values.length!=3)throw new IllegalArgumentException("Expected x, y, z");
                float x=(float)ScriptRuntime.finite(values[0]),y=(float)ScriptRuntime.finite(values[1]),z=(float)ScriptRuntime.finite(values[2]);
                if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IllegalArgumentException("Transform overflow");
                if(bone!=null)poses.record(bone,op,x,y,z,interpolatePose);return group;
            });group.sealObject();if(groupObjects.size()<4096)groupObjects.put(target,group);return group;
        });out.sealObject();if(pose)poseContext=out;else tickContext=out;return out;
    }
}
