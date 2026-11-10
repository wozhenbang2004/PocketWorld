package dev.pocketengine.features.semantic;

import android.content.pm.PackageManager;
import android.os.Build;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Status is safe through FeatureHost; every discovery/dispatch runs only through a bound capability port. */
public final class SemanticModule implements EngineModule {
    private final FeatureContext context;
    private final java.util.LinkedHashMap<String,SemanticRunSession> runSessions=new java.util.LinkedHashMap<>();
    private volatile boolean closed;
    public SemanticModule(FeatureContext context) {this.context=java.util.Objects.requireNonNull(context);}
    @Override public String id() {return "semantic";}
    @Override public JSONObject snapshot() throws Exception {
        return new JSONObject().put("id",id()).put("commands",new JSONArray(new String[]{"status"})).put("intentDiscoveryImplemented",true)
                .put("adapters",new JSONArray(new String[]{"web","app_link","share_text","show_alarms","map_search","dial","email_draft","calendar_draft","calendar_list","calendar_batch","alarm","timer"}))
                .put("calendar",CalendarProviderSemanticPlatform.permissions(context.android).put("batchLimit",50).put("batchBytesLimit",131072)
                        .put("perBatchApproval",true).put("readbackImplemented",true).put("automaticReplayAllowed",false))
                .put("clock",new JSONObject().put("permissionGranted",context.android.checkSelfPermission(AndroidSemanticPlatform.CLOCK_PERMISSION)==PackageManager.PERMISSION_GRANTED)
                        .put("deviceTimeZone",java.util.TimeZone.getDefault().getID()).put("receiverMayApplyImmediately",true).put("actualClockStateVerified",false))
                .put("dispatchScope","original_owner_phone_exact_display").put("backgroundIntentDispatch",true).put("crossProfileSemanticDispatch",false)
                .put("automaticFallback",false).put("sdkInt",Build.VERSION.SDK_INT)
                .put("appFunctions",new JSONObject().put("available",false).put("clientImplemented",false)
                        .put("executionPermissionGranted",context.android.checkSelfPermission("android.permission.EXECUTE_APP_FUNCTIONS")==PackageManager.PERMISSION_GRANTED)
                        .put("reason","typed_cross_app_client_not_integrated_in_sdk35_build").put("fallback","phone_nodes_or_vision"))
                .put("webMcp",new JSONObject().put("available",false).put("pageBindingImplemented",false).put("featureProbePerformed",false)
                        .put("reason","no_actual_origin_bound_page_client").put("fallback","authorized_browser_dom_or_phone"));
    }
    @Override public JSONObject handle(String command,JSONObject input) throws Exception {
        if(!"status".equals(command))throw new SecurityException("语义入口仅通过原Task/Run能力调用，不能从FeatureHost直接分派");
        SemanticJson.keys(input==null?new JSONObject():input,Set.of());return snapshot();
    }
    /** Call after the original run/control/phone lease exists; authorize returned tools via TaskStore as usual. */
    public CapabilityPort bind(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl originalControl,
            PhonePort selectedPhone,EventSink approvalEvents) throws Exception {
        if(closed)throw new IllegalStateException("语义模块已关闭");
        JSONObject task=tasks.get(binding.taskId,false);
        if(task==null||!binding.runId.equals(task.optString("runId"))||binding.epoch!=task.optInt("coordinatorEpoch")
                ||!originalControl.hasHostRun()||!originalControl.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("需要原任务执行绑定");
        if(selectedPhone instanceof dev.pocketengine.phone.DeferredPhoneSession deferred
                &&binding.allowWrites&&"phone".equals(task.optString("kind"))
                &&deferred.matchesHostScope(binding.taskId,binding.runId,binding.epoch)
                &&deferred.originalControl()==originalControl&&deferred.executionMode().equals(task.optString("executionMode")))
            return new SemanticCapabilityPort(existing,originalControl,new SemanticTaskAuthority(tasks,binding,approvalEvents),
                    new CalendarProviderSemanticPlatform(context.android,new DeferredSemanticPlatform(context.android,deferred)),true,retainRun(binding,originalControl));
        PhoneAccessibilityService service=PhoneAccessibilityService.get();
        boolean dispatch=binding.allowWrites&&"phone".equals(task.optString("kind"))&&"main_screen".equals(task.optString("executionMode"))
                &&service!=null&&selectedPhone!=null&&selectedPhone.isConnected();
        PhonePort physical=selectedPhone instanceof dev.pocketengine.phone.observation.AdaptivePhonePort?
                ((dev.pocketengine.phone.observation.AdaptivePhonePort)selectedPhone).physicalPort():selectedPhone;
        if(physical instanceof dev.pocketengine.phone.access.GuardedPhonePort)physical=((dev.pocketengine.phone.access.GuardedPhonePort)physical).physicalPort();
        boolean background=binding.allowWrites&&"phone".equals(task.optString("kind"))&&"background".equals(task.optString("executionMode"))
                &&physical instanceof dev.pocketengine.phone.display.BackgroundPhonePort&&selectedPhone.isConnected();
        SemanticPlatform platform=background?new BackgroundSemanticPlatform(context.android,(dev.pocketengine.phone.display.BackgroundPhonePort)physical,new dev.pocketengine.phone.access.AndroidPhoneAccessStore(context.android)):
                new AndroidSemanticPlatform(context.android,dispatch?service:null,dispatch?selectedPhone::clearReferences:null);
        platform=new CalendarProviderSemanticPlatform(context.android,platform);
        dispatch|=background;
        SemanticTaskAuthority authority=new SemanticTaskAuthority(tasks,binding,approvalEvents);
        if(!dispatch)return new SemanticCapabilityPort(existing,originalControl,authority,platform,false);
        return new SemanticCapabilityPort(existing,originalControl,authority,platform,true,retainRun(binding,originalControl));
    }
    private synchronized SemanticRunSession retainRun(ModelCapabilityAdapters.Binding binding,RunControl originalControl) {
        if(closed)throw new IllegalStateException("语义模块已关闭");
        runSessions.entrySet().removeIf(entry->entry.getValue().ended());
        String key=binding.taskId+"/"+binding.runId+"/"+binding.epoch;SemanticRunSession current=runSessions.get(key);
        if(current!=null){current.requireLive(originalControl);return current;}
        while(runSessions.size()>=16){String first=runSessions.keySet().iterator().next();runSessions.remove(first).close();}
        SemanticRunSession fresh=new SemanticRunSession(originalControl,()->System.nanoTime()/1_000_000L);runSessions.put(key,fresh);return fresh;
    }
    /** Native terminal cleanup, or explicit owner reconciliation after TaskStore has no unresolved effect.
     * Owner hold/approval/port reconstruction must NOT call this. Reconciliation requires fresh discovery/approval. */
    public synchronized void releaseRun(RunControl originalControl) {
        java.util.Iterator<SemanticRunSession> values=runSessions.values().iterator();
        while(values.hasNext()){SemanticRunSession session=values.next();if(session.originalControl==originalControl){values.remove();session.close();}}
    }
    @Override public synchronized void close(){closed=true;for(SemanticRunSession session:runSessions.values())session.close();runSessions.clear();}
}
