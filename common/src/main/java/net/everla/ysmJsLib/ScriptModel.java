package net.everla.ysmJsLib;

import com.google.gson.*;
import net.everla.reimaginedysm.gpu.GroupStyle;
import java.util.*;

/** Registration snapshot, shared by a model's meshes. */
public final class ScriptModel {
    public static final ThreadLocal<ScriptModel> BUILDING=new ThreadLocal<>();
    public final ScriptBundle bundle;
    public final String scope;
    private final JsonObject scopes;
    public final boolean logKeyBindingsRequested;
    public final Map<String,JsonObject> groups,cosmic;
    public final Map<String,ScriptDeclarations.Entry> declarations;
    public ScriptModel(ScriptBundle bundle) {
        this.bundle=bundle;scope="";
        scopes=bundle.files.containsKey("rysm/model-scopes.json")?JsonParser.parseString(bundle.text("rysm/model-scopes.json")).getAsJsonObject():new JsonObject();
        for(var value:scopes.entrySet()) {
            String name=value.getValue().getAsString();
            if(name.length()>256||name.contains("/")||name.contains("\\")||name.contains(".."))throw new IllegalArgumentException("Invalid model scope");
        }
        var runtime=new ScriptRuntime(bundle);
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
    private ScriptModel(ScriptModel source,String scope) {
        bundle=source.bundle;groups=source.groups;cosmic=source.cosmic;declarations=source.declarations;
        logKeyBindingsRequested=source.logKeyBindingsRequested;scopes=source.scopes;this.scope=scope;
    }
    public ScriptModel forModel(String role) {
        String prefix=role.equals("player.main")?"":scopes.has(role)?scopes.get(role).getAsString():role.equals("player.arm")?"arm":role;
        return new ScriptModel(this,prefix);
    }
    /** Out-of-scope pose targets are ignored while the same callback visits another mesh. */
    public String localGroupName(String target) {
        if(!scope.isEmpty())return target.startsWith(scope+".")?target.substring(scope.length()+1):null;
        for(var value:scopes.entrySet()) {String prefix=value.getValue().getAsString();if(!prefix.isEmpty()&&target.startsWith(prefix+"."))return null;}
        return target;
    }
    public JsonObject group(String name) {return groups.getOrDefault(scope.isEmpty()?name:scope+"."+name,new JsonObject());}
    public boolean flag(String name,String key) {var p=group(name);return p.has(key)&&p.get(key).getAsBoolean();}
    public String encoded(String name,String key,String prefix) {
        var p=group(name);if(!p.has(key))return "";
        var value=p.get(key);if(value.isJsonPrimitive())return value.getAsBoolean()?prefix+"@script":"";
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
        if(p.has("collider")&&p.get("collider").getAsBoolean())return "";
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
