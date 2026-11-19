package dev.pocketengine.features.local.project;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

/** Tiny private identity records. A path/length check alone does not bound a later read. */
final class ProjectPrivateRecord {
    private ProjectPrivateRecord(){}
    static JSONObject read(File file,int limit)throws Exception {
        if(limit<1||limit>32768||!file.getAbsoluteFile().equals(file.getCanonicalFile())||
                !Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>limit)
            throw new SecurityException("原项目来源记录无法安全核对。");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var input=Files.newInputStream(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[2048];for(int n;(n=input.read(buffer))!=-1;){
                if(n>limit-bytes.size())throw new SecurityException("原项目来源记录超过本机核对范围。");
                bytes.write(buffer,0,n);
            }
        }
        return new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString());
    }
}
