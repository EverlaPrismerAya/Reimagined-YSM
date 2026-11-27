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
    public final ScriptRuntime runtime;
    private final AnimatedGeoModel model;
    private long tick=Long.MIN_VALUE;
    private long printTick=Long.MIN_VALUE;
    private int printed;
    private IContext<?> lastContext;
    private final Map<String,Double> synchronizedValues=new LinkedHashMap<>();
    private final Map<IBone,float[]> saved=new IdentityHashMap<>();
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
        var instance=model.rysmScript;
        instance.saved.forEach((b,v)->{b.setPositionX(v[0]);b.setPositionY(v[1]);b.setPositionZ(v[2]);b.setRotationX(v[3]);b.setRotationY(v[4]);b.setRotationZ(v[5]);b.setScaleX(v[6]);b.setScaleY(v[7]);b.setScaleZ(v[8]);});
        instance.saved.clear();
    }
    public double value(String name) {
        return lastContext==null?0:evaluate(name,new Object[0],lastContext);
    }
    public double evaluate(String name,Object[] args,IContext<?> ctx) {
        var declaration=runtime.molang.get(name);
        if(declaration==null)return 0;
        if(!declaration.sync())return runtime.evaluate(name,args,cx->context(ctx,false));
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
            i.tick=ScriptInput.tick;i.synchronizedValues.clear();i.runtime.callbacks(false,cx->i.context(context,false));
            if(context.entity()==Minecraft.getInstance().player && context.geoInstance() instanceof com.elfmcys.yesstevemodel.capability.PlayerCapability cap && cap.getModelAssembly()!=null) {
                i.runtime.molang.forEach((name,entry)->{if(entry.sync())i.evaluate(name,new Object[0],context);});
                if(!i.synchronizedValues.isEmpty())ScriptSync.Client.send(cap.getModelId(),cap.getModelAssembly().getModelData().getModelHash(),i.runtime.bundle.digest,i.synchronizedValues);
            }
        }
    }
    public static void animate(IContext<?> context) {
        var i=get(context.geoInstance().getCurrentModel());if(i==null)return;
        begin(context);
        i.runtime.callbacks(true,cx->i.context(context,true));
    }
    public Scriptable context(IContext<?> ctx,boolean pose) {
        var mc=Minecraft.getInstance();Entity e=ctx.entity() instanceof Entity entity?entity:null;
        boolean local=e!=null&&e==mc.player;
        var entity=new HashMap<String,Object>();
        entity.put("health",e instanceof LivingEntity living?(double)living.getHealth():0.0);
        entity.put("maxHealth",e instanceof LivingEntity living?(double)living.getMaxHealth():0.0);
        entity.put("position",Map.of("x",e==null?0:e.getX(),"y",e==null?0:e.getY(),"z",e==null?0:e.getZ()));
        var velocity=e==null?net.minecraft.world.phys.Vec3.ZERO:e.getDeltaMovement();
        entity.put("velocity",Map.of("x",velocity.x,"y",velocity.y,"z",velocity.z));
        entity.put("pose",e==null?"preview":e.getPose().name().toLowerCase(Locale.ROOT));
        entity.put("yaw",e==null?0:e.getYRot());entity.put("pitch",e==null?0:e.getXRot());entity.put("local",local);
        var input=runtime.object(Context.getCurrentContext());
        runtime.define(input,"key",args->runtime.snapshot(ScriptInput.get(Context.toString(args[0]),false,local).values()));
        runtime.define(input,"binding",args->runtime.snapshot(ScriptInput.get(Context.toString(args[0]),true,local).values()));runtime.define(input,"bindings",args->{
            String filter=args.length==0?"":Context.toString(args[0]);
            Object[] bindings=ScriptKeyLogger.bindings(filter).stream().map(runtime::snapshot).toArray();
            return Context.getCurrentContext().newArray(input,bindings);
        });input.sealObject();
        double ticks=e==null?ScriptInput.tick:e.tickCount;ticks+=ctx.animationEvent().getFrameTime();
        var camera=mc.gameRenderer.getMainCamera();
        var out=runtime.game(message->{
            if(local) {
                if(printTick!=ScriptInput.tick){printTick=ScriptInput.tick;printed=0;}
                if(printed++<16)mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(message));
            }
        });
        ScriptableObject.putProperty(out,"entity",runtime.snapshot(entity));ScriptableObject.putProperty(out,"input",input);
        ScriptableObject.putProperty(out,"time",runtime.snapshot(Map.of("ticks",ticks,"seconds",ticks/20)));
        ScriptableObject.putProperty(out,"camera",runtime.snapshot(Map.of("yaw",camera.getYRot(),"pitch",camera.getXRot())));
        runtime.define(out,"molang",args->{
            if(args.length!=1)throw new IllegalArgumentException("molang expects one query string");
            String text=Context.toString(args[0]);if(text.length()>4096||expressions.size()>256)throw new IllegalArgumentException("Molang bridge limit");
            if(!text.matches("[A-Za-z_][A-Za-z0-9_.]*(?:\\([^;{}]*\\))?"))throw new IllegalArgumentException("Only read-only Molang queries are permitted");
            if(!text.matches("(?:q|query|math|v|variable|c|context|rysmjs)\\..*") || text.matches("(?s).*(?:[=;{}]|\\+\\+|--|\\b(?:fn|ctrl|loop|for_each)\\b).*"))throw new IllegalArgumentException("Mutating Molang namespaces are unavailable");
            var value=expressions.computeIfAbsent(text,s->GeckoLibCache.getMolangParser().parseExpression(s,false));
            return (double)value.evalAsFloat(ExpressionEvaluator.evaluator(ctx));
        });
        runtime.define(out,"group",args->{
            if(!pose)throw new IllegalArgumentException("Group transforms are only available in Rysm.model('pose', callback)");
            IBone bone=model.bones().get(StringPool.computeIfAbsent(Context.toString(args[0])));
            if(bone==null)throw new IllegalArgumentException("Unknown group: "+Context.toString(args[0]));
            var group=runtime.object(Context.getCurrentContext());
            for(String op:List.of("move","rotate","scale"))runtime.define(group,op,values->{
                if(values.length!=3)throw new IllegalArgumentException("Expected x, y, z");
                float x=(float)ScriptRuntime.finite(values[0]),y=(float)ScriptRuntime.finite(values[1]),z=(float)ScriptRuntime.finite(values[2]);
                if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IllegalArgumentException("Transform overflow");
                saved.computeIfAbsent(bone,b->new float[]{b.getPositionX(),b.getPositionY(),b.getPositionZ(),b.getRotationX(),b.getRotationY(),b.getRotationZ(),b.getScaleX(),b.getScaleY(),b.getScaleZ()});
                switch(op) {
                    case "move"->{bone.setPositionX(bone.getPositionX()+x);bone.setPositionY(bone.getPositionY()+y);bone.setPositionZ(bone.getPositionZ()+z);}
                    case "rotate"->{bone.setRotationX(bone.getRotationX()+(float)Math.toRadians(x));bone.setRotationY(bone.getRotationY()+(float)Math.toRadians(y));bone.setRotationZ(bone.getRotationZ()+(float)Math.toRadians(z));}
                    case "scale"->{bone.setScaleX(bone.getScaleX()*x);bone.setScaleY(bone.getScaleY()*y);bone.setScaleZ(bone.getScaleZ()*z);}
                }return group;
            });group.sealObject();return group;
        });out.sealObject();return out;
    }
}
