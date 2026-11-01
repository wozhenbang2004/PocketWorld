package dev.pocketengine.browser;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

/** Main-thread bridge to the separately hosted browsing WebView. */
public final class BrowserModule implements EngineModule {
    private final FeatureContext context;
    private final dev.pocketengine.features.attachments.AttachmentOutcomeHost originalOwnerFiles;
    private final Handler main=new Handler(Looper.getMainLooper());
    private BrowserResearchDraftStore researchDrafts;
    private volatile boolean closed;
    private volatile long ownerProfileGeneration;
    public BrowserModule(FeatureContext context){this(context,null);}
    /** Package-native injection of the same canonical host, used by narrow real-store tests; never a model authority. */
    BrowserModule(FeatureContext context,dev.pocketengine.features.attachments.AttachmentOutcomeHost originalFiles){this.context=context;originalOwnerFiles=originalFiles;BrowserActivity.setEvents(value->{context.emit(id(),value);try{if(value.has("attachment"))context.emit("attachments",new JSONObject().put("status","imported").put("attachments",new org.json.JSONArray().put(value.getJSONObject("attachment"))));else if(value.has("attachments"))context.emit("attachments",new JSONObject().put("status",value.optString("status")).put("attachments",value.getJSONArray("attachments")));}catch(Exception ignored){}});}
    @Override public String id(){return "browser";}
    @Override public JSONObject snapshot()throws Exception {if(Looper.myLooper()==Looper.getMainLooper()){BrowserActivity activity=BrowserActivity.current();return activity==null?empty():activity.snapshot();}CompletableFuture<JSONObject> result=new CompletableFuture<>();main.post(()->{if(result.isDone())return;try{BrowserActivity activity=BrowserActivity.current();result.complete(activity==null?empty():activity.snapshot());}catch(Exception failure){result.completeExceptionally(failure);}});try{return result.get(3,TimeUnit.SECONDS);}finally{result.cancel(false);}}
    /** Typed native-owner API. Never dispatch these methods from featureRequest/model/JS/channel payloads. */
    public JSONObject nativeProfiles()throws Exception {BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");return BrowserProfiles.get(context.android).snapshot();}
    public JSONObject nativeTabs()throws Exception {BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");BrowserActivity browser=BrowserActivity.current();if(browser==null)throw new IllegalStateException("请先打开浏览器");return browser.tabsForOwner();}
    public org.json.JSONArray nativeDownloads()throws Exception {nativeProfiles();return BrowserDownloads.get(context.android).list(BrowserProfiles.get(context.android).selectedId());}
    public org.json.JSONArray nativeDownloadsForTask(String taskId)throws Exception {
        BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");BrowserTaskSpace.uuid(taskId);org.json.JSONArray all=BrowserDownloads.get(context.android).list(),rows=new org.json.JSONArray();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(taskId.equals(row.optString("taskId")))rows.put(row);}return rows;
    }
    public JSONObject nativeDownloadReceipt(String downloadId)throws Exception {BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");BrowserTaskSpace.uuid(downloadId);return BrowserDownloads.get(context.android).status(downloadId);}
    /** Original canonical owner-file host. This is not exposed by handle()/model/page/channel tools. */
    public Intent nativeOpenResearchAttachment(String taskId,String attachmentId,String expectedSha,dev.pocketengine.core.RunControl ownerRead)throws Exception {
        if(closed)throw new IllegalStateException("浏览器模块已关闭");return ownerFiles().open(taskId,attachmentId,expectedSha,ownerRead);
    }
    private dev.pocketengine.features.attachments.AttachmentOutcomeHost ownerFiles(){
        dev.pocketengine.features.attachments.AttachmentOutcomeHost files=originalOwnerFiles;android.content.Context app=context.android.getApplicationContext();
        if(files==null&&app instanceof dev.pocketengine.EngineApplication)files=((dev.pocketengine.EngineApplication)app).attachmentOutcomes;
        if(files==null)throw new IllegalStateException("原canonical文件成果宿主尚未就绪");return files;
    }
    public void nativeDiscardResearchView(Intent prepared)throws Exception {ownerFiles().discardUnissuedView(prepared);}
    private synchronized BrowserResearchDraftStore researchDrafts(){if(closed)throw new IllegalStateException("浏览器模块已关闭");if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("研究文字草稿只能由原有界worker读写");if(researchDrafts==null)researchDrafts=new BrowserResearchDraftStore(new java.io.File(context.android.getNoBackupFilesDir(),"browser/research-goals.json"));return researchDrafts;}
    /** Owner text only, never routed through handle/model/page/channel. Host checks original selected Profile/task. */
    public JSONObject nativeResearchDraft(String profileId,String taskId)throws Exception{return researchDrafts().read(profileId,taskId);}
    public JSONObject nativeSaveResearchDraft(String profileId,String taskId,long expectedRevision,String goal)throws Exception{return researchDrafts().save(profileId,taskId,expectedRevision,goal);}
    public void nativeClearResearchDraftProfile(String profileId)throws Exception{researchDrafts().clearProfile(profileId);}
    public BrowserTaskCapabilityPort nativeAttachTaskSpace(dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Binding binding,dev.pocketengine.core.RunControl originalControl,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Authority authority,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.ResourceCheck resource,BrowserTaskCapabilityPort.ApprovalPort approval,BrowserTaskSpace.SourcePort source,BrowserTaskSpace.NativeEvents events,dev.pocketengine.core.CapabilityPort existing,String expectedBrowserSessionId)throws Exception {
        BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");BrowserActivity browser=BrowserActivity.current();if(browser==null)throw new IllegalStateException("请主人先打开选定Profile的浏览器");return browser.attachTaskSpaceForOwner(binding,originalControl,authority,resource,approval,source,events,existing,expectedBrowserSessionId);
    }
    public JSONObject nativeCancelUpload(JSONObject attempt)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)return new JSONObject().put("source","owner_cancel").put("platformAbortConfirmed",false).put("note","原浏览器进程句柄不可用，不能确认停止；没有重传");return browser.cancelUploadForOwner(attempt);}
    public void nativeAdoptOwnerTab(String tabId)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");browser.adoptOwnerTabForTask(tabId);}
    public void nativeTakeOverTaskTab(String tabId)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");browser.takeOverTaskTabForOwner(tabId);}
    public JSONObject nativeReturnTaskSpace(JSONObject expected,dev.pocketengine.core.RunControl original,BrowserTaskSpace.Gate resume)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");return browser.returnTaskSpaceForOwner(expected,original,resume);}
    public void nativeSelectOwnerTab(String tabId)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");browser.selectOwnerTabForOwner(tabId);}
    public JSONObject nativeEndTaskSpace(String reason)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");return browser.endTaskSpaceForOwner(reason);}
    public JSONObject nativeOpenOwnerTab(String url)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");return browser.openOwnerTabForOwner(url);}
    public void nativeCloseOwnerTab(String tabId)throws Exception {BrowserProfiles.main();BrowserActivity browser=BrowserActivity.current();if(closed||browser==null)throw new IllegalStateException("浏览器不可用");browser.closeOwnerTabForOwner(tabId);}
    public void nativeOpenResearchUi(){nativeOpenResearchUi("");}
    public void nativeOpenResearchUi(String taskId){BrowserProfiles.main();if(closed)throw new IllegalStateException("浏览器模块已关闭");if(!taskId.isEmpty())BrowserTaskSpace.uuid(taskId);context.android.startActivity(new android.content.Intent(context.android,BrowserActivity.class).putExtra("showResearch",true).putExtra("browserResearchTaskId",taskId).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));BrowserActivity browser=BrowserActivity.current();if(browser!=null)browser.showResearchPanelForOwner(taskId);}
    public JSONObject nativeCreateProfile(String label,long expectedRevision)throws Exception {nativeProfiles();return BrowserProfiles.get(context.android).createForOwner(label,expectedRevision);}
    public JSONObject nativeSelectProfile(String id,long expectedRevision)throws Exception {
        nativeProfiles();BrowserProfiles profiles=BrowserProfiles.get(context.android);profiles.requireRevision(expectedRevision);ownerProfileGeneration++;profiles.selectForOwner(id,expectedRevision);
        BrowserActivity browser=BrowserActivity.current();if(browser!=null)browser.profileChangedForOwner();return profiles.snapshot();
    }
    public JSONObject nativeDeleteProfile(String id,long expectedRevision)throws Exception {
        nativeProfiles();BrowserProfiles profiles=BrowserProfiles.get(context.android);profiles.requireRevision(expectedRevision);ownerProfileGeneration++;boolean selected=id.equals(profiles.selectedId());profiles.deleteForOwner(id,expectedRevision);
        BrowserActivity browser=BrowserActivity.current();if(selected&&browser!=null)browser.profileChangedForOwner();profiles.cleanupPending();return profiles.snapshot();
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("浏览器模块已关闭");
        final long admittedProfileGeneration=ownerProfileGeneration;
        if(command.startsWith("profile.")||command.equals("profiles")||command.startsWith("task.")||command.startsWith("research.draft")||command.equals("tabs"))throw new SecurityException("浏览器资料/任务空间和私人文字草稿只能经原生入口和原任务工具授权");
        if("status".equals(command))return snapshot();
        CompletableFuture<JSONObject> result=new CompletableFuture<>();main.post(()->{if(result.isDone())return;try{
            if(closed)throw new IllegalStateException("浏览器模块已关闭");if(admittedProfileGeneration!=ownerProfileGeneration)throw new SecurityException("排队请求所属浏览器资料已变化");BrowserProfiles profiles=BrowserProfiles.get(context.android);String selected=profiles.selectedId();
            if(input.has("profileId")&&!selected.equals(input.optString("profileId")))throw new SecurityException("请求不属于主人当前选择的浏览器资料");
            switch(command){
                case "downloads":result.complete(new JSONObject().put("profileId",selected).put("downloads",BrowserDownloads.get(context.android).list(selected)));return;
                case "download.status":result.complete(BrowserDownloads.get(context.android).status(input.getString("id"),selected));return;
                case "download.cancel":result.complete(BrowserDownloads.get(context.android).cancel(input.getString("id"),selected));return;
                case "open":{
                    String url=input.optString("url");BrowserActivity previous=BrowserActivity.current();Intent show=new Intent(context.android,BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);if(previous!=null)previous.openForOwner(url);else show.putExtra("url",url);context.android.startActivity(show);
                    result.complete(new JSONObject().put("status","open_requested").put("profileId",selected).put("agentDomTools",true).put("executionLeaseGranted",false));return;
                }
                default:BrowserActivity browser=BrowserActivity.current();if(browser==null)throw new IllegalStateException("请先打开内置浏览器");browser.execute(command,input,result);
            }
        }catch(Exception failure){result.completeExceptionally(failure);}});
        try{return result.get(8,TimeUnit.SECONDS);}catch(TimeoutException timeout){main.post(()->{BrowserActivity browser=BrowserActivity.current();if(browser!=null)browser.commandTimedOut(result);});if(command.matches("click|type|scroll|navigate|back|forward|reload"))return new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("verified",false).put("code","action_result_unknown").put("message","浏览器操作超时，请先核对结果");throw new IllegalStateException("浏览器未及时返回，请重新检查状态");}finally{result.cancel(false);}
    }
    @Override public void close(){closed=true;BrowserDownloads.closeFor(context.android);main.post(()->{BrowserActivity browser=BrowserActivity.current();if(browser!=null){try{browser.endTaskSpaceForOwner("module_closed");}catch(Exception ignored){}browser.takeover("宿主已停止浏览器执行");}BrowserActivity.setEvents(null);});}
    private JSONObject empty()throws Exception{JSONObject profiles=BrowserProfiles.get(context.android).snapshot();JSONObject capabilities=new JSONObject().put("multiProfileSupported",profiles.optBoolean("multiProfileSupported")).put("featureCheck",profiles.optString("featureCheck")).put("ownerManagementOnly",true);return new JSONObject().put("open",false).put("agentDomTools",true).put("backgroundDisplay",false).put("profileId",profiles.optString("selectedProfileId")).put("profiles",capabilities).put("profileIsolation",profiles.optString("profileIsolation")).put("sharedWithSystemChrome",false);}
}
