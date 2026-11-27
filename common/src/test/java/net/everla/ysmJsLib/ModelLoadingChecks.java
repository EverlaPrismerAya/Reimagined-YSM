package net.everla.ysmJsLib;

import com.elfmcys.yesstevemodel.util.ModelArchives;
import com.elfmcys.yesstevemodel.resource.*;
import net.everla.reimaginedysm.legacy.YesModelUtils;
import net.everla.reimaginedysm.security.YsmCrypt;
import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Synthetic regressions plus optional read-only checks against reported game sources. */
public final class ModelLoadingChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        byte[] noBom="YSGP\r\n\r\n--- [ Metadata ] ---".getBytes(StandardCharsets.UTF_8);
        check(YesModelUtils.getYsmCryptoVersion(noBom)==3,"No-BOM header rejected");
        byte[] withBom=new byte[noBom.length+3];System.arraycopy(new byte[]{(byte)239,(byte)187,(byte)191},0,withBom,0,3);System.arraycopy(noBom,0,withBom,3,noBom.length);
        check(YesModelUtils.getYsmCryptoVersion(withBom)==3,"BOM header rejected");
        check(YesModelUtils.getYsmCryptoVersion(new byte[]{89,83,71,80,0,0,0,2})==2,"Legacy header changed");
        byte[] corrupt=new byte[30];System.arraycopy(new byte[]{89,83,71,80,0,0,0,2},0,corrupt,0,8);
        try{YesModelUtils.input(corrupt);throw new AssertionError("Legacy checksum bypassed");}
        catch(IOException expected){check(expected.getMessage().contains("MD5 mismatch"),"Legacy checksum misdiagnosed");}
        Path temp=Files.createTempDirectory("rysm-archive-check-");
        try {
            for(String encoding:List.of("UTF-8","GB18030")) {
                Path archive=temp.resolve(encoding+".zip");
                try(var output=new ZipOutputStream(Files.newOutputStream(archive),Charset.forName(encoding))) {
                    output.putNextEntry(new ZipEntry("模型/ysm.json"));output.write("{}".getBytes(StandardCharsets.UTF_8));output.closeEntry();
                }
                try(var zip=ModelArchives.openZip(archive)){check(Files.readString(zip.getPath("/模型/ysm.json")).equals("{}"),"ZIP filename decoded incorrectly: "+encoding);}
            }
            Path encrypted=temp.resolve("encrypted.zip");
            try(var output=new ZipOutputStream(Files.newOutputStream(encrypted))){output.putNextEntry(new ZipEntry("ysm.json"));output.write(1);output.closeEntry();}
            byte[] bytes=Files.readAllBytes(encrypted);
            for(int i=0;i<bytes.length-10;i++)if(bytes[i]==0x50&&bytes[i+1]==0x4b) {
                if(bytes[i+2]==3&&bytes[i+3]==4)bytes[i+6]|=1;
                if(bytes[i+2]==1&&bytes[i+3]==2)bytes[i+8]|=1;
            }
            Files.write(encrypted,bytes);
            try(var ignored=ModelArchives.openZip(encrypted)){throw new AssertionError("Encrypted ZIP accepted");}
            catch(IOException expected){check(expected.getMessage().contains("password"),"Encrypted ZIP diagnostic missing");}
        }finally{try(var paths=Files.walk(temp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        for(boolean string:List.of(true,false)) {
            try(var malformed=new net.everla.reimaginedysm.security.YSMByteBuf(io.netty.buffer.Unpooled.buffer())) {
                malformed.writeVarInt(Integer.MAX_VALUE);
                try{if(string)malformed.readString();else malformed.readByteArray();throw new AssertionError("Oversized length accepted");}
                catch(IllegalArgumentException expected){check(expected.getMessage().contains("length"),"Length diagnostic missing");}
            }
        }
        oldBinaryLayouts();
        System.out.println("Model loading checks passed: BOM/no-BOM, legacy checksum, UTF-8/GB18030 ZIP and encrypted ZIP diagnostics");
        if(args.length>0)reportedSources(Path.of(args[0]));
    }
    private static void oldBinaryLayouts() throws Exception {
        for(int format:List.of(15,18)) {
            try(var bytes=new net.everla.reimaginedysm.security.YSMByteBuf(io.netty.buffer.Unpooled.buffer())) {
                bytes.writeDword(format);
                for(int i=0;i<8;i++)bytes.writeVarInt(0); // Empty legacy models, animations, controllers, textures and sounds.
                if(format>15)for(int i=0;i<4;i++)bytes.writeVarInt(0);
                for(int i=0;i<4;i++)bytes.writeVarInt(0); // Avatars and hash tables.
                metadata(bytes,format);
                try(var reader=new YSMBinaryDeserializer(bytes.toArray())) {
                    check(reader.deserializeKeepOpen().properties.defaultTexture.equals("default"),"Legacy 15/18 metadata misaligned");
                    check(reader.getReader().getRawBuf().readableBytes()==0,"Legacy fields left unread");
                }
            }
        }
        for(int format:List.of(20,21)) {
            try(var bytes=new net.everla.reimaginedysm.security.YSMByteBuf(io.netty.buffer.Unpooled.buffer())) {
                bytes.writeDword(format);bytes.writeVarInt(0);bytes.writeVarInt(0);bytes.writeVarInt(0);
                bytes.writeVarInt(1); // One old arrow submodel, without controller/texture-format fields.
                bytes.writeString("arrow");bytes.writeVarInt(0);bytes.writeString("hash");bytes.writeByteArray(new byte[4]);
                bytes.writeVarInt(1);bytes.writeVarInt(1);bytes.writeVarInt(0);bytes.writeString("model-hash");
                geometry(bytes);
                if(format>=21)bytes.writeVarInt(0);
                bytes.writeVarInt(1);bytes.writeVarInt(0);bytes.writeVarInt(0);bytes.writeVarInt(0);bytes.writeVarInt(0);
                metadata(bytes,format);
                try(var reader=new YSMBinaryDeserializer(bytes.toArray())) {
                    var raw=reader.deserializeKeepOpen();
                    check(raw.vehicles.size()==1&&raw.vehicles.get(0).textures.values().iterator().next().imageFormat==-1,"Old submodel layout lost");
                    check(raw.properties.defaultTexture.equals("default"),"Old metadata misaligned");
                }
            }
        }
    }
    private static void geometry(net.everla.reimaginedysm.security.YSMByteBuf bytes) {
        bytes.writeVarInt(0);bytes.writeString("geometry.test");
        for(int i=0;i<4;i++)bytes.writeFloat(1);
        bytes.writeVarInt(0);bytes.writeFloat(0);bytes.writeFloat(0);
        for(int i=0;i<4;i++)bytes.writeVarInt(0);
    }
    private static void metadata(net.everla.reimaginedysm.security.YSMByteBuf bytes,int format) {
        bytes.writeString("sha");bytes.writeVarInt(1);if(format<=18)bytes.writeVarInt(0);bytes.writeString("name");bytes.writeString("");bytes.writeString("");bytes.writeString("");
        bytes.writeVarInt(0);bytes.writeVarInt(0);bytes.writeFloat(1);bytes.writeFloat(1);
        bytes.writeVarInt(0);bytes.writeVarInt(0);bytes.writeVarInt(0);
        bytes.writeString("default");bytes.writeString("");
        for(int i=0;i<4;i++)bytes.writeVarInt(0);
        if(format>15)bytes.writeVarInt(0);
        if(format>18){bytes.writeString("");bytes.writeString("");bytes.writeVarInt(0);bytes.writeVarInt(0);}
    }
    private static void reportedSources(Path log) throws Exception {
        Path game=log.getParent().getParent();var counts=new TreeMap<String,Integer>();var details=new ArrayList<String>();
        for(String line:Files.readAllLines(log)) {
            String marker=line.contains("Failed to load binary model at: ")?"Failed to load binary model at: ":line.contains("Failed to scan zip model source: ")?"Failed to scan zip model source: ":null;
            if(marker==null)continue;
            Path file=game.resolve(line.substring(line.indexOf(marker)+marker.length()));String result;
            try {
                if(marker.contains("zip")) {
                    try(var zip=ModelArchives.openZip(file)){try(var paths=Files.walk(zip.getPath("/"))){paths.count();}}
                    result="ZIP recovered";
                } else {
                    byte[] bytes=Files.readAllBytes(file);int crypto=YesModelUtils.getYsmCryptoVersion(bytes);
                    if(crypto==1||crypto==2) {
                        try(var reader=new YSMFolderDeserializer(YesModelUtils.input(bytes))){reader.deserialize();}
                    } else if(crypto==3) {
                        byte[] plain=YsmCrypt.decryptYsmFile(bytes);
                        try(var reader=new YSMBinaryDeserializer(plain)) {
                            var raw=reader.deserializeKeepOpen();
                            reader.parseYSMFooter(raw);
                            int remaining=reader.getReader().getRawBuf().readableBytes();
                            check(remaining==0 || remaining==1&&reader.getReader().readByte()==0,"Unexpected trailing model data");
                            check(raw.mainEntity.mainModel!=null&&!raw.mainEntity.textures.isEmpty(),"Incomplete recovered model");
                            try(var serialized=YSMBinarySerializer.serialize(raw,32,true);
                                var restored=new YSMBinaryDeserializer(serialized.toArray(),32)) {
                                var roundTrip=restored.deserializeKeepOpen();
                                check(roundTrip.mainEntity.mainModel.bones.size()==raw.mainEntity.mainModel.bones.size(),"Recovered model lost bones");
                            }
                        }
                    } else throw new IOException("Unknown YSM header");
                    result="YSM recovered";
                }
            }catch(Exception failure){result=failure.getMessage();}
            String category=result.contains("at byte")?result.substring(0,result.indexOf(" at byte")):result;
            counts.merge(category,1,Integer::sum);details.add(file+": "+result);
        }
        Path report=Path.of("build/reports/model-loading.txt");Files.createDirectories(report.getParent());Files.write(report,details);
        counts.forEach((key,value)->System.out.println(value+" x "+key));System.out.println("Read-only model report: "+report);
    }
}
