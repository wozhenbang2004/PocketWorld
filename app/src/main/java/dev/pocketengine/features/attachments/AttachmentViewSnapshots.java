package dev.pocketengine.features.attachments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.io.FileOutputStream;
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
        JSONObject meta=metadata(token);File blob=new File(root,token+".blob");byte[] bytes=Files.readAllBytes(blob.toPath());
        try{if(!digest(bytes).equals(meta.getString("sha256")))throw new SecurityException("临时查看文件字节已变化");return blob;}finally{Arrays.fill(bytes,(byte)0);}
    }}
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
    private void prune(){File[] files=root.listFiles((d,n)->n.endsWith(".json"));for(File file:files==null?new File[0]:files){boolean expired=true;try{expired=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).optLong("expiresAt")<System.currentTimeMillis();}catch(Exception ignored){}if(expired){new File(root,file.getName().replace(".json",".blob")).delete();file.delete();}}}
    private static void checkToken(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("临时查看引用无效");}
    private static String digest(byte[] bytes)throws Exception{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
