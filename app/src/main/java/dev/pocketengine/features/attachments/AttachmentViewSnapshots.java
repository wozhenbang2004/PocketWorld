package dev.pocketengine.features.attachments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import dev.pocketengine.core.RunControl;
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
    private static final class DiskState {final java.util.Map<String,Long> writers=new java.util.HashMap<>();}
    private static final java.util.concurrent.ConcurrentHashMap<String,DiskState> DISK_LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private final Context context;private final File root;private final DiskState disk;
    public AttachmentViewSnapshots(Context context){this.context=context.getApplicationContext();root=new File(this.context.getCacheDir(),"outcome-views");disk=DISK_LOCKS.computeIfAbsent(root.getAbsolutePath(),path->new DiskState());}
    @FunctionalInterface private interface Write {void write(java.io.OutputStream destination)throws Exception;}
    public Intent prepare(String name,String mime,String sha256,byte[] bytes,RunControl control)throws Exception {
        control.check();if(bytes==null||bytes.length>AttachmentBook.MAX_FILE_BYTES||!digest(bytes).equals(sha256))throw new SecurityException("打开文件的真实字节或版本不匹配");
        return prepare(name,mime,sha256,bytes.length,output->{for(int offset=0;offset<bytes.length;){control.check();int n=Math.min(32768,bytes.length-offset);output.write(bytes,offset,n);offset+=n;}},control);
    }
    /** Uses only an original host-issued source. A failed/partial copy never gets a URI. */
    public Intent prepare(OwnerFilePreviewSource source,String mime,RunControl control)throws Exception {
        source.requireCurrent(control);
        return prepare(source.name,mime,source.sha256,source.sizeBytes,output->source.copyTo(output,control),control);
    }
    private Intent prepare(String name,String mime,String sha256,long size,Write writer,RunControl control)throws Exception {
        control.check();if(size<0||size>AttachmentBook.MAX_FILE_BYTES||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new SecurityException("打开文件的原长度或版本无效");
        String token=UUID.randomUUID().toString();File blob=new File(root,token+".blob"),meta=new File(root,token+".json");
        java.nio.channels.FileChannel channel;
        synchronized(disk){
            Files.createDirectories(root.toPath());prune();long total=0;int count=disk.writers.size();for(long reserved:disk.writers.values())total+=reserved;File[] files=root.listFiles((d,n)->n.endsWith(".blob"));
            if(files==null)throw new IllegalStateException("无法核对临时查看空间，未建立新副本");
            for(File file:files){String id=file.getName().substring(0,file.getName().length()-5);if(!disk.writers.containsKey(id)){total+=file.length();count++;}}
            if(count>=4||total+size>MAX_TOTAL)throw new IllegalStateException("临时查看文件已达上限，请稍后重试或直接导出原文件");
            channel=java.nio.channels.FileChannel.open(blob.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS);
            disk.writers.put(token,size);
        }
        // Never call source/Book/current/control while holding disk. A revoked source
        // may synchronously close an old view and acquire disk from inside Book.
        try(channel){
            MessageDigest checksum=MessageDigest.getInstance("SHA-256");long[] written={0};
            try(java.io.OutputStream output=java.nio.channels.Channels.newOutputStream(channel)){
                writer.write(new java.io.OutputStream(){
                    @Override public void write(int value)throws java.io.IOException{write(new byte[]{(byte)value},0,1);}
                    @Override public void write(byte[] value,int offset,int length)throws java.io.IOException{
                        java.util.Objects.checkFromIndexSize(offset,length,value.length);try{control.check();}catch(InterruptedException stopped){throw new java.io.IOException("临时查看写入已中断",stopped);}
                        if(length>size-written[0])throw new SecurityException("临时查看流超过原长度");
                        output.write(value,offset,length);checksum.update(value,offset,length);written[0]+=length;
                    }
                });
                if(written[0]!=size||!hex(checksum.digest()).equals(sha256))throw new SecurityException("临时查看流与原版本不匹配");
                channel.force(true);
            }
            control.check();
            JSONObject record=new JSONObject().put("id",token).put("name",AttachmentBook.displayName(name)).put("mimeType",AttachmentBook.mimeType(mime)).put("size",size).put("sha256",sha256).put("expiresAt",System.currentTimeMillis()+TTL_MS);
            synchronized(disk){
                if(!Files.isRegularFile(blob.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(blob.toPath())!=size)throw new SecurityException("临时查看副本在发布前变化");
                try(FileOutputStream output=new FileOutputStream(meta)){output.write(record.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            }
            control.check();Uri uri=new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath("view").appendPath(token).build();
            Intent result=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.getString("mimeType")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);result.setClipData(ClipData.newRawUri("Pocket World result",uri));return result;
        }catch(Exception failed){
            synchronized(disk){try{Files.deleteIfExists(blob.toPath());Files.deleteIfExists(meta.toPath());}catch(Exception cleanup){failed.addSuppressed(cleanup);}}
            throw failed;
        }finally{synchronized(disk){disk.writers.remove(token);}}
    }
    JSONObject metadata(String token)throws Exception {synchronized(disk){
        checkToken(token);File meta=new File(root,token+".json"),blob=new File(root,token+".blob");
        if(!meta.isFile()||meta.length()>4096||!blob.isFile())throw new IllegalArgumentException("原临时查看文件已不可用，请重新打开原成果");
        JSONObject record=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));
        if(!token.equals(record.getString("id"))||record.getLong("expiresAt")<System.currentTimeMillis()||record.getLong("size")!=blob.length()||blob.length()>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("临时查看文件已过期或变化，请重新打开原成果");return record;
    }}
    File file(String token)throws Exception {synchronized(disk){
        verifiedCopy(token,null);return new File(root,token+".blob");
    }}
    /** Copies into an unpublished private reader, or only checks the stream when null.
     * Caller holds disk and discards the destination if validation fails. */
    private JSONObject verifiedCopy(String token,java.io.OutputStream destination)throws Exception {
        JSONObject meta=metadata(token);long count=0;MessageDigest checksum=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];
        try(InputStream input=Files.newInputStream(new File(root,token+".blob").toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;count+=n;if(count>meta.getLong("size")||count>AttachmentBook.MAX_FILE_BYTES)throw new SecurityException("临时查看文件超过原长度");checksum.update(buffer,0,n);if(destination!=null)destination.write(buffer,0,n);}
        }finally{Arrays.fill(buffer,(byte)0);}
        if(count!=meta.getLong("size")||!hex(checksum.digest()).equals(meta.getString("sha256")))throw new SecurityException("临时查看文件字节已变化");
        return meta;
    }
    /** A seekable read-only FD for these exact verified bytes. The private reader copy is
     * unlinked before delivery, so later canonical/cache-path writes cannot change it.
     * This does not issue a URI grant or retire an existing reader's expiring snapshot. */
    ParcelFileDescriptor openReadOnly(String token)throws Exception {synchronized(disk){
        clearAbandonedReaderCopies();File copy=new File(root,"reader-"+UUID.randomUUID()+".part");boolean created=false;ParcelFileDescriptor opened=null;
        try{
            JSONObject meta;
            try(java.io.OutputStream output=Files.newOutputStream(copy.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){created=true;meta=verifiedCopy(token,output);output.flush();}
            java.io.FileDescriptor descriptor=Os.open(copy.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            try{opened=ParcelFileDescriptor.dup(descriptor);}finally{Os.close(descriptor);}
            Files.delete(copy.toPath());created=false;
            // Validate the actual detached descriptor, not a path that will be reopened later.
            MessageDigest checksum=MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[32768];
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(opened.getFileDescriptor()))){for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;count+=n;if(count>meta.getLong("size"))throw new SecurityException("查看reader副本超过原长度");checksum.update(buffer,0,n);}}
            finally{Arrays.fill(buffer,(byte)0);}
            if(count!=meta.getLong("size")||!hex(checksum.digest()).equals(meta.getString("sha256")))throw new SecurityException("查看reader副本与原快照不同");
            Os.lseek(opened.getFileDescriptor(),0,OsConstants.SEEK_SET);ParcelFileDescriptor result=opened;opened=null;return result;
        }catch(Exception failure){
            if(opened!=null)try{opened.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(created)try{Files.deleteIfExists(copy.toPath());}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
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
     * process. Reservations/publication/open/discard share the per-root disk lock.
     * Active writes run outside it, retain their full reserved size, and are skipped
     * here. No URI returns before metadata fsync. After process death no writer lives;
     * a missing index alone is insufficient: only regular owned UUID-v4 copies older
     * than a full TTL qualify.
     * The timestamp is an extra retention gate, not a cross-process/OS-write proof.
     * Unknown metadata, names and file types never establish deletion permission. */
    private void prune()throws Exception {
        if(!Files.isDirectory(root.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new SecurityException("临时查看目录不是原私有目录");
        long now=System.currentTimeMillis();
        try(java.nio.file.DirectoryStream<java.nio.file.Path> indices=Files.newDirectoryStream(root.toPath(),"*.json")){
            for(java.nio.file.Path meta:indices){
                String name=meta.getFileName().toString(),token=name.substring(0,name.length()-5);
                if(disk.writers.containsKey(token)||!ownedSnapshotToken(token)||!Files.isRegularFile(meta,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(meta)>4096)continue;
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
                String name=blob.getFileName().toString(),token=name.substring(0,name.length()-5);if(disk.writers.containsKey(token)||!ownedSnapshotToken(token))continue;
                java.nio.file.attribute.BasicFileAttributes attributes=Files.readAttributes(blob,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);long modified=attributes.lastModifiedTime().toMillis();
                if(!attributes.isRegularFile()||attributes.size()>AttachmentBook.MAX_FILE_BYTES||modified<=0||modified>=now-TTL_MS)continue;
                if(Files.notExists(root.toPath().resolve(token+".json"),java.nio.file.LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(blob);
            }
        }
    }
    private static boolean ownedSnapshotToken(String token){return token.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");}
    private static void checkToken(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("临时查看引用无效");}
    private static String digest(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return result.toString();}
}
