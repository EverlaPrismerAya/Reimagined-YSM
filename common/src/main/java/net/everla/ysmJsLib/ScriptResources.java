package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import net.everla.reimaginedysm.core.YesSteveModel;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.mozilla.javascript.ast.*;

/** Archive storage and source restoration; safe on a dedicated server (no JS evaluation). */
public final class ScriptResources {
    // Only names and animation data can change. Never expand textures, sounds or mesh vertices into JSON nodes.
    private static final Gson GSON=new GsonBuilder().serializeSpecialFloatingPointValues().setObjectToNumberStrategy(reader -> Float.parseFloat(reader.nextString()))
        .registerTypeHierarchyAdapter(Map.Entry.class, new EntryAdapter())
        .addSerializationExclusionStrategy(new ExclusionStrategy() {
            public boolean shouldSkipClass(Class<?> type){return false;}
            public boolean shouldSkipField(FieldAttributes field) {
                Class<?> owner=field.getDeclaringClass();String name=field.getName();
                if(owner==RawYsmModel.class)return Set.of("metadata","properties","soundFiles","languageFiles","footer").contains(name);
                if(owner==RawYsmModel.RawMainEntity.class || owner==RawYsmModel.RawSubEntity.class)return name.equals("textures");
                if(owner==RawYsmModel.RawGeometry.class)return !name.equals("bones");
                if(owner==RawYsmModel.RawBone.class)return !name.equals("name")&&!name.equals("parentName");
                return false;
            }
        }).create();
    /** Controller lists preserve order and duplicate keys, unlike a map. */
    private static final class EntryAdapter implements JsonSerializer<Map.Entry<?,?>>, JsonDeserializer<Map.Entry<?,?>> {
        public JsonElement serialize(Map.Entry<?,?> entry, java.lang.reflect.Type type, JsonSerializationContext context) {
            JsonObject object=new JsonObject();object.add("key",context.serialize(entry.getKey()));object.add("value",context.serialize(entry.getValue()));return object;
        }
        public Map.Entry<?,?> deserialize(JsonElement json, java.lang.reflect.Type type, JsonDeserializationContext context) {
            JsonObject object=json.getAsJsonObject();
            return new AbstractMap.SimpleEntry<>(object.get("key").getAsString(),object.get("value").getAsString());
        }
    }
    static JsonElement modelTree(RawYsmModel raw){return GSON.toJsonTree(raw);}
    static String modelDigest(Path path) throws java.io.IOException {
        var digest=com.elfmcys.yesstevemodel.util.DigestUtil.sha256Digest();
        try(var input=new java.security.DigestInputStream(Files.newInputStream(path),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    private static final Map<String,ScriptBundle> BUNDLES=new LinkedHashMap<>(16,.75f,true);
    private static long cachedBytes;
    private static synchronized void remember(String hash,ScriptBundle bundle) {
        ScriptBundle previous=BUNDLES.put(hash,bundle);if(previous!=null)cachedBytes-=size(previous);
        cachedBytes+=size(bundle);
        var entries=BUNDLES.entrySet().iterator();
        while(cachedBytes>64L*1024*1024 || BUNDLES.size()>64){var entry=entries.next();cachedBytes-=size(entry.getValue());entries.remove();}
    }
    private static long size(ScriptBundle bundle){return bundle.files.values().stream().mapToLong(data->data.length).sum();}
    private static synchronized ScriptBundle remembered(String hash){return BUNDLES.get(hash);}
    private static final Path CACHE=Path.of("config","yes_steve_model","cache","rysm-js");
    public static void install(String hash,ScriptBundle bundle) throws java.io.IOException {
        if(!hash.matches("[a-fA-F0-9]{64}"))throw new java.io.IOException("Invalid model digest");
        Files.createDirectories(CACHE);
        Path temp=Files.createTempFile(CACHE,hash,".tmp");
        try{Files.write(temp,bundle.archive());Files.move(temp,CACHE.resolve(hash+".rysm"),StandardCopyOption.REPLACE_EXISTING);remember(hash,bundle);}finally{Files.deleteIfExists(temp);}
    }
    public static ScriptBundle get(String hash) {
        if(hash==null || !hash.matches("[a-fA-F0-9]{64}"))return null;
        ScriptBundle b=remembered(hash);if(b!=null)return b;
        try{Path p=CACHE.resolve(hash+".rysm");if(Files.isRegularFile(p)){b=ScriptBundle.read(Files.readAllBytes(p));remember(hash,b);return b;}}
        catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Cannot read cached extension {}",hash,e);}return null;
    }
    public static void folder(RawYsmModel raw,Path root) {
        raw.rysmSourceChecked=true;raw.rysmBundle=null;
        if(!Files.isDirectory(root.resolve("rysm")))return;
        try{raw.rysmBundle=ScriptBundle.directory(root);}catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Invalid source extension {}",root,e);}
    }
    public static void companion(RawYsmModel raw,Path model) {
        raw.rysmSourceChecked=true;raw.rysmBundle=null;
        String file=model.getFileName().toString();Path side=model.resolveSibling(file.substring(0,file.length()-4)+".rysm");
        if(!Files.isRegularFile(side))return;
        try {
            ScriptBundle bundle=ScriptBundle.read(Files.readAllBytes(side));
            String expected=bundle.manifest().get("modelSha256").getAsString();
            String actual=modelDigest(model);
            if(!expected.equals(actual))throw new java.io.IOException("Model digest mismatch");
            raw.rysmBundle=bundle;
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Rejected companion {}",side,e);}
    }
    public static void prepare(RawYsmModel raw) {
        if(raw.rysmBundle==null)return;
        try {
            raw.rysmBundle=ScriptSyntaxMigration.upgrade(raw.rysmBundle);
            apply(raw,restore(raw,raw.rysmBundle));
            var files=new TreeMap<>(raw.rysmBundle.files);
            JsonElement tree=modelTree(raw);var recovery=raw.rysmBundle.files.containsKey("rysm/restore.json")?JsonParser.parseString(raw.rysmBundle.text("rysm/restore.json")).getAsJsonObject():new JsonObject();
            Map<String,Double> fallback=fallbacks(raw.rysmBundle);
            downgrade(tree,"",recovery,fallback);
            downgradeFunctions(tree.getAsJsonObject().getAsJsonObject("functionFiles"),recovery,fallback);
            files.put("rysm/restore.json",GSON.toJson(recovery).getBytes(StandardCharsets.UTF_8));
            ScriptBundle bundle=new ScriptBundle(files);
            // Script changes participate in the advertised model hash and therefore invalidate remote caches.
            String hash=com.elfmcys.yesstevemodel.util.DigestUtil.sha256Hex((raw.properties.sha256+bundle.digest).getBytes(StandardCharsets.UTF_8));
            raw.properties.sha256=hash;install(hash,bundle);raw.rysmBundle=bundle;
            RawYsmModel base=GSON.fromJson(tree,RawYsmModel.class);
            apply(raw,base);raw.properties.sha256=hash;
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").error("[rysmjs] Cannot prepare extension",e);throw new IllegalArgumentException(e);}
    }
    private static Map<String,Double> fallbacks(ScriptBundle bundle) {
        var out=new HashMap<String,Double>();
        ScriptDeclarations.read(bundle).forEach((name,entry)->out.put(name,entry.fallback()));
        return out;
    }

    private static void downgrade(JsonElement node,String path,JsonObject restore,Map<String,Double> fallback) {
        if(node.isJsonObject())for(var e:node.getAsJsonObject().entrySet()) {
            if(Set.of("metadata","languageFiles","soundFiles","footer","properties","mainModel","armModel","model","textures","name","boneName","parentName","identifier","animationName","initialState","effectName","hash","fileHash","modelId","key","matchIds").contains(e.getKey()))continue;
            String p=path+"/"+e.getKey().replace("~","~0").replace("/","~1");
            if(e.getValue().isJsonPrimitive()&&e.getValue().getAsJsonPrimitive().isString()) {
                String text=e.getValue().getAsString(),changed=replaceCalls(text,fallback);
                if(!text.equals(changed)){restore.addProperty(p,text);e.setValue(new JsonPrimitive(changed));}
            }else downgrade(e.getValue(),p,restore,fallback);
        }else if(node.isJsonArray())for(int i=0;i<node.getAsJsonArray().size();i++) {
            JsonElement e=node.getAsJsonArray().get(i);String p=path+"/"+i;
            if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString()) {
                String text=e.getAsString(),changed=replaceCalls(text,fallback);
                if(!text.equals(changed)){restore.addProperty(p,text);node.getAsJsonArray().set(i,new JsonPrimitive(changed));}
            }else downgrade(e,p,restore,fallback);
        }
    }
    private static void downgradeFunctions(JsonObject functions,JsonObject restore,Map<String,Double> fallback) {
        if(functions==null)return;
        for(var entry:functions.entrySet()) {
            JsonObject file=entry.getValue().getAsJsonObject();byte[] data=GSON.fromJson(file.get("data"),byte[].class);
            String source=new String(data,StandardCharsets.UTF_8),changed=replaceCalls(source,fallback);
            if(source.equals(changed))continue;
            String pointer="/functionFiles/"+entry.getKey().replace("~","~0").replace("/","~1");
            restore.add(pointer+"/data",file.get("data").deepCopy());restore.add(pointer+"/hash",file.get("hash").deepCopy());
            byte[] downgraded=changed.getBytes(StandardCharsets.UTF_8);file.add("data",GSON.toJsonTree(downgraded));
            file.addProperty("hash",com.elfmcys.yesstevemodel.util.DigestUtil.sha256Hex(downgraded));
        }
    }
    public static String replaceCalls(String text,Map<String,Double> fallback) {
        StringBuilder out=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();) {
            char c=text.charAt(i);if(c=='\''){quoted=!quoted;out.append(c);i++;continue;}
            if(!quoted && text.startsWith("rysmjs.",i) && (i==0 || !Character.isJavaIdentifierPart(text.charAt(i-1))&&text.charAt(i-1)!='.')) {
                int start=i+7,end=start;while(end<text.length()&&(Character.isLetterOrDigit(text.charAt(end))||text.charAt(end)=='_'))end++;
                if(end==start){out.append(c);i++;continue;}
                String name=text.substring(start,end);int cursor=end;while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;
                if(cursor<text.length()&&text.charAt(cursor)=='(') {
                    int depth=1;boolean q=false;cursor++;
                    while(cursor<text.length()&&depth>0){char k=text.charAt(cursor++);if(k=='\'')q=!q;if(!q){if(k=='(')depth++;if(k==')')depth--;}}
                    if(depth!=0)throw new IllegalArgumentException("Unbalanced rysmjs call");end=cursor;
                }
                out.append('(').append(fallback.getOrDefault(name,0.0)).append(')');i=end;
            }else{out.append(c);i++;}
        }return out.toString();
    }
    public static ScriptModel compile(RawYsmModel raw) {
        // Disk sources require their explicit companion; only received binary models may restore by advertised digest.
        if(raw.rysmSourceChecked&&raw.rysmBundle==null)return null;
        ScriptBundle bundle=raw.rysmBundle!=null?raw.rysmBundle:get(raw.properties.sha256);if(bundle==null)return null;
        try {
            bundle=ScriptSyntaxMigration.upgrade(bundle);
            var model=new ScriptModel(bundle);
            apply(raw,restore(raw,bundle));
            if(model.logKeyBindingsRequested)ScriptKeyLogger.log(bundle.digest);
            return model;
        }catch(Throwable e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("[rysmjs] Disabled extension {}",bundle.digest,e);return null;}
    }
    private static RawYsmModel restore(RawYsmModel raw,ScriptBundle bundle) {
            if(bundle.files.containsKey("rysm/restore.json")) {
                JsonElement tree=modelTree(raw);var restore=JsonParser.parseString(bundle.text("rysm/restore.json")).getAsJsonObject();
                for(var e:restore.entrySet()) {
                    String[] parts=e.getKey().substring(1).split("/");JsonElement parent=tree;
                    for(int i=0;i<parts.length-1;i++)parent=child(parent,decode(parts[i]));
                    String last=decode(parts[parts.length-1]);
                    if(parent.isJsonArray())parent.getAsJsonArray().set(Integer.parseInt(last),e.getValue());else parent.getAsJsonObject().add(last,e.getValue());
                }
                return GSON.fromJson(tree,RawYsmModel.class);
            }
        return raw;
    }
    private static void apply(RawYsmModel raw,RawYsmModel restored) {
        if(raw==restored)return;
        names(raw.mainEntity.mainModel,restored.mainEntity.mainModel);names(raw.mainEntity.armModel,restored.mainEntity.armModel);
        raw.mainEntity.animationFiles=restored.mainEntity.animationFiles;raw.mainEntity.animationControllerFiles=restored.mainEntity.animationControllerFiles;
        subEntities(raw.vehicles,restored.vehicles);subEntities(raw.projectiles,restored.projectiles);raw.functionFiles=restored.functionFiles;
    }
    private static void subEntities(List<RawYsmModel.RawSubEntity> original,List<RawYsmModel.RawSubEntity> restored) {
        if(original.size()!=restored.size())throw new IllegalArgumentException("Restored entity count mismatch");
        for(int i=0;i<original.size();i++) {
            var target=original.get(i);var source=restored.get(i);names(target.model,source.model);
            target.animationFiles=source.animationFiles;target.animationControllerFiles=source.animationControllerFiles;
        }
    }
    private static void names(RawYsmModel.RawGeometry original,RawYsmModel.RawGeometry restored) {
        if(original==null&&restored==null)return;
        if(original==null||restored==null||original.bones.size()!=restored.bones.size())throw new IllegalArgumentException("Restored bone count mismatch");
        for(int i=0;i<original.bones.size();i++){var target=original.bones.get(i);var source=restored.bones.get(i);target.name=source.name;target.parentName=source.parentName;}
    }
    private static String decode(String s){return s.replace("~1","/").replace("~0","~");}
    private static JsonElement child(JsonElement n,String p){return n.isJsonArray()?n.getAsJsonArray().get(Integer.parseInt(p)):n.getAsJsonObject().get(p);}
}
