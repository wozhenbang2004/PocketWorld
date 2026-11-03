package dev.pocketengine.features.attachments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** SAF/intent grants are acquired by Android; this repository never fabricates a URI permission. */
public final class AttachmentRepository {
    private static final java.util.Map<String,AttachmentRepository> INSTANCES=new java.util.HashMap<>();
    private final Context context;
    public final AttachmentBook book;
    private AttachmentRepository(Context context){this.context=context.getApplicationContext();book=new AttachmentBook(new java.io.File(this.context.getFilesDir(),"attachments"));}
    public static synchronized AttachmentRepository get(Context context){String key=context.getApplicationContext().getFilesDir().getAbsolutePath();AttachmentRepository found=INSTANCES.get(key);if(found==null){found=new AttachmentRepository(context);INSTANCES.put(key,found);}return found;}
    /** Native private-original reader only. This verifies Book scope/bytes, not task execution
     * permission: model callers must separately retain their original selected-source authority. */
    public OwnerFilePreviewSource privateOriginalForHost(String id,String sha,RunControl control)throws Exception{
        JSONObject meta=book.get(id),source=meta.optJSONObject("source");
        if(!"owner".equals(meta.optString("principalId"))||!"private".equals(meta.optString("spaceId"))||meta.optBoolean("outputSourceRetired")||source!=null&&(source.has("groupSpaceId")||source.optString("kind").startsWith("group_")))throw new SecurityException("此输入不是原私人附件，群只读权限不会转为私人服务上传授权");
        book.requireUnchanged(id,sha,control);return new OwnerFilePreviewSource("attachments",meta,c->book.verifiedBytes(id,sha,c),c->{JSONObject current=book.get(id);if(!"owner".equals(current.optString("principalId"))||!"private".equals(current.optString("spaceId"))||current.optBoolean("outputSourceRetired"))throw new SecurityException("原私人附件已撤回");book.requireUnchanged(id,sha,c);});
    }
    public synchronized JSONObject importUri(Uri uri,int receivedFlags,String taskId,String projectId)throws Exception{
        requireContent(uri);if(context.getPackageName().concat(".attachments").equals(uri.getAuthority()))throw new SecurityException("外部文件选择结果不能引用应用私有附件");boolean prior=persisted(uri),kept=prior;
        if((receivedFlags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0&&(receivedFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)try{context.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);kept=persisted(uri);}catch(SecurityException unsupported){kept=prior;}
        String name="attachment",mime=null;long declared=-1;
        try{
            mime=context.getContentResolver().getType(uri);
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))declared=cursor.getLong(s);}}
            if(declared>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("此附件超过20MiB导入上限");
            JSONObject source=new JSONObject().put("kind","content_uri").put("uri",uri.toString()).put("persistentReadPermission",kept);
            references(source,taskId,projectId);
            try(InputStream input=context.getContentResolver().openInputStream(uri)){return book.importBytes(input,name,mime,source);}
        }catch(Exception error){if(!prior&&kept)release(uri);throw error;}
    }
    public synchronized JSONObject importIntent(Intent intent,String taskId,String projectId)throws Exception{
        if(intent==null)throw new IllegalArgumentException("没有可接收的附件");LinkedHashSet<Uri> uris=new LinkedHashSet<>();
        Uri data=intent.getData();if(data!=null)uris.add(data);
        ClipData clip=intent.getClipData();if(clip!=null){if(clip.getItemCount()>10)throw new IllegalArgumentException("每次最多接收10个附件");for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null)uris.add(uri);}}
        if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())){java.util.ArrayList<?> streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(streams!=null)for(Object stream:streams){if(!(stream instanceof Uri))throw new IllegalArgumentException("分享附件引用无效");uris.add((Uri)stream);}}
        else {Object stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(stream!=null){if(!(stream instanceof Uri))throw new IllegalArgumentException("分享附件引用无效");uris.add((Uri)stream);}}
        if(uris.size()>10)throw new IllegalArgumentException("每次最多接收10个附件");JSONArray imported=new JSONArray(),errors=new JSONArray();
        for(Uri uri:uris)try{imported.put(importUri(uri,intent.getFlags(),taskId,projectId));}catch(Exception failure){errors.put(new JSONObject().put("message",failure instanceof SecurityException?"附件授权已失效，请重新选择文件":safe(failure)));}
        if(uris.isEmpty()){CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);if(text==null||text.length()==0)throw new IllegalArgumentException("分享内容没有可读取的文件或文字");if(text.length()>65536)throw new IllegalArgumentException("分享文字最多65536字符");JSONObject source=new JSONObject().put("kind","shared_text");references(source,taskId,projectId);imported.put(book.importBytes(new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)),"shared-text.txt","text/plain",source));}
        return new JSONObject().put("attachments",imported).put("errors",errors).put("status",errors.length()==0?"imported":imported.length()>0?"partially_imported":"failed");
    }
    public synchronized JSONObject exportToUri(String id,Uri destination)throws Exception{
        return exportVerifiedToUri(id,book.get(id).getString("sha256"),destination,new RunControl());
    }
    /** Native SAF export of the exact reviewed immutable artifact, with cancellation and version rechecks. */
    public JSONObject exportVerifiedToUri(String id,String expectedSha256,Uri destination,RunControl control)throws Exception{
        requireContent(destination);java.util.Objects.requireNonNull(control).check();byte[] bytes=book.verifiedBytes(id,expectedSha256,control);long copied=0;
        try {
            OutputStream opened=context.getContentResolver().openOutputStream(destination,"wt");if(opened==null)throw new IllegalStateException("选择的位置无法写入");
            Runnable cancel=()->{try{opened.close();}catch(Exception ignored){}};control.onCancel(cancel);
            try(OutputStream output=opened){
                for(int offset=0;offset<bytes.length;){control.checkpoint();int count=Math.min(32768,bytes.length-offset);output.write(bytes,offset,count);offset+=count;copied+=count;}
                control.checkpoint();output.flush();
            }finally{control.removeCancelAction(cancel);}
            book.requireUnchanged(id,expectedSha256,control);control.checkpoint();
            return new JSONObject().put("id",id).put("sha256",expectedSha256).put("status","exported").put("bytes",copied).put("verified",true);
        }catch(Exception stopped){throw new IllegalStateException("导出未完整确认，所选目标可能已有部分内容；请核对后再操作",stopped);}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    /** Export an already scoped/verified snapshot; no path, URL, new artifact or transmission is created. */
    public JSONObject exportNativeSnapshot(byte[] bytes,String expectedSha256,Uri destination,RunControl control)throws Exception {
        requireContent(destination);control.check();StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(bytes.length>AttachmentBook.MAX_FILE_BYTES||!hash.toString().equals(expectedSha256))throw new SecurityException("原成果快照版本不匹配");
        try {
            OutputStream opened=context.getContentResolver().openOutputStream(destination,"wt");if(opened==null)throw new IllegalStateException("所选位置无法写入");Runnable cancel=()->{try{opened.close();}catch(Exception ignored){}};control.onCancel(cancel);
            try(OutputStream output=opened){for(int at=0;at<bytes.length;){control.check();int n=Math.min(32768,bytes.length-at);output.write(bytes,at,n);at+=n;}output.flush();control.check();}
            finally{control.removeCancelAction(cancel);}
            return new JSONObject().put("status","exported").put("sha256",expectedSha256).put("bytes",bytes.length).put("verified",true).put("verifiedBusinessSuccess",false);
        }catch(Exception failure){throw new IllegalStateException("导出未完整确认，目标可能已有部分内容；请核对所选位置",failure);}
    }
    public synchronized void remove(String id)throws Exception{JSONObject source=book.internal(id).optJSONObject("source");book.remove(id);if(source!=null&&source.optBoolean("persistentReadPermission")){String original=source.optString("uri");JSONArray rest=book.list();for(int i=0;i<rest.length();i++){JSONObject other=book.internal(rest.getJSONObject(i).getString("id")).optJSONObject("source");if(other!=null&&original.equals(other.optString("uri")))return;}if(!original.isEmpty())release(Uri.parse(original));}}
    public Uri uri(String id)throws Exception{book.get(id);return new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments").appendPath(id).build();}
    /** Native reviewed-version open. The later external URI read uses a verified immutable copy, never the changing canonical path. */
    public Intent verifiedViewIntent(String id,String expectedSha256,RunControl control)throws Exception {
        AttachmentViewSnapshots snapshots=new AttachmentViewSnapshots(context);Intent prepared=null;
        try {
            AttachmentBook.VerifiedMetadata expected=book.readVerifiedMetadata(id,expectedSha256,control);byte[] bytes=book.readVerifiedSnapshot(id,expectedSha256,control);
            try {
                prepared=snapshots.prepare(expected.name,expected.mimeType,expected.sha256,bytes,control);
                AttachmentBook.VerifiedMetadata current=book.readVerifiedMetadata(id,expectedSha256,control);
                if(current.size!=expected.size||!current.name.equals(expected.name)||!current.mimeType.equals(expected.mimeType))throw new AttachmentBook.InputChangedException();
                control.checkpoint();return prepared;
            }finally{java.util.Arrays.fill(bytes,(byte)0);}
        }catch(Exception failure){
            if(prepared!=null)try{snapshots.discard(prepared.getData().getLastPathSegment());}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof AttachmentBook.InputChangedException)throw new IllegalStateException("原文件或固定版本已变化，请重新读取后打开；未交付其他内容",failure);
            throw failure;
        }
    }
    /** A native preparation that failed its final scope check has not issued this URI grant. */
    void discardVerifiedView(Intent prepared)throws Exception {
        Uri uri=prepared==null?null:prepared.getData();
        if(uri==null||!"content".equals(uri.getScheme())||!context.getPackageName().concat(".attachments").equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!"view".equals(uri.getPathSegments().get(0))||uri.getQuery()!=null||uri.getFragment()!=null)throw new SecurityException("只能清理本次尚未交付的原生查看快照");
        new AttachmentViewSnapshots(context).discard(uri.getLastPathSegment());
    }
    public Intent viewIntent(String id)throws Exception{return viewIntent(id,book.get(id).getString("sha256"));}
    Intent viewIntent(String id,String expectedSha256)throws Exception{return verifiedViewIntent(id,expectedSha256,new RunControl());}
    public Intent shareIntent(String id)throws Exception{return shareIntent(id,book.get(id).getString("sha256"));}
    Intent shareIntent(String id,String expectedSha256)throws Exception{Intent prepared=viewIntent(id,expectedSha256);Uri uri=prepared.getData();Intent share=new Intent(Intent.ACTION_SEND).setType(prepared.getType()).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Pocket World attachment",uri));return share;}
    private boolean persisted(Uri uri){for(UriPermission permission:context.getContentResolver().getPersistedUriPermissions())if(uri.equals(permission.getUri())&&permission.isReadPermission())return true;return false;}
    private void release(Uri uri){try{context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
    private static void requireContent(Uri uri){if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null)throw new IllegalArgumentException("附件只接受Android授权的content URI");}
    private static void references(JSONObject source,String task,String project)throws Exception{if(task!=null&&!task.isEmpty()){if(task.length()>100)throw new IllegalArgumentException("任务引用过长");source.put("taskId",task);}if(project!=null&&!project.isEmpty()){if(project.length()>100)throw new IllegalArgumentException("项目引用过长");source.put("projectId",project);}}
    private static String safe(Exception error){String message=error.getMessage();if(message==null||message.contains("content://"))return "附件未能导入，请重新选择文件";return message.length()>200?message.substring(0,200):message;}
}
