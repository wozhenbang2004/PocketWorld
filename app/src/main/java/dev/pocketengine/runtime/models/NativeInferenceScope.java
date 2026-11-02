package dev.pocketengine.runtime.models;

import dev.pocketengine.core.RunControl;
import java.util.Objects;
import org.json.JSONObject;

/** Native caller provenance. Never deserialized from HTTP, a model answer, or a tool argument. */
public final class NativeInferenceScope {
    public enum Entry { OWNER_APP, OWNER_BACKGROUND, EXTERNAL_RELAY, EXTERNAL_GROUP, PAIRED_DEVICE_GOAL }
    public final Entry entry;
    public final String actorId,spaceId,taskId,runId;
    public final int epoch;
    private final RunControl original;
    private final RunControl.AuthorityCheck consent;
    private final String consentReference,origin;

    private NativeInferenceScope(Entry entry,String actor,String space,String task,String run,int epoch,
            RunControl original,String consentReference,RunControl.AuthorityCheck consent,String origin) {
        this.entry=Objects.requireNonNull(entry);actorId=text(actor,"actorId");spaceId=text(space,"spaceId");
        taskId=task==null?"":task;runId=run==null?"":run;this.epoch=epoch;this.original=original;
        if(taskId.isEmpty()!=runId.isEmpty()||taskId.isEmpty()&&epoch!=0||!taskId.isEmpty()&&epoch<1)
            throw new IllegalArgumentException("模型调用任务范围不完整");
        if(!taskId.isEmpty()&&(original==null||!original.matchesHostRun(taskId,runId,epoch)))
            throw new SecurityException("模型调用不属于原任务执行代次");
        this.consentReference=consentReference;this.consent=consent;this.origin=origin;
    }
    public static NativeInferenceScope ownerApp(String actor,String space,String task,String run,int epoch,RunControl original) {
        return new NativeInferenceScope(Entry.OWNER_APP,actor,space,task,run,epoch,Objects.requireNonNull(original),"",null,"");
    }
    /** The host must retain the actual current consent/occurrence check, not a boolean or arbitrary text. */
    public static NativeInferenceScope ownerBackground(String actor,String space,String task,String run,int epoch,
            RunControl original,String consentReference,RunControl.AuthorityCheck currentConsent) {
        return new NativeInferenceScope(Entry.OWNER_BACKGROUND,actor,space,task,run,epoch,Objects.requireNonNull(original),
                text(consentReference,"consentReference"),Objects.requireNonNull(currentConsent),"");
    }
    public static NativeInferenceScope externalRelay() {
        return new NativeInferenceScope(Entry.EXTERNAL_RELAY,"external_client","device_loopback","","",0,null,"",null,"");
    }
    /** B-local owner-selected model policy + original PairGrant/run proof; never ownerApp identity. */
    public static NativeInferenceScope pairedDeviceGoal(dev.pocketengine.store.TaskStoreDeviceTasks.GoalModelApproval proof,RunControl original)throws Exception{
        java.util.Objects.requireNonNull(proof).checkCurrent(original);var run=original.hostRunIdentity();var admission=proof.deviceAdmission();
        return new NativeInferenceScope(Entry.PAIRED_DEVICE_GOAL,admission.principalId(),admission.spaceId(),run.taskId(),run.runId(),run.epoch(),original,
                proof.publicView().getString("reviewId"),()->proof.checkCurrent(original),admission.origin().toString());
    }
    /** Freeze the authenticated GroupHostPort.Request.origin() supplied by the native host. */
    public static NativeInferenceScope externalGroup(JSONObject authenticatedOrigin,String task,String run,int epoch,RunControl original) {
        Objects.requireNonNull(authenticatedOrigin);
        if(!"group".equals(authenticatedOrigin.optString("kind")))throw new SecurityException("缺少认证群来源");
        return new NativeInferenceScope(Entry.EXTERNAL_GROUP,authenticatedOrigin.optString("actorId"),authenticatedOrigin.optString("spaceId"),
                task,run,epoch,original,"",null,authenticatedOrigin.toString());
    }
    /** Only the original SQL-issued, native-owner reviewed weak-source claim can enter this route. */
    public static NativeInferenceScope reviewedExternalGroup(dev.pocketengine.store.TaskStore.ReviewedExternalGroupClaim claim,RunControl original)throws Exception {
        java.util.Objects.requireNonNull(claim).checkCurrent();if(!claim.matchesOriginal(original))throw new SecurityException("不是原材料任务控制器");
        return new NativeInferenceScope(Entry.EXTERNAL_GROUP,claim.actorId,claim.spaceId,claim.taskId,claim.runId,claim.epoch,original,claim.scopeDigest,claim::checkCurrent,claim.view().toString());
    }
    void check(RunControl actual)throws Exception {
        if(original!=null&&actual!=original)throw new SecurityException("模型调用控制器已改变");
        if(actual!=null)actual.check();
        if(!taskId.isEmpty()&&!actual.matchesHostRun(taskId,runId,epoch))throw new SecurityException("模型调用执行代次已改变");
        if(entry==Entry.OWNER_BACKGROUND||entry==Entry.PAIRED_DEVICE_GOAL||entry==Entry.EXTERNAL_GROUP&&consent!=null)consent.check();
        if(actual!=null)actual.check();
    }
    public JSONObject publicJson()throws Exception {
        JSONObject value=new JSONObject().put("entry",entry.name()).put("actorId",actorId).put("spaceId",spaceId)
                .put("taskId",taskId).put("runId",runId).put("epoch",epoch);
        if(!consentReference.isEmpty())value.put("consentReference",consentReference);
        if(!origin.isEmpty())value.put("origin",new JSONObject(origin));
        return value;
    }
    private static String text(String value,String field) {
        if(value==null||blank(value)||value.length()>2000)throw new IllegalArgumentException("缺少原生模型调用"+field);
        return value;
    }
    /** Same code-point semantics as String.isBlank, available on minSdk30. */
    private static boolean blank(String value){
        if(value==null)return true;
        for(int offset=0;offset<value.length();){int point=value.codePointAt(offset);if(!Character.isWhitespace(point))return false;offset+=Character.charCount(point);}
        return true;
    }

}
