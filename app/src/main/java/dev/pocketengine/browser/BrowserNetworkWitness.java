package dev.pocketengine.browser;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.ScriptHandler;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in, bounded page-world fetch/XHR witnesses. Not CDP, transport truth, or an authorization source. */
public final class BrowserNetworkWitness implements AutoCloseable {
    private final WebView view;
    private final String bridge="pwNetwork"+UUID.randomUUID().toString().replace("-","");
    private String registration="",document="",origin="";
    private boolean enabled,registered,truncated;
    private int bytes;
    private long eventSequence;
    private final java.util.ArrayDeque<JSONObject> rows=new java.util.ArrayDeque<>();
    private ScriptHandler script;
    public BrowserNetworkWitness(WebView view){this.view=view;}
    public synchronized String start(String url,String documentId)throws Exception {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw new IllegalStateException("当前WebView未支持页面网络witness");
        if(enabled)throw new IllegalStateException("原文档witness已启用，请读取或先停止，不重复安装");close();enabled=true;document=documentId;origin=BrowserFrameOrigins.exact(origin(url));registration=UUID.randomUUID().toString();final String pinned=registration,doc=document;
        try{WebViewCompat.removeWebMessageListener(view,bridge);registered=true;WebViewCompat.addWebMessageListener(view,bridge,Set.of(origin),(actual,message,sourceOrigin,main,proxy)->{synchronized(BrowserNetworkWitness.this){
            if(!enabled||actual!=view||!main||!pinned.equals(registration)||!doc.equals(document)||message.getType()!=WebMessageCompat.TYPE_STRING||message.getData()==null||message.getData().length()>2400)return;
            try{if(!origin.equals(BrowserFrameOrigins.exact(sourceOrigin.toString())))return;JSONObject event=new JSONObject(message.getData());if(!pinned.equals(event.optString("registration"))||!Set.of("request","response","error").contains(event.optString("phase"))||!Set.of("fetch","xhr").contains(event.optString("mechanism")))return;
                appendNativeEvent(event,doc);
            }catch(Exception malformed){/* Page messages cannot change grants, issue requests, or supply trusted outcome. */}}
        });return script(bridge,pinned);}catch(RuntimeException|LinkageError unavailable){close();throw new IllegalStateException("页面网络witness注册失败，未启用采集",unavailable);}
    }
    /** A navigation invalidates the opt-in document. No silent reinstallation or old-document reassociation. */
    public void documentChanged(){close();}
    private void appendNativeEvent(JSONObject event,String doc)throws Exception {
        String target=redact(event.getString("url"));JSONObject safe=new JSONObject().put("sequence",++eventSequence).put("requestSequence",Math.max(0,event.optLong("sequence"))).put("phase",event.getString("phase")).put("mechanism",event.getString("mechanism")).put("method",event.optString("method").replaceAll("[^A-Z]","").substring(0,Math.min(12,event.optString("method").replaceAll("[^A-Z]","").length()))).put("url",target).put("status",Math.max(0,Math.min(599,event.optInt("status")))).put("contentType",event.optString("contentType").substring(0,Math.min(120,event.optString("contentType").length()))).put("retryAfterMs",Math.max(0,Math.min(300000,event.optInt("retryAfterMs",0)))).put("nativeReceivedAt",System.currentTimeMillis()).put("documentId",doc).put("evidenceTrust","page_world_tamperable_not_transport_verified");
        int size=safe.toString().length();while(rows.size()>=150||bytes+size>96000){JSONObject old=rows.pollFirst();if(old==null)break;bytes-=old.toString().length();truncated=true;}rows.addLast(safe);bytes+=size;
    }
    public synchronized JSONObject snapshot()throws Exception{return snapshot(new JSONObject());}
    public synchronized JSONObject snapshot(JSONObject request)throws Exception {
        JSONArray events=new JSONArray();long after=request.optLong("afterSequence",0),next=after;int limit=request.optInt("maxEvents",150);if(after<0||limit<1||limit>150)throw new IllegalArgumentException("网络witness分页预算无效");
        String method=request.optString("method"),filterOrigin=request.optString("origin");if(!filterOrigin.isEmpty())filterOrigin=BrowserFrameOrigins.exact(filterOrigin);
        long wait=0;boolean omitted=false;for(JSONObject row:rows){if(row.getLong("sequence")<=after||!method.isEmpty()&&!method.equals(row.getString("method"))||!filterOrigin.isEmpty()&&!filterOrigin.equals(origin(row.getString("url"))))continue;if(events.length()>=limit){omitted=true;continue;}events.put(new JSONObject(row.toString()));next=row.getLong("sequence");if(row.getInt("status")==429||row.getInt("status")==503)wait=Math.max(wait,row.optLong("retryAfterMs"));}if(events.length()==0)next=Math.max(after,eventSequence);
        return new JSONObject().put("status",enabled?"observed":"unavailable").put("accepted",enabled).put("documentId",document).put("events",events).put("partial",true).put("bufferTruncated",truncated).put("eventsOmitted",omitted).put("lastSeenSequence",eventSequence).put("nextAfterSequence",next).put("sequenceMeaning","native_receive_order_in_this_opt_in_document_requestSequence_is_page_claim").put("coverage","opt_in_main_document_fetch_xhr_only").put("trust","page_can_tamper_or_bypass_not_cdp").put("bodiesCaptured",false).put("credentialHeadersCaptured",false).put("networkIdleProven",false).put("urlRedaction","query_fragment_userinfo_removed_path_may_contain_identifiers").put("automaticRequestsPerformed",false).put("serverBusinessOutcomeVerified",false)
                .put("backoff",new JSONObject().put("recommendedDelayMs",wait).put("source","untrusted_response_429_503_retry_after_hint").put("automaticallyWaited",false).put("automaticRetryPerformed",false).put("requiresCurrentTaskBudget",true));
    }
    /** Best-effort restoration is explicit cleanup of this original registration only. */
    public String stopScript(){return stopScript(bridge,registration);}
    static String stopScript(String bridge,String registration){return "(()=>{const s=globalThis["+JSONObject.quote(bridge+"State")+"];if(!s||s.registration!=="+JSONObject.quote(registration)+")return JSON.stringify({restored:false});s.active=false;if(window.fetch===s.fetch)window.fetch=s.originalFetch;if(XMLHttpRequest.prototype.open===s.open)XMLHttpRequest.prototype.open=s.originalOpen;if(XMLHttpRequest.prototype.send===s.send)XMLHttpRequest.prototype.send=s.originalSend;delete globalThis["+JSONObject.quote(bridge+"State")+"];return JSON.stringify({restored:true,networkRequestsCancelled:false})})()";}
    @Override public synchronized void close(){
        final String retired=registration,cleanup=registration.isEmpty()?null:stopScript();final boolean remove=registered;final ScriptHandler retiredScript=script;
        enabled=false;registration="";document="";rows.clear();bytes=0;eventSequence=0;truncated=false;registered=false;script=null;
        if(remove||cleanup!=null||retiredScript!=null){Runnable stop=()->{synchronized(BrowserNetworkWitness.this){if(remove&&(registration.isEmpty()||registration.equals(retired)))try{WebViewCompat.removeWebMessageListener(view,bridge);}catch(Exception ignored){}if(retiredScript!=null)retiredScript.remove();if(cleanup!=null)try{view.evaluateJavascript(cleanup,null);}catch(RuntimeException ignored){}}};if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())stop.run();else view.post(stop);}
    }
    static String origin(String url)throws Exception {URI u=new URI(url);if(u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))throw new SecurityException("不是HTTP网页");return new URI(u.getScheme(),null,u.getHost(),u.getPort(),null,null,null).toASCIIString();}
    static String redact(String url)throws Exception {URI u=new URI(url);if(u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))throw new SecurityException("非HTTPwitness");String path=u.getRawPath();return origin(url)+(path==null||path.isEmpty()?"/":path);}
    public static String script(String bridge,String registration){return "(()=>{const bridge=window["+JSONObject.quote(bridge)+"],registration="+JSONObject.quote(registration)+",slot="+JSONObject.quote(bridge+"State")+";\n"+WITNESS+"\n})()";}
    private static final String WITNESS="""
if(!bridge)return JSON.stringify({status:'unavailable',accepted:false});
const old=globalThis[slot];if(old&&old.active)return JSON.stringify({status:'unavailable',accepted:false,code:'witness_already_installed'});
let serial=0;const state={registration,active:true,originalFetch:window.fetch,originalOpen:XMLHttpRequest.prototype.open,originalSend:XMLHttpRequest.prototype.send};
const retryAfter=value=>{if(!value)return 0;const seconds=Number(value);return Math.max(0,Math.min(300000,Number.isFinite(seconds)?seconds*1000:Date.parse(value)-Date.now()||0))};
const send=(phase,mechanism,id,url,method,status,type,retry)=>{if(!state.active)return;try{const u=new URL(url,location.href);bridge.postMessage(JSON.stringify({registration,sequence:id,phase,mechanism,url:u.origin+u.pathname,method:String(method||'GET').toUpperCase().slice(0,12),status:Number(status)||0,contentType:String(type||'').slice(0,120),retryAfterMs:retryAfter(retry)}))}catch(e){}};
if(typeof state.originalFetch==='function'){state.fetch=function(input,init){const id=++serial,url=typeof input==='string'||input instanceof URL?String(input):input&&input.url,method=init&&init.method||input&&input.method||'GET';send('request','fetch',id,url,method);let promise;try{promise=state.originalFetch.apply(this,arguments)}catch(e){send('error','fetch',id,url,method);throw e}return promise.then(response=>{send('response','fetch',id,response.url||url,method,response.status,response.headers.get('content-type'),response.headers.get('retry-after'));return response},error=>{send('error','fetch',id,url,method);throw error})};window.fetch=state.fetch;}
const meta=new WeakMap();state.open=function(method,url){meta.set(this,{id:++serial,method,url});return state.originalOpen.apply(this,arguments)};
state.send=function(){const m=meta.get(this);if(m){send('request','xhr',m.id,m.url,m.method);this.addEventListener('loadend',()=>{try{send(this.status?'response':'error','xhr',m.id,this.responseURL||m.url,m.method,this.status,this.getResponseHeader('content-type'),this.getResponseHeader('retry-after'))}catch(e){send('error','xhr',m.id,m.url,m.method)}},{once:true})}return state.originalSend.apply(this,arguments)};
XMLHttpRequest.prototype.open=state.open;XMLHttpRequest.prototype.send=state.send;globalThis[slot]=state;
return JSON.stringify({status:'witness_installed',accepted:true,coverage:'main_document_fetch_xhr_only',evidenceTrust:'page_world_tamperable_not_transport_verified',bodiesCaptured:false,automaticRequestsPerformed:false,networkIdleProven:false});
""";
}
