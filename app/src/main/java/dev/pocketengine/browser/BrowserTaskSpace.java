package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-local page ownership only. Original TaskStore/RunControl retain all business authority. */
public final class BrowserTaskSpace {
    public static final int MAX_TASK_TABS=4;
    public static class StalePage extends SecurityException {public StalePage(String message){super(message);}}
    public static final class FrameReadUnavailable extends StalePage {public FrameReadUnavailable(){super("原嵌入网页引用未实际展示、已变化或已过期");}}
    public interface Gate {void check()throws Exception;default void entered(){}default void liveCheck()throws Exception{check();}}
    public interface Ui {boolean isMain();void post(Runnable work);}
    /** Only this Space can mint an actually delivered, explicitly retained safe subset. Never serialized authority. */
    public static final class DeliveredSource {
        private final String fingerprint;private final JSONObject subset;
        private DeliveredSource(JSONObject source)throws Exception {
            fingerprint=dev.pocketengine.store.TaskStore.capabilityFingerprint(source);subset=deliveredSourceProjection(source);
        }
        JSONObject forSource(JSONObject source)throws Exception {if(!fingerprint.equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(source)))throw new SecurityException("native交付子集与实际保存版本不同");return copy(subset);}
    }
    /** One closed projection shared with the live native carrier. This helper never admits a
     * page or grants a read. Callers must supply the original SQL event under their own native scope. */
    private static JSONObject deliveredSourceProjection(JSONObject source)throws Exception {
        if(!BrowserSemanticRead.KIND.equals(source.optString("kind")))throw new SecurityException("仅原实际语义交付来源有安全子集");
        JSONObject subset=new JSONObject().put("kind","browser_semantic_retained_delivered_subset").put("scope","explicitly_retained_model_delivered_excerpt_and_fields_only").put("wholePageIncluded",false).put("untrustedPageContent",true).put("verified",false);
        for(String k:new String[]{"taskId","runId","epoch","sourceId","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","frameId","observationId","excerpt"})subset.put(k,source.get(k));
        if(source.has("structuredProjection"))subset.put("structuredProjection",copy(source.getJSONObject("structuredProjection")));
        return subset;
    }
    /** Recover only an explicitly retained safe-delivery projection, never current page state.
     * The body-free event pin was written by retainDelivered after the real saveSources/prepared
     * checks. Old unpinned/public/ordinary observation events are deliberately unsupported. */
    public static JSONObject savedMemoryProjection(JSONObject originalNativeEvent)throws Exception {
        if(originalNativeEvent==null||!"browser_source_retained".equals(originalNativeEvent.optString("type"))||!"pocketworld.browser-retained-safe-delivery.v1".equals(originalNativeEvent.optString("safeProjectionFormat"))||!originalNativeEvent.optString("safeProjectionFingerprint").matches("[0-9a-f]{64}"))throw new SecurityException("原保存事件没有实际安全交付pin，不能推测或回填");
        JSONObject source=originalNativeEvent.getJSONObject("source");
        for(String key:new String[]{"taskId","runId","sourceId"})if(!source.getString(key).equals(originalNativeEvent.getString(key)))throw new SecurityException("原网页保存来源身份不一致");
        if(source.getInt("epoch")!=originalNativeEvent.getInt("epoch")||!dev.pocketengine.store.TaskStore.capabilityFingerprint(source).equals(originalNativeEvent.getString("sourceFingerprint")))throw new SecurityException("原网页保存来源版本不一致");
        JSONObject subset=deliveredSourceProjection(source);
        if(!dev.pocketengine.store.TaskStore.capabilityFingerprint(subset).equals(originalNativeEvent.getString("safeProjectionFingerprint")))throw new SecurityException("原安全交付子集已改变");
        return copy(subset);
    }
    public interface SourcePort {
        JSONObject retain(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String operationId,String stableSourceId,JSONObject lowTrustSource)throws Exception;
        default JSONObject retainDelivered(ModelCapabilityAdapters.Binding binding,RunControl original,String operationId,String sourceId,JSONObject source,DeliveredSource delivered)throws Exception {return retain(binding,original,operationId,sourceId,source);}
        JSONObject lookup(ModelCapabilityAdapters.Binding binding,String stableSourceId)throws Exception;
        default boolean publicHttpRetention(){return false;}
        default JSONObject retainPublic(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String operationId,String stableSourceId,JSONObject lowTrustSource,PublicHttpReader.Budget budget)throws Exception{throw new UnsupportedOperationException("公共HTTP来源保存端口未接入");}
    }
    public interface NativeEvents {
        void takenOver(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String spaceId,String tabId);
        void ended(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String spaceId,JSONObject cleanupReceipt);
        default void ownerBrowsing(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String spaceId,String ownerTabId) { }
        default void pageDialog(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String spaceId,JSONObject receipt) { }
        default boolean pageDialogCurrent(ModelCapabilityAdapters.Binding binding,RunControl originalControl){return false;}
        default Set<String> frameReadOrigins(){return Set.of();}
        default JSONObject recoveryPages(){return null;}
        default BrowserReadRecipeCatalog readRecipes(){return null;}
        default boolean pageManifest(ModelCapabilityAdapters.Binding binding,RunControl originalControl,JSONObject manifest){return false;}
        default void siteRequirement(ModelCapabilityAdapters.Binding binding,RunControl originalControl,String spaceId,JSONObject notice) { }
    }
    /** Implemented by the existing Activity; every method except cleanup runs behind the live gate. */
    public interface Driver {
        JSONObject metadata(Tab tab)throws Exception;
        /** Only safe metadata leaves this port. Exact destinations remain in the original private Profile. */
        default JSONObject recoveryRecord(String logicalId,String url,String title,String state,String openedBy)throws Exception {return BrowserResearchPages.page(logicalId,url,title,state,openedBy);}
        default String recoveryUrl(JSONObject page)throws Exception {if(page.has("privateNavigationId"))throw new StalePage("原私有地址端口不可用，未改开脱敏地址");if(page.optBoolean("urlQueryOmitted"))throw new StalePage("原页参数未保存，不能用基础地址冒作原内容");return page.getString("url");}
        void open(Tab tab,String url,Gate gate)throws Exception;
        void select(Tab tab,Gate gate)throws Exception;
        void execute(Tab tab,String command,JSONObject arguments,Gate gate,CompletableFuture<JSONObject> result)throws Exception;
        JSONObject download(Tab tab,String url,String operationId,Gate gate)throws Exception;
        default JSONObject downloadDocument(Tab tab,JSONObject args,String operationId,Gate gate)throws Exception {throw new UnsupportedOperationException("原文档文件读取宿主不可用");}
        default JSONObject downloadStatus(String id,Gate gate)throws Exception {throw new UnsupportedOperationException("下载状态端口不可用");}
        void adoptOwner(Tab tab,Gate gate)throws Exception;
        default JSONObject ownerMetadata(String tabId)throws Exception {throw new UnsupportedOperationException("主人页状态不可用");}
        default void returnOwner(Tab tab,Gate gate)throws Exception {adoptOwner(tab,gate);}
        void releaseToOwner(Tab tab)throws Exception;
        boolean closeOwned(Tab tab)throws Exception;
        /** End/cancel releases borrowed owner pages; an explicit approved close still uses closeOwned. */
        default boolean closeOnFinish(Tab tab)throws Exception{return closeOwned(tab);}
        void abort(Tab tab,CompletableFuture<JSONObject> operation);
        default void ended() { }
        default JSONObject presentedObservation(Tab tab,JSONObject delivered)throws Exception {return new JSONObject(delivered.toString());}
        default java.util.List<BrowserReadDelta.FullResync> readContextRepairs(Tab tab,JSONArray messages)throws Exception {return java.util.List.of();}
        default void beforeModelRequest(Tab tab,String id,JSONArray messages)throws Exception { }
        default void afterModelResponse(Tab tab,String id)throws Exception { }
        default void modelRequestAbandoned(Tab tab,String id)throws Exception { }
        default void dialogOperationSettled(Tab tab,BrowserPageDialog dialog)throws Exception { }
        default boolean visualAvailable(){return false;}
        default JSONObject visualPin(Tab tab,JSONObject args)throws Exception {throw new UnsupportedOperationException("当前网页宿主不可截图");}
        default void visualCapture(Tab tab,JSONObject args,JSONObject pin,Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {throw new UnsupportedOperationException("当前网页宿主不可截图");}
        default JSONObject readFramePin(Tab tab,String ref)throws Exception {throw new FrameReadUnavailable();}
        default JSONObject deliveredObservation(Tab tab,JSONObject actual)throws Exception {return new JSONObject(actual.toString());}
        default void requireObservedFrame(Tab tab,JSONObject observed)throws Exception {if(!observed.optString("frameId","main").equals("main"))throw new StalePage("原子frame来源未配置");}
    }
    public final String spaceId=UUID.randomUUID().toString();
    public final ModelCapabilityAdapters.Binding binding;
    public final RunControl originalControl;
    public final BrowserProfileRegistry.Session profile;
    private final Driver driver;
    private final Ui ui;
    private final NativeEvents events;
    private final SourcePort sources;
    private final Set<String> frameOrigins;
    private final JSONObject recoveryPages;private final Map<String,JSONObject> logicalPages=new LinkedHashMap<>();private long manifestRevision;private String manifestFingerprint="";private boolean manifestSaved;
    private final Map<String,Tab> tabs=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,JSONObject> observations=new LinkedHashMap<>();
    private final Map<String,JSONObject> sourceAttempts=new LinkedHashMap<>();
    private final Map<String,JSONObject> readObservations=new LinkedHashMap<>();
    private final Map<String,JSONObject> deliveredStructuredObservations=new LinkedHashMap<>();
    private final Map<String,JSONObject> downloadRefs=new LinkedHashMap<>();
    private final Map<String,Tab> nativeCalls=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,BrowserPageDialog.Call> callDialogs=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile JSONObject lastPageDialog;
    private final Map<String,Long> handedOff=new java.util.concurrent.ConcurrentHashMap<>();private final Map<String,String> handoffLogical=new LinkedHashMap<>();
    private String handoffReason="";
    private String lastTaskTab="";
    private volatile boolean ended,suspended;
    private long revision=1;
    private final Runnable cancelled;

    public BrowserTaskSpace(ModelCapabilityAdapters.Binding binding,RunControl originalControl,BrowserProfileRegistry.Session profile,Driver driver,Ui ui,SourcePort sources,NativeEvents events)throws Exception {
        this.binding=java.util.Objects.requireNonNull(binding);this.originalControl=java.util.Objects.requireNonNull(originalControl);this.profile=java.util.Objects.requireNonNull(profile);
        this.driver=java.util.Objects.requireNonNull(driver);this.ui=java.util.Objects.requireNonNull(ui);this.sources=sources;this.events=events;frameOrigins=BrowserFrameOrigins.checkedExtras(events==null?Set.of():events.frameReadOrigins());recoveryPages=events==null||events.recoveryPages()==null?null:copy(events.recoveryPages());if(recoveryPages!=null){BrowserResearchPages.validate(recoveryPages);if(!binding.taskId.equals(recoveryPages.getString("taskId"))||!profile.profileId.equals(recoveryPages.getString("profileId")))throw new SecurityException("恢复页清单不属于原任务/资料");
            // Carry logical metadata forward before any new page publishes. Never rebuild a Tab,
            // old run/session/ref/grant, or convert an unresolved result into a reloadable success.
            JSONArray prior=recoveryPages.getJSONArray("pages");for(int i=0;i<prior.length();i++){JSONObject row=copy(prior.getJSONObject(i));if(!row.getString("state").equals("unknown"))row.put("state","needs_reload");row.put("needsFreshObservation",true);logicalPages.put(row.getString("logicalPageId"),row);}
        }
        checkOriginal(originalControl);cancelled=()->{invalidateAllTabs();ui.post(()->{try{endNative("cancelled");}catch(Exception ignored){}});};originalControl.onCancel(cancelled);
    }
    public boolean visualAvailable(){return driver.visualAvailable();}
    public JSONObject visualPin(JSONObject args)throws Exception {main();checkOriginal(originalControl);Tab tab=required(args.getString("tabId"));return pin(args).put("visual",driver.visualPin(tab,args)).put("visualDisclosure",new JSONObject().put("sendsActualViewportPixels",true).put("encodedByteLimit",BrowserVisualCapture.MAX_BYTES).put("maxPixels",BrowserVisualCapture.MAX_PIXELS).put("retainedAsSource",false).put("coordinateAuthority",false));}
    public void requireVisualPin(JSONObject expected,JSONObject args)throws Exception {main();if(!dev.pocketengine.store.TaskStore.capabilityFingerprint(expected).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(visualPin(args))))throw new StalePage("截图的原文档、滚动、缩放或可见区域已变化，未转给新页面");}
    public void captureVisual(JSONObject args,JSONObject pin,Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {main();checkOriginal(originalControl);gate.check();driver.visualCapture(required(args.getString("tabId")),args,pin.getJSONObject("visual"),gate,result);}
    public BrowserReadRecipeCatalog.Prepared prepareReadRecipe(JSONObject args)throws Exception {checkOriginal(originalControl);if(args==null||!args.has("recipe"))return null;BrowserReadRecipeCatalog catalog=events==null?null:events.readRecipes();if(catalog==null)throw new UnsupportedOperationException("原读取配方宿主尚未接入，请普通observe");return catalog.prepare(profile.profileId,args.getJSONObject("recipe"),args.getJSONObject("read"));}
    public JSONArray readRecipesForHost()throws Exception {checkOriginal(originalControl);BrowserReadRecipeCatalog catalog=events==null?null:events.readRecipes();JSONArray result=catalog==null?new JSONArray():catalog.forHost(profile.profileId);checkOriginal(originalControl);return result;}
    public void requirePinRecipe(JSONObject pin,JSONObject args,BrowserReadRecipeCatalog.Prepared recipe)throws Exception {if(!pin.toString().equals(pinRecipe(recipe,args).toString()))throw new StalePage("读取配方审批后原页或配置目标已变化");}
    public JSONObject pinRecipe(BrowserReadRecipeCatalog.Prepared recipe,JSONObject args)throws Exception {main();JSONObject pin=pin(args);if(recipe==null)return pin;Tab tab=required(args.getString("tabId"));JSONObject page=driver.metadata(tab);boolean child=args.getJSONObject("read").has("frameRef");String actualOrigin=child?driver.readFramePin(tab,args.getJSONObject("read").getString("frameRef")).getString("sourceOrigin"):origin(new URI(page.getString("url")));recipe.requirePage(profile.profileId,actualOrigin,page.getString("url"),child);return pin.put("readRecipe",recipe.identity());}
    public Set<String> frameOriginsForNative(){return frameOrigins;}
    public boolean sourcePortAvailable(){return sources!=null;}
    public boolean publicHttpAvailable(){return sources!=null&&sources.publicHttpRetention();}
    public boolean ended(){return ended;}
    public boolean waitingForOwner(){return !ended&&suspended&&profile.current()&&originalControl.isPaused()&&!originalControl.isCancelled();}
    public void checkOriginalRun(RunControl supplied)throws Exception {
        if(supplied!=originalControl||!originalControl.hasHostRun()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("任务浏览必须保留原Task/Run/epoch与同一个RunControl");
        originalControl.check();if(originalControl.isPaused())throw new SecurityException("原任务已暂停");
    }
    public void checkOriginal(RunControl supplied)throws Exception {
        checkOriginalRun(supplied);if(ended||suspended||!profile.current())throw new SecurityException("任务浏览空间已暂停、结束或资料失效");
    }
    public JSONObject snapshot()throws Exception {
        main();JSONArray pages=new JSONArray();for(Tab tab:tabs.values()){JSONObject item=tab.identity().put("active",tab.current());try{JSONObject page=driver.metadata(tab);JSONObject visible=new JSONObject().put("navigation",page.getLong("navigation")).put("loading",page.optBoolean("loading")).put("url",bounded(page.optString("url"),2048)).put("title",bounded(page.optString("title"),240));if(page.has("siteNotice"))visible.put("siteNotice",copy(page.getJSONObject("siteNotice")));item.put("page",visible);}catch(Exception unavailable){item.put("pageUnavailable",true);}pages.put(item);}
        JSONArray downloads=new JSONArray();for(Map.Entry<String,JSONObject> entry:downloadRefs.entrySet())downloads.put(new JSONObject().put("id",entry.getKey()).put("tabId",entry.getValue().getString("tabId")));
        JSONArray returnable=new JSONArray();for(String tabId:handedOff.keySet())try{returnable.put(returnPinNative(tabId));}catch(Exception unavailable){returnable.put(new JSONObject().put("tabId",tabId).put("unavailable",true));}
        JSONObject snapshot=identity().put("revision",revision).put("status",ended?"ended":suspended?"owner_control":"active").put("tabs",pages).put("handoffPages",returnable).put("interactionCandidates",interactionCandidatesForOwner()).put("handoffReason",handoffReason).put("downloads",downloads).put("maxTaskTabs",MAX_TASK_TABS).put("sourceRetentionAvailable",sources!=null).put("transientPageOwnership",true).put("semanticReads",new JSONObject().put("modes",new JSONArray().put("outline").put("text").put("table").put("list")).put("optionalObserveRead",true).put("dynamicSettle",new JSONObject().put("maxTimeoutMs",6000).put("maxSamples",32).put("afterProjectionChange",true).put("networkIdleProven",false)).put("queryMatching","literal_case_insensitive").put("regionAndCursorRefs","read_only_evidence_not_action_refs").put("regionRefresh","actual_model_known_base_complete_region_ordered_delta_or_explicit_full_resync").put("maxOutputBytes",32768).put("scope","selected_admitted_frame_light_open_shadow_and_slots_with_explicit_unread_frames").put("extraFrameOrigins",new JSONArray(frameOrigins)));
        if(recoveryPages!=null)snapshot.put("recovery",copy(recoveryPages));snapshot.put("logicalPageManifestSaved",manifestSaved);if(lastPageDialog!=null)snapshot.put("pageDialog",copy(lastPageDialog));
        if(!ended&&suspended&&originalControl.isPaused()&&!originalControl.isCancelled())snapshot.put("continuePin",returnPinNative(""));return snapshot;
    }
    /** Only already-observed, actually presented controls; no hidden DOM scan or page-derived authority. */
    public JSONArray interactionCandidatesForOwner()throws Exception {
        main();JSONArray rows=new JSONArray();if(ended||suspended||originalControl.isPaused()||originalControl.isCancelled())return rows;
        for(Map.Entry<String,JSONObject> entry:observations.entrySet()){JSONObject observed=entry.getValue();JSONArray targets=observed.getJSONArray("elementPreviews");for(int i=0;i<targets.length()&&rows.length()<24;i++){JSONObject target=targets.getJSONObject(i);if(!BrowserActionScope.candidate(target)||!target.optString("nodeKey").matches("k[1-9][0-9]{0,6}"))continue;try{JSONObject args=new JSONObject().put("tabId",entry.getKey()).put("observationId",observed.getString("observationId")).put("ref",target.getString("ref")),pin=pin(args);if(!pin.optString("documentId").isEmpty())rows.put(new JSONObject().put("arguments",args).put("pin",pin));}catch(Exception stale){/* Current native observations only. */}}}return rows;
    }
    public void requireInteractionCandidatesCurrent(JSONObject policy)throws Exception {main();JSONArray selected=policy.getJSONArray("observedTargets");for(int i=0;i<selected.length();i++){JSONObject row=selected.getJSONObject(i);requirePin(row.getJSONObject("pin"),row.getJSONObject("arguments"));if(!BrowserActionScope.candidate(row.getJSONObject("pin").getJSONObject("targetPreview")))throw new SecurityException("原交互控件已不可授予");}}
    public JSONObject identity()throws Exception {return profile.identity().put("browserSpaceId",spaceId).put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch);}
    public JSONObject pin(JSONObject args)throws Exception {
        main();checkOriginal(originalControl);JSONObject pin=identity().put("spaceRevision",revision);
        if(args.has("downloadId")){String id=args.getString("downloadId");uuid(id);if(!downloadRefs.containsKey(id))throw new SecurityException("下载不属于原任务浏览空间");pin.put("downloadId",id);}
        if(args.has("tabId")){Tab tab=required(args.getString("tabId"));JSONObject page=driver.metadata(tab);pin.put("tabId",tab.tabId).put("tabGeneration",tab.generation).put("navigation",page.getLong("navigation")).put("pageIdentityDigest",digest(page.optString("url"))).put("pageUrl",page.optString("url")).put("documentId",page.optString("documentId"));
            if(args.has("observationId")){JSONObject observation=observation(tab,args.getString("observationId"));if(observation.getLong("navigation")!=page.getLong("navigation"))throw new SecurityException("来源网页已跳转，请重新观察");pin.put("observationId",observation.getString("observationId"));if(observation.has("actionFrame")){JSONObject frame=observation.getJSONObject("actionFrame"),current=driver.readFramePin(tab,frame.getString("frameId"));for(String key:new String[]{"frameId","documentId","sourceOrigin","topDocumentId"})if(!String.valueOf(frame.get(key)).equals(String.valueOf(current.get(key))))throw new StalePage("原动作frame文档已变化");pin.put("actionFrame",copy(frame));}if(args.has("ref")){JSONObject target=null;JSONArray rows=observation.getJSONArray("elementPreviews");for(int i=0;i<rows.length();i++)if(args.getString("ref").equals(rows.getJSONObject(i).getString("ref")))target=rows.getJSONObject(i);if(target==null)throw new SecurityException("此控件不在当次观察中");pin.put("targetPreview",copy(target)).put("targetPreviewUntrusted",true);}}}
        if(args.has("frameRef"))pin.put("readFrame",driver.readFramePin(required(args.getString("tabId")),args.getString("frameRef")));
        if(args.optJSONObject("read")!=null&&args.getJSONObject("read").has("frameRef"))pin.put("readFrame",driver.readFramePin(required(args.getString("tabId")),args.getJSONObject("read").getString("frameRef")));
        if(args.optJSONObject("target")!=null&&args.getJSONObject("target").has("frameRef"))pin.put("readFrame",driver.readFramePin(required(args.getString("tabId")),args.getJSONObject("target").getString("frameRef")));
        if(args.has("optionRef")||args.has("checked")){JSONObject target=pin.optJSONObject("targetPreview");if(target==null)throw new StalePage("表单操作需要当前已展示控件");BrowserFormControls.requireTarget(target,args);}
        if(retentionArgs(args)){JSONObject source=sourceFor(args),projection=source.optJSONObject("structuredProjection");int fields=0;if(projection!=null)for(int i=0;i<projection.getJSONArray("data").length();i++)fields+=projection.getString("mode").equals("list")?1:projection.getJSONArray("data").getJSONObject(i).getJSONArray("cells").length();pin.put("sourceRetentionPin",dev.pocketengine.store.TaskStore.capabilityFingerprint(source)).put("sourceRetentionPreview",new JSONObject().put("mode",projection==null?"text_excerpt":projection.getString("mode")).put("rows",projection==null?0:projection.getJSONArray("data").length()).put("fields",fields).put("partial",projection!=null&&projection.getBoolean("partial")).put("excerptPreview",unicodeClip(source.getString("excerpt"),400)));}
        if(args.has("steps"))pin.put("sequenceTargets",BrowserToolSequence.pinTargets(args,observation(required(args.getString("tabId")),args.getString("observationId"))));
        pin.put("dataProvenance",new JSONObject().put("sourceScope","owner_private_profile").put("sourceKind","untrusted_rendered_page_or_model_context")
                .put("declaredSinkCoverage","navigation_url_href_form_action_and_submitter_formaction_only").put("ajaxAndScriptSinksCovered",false));
        if(args.has("pageRecordId")){JSONObject record=recoveryPage(args.getString("pageRecordId"),args.getString("url"));pin.put("recoveryPage",record);}
        if(args.has("url")){URI destination=new URI(args.getString("url"));pin.getJSONObject("dataProvenance").put("destinationOrigin",origin(destination))
                .put("urlContainsQueryOrFragment",destination.getRawQuery()!=null||destination.getRawFragment()!=null);}
        return pin;
    }
    /** Native anonymous acquisition policy; not a tab/document identity and not a model-provided grant. */
    public JSONObject publicHttpPin(JSONObject args)throws Exception {
        return pin(args).put("dataProvenance",new JSONObject().put("sourceScope","public_anonymous_response").put("sourceKind","untrusted_unrendered_http_body")
                .put("profileCredentialsUsed",false).put("systemProxyCredentialsUsed",false).put("rendered",false))
                .put("publicHttpPolicy",PublicHttpReader.approvalPolicy(args));
    }
    public void requirePublicHttpPin(JSONObject pin,JSONObject args)throws Exception {if(!pin.toString().equals(publicHttpPin(args).toString()))throw new StalePage("审批后原空间、资料或公共读取策略已变化，尚未派发");}
    public JSONObject receiptPageState(JSONObject args)throws Exception {
        main();if(args==null||!args.has("tabId"))return null;Tab tab=required(args.getString("tabId"));JSONObject page=driver.metadata(tab);
        JSONObject result=tab.identity().put("navigation",page.getLong("navigation")).put("pageIdentityDigest",digest(page.optString("url")));
        JSONObject observation=observations.get(tab.tabId);if(observation!=null&&observation.optLong("navigation",-1)==page.getLong("navigation"))result.put("observationId",observation.optString("observationId"));
        return result;
    }
    private static String origin(URI uri)throws Exception{return new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toASCIIString();}
    public void requirePin(JSONObject pin,JSONObject args)throws Exception {if(!pin.toString().equals(pin(args).toString()))throw new StalePage("审批后页面、空间或观察已变化，尚未派发");}
    public void requireSequencePage(JSONObject initial)throws Exception {
        main();checkOriginal(originalControl);Tab tab=required(initial.getString("tabId"));JSONObject page=driver.metadata(tab),current=tab.identity();
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(!String.valueOf(initial.get(key)).equals(String.valueOf(current.get(key))))throw new StalePage("组合原页面身份已变化");
        if(page.getLong("navigation")!=initial.getLong("navigation")||!page.getString("documentId").equals(initial.getString("documentId"))||!page.getString("url").equals(initial.getString("pageUrl")))throw new StalePage("组合页面已导航，剩余步骤没有派发");
    }
    public JSONObject sequenceObservation(String tabId)throws Exception {main();checkOriginal(originalControl);Tab tab=required(tabId);JSONObject latest=observations.get(tab.tabId);return latest==null?null:copy(latest);}
    public void finalSequenceObservation(JSONObject args,JSONObject delivered)throws Exception {
        main();checkOriginal(originalControl);if(args.has("read")||delivered.optBoolean("semanticRead"))return;JSONObject captured=observations.get(args.getString("tabId"));if(captured==null)return;captured.put("runnerDelivered",false);
        if(!delivered.optString("status").equals("observed")||!delivered.optBoolean("ok")||!captured.getString("observationId").equals(delivered.optString("observationId"))||delivered.optJSONArray("elements")==null)return;
        presentedObservation(delivered);captured.put("runnerDelivered",true);
    }
    public CompletableFuture<JSONObject> dispatch(String command,JSONObject args,String operationId,Gate gate)throws Exception {
        main();checkOriginal(originalControl);gate.check();CompletableFuture<JSONObject> result=new CompletableFuture<>();
        if(command.equals("open")){
            if(tabs.size()>=MAX_TASK_TABS)throw new IllegalStateException("一个任务最多4个浏览页，请先保留来源或关闭已有任务页");
            JSONObject recovered=args.has("pageRecordId")?recoveryPage(args.getString("pageRecordId"),args.getString("url")):null;String navigationUrl=recovered==null?args.getString("url"):driver.recoveryUrl(recovered);
            Tab tab=new Tab(UUID.randomUUID().toString(),recovered==null?UUID.randomUUID().toString():recovered.getString("logicalPageId"));tabs.put(tab.tabId,tab);revision++;
            try{recordPage(tab,navigationUrl,"","opening","task");driver.open(tab,navigationUrl,gate);gate.check();lastTaskTab=tab.tabId;publishManifest();result.complete(tab.identity().put("accepted",true).put("verified",false).put("status","navigation_requested").put("freshObservationRequired",true));}
            catch(Exception failure){tab.invalidate();try{driver.closeOwned(tab);tabs.remove(tab.tabId);}catch(Exception cleanup){throw new IllegalStateException("新任务页创建失败且清理未完成",failure);}throw failure;}return result;
        }
        if(command.equals("finish")){gate.check();gate.entered();result.complete(endNative("task_finished"));return result;}
        if(command.equals("download_status")){String id=args.getString("downloadId");JSONObject expected=downloadRefs.get(id);if(expected==null)throw new SecurityException("下载不属于此任务");gate.check();JSONObject found=driver.downloadStatus(id,gate);for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId"})if(!String.valueOf(expected.get(key)).equals(String.valueOf(found.opt(key))))throw new SecurityException("下载状态回执身份已变化");result.complete(found);return result;}
        Tab tab=required(args.getString("tabId"));
        lastTaskTab=tab.tabId;
        switch(command){
            case "request_user":driver.select(tab,gate);gate.check();JSONObject handoff=tab.identity().put("accepted",true).put("verified",false).put("status","owner_control").put("message",bounded(args.getString("reason"),600));gate.entered();handoffReason=bounded(args.getString("reason"),600);takeOverNative(tab.tabId);result.complete(handoff);break;
            case "select":driver.select(tab,gate);gate.check();revision++;recordCurrentPage(tab,"ready");publishManifest();result.complete(tab.identity().put("accepted",true).put("verified",false).put("status","selected"));break;
            case "close":gate.check();gate.entered();recordCurrentPage(tab,"closed");tab.invalidate();observations.remove(tab.tabId);clearReadObservations(tab.tabId);driver.closeOwned(tab);tabs.remove(tab.tabId);revision++;publishManifest();result.complete(tab.identity().put("accepted",true).put("verified",false).put("status","closed"));break;
            case "download":if(observation(tab,args.getString("observationId")).optBoolean("readOnlyEvidence"))throw new StalePage("语义读取证据不是下载动作引用，请重新普通observe");if(downloadRefs.size()>=100)throw new IllegalStateException("本浏览空间下载引用已达上限");JSONObject job=driver.download(tab,args.getString("url"),operationId,gate);String id=job.getString("id");uuid(id);downloadRefs.put(id,tab.identity());result.complete(job);break;
            case "download_document":{
                JSONObject captured=observation(tab,args.getString("observationId"));if(captured.optBoolean("readOnlyEvidence")||captured.optJSONObject("actionFrame")!=null)throw new StalePage("文档下载仅支持原主文档实际展示的链接");
                JSONObject target=pin(args).getJSONObject("targetPreview");if(!target.optString("tag").equals("A")||!target.optString("href").matches("(?s)^(blob:|data:).+"))throw new SecurityException("请先观察原页面的blob/data下载链接");
                if(downloadRefs.size()>=100)throw new IllegalStateException("本浏览空间下载引用已达上限");JSONObject documentJob=driver.downloadDocument(tab,copy(args).put("_nativeTargetPreview",target),operationId,gate);String documentId=documentJob.getString("id");uuid(documentId);downloadRefs.put(documentId,tab.identity());result.complete(documentJob);break;
            }
            default:
                JSONObject dispatchArgs=args;
                if(command.matches("click|type|set_date|focus|press|select_option|set_checked|scroll")&&observation(tab,args.getString("observationId")).optBoolean("readOnlyEvidence"))throw new StalePage("语义区域/续读仅作证据，请用普通observe取得原动作ref");
                if(command.matches("click|type|set_date|focus|press|select_option|set_checked")){
                    JSONObject observed=observation(tab,args.getString("observationId")),target=null;JSONArray previews=observed.getJSONArray("elementPreviews");
                    for(int i=0;i<previews.length();i++)if(args.getString("ref").equals(previews.getJSONObject(i).optString("ref")))target=previews.getJSONObject(i);
                    if(target==null)throw new StalePage("原审批目标已不在当次观察中");
                    if(BrowserFormControls.command(command))BrowserFormControls.requireTarget(target,args);
                    // Native-only copy of the exact approved observation target; never accepted from model args.
                    dispatchArgs=copy(args).put("_nativeTargetPreview",copy(target));if(observed.has("actionFrame"))dispatchArgs.put("_nativeActionFrame",copy(observed.getJSONObject("actionFrame")));
                }
                if(command.equals("scroll")){JSONObject observed=observation(tab,args.getString("observationId"));if(observed.has("actionFrame"))dispatchArgs=copy(args).put("_nativeActionFrame",copy(observed.getJSONObject("actionFrame")));}
                if(command.matches("click|type|set_date|focus|press|select_option|set_checked|scroll|navigate|script")){observations.remove(tab.tabId);clearReadObservations(tab.tabId);}
                nativeCalls.put(operationId,tab);
                try{driver.execute(tab,command,copy(dispatchArgs).put("_nativeBrowserOperationId",operationId),gate,result);}
                catch(Exception failed){nativeCalls.remove(operationId,tab);throw failed;}
                result.whenComplete((value,error)->{if(!callDialogs.containsKey(operationId))nativeCalls.remove(operationId,tab);});
                if(command.equals("observe"))result.thenAccept(value->{try{if(!ended&&tab.current()&&tabs.get(tab.tabId)==tab){JSONObject page=driver.metadata(tab);
                    if(value.optBoolean("semanticRead")){if(value.optString("status").equals("observed")&&value.has("observationId")&&value.optLong("navigation",-1)==page.getLong("navigation")&&pageMatchesObservation(page,value)){JSONObject read=copy(value).put("tabId",tab.tabId).put("observedAt",System.currentTimeMillis()).put("elementPreviews",new JSONArray());BrowserSemanticRead.sourceFacts(read.getJSONObject("read"));readObservations.put(read.getString("observationId"),read);while(readObservations.size()>8)readObservations.remove(readObservations.keySet().iterator().next());}return;}
if(value.has("observationId")&&value.optLong("navigation",-1)==page.getLong("navigation"))observations.put(tab.tabId,new JSONObject().put("url",value.getString("url")).put("title",bounded(value.optString("title"),240)).put("text",bounded(value.getString("text"),12000)).put("elementPreviews",elementPreviews(value.getJSONArray("elements"))).put("observationId",value.getString("observationId")).put("navigation",value.getLong("navigation")).put("observedAt",System.currentTimeMillis()).put("tabId",tab.tabId).put("actionFrame",value.optJSONObject("actionFrame")));}}catch(Exception ignored){}});
        }
        return result;
    }
    /** Called only by the native Chrome callback with this original tab and callback handle. */
    public void pageDialogOpenedNative(Tab tab,BrowserPageDialog dialog)throws Exception {
        main();tab.check();if(tabs.get(tab.tabId)!=tab)throw new SecurityException("网页提示不是原任务页");
        JSONObject value=dialog.snapshot();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(!String.valueOf(tab.identity().get(key)).equals(String.valueOf(value.opt(key))))throw new SecurityException("网页提示不属于原任务/文档身份");
        String op=dialog.operationId();if(!op.isEmpty()){if(nativeCalls.get(op)!=tab||!callDialogs.isEmpty()&&!callDialogs.containsKey(op))throw new SecurityException("网页提示没有当前唯一原操作");BrowserPageDialog.Call call=callDialogs.computeIfAbsent(op,BrowserPageDialog.Call::new);call.add(dialog);}
        publishPageDialog(value);
        if(op.isEmpty())takeOverNative(tab.tabId);
    }
    /** A retained owner page stays bound to its original task, even while that run is paused. */
    public void pageDialogOpenedForOwnerNative(BrowserPageDialog dialog)throws Exception {
        main();JSONObject value=dialog.snapshot();requirePageDialogCurrentNative(value);publishPageDialog(value);
    }
    public void requirePageDialogCurrentNative(JSONObject value)throws Exception {
        main();if(ended||!profile.current()||originalControl.isCancelled()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch)||events==null||!events.pageDialogCurrent(binding,originalControl))throw new SecurityException("原网页提示的任务已不再当前");
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId"})if(!String.valueOf(identity().get(key)).equals(String.valueOf(value.opt(key))))throw new SecurityException("网页提示身份已变化");
        String tabId=value.getString("tabId");long generation=value.getLong("tabGeneration");Tab tab=tabs.get(tabId);Long handed=handedOff.get(tabId);if(!(tab!=null&&tab.generation==generation)&&!(handed!=null&&handed==generation))throw new SecurityException("原网页提示所属页已关闭或重新移交");
    }
    public void pageDialogResolvedNative(BrowserPageDialog dialog)throws Exception {
        main();JSONObject value=dialog.snapshot();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId"})if(!String.valueOf(identity().get(key)).equals(String.valueOf(value.opt(key))))throw new SecurityException("网页提示不能归入其他任务");publishPageDialog(value);
    }
    private void publishPageDialog(JSONObject value)throws Exception {
        if(lastPageDialog==null||!lastPageDialog.optString("status").equals("pending")||lastPageDialog.optString("dialogId").equals(value.optString("dialogId"))||value.optString("status").equals("pending"))lastPageDialog=copy(value);if(events!=null)events.pageDialog(binding,originalControl,spaceId,copy(value));
    }
    /** Read-only timing over a real native modal; no UI callback or TaskStore monitor on the waiter. */
    public long pageDialogWaitExtensionMillis(String operationId){BrowserPageDialog.Call call=callDialogs.get(operationId);return call==null?0:call.waitExtensionMillis();}
    public long pageDialogRemainingMillis(String operationId){BrowserPageDialog.Call call=callDialogs.get(operationId);return call==null?BrowserPageDialog.OWNER_LIMIT_MS:call.remainingMillis();}
    /** Original Runner already recorded this tool receipt before its existing afterTool boundary. */
    public void pageDialogOperationSettled(String operationId,JSONObject actualReceipt)throws Exception {
        main();BrowserPageDialog.Call call=callDialogs.remove(operationId);Tab tab=nativeCalls.remove(operationId);BrowserPageDialog dialog=call==null?null:call.latest();if(dialog==null||tab==null)return;
        if(!operationId.equals(actualReceipt.optString("operationId")))throw new SecurityException("网页提示不能匹配其他操作的回执");
        String status=actualReceipt.optString("status");if(!Set.of("observed","selected","clicked","typed","scrolled","navigation_requested","failed","unknown","owner_control","cancelled","unavailable","control_state_observed","no_change").contains(status))status="unspecified";
        Object accepted=actualReceipt.opt("accepted");JSONObject event=dialog.snapshot().put("operationReceiptObserved",true).put("operationReceiptStatus",status)
                .put("operationAccepted",accepted instanceof Boolean?accepted:JSONObject.NULL).put("requiresFreshObservation",true);
        publishPageDialog(event);
        if(!ended&&tabs.get(tab.tabId)==tab&&tab.current()&&!originalControl.isCancelled()){takeOverNative(tab.tabId);driver.dialogOperationSettled(tab,dialog);}
    }
    /** Native projection boundary only: candidates omitted from the actual model response are not known.
     * Keeps the full private destination/identity preview for the visible refs; no ref reassignment,
     * action lifetime extension, DOM mutation or authority is introduced. */
    public JSONObject presentedObservation(JSONObject delivered)throws Exception {
        main();checkOriginal(originalControl);
        if(delivered.optBoolean("semanticRead")){Tab tab=required(delivered.getString("tabId"));return driver.presentedObservation(tab,copy(delivered));}
        Tab tab=required(delivered.getString("tabId"));JSONObject captured=observation(tab,delivered.getString("observationId"));
        if(delivered.getLong("navigation")!=captured.getLong("navigation"))throw new StalePage("待呈现观察已不属于原文档");
        JSONArray nativeRows=captured.getJSONArray("elementPreviews"),shown=delivered.getJSONArray("elements"),kept=new JSONArray();Set<String> refs=new java.util.HashSet<>();
        for(int i=0;i<shown.length();i++){JSONObject displayed=shown.getJSONObject(i);String ref=displayed.getString("ref");if(!refs.add(ref))throw new SecurityException("呈现控件引用重复");JSONObject found=null;for(int j=0;j<nativeRows.length();j++)if(ref.equals(nativeRows.getJSONObject(j).getString("ref"))){found=nativeRows.getJSONObject(j);break;}if(found==null)throw new StalePage("呈现引用并非本次原生登记候选");JSONObject actual=copy(found);if(actual.has("control")){if(displayed.has("control"))actual.put("control",BrowserFormControls.presented(actual.getJSONObject("control"),displayed.getJSONObject("control")));else actual.remove("control");}else if(displayed.has("control"))throw new SecurityException("不能向呈现控件补造表单权限");kept.put(actual);}
        captured.put("elementPreviews",kept).put("presentedElementCount",kept.length());return copy(delivered);
    }
    /** Only called with this port's original Runner afterTool safe-JSON projection, not raw DOM results. */
    public void deliveredAdvancedSource(JSONObject args,JSONObject delivered)throws Exception {
        main();checkOriginal(originalControl);if(args.has("expression"))return; // Computed/literal results remain operation evidence, never relabelled as source quotations.
        if(!delivered.optBoolean("advancedRead")||!delivered.optBoolean("ok")||!delivered.optString("status").equals("observed"))return;
        Tab tab=required(args.getString("tabId"));JSONObject expected=tab.identity();for(java.util.Iterator<String> keys=expected.keys();keys.hasNext();){String key=keys.next();if(!String.valueOf(expected.get(key)).equals(String.valueOf(delivered.opt(key))))throw new StalePage("高级读取交付不属于原任务");}
        JSONObject page=driver.metadata(tab);if(page.getLong("navigation")!=delivered.getLong("navigation")||!pageMatchesObservation(page,delivered))throw new StalePage("高级读取交付的页面已变化");driver.requireObservedFrame(tab,delivered);
        String text=delivered.has("text")?delivered.getString("text"):String.valueOf(delivered.opt("value"));JSONObject saved=copy(delivered).put("text",unicodeClip(text,12000)).put("readOnlyEvidence",true).put("elementPreviews",new JSONArray()).put("observedAt",System.currentTimeMillis());readObservations.put(saved.getString("observationId"),saved);while(readObservations.size()>8)readObservations.remove(readObservations.keySet().iterator().next());
    }
    public void deliveredSourceObservation(JSONObject args,JSONObject delivered)throws Exception {
        main();deliveredStructuredObservations.remove(delivered.optString("observationId"));checkOriginal(originalControl);
        if(!delivered.optBoolean("semanticRead")||!delivered.optBoolean("ok")||!delivered.optString("status").equals("observed"))return;
        Tab tab=required(args.getString("tabId"));JSONObject captured=observation(tab,delivered.getString("observationId"));
        JSONObject expected=tab.identity();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(!String.valueOf(expected.get(key)).equals(String.valueOf(delivered.opt(key))))throw new SecurityException("来源交付不属于原任务或浏览会话");
        for(String key:new String[]{"observationId","navigation","documentId"})if(!String.valueOf(captured.get(key)).equals(String.valueOf(delivered.opt(key))))throw new StalePage("来源交付不是当前原文档观察");
        JSONObject page=driver.metadata(tab);if(page.getLong("navigation")!=captured.getLong("navigation")||!pageMatchesObservation(page,captured))throw new StalePage("来源交付前原网页已跳转");
        delivered=driver.deliveredObservation(tab,copy(delivered));
        JSONObject nativeFacts=BrowserSemanticRead.sourceFacts(captured.getJSONObject("read")),deliveredFacts=BrowserSemanticRead.sourceFacts(delivered.getJSONObject("read"));nativeFacts.remove("delivery");deliveredFacts.remove("delivery");
        if(!dev.pocketengine.store.TaskStore.capabilityFingerprint(nativeFacts).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(deliveredFacts)))throw new SecurityException("来源交付范围与原观察不同");
        // Full safe data or a verified reconstruction of the actually delivered base+patch. Never raw omitted rows.
        captured.put("read",copy(delivered.getJSONObject("read"))).put("text",delivered.getString("text"));if(delivered.has("readRecipe")){BrowserReadRecipe.validateEvidence(delivered.getJSONObject("readRecipe"));if(args.optJSONObject("recipe")==null||!args.getJSONObject("recipe").getString("id").equals(delivered.getJSONObject("readRecipe").getString("id"))||args.getJSONObject("recipe").getInt("revision")!=delivered.getJSONObject("readRecipe").getInt("revision")||!args.getJSONObject("recipe").getString("digest").equals(delivered.getJSONObject("readRecipe").getString("digest")))throw new SecurityException("模型交付配方不是原调用版本");captured.put("readRecipe",copy(delivered.getJSONObject("readRecipe")));}
        String mode=delivered.getJSONObject("read").getString("mode");
        JSONObject entry=new JSONObject().put("tabId",tab.tabId).put("text",unicodeClip(delivered.getString("text"),6000));
        try{if(Set.of("table","list").contains(mode))entry.put("projection",BrowserStructuredSource.fromDelivered(delivered));}
        catch(IllegalStateException oversizedRow){/* A whole row cannot fit. Explicit delivered-text excerpts remain available; no raw row is substituted. */}
        deliveredStructuredObservations.put(delivered.getString("observationId"),entry);
        while(deliveredStructuredObservations.size()>8)deliveredStructuredObservations.remove(deliveredStructuredObservations.keySet().iterator().next());
    }
    public java.util.List<BrowserReadDelta.FullResync> readContextRepairs(JSONArray messages)throws Exception {
        main();java.util.List<BrowserReadDelta.FullResync> out=new java.util.ArrayList<>();if(ended)return out;checkOriginal(originalControl);
        for(Tab tab:tabs.values())out.addAll(driver.readContextRepairs(tab,messages));if(out.size()>8)throw new SecurityException("browser_context_repair_limit");return java.util.List.copyOf(out);
    }
    public void requireReadRepairRecipe(JSONObject original)throws Exception {checkOriginal(originalControl);if(original.has("readRecipe")){BrowserReadRecipeCatalog catalog=events==null?null:events.readRecipes();if(catalog==null)throw new SecurityException("browser_context_recipe_not_available");catalog.requireEvidenceCurrent(profile.profileId,original.getJSONObject("readRecipe"));}}
    public void requireReadContextRepair(JSONObject original)throws Exception {
        main();checkOriginal(originalControl);Tab tab=required(original.getString("tabId"));JSONObject expected=tab.identity();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(!String.valueOf(expected.get(key)).equals(String.valueOf(original.get(key))))throw new SecurityException("browser_context_repair_identity_changed");
        JSONObject captured=observation(tab,original.getString("observationId")),page=driver.metadata(tab);
        if(page.getLong("navigation")!=original.getLong("navigation")||!pageMatchesObservation(page,original)||!captured.getString("documentId").equals(original.getString("documentId"))||!captured.getJSONObject("read").getString("projectionSha256").equals(original.getJSONObject("read").getString("projectionSha256")))throw new StalePage("browser_context_repair_document_changed");driver.requireObservedFrame(tab,original);
    }
    public void beforeModelRequest(String id,JSONArray messages)throws Exception {main();checkOriginalRun(originalControl);for(Tab tab:tabs.values())driver.beforeModelRequest(tab,id,messages);}
    public void afterModelResponse(String id)throws Exception {main();checkOriginalRun(originalControl);for(Tab tab:tabs.values())driver.afterModelResponse(tab,id);}
    public void modelRequestAbandoned(String id)throws Exception {main();for(Tab tab:tabs.values())driver.modelRequestAbandoned(tab,id);}
    private static boolean pageMatchesObservation(JSONObject page,JSONObject observed)throws Exception {
        if((observed.optBoolean("advancedRead")||observed.optBoolean("semanticRead")&&observed.optJSONObject("frameCoverage")!=null&&observed.getJSONObject("frameCoverage").optInt("schemaVersion")==2)&&observed.optJSONObject("frameCoverage")!=null)return dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject().put("url",page.optString("url"))).equals(observed.getJSONObject("frameCoverage").getString("parentPageIdentityDigest"));
        return page.optString("url").equals(observed.optString("url"));
    }
    private static boolean retentionArgs(JSONObject args){if(!args.has("tabId")||!args.has("observationId"))return false;for(java.util.Iterator<String> it=args.keys();it.hasNext();)if(!Set.of("tabId","observationId","excerpt").contains(it.next()))return false;return true;}
    public JSONObject sourceFor(JSONObject args)throws Exception {
        main();Tab tab=required(args.getString("tabId"));JSONObject observation=observation(tab,args.getString("observationId"));
        JSONObject page=driver.metadata(tab);if(page.getLong("navigation")!=observation.getLong("navigation")||!pageMatchesObservation(page,observation))throw new SecurityException("来源观察已跳转");
        if(observation.optBoolean("semanticRead")||observation.optBoolean("advancedRead"))driver.requireObservedFrame(tab,observation);
        JSONObject structured=null;String text=observation.optString("text");
        if(observation.optBoolean("semanticRead")){JSONObject delivered=deliveredStructuredObservations.get(observation.getString("observationId"));if(delivered==null)throw new StalePage("本次模型实际收到的语义内容尚未确认，请重新读取后保留；未改存未交付原文");text=delivered.getString("text");if(Set.of("table","list").contains(observation.getJSONObject("read").getString("mode"))&&!args.has("excerpt")){if(!delivered.has("projection"))throw new StalePage("本次整行超出结构化保存预算，请缩小读取范围或明确保留已读文字摘录");structured=copy(delivered.getJSONObject("projection"));}}
        String excerpt=structured==null?args.optString("excerpt",text.substring(0,Math.min(3000,text.length()))):BrowserStructuredSource.excerpt(structured);
        if(excerpt.length()>6000||structured==null&&!text.contains(excerpt))throw new IllegalArgumentException("来源摘录必须出自该页实际交付文字，最多6000字符");
        URI raw=new URI(observation.getString("url"));if(raw.getHost()==null||raw.getUserInfo()!=null||!java.util.Set.of("http","https").contains(raw.getScheme()))throw new SecurityException("来源地址无效");
        String safeUrl=new URI(raw.getScheme(),null,raw.getHost(),raw.getPort(),raw.getPath(),null,null).toASCIIString();if(safeUrl.length()>2048)throw new IllegalArgumentException("来源地址过长，请使用可核对的短链接");
        String privateUrl=raw.toASCIIString();if(privateUrl.length()>8192)throw new IllegalArgumentException("来源地址过长，尚未保存；请主人核对原页面");
        JSONObject source=tab.identity().put("url",safeUrl).put("urlQueryOmitted",raw.getRawQuery()!=null||raw.getRawFragment()!=null).put("privateUrl",privateUrl).put("urlScope","owner_private_profile")
                .put("title",bounded(observation.optString("title"),240)).put("excerpt",excerpt).put("observedAt",observation.getLong("observedAt"))
                .put("observationId",observation.getString("observationId")).put("navigation",observation.getLong("navigation")).put("frameId",observation.optBoolean("semanticRead")||observation.optBoolean("advancedRead")?observation.getString("frameId"):"main").put("acquisitionMethod","rendered_dom").put("dataScope","owner_private_profile").put("kind","browser_observation").put("untrustedPageContent",true).put("verified",false);
        if(observation.optBoolean("advancedRead")){source.put("kind","browser_live_observation").put("acquisitionMethod","rendered_live_dom").put("documentId",observation.getString("documentId")).put("frameCoverage",copy(observation.getJSONObject("frameCoverage"))).put("liveEvidence",new JSONObject().put("coverage",copy(observation.getJSONObject("coverage"))).put("stability",copy(observation.getJSONObject("stability"))).put("scope","main".equals(observation.getString("frameId"))?"main_document_rendered_light_and_open_shadow_dom":"selected_frame_rendered_light_and_open_shadow_dom").put("bodyExcerptOnly",true).put("businessSuccess",false));}
        if(observation.has("readRecipe"))source.put("readRecipe",copy(observation.getJSONObject("readRecipe")));
        if(observation.optBoolean("semanticRead")){source.put("kind",BrowserSemanticRead.KIND).put("acquisitionMethod","rendered_semantic_dom").put("semantic",BrowserSemanticRead.sourceFacts(observation.getJSONObject("read"))).put("documentId",observation.getString("documentId")).put("frameCoverage",copy(observation.getJSONObject("frameCoverage")));}
        if(structured!=null)source.put("structuredProjection",structured);
        source.put("sourceId",UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8)).toString());return source;
    }
    public record SourceDraft(JSONObject source,DeliveredSource delivered) { }
    public SourceDraft sourceDraft(JSONObject args)throws Exception {main();JSONObject source=sourceFor(args);return new SourceDraft(source,BrowserSemanticRead.KIND.equals(source.optString("kind"))?new DeliveredSource(source):null);}
    public JSONObject retainPublicSource(PublicHttpReader.Result read,String operationId,Gate live)throws Exception {return retainPublicSource(read,operationId,live,id->{});}
    JSONObject retainPublicSource(PublicHttpReader.Result read,String operationId,Gate live,java.util.function.Consumer<String> intendedReference)throws Exception {
        if(!publicHttpAvailable()||!read.readable())throw new UnsupportedOperationException("没有可保留的公共HTTP正文");
        read.budget.check();JSONObject fetched=read.value,facts=fetched.getJSONObject("http");
        java.net.URI raw=new java.net.URI(facts.getString("finalUrl"));
        String safe=new java.net.URI(raw.getScheme(),null,raw.getHost(),raw.getPort(),raw.getPath(),null,null).toASCIIString();
        JSONObject source=identity().put("tabId",JSONObject.NULL).put("tabGeneration",JSONObject.NULL).put("openedBy","task_public_http")
                .put("url",safe).put("urlQueryOmitted",raw.getRawQuery()!=null||raw.getRawFragment()!=null).put("privateUrl",raw.toASCIIString()).put("urlScope","owner_private_task")
                .put("title",bounded(fetched.optString("title"),240)).put("excerpt",fetched.getString("text")).put("observedAt",facts.getLong("retrievedAt"))
                .put("observationId",fetched.getString("observationId")).put("navigation",JSONObject.NULL).put("frameId","not_rendered")
                .put("acquisitionMethod","public_http").put("dataScope","public_anonymous_response").put("kind","public_http_observation")
                .put("http",copy(facts)).put("untrustedPageContent",true).put("verified",false);
        source.put("sourceId",UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8)).toString());
        intendedReference.accept(source.getString("sourceId")); // stable reconciliation identity only, never a successful write or page body
        return retainSource(source,operationId,live,read.budget,null);
    }
    public JSONObject retainSource(JSONObject source,String operationId,Gate live)throws Exception {return retainSource(source,operationId,live,null,null);}
    public JSONObject retainSource(SourceDraft draft,String operationId,Gate live)throws Exception {return retainSource(draft.source(),operationId,live,null,draft.delivered());}
    private JSONObject retainSource(JSONObject source,String operationId,Gate live,PublicHttpReader.Budget budget,DeliveredSource delivered)throws Exception {
        if(sources==null)throw new UnsupportedOperationException("原任务来源保存端口尚未接入");String id=source.getString("sourceId");boolean tried;
        synchronized(sourceAttempts){if(sourceAttempts.size()>=100&&!sourceAttempts.containsKey(id))throw new IllegalStateException("本浏览空间来源已达上限");tried=sourceAttempts.containsKey(id);sourceAttempts.putIfAbsent(id,copy(source));}
        JSONObject known=sources.lookup(binding,id);if(isRetained(known,id))return sourceReceipt(known,id);
        if(tried)return unknownSource(id,operationId);
        live.check();live.entered();try{JSONObject saved=budget==null?sources.retainDelivered(binding,originalControl,operationId,id,copy(source),delivered):sources.retainPublic(binding,originalControl,operationId,id,copy(source),budget);if(!isRetained(saved,id))return unknownSource(id,operationId);return sourceReceipt(saved,id);}
        catch(dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable revoked){synchronized(sourceAttempts){sourceAttempts.remove(id);}throw revoked;}
        catch(Exception lost){try{JSONObject looked=sources.lookup(binding,id);if(isRetained(looked,id))return sourceReceipt(looked,id);}catch(Exception unavailable){}return unknownSource(id,operationId);}
    }
    public JSONObject lookupSourceForOwner(String sourceId)throws Exception {
        if(sources==null)throw new UnsupportedOperationException("来源保存端口尚未接入");synchronized(sourceAttempts){if(!sourceAttempts.containsKey(sourceId))throw new SecurityException("来源不属于此原任务浏览空间");}
        JSONObject found=sources.lookup(binding,sourceId);return isRetained(found,sourceId)?sourceReceipt(found,sourceId):unknownSource(sourceId,"");
    }
    public void adoptOwnerNative(String tabId,Gate gate)throws Exception {main();checkOriginal(originalControl);if(tabs.size()>=MAX_TASK_TABS)throw new IllegalStateException("任务页已达4个");uuid(tabId);if(tabs.containsKey(tabId))throw new IllegalStateException("此页已属于任务");Tab tab=new Tab(tabId);driver.adoptOwner(tab,gate);tabs.put(tabId,tab);revision++;try{JSONObject page=driver.metadata(tab);recordPage(tab,page.optString("url"),page.optString("title"),"ready","owner_adopted");publishManifest();}catch(Exception unavailable){manifestSaved=false;}}
    public void siteRequirementNative(JSONObject notice)throws Exception {
        main();if(ended||events==null||!profile.current()||originalControl.isCancelled()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch))return;
        JSONObject expected=identity();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId"})if(!String.valueOf(expected.get(key)).equals(String.valueOf(notice.opt(key))))throw new SecurityException("站点权限反馈不属于原任务");
        String tab=notice.getString("tabId");if(!tabs.containsKey(tab)&&!handedOff.containsKey(tab))return;events.siteRequirement(binding,originalControl,spaceId,copy(notice));
    }
    public void takeOverNative(String tabId)throws Exception {main();Tab tab=tabs.get(tabId);if(tab==null||ended||!profile.current())throw new SecurityException("原任务页已不再可接管");suspended=true;for(Tab active:tabs.values())active.stopTransfers();tab.invalidate();driver.releaseToOwner(tab);tabs.remove(tabId);handedOff.put(tabId,tab.generation);handoffLogical.put(tabId,tab.logicalPageId);recordCurrentPage(tab,"owner_control");publishManifest();observations.clear();readObservations.clear();deliveredStructuredObservations.clear();revision++;if(events!=null)events.takenOver(binding,originalControl,spaceId,tabId);}
    /** Native return confirmation binds the actual post-login document, never an old observation. */
    public JSONObject returnPinNative(String tabId)throws Exception {
        main();if(ended||!suspended||!profile.current()||!originalControl.isPaused()||originalControl.isCancelled()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("原浏览空间或暂停执行已失效");
        JSONObject pin=identity().put("spaceRevision",revision).put("pauseRevision",originalControl.pauseRevision()).put("tabId",tabId);
        if(!tabId.isEmpty()){uuid(tabId);if(!handedOff.containsKey(tabId))throw new SecurityException("只能交还本空间明确接管的原页面");JSONObject page=driver.ownerMetadata(tabId);pin.put("previousTabGeneration",handedOff.get(tabId)).put("navigation",page.getLong("navigation")).put("pageIdentityDigest",digest(page.getString("url")));}
        return pin;
    }
    public JSONObject returnOwnerNative(JSONObject expected,Gate nativeGate)throws Exception {
        main();String tabId=expected.getString("tabId");if(!expected.toString().equals(returnPinNative(tabId).toString()))throw new SecurityException("交还确认后原空间或登录页面已变化，请刷新");nativeGate.check();
        if(!tabId.isEmpty()){if(tabs.size()>=MAX_TASK_TABS)throw new IllegalStateException("任务页已达4个，请先结束或关闭其他任务页");Tab tab=new Tab(tabId,handoffLogical.getOrDefault(tabId,UUID.randomUUID().toString()));driver.returnOwner(tab,nativeGate);tabs.put(tabId,tab);handedOff.remove(tabId);handoffLogical.remove(tabId);recordCurrentPage(tab,"ready");}
        observations.clear();readObservations.clear();deliveredStructuredObservations.clear();suspended=false;handoffReason="";revision++;publishManifest();return identity().put("tabId",tabId).put("status","returned").put("freshObservationRequired",true).put("verified",false);
    }
    /** Called before the resumed Runner can request another model decision. */
    public JSONObject returnObservationPin(JSONObject confirmed)throws Exception {
        main();checkOriginal(originalControl);JSONObject identity=identity();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId"})if(!String.valueOf(identity.get(key)).equals(String.valueOf(confirmed.opt(key))))throw new SecurityException("交还观察不属于原任务或登录会话");
        if(originalControl.pauseRevision()!=confirmed.getLong("pauseRevision")||revision!=confirmed.getLong("spaceRevision")+1)throw new StalePage("交还后页面或暂停状态已变化");
        String tabId=confirmed.getString("tabId");if(tabId.isEmpty()){if(tabs.containsKey(lastTaskTab))tabId=lastTaskTab;else for(Tab tab:tabs.values()){tabId=tab.tabId;break;}}
        if(tabId.isEmpty())return identity.put("status","no_task_page").put("freshObservationAvailable",false);
        JSONObject pin=pin(new JSONObject().put("tabId",tabId));if(!confirmed.getString("tabId").isEmpty())for(String key:new String[]{"navigation","pageIdentityDigest"})if(!String.valueOf(pin.get(key)).equals(String.valueOf(confirmed.get(key))))throw new StalePage("交还的完整网页目的地已变化，请重新确认");return pin;
    }
    public JSONObject bindReturnObservation(JSONObject pin,JSONObject raw)throws Exception {
        main();JSONObject args=new JSONObject().put("tabId",pin.getString("tabId"));requirePin(pin,args);Tab tab=required(pin.getString("tabId"));JSONObject expected=tab.identity();for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration"})if(!String.valueOf(expected.get(key)).equals(String.valueOf(raw.opt(key))))throw new SecurityException("交还观察回调不属于原任务的新页面代次");
        if(raw.getLong("navigation")!=pin.getLong("navigation")||!digest(raw.getString("url")).equals(pin.getString("pageIdentityDigest")))throw new StalePage("观察完成前交还页面已跳转");return copy(raw).put("pageIdentityDigest",pin.getString("pageIdentityDigest")).put("pauseRevision",originalControl.pauseRevision()).put("frameId","main").put("observedAt",System.currentTimeMillis()).put("kind","browser_owner_return_observation").put("untrustedPageContent",true).put("verified",false);
    }
    public void returnObservationFailedNative(String tabId)throws Exception {main();if(waitingForOwner())return;handoffReason="交还后尚未读取到当前网页，请检查页面并再次交还。";takeOverNative(tabId);}
    public boolean ownerTransferCurrent(String tabId){return !ended&&suspended&&profile.current()&&originalControl.isPaused()&&!originalControl.isCancelled()&&handedOff.containsKey(tabId);}
    public void ownerBrowsingNative(String ownerTabId){main();if(ended||suspended)return;suspended=true;for(Tab tab:tabs.values())tab.stopTransfers();revision++;if(events!=null)events.ownerBrowsing(binding,originalControl,spaceId,ownerTabId);}
    public JSONObject endNative(String reason)throws Exception {
        main();for(Tab tab:tabs.values())recordCurrentPage(tab,"closed");ended=true;suspended=true;publishManifest();invalidateAllTabs();JSONArray closed=new JSONArray(),failed=new JSONArray(),preserved=new JSONArray();
        for(Tab tab:new java.util.ArrayList<>(tabs.values()))try{if(driver.closeOnFinish(tab))closed.put(tab.tabId);else preserved.put(tab.tabId);tabs.remove(tab.tabId);}catch(Exception cleanup){failed.put(tab.tabId);}
        observations.clear();readObservations.clear();deliveredStructuredObservations.clear();nativeCalls.clear();callDialogs.clear();revision++;originalControl.removeCancelAction(cancelled);JSONObject receipt=identity().put("status",failed.length()==0?"space_closed":"cleanup_required").put("reason",bounded(reason,120)).put("closedTaskTabs",closed).put("unclearedTaskTabs",failed).put("preservedMovedTabs",preserved).put("ownerPagesPreserved",true).put("verified",false);
        driver.ended();if(events!=null)events.ended(binding,originalControl,spaceId,receipt);return receipt;
    }
    public void abortNative(JSONObject args,CompletableFuture<JSONObject> pending){main();suspended=true;Tab tab=tabs.get(args.optString("tabId"));if(tab!=null)driver.abort(tab,pending);}
    public void pageGoneNative(String tabId){main();Tab tab=tabs.remove(tabId);if(tab!=null){recordCurrentPage(tab,"renderer_gone");tab.invalidate();publishManifest();}observations.remove(tabId);clearReadObservations(tabId);suspended=true;revision++;}
    public void navigationChangedNative(String tabId){main();Tab tab=tabs.get(tabId);if(tab!=null)tab.stopTransfers();observations.remove(tabId);clearReadObservations(tabId);}
    private void invalidateAllTabs(){synchronized(tabs){for(Tab tab:tabs.values())tab.invalidate();}}
    private void clearReadObservations(String tabId){readObservations.values().removeIf(value->tabId.equals(value.optString("tabId")));deliveredStructuredObservations.values().removeIf(value->tabId.equals(value.optString("tabId")));}
    private JSONObject recoveryPage(String id,String url)throws Exception {if(recoveryPages==null)throw new StalePage("此原任务没有可重建的逻辑页清单");JSONObject row=BrowserResearchPages.required(recoveryPages,id);if(row.getString("url").isEmpty()||!row.getString("url").equals(url))throw new StalePage("重开地址必须是主人已看到的原逻辑页地址；未恢复原授权或未保存表单");driver.recoveryUrl(row);for(Tab tab:tabs.values())if(tab.logicalPageId.equals(id))throw new StalePage("该逻辑页已经重建，未重复开页");return row;}
    private void recordPage(Tab tab,String url,String title,String state,String openedBy)throws Exception {logicalPages.put(tab.logicalPageId,driver.recoveryRecord(tab.logicalPageId,url,title,state,openedBy));while(logicalPages.size()>BrowserResearchPages.MAX_PAGES){String remove=null;for(Map.Entry<String,JSONObject> entry:logicalPages.entrySet())if(entry.getValue().getString("state").matches("closed|renderer_gone")){remove=entry.getKey();break;}if(remove==null)remove=logicalPages.keySet().iterator().next();logicalPages.remove(remove);}}
    private void recordCurrentPage(Tab tab,String state){try{JSONObject page=driver.metadata(tab);recordPage(tab,page.optString("url"),page.optString("title"),state,"task");}catch(Exception unavailable){JSONObject old=logicalPages.get(tab.logicalPageId);if(old!=null)try{old.put("state",state);}catch(Exception ignored){}}}
    public void pageLoadedNative(String tabId,String url,String title)throws Exception {main();Tab tab=tabs.get(tabId);if(tab==null||ended)return;recordPage(tab,url,title,"ready","task");publishManifest();}
    public void pageStartedNative(String tabId,String url)throws Exception {main();Tab tab=tabs.get(tabId);if(tab==null||ended)return;recordPage(tab,url,"","loading","task");publishManifest();}
    public void effectSettledNative(JSONObject args,JSONObject receipt)throws Exception {main();Tab tab=tabs.get(args.optString("tabId"));if(tab==null||ended)return;if(receipt.optString("status").equals("unknown")){recordCurrentPage(tab,"unknown");publishManifest();}}
    private void publishManifest(){if(events==null)return;try{JSONArray pages=new JSONArray();for(JSONObject row:logicalPages.values())pages.put(copy(row));JSONObject value=new JSONObject().put("version",1).put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch).put("profileId",profile.profileId).put("profileRevision",profile.profileRevision).put("browserSessionId",profile.sessionId).put("browserSpaceId",spaceId).put("revision",1).put("pages",pages).put("phase",ended?"ended":suspended?"owner_control":"active").put("automaticReplayAllowed",false).put("actionRefsRestored",false);String fp=dev.pocketengine.store.TaskStore.capabilityFingerprint(value);if(fp.equals(manifestFingerprint))return;value.put("revision",++manifestRevision);BrowserResearchPages.validate(value);manifestSaved=events.pageManifest(binding,originalControl,copy(value));if(manifestSaved)manifestFingerprint=fp;}catch(Exception unavailable){manifestSaved=false;}}
    private JSONObject observation(Tab tab,String id)throws Exception {JSONObject value=observations.get(tab.tabId);if(value!=null&&id.equals(value.optString("observationId")))return value;value=readObservations.get(id);if(value==null||!tab.tabId.equals(value.optString("tabId")))throw new StalePage("请读取该任务页的当前观察");return value;}
    private Tab required(String id)throws Exception {uuid(id);Tab tab=tabs.get(id);if(tab==null||!tab.current())throw new SecurityException("页面不属于此任务或已由主人接管");return tab;}
    private boolean isRetained(JSONObject result,String id)throws Exception {if(result==null)return false;if(!id.equals(result.optString("sourceId"))||!binding.taskId.equals(result.optString("taskId"))||!binding.runId.equals(result.optString("runId"))||binding.epoch!=result.optInt("epoch"))throw new SecurityException("来源回执不属于原任务/执行代次");return "retained".equals(result.optString("status"));}
    private JSONObject sourceReceipt(JSONObject result,String id)throws Exception {JSONObject receipt=identity().put("sourceId",id).put("sourceRetained",true).put("exactUrlRetainedPrivately",result.optBoolean("exactUrlRetainedPrivately")).put("status","retained").put("ok",true).put("accepted",true).put("verified",false);if(result.has("sourceRef"))receipt.put("sourceRef",bounded(result.optString("sourceRef"),200));if(result.has("retainedAt"))receipt.put("retainedAt",result.optLong("retainedAt"));for(String key:new String[]{"structuredRevision","fieldRefs","fieldRefsOmitted","outputCapture"})if(result.has(key))receipt.put(key,result.get(key));return receipt;}
    private JSONObject unknownSource(String id,String operation)throws Exception {return identity().put("sourceId",id).put("operationId",operation).put("status","unknown").put("code","source_retention_unknown").put("accepted",JSONObject.NULL).put("verified",false).put("message","来源保存结果待核对；仅查询同一sourceId，未重新写入");}
    private void main(){if(!ui.isMain())throw new IllegalStateException("页面归属必须在原生主线程操作");}
    private static String bounded(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    private static String unicodeClip(String value,int max){int end=Math.min(value.length(),max);if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;return value.substring(0,end);}
    private static JSONArray elementPreviews(JSONArray source)throws Exception {JSONArray rows=new JSONArray();for(int i=0;i<source.length()&&i<250;i++){JSONObject item=source.optJSONObject(i);if(item==null||!item.optString("ref").matches("n[0-9]{1,3}"))continue;JSONObject row=new JSONObject().put("ref",item.getString("ref"));for(String key:new String[]{"nodeKey","tag","type","role","text","id","name","href","formAction","formMethod","submitterFormAction"})if(item.opt(key) instanceof String)row.put(key,bounded(item.getString(key),key.matches("href|formAction|submitterFormAction")?2048:240));if(item.has("ownerRequired"))row.put("ownerRequired",item.optBoolean("ownerRequired"));if(item.has("control"))row.put("control",BrowserFormControls.project(item.getJSONObject("control")));rows.put(row);}return rows;}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static String digest(String value)throws Exception {byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    public static void uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("需要有效的浏览页或原operation引用");}

    public final class Tab implements BrowserTransferFence {
        public final String tabId,logicalPageId;public final long generation=System.nanoTime();
        private final AtomicBoolean active=new AtomicBoolean(true);private final CopyOnWriteArrayList<Runnable> closers=new CopyOnWriteArrayList<>();
        private Tab(String id){this(id,UUID.randomUUID().toString());}private Tab(String id,String logical){tabId=id;logicalPageId=logical;}
        public BrowserTaskSpace space(){return BrowserTaskSpace.this;}
        public JSONObject identity()throws Exception{return BrowserTaskSpace.this.identity().put("tabId",tabId).put("tabGeneration",generation).put("logicalPageId",logicalPageId).put("openedBy","task");}
        public boolean current(){return active.get()&&!ended&&!suspended&&profile.current()&&!originalControl.isCancelled()&&!originalControl.isPaused();}
        public void check()throws Exception{checkOriginal(originalControl);if(!active.get())throw new SecurityException("任务页已结束或接管");}
        private void invalidate(){active.set(false);stopTransfers();}
        private void stopTransfers(){for(Runnable close:closers)close.run();closers.clear();}
        public AutoCloseable watch(Runnable closeResource){AtomicBoolean once=new AtomicBoolean();Runnable close=()->{if(once.compareAndSet(false,true))try{closeResource.run();}catch(RuntimeException ignored){}};closers.add(close);AutoCloseable profileWatch=profile.watch(close);originalControl.onCancel(close);if(!current())close.run();return ()->{closers.remove(close);profileWatch.close();originalControl.removeCancelAction(close);};}
    }
}
