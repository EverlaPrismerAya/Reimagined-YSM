package net.everla.ysmJsLib;
import com.elfmcys.yesstevemodel.molang.runtime.*;
import com.elfmcys.yesstevemodel.molang.runtime.binding.ObjectBinding;
import com.elfmcys.yesstevemodel.geckolib3.core.molang.context.IContext;
/** Name resolution is deferred until evaluation, so parsed animations can be shared. */
public final class ScriptBinding implements ObjectBinding {
    public Object getProperty(String name) {
        return new ScriptValue(name);
    }
    private record ScriptValue(String name) implements Function,Variable {
        public Object evaluate(ExecutionContext<?> context){return evaluate(context,Function.EMPTY_ARGUMENT);}
        public Object evaluate(ExecutionContext<?> context,Function.ArgumentCollection args) {
            if(!(context.entity() instanceof IContext<?> ctx))return 0.0;
            var model=ctx.geoInstance().getCurrentModel();var i=ScriptInstance.get(model);
            if(i==null){var scripts=model==null?null:model.getGeoModel().scripts;var entry=scripts==null?null:scripts.declarations.get(name);return entry==null?0.0:entry.fallback();}
            if(args.size()>32)return 0.0;Object[] values=new Object[args.size()];
            for(int n=0;n<values.length;n++)values[n]=args.getAsDouble(context,n);
            return i.evaluate(name,values,ctx);
        }
    }
}
