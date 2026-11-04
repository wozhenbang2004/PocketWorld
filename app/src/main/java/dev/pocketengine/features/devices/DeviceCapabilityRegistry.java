package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fresh native diagnostics only. A manifest is NEVER a permission, model policy or accepted receipt. */
public final class DeviceCapabilityRegistry {
    @FunctionalInterface public interface NativeHealth { JSONObject snapshot() throws Exception; }
    /** Root host can project the ORIGINAL owner policies for this actual paired TLS caller only. */
    @FunctionalInterface public interface ScopedNativeHealth { JSONObject snapshot(PairingCoordinator.DiscoveryPermission caller) throws Exception; }
    private final String deviceId, instanceId;
    private final ScopedNativeHealth health;
    private static final String[] PROJECT={"project_inspect","project_read_file","project_search","project_plan_manifest","project_plan","project_execute","project_status","project_log","project_changes","project_review_file"};
    private static final String[] DEVICES={"device_invoke_tool","device_delegate_goal","device_operation_get"};
    public DeviceCapabilityRegistry(String deviceId,String instanceId,NativeHealth health){this(deviceId,instanceId,ignored->health.snapshot());}
    public DeviceCapabilityRegistry(String deviceId,String instanceId,ScopedNativeHealth health){this.deviceId=java.util.Objects.requireNonNull(deviceId);this.instanceId=java.util.Objects.requireNonNull(instanceId);this.health=java.util.Objects.requireNonNull(health);}
    public JSONObject describe()throws Exception{return describe(null);}
    public static JSONArray supportedProjectToolNames()throws Exception{return new JSONArray(PROJECT);}
    public static JSONArray supportedDeviceGoalToolNames()throws Exception{return new JSONArray(DEVICES);}
    public JSONObject describe(PairingCoordinator.DiscoveryPermission permission)throws Exception{
        if(permission!=null)permission.checkCurrent(System.currentTimeMillis());JSONObject current=health.snapshot(permission);long now=System.currentTimeMillis();JSONArray capabilities=new JSONArray();
        capabilities.put(capability("phone.tools.main","main_screen",current,false));capabilities.put(capability("phone.tools.background","background",current,true));
        JSONObject goal=current.optJSONObject("goalPolicy");boolean sameCaller=permission!=null&&goal!=null&&permission.callerDeviceId().equals(goal.optString("callerDeviceId"));
        boolean configured=sameCaller&&goal.optBoolean("configured"),apiReady=configured&&goal.optBoolean("apiModelReady"),reviewAvailable=current.optBoolean("goalOwnerReviewAvailable");
        boolean enabled=current.optBoolean("receiverEnabled"),busy=current.optBoolean("goalExecutorBusy",current.optBoolean("busy")),ready=enabled&&!busy&&configured&&apiReady,canReview=enabled&&!busy&&reviewAvailable;
        String reason=!enabled?"receiver_disabled":busy?"native_executor_busy":!configured?reviewAvailable?"local_owner_model_review_required":"local_owner_model_policy_unconfigured":!apiReady?"selected_policy_api_model_unavailable":"";
        JSONObject project=new JSONObject().put("implemented",true).put("installed",current.optBoolean("scopedProjectPortInstalled")).put("toolNames",new JSONArray(PROJECT))
                .put("resourceBinding",new JSONObject().put("screenMode","capabilities").put("required",new JSONArray(new String[]{"projectId","checkoutId","runtimeId"})))
                .put("runtimeIds",new JSONArray(new String[]{"termux","managed_process_v1"})).put("concreteEffectsRequireExactOriginalApproval",true);
        JSONObject routes=new JSONObject().put("implemented",true).put("nativeRoutesReady",current.optBoolean("nativeDeviceRoutesReady")).put("toolNames",new JSONArray(DEVICES)).put("ordinaryPairingDoesNotDelegate",true);
        capabilities.put(new JSONObject().put("id","agent.goal").put("version","1.0").put("implemented",true).put("installed",true).put("authorized",enabled&&configured).put("ready",ready).put("busy",busy).put("blocked",!ready&&!canReview).put("reason",reason)
                .put("admissionState",ready?"ready":canReview?"owner_review_available":"blocked").put("canSubmitForLocalReview",canReview).put("requiresLocalModelReview",!configured).put("defaultModelIsNotPolicy",true)
                .put("requires",new JSONArray(new String[]{"B_native_owner_selected_model_policy","current_exact_PairGrant_revision","finite_original_budget","visible_stop_control"}))
                .put("modelLocation","owner_selected_authorized_api").put("localInference",false).put("supportedExecutionModes",new JSONArray().put("capabilities").put("main_screen").put("background"))
                .put("supported",new JSONObject().put("scopedProject",project).put("explicitDeviceRoutes",routes))
                .put("unsupported",new JSONArray(new String[]{"owned_service","PTY","Git","freeze_package","collect","preview","ambient_private_memory","whole_private_workspace"}))
                .put("account",new JSONObject().put("ready",false).put("authorized",false).put("reason","CHATGPT_DEVICE_GOAL_AUDIENCE_UNPROVEN")));
        boolean draftInstalled=current.optBoolean("scheduleDraftsInstalled"),draftAuthorized=current.optBoolean("scheduleDraftsAuthorized"),draftReady=enabled&&draftInstalled&&draftAuthorized;
        capabilities.put(new JSONObject().put("id",DeviceScheduleDrafts.CAPABILITY).put("version",DeviceProtocol.VERSION).put("implemented",true).put("installed",draftInstalled).put("authorized",draftAuthorized).put("ready",draftReady).put("blocked",!draftReady)
                .put("reason",!enabled?"receiver_disabled":!draftInstalled?"native_schedule_drafts_unavailable":!draftAuthorized?"explicit_schedule_draft_grant_required":"").put("toolNames",DeviceScheduleDrafts.tools()).put("resourceBinding",DeviceScheduleDrafts.resource())
                .put("toolCalls",0).put("modelCalls",0).put("reviewRequired",true).put("automaticExecution",false).put("queryOriginalOnlyOnUnknown",true).put("maxDraftsPerHour",8).put("minimumDraftIntervalMs",30000));
        // Native health is assembled in-process using this SAME opaque discovery permission and
        // the original local signer. Still filter recipient/epoch and bound the resulting control frame.
        JSONArray receipts=new JSONArray(),provided=current.optJSONArray("grantReceipts");boolean truncated=current.optBoolean("grantReceiptsTruncated")||provided!=null&&provided.length()>=16;
        for(int i=0;permission!=null&&provided!=null&&i<provided.length();i++){
            JSONObject signed=provided.getJSONObject(i),body=signed.optJSONObject("body"),grant=body==null?null:body.optJSONObject("grant");if(grant==null||!permission.callerDeviceId().equals(grant.optString("peerDeviceId"))||permission.authorizationEpoch()!=grant.optLong("authorizationEpoch")||!deviceId.equals(signed.optString("issuerDeviceId"))||!instanceId.equals(body.optString("targetInstanceId")))throw new SecurityException("native发现回执不是此实际caller/epoch/目标实例");
            JSONArray candidate=new JSONArray(receipts.toString()).put(signed);if(candidate.length()>16||DeviceProtocol.canonicalBytes(new JSONObject().put("capabilities",capabilities).put("grantReceipts",candidate)).length>DeviceProtocol.MAX_CONTROL_BYTES-4096){truncated=true;break;}receipts=candidate;
        }
        JSONObject metadata=new JSONObject().put("serverAlwaysOn",false).put("transportIndependentFromTaskState",true).put("grantReceiptsTruncated",truncated).put("taskControl",true).put("operationCancel",true).put("operationCancelScope","original_goal_submission_only");
        return DeviceProtocol.deviceDescription(deviceId,instanceId,now,now+5000,capabilities,receipts,metadata);
    }
    private static JSONObject capability(String id,String mode,JSONObject health,boolean background)throws Exception{
        JSONObject nativeState=health.optJSONObject(mode);boolean authorized=health.optBoolean("receiverEnabled")&&nativeState!=null&&nativeState.optBoolean("authorized"),ready=authorized&&nativeState.optBoolean("ready")&&!health.optBoolean("busy");
        JSONArray granted=nativeState==null?null:nativeState.optJSONArray("toolNames");JSONObject binding=nativeState==null?null:nativeState.optJSONObject("resourceBinding");
        JSONArray available=granted==null||binding==null?new JSONArray():DeviceToolsRuntime.toolDeclarations(granted,binding);
        JSONArray requires=new JSONArray().put("native_device_scope").put("visible_stop_control").put("accessibility").put("unlocked_interactive_session");if(background)requires.put("android_14_or_newer").put("local_authorized_adb_or_shizuku_helper").put("background_display_session");
        return new JSONObject().put("id",id).put("version","1.0").put("implemented",true).put("installed",nativeState!=null&&nativeState.optBoolean("installed")).put("authorized",authorized).put("ready",ready).put("busy",health.optBoolean("busy")).put("blocked",!ready)
                .put("reason",nativeState==null?"native_health_unavailable":nativeState.optString("reason",ready?"":"permission_or_resource_unavailable")).put("requires",requires).put("modelRequired",false).put("screenMode",mode).put("toolDeclarations",available).put("supportedToolDeclarations",DeviceToolsRuntime.toolDeclarations())
                .put("wholeDisplayInputRequiresExplicitGrant",true).put("wholeDisplayCaptureRequiresSeparateExplicitGrant",true).put("fallbackToMainScreen",false);
    }
}
