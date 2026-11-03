package dev.pocketengine.features.local.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Bounded observation of one original local file, not an atomic same-UID filesystem fence. */
public final class ProjectFileBytes {
    private ProjectFileBytes(){}
    public static byte[] read(Path path,BasicFileAttributes before,int limit)throws IOException {
        if(limit<0||before.size()>limit)throw new IOException("文件超过本次实际字节读取预算");
        if(before.isSymbolicLink()||!before.isRegularFile())throw new SecurityException("不读取链接或特殊文件");
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] block=new byte[Math.min(8192,limit+1)];
            for(;;){
                int remaining=limit-output.size(),count=input.read(block,0,Math.min(block.length,remaining+1));
                if(count<0)break;
                if(count>remaining)throw new IOException("读取期间文件超过本次实际字节预算，未保留部分内容");
                output.write(block,0,count);
            }
            BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(after.isSymbolicLink()||!after.isRegularFile()||before.size()!=output.size()||after.size()!=output.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))
                throw new SecurityException("读取期间原文件身份或内容变化，请重新核对");
            return output.toByteArray();
        }
    }
}
