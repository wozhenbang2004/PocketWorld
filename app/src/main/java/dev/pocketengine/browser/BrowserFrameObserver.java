package dev.pocketengine.browser;

import android.net.Uri;
import android.webkit.WebView;
import dev.pocketengine.browser.BrowserFrameReadRegistry.Frame;
import androidx.webkit.JavaScriptExecutionException;
import androidx.webkit.JavaScriptExecutionWorld;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewOutcomeReceiver;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-real-WebView read channel, not a browser engine/frame-tree authority. All calls are native UI-thread calls. */
public final class BrowserFrameObserver implements AutoCloseable {
    @FunctionalInterface public interface Check {void check()throws Exception;}
    private final WebView view;
    private final String key="__pw_read_"+UUID.randomUUID().toString().replace("-","");
    private final String bridge="pwRead"+UUID.randomUUID().toString().replace("-","");
    private final boolean supported;
    private JavaScriptExecutionWorld world;
    private ScriptHandler script;
    private String armed="",active="",origin="",document=UUID.randomUUID().toString();
    private boolean closed;
    private long revision;
    private final BrowserFrameReadRegistry<JavaScriptReplyProxy> frames=new BrowserFrameReadRegistry<>();
    private Set<String> extraOrigins=Set.of(),admittedOrigins=Set.of();
    private final String documentKey="__pw_document_"+UUID.randomUUID().toString().replace("-","");
    private Map<String,Frame<JavaScriptReplyProxy>> pendingFrames=new LinkedHashMap<>();
    /** Bounded process-local evidence issuance, never a grant or second task registry. */
    private final Map<String,IssuedRead> issued=new LinkedHashMap<>();
    private final Map<String,IssuedRead> pendingIssued=new LinkedHashMap<>();
    private String pendingObservation="",pendingProjection="";private long pendingRevision=-1;
    private static final class Delivery {final long revision;final String projection;final Map<String,Frame<JavaScriptReplyProxy>> frames;final Map<String,IssuedRead> issued;Delivery(long revision,String projection,Map<String,Frame<JavaScriptReplyProxy>> frames,Map<String,IssuedRead> issued){this.revision=revision;this.projection=projection;this.frames=new LinkedHashMap<>(frames);this.issued=new LinkedHashMap<>(issued);}}
    private final Map<String,Delivery> awaitingDelivery=new LinkedHashMap<>();
    private final BrowserReadDelta deltas=new BrowserReadDelta();private JSONObject pendingReadRequest;
    private static final class IssuedRead {final String scope,prefix,readRevision,readMode;final long created;IssuedRead(String scope,long created,String prefix,String readRevision,String readMode){this.scope=scope;this.created=created;this.prefix=prefix;this.readRevision=readRevision;this.readMode=readMode;}}
    public BrowserFrameObserver(WebView view){this.view=java.util.Objects.requireNonNull(view);boolean detected;try{detected=WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD);}catch(RuntimeException|LinkageError unavailable){detected=false;}supported=detected;}
    /** Native original-task policy only. Existing pages are not reloaded to broaden frame access. */
    public void extraOriginsForNative(Set<String> origins)throws Exception {Set<String> next=BrowserFrameOrigins.checkedExtras(origins);if(!extraOrigins.equals(next)){extraOrigins=next;invalidate();removeRegistration();}}
    /** Only a native admitted load arms a new document. Never reload an existing login page to install a script. */
    public void beforeNativeLoad(String url){
        invalidate();if(closed||!supported)return;
        try{
            URI u=new URI(url);if(u.getHost()==null||u.getUserInfo()!=null||!Set.of("http","https").contains(u.getScheme()))return;
            origin=BrowserFrameOrigins.exact(new URI(u.getScheme(),null,u.getHost(),u.getPort(),null,null,null).toASCIIString());admittedOrigins=BrowserFrameOrigins.admitted(origin,extraOrigins);
            removeRegistration();world=WebViewCompat.getExecutionWorld(view,"PocketWorldRead");armed=UUID.randomUUID().toString();final String registration=armed;
            WebViewCompat.addWebMessageListener(view,bridge,admittedOrigins,world,(actual,message,sourceOrigin,main,proxy)->{
                if(closed||actual!=view||!registration.equals(active)||message.getType()!=WebMessageCompat.TYPE_STRING||message.getData()==null||message.getData().length()>256)return;
                try{JSONObject hello=new JSONObject(message.getData());if(hello.length()!=2)return;frames.register(proxy,BrowserFrameOrigins.exact(sourceOrigin.toString()),main,hello.getString("registration"),hello.getString("documentToken"));}catch(Exception invalid){/* A page/old frame cannot mint a different native origin or document handle. */}
            });
            // One marker per actual isolated-world document. An old proxy routed to a replacement
            // context must fail this marker check instead of silently reading its new document.
            String bootstrap="(()=>{const b=globalThis["+JSONObject.quote(bridge)+"];if(!b||!globalThis.crypto||!crypto.getRandomValues)return;let n=globalThis["+JSONObject.quote(documentKey)+"];if(!n){n=Array.from(crypto.getRandomValues(new Uint8Array(16)),x=>x.toString(16).padStart(2,'0')).join('');Object.defineProperty(globalThis,"+JSONObject.quote(documentKey)+",{value:n,writable:false,configurable:false});}b.postMessage(JSON.stringify({registration:"+JSONObject.quote(registration)+",documentToken:n}));})()";
            script=WebViewCompat.addJavaScriptOnEvent(view,bootstrap,WebViewCompat.INJECTION_EVENT_DOCUMENT_START,admittedOrigins,world);
        }catch(Exception unavailable){armed="";active="";removeRegistration();}
    }
    /** Unexpected links/redirects/restores are explicitly limited until a future admitted load; no old proxy is rebound. */
    public String documentIdentityForNative(){if(closed)throw new IllegalStateException("原网页读取宿主已关闭");Frame<JavaScriptReplyProxy> main=frames.main();return main==null?document:main.document;}
    public JSONObject framePinForNative(String ref)throws Exception {Frame<JavaScriptReplyProxy> frame=frames.requirePresentedChild(ref,android.os.SystemClock.elapsedRealtime());return new JSONObject().put("frameId",frame.id).put("documentId",frame.document).put("sourceOrigin",frame.origin).put("topDocumentId",document);}
    public void documentStarted(){
        frames.clear();issued.clear();awaitingDelivery.clear();deltas.clear();clearPresentation();revision++;document=UUID.randomUUID().toString();active=armed;armed="";try{frames.reset(document,active,origin,admittedOrigins);}catch(Exception invalid){active="";frames.clear();}
    }
    public void invalidate(){revision++;active="";armed="";frames.clear();issued.clear();awaitingDelivery.clear();deltas.clear();clearPresentation();document=UUID.randomUUID().toString();}
    public void read(JSONObject request,String observationId,String nativeEpoch,Check current,CompletableFuture<JSONObject> result)throws Exception {
        if(closed)throw new IllegalStateException("网页读取通道已关闭");BrowserSemanticRead.validate(request);current.check();clearPresentation();
        final long expected=revision,now=android.os.SystemClock.elapsedRealtime();final String url=view.getUrl()==null?"":view.getUrl();
        Frame<JavaScriptReplyProxy> chosen;
        try{chosen=request.has("frameRef")?frames.requirePresentedChild(request.getString("frameRef"),now):frames.main();}
        catch(BrowserTaskSpace.FrameReadUnavailable unavailable){result.complete(unavailableFrame(url,"frame_ref_not_current"));return;}
        final Frame<JavaScriptReplyProxy> selected=chosen;
        final String doc=selected==null?document:selected.document,scope=nativeEpoch+"|"+doc;
        String reference=request.has("cursor")?request.getString("cursor"):request.optString("regionRef");IssuedRead old=reference.isEmpty()?null:issued.get(reference);
        if(!reference.isEmpty()&&(old==null||!old.scope.equals(scope)||now<old.created||now-old.created>120000)){result.complete(new JSONObject().put("semanticRead",true).put("status","unavailable").put("code","read_reference_not_current").put("url",url).put("title",view.getTitle()==null?"":view.getTitle()).put("readOnlyEvidence",true).put("nativeReadStarted",false).put("accepted",true).put("verified",false).put("untrustedPageContent",true));return;}
        // A real current region is still mandatory. An unknown/evicted baseline causes a full resync, never a guessed diff.
        final long cursorCreated=request.has("cursor")?old.created:now;final String regionPrefix=doc;String code=BrowserSemanticRead.script(request,key,scope,observationId,doc,selected!=null&&!selected.main);if(selected!=null)code=guarded(code,selected,"semanticRead");
        android.webkit.ValueCallback<String> done=value->{if(result.isDone())return;try{
            current.check();if(closed||revision!=expected||!url.equals(view.getUrl()==null?"":view.getUrl()))throw new BrowserTaskSpace.StalePage("语义读取回调的原文档已变化");if(selected!=null&&!frames.current(selected)){result.complete(unavailableFrame(url,"frame_document_replaced_or_closed"));return;}
            Object decoded=new org.json.JSONTokener(value==null?"null":value).nextValue();JSONObject raw=decoded instanceof String?new JSONObject((String)decoded):decoded instanceof JSONObject?(JSONObject)decoded:null;
            if(raw==null)throw new IllegalStateException("网页语义读取返回无效JSON");
            String documentKind="http_document";
            if(selected!=null){if(raw.optString("code").matches("frame_document_changed|native_frame_detached|isolated_frame_read_failed"))frames.retire(selected);else if(!raw.optString("status").equals("unavailable")){
                String frameUrl=raw.getString("url");URI actual=new URI(frameUrl);String inline=actual.getScheme()!=null&&actual.getScheme().equals("about")?actual.getSchemeSpecificPart():"";
                if(!selected.main&&Set.of("srcdoc","blank").contains(inline)){documentKind=inline.equals("srcdoc")?"inline_srcdoc":"inline_about_blank";raw.put("url",url);}
                else if(!selected.origin.equals(urlOrigin(frameUrl)))throw new BrowserTaskSpace.StalePage("原frame回执的来源已变化");
            }}
            raw.put("frameId",selected==null?"main":selected.id).put("documentId",doc).put("frameCoverage",coverage(selected,doc,url,raw.optString("status").equals("observed"),documentKind));
            JSONObject projected=BrowserSemanticRead.result(raw);
            if(projected.optString("status").equals("observed")){
                JSONObject reading=projected.getJSONObject("read");String readRevision=reading.getJSONObject("delivery").getString("revision");
                // Registration is not knowledge: only rows actually returned by this bounded projection
                // enter the original document's presented set. Discovery never grants hidden refs.
                if(reading.optString("mode").equals("outline")){
                    JSONArray rows=reading.getJSONArray("data");for(int i=0;i<rows.length()&&i<32;i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=row.optString("regionRef");requireRegion(id,regionPrefix);IssuedRead prior=issued.get(id);pendingIssued.put(id,new IssuedRead(scope,now,regionPrefix,prior!=null&&prior.scope.equals(scope)?prior.readRevision:"",prior!=null&&prior.scope.equals(scope)?prior.readMode:""));}
                }else{
                    String id=reading.getJSONObject("facts").optString("regionRef");if(!id.isEmpty()){requireRegion(id,regionPrefix);pendingIssued.put(id,new IssuedRead(scope,now,regionPrefix,readRevision,reading.getString("mode")));}
                }
                if(!reading.isNull("cursor")){String id=reading.getString("cursor");if(!id.equals(observationId+":c0"))throw new IllegalStateException("续读id不是本次native读取签发");pendingIssued.put(id,new IssuedRead(scope,cursorCreated,regionPrefix,readRevision,reading.getString("mode")));}
                pendingObservation=observationId;pendingProjection=readRevision;pendingRevision=expected;pendingFrames=frames.candidates();pendingReadRequest=new JSONObject(request.toString());
            }
            result.complete(projected);
        }catch(Exception failure){result.completeExceptionally(failure);}};
        if(selected==null)view.evaluateJavascript(code,done);
        else selected.proxy.executeJavaScript(code,new WebViewOutcomeReceiver<String,JavaScriptExecutionException>(){
            @Override public void onResult(String value){done.onReceiveValue(value);}
            @Override public void onError(JavaScriptExecutionException failure){if(result.isDone())return;try{done.onReceiveValue(JSONObject.quote(new JSONObject().put("semanticRead",true).put("status","unavailable").put("code","isolated_frame_read_failed").put("url",url).put("title",view.getTitle()==null?"":view.getTitle()).put("readOnlyEvidence",true).put("automaticReplayAllowed",false).toString()));}catch(Exception invalid){result.completeExceptionally(invalid);}}
        });
    }
    /** Read-only condition probe in one already presented region; it never registers/presents new refs. */
    public void condition(JSONObject target,String nativeEpoch,Check current,CompletableFuture<JSONObject> result)throws Exception {
        if(closed)throw new IllegalStateException("网页读取通道已关闭");BrowserTargetCondition.validate(target);current.check();
        final long expected=revision,now=android.os.SystemClock.elapsedRealtime();final String url=view.getUrl()==null?"":view.getUrl();
        final Frame<JavaScriptReplyProxy> selected=frames.main();final String doc=selected==null?document:selected.document,scope=nativeEpoch+"|"+doc;
        String ref=target.getString("regionRef");IssuedRead old=issued.get(ref);
        if(old==null||!old.scope.equals(scope)||now<old.created||now-old.created>120000){
            result.complete(BrowserTargetCondition.project(new JSONObject().put("targetProbe",true).put("status","unavailable").put("code","read_reference_not_current").put("matched",JSONObject.NULL).put("actual",new JSONObject())
                    .put("coverage",new JSONObject().put("nodesVisited",0).put("textCharsVisited",0).put("queuedPeak",0).put("layoutChecks",0).put("partial",true).put("reasons",new JSONArray().put("read_reference_not_current"))),target,doc).put("nativeProbeStarted",false));return;
        }
        String code=BrowserTargetCondition.script(target,key,scope,doc);
        android.webkit.ValueCallback<String> done=value->{if(result.isDone())return;try{
            current.check();if(closed||revision!=expected||!url.equals(view.getUrl()==null?"":view.getUrl())||selected!=null&&!frames.current(selected))throw new BrowserTaskSpace.StalePage("目标条件回调的原文档已变化");
            Object decoded=new org.json.JSONTokener(value==null?"null":value).nextValue();JSONObject raw=decoded instanceof String?new JSONObject((String)decoded):decoded instanceof JSONObject?(JSONObject)decoded:null;
            if(raw==null)throw new IllegalStateException("网页目标条件返回无效JSON");
            JSONObject projected=BrowserTargetCondition.project(raw,target,doc).put("observedAt",System.currentTimeMillis()).put("nativeProbeStarted",true).put("frameId","main")
                    .put("frameCoverage",new JSONObject().put("featureSupported",supported).put("execution",selected==null?"page_world_fallback":"isolated_world").put("fallbackReason",selected==null?(supported?"no_current_document_start_handshake":"provider_feature_unsupported"):JSONObject.NULL).put("childFrameContentsRead",false).put("frameTreeComplete",false));
            result.complete(projected);
        }catch(Exception stale){result.completeExceptionally(stale);}};
        if(selected==null)view.evaluateJavascript(code,done);
        else selected.proxy.executeJavaScript(code,new WebViewOutcomeReceiver<String,JavaScriptExecutionException>(){
            @Override public void onResult(String value){done.onReceiveValue(value);}
            @Override public void onError(JavaScriptExecutionException failure){if(!result.isDone())result.completeExceptionally(failure);}
        });
    }
    /** Bounded privacy/mutation metadata only. It is not an image, input value read, or permission source. */
    public void visualProbe(Check current,CompletableFuture<JSONObject> result)throws Exception {
        if(closed)throw new IllegalStateException("原截图页已关闭");current.check();final long expected=revision;final Frame<JavaScriptReplyProxy> selected=frames.main();String code=BrowserVisualCapture.privacyScript(key+"_visual");if(selected!=null)code=guarded(code,selected,"visualProbe");
        android.webkit.ValueCallback<String> done=value->{if(result.isDone())return;try{current.check();if(closed||revision!=expected||selected!=null&&!frames.current(selected))throw new BrowserTaskSpace.StalePage("截图隐私检查的原文档已变化");Object decoded=new org.json.JSONTokener(value==null?"null":value).nextValue();JSONObject raw=decoded instanceof String?new JSONObject((String)decoded):decoded instanceof JSONObject?(JSONObject)decoded:null;if(raw==null)throw new IllegalStateException("网页截图检查未返回实际回执");result.complete(BrowserVisualCapture.privacy(raw).put("documentId",documentIdentityForNative()).put("probeExecution",selected==null?"page_world_fallback_untrusted":"isolated_world"));}catch(Exception unavailable){result.completeExceptionally(unavailable);}};
        if(selected==null)view.evaluateJavascript(code,done);else selected.proxy.executeJavaScript(code,new WebViewOutcomeReceiver<String,JavaScriptExecutionException>(){public void onResult(String value){done.onReceiveValue(value);}public void onError(JavaScriptExecutionException failure){result.completeExceptionally(new IllegalStateException("截图检查无法读取当前独立文档"));}});
    }
    /** Final model projection boundary, not discovery registration. Never grants action authority. */
    public JSONObject presented(JSONObject delivered)throws Exception {
        if(closed||revision!=pendingRevision||!pendingObservation.equals(delivered.optString("observationId"))||!delivered.optString("status").equals("observed"))throw new BrowserTaskSpace.StalePage("原只读投影尚未正式呈现或已过期");
        JSONObject reading=delivered.getJSONObject("read");if(!pendingProjection.equals(reading.getJSONObject("delivery").getString("revision")))throw new SecurityException("不能替换待呈现的原只读版本");
        Set<String> shown=new java.util.LinkedHashSet<>();if(reading.getString("mode").equals("outline")){JSONArray rows=reading.getJSONArray("data");for(int i=0;i<rows.length();i++)shown.add(rows.getJSONObject(i).getString("regionRef"));}else{String id=reading.getJSONObject("facts").getString("regionRef");if(!id.isEmpty())shown.add(id);}
        if(!reading.isNull("cursor"))shown.add(reading.getString("cursor"));
        for(String id:shown)if(!pendingIssued.containsKey(id))throw new SecurityException("未呈现的登记引用不可升级为原生已知引用");
        awaitingDelivery.put(delivered.getString("observationId"),new Delivery(pendingRevision,pendingProjection,pendingFrames,pendingIssued));while(awaitingDelivery.size()>8)awaitingDelivery.remove(awaitingDelivery.keySet().iterator().next());
        JSONObject projected=deltas.offer(delivered,pendingReadRequest,android.os.SystemClock.elapsedRealtime());clearPresentation();return projected;
    }
    /** Original afterTool safe-JSON boundary, after the real journal receipt. Registered frames omitted
     * by the actual model projection never become known, even if they were discovered earlier. */
    public JSONObject delivered(JSONObject actual)throws Exception {
        Delivery prepared=awaitingDelivery.remove(actual.getString("observationId"));
        if(closed||prepared==null||prepared.revision!=revision||!prepared.projection.equals(actual.getJSONObject("read").getJSONObject("delivery").getString("revision")))throw new BrowserTaskSpace.StalePage("原模型交付的frame目录已失效");
        JSONObject read=actual.getJSONObject("read");Set<String> shown=new java.util.LinkedHashSet<>();
        if(read.getString("mode").equals("outline")){JSONArray rows=read.getJSONArray("data");for(int i=0;i<rows.length();i++)shown.add(rows.getJSONObject(i).getString("regionRef"));}
        else {String id=read.getJSONObject("facts").getString("regionRef");if(!id.isEmpty())shown.add(id);}
        if(!read.isNull("cursor"))shown.add(read.getString("cursor"));
        for(String id:shown)if(!prepared.issued.containsKey(id))throw new SecurityException("模型没有收到的只读引用不可进入已知集合");
        JSONObject materialized=deltas.delivered(actual,android.os.SystemClock.elapsedRealtime());
        frames.presented(actual.getJSONObject("frameCoverage").getJSONArray("knownOriginMatchedFrames"),prepared.frames,android.os.SystemClock.elapsedRealtime());
        for(String id:shown)issued.put(id,prepared.issued.get(id));while(issued.size()>128)issued.remove(issued.keySet().iterator().next());return materialized;
    }
    public java.util.List<BrowserReadDelta.FullResync> contextRepairs(JSONArray messages,String tabId)throws Exception {if(closed)return java.util.List.of();return deltas.repairs(messages,android.os.SystemClock.elapsedRealtime(),tabId);}
    public void beforeModelRequest(String id,JSONArray messages)throws Exception {deltas.beforeModelRequest(id,messages);}
    public void afterModelResponse(String id){deltas.afterModelResponse(id);}
    public void modelRequestAbandoned(String id){deltas.abandoned(id);}
    private void clearPresentation(){pendingReadRequest=null;pendingIssued.clear();pendingFrames.clear();pendingObservation="";pendingProjection="";pendingRevision=-1;}
    private static void requireRegion(String id,String namespace){if(!id.matches(java.util.regex.Pattern.quote(namespace)+":r[0-9]{1,6}"))throw new IllegalStateException("区域id不属于原native文档命名空间");}
    private static JSONObject unavailableFrame(String topUrl,String code)throws Exception {return new JSONObject().put("semanticRead",true).put("status","unavailable").put("code",code).put("url",topUrl).put("title","").put("readOnlyEvidence",true).put("accepted",true).put("verified",false).put("automaticReplayAllowed",false).put("message","嵌入网页已变化或引用未展示，请重新读取frame目录；没有把它改指向新文档。");}
    private String guarded(String code,Frame<JavaScriptReplyProxy> selected,String type)throws Exception {return guardScript(code,documentKey,selected.documentToken,type);}
    static String guardScript(String code,String nativeDocumentKey,String nativeDocumentToken,String type)throws Exception {
        if(!Set.of("semanticRead","targetProbe","visualProbe").contains(type)||!nativeDocumentToken.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("原frame探针类型或文档标记无效");
        String refusal="JSON.stringify({"+type+":true,status:'unavailable',code:'frame_document_changed',url:location.href,title:document.title||'',readOnlyEvidence:true,automaticReplayAllowed:false})";
        return "(()=>{if(globalThis["+JSONObject.quote(nativeDocumentKey)+"]!=="+JSONObject.quote(nativeDocumentToken)+"||globalThis.closed||!document.documentElement||!document.documentElement.isConnected)return "+refusal+";return "+code+";})()";
    }
    private JSONObject coverage(Frame<JavaScriptReplyProxy> selected,String doc,String topUrl,boolean read,String documentKind)throws Exception {
        return new JSONObject().put("schemaVersion",2).put("featureSupported",supported).put("execution",selected==null?"page_world_fallback":"isolated_world")
                .put("fallbackReason",selected==null?(supported?"no_current_document_start_handshake":"provider_feature_unsupported"):JSONObject.NULL)
                .put("knownOriginMatchedFrames",frames.catalogue(read?selected:null)).put("frameTreeComplete",false).put("childFrameContentsRead",read&&selected!=null&&!selected.main).put("automaticReload",false)
                .put("selectedFrameId",selected==null?"main":selected.id).put("selectedDocumentId",doc).put("selectedSourceOrigin",selected==null?urlOrigin(topUrl):selected.origin)
                .put("topDocumentId",document).put("selectedDocumentKind",documentKind).put("sourceUrlRole",documentKind.equals("http_document")?"selected_document_url":"inline_document_host_page").put("parentPageIdentityDigest",dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject().put("url",topUrl)));
    }
    private static String urlOrigin(String url)throws Exception {URI u=new URI(url);if(u.getHost()==null||u.getUserInfo()!=null)throw new SecurityException("原frame地址不可核对");return BrowserFrameOrigins.exact(new URI(u.getScheme(),null,u.getHost(),u.getPort(),null,null,null).toASCIIString());}
    private void removeRegistration(){
        if(script!=null){try{script.remove();}catch(Exception ignored){}script=null;}
        if(world!=null)try{WebViewCompat.removeWebMessageListener(view,world,bridge);}catch(Exception ignored){}
    }
    @Override public void close(){if(closed)return;invalidate();closed=true;removeRegistration();}
}
