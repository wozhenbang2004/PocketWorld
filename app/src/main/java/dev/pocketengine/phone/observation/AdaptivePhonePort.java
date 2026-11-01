package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.LocalImageOcr;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.Base64;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import org.json.JSONArray;

import static dev.pocketengine.phone.observation.AdaptiveObservationPolicy.*;

/** One native-host run's observation adapter; it owns no task journal, grant, or scheduler. */
public final class AdaptivePhonePort implements PhonePort {
    public static final String CLICK_TEXT="phone_click_text";
    private static final long MAX_AGE_MS = 20_000;
    public static final class Scope {
        public final String taskId, runId, sessionId;
        public final int epoch, displayId;
        public Scope(String taskId, String runId, int epoch, String sessionId, int displayId) {
            this.taskId = AdaptiveObservationPolicy.required(taskId);
            this.runId = AdaptiveObservationPolicy.required(runId);
            this.sessionId = AdaptiveObservationPolicy.required(sessionId);
            if (epoch < 1 || displayId < 0) throw new IllegalArgumentException("Invalid run/display scope");
            this.epoch = epoch; this.displayId = displayId;
        }
        private Binding observation(String id, long revision) {
            return new Binding(taskId, runId, epoch, sessionId, displayId, id, revision);
        }
    }

    private final PhonePort source;
    private final Scope scope;
    private final boolean sendScreenshots, visionAvailable;
    private final LongSupplier uptime;
    private final LocalImageOcr ocr;
    private final AdaptiveObservationPolicy policy = new AdaptiveObservationPolicy();
    private RunControl boundControl;
    private JSONObject latest;
    private Binding latestBinding;
    private Need pendingNeed = Need.AUTOMATIC;
    private boolean imageCeiling, reconciliationRequired;
    private String safetyPause;
    private PhoneTreeCollection.State collection;
    private final java.util.concurrent.atomic.AtomicBoolean actionEffectInFlight=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Map<String,OcrTextTarget> textTargets=new java.util.LinkedHashMap<>();

    /** All arguments come from the native host, including verified model image support and its uptime clock. */
    public AdaptivePhonePort(PhonePort source, Scope scope, boolean sendScreenshots,
                             boolean visionAvailable, LongSupplier uptime) {
        this(source, scope, sendScreenshots, visionAvailable, uptime, null);
    }
    /** The same Application-owned local engine as attachments; this run adapter never closes it. */
    public AdaptivePhonePort(PhonePort source, Scope scope, boolean sendScreenshots,
                             boolean visionAvailable, LongSupplier uptime, LocalImageOcr ocr) {
        this.source = Objects.requireNonNull(source); this.scope = Objects.requireNonNull(scope);
        this.sendScreenshots = sendScreenshots; this.visionAvailable = visionAvailable;
        this.imageCeiling = sendScreenshots; this.uptime = Objects.requireNonNull(uptime);
        this.ocr = ocr;
    }
    @Override public boolean isConnected() { return source.isConnected(); }
    public boolean textTargetingAvailable(){return sendScreenshots&&source instanceof NativeOcrTapPort&&ocrAvailable();}
    public boolean matchesHostScope(String task,String run,int epoch){return scope.taskId.equals(task)&&scope.runId.equals(run)&&scope.epoch==epoch;}
    @Override public synchronized void clearReferences() {
        latest = null; latestBinding = null; textTargets.clear();source.clearReferences();
        // Clearing references cannot reconcile a possibly dispatched operation.
    }
    @Override public synchronized JSONObject observe(boolean includeImage, RunControl control) throws Exception {
        String blocked = authority(control);
        if (blocked != null) return failure(blocked, "请核对当前任务和控制权限后继续。");
        imageCeiling = sendScreenshots && includeImage;
        if (reconciliationRequired) return failure("reconciliation_required", "上次操作结果未知，请先由主人核对；不会自动重放。");
        if (safetyPause != null) return failure(safetyPause, "本次执行已因显示或权限问题阻断，请由主人处理后继续原任务。");
        Need need = pendingNeed;
        clearReferences();
        JSONObject nodes = source.observe(false, control);
        if (nodes == null) return failure("empty_observation", "手机没有返回观察。");
        if (!nodes.optBoolean("ok")) return rejectedObservation(nodes);
        // A delegate cannot turn the node-only first pass into image delivery.
        stripImage(nodes);
        JSONObject nodeSources = nodes.optJSONObject("observationSources");
        if (nodeSources != null) nodeSources.put("imageIncluded", false);
        Decision decision = choose(nodes, need, control);
        if (decision.requestsImage()) {
            // A fresh native observation gets its own ID; no coordinates from the first read survive this upgrade.
            JSONObject image = source.observe(true, control);
            if (image == null) return failure("empty_observation", "手机没有返回图片观察。");
            if (!image.optBoolean("ok")) return rejectedObservation(image);
            decision = choose(image, need, control);
            if (decision.requestsImage()) return failure("image_unavailable", "没有取得真实图片，无法继续视觉定位。");
            return deliver(image, decision, control);
        }
        return deliver(nodes, decision, control);
    }

