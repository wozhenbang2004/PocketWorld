package dev.pocketengine.browser;

import android.webkit.WebView;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Streams one current document's blob/data link. It never accepts an HTTP URL or a file path. */
public final class BrowserDocumentInput extends InputStream {
    public static final int CHUNK=32768,MAX_FILE_BYTES=20*1024*1024;
    @FunctionalInterface public interface Check {void check()throws Exception;}
    private final WebView view;private final Check current;private final String slot,token,url;private final long deadline=System.nanoTime()+120_000_000_000L;
    private int offset,total;private byte[] chunk=new byte[0];private int position;private boolean closed;private String mime="application/octet-stream";
    private BrowserDocumentInput(WebView view,String url,Check current) {
        this.view=view;this.current=current;this.url=url;token=UUID.randomUUID().toString();slot="__pw_document_file_"+token.replace("-","");
    }
    private void initialize(String pageKey,String observationId,JSONObject target)throws Exception {
        JSONObject opened=call(openScript(pageKey,url,observationId,target,slot,token));if(!opened.optString("status").equals("reading"))throw new IOException("原文档下载未开始："+opened.optString("code","unavailable"));
        while(true){JSONObject state=call(pollScript(slot,token,0,false));if(state.optString("status").equals("ready")){total=state.getInt("size");mime=state.optString("mime","application/octet-stream");if(total<0||total>MAX_FILE_BYTES)throw new IOException("原文档文件超出20MiB预算");break;}if(!state.optString("status").equals("reading"))throw new IOException("原文档下载未取得文件："+state.optString("code","unavailable"));Thread.sleep(30);}
    }
    public static BrowserDocumentInput open(WebView view,String pageKey,String url,String observationId,JSONObject target,Check current)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("文档文件读取不能阻塞原生主线程");BrowserDocumentInput result=new BrowserDocumentInput(view,url,current);try{result.initialize(pageKey,observationId,target);return result;}catch(Exception failure){result.close();throw failure;}
    }
    public String mimeType(){return mime;}
    private JSONObject call(String code)throws Exception {
        if(closed||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline)throw new IOException("原文档下载已停止或到期");CompletableFuture<JSONObject> value=new CompletableFuture<>();
        if(!view.post(()->{if(value.isDone())return;try{current.check();view.evaluateJavascript(code,raw->{if(value.isDone())return;try{current.check();Object decoded=new org.json.JSONTokener(raw==null?"null":raw).nextValue();JSONObject result=decoded instanceof String?new JSONObject((String)decoded):decoded instanceof JSONObject?(JSONObject)decoded:null;if(result==null)throw new IOException("原文档文件回执无效");value.complete(result);}catch(Exception error){value.completeExceptionally(error);}});}catch(Exception error){value.completeExceptionally(error);}}))throw new IOException("原文档读取线程不可用");
        try{return value.get(Math.min(5000,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))),TimeUnit.MILLISECONDS);}finally{value.cancel(false);}
    }
    @Override public int read()throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
    @Override public int read(byte[] destination,int start,int length)throws IOException {
        java.util.Objects.checkFromIndexSize(start,length,destination.length);if(length==0)return 0;if(closed)throw new IOException("文档文件流已关闭");
        try{if(position==chunk.length){Arrays.fill(chunk,(byte)0);if(offset>=total)return -1;JSONObject response=call(pollScript(slot,token,offset,true));if(!response.optString("status").equals("chunk")||response.getInt("offset")!=offset||response.getInt("size")!=total)throw new IOException("原文档文件版本或分块偏移已变化");chunk=java.util.Base64.getDecoder().decode(response.getString("data"));if(chunk.length<1||chunk.length>CHUNK||offset>total-chunk.length)throw new IOException("原文档文件分块越界");position=0;offset+=chunk.length;}
            int count=Math.min(length,chunk.length-position);System.arraycopy(chunk,position,destination,start,count);position+=count;return count;
        }catch(Exception error){throw error instanceof IOException?(IOException)error:new IOException("原文档下载未完成",error);}
    }
    @Override public void close(){if(closed)return;closed=true;Arrays.fill(chunk,(byte)0);chunk=new byte[0];view.post(()->{try{view.evaluateJavascript(cleanupScript(slot,token),null);}catch(RuntimeException ignored){}});}
    public static String openScript(String pageKey,String url,String observationId,JSONObject target,String slot,String token)throws Exception {
        String ref=target.getString("ref");if(!ref.matches("n[0-9]{1,3}")||!target.optString("tag").equals("A")||!target.optString("nodeKey").matches("k[1-9][0-9]{0,6}"))throw new IllegalArgumentException("文档下载需要已展示的准确链接");
        return "(()=>{const pageKey="+JSONObject.quote(pageKey)+",expectedUrl="+JSONObject.quote(url)+",observation="+JSONObject.quote(observationId)+",index="+Integer.parseInt(ref.substring(1))+",nodeKey="+JSONObject.quote(target.getString("nodeKey"))+",slot="+JSONObject.quote(slot)+",token="+JSONObject.quote(token)+";\n"+OPEN+"\n})()";
    }
    private static final String OPEN="""
const fail=code=>JSON.stringify({status:'unavailable',code});if(location.href!==expectedUrl)return fail('document_changed');const state=window[pageKey];if(!state||state.id!==observation||state.dirty||state.observer.takeRecords().length)return fail('observation_changed');const element=state.nodes[index];if(!element||!element.isConnected||element.ownerDocument!==document||element.tagName!=='A'||!state.nodeKeys||state.nodeKeys.get(element)!==nodeKey)return fail('link_changed');
const href=element.href;let kind='';if(href.startsWith('blob:')){if(new URL(href).origin!==location.origin)return fail('blob_origin_mismatch');kind='blob'}else if(href.startsWith('data:'))kind='data';else return fail('only_document_blob_data_supported');
if(href.length>29*1024*1024)return fail('document_file_budget');const owned={token,element,href,status:'reading',kind,bytes:null,abort:new AbortController()};globalThis[slot]=owned;
fetch(href,{signal:owned.abort.signal}).then(response=>{if(!response.ok)throw Error('document_file_unavailable');return response.blob()}).then(blob=>{if(blob.size>20*1024*1024)throw Error('document_file_budget');owned.mime=String(blob.type||'application/octet-stream').slice(0,120);return blob.arrayBuffer()}).then(buffer=>{if(globalThis[slot]!==owned)return;owned.bytes=new Uint8Array(buffer);owned.status='ready'}).catch(error=>{if(globalThis[slot]===owned){owned.status='unavailable';owned.code=String(error.message||'document_file_failed').slice(0,80)}});
return JSON.stringify({status:'reading',kind,documentBound:true,nativeHttpClientUsed:false,pageWorldTamperable:true,automaticHttpRequestsRequested:false});
""";
    public static String pollScript(String slot,String token,int offset,boolean bytes){return "(()=>{const s=globalThis["+JSONObject.quote(slot)+"];if(!s||s.token!=="+JSONObject.quote(token)+"||!s.element.isConnected||s.element.href!==s.href)return JSON.stringify({status:'unavailable',code:'document_file_changed'});if(s.status!=='ready')return JSON.stringify({status:s.status,code:s.code||null});const offset="+offset+";if(offset<0||offset>s.bytes.length)return JSON.stringify({status:'unavailable',code:'offset_invalid'});"+(bytes?"const chunk=s.bytes.subarray(offset,Math.min(s.bytes.length,offset+32768));let binary='';for(let i=0;i<chunk.length;i++)binary+=String.fromCharCode(chunk[i]);return JSON.stringify({status:'chunk',offset,size:s.bytes.length,data:btoa(binary)});":"return JSON.stringify({status:'ready',size:s.bytes.length,mime:s.mime});")+"})()";}
    public static String cleanupScript(String slot,String token){return "(()=>{const s=globalThis["+JSONObject.quote(slot)+"];if(s&&s.token==="+JSONObject.quote(token)+"){s.abort.abort();if(s.bytes)s.bytes.fill(0);delete globalThis["+JSONObject.quote(slot)+"]}return null})()";}
}
