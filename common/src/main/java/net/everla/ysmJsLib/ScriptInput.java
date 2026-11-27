package net.everla.ysmJsLib;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Non-consuming event sampler. Edges remain visible for one client tick. */
public final class ScriptInput {
    public record State(boolean down,boolean pressed,boolean released) {
        public Map<String,Object> values(){return Map.of("down",down,"pressed",pressed,"released",released);}
    }
    private static final Map<String,State> pending=new HashMap<>(),current=new HashMap<>();
    private static final Map<String,Boolean> bindings=new HashMap<>();
    private static final Map<String,Set<String>> heldBindings=new HashMap<>();
    private static final Set<String> missing=new HashSet<>();
    public static long tick;
    public static void event(String name,int action) {
        State old=pending.getOrDefault(name,new State(false,false,false));boolean down=action!=0;
        pending.put(name,new State(down,old.pressed||down&&!old.down,old.released||!down&&old.down));
    }
    public static void key(int key,int scan,int action) {
        event(InputConstants.getKey(key,scan).getName(),action);bindingEvent(key,scan,-1,action);
    }
    public static void mouse(int button,int action) {
        event(InputConstants.Type.MOUSE.getOrCreate(button).getName(),action);bindingEvent(0,0,button,action);
    }
    private static void bindingEvent(int key,int scan,int mouse,int action) {
        String source=mouse>=0?"mouse:"+mouse:"key:"+key+":"+scan;
        if(action==0) {
            var held=heldBindings.remove(source);
            if(held!=null)held.forEach(name->event("binding:"+name,0));
            return;
        }
        if(action!=1)return;
        Set<String> matched=new HashSet<>();
        for(var mapping:Minecraft.getInstance().options.keyMappings)if(ScriptKeys.matches(mapping,key,scan,mouse)) {
            matched.add(mapping.getName());event("binding:"+mapping.getName(),action);
        }
        heldBindings.put(source,matched);
    }
    public static void advance(boolean active) {
        tick++;current.clear();current.putAll(pending);
        pending.replaceAll((k,v)->new State(active&&v.down,false,false));
        if(!active)current.replaceAll((k,v)->new State(false,false,v.down||v.released));
    }
    public static void tick(Minecraft mc) {
        advance(mc.isWindowActive());
        if(!mc.isWindowActive())heldBindings.clear();
        for(var key:mc.options.keyMappings) {
            boolean down=mc.isWindowActive()&&key.isDown(),before=bindings.getOrDefault(key.getName(),false);
            var edge=current.getOrDefault("binding:"+key.getName(),new State(false,false,false));
            current.put("binding:"+key.getName(),new State(down,edge.pressed||down&&!before,edge.released||!down&&before));bindings.put(key.getName(),down);
        }
        if(mc.level==null){bindings.clear();pending.clear();current.clear();heldBindings.clear();missing.clear();}
    }
    public static State get(String id,boolean binding,boolean local) {
        if(!local)return new State(false,false,false);
        String key=(binding?"binding:":"")+id;State value=current.get(key);
        if(value==null&&binding&&missing.add(id))org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Missing key binding: {}",id);
        return value==null?new State(false,false,false):value;
    }
}
