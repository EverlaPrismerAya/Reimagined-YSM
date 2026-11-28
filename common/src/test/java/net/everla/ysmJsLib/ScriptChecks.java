package net.everla.ysmJsLib;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import com.google.gson.*;
import com.elfmcys.yesstevemodel.resource.*;
import net.everla.reimaginedysm.security.*;

public final class ScriptChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static ScriptBundle bundle(String js) throws Exception {
        return new ScriptBundle(Map.of("rysm/manifest.json","{\"version\":1,\"scripts\":[\"scripts/main.js\"]}".getBytes(StandardCharsets.UTF_8),"rysm/scripts/main.js",ScriptSyntaxMigration.convert(js).getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        var bundle=bundle("Rysm.group('cape',{physics:{g:9.8}}); Rysm.molang('sum',(game,a,b)=>a+b,{fallback:7}); Rysm.molang('isolated',game=>{game.model.state.n=(game.model.state.n||0)+1;return game.model.state.n;}); Rysm.molang('permissions',game=>typeof java==='undefined' && typeof Packages==='undefined'); Rysm.molang('loop',game=>{while(true){}},{fallback:9});");
        var logging=new ScriptRuntime(bundle("Rysm.logKeyBindings();Rysm.logKeyBindings();"));
        check(logging.logKeyBindingsRequested,"Registration requests a single model-load key listing");
        var deferredLogging=new ScriptRuntime(bundle("Rysm.molang('log',game=>{Rysm.logKeyBindings();return 1;});"));
        check(deferredLogging.evaluate("log",new Object[0],cx->deferredLogging.snapshot(Map.of()))==1&&!deferredLogging.logKeyBindingsRequested,"Runtime callbacks cannot request repeated key listings");
        var printed=new ArrayList<String>();
        var printing=new ScriptRuntime(bundle("Rysm.model('tick',game=>{game.model.state.n=(game.model.state.n||0)+1;game.print('value',game.model.state.n,{active:true});});"));
        printing.callbacks(false,cx->printing.game(printed::add));
        printing.callbacks(false,cx->printing.game(printed::add));
        check(printed.equals(List.of("value 1 {\"active\":true}","value 2 {\"active\":true}")),"Chat formats values and persists model state");
        var a=new ScriptRuntime(bundle);var b=new ScriptRuntime(bundle);
        check(a.evaluate("sum",new Object[]{2,3},cx->a.snapshot(Map.of()))==5,"Numeric arguments");
        check(a.evaluate("sum",new Object[]{Double.NaN},cx->a.snapshot(Map.of()))==7,"Non-finite argument fallback");
        check(a.evaluate("isolated",new Object[]{},cx->a.snapshot(Map.of("model",Map.of("state",a.state()))))==1,"First state");
        check(a.evaluate("isolated",new Object[]{},cx->a.snapshot(Map.of("model",Map.of("state",a.state()))))==2,"Persistent state");
        check(b.evaluate("isolated",new Object[]{},cx->b.snapshot(Map.of("model",Map.of("state",b.state()))))==1,"Instance isolation");
        check(a.evaluate("permissions",new Object[]{},cx->a.snapshot(Map.of()))==1,"Host classes hidden");
        check(a.evaluate("loop",new Object[]{},cx->a.snapshot(Map.of()))==9,"Instruction budget");
        ScriptInput.event("key.keyboard.escape",1);ScriptInput.event("key.keyboard.escape",0);ScriptInput.advance(true);
        var edge=ScriptInput.get("key.keyboard.escape",false,true);check(edge.pressed()&&edge.released()&&!edge.down(),"Short input edges");
        check(!ScriptInput.get("key.keyboard.escape",false,false).pressed(),"Remote input isolation");
        ScriptInput.advance(true);check(!ScriptInput.get("key.keyboard.escape",false,true).pressed(),"Input edge reset");
        ScriptInput.event("key.keyboard.escape",1);ScriptInput.advance(false);check(!ScriptInput.get("key.keyboard.escape",false,true).down(),"Focus loss releases input");
        var limits=new ScriptRuntime(bundle("Rysm.molang('array',game=>new Array(1000000000),{fallback:3});Rysm.molang('string',game=>'x'.repeat(1000000000),{fallback:4});"));
        check(limits.evaluate("array",new Object[0],cx->limits.snapshot(Map.of()))==3,"Array allocation limit");
        check(limits.evaluate("string",new Object[0],cx->limits.snapshot(Map.of()))==4,"String allocation limit");
        var restored=ScriptBundle.read(bundle.archive());check(restored.digest.equals(bundle.digest),"7z round trip");
        try {new ScriptBundle(Map.of("rysm/../escape",new byte[0]));throw new AssertionError("Traversal accepted");}catch(java.io.IOException expected){}
        check(ScriptResources.replaceCalls("2+rysmjs.sum(rysmjs.x(), 3)",Map.of("sum",7.0)).equals("2+(7.0)"),"Nested call downgrade");
        check(ScriptResources.replaceCalls("'rysmjs.sum()'",Map.of()).equals("'rysmjs.sum()'"),"String literal unchanged");
        for(String old:List.of("group","molang","cosmic","update","pose","logKeyBindings")) {
            var obsolete=new ScriptBundle(Map.of("rysm/manifest.json","{\"version\":1,\"scripts\":[\"scripts/main.js\"]}".getBytes(StandardCharsets.UTF_8),"rysm/scripts/main.js",("Rysm."+old+"();").getBytes(StandardCharsets.UTF_8)));
            try {new ScriptRuntime(obsolete);throw new AssertionError("Old API accepted: "+old);}catch(RuntimeException expected){}
        }
        attributes();
        modelEvents();
        fluentGroups();
        modelScopes();
        synchronization();
        functionFallbacks();
        automaticUpgrade();
        if(args.length>0 && !args[0].startsWith("--")){examples(Path.of(args[0]));}
        if(Arrays.asList(args).contains("--gpu"))gpu();
        System.out.println("RYSM script checks passed");
    }
    private static void automaticUpgrade() throws Exception {
        String source="// Rysm.group('comment',{});\nconst text=\"Rysm.group('text',{});\";Rysm.group('cape',{glow:true});Rysm.molang('value',game=>2,{fallback:7});";
        var legacy=new ScriptBundle(Map.of(
            "rysm/manifest.json","{\"version\":1,\"scripts\":[\"scripts/old.js\",\"scripts/current.js\"]}".getBytes(StandardCharsets.UTF_8),
            "rysm/scripts/old.js",source.getBytes(StandardCharsets.UTF_8),
            "rysm/scripts/current.js","Rysm.model('group_modifier',event=>event.modify('hat').item());".getBytes(StandardCharsets.UTF_8),
            "rysm/textures/keep.bin",new byte[]{0,1,2}));
        var upgraded=ScriptSyntaxMigration.upgrade(legacy);
        check(legacy.text("rysm/scripts/old.js").equals(source),"Automatic upgrade changed the source bundle");
        check(upgraded.text("rysm/scripts/old.js").startsWith("// Rysm.group('comment',{});\nconst text=\"Rysm.group('text',{});\";"),"Automatic upgrade changed comments or strings");
        check(!legacy.digest.equals(upgraded.digest)&&ScriptSyntaxMigration.upgrade(upgraded)==upgraded,"Automatic upgrade hash or idempotence");
        check(Arrays.equals(legacy.files.get("rysm/textures/keep.bin"),upgraded.files.get("rysm/textures/keep.bin"))&&legacy.text("rysm/scripts/current.js").equals(upgraded.text("rysm/scripts/current.js")),"Automatic upgrade changed unrelated resources");
        var raw=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel();
        raw.properties.sha256="b".repeat(64);raw.rysmBundle=legacy;
        raw.functionFiles.put("test",new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawDataFile("test","rysmjs.value()".getBytes(StandardCharsets.UTF_8)));
        ScriptResources.prepare(raw);
        check(new String(raw.functionFiles.get("test").data,StandardCharsets.UTF_8).equals("(7.0)"),"Server upgrade missed legacy Molang fallback");
        var model=ScriptResources.compile(raw);
        check(model!=null&&model.flag("cape","glow")&&model.flag("hat","item"),"Prepared legacy model failed to compile");
        var received=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel();received.rysmBundle=legacy;
        check(ScriptResources.compile(received)!=null,"Received legacy extension did not upgrade at model load");
    }
    private static void attributes() throws Exception {
        var runtime=new ScriptRuntime(bundle("""
            Rysm.model('molang',event=>{
              event.register('current',game=>game.entity.attribute('example:generic.player_flying_speed',0.1));
              event.register('base',game=>game.entity.attributeBase('example:generic.player_flying_speed',0.05));
              event.register('default_value',game=>game.entity.attribute('minecraft:generic.movement_speed'));
              event.register('invalid_fallback',game=>game.entity.attribute('example:generic.player_flying_speed',NaN),{fallback:7});
            });
            """));
        for(var entry:Map.of("current",0.1,"base",0.05,"default_value",0.0,"invalid_fallback",7.0).entrySet()) {
            double value=runtime.evaluate(entry.getKey(),new Object[0],cx->runtime.snapshot(Map.of("entity",runtime.snapshot(Map.of(),view->ScriptAttributes.bind(runtime,view,null)))));
            check(value==entry.getValue(),"Attribute bridge fallback: "+entry.getKey());
        }
    }
    private static void modelEvents() throws Exception {
        String source="// preserve this header\nRysm.logKeyBindings();Rysm.cosmic('crosses',{textures:['cross.png']});Rysm.update(game=>{game.model.state.ticks=(game.model.state.ticks||0)+1;});Rysm.molang('counter',game=>game.model.state.ticks||0,{fallback:0,sync:true});Rysm.molang('sum',(game,a,b)=>a+b,{fallback:7});Rysm.pose(game=>{game.model.state.posed=true;});";
        String converted=ScriptSyntaxMigration.convert(source);
        check(converted.contains("Rysm.model('load'")&&converted.contains("Rysm.model('cosmic'")&&converted.contains("Rysm.model('molang'")&&converted.contains("Rysm.model('tick'")&&converted.contains("Rysm.model('pose'"),"All legacy entries migrate to model events");
        check(!converted.contains("Rysm.molang(")&&!converted.contains("Rysm.update(")&&!converted.contains("Rysm.pose(")&&!converted.contains("Rysm.cosmic(")&&!converted.contains("Rysm.logKeyBindings("),"Legacy entry calls remain after conversion");
        check(converted.startsWith("// preserve this header\n")&&ScriptSyntaxMigration.convert(converted).equals(converted),"Event migration preserves source and is idempotent");
        var old=new ScriptRuntime(bundle(source));var current=new ScriptRuntime(bundle(converted));
        check(old.cosmic.equals(current.cosmic)&&current.logKeyBindingsRequested,"Load and cosmic events match legacy behavior");
        check(ScriptDeclarations.read(bundle(source)).equals(ScriptDeclarations.read(bundle(converted))),"Server declarations match migrated client metadata");
        current.callbacks(false,cx->current.snapshot(Map.of("model",Map.of("state",current.state()))));
        check(current.evaluate("counter",new Object[0],cx->current.snapshot(Map.of("model",Map.of("state",current.state()))))==1,"Tick event runs on tick, not during registration");
        check(current.evaluate("sum",new Object[]{2,3},cx->current.snapshot(Map.of()))==5,"New Molang event accepts numeric arguments");
        current.callbacks(true,cx->current.snapshot(Map.of("model",Map.of("state",current.state()))));
        check(org.mozilla.javascript.ScriptableObject.getProperty(current.state(),"posed").equals(true),"Pose event dispatch");
        for(String invalid:List.of(
                "Rysm.model('unknown',event=>{});",
                "Rysm.model('molang',event=>event.register('bad',game=>0,{sync:1}));",
                "Rysm.model('molang',event=>event.register('bad',(game,a)=>a,{sync:true}));",
                "const options={sync:true};Rysm.model('molang',event=>event.register('bad',game=>0,options));",
                "Rysm.model('molang',event=>{if(false)event.register('bad',game=>0);});",
                "Rysm.model('molang',event=>{const register=event.register;register('bad',game=>0);});")) {
            try{new ScriptRuntime(bundle(invalid));throw new AssertionError("Invalid event declaration accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        var saved=new ScriptRuntime(bundle("let registration;Rysm.model('molang',event=>{registration=event;});Rysm.model('tick',game=>registration.register('late',game=>1));"));
        saved.callbacks(false,cx->saved.snapshot(Map.of()));check(saved.molang.isEmpty(),"Saved event cannot register during tick");
        // A deferred legacy log call was a no-op; migration must keep that behavior.
        String deferred=ScriptSyntaxMigration.convert("Rysm.molang('log',game=>{Rysm.logKeyBindings();return 1;});");
        var log=new ScriptRuntime(bundle(deferred));check(log.evaluate("log",new Object[0],cx->log.snapshot(Map.of()))==1&&!log.logKeyBindingsRequested,"Migration preserves deferred logging");
    }
    private static void fluentGroups() throws Exception {
        String source="// keep this comment\nRysm.group('sky',{cosmic:{effect:'crosses',sc:'3388FF',sa:25},outline:{width:2,color:'33AAFF'},frameAnimation:{count:2,tick:6}});\nRysm.molang('constant',game=>3);";
        String converted=ScriptSyntaxMigration.convert(source);
        check(converted.contains(".cosmic(")&&converted.contains(".outline(")&&converted.contains(".frameAnimation("),"Migration emits fluent effects");
        check(converted.startsWith("// keep this comment\n")&&converted.contains("game=>3"),"Migration preserves unrelated source");
        check(ScriptSyntaxMigration.convert(converted).equals(converted),"JS migration is idempotent");
        var before=new ScriptModel(bundle(source));var after=new ScriptModel(bundle(converted));
        check(before.groups.equals(after.groups),"JS migration preserves group effects");
        var combined=new ScriptModel(bundle(converted+"Rysm.model('group_modifier',event=>{event.modify('sky').glow().cosmic({sa:40});event.modify('sky').outline({width:3});});"));
        var sky=combined.group("sky");
        check(sky.getAsJsonObject("cosmic").get("effect").getAsString().equals("crosses")&&sky.getAsJsonObject("cosmic").get("sa").getAsInt()==40,"Repeated modifications merge effect parameters");
        check(sky.has("frameAnimation")&&sky.getAsJsonObject("outline").get("width").getAsInt()==3&&sky.get("glow").getAsBoolean(),"Effects stack on one group");
        String dynamic="const props={glow:true};Rysm.group('dynamic',props);";
        check(new ScriptModel(bundle(ScriptSyntaxMigration.convert(dynamic))).flag("dynamic","glow"),"Dynamic properties survive migration");
        var closed=new ScriptRuntime(bundle("let saved;Rysm.model('group_modifier',event=>{saved=event.modify('sky');});Rysm.molang('late',game=>{saved.glow();return 1;},{fallback:7});"));
        check(closed.evaluate("late",new Object[0],cx->closed.snapshot(Map.of()))==7&&!closed.groups.get("sky").has("glow"),"Builders cannot modify registrations after loading");
        try{new ScriptModel(bundle("Rysm.model('group_modifier',event=>event.modify('bad').physics({}).end());"));throw new AssertionError("Conflicting physics roles accepted");}catch(IllegalArgumentException expected){}
    }
    private static void modelScopes() throws Exception {
        Path root=Files.createTempDirectory("rysm-model-scopes-");
        try {
            Files.createDirectories(root.resolve("rysm/scripts"));
            Files.writeString(root.resolve("ysm.json"),"""
                {"files":{"player":{"model":{"main":"models/body.json","arm":"models/hands.json"}},"projectiles":[{"model":"models/trident.json"},{"model":"models/arrow.json"}],"vehicles":{"example:boat":{"model":"models/boat.json"}}}}
                """);
            Files.writeString(root.resolve("rysm/manifest.json"),"{\"version\":1,\"scripts\":[\"scripts/main.js\"]}");
            Files.writeString(root.resolve("rysm/scripts/main.js"),"""
                Rysm.model('group_modifier', e=>{
                  e.modify('Bow').glow();
                  e.modify('arrow.Bow').facingCamera().physics({g:2});
                  e.modify('arrow.tip').end();
                  e.modify('hands.Bow').outline({color:'33AAFF',width:3});
                  e.modify('boat.Bow').collider();
                });
                """);
            var bundle=ScriptBundle.read(ScriptBundle.directory(root).archive());
            ScriptModel shared=new ScriptModel(bundle),main=shared.forModel("player.main"),arrow=shared.forModel("projectiles.1"),hands=shared.forModel("player.arm"),boat=shared.forModel("vehicles.0");
            check(main.flag("Bow","glow")&&!main.flag("Bow","facingCamera"),"Main scope isolation");
            check(arrow.scope.equals("arrow")&&arrow.flag("Bow","facingCamera")&&!arrow.flag("Bow","glow"),"Arrow filename scope survives archive");
            check(arrow.physicsName("Bow").contains("g=2")&&arrow.physicsName("tip").startsWith("ysmPhysEnd"),"Scoped bone chain declarations");
            check(hands.group("Bow").getAsJsonObject("outline").get("width").getAsInt()==3&&boat.flag("Bow","collider"),"Custom arm filename and vehicle scope");
            check(arrow.localGroupName("arrow.Bow").equals("Bow")&&arrow.localGroupName("Bow")==null&&main.localGroupName("arrow.Bow")==null,"Pose target namespace isolation");
            check(shared.forModel("projectiles.0").group("Bow").size()==0,"Other projectile isolation");
        }finally{try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void functionFallbacks() throws Exception {
        var raw=new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel();raw.properties.sha256="f".repeat(64);
        raw.metadata.tips="Use rysmjs.sum() in animations";
        byte[] code="return rysmjs.sum(1, 2);".getBytes(StandardCharsets.UTF_8);
        raw.functionFiles.put("algorithm",new com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawDataFile("original",code));
        raw.rysmBundle=bundle("Rysm.molang('sum',(game,a,b)=>a+b,{fallback:7});");
        ScriptResources.prepare(raw);
        check(new String(raw.functionFiles.get("algorithm").data,StandardCharsets.UTF_8).equals("return (7.0);"),"Function file fallback export");
        check(raw.metadata.tips.equals("Use rysmjs.sum() in animations"),"Export modified descriptive text");
        check(ScriptResources.compile(raw)!=null && Arrays.equals(raw.functionFiles.get("algorithm").data,code),"Function source restoration");
        check(raw.functionFiles.get("algorithm").hash.equals("original"),"Function digest restoration");
    }
    private static void synchronization() throws Exception {
        var bundle=bundle("Rysm.molang('toggle',game=>true,{fallback:false,sync:true});Rysm.molang('sum',(game,a,b)=>a+b,{fallback:7});");
        var declarations=ScriptDeclarations.read(bundle);
        check(declarations.get("toggle").sync()&&!declarations.get("sum").sync(),"Static sync declarations");
        var runtime=new ScriptRuntime(bundle);
        check(runtime.evaluate("toggle",new Object[0],cx->runtime.snapshot(Map.of()))==1,"Boolean synchronized output");
        check(runtime.evaluate("toggle",new Object[]{1},cx->runtime.snapshot(Map.of()))==0,"Synchronized output rejects arguments");
        for(String invalid:List.of("Rysm.molang('bad',(game,a)=>a,{sync:true});","Rysm.molang('rysmjs.bad',game=>0);","Rysm.molang('bad',game=>0,{sync:1});","const flag=true;Rysm.molang('bad',game=>0,{sync:flag});")) {
            try{new ScriptRuntime(bundle(invalid));throw new AssertionError("Invalid declaration accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        check(ScriptSync.validValues(Map.of("toggle",1.0),Set.of("toggle")),"Valid sync snapshot");
        check(!ScriptSync.validValues(Map.of("unknown",1.0),Set.of("toggle")),"Undeclared sync value");
        check(!ScriptSync.validValues(Map.of("toggle",Double.POSITIVE_INFINITY),Set.of("toggle")),"Non-finite sync value");
        var packet=new ScriptSync.Submit("example","a".repeat(64),bundle.digest,Map.of("toggle",1.0));
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {ScriptSync.Submit.encode(packet,buffer);check(packet.equals(ScriptSync.Submit.decode(buffer)),"Sync packet round trip");}
        finally{buffer.release();}
        UUID player=UUID.randomUUID();ScriptSync.supported=true;ScriptSync.enabled=true;
        ScriptSync.Client.receive(new ScriptSync.Values(player,"example",packet.hash(),bundle.digest,packet.values()));
        check(ScriptSync.Client.value(player,"example",packet.hash(),bundle.digest,"toggle",7)==1,"Remote value routing");
        check(ScriptSync.Client.value(player,"other",packet.hash(),bundle.digest,"toggle",7)==7,"Model isolation");
        check(ScriptSync.Client.value(player,"example","b".repeat(64),bundle.digest,"toggle",7)==7,"Model hash isolation");
        check(ScriptSync.Client.value(player,"example",packet.hash(),"b".repeat(64),"toggle",7)==7,"Script digest isolation");
        ScriptSync.enabled=false;
        check(ScriptSync.Client.value(player,"example",packet.hash(),bundle.digest,"toggle",7)==7,"Server disable fallback");
        ScriptSync.supported=false;ScriptSync.Client.clear();
        System.out.println("Synchronization declarations, packet limits, remote routing and policy fallback passed");
    }
    private static void examples(Path root) throws Exception {
        {
            Path source=root.resolve("source/all-features");com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel raw;
            try(var reader=new YSMFolderDeserializer(source)){raw=reader.deserialize();}
            ScriptResources.prepare(raw);
            try(var body=YSMBinarySerializer.serialize(raw,32,false);var file=new YSMByteBuf(io.netty.buffer.Unpooled.buffer())) {
                file.writeDword(32);file.getRawBuf().writeBytes(body.toArray());file.writeVarInt(32);file.writeVarInt(1);file.writeString("rysmjs-example");file.writeVarLong(0);file.writeString("");file.writeVarInt(0);
                byte[] encrypted=YsmCrypt.encryptYsmFile(file.toArray());
                ScriptExports.writePair(root.resolve("export/rysmjs-all-features.ysm"),encrypted,ScriptResources.get(raw.properties.sha256));
                exportFailures(encrypted,ScriptResources.get(raw.properties.sha256));
                try(var reader=new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(encrypted))){var read=reader.deserializeKeepOpen();check(!new Gson().toJson(read).contains("rysmjs."),"Vanilla export contains script Molang");}
                var scriptModel=ScriptResources.compile(raw);check(scriptModel!=null,"Client script restoration");
                var boneNames=raw.mainEntity.mainModel.bones.stream().map(b->b.name).collect(java.util.stream.Collectors.toSet());
                check(boneNames.containsAll(scriptModel.groups.keySet()),"Example declares missing bones");
                var features=new HashSet<String>();scriptModel.groups.values().forEach(p->features.addAll(p.keySet()));
                check(features.containsAll(Set.of("physics","end","collider","glow","item","facingCamera","cosmic","outline","frameAnimation")),"Example misses a group feature");
                check(scriptModel.group("tail").getAsJsonObject("physics").get("kind").getAsString().equals("tail"),"Example misses tail physics");
                var declarations=ScriptDeclarations.read(raw.rysmBundle);
                check(Set.of("escape_toggle","inventory","backpack","mouse","releases","player_flying_speed","flying_speed_multiplier","flight_ready","smooth_flying_speed").stream().allMatch(name->declarations.containsKey(name)&&declarations.get(name).sync()),"Example misses synchronized inputs or entity algorithms");
                check(new Gson().toJson(raw).contains("rysmjs."),"Custom expressions were not restored");
                var side=ScriptBundle.read(Files.readAllBytes(root.resolve("export/rysmjs-all-features.rysm")));
                check(side.manifest().get("modelSha256").getAsString().equals(com.elfmcys.yesstevemodel.util.DigestUtil.sha256Hex(encrypted)),"Export model binding");
            }
        }
    }
    private static void exportFailures(byte[] model,ScriptBundle scripts) throws Exception {
        Path root=Files.createTempDirectory("rysm-export-"),file=root.resolve("example.ysm"),side=root.resolve("example.rysm");
        try {
            Files.writeString(file,"original-model");Files.createDirectory(side);Files.writeString(side.resolve("keep"),"sentinel");
            try{ScriptExports.writePair(file,model,scripts);throw new AssertionError("Directory extension destination accepted");}catch(java.io.IOException expected){}
            check(Files.readString(file).equals("original-model")&&Files.readString(side.resolve("keep")).equals("sentinel"),"Failed export damaged previous products");
            Files.delete(side.resolve("keep"));Files.delete(side);Files.writeString(side,"original-script");
            try{ScriptExports.writePair(file,new byte[]{1,2,3},scripts);throw new AssertionError("Invalid base archive accepted");}catch(Exception expected){}
            check(Files.readString(file).equals("original-model")&&Files.readString(side).equals("original-script"),"Invalid export changed previous pair");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void gpu() throws Exception {
        if(!org.lwjgl.glfw.GLFW.glfwInit())throw new AssertionError("GLFW init");
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE,org.lwjgl.glfw.GLFW.GLFW_FALSE);
        long window=org.lwjgl.glfw.GLFW.glfwCreateWindow(32,32,"RYSM cosmic checks",0,0);check(window!=0,"GL context");
        try {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            ScriptBundle resources=ScriptBundle.directory(Path.of("examples/rysmjs/source/all-features"));var model=new ScriptModel(resources);
            check(CosmicTextures.bind(model,"crosses",0)==1,"Static texture effect");
            int a=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            check(CosmicTextures.bind(model,"pulses",0)==1,"Animated texture effect");
            int b=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY);check(a!=b,"Effects share a texture unexpectedly");
            CosmicTextures.bind(model,"pulses",8);
            var pixels=org.lwjgl.BufferUtils.createByteBuffer(16*16*4);org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY,0,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,pixels);
            check((pixels.get((8*16+12)*4)&255)==255,"Animation frame was not uploaded");
            check(CosmicTextures.bind(model,"missing",0)==0,"Invalid effect did not fall back");
            CosmicTextures.clear();check(!org.lwjgl.opengl.GL11.glIsTexture(a)&&!org.lwjgl.opengl.GL11.glIsTexture(b),"Texture resources leaked");
            check(org.lwjgl.opengl.GL11.glGetError()==0,"OpenGL error");
            System.out.println("Cosmic effect isolation, animated upload, fallback and cleanup passed");
            com.elfmcys.yesstevemodel.geckolib3.geo.OutlineDepthChecks.run();
            net.everla.reimaginedysm.gpu.GpuSurfaceChecks.run();
        }finally {CosmicTextures.clear();org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);org.lwjgl.glfw.GLFW.glfwTerminate();}
    }

}
