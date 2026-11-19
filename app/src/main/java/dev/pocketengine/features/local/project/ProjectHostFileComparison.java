package dev.pocketengine.features.local.project;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads only previously selected relative names, not the checkout tree. No file contents leave this reader. */
final class ProjectHostFileComparison {
    private ProjectHostFileComparison(){}
    private static final class Budget { long left; Budget(int total){left=2L*total;} }
    static final int TEXT_LIMIT=64*1024;
    private static final class Pick { final String sha; String text; Pick(String sha){this.sha=sha;} }
    static String text(byte[] data)throws Exception {
        String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
        if(value.indexOf(0)>=0)throw new IllegalArgumentException("这不是普通 UTF-8 文本");return value;
    }
    private static void current(BooleanSupplier check){if(!check.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IllegalStateException("原副本页面已离开，未返回这次核对");}
    private static File exact(File root,String path)throws Exception{
        if(!root.getAbsoluteFile().equals(root.getCanonicalFile())||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException();
        if(path.isEmpty()||path.length()>2048||path.contains("\\")||path.contains(":")||path.chars().anyMatch(c->c<32))throw new SecurityException();
        File at=root;for(String part:path.split("/",-1)){
            if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException();
            at=new File(at,part);if(Files.isSymbolicLink(at.toPath()))throw new SecurityException();
        }
        if(!at.getAbsoluteFile().equals(at.getCanonicalFile()))throw new SecurityException();return at;
    }
    private static boolean same(StructStat a,StructStat b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_nlink==1&&b.st_nlink==1&&
        OsConstants.S_ISREG(a.st_mode)&&OsConstants.S_ISREG(b.st_mode)&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime;}
    private static String hash(FileDescriptor fd,long bytes,int cap,Budget budget,BooleanSupplier check,byte[] selected)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];long count=0;
        for(;;){current(check);int n=Os.read(fd,buffer,0,(int)Math.min(buffer.length,bytes-count+1));if(n==0)break;
            if(n<0||n>budget.left)throw new SecurityException();budget.left-=n;
            if(n>cap-count||n>bytes-count)throw new SecurityException();if(selected!=null)System.arraycopy(buffer,0,selected,(int)count,n);count+=n;digest.update(buffer,0,n);
        }
        if(count!=bytes)throw new SecurityException();
        StringBuilder text=new StringBuilder(64);for(byte b:digest.digest())text.append(Character.forDigit(b>>4&15,16)).append(Character.forDigit(b&15,16));
        return text.toString();
    }
    private static JSONObject observe(File root,JSONObject claim,int cap,Budget budget,BooleanSupplier check,Pick pick)throws Exception{
        String path=claim.getString("path"),original=claim.getString("sha256");long bytes=claim.getLong("bytes");
        JSONObject row=new JSONObject().put("path",path).put("originalSha256",original).put("originalBytes",bytes);
        if(!original.matches("[0-9a-f]{64}")||bytes<0||bytes>cap)throw new SecurityException("原主机冻结文件记录不完整");
        try{
            File file=exact(root,path);StructStat before;
            try{before=Os.lstat(file.getPath());}catch(ErrnoException missing){
                if(missing.errno!=OsConstants.ENOENT)throw missing;
                // Failure to inspect an ancestor or a dangling link is not a deletion observation.
                exact(root,path);try{Os.lstat(file.getPath());return row.put("state","unverified");}
                catch(ErrnoException again){if(again.errno!=OsConstants.ENOENT)throw again;}
                return row.put("state","missing");
            }
            if(!OsConstants.S_ISREG(before.st_mode)||before.st_nlink!=1||before.st_size<0)return row.put("state","unverified");
            if(before.st_size>cap||before.st_size>budget.left/2)return row.put("state","limit");
            var time=Files.getLastModifiedTime(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0);
            try{
                if(!same(before,Os.fstat(fd))||!same(before,Os.lstat(exact(root,path).getPath())))return row.put("state","unverified");
                String digest=hash(fd,before.st_size,cap,budget,check,null);
                if(pick!=null&&!pick.sha.equals(digest))return row.put("state","unverified");
                byte[] selected=pick==null?null:new byte[(int)before.st_size];Os.lseek(fd,0,OsConstants.SEEK_SET);
                if(!digest.equals(hash(fd,before.st_size,cap,budget,check,selected))||!same(before,Os.fstat(fd))||
                    !same(before,Os.lstat(exact(root,path).getPath()))||!time.equals(Files.getLastModifiedTime(file.toPath(),LinkOption.NOFOLLOW_LINKS)))
                    return row.put("state","unverified");
                if(pick!=null)pick.text=text(selected);
                return row.put("state",original.equals(digest)&&bytes==before.st_size?"same":"changed").put("phoneBytes",before.st_size).put("phoneSha256",digest);
            }finally{Os.close(fd);}
        }catch(Exception inaccessible){current(check);return row.put("state","unverified");}
    }
    /** Exact same descriptor/read proof as the list, only for a specifically reopened changed text. */
    static String selectedChangedText(File root,JSONObject claim,String phoneSha,BooleanSupplier check)throws Exception {
        if(!phoneSha.matches("[0-9a-f]{64}")||phoneSha.equals(claim.getString("sha256"))||claim.getLong("bytes")>TEXT_LIMIT)
            throw new IllegalArgumentException("只核对两侧各不超过 64 KiB 的已改文本");
        Pick pick=new Pick(phoneSha);JSONObject result=observe(root,claim,TEXT_LIMIT,new Budget(TEXT_LIMIT),check,pick);
        if(!"changed".equals(result.opt("state"))||!phoneSha.equals(result.opt("phoneSha256"))||pick.text==null)
            throw new SecurityException("本机文件已变化、不可读或不是可比较的 UTF-8 文本，请重新核对已保存版本。");
        current(check);return pick.text;
    }
    static JSONArray read(File root,JSONArray claims,int maxFiles,int cap,int total,BooleanSupplier check)throws Exception{
        if(claims.length()<1||claims.length()>maxFiles||maxFiles>8||cap<1||cap>256*1024||total<cap||total>512*1024)throw new SecurityException("超出原主机文件选择范围");
        JSONArray rows=new JSONArray();HashSet<String> seen=new HashSet<>();Budget budget=new Budget(total);
        for(int i=0;i<claims.length();i++){current(check);JSONObject claim=claims.getJSONObject(i);
            if(!seen.add(claim.getString("path")))throw new SecurityException("原主机文件身份重复");
            rows.put(observe(root,claim,cap,budget,check,null));
        }
        current(check);return rows;
    }
}
