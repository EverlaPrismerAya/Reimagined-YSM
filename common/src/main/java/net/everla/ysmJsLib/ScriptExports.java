package net.everla.ysmJsLib;
import com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer;
import net.everla.reimaginedysm.security.YsmCrypt;
import com.elfmcys.yesstevemodel.util.DigestUtil;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

public final class ScriptExports {
    /** Both products are validated before publication. Prior outputs are restored if either move fails. */
    public static void writePair(Path model,byte[] ysm,ScriptBundle extension) throws Exception {
        Files.createDirectories(model.getParent());
        try(var reader=new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(ysm))){reader.deserializeKeepOpen();}
        String name=model.getFileName().toString();Path side=model.resolveSibling(name.substring(0,name.length()-4)+".rysm");
        if(Files.exists(model)&&!Files.isRegularFile(model) || Files.exists(side)&&!Files.isRegularFile(side))throw new IOException("Export destination is not a regular file");
        Path tempModel=Files.createTempFile(model.getParent(),"rysm-model-",".tmp");
        Path tempSide=null,oldModel=null,oldSide=null;boolean started=false,success=false;
        try {
            Files.write(tempModel,ysm);
            if(extension!=null){
                var files=new TreeMap<>(extension.files);var manifest=extension.manifest();manifest.addProperty("modelSha256",DigestUtil.sha256Hex(ysm));
                files.put("rysm/manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
                byte[] archive=new ScriptBundle(files).archive();ScriptBundle.read(archive);
                tempSide=Files.createTempFile(model.getParent(),"rysm-extension-",".tmp");Files.write(tempSide,archive);
            }
            if(Files.exists(model)){oldModel=Files.createTempFile(model.getParent(),"rysm-old-model-",".tmp");Files.copy(model,oldModel,StandardCopyOption.REPLACE_EXISTING);}
            if(Files.exists(side)){oldSide=Files.createTempFile(model.getParent(),"rysm-old-extension-",".tmp");Files.copy(side,oldSide,StandardCopyOption.REPLACE_EXISTING);}
            started=true;
            Files.move(tempModel,model,StandardCopyOption.REPLACE_EXISTING);
            if(tempSide!=null)Files.move(tempSide,side,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(side);
            success=true;
        }catch(Exception e){
            if(started) {
                try{if(oldModel!=null)Files.copy(oldModel,model,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(model);
                    if(oldSide!=null)Files.copy(oldSide,side,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(side);
                }catch(Exception rollback){e.addSuppressed(rollback);throw new IOException("Export rollback failed; recovery files: "+oldModel+", "+oldSide,e);}
            }
            throw e instanceof IOException io?io:new IOException(e);
        }finally {
            Files.deleteIfExists(tempModel);if(tempSide!=null)Files.deleteIfExists(tempSide);
            // Keep recovery copies on any failed publication for manual recovery.
            if(success){if(oldModel!=null)Files.deleteIfExists(oldModel);if(oldSide!=null)Files.deleteIfExists(oldSide);}
        }
    }
}
