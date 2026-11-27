package net.everla.ysmJsLib;

import com.google.gson.*;
import org.mozilla.javascript.*;
import java.util.*;
import java.util.function.BiFunction;

/** Each instance owns its JS scope; Java objects are never wrapped for script access. */
public final class ScriptRuntime {
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
    private static long allocated(){return ALLOCATIONS==null?0:ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId());}
    private static final ContextFactory FACTORY = new ContextFactory() {
        protected Context makeContext() {
            Context cx=super.makeContext();cx.setOptimizationLevel(-1);cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(1);cx.setClassShutter(name -> false);cx.setMaximumInterpreterStackDepth(128);
            return cx;
        }
        protected void observeInstructionCount(Context cx,int count) {
            int left=(int)cx.getThreadLocal(ScriptRuntime.class)-count;
            cx.putThreadLocal(ScriptRuntime.class,left);
            if(left<0 || System.nanoTime()>(long)cx.getThreadLocal("deadline") || allocated()-(long)cx.getThreadLocal("allocated")>(long)cx.getThreadLocal("allocationLimit"))throw new BudgetExceeded();
        }
    };
    private static final String LIMITS = """
        (function(global) {
            const A = Array;
            const limit = 65536;
            const textLimit = 1048576;
            function fail() { throw new RangeError('RYSM script data limit exceeded'); }
            function array() {
                if(arguments.length === 1 && typeof arguments[0] === 'number' && arguments[0] > limit) fail();
                if(arguments.length > limit) fail();
                return A.apply(null, arguments);
            }
            array.prototype = A.prototype;
            array.isArray = A.isArray;
            global.Array = array;
            A.prototype.constructor = array;
            ['push','unshift','splice','concat','fill','map','filter','slice','join','sort','reverse','reduce','reduceRight','forEach','every','some','indexOf','lastIndexOf','includes'].forEach(function(name) {
                const original = A.prototype[name];
                if(!original) return;
                A.prototype[name] = function() {
                    if(this.length > limit || arguments.length + this.length > limit) fail();
                    if(name === 'join') {
                        const separator = arguments.length ? String(arguments[0]) : ',';
                        let size = separator.length * this.length;
                        for(let i=0;i<this.length;i++) { size += String(this[i]).length; if(size>textLimit)fail(); }
                    }
                    if(name === 'concat') {
                        let size=this.length;
                        for(let i=0;i<arguments.length;i++)size += A.isArray(arguments[i]) ? arguments[i].length : 1;
                        if(size>limit)fail();
                    }
                    const result = original.apply(this, arguments);
                    if(A.isArray(result) && result.length > limit) fail();
                    return result;
                };
            });
            ['repeat','padStart','padEnd','concat','split'].forEach(function(name) {
                const original = String.prototype[name];
                if(!original)return;
                String.prototype[name] = function() {
                    if(this.length>textLimit)fail();
                    if(name==='repeat' && this.length * Number(arguments[0]) > textLimit)fail();
                    if((name==='padStart'||name==='padEnd') && Number(arguments[0])>textLimit)fail();
                    if(name==='concat') { let size=this.length; for(let i=0;i<arguments.length;i++)size+=String(arguments[i]).length; if(size>textLimit)fail(); }
                    const result=original.apply(this,arguments);
                    if(A.isArray(result)&&result.length>limit)fail();
                    return result;
                };
            });
        })(this);
        """;
    static final class BudgetExceeded extends Error {}
    public record Molang(org.mozilla.javascript.Function function,double fallback,boolean sync) {}
    public final Map<String,JsonObject> groups=new LinkedHashMap<>();
    public final Map<String,JsonObject> cosmic=new LinkedHashMap<>();
    public final Map<String,Molang> molang=new LinkedHashMap<>();
    private final List<org.mozilla.javascript.Function> updates=new ArrayList<>(),poses=new ArrayList<>();
    private final Set<String> evaluating=new HashSet<>();
    private final Set<String> warned=new HashSet<>();
    private ScriptableObject scope;
    private Scriptable state;
    private boolean registering=true;
    public boolean logKeyBindingsRequested;
    public final ScriptBundle bundle;
    public ScriptRuntime(ScriptBundle bundle) {
        this.bundle=bundle;
        var declarations=ScriptDeclarations.read(bundle);
        run(cx -> {
            scope=cx.initSafeStandardObjects();
            ScriptableObject.getFunctionPrototype(scope).delete("constructor");
            cx.evaluateString(scope, LIMITS, "rysmjs-limits",1,null);
            for(String key:List.of("eval","Function","Packages","java","javax","org","com","getClass","RegExp"))ScriptableObject.deleteProperty(scope,key);
            var api=object(cx);
            define(api,"logKeyBindings",args -> {if(registering)logKeyBindingsRequested=true;return null;});
            define(api,"group",args -> {register(groups,args);return null;});
            define(api,"cosmic",args -> {register(cosmic,args);return null;});
            define(api,"molang",args -> {
                if(!registering)throw new IllegalStateException("Registration is closed");
                if(args.length<2 || !(args[1] instanceof org.mozilla.javascript.Function f))throw new IllegalArgumentException("Expected Molang callback");
                String name=name(args[0]);
                if(!name.matches("[a-z_][a-z0-9_]*"))throw new IllegalArgumentException("Molang name must be a lowercase identifier without rysmjs. prefix");
                double fallback=0;
                boolean sync=false;
                if(args.length>2 && args[2] instanceof Scriptable options) {
                    Object v=ScriptableObject.getProperty(options,"fallback");if(v!=Scriptable.NOT_FOUND)fallback=finite(v);
                    Object s=ScriptableObject.getProperty(options,"sync");
                    if(s!=Scriptable.NOT_FOUND){if(!(s instanceof Boolean))throw new IllegalArgumentException("sync must be boolean");sync=(Boolean)s;}
                }
                if(sync && f instanceof NativeFunction fn && fn.getLength()>1)throw new IllegalArgumentException("Synchronized Molang cannot accept numeric arguments");
                if(molang.putIfAbsent(name,new Molang(f,fallback,sync))!=null)throw new IllegalArgumentException("Duplicate Molang: "+name);
                if(molang.size()>256)throw new IllegalArgumentException("Too many Molang registrations");return null;
            });
            define(api,"update",args -> {updates.add(callback(args));return null;});
            define(api,"pose",args -> {poses.add(callback(args));return null;});
            api.sealObject();ScriptableObject.defineProperty(scope,"Rysm",api,ScriptableObject.READONLY|ScriptableObject.PERMANENT);
            JsonArray scripts=bundle.manifest().getAsJsonArray("scripts");
            if(scripts==null || scripts.size()>64)throw new IllegalArgumentException("Expected at most 64 script entries");
            for(JsonElement script:scripts) {
                String path="rysm/"+script.getAsString();
                try {ScriptBundle.checkPath(path);}catch(Exception e){throw new IllegalArgumentException(e);}
                String source=bundle.text(path);
                var env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);
                new Parser(env).parse(source,path,1).visit(node->{
                    if(node instanceof org.mozilla.javascript.ast.RegExpLiteral)throw new IllegalArgumentException("Regular expressions are unavailable in model scripts");
                    if(node instanceof org.mozilla.javascript.ast.ArrayLiteral a && a.getElements().size()>65536)throw new IllegalArgumentException("Array literal too large");
                    return true;
                });
                cx.evaluateString(scope,source,path,1,null);
            }
            if(!molang.keySet().equals(declarations.keySet()))throw new IllegalArgumentException("Molang declarations must register unconditionally at load time");
            for(var entry:molang.entrySet()) {
                var declared=declarations.get(entry.getKey());var actual=entry.getValue();
                if(declared.sync()!=actual.sync() || declared.fallback()!=actual.fallback())throw new IllegalArgumentException("Molang declaration mismatch: "+entry.getKey());
            }
            if(molang.values().stream().filter(Molang::sync).count()>ScriptSync.MAX_VALUES)throw new IllegalArgumentException("At most 64 synchronized Molang values");
            registering=false;state=cx.newObject(scope);return null;
        });
    }
    private org.mozilla.javascript.Function callback(Object[] args) {
        if(!registering)throw new IllegalStateException("Registration is closed");
        if(args.length!=1 || !(args[0] instanceof org.mozilla.javascript.Function f) || updates.size()+poses.size()>=64)
            throw new IllegalArgumentException("Expected callback (maximum 64)");return f;
    }
    private void register(Map<String,JsonObject> target,Object[] args) {
        if(!registering)throw new IllegalStateException("Registration is closed");
        if(args.length!=2 || !(args[1] instanceof Scriptable))throw new IllegalArgumentException("Expected name and properties");
        String name=name(args[0]);
        Object json=NativeJSON.stringify(Context.getCurrentContext(),scope,args[1],null,null);
        String text=Context.toString(json);if(text.length()>65536)throw new IllegalArgumentException("Registration too large");
        if(target.putIfAbsent(name,JsonParser.parseString(text).getAsJsonObject())!=null)throw new IllegalArgumentException("Duplicate registration: "+name);
        if(target.size()>512)throw new IllegalArgumentException("Too many registrations");
    }
    private static String name(Object value) {
        String name=Context.toString(value);
        if(name.isBlank() || name.length()>256 || name.contains("/") || name.contains("\\") || name.contains(".."))throw new IllegalArgumentException("Invalid registration name");return name;
    }
    public static double finite(Object value) {
        double v=value instanceof Boolean b?(b?1:0):Context.toNumber(value);
        if(!Double.isFinite(v))throw new IllegalArgumentException("Expected finite number");return v;
    }
    public ScriptableObject object(Context cx) {return (ScriptableObject)cx.newObject(scope);}
    public void define(Scriptable target,String name,java.util.function.Function<Object[],Object> body) {
        var fn=new BaseFunction(scope,ScriptableObject.getFunctionPrototype(scope)) {
            public Object call(Context cx,Scriptable s,Scriptable self,Object[] args) {return body.apply(args);}
        };
        ScriptableObject.defineProperty(target,name,fn,ScriptableObject.READONLY|ScriptableObject.PERMANENT);
    }
    public ScriptableObject snapshot(Map<String,?> values) {
        var out=object(Context.getCurrentContext());
        for(var e:values.entrySet()) {
            Object v=e.getValue();
            if(v instanceof Map m && !(v instanceof Scriptable))v=snapshot(m);
            if(!(v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Scriptable))throw new IllegalArgumentException("Unsupported bridge value");
            ScriptableObject.defineProperty(out,e.getKey(),v,ScriptableObject.READONLY|ScriptableObject.PERMANENT);
        }
        out.sealObject();return out;
    }
    public Object run(java.util.function.Function<Context,Object> work) {
        return FACTORY.call(cx -> {
            boolean outer=cx.getThreadLocal(ScriptRuntime.class)==null;
            if(outer){cx.putThreadLocal(ScriptRuntime.class,200000);cx.putThreadLocal("deadline",System.nanoTime()+(registering?2_000_000_000L:100_000_000L));cx.putThreadLocal("allocated",allocated());cx.putThreadLocal("allocationLimit",registering?128L*1024*1024:32L*1024*1024);}
            try{return work.apply(cx);}finally{if(outer){cx.removeThreadLocal(ScriptRuntime.class);cx.removeThreadLocal("deadline");cx.removeThreadLocal("allocated");cx.removeThreadLocal("allocationLimit");}}
        });
    }
    public double evaluate(String name,Object[] args,java.util.function.Function<Context,Scriptable> context) {
        Molang m=molang.get(name);if(m==null)return 0;
        if(args.length>32 || Arrays.stream(args).anyMatch(a->!(a instanceof Number n)||!Double.isFinite(n.doubleValue()))) {
            warn(name,new IllegalArgumentException("Expected at most 32 finite numeric arguments"));return m.fallback;
        }
        if(m.sync && args.length!=0){warn(name,new IllegalArgumentException("Synchronized Molang takes no arguments"));return m.fallback;}
        if(!evaluating.add(name)){warn(name,new IllegalStateException("Molang cycle"));return m.fallback;}
        try {
            return (double)run(cx -> {
                Object[] all=new Object[args.length+1];all[0]=context.apply(cx);System.arraycopy(args,0,all,1,args.length);
                return finite(m.function.call(cx,scope,scope,all));
            });
        } catch(BudgetExceeded|RuntimeException e){warn(name,e);return m.fallback;}
        finally{evaluating.remove(name);}
    }
    public void callbacks(boolean pose,java.util.function.Function<Context,Scriptable> context) {
        try {run(cx -> {for(var f:pose?poses:updates)f.call(cx,scope,scope,new Object[]{context.apply(cx)});return null;});}
        catch(BudgetExceeded|RuntimeException e){warn(pose?"pose":"update",e);}
    }
    public Scriptable state(){return state;}
    public void warn(String callback,Throwable e) {
        if(warned.add(callback))org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs {} {}] {}",bundle.digest,callback,e.toString());
    }
}
