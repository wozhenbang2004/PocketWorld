package dev.pocketengine.features.attachments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Expiring, bounded copies for an explicit Android read-URI grant. Canonical artifacts remain in their original scoped store. */
public final class AttachmentViewSnapshots {
    private static final long TTL_MS=10*60*1000L,MAX_TOTAL=40L*1024*1024;
    private static final java.util.concurrent.ConcurrentHashMap<String,Object> DISK_LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private final Context context;private final File root;private final Object disk;
    public AttachmentViewSnapshots(Context context){this.context=context.getApplicationContext();root=new File(this.context.getCacheDir(),"outcome-views");disk=DISK_LOCKS.computeIfAbsent(root.getAbsolutePath(),path->new Object());}
    public Intent prepare(String name,String mime,String sha256,byte[] bytes,RunControl control)throws Exception {synchronized(disk){
        control.check();if(bytes==null||bytes.length>AttachmentBook.MAX_FILE_BYTES||!digest(bytes).equals(sha256))throw new SecurityException("打开文件的真实字节或版本不匹配");
        Files.createDirectories(root.toPath());prune();long total=0;int count=0;File[] files=root.listFiles((d,n)->n.endsWith(".blob"));
        for(File file:files==null?new File[0]:files){total+=file.length();count++;}if(count>=4||total+bytes.length>MAX_TOTAL)throw new IllegalStateException("临时查看文件已达上限，请稍后重试或直接导出原文件");
        String token=UUID.randomUUID().toString();File blob=new File(root,token+".blob"),meta=new File(root,token+".json");
        try{
            try(FileOutputStream output=new FileOutputStream(blob)){
                for(int offset=0;offset<bytes.length;){control.check();int n=Math.min(32768,bytes.length-offset);output.write(bytes,offset,n);offset+=n;}output.getFD().sync();
            }
            JSONObject record=new JSONObject().put("id",token).put("name",AttachmentBook.displayName(name)).put("mimeType",AttachmentBook.mimeType(mime)).put("size",bytes.length).put("sha256",sha256).put("expiresAt",System.currentTimeMillis()+TTL_MS);
            try(FileOutputStream output=new FileOutputStream(meta)){output.write(record.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            control.check();Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath("view").appendPath(token).build();
            Intent result=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.getString("mimeType")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);result.setClipData(ClipData.newRawUri("Pocket World result",uri));return result;
        }catch(Exception failed){blob.delete();meta.delete();throw failed;}
    }}
    JSONObject metadata(String token)throws Exception {synchronized(disk){
        checkToken(token);File meta=new File(root,token+".json"),blob=new File(root,token+".blob");
        if(!meta.isFile()||meta.length()>4096||!blob.isFile())throw new IllegalArgumentException("原临时查看文件已不可用，请重新打开原成果");
        JSONObject record=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));
        if(!token.equals(record.getString("id"))||record.getLong("expiresAt")<System.currentTimeMillis()||record.getLong("size")!=blob.length()||blob.length()>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("临时查看文件已过期或变化，请重新打开原成果");return record;
    }}
    File file(String token)throws Exception {synchronized(disk){
        byte[] bytes=verifiedBytes(token);try{return new File(root,token+".blob");}finally{Arrays.fill(bytes,(byte)0);}
    }}
    private byte[] verifiedBytes(String token)throws Exception {
        JSONObject meta=metadata(token);ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)meta.getLong("size"));long count=0;
        try(InputStream input=Files.newInputStream(new File(root,token+".blob").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;count+=n;if(count>meta.getLong("size")||count>AttachmentBook.MAX_FILE_BYTES)throw new SecurityException("临时查看文件超过原长度");bytes.write(buffer,0,n);}
        }
        byte[] snapshot=bytes.toByteArray();if(count!=meta.getLong("size")||!digest(snapshot).equals(meta.getString("sha256"))){Arrays.fill(snapshot,(byte)0);throw new SecurityException("临时查看文件字节已变化");}return snapshot;
    }
    /** A seekable read-only FD for these exact verified bytes. The private reader copy is
     * unlinked before delivery, so later canonical/cache-path writes cannot change it.
     * This does not issue a URI grant or retire an existing reader's expiring snapshot. */
    ParcelFileDescriptor openReadOnly(String token)throws Exception {synchronized(disk){
        clearAbandonedReaderCopies();byte[] bytes=verifiedBytes(token);File copy=new File(root,"reader-"+UUID.randomUUID()+".part");boolean created=false;ParcelFileDescriptor opened=null;
        try{
            try(java.io.OutputStream output=Files.newOutputStream(copy.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){created=true;output.write(bytes);output.flush();}
            java.io.FileDescriptor descriptor=Os.open(copy.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            try{opened=ParcelFileDescriptor.dup(descriptor);}finally{Os.close(descriptor);}
            Files.delete(copy.toPath());created=false;
            // Validate the actual detached descriptor, not a path that will be reopened later.
            MessageDigest checksum=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(opened.getFileDescriptor()))){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;count+=n;if(count>bytes.length)throw new SecurityException("查看reader副本超过原长度");checksum.update(buffer,0,n);}}
            if(count!=bytes.length||!Arrays.equals(checksum.digest(),MessageDigest.getInstance("SHA-256").digest(bytes)))throw new SecurityException("查看reader副本与原快照不同");
            Os.lseek(opened.getFileDescriptor(),0,OsConstants.SEEK_SET);ParcelFileDescriptor result=opened;opened=null;return result;
        }catch(Exception failure){
            if(opened!=null)try{opened.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(created)try{Files.deleteIfExists(copy.toPath());}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }finally{Arrays.fill(bytes,(byte)0);}
    }}
    /** The manifest mounts this provider in the one default app process. Every reader
     * copy creation/open/unlink holds this same disk lock; these names never carry a
     * URI grant. A cold leftover can be unlinked without touching a live detached FD.
     * Inspect at most 32 names and unlink at most 16 per read; failure admits no new copy. */
    private void clearAbandonedReaderCopies()throws Exception {
        if(!root.isDirectory())return;int inspected=0,removed=0;
        try(java.nio.file.DirectoryStream<java.nio.file.Path> parts=Files.newDirectoryStream(root.toPath(),"reader-*.part")){
            for(java.nio.file.Path part:parts){if(++inspected>32)break;if(!part.getFileName().toString().matches("reader-[a-f0-9-]{36}\\.part"))continue;if(++removed>16)break;if(!Files.isRegularFile(part,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(part))throw new SecurityException("临时reader副本不是普通文件");Files.deleteIfExists(part);}
        }
    }
    /** Native view-copy cleanup, including group preparations. It grants no read/delete authority over an original store. */
    public void discardUnissuedView(Intent prepared)throws Exception {
        if(prepared==null||prepared.getBooleanExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false))throw new SecurityException("已进入外部reader交付的查看副本不能作为未交付请求清理");
        discardUnsent(prepared.getData());
    }
    /** Native failed file-chooser preparation; validates the exact expiring-copy URI before removal. */
    public void discardUnsent(Uri uri)throws Exception {
        if(uri==null||!"content".equals(uri.getScheme())||!context.getPackageName().concat(".attachments").equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!"view".equals(uri.getPathSegments().get(0))||uri.getQuery()!=null||uri.getFragment()!=null)throw new SecurityException("只能清理本次未交付的准确原生快照");
        discard(uri.getLastPathSegment());
    }
    /** Native failed preparation only; no URI grant has been handed to an external reader. */
    void discard(String token)throws Exception {synchronized(disk){checkToken(token);Files.deleteIfExists(new File(root,token+".blob").toPath());Files.deleteIfExists(new File(root,token+".json").toPath());}}
    /** The snapshot producers and provider are mounted in the manifest's default app
     * process. Their prepare/open/discard paths hold this same per-root disk lock,
     * and prepare cannot return a URI before metadata fsync. Acquiring disk therefore
     * excludes a native writer between blob and index writes. A missing index alone
     * is insufficient: only regular owned UUID-v4 copies older than a full TTL qualify.
     * The timestamp is an extra retention gate, not a cross-process/OS-write proof.
     * Unknown metadata, names and file types never establish deletion permission. */
    private void prune()throws Exception {
        if(!Files.isDirectory(root.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new SecurityException("临时查看目录不是原私有目录");
        long now=System.currentTimeMillis();
        try(java.nio.file.DirectoryStream<java.nio.file.Path> indices=Files.newDirectoryStream(root.toPath(),"*.json")){
            for(java.nio.file.Path meta:indices){
                String name=meta.getFileName().toString(),token=name.substring(0,name.length()-5);
                if(!ownedSnapshotToken(token)||!Files.isRegularFile(meta,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(meta)>4096)continue;
                long expiresAt,size;
                try{
                    JSONObject record=new JSONObject(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8));Object expiry=record.opt("expiresAt"),length=record.opt("size"),display=record.opt("name"),mime=record.opt("mimeType"),sha=record.opt("sha256");
                    if(!token.equals(record.opt("id"))||!(expiry instanceof Long||expiry instanceof Integer)||!(length instanceof Long||length instanceof Integer)||!(display instanceof String)||!(mime instanceof String)||!(sha instanceof String))continue;
                    if(!display.equals(AttachmentBook.displayName((String)display))||!mime.equals(AttachmentBook.mimeType((String)mime))||!((String)sha).matches("[a-f0-9]{64}"))continue;
                    expiresAt=((Number)expiry).longValue();size=((Number)length).longValue();
                }catch(Exception unknown){continue;}
                if(expiresAt<=0||expiresAt>=now||size<0||size>AttachmentBook.MAX_FILE_BYTES)continue;
                java.nio.file.Path blob=root.toPath().resolve(token+".blob");
                if(!Files.isRegularFile(blob,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(blob)!=size)continue;
                Files.deleteIfExists(blob);Files.deleteIfExists(meta);
            }
        }
        try(java.nio.file.DirectoryStream<java.nio.file.Path> blobs=Files.newDirectoryStream(root.toPath(),"*.blob")){
            for(java.nio.file.Path blob:blobs){
                String name=blob.getFileName().toString(),token=name.substring(0,name.length()-5);if(!ownedSnapshotToken(token))continue;
                java.nio.file.attribute.BasicFileAttributes attributes=Files.readAttributes(blob,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);long modified=attributes.lastModifiedTime().toMillis();
                if(!attributes.isRegularFile()||attributes.size()>AttachmentBook.MAX_FILE_BYTES||modified<=0||modified>=now-TTL_MS)continue;
                if(Files.notExists(root.toPath().resolve(token+".json"),java.nio.file.LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(blob);
            }
        }
    }
    private static boolean ownedSnapshotToken(String token){return token.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");}
    private static void checkToken(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("临时查看引用无效");}
    private static String digest(byte[] bytes)throws Exception{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
