package net.everla.ysmJsLib;

import com.google.gson.*;
import net.everla.reimaginedysm.gpu.GroupStyle;
import java.util.*;

/** Registration snapshot, shared by a model's meshes. */
public final class ScriptModel {
    public static final ThreadLocal<ScriptModel> BUILDING=new ThreadLocal<>();
    public final ScriptBundle bundle;
    public final boolean logKeyBindingsRequested;
    public final Map<String,JsonObject> groups,cosmic;
    public final Map<String,ScriptDeclarations.Entry> declarations;
    public ScriptModel(ScriptBundle bundle) {
        this.bundle=bundle;var runtime=new ScriptRuntime(bundle);
        declarations=ScriptDeclarations.read(bundle);
        logKeyBindingsRequested=runtime.logKeyBindingsRequested;
        groups=Map.copyOf(runtime.groups);cosmic=Map.copyOf(runtime.cosmic);
        for(var entry:groups.entrySet()) {
            var properties=entry.getValue();
            if(java.util.stream.Stream.of("physics","end","collider").filter(k->properties.has(k)&&!properties.get(k).equals(new JsonPrimitive(false))).count()>1)
                throw new IllegalArgumentException("Physics, endpoint and collider roles are mutually exclusive: "+entry.getKey());
            if(!Set.of("physics","end","collider","glow","item","facingCamera","cosmic","outline","frameAnimation").containsAll(entry.getValue().keySet()))throw new IllegalArgumentException("Unknown group property: "+entry.getKey());
            String name=entry.getKey(),physical=physicsName(name);
            if(net.everla.ysmPhysLib.PhysParameters.isCandidate(physical))net.everla.ysmPhysLib.PhysParameters.parse(physical,physical.startsWith("ysmTail")?net.everla.ysmPhysLib.PhysParameters.TAIL_DEFAULT:net.everla.ysmPhysLib.PhysParameters.DEFAULT);
            style(name,GroupStyle.NONE);
            String frame=encoded(name,"frameAnimation","FrameAnimation");if(frame.startsWith("FrameAnimation"))net.everla.reimaginedysm.gpu.FrameAnimation.parse(frame);
        }
    }
    public JsonObject group(String name) {return groups.getOrDefault(name,new JsonObject());}
    public boolean flag(String name,String key) {var p=group(name);return p.has(key)&&p.get(key).getAsBoolean();}
    public String encoded(String name,String key,String prefix) {
        var p=group(name);if(!p.has(key))return name;
        var value=p.get(key);if(value.isJsonPrimitive())return value.getAsBoolean()?prefix+"@script":name;
        var obj=value.getAsJsonObject();var entries=new ArrayList<String>();
        for(var e:obj.entrySet()) {
            if(e.getKey().equals("kind"))continue;
            entries.add(e.getKey()+"="+e.getValue().getAsString());
        }
        return prefix+(entries.isEmpty()?"":"#"+String.join(",",entries))+"@script";
    }
    public String physicsName(String name) {
        var p=group(name);
        if(p.has("end")&&p.get("end").getAsBoolean())return "ysmPhysEnd@script";
        if(p.has("collider")&&p.get("collider").getAsBoolean())return "ysmPhysC@script";
        String prefix=p.has("physics")&&p.get("physics").isJsonObject()&&p.getAsJsonObject("physics").has("kind")&&
                p.getAsJsonObject("physics").get("kind").getAsString().equals("tail")?"ysmTail":"ysmPhys";
        return encoded(name,"physics",prefix);
    }
    public GroupStyle style(String name,GroupStyle parent) {
        var p=group(name);GroupStyle style=parent;
        for(String key:List.of("cosmic","outline"))if(p.has(key)) {
            if(p.get(key).isJsonPrimitive()&&!p.get(key).getAsBoolean()) {
                style=new GroupStyle(key.equals("cosmic")?false:style.cosmic(),style.spaceColor(),style.spaceAlpha(),style.astralColor(),style.astralAlpha(),key.equals("outline")?false:style.outline(),style.outlineColor(),style.outlineWidth());continue;
            }
            var obj=p.get(key).isJsonObject()?p.getAsJsonObject(key).deepCopy():new JsonObject();obj.remove("effect");
            String prefix=key.equals("cosmic")?"ysmCosmic":"ysmOutline";
            var entries=new ArrayList<String>();for(var e:obj.entrySet())entries.add(e.getKey()+"="+e.getValue().getAsString());
            style=GroupStyle.parse(prefix+(entries.isEmpty()?"":"#"+String.join(",",entries))+"@script",style);
        }
        return style;
    }
    public String effect(String name,String inherited) {
        var p=group(name);if(!p.has("cosmic"))return inherited;
        var cosmic=p.get("cosmic");
        return cosmic.isJsonObject()&&cosmic.getAsJsonObject().has("effect")?cosmic.getAsJsonObject().get("effect").getAsString():"";
    }
}
