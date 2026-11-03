package dev.pocketengine.browser;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.json.JSONObject;

/** One native-authenticated CDP target. The original task owns its gate, cancellation and approvals. */
public final class BrowserNativeNetwork implements AutoCloseable {
    public static final String BODY_TOOL="browser_task_network_body";
    static final long MAX_CAPTURE_MS=120000;
    /** Native isolated-world marker, never serialized to a model, page world, task event or log. */
    public static final class DocumentProof {
        final String property,token,document;
        DocumentProof(String property,String token,String document){if(!property.matches("__pw_document_[a-f0-9]{32}")||!token.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("invalid_native_document_proof");this.property=property;this.token=token;this.document=document;}
        @Override public String toString(){return "[native document proof]";}
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Executor io;
    private final BrowserFrameObserver.Check current;
    private final Supplier<String> operation;
    private final JSONObject identity;
    private final DocumentProof proof;
    private volatile BrowserDevToolsConnection connection;
    private volatile BrowserNetworkRecords records;
    private volatile boolean closed;
    private AutoCloseable retained;
    private final long created=SystemClock.elapsedRealtime();
    private final Runnable watch=new Runnable(){public void run(){if(closed)return;try{current.check();if(SystemClock.elapsedRealtime()-created>=MAX_CAPTURE_MS){invalidate("capture_time_budget");return;}main.postDelayed(this,100);}catch(Exception changed){invalidate("task_or_document_changed");}}};

    public BrowserNativeNetwork(JSONObject identity,DocumentProof proof,Executor io,BrowserFrameObserver.Check current,Supplier<String> operation)throws Exception {
        this.identity=new JSONObject(identity.toString());this.proof=java.util.Objects.requireNonNull(proof);this.io=io;this.current=current;this.operation=operation;
        if(!identity.getString("documentId").equals(proof.document))throw new SecurityException("native_network_document_mismatch");current.check();main.post(watch);
    }
    public void start(CompletableFuture<JSONObject> result){
        result.whenComplete((value,error)->{if(result.isCancelled()||error!=null)invalidate("capture_start_abandoned");});
        io.execute(()->{try{attach();onMain(()->{current.check();if(closed||records==null)throw new SecurityException("capture_not_current");JSONObject receipt=records.snapshot(SystemClock.elapsedRealtime()).put("status","capture_started").put("maxCaptureMs",MAX_CAPTURE_MS).put("targetAuthentication","native_isolated_document_nonce");if(!result.complete(receipt))close();return null;});}
        catch(Exception unavailable){invalidate("native_backend_unavailable");main.post(()->{try{result.complete(unavailable("native_backend_unavailable","原生网络后端或原页面隔离文档不可用；请核对浏览器更多菜单的本次观察授权及WebView支持。没有重载页面、补发请求或降级冒充CDP。"));}catch(Exception invalid){result.completeExceptionally(invalid);}});}});
    }
    /** Called only by the native owner's explicit settings path; it never enables debugging. */
    public static boolean backendListening(){return BrowserDevToolsConnection.alreadyListening();}
    static JSONObject nonceProbe(DocumentProof proof,JSONObject context)throws Exception {
        JSONObject aux=context.optJSONObject("auxData");String unique=context.optString("uniqueId");
        if(aux==null||aux.optBoolean("isDefault",true)||!"isolated".equals(aux.optString("type"))||aux.optString("frameId").isEmpty()||unique.isEmpty())return null;
        return new JSONObject().put("expression","Object.getOwnPropertyDescriptor(globalThis,"+JSONObject.quote(proof.property)+")?.value")
                .put("returnByValue",true).put("silent",true).put("throwOnSideEffect",true).put("timeout",250).put("allowUnsafeEvalBlockedByCSP",false).put("uniqueContextId",unique);
    }
    static boolean nonceMatches(DocumentProof proof,JSONObject evaluated){JSONObject value=evaluated.optJSONObject("result");return !evaluated.has("exceptionDetails")&&value!=null&&"string".equals(value.optString("type"))&&proof.token.equals(value.opt("value"));}
    static boolean frameMatches(JSONObject context,JSONObject frame){JSONObject aux=context.optJSONObject("auxData");return aux!=null&&!aux.optString("frameId").isEmpty()&&aux.optString("frameId").equals(frame.optString("id"))&&!frame.optString("loaderId").isEmpty();}
    public synchronized void retain(AutoCloseable registration)throws Exception {if(closed){registration.close();return;}if(retained!=null)throw new IllegalStateException("capture_already_retained");retained=registration;}
    private long remaining(long deadline)throws Exception {long left=deadline-SystemClock.elapsedRealtime();if(closed||left<=0)throw new java.util.concurrent.TimeoutException("native_network_attach_deadline");return Math.min(1200,left);}
    private void attach()throws Exception {
        long deadline=SystemClock.elapsedRealtime()+6000;
        for(String path:BrowserDevToolsConnection.targets()){
            remaining(deadline);Candidate candidate=new Candidate();BrowserDevToolsConnection socket=new BrowserDevToolsConnection(path,candidate::event,reason->{if(candidate.selected)invalidate(reason);});
            // The bounded constructor handshake may finish after cancellation. Never publish a leaked handle.
            synchronized(this){if(closed){socket.close();throw new SecurityException("capture_stopped");}connection=socket;}boolean keep=false;
            try{
                socket.call("Runtime.enable",new JSONObject(),remaining(deadline));
                if(candidate.invalid)continue;
                for(JSONObject context:new ArrayList<>(candidate.contexts.values())){
                    JSONObject parameters=nonceProbe(proof,context);if(parameters==null)continue;String unique=context.getString("uniqueId");
                    JSONObject evaluated;try{evaluated=socket.call("Runtime.evaluate",parameters,remaining(deadline));}catch(java.io.IOException missingContext){onMain(()->{current.check();return null;});continue;}
                    if(!nonceMatches(proof,evaluated))continue;
                    JSONObject tree=socket.call("Page.getFrameTree",new JSONObject(),remaining(deadline)).getJSONObject("frameTree").getJSONObject("frame");
                    String frame=tree.getString("id"),loader=tree.optString("loaderId");if(!frameMatches(context,tree))continue;
                    onMain(()->{current.check();if(closed)throw new SecurityException("capture_stopped");records=new BrowserNetworkRecords(identity,frame,loader,SystemClock.elapsedRealtime());return null;});
                    if(!candidate.select(frame,loader,unique,context.getInt("id")))throw new SecurityException("native_candidate_context_changed");
                    socket.call("Page.enable",new JSONObject(),remaining(deadline));
                    socket.call("Network.enable",new JSONObject().put("maxTotalBufferSize",262144).put("maxResourceBufferSize",BrowserNetworkRecords.MAX_NATIVE_BODY_BYTES).put("maxPostDataSize",0),remaining(deadline));
                    onMain(()->{current.check();if(closed)throw new SecurityException("capture_stopped");records.networkEnabled(SystemClock.elapsedRealtime());return null;});keep=true;return;
                }
            }finally{if(!keep){candidate.selected=false;socket.close();if(connection==socket)connection=null;}}
        }
        throw new SecurityException("native_document_target_not_authenticated");
    }
    private final class Candidate {
        final Map<Integer,JSONObject> contexts=new ConcurrentHashMap<>();volatile boolean selected,invalid;String context="",frame="",loader="";int contextId;
        synchronized boolean select(String frame,String loader,String context,int contextId){JSONObject exact=contexts.get(contextId);if(invalid||closed||exact==null||!context.equals(exact.optString("uniqueId")))return false;this.frame=frame;this.loader=loader;this.context=context;this.contextId=contextId;selected=true;contexts.clear();return true;}
        synchronized void event(JSONObject message){
            if(closed||invalid)return;try{String name=message.getString("method");JSONObject p=message.optJSONObject("params");if(p==null)return;
                if(name.equals("Runtime.executionContextCreated")){if(selected)return;if(contexts.size()>=64)throw new SecurityException("native_context_limit");JSONObject c=p.getJSONObject("context");contexts.put(c.getInt("id"),c);return;}
                if(!selected){
                    if(name.equals("Runtime.executionContextsCleared"))contexts.clear();
                    else if(name.equals("Inspector.detached")){invalid=true;contexts.clear();}
                    else if(name.equals("Runtime.executionContextDestroyed")){String unique=p.optString("executionContextUniqueId");if(unique.isEmpty())contexts.remove(p.optInt("executionContextId",-1));else contexts.entrySet().removeIf(entry->unique.equals(entry.getValue().optString("uniqueId")));}
                    return;
                }
                if(name.equals("Runtime.executionContextsCleared")||name.equals("Inspector.detached")||name.equals("Runtime.executionContextDestroyed")&&(context.equals(p.optString("executionContextUniqueId"))||contextId==p.optInt("executionContextId",-1))||name.equals("Page.frameNavigated")&&frame.equals(p.getJSONObject("frame").optString("id"))&&!loader.equals(p.getJSONObject("frame").optString("loaderId"))){invalidate("native_document_replaced");return;}
                if(!name.matches("Network\\.(requestWillBeSent|responseReceived|loadingFinished|loadingFailed)"))return;
                // At most one event is queued to the UI: this waits for its bounded native guard.
                onMain(()->{current.check();if(!closed&&records!=null){records.event(message,operation.get(),SystemClock.elapsedRealtime());if(!records.active())invalidate("native_network_event_budget");}return null;});
            }catch(Exception changed){if(selected)invalidate("native_network_evidence_unavailable");else{invalid=true;contexts.clear();}}
        }
    }
    public JSONObject snapshot(String capture)throws Exception {require(capture);return records.snapshot(SystemClock.elapsedRealtime());}
    public JSONObject capturePin(String capture)throws Exception {require(capture);return new JSONObject(identity.toString()).put("captureId",capture).put("backend","webview_cdp_local_socket");}
    public JSONObject bodyPin(JSONObject args)throws Exception {require(args.getString("captureId"));return records.bodyPin(args);}
    public void delivered(JSONObject receipt)throws Exception {require(receipt.getString("captureId"));records.delivered(receipt);}
    public JSONObject quiet(String capture)throws Exception {require(capture);return records.quiet(SystemClock.elapsedRealtime());}
    public void body(JSONObject args,BrowserFrameObserver.Check bodyCurrent,CompletableFuture<JSONObject> result)throws Exception {
        bodyCurrent.check();require(args.getString("captureId"));BrowserNetworkRecords original=records;BrowserNetworkRecords.BodyPermit permit=original.reserveBody(args);BrowserDevToolsConnection socket=connection;
        io.execute(()->{try{onMain(()->{bodyCurrent.check();require(permit.captureId);original.requireBodyCurrent(permit);return null;});JSONObject response=socket.call("Network.getResponseBody",new JSONObject().put("requestId",permit.requestId),2500);
            onMain(()->{bodyCurrent.check();require(permit.captureId);original.requireBodyCurrent(permit);if(result.isDone())throw new SecurityException("body_call_cancelled");result.complete(original.body(permit,response));return null;});
        }catch(Exception unavailable){main.post(()->{try{result.complete(original.bodyUnavailable(permit,"response_body_evicted_cancelled_or_unavailable"));}catch(Exception invalid){result.completeExceptionally(invalid);}});}});
    }
    public JSONObject stop(String capture)throws Exception {require(capture);JSONObject receipt=new JSONObject(identity.toString()).put("captureId",capture).put("backend","webview_cdp_local_socket").put("status","stopped").put("accepted",true).put("networkRequestsCancelled",false);close();return receipt;}
    public boolean active(){return !closed;}
    private void require(String capture)throws Exception {current.check();if(closed||records==null||!records.active()||!records.captureId.equals(capture))throw new SecurityException("native_network_capture_not_current");}
    private <T>T onMain(Callable<T> action)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();CompletableFuture<T> result=new CompletableFuture<>();if(!main.post(()->{if(result.isDone())return;try{result.complete(action.call());}catch(Exception failure){result.completeExceptionally(failure);}}))throw new IllegalStateException("native_main_unavailable");
        try{return result.get(2000,TimeUnit.MILLISECONDS);}finally{result.cancel(false);}
    }
    public static JSONObject unavailable(String code,String message)throws Exception{return new JSONObject().put("status","unavailable").put("accepted",false).put("backend","webview_cdp_local_socket").put("code",code).put("message",message).put("networkIdleProven",false).put("automaticReplayAllowed",false);}
    public synchronized void invalidate(String reason){if(closed)return;closed=true;main.removeCallbacks(watch);BrowserDevToolsConnection socket=connection;if(socket!=null)socket.close();BrowserNetworkRecords current=records;if(current!=null)current.invalidate(reason);if(retained!=null){try{retained.close();}catch(Exception ignored){}retained=null;}}
    @Override public void close(){invalidate("capture_closed");}
}
