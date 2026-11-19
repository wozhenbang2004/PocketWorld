package dev.pocketengine.ui;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectPackageArchive;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/** After an already authorized document write closes: read at most its recorded length plus one. No writer or path. */
final class ProjectPackageTargetBytes {
    static final String PROTOCOL="pocketworld.package.target-sha256.v1";
    static final String TASK_OUTPUT_PROTOCOL="pocketworld.task-output.target-sha256.v1";
    interface Open { InputStream selected()throws Exception; }
    interface Gate { void current()throws Exception; }
    enum Outcome { MATCHED,DIFFERENT,UNREADABLE }
    static Outcome check(long expected,String sha,Open open,Gate gate,RunControl c)throws Exception{
        if(expected<=0||expected>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("原固定包缺少有界的大小");
        return read(expected,sha,open,gate,c);
    }
    static Outcome taskOutput(long expected,String sha,Open open,Gate gate,RunControl c)throws Exception{
        if(expected<0||expected>dev.pocketengine.features.attachments.AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("原任务文件超出范围");
        return read(expected,sha,open,gate,c);
    }
    private static Outcome read(long expected,String sha,Open open,Gate gate,RunControl c)throws Exception{
        if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原文件缺少字节摘要");
        gate.current();c.checkpoint();byte[] chunk=new byte[16384];long read=0;MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=open.selected()){
            if(in==null)return Outcome.UNREADABLE;Runnable close=()->{try{in.close();}catch(Exception ignored){}};c.onCancel(close);
            try{
                for(;;){gate.current();c.checkpoint();int n=in.read(chunk,0,(int)Math.min(chunk.length,expected-read+1));
                    if(n<0)break;if(n==0)return Outcome.UNREADABLE;if(n>expected-read)return Outcome.DIFFERENT;read+=n;hash.update(chunk,0,n);}
                gate.current();c.checkpoint();if(read!=expected)return Outcome.DIFFERENT;
                byte[] digest=hash.digest();StringBuilder actual=new StringBuilder(64);
                for(byte b:digest)actual.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
                return sha.contentEquals(actual)?Outcome.MATCHED:Outcome.DIFFERENT;
            }finally{c.removeCancelAction(close);}
        }catch(Exception notReadable){gate.current();c.checkpoint();return Outcome.UNREADABLE;}
        finally{Arrays.fill(chunk,(byte)0);}
    }
    private ProjectPackageTargetBytes(){}
}
