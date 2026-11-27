package net.everla.ysmJsLib;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Called only by client model compilation, never by entity script instances. */
final class ScriptKeyLogger {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("rysmjs");
    static void log(String model) {
        var mc=Minecraft.getInstance();
        if(mc==null || mc.options==null)return;
        var keys=bindings("");
        LOGGER.info("[rysmjs] Model {}: {} registered key bindings (ID | bound key | zh_cn | en_us)",model,keys.size());
        for(var key:keys)LOGGER.info("[rysmjs] {} | {} | {} | {}",key.get("id"),key.get("key"),key.get("zh_cn"),key.get("en_us"));
    }
    static List<Map<String,String>> bindings(String filter) {
        var mc=Minecraft.getInstance();
        if(mc==null || mc.options==null)return List.of();
        var resources=mc.getResourceManager();
        Map<String,String> zh=new HashMap<>(),en=new HashMap<>();
        for(String namespace:new TreeSet<>(resources.getNamespaces())) {
            for(String language:List.of("zh_cn","en_us")) {
                var translations=language.equals("zh_cn")?zh:en;
                for(var resource:resources.getResourceStack(new ResourceLocation(namespace,"lang/"+language+".json"))) {
                    try(var reader=resource.openAsReader()) {
                        JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e->{
                            if(e.getValue().isJsonPrimitive()&&e.getValue().getAsJsonPrimitive().isString())translations.put(e.getKey(),e.getValue().getAsString());
                        });
                    }catch(Exception e){LOGGER.warn("[rysmjs] Cannot read {}:{} translations",namespace,language,e);}
                }
            }
        }
        String match=filter.toLowerCase(Locale.ROOT);
        return Arrays.stream(mc.options.keyMappings)
                .filter(key->key.getName().toLowerCase(Locale.ROOT).contains(match)||key.getCategory().toLowerCase(Locale.ROOT).contains(match))
                .sorted(Comparator.comparing(net.minecraft.client.KeyMapping::getName))
                .map(key->Map.of("id",key.getName(),"key",key.saveString(),"category",key.getCategory(),
                        "zh_cn",zh.getOrDefault(key.getName(),"<缺少中文翻译>"),"en_us",en.getOrDefault(key.getName(),"<missing English translation>"),
                        "name",zh.getOrDefault(key.getName(),en.getOrDefault(key.getName(),key.getName()))))
                .toList();
    }
}