    /** Queries only the named, still-current observation; it cannot silently rebind an old parent ID. */
    public synchronized JSONObject queryTree(JSONObject args,RunControl control)throws Exception {
        PhoneTreeQuery.validate(PhoneTreeQuery.QUERY,args);
        String blocked=authority(control);
        if(blocked!=null)return failure(blocked,"原任务控制已变化，树查询未执行。");
        if(reconciliationRequired)return failure("reconciliation_required","先核对原未知操作，再查询新目标。");
        if(safetyPause!=null)return failure(safetyPause,"显示或权限已失效，请先由主人处理。");
        if(!current(args.getString("observationId"),control))return failure("stale_observation","树查询属于旧观察，请重新观察。");
        return PhoneTreeQuery.query(latest,args);
    }
    public synchronized JSONObject extractTree(JSONObject args,RunControl control)throws Exception {
        PhoneTreeExtract.validate(args);String blocked=authority(control);if(blocked!=null)return failure(blocked,"原控制已变化，未提取。");
        if(reconciliationRequired||safetyPause!=null)return failure(reconciliationRequired?"reconciliation_required":safetyPause,"请先核对原操作或显示权限。");
        if(!current(args.getString("observationId"),control))return failure("stale_observation","提取需要当前真实观察。");return PhoneTreeExtract.extract(latest,args);
    }
    /** The cursor never executes a scroll; every advance consumes one original journal receipt. */
    public JSONObject collectTree(JSONObject args,JSONObject originalScroll,RunControl control)throws Exception {
        PhoneTreeCollection.validate(args);String action=args.getString("action");final PhoneTreeCollection.State selected;
        synchronized(this){
            String blocked=authority(control);if(blocked!=null){if(collection!=null)collection.stop("scope_or_pause_changed");return failure(blocked,"原控制已变化，采集停止。");}
            if(action.equals("start")){
                if(reconciliationRequired||safetyPause!=null)return failure(reconciliationRequired?"reconciliation_required":safetyPause,"先核对原操作后再采集。");
                if(!current(args.getString("observationId"),control))return failure("stale_observation","采集起点观察已过期。");
                collection=PhoneTreeCollection.start(args,latest,collectionScope(control),uptime.getAsLong());return collection.result();
            }
            selected=collection;if(selected==null||!selected.id.equals(args.getString("cursorId")))return failure("cursor_unavailable","游标不属于此原执行；重启或新采集后不能恢复旧游标。");
            JSONObject stopped=selected.scopeCheck(collectionScope(control),uptime.getAsLong());if(stopped!=null)return stopped;
            if(action.equals("finish"))return selected.stop("explicit_stop");if(selected.stopped())return selected.result();
            if(reconciliationRequired)return selected.stop("operation_result_unknown");if(safetyPause!=null)return selected.stop("display_or_permission_unavailable");
            if(action.equals("advance")){
                if(originalScroll==null||!"receipt".equals(originalScroll.optString("state"))||originalScroll.optJSONObject("receipt")==null)return selected.stopAfterScroll("operation_result_unknown",originalScroll);
                JSONObject receipt=originalScroll.getJSONObject("receipt");if(!Boolean.TRUE.equals(receipt.opt("accepted")))return selected.stopAfterScroll(receipt.has("accepted")&&receipt.isNull("accepted")?"operation_result_unknown":"scroll_not_accepted",originalScroll);
                selected.requireScroll(originalScroll);String after=receipt.optString("afterObservationId");
                if(after.isEmpty()||!current(after,control))return selected.stopAfterScroll("scroll_fresh_observation_expired_or_unavailable",originalScroll);
                return selected.advance(latest,originalScroll);
            }
        }
        // Read refresh owns no adapter monitor while it schedules the physical read or waits.
        JSONObject fresh=readOnlyTree(control);
        synchronized(this){if(collection!=selected)return failure("cursor_replaced","本采集已被新的原生采集替换。");String blocked=authority(control);if(blocked!=null)return selected.stop("scope_or_pause_changed");
            JSONObject stopped=selected.scopeCheck(collectionScope(control),uptime.getAsLong());if(stopped!=null)return stopped;
            if(!fresh.optBoolean("ok"))return selected.stop("observation_unavailable");return selected.refresh(fresh);}
    }
    private String collectionScope(RunControl control)throws Exception {return dev.pocketengine.store.TaskStore.capabilityFingerprint(scopeJson().put("pauseRevision",control.pauseRevision()));}
    /** One original prepared native scroll followed only by bounded metadata/tree reads. */
    public JSONObject scrollNode(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        NativeTreeScrollPort.validate(args);final JSONObject target;final String before;final long revision;
        synchronized(this){String blocked=authority(control);if(blocked!=null)return failure(blocked,"原控制已变化，未滚动。");
            if(!(source instanceof NativeTreeScrollPort))return failure("semantic_scroll_unavailable","当前原生端口不支持节点滚动，不改用截图坐标。");
            if(reconciliationRequired||safetyPause!=null)return failure(reconciliationRequired?"reconciliation_required":safetyPause,"先核对原操作或显示状态。");
            if(!current(args.getString("observationId"),control))return failure("stale_observation","滚动需要当前真实观察。");
            JSONObject node=PhoneTreeExtract.index(latest).get(args.getString("nodeId"));if(node==null||node.optBoolean("password")||!node.optBoolean("scrollable")||!node.optBoolean("enabled",true)||!node.optBoolean("visible",true))return failure("node_not_scrollable","需要当前窗口内可见且可滚动的精确节点。");
            target=PhoneTreeExtract.ref(node);before=latest.optString("treeFingerprint");revision=control.pauseRevision();
        }
        JSONObject result;
        try{result=((NativeTreeScrollPort)source).scrollNode(args,control,admission);}
        catch(InterruptedException stopped){synchronized(this){clearReferences();}throw stopped;}
        catch(Exception unknown){result=new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","action_unknown");}
        synchronized(this){clearReferences();if(result==null)result=new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","action_unknown");
            if(result.has("accepted")&&result.isNull("accepted")){reconciliationRequired=true;return ObservationReport.annotateFailure(result).put("targetRef",target).put("automaticReplayAllowed",false);}}
        result.put("targetRef",target).put("beforeTreeFingerprint",before).put("verified",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false).put("mechanism","accessibility_action_scroll_"+args.getString("direction"));
        if(!Boolean.TRUE.equals(result.opt("accepted")))return result;
        JSONObject fresh=null;long until=System.nanoTime()+1_250_000_000L;
        for(int i=0;i<6;i++){
            control.check();if(control.isPaused()||control.pauseRevision()!=revision)return result.put("freshObservationAvailable",false).put("effect","unavailable_after_pause");
            fresh=readOnlyTree(control);if(!fresh.optBoolean("ok")||!before.equals(fresh.optString("treeFingerprint"))||System.nanoTime()>=until)break;
            long wake=Math.min(until,System.nanoTime()+250_000_000L);while(System.nanoTime()<wake){control.check();if(control.isPaused()||control.pauseRevision()!=revision)return result.put("freshObservationAvailable",false).put("effect","unavailable_after_pause");Thread.sleep(Math.max(1,Math.min(50,(wake-System.nanoTime())/1_000_000L)));}
        }
        if(fresh==null||!fresh.optBoolean("ok"))return result.put("freshObservationAvailable",false).put("effect","not_observed");
        JSONObject actual=null;for(JSONObject n:PhoneTreeExtract.index(fresh).values())if(n.getInt("windowId")==target.getInt("windowId")&&n.getJSONArray("path").toString().equals(target.getJSONArray("path").toString())){actual=n;break;}
        result.put("afterObservationId",fresh.getString("observationId")).put("afterTreeFingerprint",fresh.optString("treeFingerprint")).put("freshObservationAvailable",true).put("effect",before.equals(fresh.optString("treeFingerprint"))?"no_change_observed":"tree_changed_not_business_proof");
        if(actual!=null)result.put("observation",PhoneTreeExtract.actionObservation(fresh,actual.getString("id")));return result;
    }

    /** The waiting loop owns no monitor, task state or execution journal. */
    public JSONObject conditionWait(JSONObject args,RunControl control)throws Exception {
        PhoneTreeQuery.validate(PhoneTreeQuery.WAIT,args);final JSONObject anchor;
        synchronized(this){
            String blocked=authority(control);if(blocked!=null)return failure(blocked,"原任务控制已变化，条件等待未执行。");
            if(args.getJSONObject("selector").has("parentId")&&!current(args.getString("observationId"),control))
                return failure("stale_observation","父范围属于旧观察，请重新观察。");
            anchor=latest==null?null:new JSONObject(latest.toString());
        }
        try{
            JSONObject result=PhoneTreeQuery.await(args,anchor,()->readOnlyTree(control),control);
            synchronized(this){
                String blocked=authority(control);
                if(blocked!=null){clearReferences();return failure(blocked,"任务控制已变化，等待结果已丢弃。");}
                if(!result.optBoolean("ok")||!result.has("observation"))clearReferences();
            }
            return result;
        }catch(InterruptedException stopped){synchronized(this){clearReferences();}throw stopped;}
    }
    private JSONObject readOnlyTree(RunControl control)throws Exception {
        final long revision;String blocked;
        synchronized(this){blocked=authority(control);if(blocked!=null)return failure(blocked,"任务已暂停，未读取旧观察。");
            if(reconciliationRequired)return failure("reconciliation_required","原操作结果未知，未读取新目标。");
            if(safetyPause!=null)return failure(safetyPause,"显示或权限已失效，请由主人处理。");
            clearReferences();revision=control.pauseRevision();}
        JSONObject observed=source.observe(false,control);
        synchronized(this){
        if(observed==null)return failure("empty_observation","没有取得当前树。");
        stripImage(observed);
        if(!observed.optBoolean("ok"))return rejectedObservation(observed);
        blocked=authority(control);
        long at=observed.optLong("observedAtUptimeMs",-1),now=uptime.getAsLong();
        if(blocked!=null||revision!=control.pauseRevision()||observed.optLong("pauseRevision",-1)!=revision
                ||observed.optInt("displayId",-1)!=scope.displayId||!scope.sessionId.equals(observed.optString("sessionId"))
                ||observed.optString("observationId").isEmpty()||at<0||now<at||now-at>MAX_AGE_MS){
            clearReferences();return failure(blocked==null?"stale_observation":blocked,"显示、执行代次或观察已变化，迟回结果已丢弃。");
        }
        JSONObject sources=observed.optJSONObject("observationSources");
        if(sources==null){clearReferences();return failure("observation_metadata_missing","树来源不可核对。");}
        sources.put("imageIncluded",false);
        latestBinding=scope.observation(observed.getString("observationId"),revision);
        observed.put("observationBinding",bindingJson(latestBinding)).put("observationDecision",new JSONObject()
                .put("request","nodes").put("reason","read_only_condition").put("adequacy","not_asserted"));
        latest=new JSONObject(observed.toString());return observed;
        }
    }

    /** Native short-plan preparation only. No physical action or execution loop lives here. */
    public synchronized PhoneTreeSequence.State prepareSequence(JSONObject args,RunControl control)throws Exception {
        PhoneTreeSequence.validate(args);String blocked=authority(control);
        if(blocked!=null||reconciliationRequired||safetyPause!=null||!source.isConnected()||!current(args.getString("observationId"),control))
            throw new SecurityException("短序列需要原应用当前完整观察，不能复用暂停、未知或旧显示");
        JSONObject actual=new JSONObject(latest.toString());stripImage(actual);actual.remove("ocr");
        return PhoneTreeSequence.prepare(args,actual,uptime.getAsLong());
    }
    /** A guard called around the original prepared step; it must not take a read-side Store fence. */
    public synchronized void requireSequenceCurrent(PhoneTreeSequence.State state,String observationId,RunControl control)throws Exception {
        String blocked=authority(control);
        if(blocked!=null||reconciliationRequired||safetyPause!=null||!source.isConnected()||latest==null)
            throw new SecurityException("原手机短序列已停止，余下步骤未派发");
        state.requireScope(latest,uptime.getAsLong());
        if(observationId!=null&&!current(observationId,control))throw new SecurityException("下一步的原生新观察已变化");
        if(!state.started()&&!current(state.initialObservation,control))throw new SecurityException("短序列准备起点已变化");
    }
    /** Every child is resolved from one metadata-only fresh physical tree, outside this port's monitor. */
    public JSONObject resolveSequenceStep(PhoneTreeSequence.State state,int index,RunControl control)throws Exception {
        synchronized(this){requireSequenceCurrent(state,null,control);if(!state.started())state.start(latest.getString("observationId"));}
        JSONObject fresh=readOnlyTree(control);
        if(fresh==null||!fresh.optBoolean("ok"))throw new SecurityException("短序列没有取得下一步真实树");
        synchronized(this){requireSequenceCurrent(state,fresh.getString("observationId"),control);}
        JSONObject out=new JSONObject(fresh.toString());stripImage(out);out.remove("ocr");return out;
    }
    public synchronized JSONObject resolveSequenceIdentity(PhoneTreeSequence.State state,RunControl control)throws Exception {
        requireSequenceCurrent(state,state.initialObservation,control);return scopeJson();
    }
    public long sequenceUptime(){return uptime.getAsLong();}

    /** Host-mediated help signal only; it never authorizes or repeats the failed business action. */
    public synchronized JSONObject requestUpgrade(String observationId, Need need, RunControl control) throws Exception {
        if (need == null || need == Need.AUTOMATIC) throw new IllegalArgumentException("An explicit observation need is required");
        String blocked = authority(control);
        if (blocked != null) return failure(blocked, "任务控制已变化，请重新观察。");
        if (need == Need.EFFECT_UNKNOWN) {
            reconciliationRequired = true; clearReferences();
            return failure("reconciliation_required", "操作效果未知，请由主人核对；不会重放。");
        }
        if (reconciliationRequired) return failure("reconciliation_required", "请先核对上次未知结果。");
        if (safetyPause != null) return failure(safetyPause, "请由主人恢复显示和权限后继续原任务。");
        if (!current(observationId, control)) return failure("stale_observation", "升级请求属于旧页面，请重新观察。");
        Decision decision = choose(latest, need, control);
        if (decision.requestsImage()) {
            pendingNeed = need;
            return observe(imageCeiling, control);
        }
        return deliver(latest, decision, control);
    }

    @Override public JSONObject execute(String tool, JSONObject args, RunControl control) throws Exception {
        return executeWithEffect(tool,args,control,null);
    }
    /** Native host capability path; the admission callback retains the original prepared operation. */
    public JSONObject tapTextTarget(JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        return executeWithEffect(CLICK_TEXT,args,control,Objects.requireNonNull(admission));
    }
    private JSONObject executeWithEffect(String tool,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        boolean effect=java.util.Set.of("phone_click_node","phone_type","phone_tap","phone_swipe","phone_back","phone_home","phone_launch_app",CLICK_TEXT).contains(tool);
        if(!effect){synchronized(this){return executeBound(tool,args,control,admission);}}
        if(!actionEffectInFlight.compareAndSet(false,true))return failure("original_action_in_flight","原动作回执或后置只读核对尚未结束，未派发新动作。");
        try{
            final JSONObject before;final long revision;final Integer targetWindow;JSONObject result;
            synchronized(this){before=effectSnapshot(latest);revision=control.pauseRevision();Integer selected=null;if(before!=null&&args!=null&&!args.optString("nodeId").isEmpty())try{JSONObject n=PhoneTreeExtract.index(before).get(args.getString("nodeId"));if(n!=null)selected=n.getInt("windowId");}catch(Exception absent){}targetWindow=selected;result=executeBound(tool,args,control,admission);}
            // The original side effect already has its receipt. Only read afterward; no port monitor
            // is held across event waits, and no error here converts accepted input into a replay.
            if(Boolean.TRUE.equals(result.opt("accepted"))&&!ObservationSafety.unknownEffect(result.optString("error",result.optString("code")))&&!result.optString("status").equals("unknown")&&before!=null){
                dev.pocketengine.phone.PhoneUiSignals feed=null;if(source instanceof NativePhoneSignals)try{feed=((NativePhoneSignals)source).uiSignals(control);}catch(Exception unavailable){}
                try{PhoneActionEffects.Result after=PhoneActionEffects.await(before,targetWindow,()->readOnlyTree(control),control,revision,feed,PhoneActionEffects.REAL);result.put("actionEffects",after.evidence);
                    JSONObject observed=after.evidence.optJSONObject("observed");if(observed!=null)result.put("actionFeedback",observed.optString("status").equals("change_observed")?"已观察到界面变化；不代表业务完成。":"当前可读树未显示确定变化；这不证明动作没发生，勿原样重发。");
                }catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();result.put("actionEffects",new JSONObject().put("stability","unavailable_after_dispatch").put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false));}
            }else result.put("actionEffects",new JSONObject().put("stability",result.has("accepted")&&result.isNull("accepted")?"unknown_dispatch_not_replayed":Boolean.TRUE.equals(result.opt("accepted"))?"before_tree_unavailable":"not_accepted").put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false));
            JSONObject readback=result.optJSONObject("nativeTextReadback");if(readback!=null&&readback.optString("status").equals("exact_match"))result.put("actionFeedback","原生同一输入节点已回读，文字与所输内容精确一致；仍不代表业务完成。");
            return result.put("verifiedBusinessSuccess",false);
        }finally{actionEffectInFlight.set(false);}
    }
    private static JSONObject effectSnapshot(JSONObject source)throws Exception {
        if(source==null)return null;JSONObject out=new JSONObject();for(String key:new String[]{"ok","observationId","packageName","displayId","sessionId","pauseRevision","nodes","windows","truncated","treeCoverage","observationSources","treeFingerprint"})if(source.has(key))out.put(key,source.get(key));return new JSONObject(out.toString());
    }
    private JSONObject executeBound(String tool,JSONObject args,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        String blocked = authority(control);
        if (blocked != null) return failure(blocked, "任务已暂停或控制身份已变化，未派发操作。");
        if (reconciliationRequired) return failure("reconciliation_required", "上次操作效果未知，未派发新操作。");
        if (safetyPause != null) return failure(safetyPause, "本次执行已安全阻断，未派发操作。");
        if ("phone_observe".equals(tool)) return observe(imageCeiling, control);
        if (args == null) args = new JSONObject();
        if(CLICK_TEXT.equals(tool)&&admission==null)return failure("host_authority_required","文字目标点击需要原任务预留操作，尚未派发。");
        boolean withoutObservation = "phone_list_apps".equals(tool) || "phone_launch_app".equals(tool);
        if (!withoutObservation && !current(args.optString("observationId"), control))
            return failure("stale_observation", "页面引用已变化，未派发操作，请重新观察。");
        if (!withoutObservation && "empty_display".equals(latest.getJSONObject("observationDecision").optString("request")))
            return failure("coordinate_unverifiable", "独立显示尚无画面，请先列出并打开应用。");
        boolean coordinate = "phone_tap".equals(tool) || "phone_swipe".equals(tool);
        if (coordinate && (!imageCeiling || latest == null || !ObservationReport.hasImage(latest))) {
            pendingNeed = Need.TARGET_MISSING;
            return failure("coordinate_unverifiable", "尚未向模型提供当前真实图片，未派发坐标；请先升级观察。");
        }
        Binding dispatchedBinding = withoutObservation ? null : latestBinding;
        JSONObject result;
        OcrTextTarget selected=null;
        try {
            if(CLICK_TEXT.equals(tool)){
                selected=textTargets.get(args.optString("textTargetId"));
                if(!(source instanceof NativeOcrTapPort)||selected==null||!selected.matches(control,args.optString("observationId"),control.pauseRevision()))
                    result=failure("stale_text_target","文字目标不属于当前原生OCR观察，请重新观察。");
                else if(!imageCeiling)result=failure("screenshots_disabled","本轮未允许截图读取，尚未派发文字点击。");
                else result=((NativeOcrTapPort)source).tapOcrText(selected,control,admission);
            }else result = source.execute(tool, args, control);
        }
        catch (InterruptedException cancelled) { throw cancelled; }
        catch (Exception unknown) {
            result = new JSONObject().put("ok", false).put("error", "action_unknown")
                    .put("accepted", JSONObject.NULL).put("verified", false)
                    .put("message", "未取得操作回执，请由主人核对；不会自动重放。");
        }
        if (result == null) result = new JSONObject().put("ok", false).put("error", "action_unknown")
                .put("accepted", JSONObject.NULL).put("verified", false).put("message", "操作没有确定回执，请由主人核对。");
        if (!"phone_list_apps".equals(tool)) {
            clearReferences();
            String code = result.optString("error", result.optString("code"));
            boolean unknown = ObservationSafety.unknownEffect(code) || result.has("accepted") && result.isNull("accepted");
            if (unknown) reconciliationRequired = true;
            else if (!result.optBoolean("ok") && (isTargetFailure(code) || code.isEmpty()
                    && Boolean.FALSE.equals(result.opt("accepted"))
                    && ("phone_click_node".equals(tool) || "phone_type".equals(tool)))) pendingNeed = Need.ACTION_REJECTED;
            else pendingNeed = Need.AUTOMATIC;
        }
        if (!result.optBoolean("ok")) {
            ObservationReport.annotateFailure(result);
            retainSafetyFence(result);
        }
        if (dispatchedBinding != null) result.put("observationBinding", bindingJson(dispatchedBinding));
        if(selected!=null)result.put("ocrTextTarget",selected.receiptEvidence());
        return result.put("observationScope", scopeJson()).put("automaticReplayAllowed", false);
    }

    private Decision choose(JSONObject observation, Need need, RunControl control) throws Exception {
        String blocked = authority(control);
        if (blocked != null) return AdaptiveObservationPolicy.blocked(blocked);
        if (observation.optInt("displayId", -1) != scope.displayId || !scope.sessionId.equals(observation.optString("sessionId")))
            return AdaptiveObservationPolicy.blocked("identity_mismatch");
        JSONObject sources = observation.optJSONObject("observationSources");
        String id = observation.optString("observationId");
        if (sources == null || id.isEmpty() || id.length() > 250 || !observation.has("observedAtUptimeMs")
                || observation.optLong("pauseRevision", -1) < 0)
            return AdaptiveObservationPolicy.blocked("observation_metadata_missing");
        Binding expected = scope.observation(id, control.pauseRevision());
        Binding binding = scope.observation(id, observation.getLong("pauseRevision"));
        long at = observation.optLong("observedAtUptimeMs", -1);
        if (at < 0) return AdaptiveObservationPolicy.blocked("observation_metadata_missing");
        // Recompute counts and image presence instead of treating a source's metadata as image input.
        ObservationReport.attach(observation, sources.optBoolean("treeAvailable"), sources.optString("treeReason"),
                sources.optBoolean("imageIncluded"), sources.optString("imageReason"), ocrAvailable());
        sources = observation.getJSONObject("observationSources");
        Evidence evidence = new Evidence(binding, sources.optBoolean("treeAvailable"), observation.optBoolean("truncated"),
                sources.optInt("usefulNodes"), sources.optBoolean("imageIncluded"),
                "display_has_no_frame".equals(observation.optString("screenState")), at,sources.optBoolean("treeAdequate"));
        return policy.decide(expected, evidence, need, Health.READY, imageCeiling, visionAvailable, ocrAvailable(),
                control.isPaused(), control.isCancelled(), uptime.getAsLong(), MAX_AGE_MS);
    }
    private JSONObject deliver(JSONObject observation, Decision decision, RunControl control) throws Exception {
        String blocked = authority(control);
        if (blocked != null) { clearReferences(); return failure(blocked, "任务控制已变化，旧观察已丢弃。"); }
        if (decision.request != Request.NODES && decision.request != Request.VISION && decision.request != Request.OCR && decision.request != Request.EMPTY_DISPLAY) {
            String reason = decision.reason;
            clearReferences();
            String code = reason.startsWith("screenshots_disabled:") ? "screenshots_disabled"
                    : reason.startsWith("visual_engines_unavailable:") ? "visual_engines_unavailable"
                    : decision.request == Request.REOBSERVE ? "stale_observation" : reason;
            JSONObject failed = failure(code, "当前观察无法安全继续，请重新观察或由主人处理。").put("observationReason", reason);
            retainSafetyFence(failed);
            return failed;
        }
        JSONObject out = new JSONObject(observation.toString());
        if (decision.request == Request.OCR) {
            JSONObject recognized = recognize(out, control);
            if (!recognized.optBoolean("ok")) { clearReferences(); return recognized; }
        }
        if (decision.request != Request.VISION) {
            stripImage(out);
            JSONObject sources = out.getJSONObject("observationSources");
            sources.put("imageIncluded", false);
            if (decision.request == Request.NODES) sources.put("imageReason", "nodes_preferred");
            if (decision.request == Request.OCR) sources.put("imageReason", "local_ocr_text_only");
        }
        latestBinding = scope.observation(out.getString("observationId"), out.getLong("pauseRevision"));
        out.put("observationBinding", bindingJson(latestBinding)).put("observationDecision", new JSONObject()
                .put("request", decision.request.name().toLowerCase(java.util.Locale.ROOT)).put("reason", decision.reason)
                .put("visionAvailable", visionAvailable).put("ocrAvailable", ocrAvailable())
                .put("ocrReason", ocrAvailable() ? "mounted_local_engine" : "engine_not_installed")
                .put("screenshotsAllowed", imageCeiling).put("automaticReplayAllowed", false));
        latest = new JSONObject(out.toString()); pendingNeed = Need.AUTOMATIC;
        return out;
    }
    private boolean ocrAvailable() { return ocr != null && ocr.available(); }

    private JSONObject recognize(JSONObject observation, RunControl control) throws Exception {
        if (!imageCeiling || !ocrAvailable() || !ObservationReport.hasImage(observation))
            return failure("ocr_unavailable", "未取得可用于本地识别的授权图片。");
        String mime = observation.optString("imageMimeType");
        String format = "image/png".equals(mime) ? "png" : "image/jpeg".equals(mime) ? "jpeg" : "webp";
        final OcrPage page;
        try { page = ocr.recognizeImage(Base64.getDecoder().decode(observation.getString("imageBase64")), format, control); }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception unavailable) {
            control.check();
            return failure("ocr_unavailable", "本地文字识别未完成；未生成文字或操作坐标。");
        }
        String blocked = authority(control);
        if (blocked != null) return failure(blocked, "任务控制已变化，识别结果已丢弃。");
        long now = uptime.getAsLong(), at = observation.getLong("observedAtUptimeMs");
        if (control.pauseRevision() != observation.getLong("pauseRevision") || now < at || now - at > MAX_AGE_MS)
            return failure("stale_observation", "图片观察已过期，识别结果已丢弃，请重新观察。");
        if (page.sourceWidth != observation.getInt("imageWidth") || page.sourceHeight != observation.getInt("imageHeight"))
            return failure("ocr_image_mismatch", "实际图片尺寸与原观察不一致，识别结果已丢弃。");
        if (page.lines.isEmpty()) return failure("ocr_no_text", "没有识别出可用文字；这不证明页面为空，请由主人核对。");
        JSONArray lines = new JSONArray(); int characters = 0; boolean truncated = false;
        java.util.Map<String,OcrTextTarget> minted=new java.util.LinkedHashMap<>();
        java.util.Set<Integer> shownLines=new java.util.HashSet<>();
        for (OcrPage.Line line : page.lines) {
            control.check();
            if (lines.length() >= 128 || characters + line.text.length() > 12000) { truncated = true; break; }
            JSONObject recognizedLine=new JSONObject().put("line", line.index).put("text", line.text)
                    .put("boundsPixels", new JSONArray().put(line.left).put(line.top).put(line.right).put(line.bottom));
            // A provider with real word capture never substitutes a whole-line center for a missing word.
            if(!page.wordReadAttempted&&source instanceof NativeOcrTapPort){OcrTextTarget target=new OcrTextTarget(observation.getString("observationId"),observation.getLong("pauseRevision"),control,page,line);minted.put(target.id,target);recognizedLine.put("textTargetId",target.id).put("targetGranularity","line_center_not_a_specific_word");}
            lines.put(recognizedLine);
            shownLines.add(line.index);
            characters += line.text.length();
        }
        if (lines.length() == 0) return failure("ocr_limit_exceeded", "文字超出单次观察预算，请由主人核对。");
        JSONArray words=new JSONArray();int wordBytes=2;
        for(OcrPage.Word word:page.words){
            control.check();if(!shownLines.contains(word.lineIndex))continue;
            if(words.length()>=128)break;
            JSONObject row=new JSONObject().put("word",word.index).put("line",word.lineIndex).put("text",word.text).put("confidence",word.confidence)
                    .put("boundsPixels",new JSONArray().put(word.left).put(word.top).put(word.right).put(word.bottom));
            OcrTextTarget target=source instanceof NativeOcrTapPort?new OcrTextTarget(observation.getString("observationId"),observation.getLong("pauseRevision"),control,page,word):null;
            if(target!=null)row.put("textTargetId",target.id);
            int bytes=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+(words.length()>0?1:0);if(wordBytes+bytes>8000)break;
            words.put(row);wordBytes+=bytes;if(target!=null)minted.put(target.id,target);
        }
        int omittedWords=page.words.size()-words.length();truncated|=page.wordsTruncated||omittedWords>0;
        observation.put("ocr", new JSONObject().put("engineVersion", ocr.engineVersion()).put("kind", "ocr_text")
                .put("observationId", observation.getString("observationId")).put("lines", lines)
                .put("words",words).put("wordBoxesAttempted",page.wordReadAttempted).put("wordBoxesTruncated",page.wordsTruncated).put("wordTargetsOmitted",omittedWords)
                .put("nativeTargetGranularity",page.wordReadAttempted?words.length()>0?"word_boxes":"line_text_only_no_precise_target":"legacy_line_center_not_specific_word")
                .put("wordConfidenceMeaning","ocr_engine_score_not_calibrated_accuracy_semantic_or_clickability_guarantee")
                .put("coordinateSpace", OcrPage.COORDINATE_SPACE).put("rasterWidth", page.width).put("rasterHeight", page.height)
                .put("sourceWidth", page.sourceWidth).put("sourceHeight", page.sourceHeight).put("downsampled", page.downsampled())
                .put("truncated", truncated).put("coverage", "recognized_text_only").put("actionCoordinates", false)
                .put("nativeTextTargetsAvailable",!minted.isEmpty()).put("recognitionMayBeWrong",true)
                .put("verifiedBusinessSuccess", false).put("contentTrust", "untrusted_screen_content"));
        String finalBlocked=authority(control);long finalNow=uptime.getAsLong();
        if(finalBlocked!=null||control.pauseRevision()!=observation.getLong("pauseRevision")||finalNow<at||finalNow-at>MAX_AGE_MS)
            return failure(finalBlocked==null?"stale_observation":finalBlocked,"原观察已变化，OCR文字和目标已丢弃，请重新观察。");
        textTargets.clear();textTargets.putAll(minted);
        observation.getJSONObject("observationSources").put("ocrTextIncluded", true);
        return new JSONObject().put("ok", true);
    }
    private String authority(RunControl control) throws Exception {
        Objects.requireNonNull(control).check();
        if (!control.hasHostRun() || !control.matchesHostRun(scope.taskId, scope.runId, scope.epoch)
                || boundControl != null && boundControl != control) return "identity_mismatch";
        if (boundControl == null) boundControl = control;
        if (control.isPaused()) return "task_paused";
        return null;
    }
    private boolean current(String id, RunControl control) {
        if (latest == null || latestBinding == null || !latestBinding.observationId.equals(id)
                || latestBinding.pauseRevision != control.pauseRevision()) return false;
        long now = uptime.getAsLong(), at = latest.optLong("observedAtUptimeMs", -1);
        return now >= at && now - at <= MAX_AGE_MS;
    }
    private JSONObject rejectedObservation(JSONObject result) throws Exception {
        clearReferences(); ObservationReport.annotateFailure(result); retainSafetyFence(result);
        return result.put("observationScope", scopeJson());
    }
    /** A transient refusal fence, never a replacement for TaskStore's durable unresolved-operation state. */
    private void retainSafetyFence(JSONObject result) {
        if ("reconcile".equals(result.optString("nextObservationAction"))) reconciliationRequired = true;
        else if (result.optBoolean("requiresPause")) {
            String code = result.optString("error", result.optString("code"));
            if (!"task_paused".equals(code) && !"paused_or_changed".equals(code)) safetyPause = code;
        }
    }
    private static boolean isTargetFailure(String code) {
        return "action_rejected".equals(code) || "not_clickable".equals(code) || "not_editable".equals(code)
                || "node_unavailable".equals(code) || "input_rejected".equals(code);
    }
    private static void stripImage(JSONObject json) {
        for (String key : new String[]{"imageBase64", "imageMimeType", "imageWidth", "imageHeight"}) json.remove(key);
    }
    private static JSONObject bindingJson(Binding binding) throws Exception {
        return new JSONObject().put("taskId", binding.taskId).put("runId", binding.runId).put("epoch", binding.epoch)
                .put("displayId", binding.displayId).put("sessionId", binding.sessionId)
                .put("observationId", binding.observationId).put("pauseRevision", binding.pauseRevision);
    }
    private JSONObject scopeJson() throws Exception {
        return new JSONObject().put("taskId", scope.taskId).put("runId", scope.runId).put("epoch", scope.epoch)
                .put("displayId", scope.displayId).put("sessionId", scope.sessionId);
    }
    private JSONObject failure(String code, String message) throws Exception {
        return ObservationReport.annotateFailure(new JSONObject().put("ok", false).put("error", code)
                .put("message", message).put("accepted", false).put("verified", false))
                .put("observationScope", scopeJson());
    }
}
