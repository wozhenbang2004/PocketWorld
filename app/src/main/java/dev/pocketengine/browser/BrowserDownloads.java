package dev.pocketengine.browser;

import android.content.Context;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.File;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executor download receipts, not task scheduling. Credentials live only in the active request. */
public final class BrowserDownloads {
    interface Cookies {String forUrl(String url)throws Exception;}
    @FunctionalInterface public interface DocumentSource {BrowserDocumentInput open()throws Exception;}
    private static final Map<String,BrowserDownloads> INSTANCES=new LinkedHashMap<>();
    private final AttachmentRepository attachments;
    private final File receipts;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,JSONObject> records=new LinkedHashMap<>();
    private final Map<String,AtomicBoolean> cancellations=new LinkedHashMap<>();
    private final Map<String,HttpURLConnection> connections=new LinkedHashMap<>();
    private final Map<String,BrowserProfileRegistry.Session> sessions=new LinkedHashMap<>();
    private final Map<String,BrowserTransferFence> fences=new LinkedHashMap<>();
    private BrowserDownloads(Context context)throws Exception{
        attachments=AttachmentRepository.get(context);receipts=new File(context.getFilesDir(),"browser-download-receipts.json");
        if(receipts.isFile()){JSONArray old=new JSONArray(new String(Files.readAllBytes(receipts.toPath()),StandardCharsets.UTF_8));for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(item.optString("status").matches("queued|running|stopping"))item.put("status","interrupted").put("message","下载已中断，未自动重试；请核对已有附件后重新请求");records.put(item.getString("id"),item);}persist();}
    }
    public static synchronized BrowserDownloads get(Context context)throws Exception{String path=context.getFilesDir().getAbsolutePath();BrowserDownloads found=INSTANCES.get(path);if(found==null){found=new BrowserDownloads(context.getApplicationContext());INSTANCES.put(path,found);}return found;}
    public static synchronized void closeFor(Context context){BrowserDownloads found=INSTANCES.remove(context.getFilesDir().getAbsolutePath());if(found!=null)found.close();}
    public synchronized JSONObject startDocument(String name,JSONObject identity,BrowserProfileRegistry.Session session,BrowserTransferFence fence,DocumentSource source,Consumer<JSONObject> updates)throws Exception {
        java.util.Objects.requireNonNull(session);java.util.Objects.requireNonNull(fence);fence.check();if(!session.current()||cancellations.size()>=3)throw new SecurityException("原浏览器会话不可下载或已达3个排队上限");
        BrowserTaskSpace.uuid(identity.getString("documentId"));BrowserTaskSpace.uuid(identity.getString("operationId"));if(identity.getLong("navigation")<0)throw new SecurityException("文档下载缺少原导航版本");
        while(records.size()>=100){String oldest=null;for(String key:records.keySet())if(!cancellations.containsKey(key)){oldest=key;break;}if(oldest==null)throw new IllegalStateException("下载记录已满");records.remove(oldest);}
        String id=UUID.randomUUID().toString();JSONObject record=new JSONObject(identity.toString()).put("id",id).put("kind","download").put("status","queued").put("name",AttachmentBook.displayName(name)).put("acquisitionMethod","original_document_blob_data_stream").put("createdAt",System.currentTimeMillis()).put("verified",false);
        for(String key:new String[]{"profileId","profileRevision","browserSessionId"})record.put(key,session.identity().get(key));
        AtomicBoolean cancelled=new AtomicBoolean();records.put(id,record);cancellations.put(id,cancelled);sessions.put(id,session);fences.put(id,fence);persist();
        try{worker.execute(()->downloadDocument(id,source,cancelled,fence,updates));}catch(RuntimeException rejected){cancellations.remove(id);sessions.remove(id);fences.remove(id);update(id,"failed","文档下载线程不可用",null,updates);throw rejected;}return copy(record);
    }
    private void downloadDocument(String id,DocumentSource source,AtomicBoolean cancellation,BrowserTransferFence fence,Consumer<JSONObject> updates){
        JSONObject committed=null;
        try{
            check(cancellation,fence);update(id,"running",null,null,updates);JSONObject record; synchronized(this){record=copy(required(id));}
            try(BrowserDocumentInput document=source.open();InputStream guarded=new FilterInputStream(document){@Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{try{check(cancellation,fence);}catch(Exception stopped){throw new java.io.InterruptedIOException("原文档下载已停止");}return super.read(bytes,offset,length);}}){
                JSONObject provenance=new JSONObject(record.toString()).put("kind","browser_document_download").put("untrustedPageContent",true);provenance.remove("name");
                committed=attachments.book.importBytes(guarded,record.getString("name"),document.mimeType(),provenance);
            }
            check(cancellation,fence);JSONObject actual=attachments.book.get(committed.getString("id"));if(!actual.getString("sha256").equals(committed.getString("sha256"))||actual.getLong("size")!=committed.getLong("size"))throw new IllegalStateException("文档下载文件无法重新打开核对");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long reopened=0;byte[] buffer=new byte[32768];try(InputStream input=Files.newInputStream(attachments.book.file(actual.getString("id")).toPath())){for(int n;(n=input.read(buffer))!=-1;){check(cancellation,fence);if(n==0)continue;reopened+=n;digest.update(buffer,0,n);}}finally{java.util.Arrays.fill(buffer,(byte)0);}StringBuilder sha=new StringBuilder();for(byte value:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",value&255));if(reopened!=actual.getLong("size")||!sha.toString().equals(actual.getString("sha256")))throw new IllegalStateException("文档下载重新打开的真实字节hash不同");
            update(id,"downloaded",null,committed,updates);
        }catch(Exception failure){boolean stopped=cancellation.get()||!fence.current();if(committed!=null)try{attachments.remove(committed.getString("id"));committed=null;}catch(Exception cleanupUnknown){}
            try{update(id,committed!=null?"unknown":stopped?"cancelled":"failed",committed!=null?"文件可能已保存，清理或回执待核对，未重新下载":stopped?"文档下载已停止":safe(failure),committed,updates);}catch(Exception ignored){}
        }finally{synchronized(this){cancellations.remove(id);sessions.remove(id);fences.remove(id);}}
    }
    private synchronized void close(){for(AtomicBoolean cancelled:cancellations.values())cancelled.set(true);for(HttpURLConnection connection:connections.values())connection.disconnect();worker.shutdownNow();}
    public synchronized JSONObject start(String url,String userAgent,String cookie,String name,String mime,JSONObject identity,Consumer<JSONObject> updates)throws Exception{
        return start(url,userAgent,target->target.equals(url)?cookie:null,name,mime,identity,null,updates);
    }
    synchronized JSONObject start(String url,String userAgent,Cookies cookies,String name,String mime,JSONObject identity,BrowserProfileRegistry.Session session,Consumer<JSONObject> updates)throws Exception{
        return start(url,userAgent,cookies,name,mime,identity,session,session,updates);
    }
    synchronized JSONObject start(String url,String userAgent,Cookies cookies,String name,String mime,JSONObject identity,BrowserProfileRegistry.Session session,BrowserTransferFence fence,Consumer<JSONObject> updates)throws Exception{
        if(session!=null&&!session.current())throw new SecurityException("下载所属浏览器会话已失效");
        URI target=valid(url);if(cancellations.size()>=3)throw new IllegalStateException("最多同时排队3个下载");
        while(records.size()>=100){String oldest=null;for(Map.Entry<String,JSONObject> entry:records.entrySet())if(!cancellations.containsKey(entry.getKey())){oldest=entry.getKey();break;}if(oldest==null)throw new IllegalStateException("下载记录已满");records.remove(oldest);}
        String id=UUID.randomUUID().toString();AtomicBoolean cancelled=new AtomicBoolean();JSONObject record=new JSONObject().put("id",id).put("kind","download").put("status","queued").put("sourceOrigin",origin(target)).put("name",AttachmentBook.displayName(name)).put("createdAt",System.currentTimeMillis()).put("verified",false);
        if(identity!=null)for(String key:new String[]{"taskId","runId","leaseId","epoch","browserSpaceId","tabId","tabGeneration","operationId"})if(identity.has(key))record.put(key,identity.get(key));
        if(session!=null){record.put("profileId",session.profileId).put("profileRevision",session.profileRevision).put("browserSessionId",session.sessionId);sessions.put(id,session);}
        else record.put("profileId",BrowserLease.PROFILE);
        if(fence!=null)fences.put(id,fence);records.put(id,record);cancellations.put(id,cancelled);persist();
        worker.execute(()->download(id,target,userAgent,cookies,name,mime,cancelled,session,fence,updates));return copy(record);
    }
    public synchronized JSONArray list()throws Exception{JSONArray array=new JSONArray();for(JSONObject record:records.values())array.put(copy(record));return array;}
    public synchronized JSONObject status(String id)throws Exception{return copy(required(id));}
    public synchronized JSONObject cancel(String id)throws Exception{JSONObject record=required(id);AtomicBoolean cancellation=cancellations.get(id);if(cancellation!=null){cancellation.set(true);HttpURLConnection active=connections.get(id);if(active!=null)active.disconnect();record.put("status","stopping");persist();}return copy(record);}
    public synchronized JSONArray list(String profileId)throws Exception{JSONArray array=new JSONArray();for(JSONObject record:records.values())if(profileId.equals(record.optString("profileId",BrowserLease.PROFILE)))array.put(copy(record));return array;}
    public synchronized JSONObject status(String id,String profileId)throws Exception{requireProfile(id,profileId);return status(id);}
    public synchronized JSONObject cancel(String id,String profileId)throws Exception{requireProfile(id,profileId);return cancel(id);}
    private void requireProfile(String id,String profileId)throws Exception{if(!profileId.equals(required(id).optString("profileId",BrowserLease.PROFILE)))throw new SecurityException("下载不属于当前浏览器资料");}
    public synchronized void cancelSession(String sessionId)throws Exception{for(Map.Entry<String,BrowserProfileRegistry.Session> entry:sessions.entrySet())if(entry.getValue().sessionId.equals(sessionId))cancel(entry.getKey());}
    private void download(String id,URI initial,String userAgent,Cookies cookies,String name,String mime,AtomicBoolean cancellation,BrowserProfileRegistry.Session session,BrowserTransferFence fence,Consumer<JSONObject> updates){
        JSONObject committed=null;AutoCloseable sessionWatch=null;final long deadline=System.nanoTime()+120_000_000_000L;
        try{
            update(id,"running",null,null,updates);URI uri=initial;HttpURLConnection connection=null;
            for(int redirect=0;redirect<=5;redirect++){
                check(cancellation,fence);if(System.nanoTime()>=deadline)throw new IllegalStateException("下载总时限已到，请核对网络后重新请求");connection=(HttpURLConnection)uri.toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod("GET");connection.setRequestProperty("Accept-Encoding","identity");
                if(userAgent!=null&&!userAgent.isEmpty())connection.setRequestProperty("User-Agent",userAgent);
                String cookie=cookies==null?null:cookies.forUrl(uri.toString());if(cookie!=null&&!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
                if(sessionWatch!=null)sessionWatch.close();final HttpURLConnection owned=connection;sessionWatch=fence==null?null:fence.watch(owned::disconnect);
                check(cancellation,fence);synchronized(this){cheapCheck(cancellation,fence);connections.put(id,connection);}int code=connection.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null||redirect==5)throw new IllegalStateException("下载重定向无效或次数过多");URI next=valid(uri.resolve(location).toString());if(!sameOrigin(initial,next))throw new SecurityException("下载跳转到另一站点，请在网页中重新确认下载；未转发登录凭据");uri=next;continue;}
                if(code<200||code>=300)throw new IllegalStateException("下载服务返回HTTP "+code);
                if(connection.getContentLengthLong()>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("此文件超过20MiB附件上限");
                String type=connection.getContentType();if(type==null||type.isEmpty())type=mime;if(type!=null&&type.contains(";"))type=type.substring(0,type.indexOf(';')).trim();
                JSONObject source=new JSONObject().put("kind","browser_download").put("downloadId",id).put("origin",origin(initial));synchronized(this){JSONObject record=required(id);for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","operationId"})if(record.has(key))source.put(key,record.get(key));}
                JSONObject imported;
                try(InputStream stream=new FilterInputStream(connection.getInputStream()){@Override public int read(byte[] buffer,int offset,int length)throws java.io.IOException{checkTransfer();return super.read(buffer,offset,length);}@Override public int read()throws java.io.IOException{checkTransfer();return super.read();}private void checkTransfer()throws java.io.IOException{try{check(cancellation,fence);}catch(Exception stopped){throw new java.io.InterruptedIOException("下载原任务、页或会话已停止");}if(System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException("下载总时限已到");}}){check(cancellation,fence);imported=attachments.book.importBytes(stream,name,type,source);}
                committed=imported;check(cancellation,fence);update(id,"downloaded",null,imported,updates);return;
            }
            throw new IllegalStateException("下载未取得文件");
        }catch(Exception failure){boolean fenced=cancellation.get()||session!=null&&!session.current()||fence!=null&&!fence.current();if(!fenced&&fence!=null)try{fence.check();}catch(Exception revoked){fenced=true;}if(fenced&&committed!=null)try{attachments.remove(committed.getString("id"));committed=null;}catch(Exception cleanupFailed){}
            try{update(id,committed!=null?"unknown":fenced?"cancelled":"failed",committed!=null?"文件可能已导入，清理或回执未完成，请在原资料下载记录核对":fenced?"下载已停止，未转交给另一浏览器资料":safe(failure),committed,updates);}catch(Exception ignored){}}
        finally{if(sessionWatch!=null)try{sessionWatch.close();}catch(Exception ignored){}synchronized(this){HttpURLConnection connection=connections.remove(id);if(connection!=null)connection.disconnect();cancellations.remove(id);sessions.remove(id);fences.remove(id);}}
    }
    private void update(String id,String status,String message,JSONObject attachment,Consumer<JSONObject> updates)throws Exception{JSONObject event;BrowserTransferFence fence;AtomicBoolean cancellation;synchronized(this){fence=fences.get(id);cancellation=cancellations.get(id);}if(status.equals("downloaded"))check(cancellation,fence);synchronized(this){if(status.equals("downloaded"))cheapCheck(cancellation,fence);JSONObject record=required(id);record.put("status",status).put("updatedAt",System.currentTimeMillis());if(message!=null)record.put("message",message);if(attachment!=null)record.put("attachment",attachment);persist();event=copy(record);}if(updates!=null)try{updates.accept(event);}catch(RuntimeException ignored){}}
    private JSONObject required(String id){JSONObject found=records.get(id);if(found==null)throw new IllegalArgumentException("下载记录不存在");return found;}
    private void persist()throws Exception{JSONArray all=new JSONArray();for(JSONObject record:records.values())all.put(record);android.util.AtomicFile file=new android.util.AtomicFile(receipts);java.io.FileOutputStream output=null;try{output=file.startWrite();output.write(all.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(Exception failure){if(output!=null)file.failWrite(output);throw failure;}}
    private static JSONObject copy(JSONObject source)throws Exception{return new JSONObject(source.toString());}
    private static void check(AtomicBoolean cancellation,BrowserTransferFence fence)throws Exception{cheapCheck(cancellation,fence);if(fence!=null)fence.check();}
    private static void cheapCheck(AtomicBoolean cancellation,BrowserTransferFence fence)throws InterruptedException{if(cancellation!=null&&cancellation.get()||fence!=null&&!fence.current()||Thread.currentThread().isInterrupted())throw new InterruptedException("下载已停止");}
    public static URI valid(String value)throws Exception{URI uri=new URI(value);if(!uri.isAbsolute()||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())))throw new IllegalArgumentException("下载只支持有效的http/https地址");return uri;}
    public static boolean sameOrigin(URI first,URI second){return first.getScheme().equalsIgnoreCase(second.getScheme())&&first.getHost().equalsIgnoreCase(second.getHost())&&port(first)==port(second);}
    private static int port(URI uri){return uri.getPort()==-1?(uri.getScheme().equalsIgnoreCase("https")?443:80):uri.getPort();}
    private static String origin(URI uri){return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort());}
    private static String safe(Exception failure){String message=failure.getMessage();if(message==null||message.contains("http://")||message.contains("https://")||message.contains("Cookie"))return "下载未完成，请检查网络与网页登录状态";return message.length()>240?message.substring(0,240):message;}
}
