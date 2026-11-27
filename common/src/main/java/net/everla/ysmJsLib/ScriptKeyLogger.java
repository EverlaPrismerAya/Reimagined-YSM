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
        var keys=Arrays.stream(mc.options.keyMappings).sorted(Comparator.comparing(net.minecraft.client.KeyMapping::getName)).toList();
        LOGGER.info("[rysmjs] Model {}: {} registered key bindings (ID | bound key | zh_cn | en_us)",model,keys.size());
        for(var key:keys)LOGGER.info("[rysmjs] {} | {} | {} | {}",key.getName(),key.saveString(),
                zh.getOrDefault(key.getName(),"<缺少中文翻译>"),en.getOrDefault(key.getName(),"<missing English translation>"));
    }
}
