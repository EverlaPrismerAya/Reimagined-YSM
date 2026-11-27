package net.everla.ysmJsLib;

import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;
import java.util.*;

/** Literal registration metadata can be inspected on the server without running model code. */
public final class ScriptDeclarations {
    public record Entry(double fallback, boolean sync) {}
    /** Only a direct registration in the literal molang event has server-readable metadata. */
    private static boolean isMolangRegistration(FunctionCall call) {
        if(call.getTarget().toSource().equals("Rysm.molang"))return true;
        if(!(call.getTarget() instanceof PropertyGet property) || !property.getProperty().getIdentifier().equals("register") || !(property.getTarget() instanceof Name target))return false;
        var fn=call.getEnclosingFunction();
        if(fn==null || fn.getParams().size()!=1 || !(fn.getParams().get(0) instanceof Name parameter) || !parameter.getIdentifier().equals(target.getIdentifier()))return false;
        if(!(fn.getParent() instanceof FunctionCall owner) || !owner.getTarget().toSource().equals("Rysm.model"))return false;
        var args=owner.getArguments();
        return args.size()==2 && args.get(0) instanceof StringLiteral event && event.getValue().equals("molang") && args.get(1)==fn;
    }
    public static Map<String,Entry> read(ScriptBundle bundle) {
        Map<String,Entry> entries=new LinkedHashMap<>();
        var scripts=bundle.manifest().getAsJsonArray("scripts");
        if(scripts==null || scripts.size()>64)throw new IllegalArgumentException("Expected at most 64 script entries");
        for(var script:scripts) {
            String path="rysm/"+script.getAsString();
            try{ScriptBundle.checkPath(path);}catch(java.io.IOException e){throw new IllegalArgumentException(e);}
            var env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);
            new Parser(env).parse(bundle.text(path),path,1).visit(node->{
                if(!(node instanceof FunctionCall call) || !isMolangRegistration(call))return true;
                var args=call.getArguments();
                if(args.size()<2 || args.size()>3 || !(args.get(0) instanceof StringLiteral literal))throw new IllegalArgumentException(path+":"+node.getLineno()+": Molang requires a literal name");
                String name=literal.getValue();double fallback=0;boolean sync=false;
                if(!name.matches("[a-z_][a-z0-9_]{0,255}"))throw new IllegalArgumentException("Invalid Molang name: "+name);
                if(args.size()==3) {
                    if(!(args.get(2) instanceof ObjectLiteral options))throw new IllegalArgumentException("Molang options must be literal");
                    for(var property:options.getElements()) {
                        String key=property.getLeft() instanceof StringLiteral s?s.getValue():property.getLeft().toSource();
                        String value=property.getRight().toSource();
                        if(key.equals("fallback")) {
                            try{fallback=value.equals("true")?1:value.equals("false")?0:Double.parseDouble(value);}
                            catch(NumberFormatException e){throw new IllegalArgumentException("fallback must be a numeric or boolean literal");}
                        }else if(key.equals("sync")) {
                            if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("sync must be a boolean literal");
                            sync=value.equals("true");
                        }else throw new IllegalArgumentException("Unknown Molang option: "+key);
                    }
                }
                if(!Double.isFinite(fallback))throw new IllegalArgumentException("Non-finite fallback");
                if(sync && (!(args.get(1) instanceof FunctionNode fn) || fn.getParams().size()>1))throw new IllegalArgumentException("Synchronized Molang requires a callback with only ctx");
                if(entries.putIfAbsent(name,new Entry(fallback,sync))!=null || entries.size()>256)throw new IllegalArgumentException("Duplicate or excessive Molang registration: "+name);
                return true;
            });
        }
        return Map.copyOf(entries);
    }
}
