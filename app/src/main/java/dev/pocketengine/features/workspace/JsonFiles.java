package dev.pocketengine.features.workspace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

/** Small private feature records, replaced only after the complete new record is synced. */
public final class JsonFiles {
    private JsonFiles() { }
    public static JSONObject read(File file) throws Exception {
        if(!file.isFile())return new JSONObject();
        if(file.length()>8*1024*1024)throw new IOException("记录过大，无法读取");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    public static void write(File file,JSONObject value) throws Exception {
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建私有目录");
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>8*1024*1024)throw new IOException("记录超过存储上限");
        File pending=File.createTempFile("pending-",".tmp",parent);
        try {
            try(FileOutputStream out=new FileOutputStream(pending)){out.write(bytes);out.getFD().sync();}
            try{Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException unavailable){Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(pending.toPath());}
    }
    public static JSONObject copy(JSONObject object)throws Exception{return new JSONObject(object.toString());}
    public static String text(JSONObject input,String key,int max)throws Exception {
        Object value=input.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max)throw new IllegalArgumentException(key+" 无效");return ((String)value).trim();
    }
}
