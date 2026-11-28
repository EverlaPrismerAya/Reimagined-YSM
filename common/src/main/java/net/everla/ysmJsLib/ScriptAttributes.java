package net.everla.ysmJsLib;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.mozilla.javascript.*;
import java.util.List;

/** Read attribute values without exposing mutable entity or attribute objects. */
final class ScriptAttributes {
    static void bind(ScriptRuntime runtime,ScriptableObject view,LivingEntity entity){bindLive(runtime,view,()->entity);}
    static void bindLive(ScriptRuntime runtime, ScriptableObject view, java.util.function.Supplier<LivingEntity> source) {
        for(String method:List.of("attribute","attributeBase"))runtime.define(view,method,args->{
            if(args.length<1 || args.length>2)throw new IllegalArgumentException("Expected attribute ID and optional fallback");
            double fallback=args.length==2?ScriptRuntime.finite(args[1]):0;
            LivingEntity entity=source.get();if(entity==null)return fallback;
            var id=ResourceLocation.tryParse(Context.toString(args[0]));
            var attribute=id==null?null:BuiltInRegistries.ATTRIBUTE.getOptional(id).orElse(null);
            var instance=attribute==null?null:entity.getAttribute(attribute);
            return instance==null?fallback:method.equals("attributeBase")?instance.getBaseValue():instance.getValue();
        });
    }
}
