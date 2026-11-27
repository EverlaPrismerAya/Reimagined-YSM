package net.everla.ysmJsLib;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

/** Converts source JSON only, with a complete backup before any source mutation. */
public final class ScriptMigration {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> PREFIXES=List.of("ysmPhysEnd","ysmPhysC","ysmPhys","ysmTail","ysmCosmic","ysmOutline","FrameAnimation","FacingCamera","ysmGlow","ysmItem");
    public static boolean migrate(Path root) {
        return migrate(root,false);
    }
    private static boolean migrate(Path root,boolean inspected) {
        if(Files.exists(root.resolve("rysm/manifest.json")))return true;
        try {
            if(!inspected&&!legacySource(root))return true;
            var documents=new LinkedHashMap<Path,JsonElement>();var names=new LinkedHashSet<String>();
            try(var files=Files.walk(root)) {
                for(Path file:files.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".json")).toList()) {
                    if(root.relativize(file).toString().startsWith("rysm"))continue;
                    JsonElement tree=JsonParser.parseString(Files.readString(file));documents.put(file,tree);collect(tree,names);
                }
            }
            var props=new LinkedHashMap<String,JsonObject>();var renames=new LinkedHashMap<String,String>();var report=new ArrayList<String>();
            for(String name:names) {
                JsonObject p=properties(name);if(p==null)continue;
                String clean=clean(name);boolean ambiguous=clean.isBlank()||names.contains(clean)||renames.containsValue(clean);
                for(JsonElement tree:documents.values())if(unsafe(tree,name))ambiguous=true;
                if(ambiguous){clean=name;report.add("Kept original name (collision or unknown reference): "+name);}else renames.put(name,clean);
                props.put(clean,p);
            }
            if(props.isEmpty())return true;
            for(JsonElement tree:documents.values())rewrite(tree,renames);
            // Validate rewritten names and registrations before touching source files.
            var manifest=new JsonObject();manifest.addProperty("version",1);var entries=new JsonArray();entries.add("scripts/migrated.js");manifest.add("scripts",entries);manifest.addProperty("migrated",true);
            StringBuilder js=new StringBuilder("// Automatically migrated from group-name properties.\n");
            props.forEach((name,p)->js.append("Rysm.group(").append(new Gson().toJson(name)).append(", ").append(p).append(");\n"));
            Path backup=Path.of("config","yes_steve_model","rysm-backups",root.getFileName()+"-"+System.currentTimeMillis()+"-"+java.util.UUID.randomUUID());Files.createDirectories(backup);
            var originals=new LinkedHashMap<Path,byte[]>();
            try(var files=Files.walk(root)) {
                for(Path file:files.toList()) {
                    if(Files.isSymbolicLink(file))throw new IOException("Cannot migrate symbolic links");
                    Path dest=backup.resolve(root.relativize(file).toString());
                    if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest);
                }
            }
            // Validate a staged copy with the real source parser, before replacing any JSON.
            Path stage=Files.createTempDirectory(backup.getParent(),"migration-validation-");
            try {
                try(var files=Files.walk(root)) {for(Path file:files.toList()) {
                    Path dest=stage.resolve(root.relativize(file).toString());
                    if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest);
                }}
                for(var e:documents.entrySet())Files.writeString(stage.resolve(root.relativize(e.getKey()).toString()),JSON.toJson(e.getValue()));
                Files.createDirectories(stage.resolve("rysm/scripts"));
                Files.writeString(stage.resolve("rysm/scripts/migrated.js"),js);
                Files.writeString(stage.resolve("rysm/manifest.json"),JSON.toJson(manifest));
                try(var reader=new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(stage)) {
                    var raw=reader.deserialize();if(raw.mainEntity.mainModel==null)throw new IOException("Migrated model has no main geometry");
                }
            }finally {try(var files=Files.walk(stage)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
            try {
                for(String extra:List.of("rysm/scripts/migrated.js","rysm/migration-report.txt","rysm/manifest.json")) {
                    Path file=root.resolve(extra);originals.put(file,Files.exists(file)?Files.readAllBytes(file):null);
                }
                for(var e:documents.entrySet()){originals.put(e.getKey(),Files.readAllBytes(e.getKey()));Files.writeString(e.getKey(),JSON.toJson(e.getValue()));}
                Files.createDirectories(root.resolve("rysm/scripts"));
                Files.writeString(root.resolve("rysm/scripts/migrated.js"),js);
                Files.writeString(root.resolve("rysm/migration-report.txt"),"Backup: "+backup+"\n"+String.join("\n",report));
                Files.writeString(root.resolve("rysm/manifest.json"),JSON.toJson(manifest));
                org.slf4j.LoggerFactory.getLogger("rysmjs").info("Migrated {} (backup {})",root,backup);
                return true;
            }catch(Exception e){
                for(var original:originals.entrySet())try {
                    if(original.getValue()==null)Files.deleteIfExists(original.getKey());else Files.write(original.getKey(),original.getValue());
                }catch(Exception rollback){e.addSuppressed(rollback);}
                throw e;
            }
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Migration retained legacy source {}",root,e);return false;}
    }
    /** Scan geometry structure without materializing animation files, cubes, or unrelated JSON trees. */
    static boolean legacySource(Path root) throws IOException {
        try(var paths=Files.walk(root)) {
            var files=paths.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".json")&&!root.relativize(p).toString().replace('\\','/').startsWith("rysm/")).iterator();
            while(files.hasNext())try(var reader=new com.google.gson.stream.JsonReader(Files.newBufferedReader(files.next(),StandardCharsets.UTF_8))) {
                reader.setLenient(true);
                if(legacyGeometry(reader))return true;
            }
        }
        return false;
    }
    private static boolean legacyGeometry(com.google.gson.stream.JsonReader reader) throws IOException {
        var token=reader.peek();
        if(token==com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
            reader.beginArray();while(reader.hasNext())if(legacyGeometry(reader))return true;reader.endArray();
        } else if(token==com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            while(reader.hasNext()) {
                String name=reader.nextName();
                if(name.equals("bones")&&reader.peek()==com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while(reader.hasNext()) {
                        if(reader.peek()!=com.google.gson.stream.JsonToken.BEGIN_OBJECT){reader.skipValue();continue;}
                        reader.beginObject();
                        while(reader.hasNext()) {
                            if(reader.nextName().equals("name")&&reader.peek()==com.google.gson.stream.JsonToken.STRING) {
                                String bone=reader.nextString();if(PREFIXES.stream().anyMatch(bone::startsWith))return true;
                            }else reader.skipValue();
                        }
                        reader.endObject();
                    }
                    reader.endArray();
                }else if(name.equals("minecraft:geometry")||name.startsWith("geometry.")) {
                    if(legacyGeometry(reader))return true;
                }else reader.skipValue();
            }
            reader.endObject();
        }else reader.skipValue();
        return false;
    }
    public static ScriptBundle binary(com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel raw,Path source) throws IOException {
        var names=new LinkedHashSet<String>();
        collect(raw.mainEntity.mainModel,names);collect(raw.mainEntity.armModel,names);
        for(var entity:raw.vehicles)collect(entity.model,names);
        for(var entity:raw.projectiles)collect(entity.model,names);
        if(names.stream().noneMatch(name->PREFIXES.stream().anyMatch(name::startsWith)))return null;
        JsonElement before=ScriptResources.modelTree(raw),after=before.deepCopy();
        var props=new LinkedHashMap<String,JsonObject>();var renames=new LinkedHashMap<String,String>();
        for(String name:names) {
            JsonObject p=properties(name);if(p==null)continue;
            String clean=clean(name);
            if(clean.isBlank()||names.contains(clean)||renames.containsValue(clean)||unsafe(before,name))clean=name;
            else renames.put(name,clean);
            props.put(clean,p);
        }
        if(props.isEmpty())return null;
        rewrite(after,renames);var restore=new JsonObject();differences(before,after,"",restore);
        StringBuilder js=new StringBuilder("// Migrated binary model; source is preserved in the backup.\n");
        props.forEach((name,p)->js.append("Rysm.group(").append(new Gson().toJson(name)).append(", ").append(p).append(");\n"));
        var manifest=new JsonObject();manifest.addProperty("version",1);manifest.addProperty("migrated",true);
        manifest.addProperty("modelSha256",ScriptResources.modelDigest(source));
        var scripts=new JsonArray();scripts.add("scripts/migrated.js");manifest.add("scripts",scripts);
        ScriptBundle bundle=new ScriptBundle(Map.of("rysm/manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8),"rysm/scripts/migrated.js",js.toString().getBytes(StandardCharsets.UTF_8),"rysm/restore.json",restore.toString().getBytes(StandardCharsets.UTF_8)));
        Path backups=Path.of("config","yes_steve_model","rysm-backups");Files.createDirectories(backups);
        Path backup=backups.resolve(source.getFileName()+"-"+UUID.randomUUID()+".bak");Files.copy(source,backup);
        String filename=source.getFileName().toString();Path side=source.resolveSibling(filename.substring(0,filename.length()-4)+".rysm");
        byte[] bytes=bundle.archive();ScriptBundle.read(bytes);
        try {
            Path temp=Files.createTempFile(source.getParent(),"rysm-migration-",".tmp");
            try{Files.write(temp,bytes);Files.move(temp,side);}finally{Files.deleteIfExists(temp);}
        }catch(IOException e){org.slf4j.LoggerFactory.getLogger("rysmjs").warn("Using cached binary migration for {} (backup {})",source,backup);}
        return bundle;
    }
    private static void differences(JsonElement before,JsonElement after,String path,JsonObject result) {
        if(before.equals(after))return;
        if(before.isJsonObject()&&after.isJsonObject())for(var e:after.getAsJsonObject().entrySet()) {
            JsonElement old=before.getAsJsonObject().get(e.getKey());
            if(old==null)throw new IllegalArgumentException("Cannot migrate renamed map keys in binary restoration");
            differences(old,e.getValue(),path+"/"+e.getKey().replace("~","~0").replace("/","~1"),result);
        }else if(before.isJsonArray()&&after.isJsonArray())for(int i=0;i<after.getAsJsonArray().size();i++)differences(before.getAsJsonArray().get(i),after.getAsJsonArray().get(i),path+"/"+i,result);
        else result.add(path,after);
    }
    private static void collect(com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.RawGeometry geometry,Set<String> names) {
        if(geometry!=null)for(var bone:geometry.bones)if(bone.name!=null)names.add(bone.name);
    }
    private static void collect(JsonElement n,Set<String> names) {
        if(n.isJsonObject()){var o=n.getAsJsonObject();if(o.has("bones")&&o.get("bones").isJsonArray())for(var b:o.getAsJsonArray("bones"))if(b.isJsonObject()&&b.getAsJsonObject().has("name"))names.add(b.getAsJsonObject().get("name").getAsString());for(var e:o.entrySet())collect(e.getValue(),names);}
        else if(n.isJsonArray())n.getAsJsonArray().forEach(e->collect(e,names));
    }
    public static JsonObject properties(String name) {
        String prefix=PREFIXES.stream().filter(name::startsWith).findFirst().orElse(null);if(prefix==null)return null;
        switch(prefix) {
            case "ysmPhys","ysmTail" -> net.everla.ysmPhysLib.PhysParameters.parse(name,prefix.equals("ysmTail")?net.everla.ysmPhysLib.PhysParameters.TAIL_DEFAULT:net.everla.ysmPhysLib.PhysParameters.DEFAULT);
            case "ysmPhysEnd" -> {if(!net.everla.ysmPhysLib.PhysParameters.isEnd(name))throw new IllegalArgumentException("Invalid endpoint");}
            case "ysmPhysC" -> {if(!net.everla.ysmPhysLib.PhysParameters.isCollider(name))throw new IllegalArgumentException("Invalid collider");}
            case "ysmCosmic","ysmOutline" -> net.everla.reimaginedysm.gpu.GroupStyle.parse(name,net.everla.reimaginedysm.gpu.GroupStyle.NONE);
            case "FrameAnimation" -> net.everla.reimaginedysm.gpu.FrameAnimation.parse(name);
        }
        JsonObject p=new JsonObject(),parameters=new JsonObject();String head=name.split("@",2)[0];int hash=head.indexOf('#');
        if(hash>=0)for(String entry:split(head.substring(hash+1))) {
            int equals=entry.indexOf('=');if(equals<1)throw new IllegalArgumentException("Invalid group parameter: "+name);
            String key=entry.substring(0,equals).trim(),value=entry.substring(equals+1).trim();
            parameters.addProperty(key,value);
        }
        switch(prefix) {
            case "ysmPhysEnd"->p.addProperty("end",true);
            case "ysmPhysC"->p.addProperty("collider",true);
            case "FacingCamera"->p.addProperty("facingCamera",true);
            case "ysmGlow"->p.addProperty("glow",true);
            case "ysmItem"->p.addProperty("item",true);
            case "ysmTail","ysmPhys"->{if(prefix.equals("ysmTail"))parameters.addProperty("kind","tail");p.add("physics",parameters);}
            case "ysmCosmic"->p.add("cosmic",parameters);
            case "ysmOutline"->p.add("outline",parameters);
            case "FrameAnimation"->p.add("frameAnimation",parameters);
        }return p;
    }
    private static List<String> split(String text) {
        var out=new ArrayList<String>();int depth=0,start=0;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='(')depth++;if(c==')')depth--;if(c==','&&depth==0){out.add(text.substring(start,i));start=i+1;}}
        out.add(text.substring(start));return out;
    }
    public static String clean(String name) {
        int at=name.indexOf('@');if(at>=0)return name.substring(at+1);
        for(String prefix:PREFIXES)if(name.startsWith(prefix))return name.substring(prefix.length()).replaceFirst("^[_ ]+","");return name;
    }
    private static boolean unsafe(JsonElement n,String name) {
        if(n.isJsonPrimitive()&&n.getAsJsonPrimitive().isString()) {
            String s=n.getAsString();return !s.equals(name)&&s.contains(name)&&s.replace("'"+name+"'","").replace("\""+name+"\"","").contains(name);
        }
        if(n.isJsonObject())for(var e:n.getAsJsonObject().entrySet()){if(e.getKey().contains(name)&&!e.getKey().equals(name)||unsafe(e.getValue(),name))return true;}
        if(n.isJsonArray())for(var e:n.getAsJsonArray())if(unsafe(e,name))return true;return false;
    }
    private static void rewrite(JsonElement n,Map<String,String> names) {
        if(n.isJsonObject()) {
            var o=n.getAsJsonObject();var entries=new ArrayList<>(o.entrySet());
            for(var e:entries){
                JsonElement v=e.getValue();rewrite(v,names);
                if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()) {
                    String old=v.getAsString(),changed=replace(old,names);if(!old.equals(changed))v=new JsonPrimitive(changed);
                }
                String key=names.getOrDefault(e.getKey(),e.getKey());
                if(!key.equals(e.getKey())){o.remove(e.getKey());o.add(key,v);}
                else if(v!=e.getValue())e.setValue(v);
            }
        }else if(n.isJsonArray())for(int i=0;i<n.getAsJsonArray().size();i++){
            var a=n.getAsJsonArray();var v=a.get(i);rewrite(v,names);
            if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()){
                String old=v.getAsString(),changed=replace(old,names);if(!old.equals(changed))a.set(i,new JsonPrimitive(changed));
            }
        }
    }
    private static String replace(String s,Map<String,String> names) {
        if(names.containsKey(s))return names.get(s);
        for(var e:names.entrySet())if(s.contains(e.getKey()))s=s.replace("'"+e.getKey()+"'","'"+e.getValue()+"'").replace("\""+e.getKey()+"\"","\""+e.getValue()+"\"");return s;
    }
}
