package dev.pocketengine.browser;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-picked uploads are copied with profile provenance; stale picks never reach another page. */
public final class BrowserUploads {
    public static final class CleanupRequired extends Exception {CleanupRequired(Exception cause){super("失效上传的临时附件清理未完成，请核对原任务附件",cause);}}
    /** One native picker destination. Closing it actively interrupts an in-progress file copy. */
    public static final class Destination implements BrowserTransferFence,AutoCloseable {
        final BrowserProfileRegistry.Session session;final JSONObject provenance;final boolean multiple;
        private final BooleanSupplier pageCurrent;private final RunControl original;private final Runnable stopped=this::close;
        private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
        private final java.util.concurrent.CopyOnWriteArrayList<Runnable> closers=new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AutoCloseable profileWatch;
        public Destination(BrowserProfileRegistry.Session session,JSONObject nativeIdentity,boolean multiple,BooleanSupplier pageCurrent,RunControl original)throws Exception {
            this.session=java.util.Objects.requireNonNull(session);this.multiple=multiple;this.pageCurrent=java.util.Objects.requireNonNull(pageCurrent);this.original=original;provenance=session.identity().put("kind","browser_upload");
            for(String key:new String[]{"profileId","profileRevision","browserSessionId"})if(nativeIdentity.has(key)&&!String.valueOf(provenance.get(key)).equals(String.valueOf(nativeIdentity.get(key))))throw new SecurityException("上传原页面与所选登录资料不一致");
            for(String key:new String[]{"chooserId","tabId","documentId","navigation","destinationOrigin","destinationUrl","destinationDigest","taskId","runId","epoch","browserSpaceId","tabGeneration"})if(nativeIdentity.has(key))provenance.put(key,nativeIdentity.get(key));
            BrowserTaskSpace.uuid(provenance.getString("chooserId"));BrowserTaskSpace.uuid(provenance.getString("tabId"));if(provenance.getLong("navigation")<0||!provenance.getString("destinationDigest").matches("[a-f0-9]{64}"))throw new SecurityException("上传目的地绑定无效");
            java.net.URI target=new java.net.URI(provenance.getString("destinationUrl"));if(target.getHost()==null||target.getUserInfo()!=null||target.getRawQuery()!=null||target.getRawFragment()!=null||!java.util.Set.of("http","https").contains(target.getScheme()))throw new SecurityException("上传目的地须为不含凭据的原网页标识");
            if(original!=null){for(String key:new String[]{"taskId","runId","browserSpaceId"})BrowserTaskSpace.uuid(provenance.getString(key));if(!original.matchesHostRun(provenance.getString("taskId"),provenance.getString("runId"),provenance.getInt("epoch"))||!provenance.has("tabGeneration"))throw new SecurityException("上传未绑定原任务执行");}else if(provenance.has("taskId"))throw new SecurityException("任务上传缺少原控制器");
            profileWatch=session.watch(stopped);if(original!=null)original.onCancel(stopped);
        }
        public JSONObject identity(){try{return new JSONObject(provenance.toString());}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        public boolean current(){return active.get()&&session.current()&&(original==null||!original.isCancelled())&&pageCurrent.getAsBoolean();}
        public boolean matchesDestination(String url){try{return current()&&provenance.getString("destinationDigest").equals(destinationDigest(url));}catch(Exception invalid){return false;}}
        public void check()throws Exception {if(!current()||Thread.currentThread().isInterrupted())throw new InterruptedException("上传所属任务、网页或目的地已变化");if(original!=null)original.check();}
        public AutoCloseable watch(Runnable closer){java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean();Runnable action=()->{if(once.compareAndSet(false,true))try{closer.run();}catch(RuntimeException ignored){}};closers.add(action);if(!current())action.run();return ()->closers.remove(action);}
        public void close(){if(active.compareAndSet(true,false)){for(Runnable closer:closers)closer.run();closers.clear();}if(original!=null)original.removeCancelAction(stopped);try{if(profileWatch!=null)profileWatch.close();}catch(Exception ignored){}}
    }
    private BrowserUploads(){}
    public static JSONObject destinationIdentity(String url)throws Exception {java.net.URI target=new java.net.URI(url);if(target.getHost()==null||target.getUserInfo()!=null||!java.util.Set.of("http","https").contains(target.getScheme()))throw new SecurityException("上传须来自有效网页");return new JSONObject().put("destinationDigest",destinationDigest(url)).put("destinationOrigin",new java.net.URI(target.getScheme(),null,target.getHost(),target.getPort(),null,null,null).toASCIIString()).put("destinationUrl",new java.net.URI(target.getScheme(),null,target.getHost(),target.getPort(),target.getPath(),null,null).toASCIIString());}
    private static String destinationDigest(String url)throws Exception {StringBuilder out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    public static JSONObject importPicked(Context context,Intent data,Destination destination)throws Exception {destination.check();return importPicked(context,data,destination.session,null,destination::current,destination);}
    public static JSONObject importPicked(Context context,Intent data,BrowserProfileRegistry.Session session,String taskId,BooleanSupplier pageCurrent)throws Exception {
        return importPicked(context,data,session,taskId,pageCurrent,null);
    }
    private static JSONObject importPicked(Context context,Intent data,BrowserProfileRegistry.Session session,String taskId,BooleanSupplier pageCurrent,Destination destination)throws Exception {
        check(session,pageCurrent);LinkedHashSet<Uri> picked=new LinkedHashSet<>();if(data.getData()!=null)picked.add(data.getData());
        ClipData clip=data.getClipData();if(clip!=null){if(clip.getItemCount()>10)throw new IllegalArgumentException("每次最多上传10个文件");for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri==null)throw new IllegalArgumentException("上传必须由文件选择器提供文件");picked.add(uri);}}
        if(picked.isEmpty()||picked.size()>10)throw new IllegalArgumentException("请选择1至10个文件");
        if(destination!=null&&!destination.multiple&&picked.size()!=1)throw new IllegalArgumentException("此网页只允许选择一个上传文件");
        AttachmentRepository repository=AttachmentRepository.get(context);JSONArray imported=new JSONArray();
        try{
            for(Uri uri:picked){
                check(session,pageCurrent);if(!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().equals(context.getPackageName()+".attachments"))throw new SecurityException("上传只接受系统选择器授予的外部文件");
                String name="upload",mime=context.getContentResolver().getType(uri);long size=-1;
                try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s);}
                }
                if(size>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("上传文件超过20MiB上限");
                JSONObject source=destination==null?session.identity().put("kind","browser_upload"):new JSONObject(destination.provenance.toString());if(taskId!=null&&!taskId.isEmpty())source.put("taskId",taskId);
                InputStream original=context.getContentResolver().openInputStream(uri);if(original==null)throw new IllegalStateException("上传文件无法读取");
                try(AutoCloseable registration=(destination==null?session:destination).watch(()->{try{original.close();}catch(java.io.IOException ignored){}});InputStream input=new FilterInputStream(original){@Override public int read()throws java.io.IOException{verify();return super.read();}@Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{verify();return super.read(bytes,offset,length);}private void verify()throws java.io.IOException{if(!session.current()||!pageCurrent.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("上传会话已变化");}}){imported.put(repository.book.importBytes(input,name,mime,source));}
                check(session,pageCurrent);
            }
            return new JSONObject().put("status","imported").put("attachments",imported);
        }catch(Exception failure){discard(context,imported);throw failure;}
    }
    public static void discard(Context context,JSONArray imported)throws Exception {
        Exception failed=null;AttachmentRepository repository=AttachmentRepository.get(context);
        for(int i=0;i<imported.length();i++)try{repository.remove(imported.getJSONObject(i).getString("id"));}catch(Exception cleanup){failed=cleanup;}
        if(failed!=null)throw new CleanupRequired(failed);
    }
    private static void check(BrowserProfileRegistry.Session session,BooleanSupplier current)throws InterruptedException {if(session==null||!session.current()||!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException("上传所属网页或资料已变化");}
}
