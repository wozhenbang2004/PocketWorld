package dev.pocketengine.browser;

import android.content.Context;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One exact original attachment through the real file chooser and original upload attempt ledger. */
public final class BrowserArtifactUpload implements AutoCloseable {
    public interface Host {
        void post(Runnable work);void later(Runnable work,long delayMs);void check()throws Exception;
        void openInput(BrowserArtifactUpload delivery)throws Exception;
        void discardUnsent(Uri stage)throws Exception;
    }
    private final Context context;private final TaskStore tasks;private final RunControl original;private final JSONObject identity,artifact;private final Executor worker;private final Host host;private final CompletableFuture<JSONObject> result;
    private final Runnable cancelled=this::close;
    private final AtomicBoolean closed=new AtomicBoolean(),callbackUsed=new AtomicBoolean();private volatile Uri stage;private volatile JSONObject attempt;private volatile ValueCallback<Uri[]> callback;private volatile boolean boundaryRecorded,delivered,chooserSeen,cleanupUnknown;
    public BrowserArtifactUpload(Context context,TaskStore tasks,RunControl original,JSONObject identity,JSONObject artifact,Executor worker,Host host,CompletableFuture<JSONObject> result)throws Exception {
        this.context=context.getApplicationContext();this.tasks=tasks;this.original=original;this.identity=new JSONObject(identity.toString());this.artifact=new JSONObject(artifact.toString());this.worker=worker;this.host=host;this.result=result;
        original.onCancel(cancelled);result.whenComplete((value,error)->{if(result.isCancelled()||error!=null)close();});
    }
    public void start(){worker.execute(()->{try{
        original.check();stage=BrowserUploads.stageArtifact(context,identity.getString("taskId"),artifact,original);if(closed.get()||result.isDone()){discard();return;}
        synchronized(tasks){requirePriorResolved();attempt=tasks.beginBrowserUpload(identity,new JSONArray().put(artifact));}
        host.post(()->{if(result.isDone()||closed.get()){close();return;}try{host.check();requireArtifact();host.openInput(this);host.later(()->{if(!chooserSeen&&!result.isDone())notDelivered("file_chooser_not_opened");},5000);}catch(Exception stale){notDelivered("original_file_input_changed");}});
    }catch(Exception failure){notDelivered("artifact_prepare_unavailable");}});}
    private void requireArtifact()throws Exception {original.check();if(!artifact.toString().equals(BrowserUploads.artifactPin(context,identity.getString("taskId"),artifact.getString("id"),artifact.getString("sha256")).toString()))throw new SecurityException("上传原件已撤下或版本变化");}
    private void requirePriorResolved()throws Exception {
        JSONArray attempts=tasks.get(identity.getString("taskId"),false).optJSONArray("browserUploadAttempts");
        for(int i=0;attempts!=null&&i<attempts.length();i++){JSONObject prior=attempts.getJSONObject(i),pin=prior.getJSONObject("pin");if(!prior.optString("deliveryState").matches("delivery_unknown|local_delivered")||prior.optString("siteState").matches("accepted|rejected")||!pin.optString("destinationDigest").equals(identity.getString("destinationDigest")))continue;JSONArray files=pin.getJSONArray("attachments");for(int j=0;j<files.length();j++)if(artifact.getString("id").equals(files.getJSONObject(j).getString("attachmentId"))&&artifact.getString("sha256").equals(files.getJSONObject(j).getString("sha256")))throw new SecurityException("同一原件对该站点已有未核实交付，须主人先核对，禁止自动重传");}
    }
    /** Called only by the native WebChromeClient for the exact original Page. */
    public synchronized boolean chooser(ValueCallback<Uri[]> actual,WebChromeClient.FileChooserParams params){
        if(closed.get()||result.isDone()||chooserSeen){actual.onReceiveValue(null);return true;}chooserSeen=true;
        callback=value->{if(callbackUsed.compareAndSet(false,true))actual.onReceiveValue(value);};
        try{host.check();requireArtifact();if(stage==null||params.getMode()!=WebChromeClient.FileChooserParams.MODE_OPEN&&params.getMode()!=WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE||!mimeAccepted(params.getAcceptTypes(),artifact.getString("mimeType"),artifact.getString("name")))throw new SecurityException("原文件控件不接受所选版本");
            worker.execute(()->{try{original.check();requireArtifact();dev.pocketengine.features.attachments.AttachmentRepository.get(context).privateOriginalForHost(artifact.getString("id"),artifact.getString("sha256"),original).requireCurrent(original);if(closed.get())throw new SecurityException("upload_cancelled");tasks.updateBrowserUpload(identity.getString("taskId"),attempt.getString("attemptId"),attempt.getString("fingerprint"),"delivery_entered",evidence("native_exact_artifact_file_chooser"));boundaryRecorded=true;
                host.post(()->{try{host.check();requireArtifact();if(closed.get()||callbackUsed.get())throw new SecurityException("upload_cancelled");delivered=true;callback.onReceiveValue(new Uri[]{stage});
                    worker.execute(()->{try{tasks.updateBrowserUpload(identity.getString("taskId"),attempt.getString("attemptId"),attempt.getString("fingerprint"),"local_delivered",evidence("native_webview_callback"));complete("local_delivered",true);}catch(Exception unknown){complete("unknown",false);}});
                }catch(Exception changed){cancelCallback();complete("unknown",false);}});
            }catch(Exception failure){cancelCallback();if(boundaryRecorded)complete("unknown",false);else notDelivered("artifact_changed_before_delivery");}});
        }catch(Exception rejected){cancelCallback();notDelivered("file_chooser_rejected_artifact");}return true;
    }
    private static boolean mimeAccepted(String[] accepted,String actual,String name){boolean constrained=false;for(String raw:accepted==null?new String[0]:accepted)if(raw!=null)for(String type:raw.split(",")){type=type.trim();if(type.isEmpty())continue;constrained=true;if(type.equals("*/*")||type.equalsIgnoreCase(actual)||type.endsWith("/*")&&actual.toLowerCase(java.util.Locale.ROOT).startsWith(type.substring(0,type.length()-1).toLowerCase(java.util.Locale.ROOT))||type.matches("\\.[A-Za-z0-9]{1,12}")&&name.toLowerCase(java.util.Locale.ROOT).endsWith(type.toLowerCase(java.util.Locale.ROOT)))return true;}return !constrained;}
    private JSONObject evidence(String source)throws Exception{return new JSONObject().put("source",source).put("siteConfirmed",false).put("operationId",identity.getString("operationId")).put("fileInput",identity.getJSONObject("fileInput"));}
    private void complete(String state,boolean accepted){try{
        if(!delivered)discard();JSONObject receipt=new JSONObject().put("status",cleanupUnknown?"cleanup_required":state).put("accepted",state.equals("unknown")?JSONObject.NULL:accepted).put("verified",false).put("siteState","unknown").put("siteConfirmed",false).put("automaticReplayAllowed",false).put("attachment",new JSONObject(artifact.toString()));if(attempt!=null)receipt.put("uploadAttemptId",attempt.getString("attemptId"));if(state.equals("local_delivered"))receipt.put("requiresPause",true).put("code","browser_upload_site_check_needed").put("message","文件已一次交给原WebView；请主人核对站点接收或拒绝后继续原任务，不会自动再次上传。");if(cleanupUnknown)receipt.put("requiresPause",true).put("code","artifact_upload_stage_cleanup_required");result.complete(receipt);closed.set(true);
    }catch(Exception failure){result.completeExceptionally(failure);}finally{original.removeCancelAction(cancelled);}}
    public JSONObject attemptSnapshot()throws Exception {return attempt==null?null:new JSONObject(attempt.toString());}
    public boolean chooserSeen(){return chooserSeen;}
    public void inputNotOpened(){if(!chooserSeen)notDelivered("file_input_did_not_open_chooser");}
    private void notDelivered(String code){if(boundaryRecorded){complete("unknown",false);return;}try{if(attempt!=null)tasks.updateBrowserUpload(identity.getString("taskId"),attempt.getString("attemptId"),attempt.getString("fingerprint"),"cancelled_before_delivery",new JSONObject().put("source","native_artifact_upload").put("reason",code));}catch(Exception unknown){complete("unknown",false);return;}
        discard();try{result.complete(new JSONObject().put("status",cleanupUnknown?"cleanup_required":"unavailable").put("accepted",false).put("verified",false).put("code",cleanupUnknown?"artifact_upload_stage_cleanup_required":code).put("requiresPause",cleanupUnknown).put("automaticReplayAllowed",false));}catch(Exception impossible){result.completeExceptionally(impossible);}close();}
    private void cancelCallback(){ValueCallback<Uri[]> actual=callback;if(actual!=null)host.post(()->actual.onReceiveValue(null));}
    private void discard(){Uri copy=stage;if(copy!=null&&!delivered){stage=null;try{host.discardUnsent(copy);}catch(Exception cleanup){cleanupUnknown=true;}}}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;original.removeCancelAction(cancelled);cancelCallback();discard();if(!result.isDone()){if(boundaryRecorded)complete("unknown",false);else notDelivered("upload_cancelled_before_delivery");}}
}
