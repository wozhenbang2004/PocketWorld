package dev.pocketengine.ui;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** One exact, bounded formal text revision, not a directory or project export. */
final class ProjectFileDeliveryBytes {
    static final int LIMIT=256*1024;
    interface Target { OutputStream open()throws Exception; }
    interface Readback { InputStream open()throws Exception; }
    interface Current { void require()throws Exception; }
    static String sha(byte[] value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static String hex(byte[] value){StringBuilder s=new StringBuilder(64);for(byte b:value)s.append(Character.forDigit(b>>>4&15,16)).append(Character.forDigit(b&15,16));return s.toString();}
    /** Returns verified only for an independently reopened, byte-identical target. Never retries a write. */
    static String deliver(byte[] source,String expected,Target target,Readback readback,Current current)throws Exception{
        if(source.length>LIMIT||!sha(source).equals(expected))throw new SecurityException("本次准备的正式版本不完整，未打开写入目标");
        current.require();
        // open itself may truncate; no failure below can be called 'not written'.
        try(OutputStream out=target.open()){
            if(out==null)return "write_unknown";
            for(int i=0;i<source.length;){current.require();int n=Math.min(8192,source.length-i);out.write(source,i,n);i+=n;}
            current.require();out.flush();
        }catch(Exception e){return "write_unknown";}
        try(InputStream in=readback.open()){
            if(in==null)return "written_unverified";MessageDigest hash=MessageDigest.getInstance("SHA-256");int count=0;byte[] b=new byte[8192];
            for(;;){current.require();int n=in.read(b,0,Math.min(b.length,source.length-count+1));if(n==-1)break;
                if(n<=0||n>source.length-count)return "written_unverified";hash.update(b,0,n);count+=n;}
            current.require();return count==source.length&&expected.equals(hex(hash.digest()))?"verified":"written_unverified";
        }catch(Exception unavailable){return "written_unverified";}
    }
    private ProjectFileDeliveryBytes(){}
}
