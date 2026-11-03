package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure wake-intent data in the ORIGINAL TaskStore task JSON. No file, worker, grant,
 * scheduler, model/tool budget or executable claim is created by this class.
 * The original SQL owner supplies current native facts and atomically commits input,
 * intent and its existing continuation row. A JSON view is never execution admission. */
public final class TeamWakeQueue {
    public static final String KEY="teamWakeState",FORMAT="pocketworld.team-wake.v1";
    public static final int MAX_INTENTS=128,MAX_BATCH=8,MAX_TOTAL=64,MAX_PER_ROUND=8;
    public static final int MAX_PEER_BACKGROUND_INTENTS=96,MAX_NON_CONTROL_INTENTS=120;
    public static final long MAX_POLICY_MILLIS=24*60*60*1000L;
    public enum SourceKind {OWNER_FOREGROUND,AUTHORIZED_CONTROL,DIRECTED_PEER,BACKGROUND}
    public enum SourceState {CURRENT,REVOKED,UNKNOWN}
    public enum Outcome {COMPLETED,FAILED,CANCELLED,UNKNOWN}
    public record Scope(String teamRunId,String parentTaskId,String parentRunId,int parentEpoch,String parentBudgetRef) {
        public Scope{uuid(teamRunId);uuid(parentTaskId);uuid(parentRunId);positive(parentEpoch);sha(parentBudgetRef);}
    }
    public record Target(String memberId,int identityRevision,int runGeneration,int memberGeneration,int round,
            String childTaskId,String childRunId,int childEpoch,String bindingHash) {
        public Target{member(memberId);positive(identityRevision);nonnegative(runGeneration);nonnegative(memberGeneration);if(round<1||round>6)throw new IllegalArgumentException("原研究/工作轮次无效");uuid(childTaskId);uuid(childRunId);positive(childEpoch);sha(bindingHash);}
    }
    public record Message(Scope scope,Target target,String messageId,String payloadHash,String sourceBindingHash,
            SourceKind sourceKind,boolean nativeCommand,boolean intervention) {
        public Message{Objects.requireNonNull(scope);Objects.requireNonNull(target);uuid(messageId);sha(payloadHash);sha(sourceBindingHash);Objects.requireNonNull(sourceKind);
            if((nativeCommand||intervention)&&sourceKind!=SourceKind.OWNER_FOREGROUND&&sourceKind!=SourceKind.AUTHORIZED_CONTROL)throw new SecurityException("peer/background文本不能伪装原生命令或介入");}
    }
    public record OwnerPolicy(boolean enabled,Set<SourceKind> allowedSources,int perRoundLimit,int cumulativeLimit,long expiresAt) {
        public OwnerPolicy{allowedSources=Set.copyOf(allowedSources);if(perRoundLimit<1||perRoundLimit>MAX_PER_ROUND||cumulativeLimit<perRoundLimit||cumulativeLimit>MAX_TOTAL||enabled&&allowedSources.isEmpty())throw new IllegalArgumentException("唤醒策略需明确来源和有界阈值");}
    }
    /** Exact original SQL wake-claim facts, NOT caller counters or a new budget.
     * The original owner counts its actual original continuation rows under its transaction. */
    public record NativeClaims(int total,Map<Integer,Integer> rounds,String digest) {
        public NativeClaims{rounds=Map.copyOf(rounds);sha(digest);if(total<0)throw new IllegalArgumentException("原唤醒计数无效");long sum=0;for(var e:rounds.entrySet()){if(e.getKey()<1||e.getKey()>6||e.getValue()<0)throw new IllegalArgumentException("原轮次计数无效");sum+=e.getValue();}if(sum!=total)throw new SecurityException("原累计计数不等于逐轮事实");}
        int inRound(int round){return rounds.getOrDefault(round,0);}
    }
    /** Captured only by the native SQL/source owner; source proof checks happen at their
     * original safe boundary. This data object cannot replace that owner's typed proof. */
    public record NativeView(Scope scope,Target target,long now,NativeClaims claims,boolean safeIdle,
            boolean ownerPaused,boolean stopped,boolean unknownEffects,boolean budgetAvailable,Map<String,SourceState> sources) {
        public NativeView{Objects.requireNonNull(scope);Objects.requireNonNull(target);Objects.requireNonNull(claims);sources=Map.copyOf(sources);nonnegative(now);for(String key:sources.keySet())sha(key);}
    }
    /** Reference to the ORIGINAL persisted continuation operation/child tuple, never a new ID. */
    public record OriginalAttempt(String operationId,String childTaskId,String childRunId,int childEpoch) {
        public OriginalAttempt{uuid(operationId);uuid(childTaskId);uuid(childRunId);positive(childEpoch);}
    }
    public static final class Change {
        private final String before,state,receipt;
        private Change(JSONObject originalState,JSONObject changed,JSONObject receipt)throws Exception{before=hash(originalState);seal(changed);state=changed.toString();this.receipt=receipt.put("dataOnly",true).put("executionAuthorized",false).put("messageConsumptionChanged",false).toString();}
        /** Merge only the wake field into the latest actual task; preserve native budget/input writes. */
        public JSONObject mergeInto(JSONObject actualTask)throws Exception{if(!before.equals(hash(stored(actualTask))))throw new IllegalStateException("原wake字段已变化，未覆盖并发输入/claim");return new JSONObject(actualTask.toString()).put(KEY,new JSONObject(state));}
        public JSONObject receipt()throws Exception{return new JSONObject(receipt);}
    }
    public static final class Plan {
        private final String original,before,state,batch,receipt;
        private Plan(JSONObject before,JSONObject state,JSONArray batch,JSONObject receipt)throws Exception{original=before.toString();this.before=hash(before);this.state=state.toString();this.batch=batch.toString();this.receipt=receipt.put("executionAuthorized",false).put("dataOnly",true).toString();}
        public JSONArray intentIds()throws Exception{return new JSONArray(batch);}
        public JSONObject receipt()throws Exception{return new JSONObject(receipt);}
        public boolean hasCandidate()throws Exception{return intentIds().length()>0;}
    }
    private TeamWakeQueue(){}

    /** Native owner review must be checked by TaskStore before this data transition. */
    public static Change configure(JSONObject task,Scope nativeScope,long expectedRevision,OwnerPolicy policy,long now,NativeClaims nativeClaims)throws Exception {
        nonnegative(now);
        JSONObject old=stored(task),state=state(task,nativeScope,nativeClaims);long revision=state.optLong("policyRevision",0);
        if(expectedRevision!=revision)throw new IllegalStateException("原唤醒策略版本已变化");
        if(policy.expiresAt()<=now||policy.expiresAt()-now>MAX_POLICY_MILLIS)throw new IllegalArgumentException("策略有效期须为原宿主当前时间之后且不超过24小时");
        JSONArray allowed=new JSONArray();for(var kind:SourceKind.values())if(policy.allowedSources().contains(kind))allowed.put(kind.name());
        state.put("policyRevision",Math.addExact(revision,1)).put("policy",new JSONObject().put("enabled",policy.enabled()).put("allowedSources",allowed)
            .put("perRoundLimit",policy.perRoundLimit()).put("cumulativeLimit",policy.cumulativeLimit()).put("expiresAt",policy.expiresAt()));
        // Changing policy never zeroes original SQL counters, clears an unknown or adopts old intents.
        return new Change(old,state,new JSONObject().put("policyRevision",revision+1).put("originalClaims",claims(nativeClaims)));
    }
    public static Change enqueue(JSONObject task,Message nativeMessage,long policyRevision,long now,NativeClaims nativeClaims)throws Exception {
        nonnegative(now);
        JSONObject old=stored(task),state=state(task,nativeMessage.scope(),nativeClaims);if(policyRevision!=state.getLong("policyRevision")||!state.has("policy"))throw new SecurityException("消息没有准确原生策略版本");
        JSONArray intents=state.getJSONArray("intents");String id=intentId(nativeMessage),binding=hash(message(nativeMessage));
        for(int i=0;i<intents.length();i++){JSONObject row=intents.getJSONObject(i);if(!id.equals(row.getString("id")))continue;
            if(!binding.equals(row.getString("bindingHash"))||policyRevision!=row.getLong("policyRevision"))throw new SecurityException("同一原消息身份已绑定不同来源、目标或代次");
            return new Change(old,state,new JSONObject().put("intent",row).put("duplicate",true).put("queryOnly",true));
        }
        if(intents.length()>=MAX_INTENTS)throw new IllegalStateException("原wake意图队列已满，不删除旧身份或unknown以重投");
        int admissionLimit=nativeMessage.sourceKind()==SourceKind.AUTHORIZED_CONTROL?MAX_INTENTS:nativeMessage.sourceKind()==SourceKind.OWNER_FOREGROUND?MAX_NON_CONTROL_INTENTS:MAX_PEER_BACKGROUND_INTENTS;
        if(intents.length()>=admissionLimit)throw new IllegalStateException("此原生来源的wake入队空间已满，保留主人/控制空间；旧意图和原attempt未删除");
        long sequence=state.getLong("nextSequence");state.put("nextSequence",Math.addExact(sequence,1));
        JSONObject row=new JSONObject().put("id",id).put("binding",message(nativeMessage)).put("bindingHash",binding).put("policyRevision",policyRevision)
            .put("sequence",sequence).put("createdAt",now).put("status","queued").put("reason","queued").put("escalationRecorded",false);
        intents.put(row);return new Change(old,state,new JSONObject().put("intent",row).put("duplicate",false));
    }
    /** Priority/FIFO decision only. The original Coordinator remains the sole scheduler. */
    public static Plan plan(JSONObject task,NativeView nativeView)throws Exception {
        JSONObject old=stored(task),state=state(task,nativeView.scope(),nativeView.claims());JSONArray intents=state.getJSONArray("intents");
        List<JSONObject> waiting=new ArrayList<>();JSONArray blocked=new JSONArray();boolean active=false;
        for(int i=0;i<intents.length();i++){JSONObject row=intents.getJSONObject(i);if(nativeView.target().memberId().equals(row.getJSONObject("binding").getJSONObject("target").getString("memberId"))&&inflight(row.getString("status"))){active=true;blocked.put(new JSONObject().put("intentId",row.getString("id")).put("reason","original_continuation_active_or_unknown").put("queryOriginalAttempt",row.getJSONObject("originalAttempt")));}}
        for(int i=0;i<intents.length();i++){
            JSONObject row=intents.getJSONObject(i);if(!nativeView.target().memberId().equals(row.getJSONObject("binding").getJSONObject("target").getString("memberId"))||!row.getString("status").matches("queued|blocked"))continue;
            String reason=reason(state,row,nativeView,active);if(reason.isEmpty()){row.put("status","queued").put("reason","ready");waiting.add(row);}else{
                row.put("status",reason.equals("expired")?"expired":"blocked").put("reason",reason);blocked.put(new JSONObject().put("intentId",row.getString("id")).put("reason",reason));
            }
        }
        waiting.sort(Comparator.comparingInt(TeamWakeQueue::priority).thenComparingLong(row->row.optLong("sequence")));
        JSONArray selected=new JSONArray();boolean command=false,isolated=false;int priority=-1;
        for(JSONObject row:waiting){JSONObject binding=row.getJSONObject("binding");boolean barrier=binding.getBoolean("nativeCommand")||binding.getBoolean("intervention")||SourceKind.valueOf(binding.getString("sourceKind"))==SourceKind.AUTHORIZED_CONTROL;
            if(selected.length()==0){priority=priority(row);command=binding.getBoolean("nativeCommand");isolated=barrier;selected.put(row.getString("id"));if(barrier)break;}
            else{if(priority(row)!=priority||barrier||selected.length()>=MAX_BATCH)break;selected.put(row.getString("id"));}
        }
        return new Plan(old,state,selected,new JSONObject().put("intentIds",selected).put("blocked",blocked).put("nativeCommand",command).put("isolatedBatch",isolated).put("priority",priority).put("queryOnly",true));
    }
    public static Change recordBlocked(Plan plan)throws Exception{return new Change(new JSONObject(plan.original),new JSONObject(plan.state),plan.receipt());}

    /** Called only after the ORIGINAL SQL owner has inserted/reserved its exact continuation
     * in the same transaction. Observed counts must include that original row exactly once. */
    public static Change recordOriginalClaim(JSONObject task,Plan plan,NativeView currentNative,OriginalAttempt original,NativeClaims afterOriginalSqlClaim)throws Exception {
        JSONObject old=stored(task);if(!plan.before.equals(hash(old)))throw new IllegalStateException("原wake计划已变化，未调度");JSONArray selected=plan.intentIds();if(selected.length()==0)throw new SecurityException("原wake计划没有安全候选");
        Plan current=plan(task,currentNative);if(!current.batch.equals(plan.batch))throw new SecurityException("原wake来源、身份、有效期或准入条件在claim前已变化");
        NativeClaims before=currentNative.claims();int round=currentNative.target().round();
        if(afterOriginalSqlClaim.total()!=before.total()+1||afterOriginalSqlClaim.inRound(round)!=before.inRound(round)+1||afterOriginalSqlClaim.digest().equals(before.digest()))throw new SecurityException("只有原SQL实际单次continuation计数可推进阈值");
        for(int r=1;r<=6;r++)if(r!=round&&afterOriginalSqlClaim.inRound(r)!=before.inRound(r))throw new SecurityException("原SQL其他轮次计数在claim时已变化");
        if(!original.childTaskId().equals(currentNative.target().childTaskId())||original.childEpoch()<currentNative.target().childEpoch()
                ||original.childEpoch()==currentNative.target().childEpoch()&&!original.childRunId().equals(currentNative.target().childRunId()))throw new SecurityException("唤醒只能继续同一个原child任务且不能复用旧epoch");
        JSONObject state=new JSONObject(current.state);state.put("nativeClaims",claims(afterOriginalSqlClaim));String batchId=UUID.nameUUIDFromBytes(("pocketworld.wake-batch.v1\0"+selected).getBytes(StandardCharsets.UTF_8)).toString();
        for(int i=0;i<state.getJSONArray("intents").length();i++){JSONObject row=state.getJSONArray("intents").getJSONObject(i);if(row.has("originalAttempt")&&original.operationId().equals(row.getJSONObject("originalAttempt").getString("operationId")))throw new SecurityException("原continuation操作已经属于另一wake批次，只能核对不能重投");}
        for(int i=0;i<selected.length();i++)find(state,selected.getString(i)).put("status","claimed").put("reason","original_continuation_claimed").put("batchId",batchId).put("originalAttempt",attempt(original));
        return new Change(old,state,new JSONObject().put("batchId",batchId).put("intentIds",selected).put("originalAttempt",attempt(original)).put("queryOnly",true).put("nativeDispatchProofRequired",true));
    }
    /** Native original outcome observation only; never starts/retries or consumes mailbox input. */
    public static Change observeOriginal(JSONObject task,Scope nativeScope,OriginalAttempt original,Outcome outcome,String receiptDigest,NativeClaims nativeClaims)throws Exception {
        sha(receiptDigest);JSONObject old=stored(task),state=state(task,nativeScope,nativeClaims);boolean found=false,duplicate=true;JSONArray intents=state.getJSONArray("intents"),selected=new JSONArray();
        for(int i=0;i<intents.length();i++){JSONObject row=intents.getJSONObject(i);if(!row.has("originalAttempt")||!original.operationId().equals(row.getJSONObject("originalAttempt").getString("operationId")))continue;
            if(!hash(attempt(original)).equals(hash(row.getJSONObject("originalAttempt"))))throw new SecurityException("原wake操作属于另一run/epoch/child");found=true;selected.put(row.getString("id"));String next=outcome.name().toLowerCase(Locale.ROOT),status=row.getString("status");
            if(status.matches("completed|failed|cancelled")){if(!status.equals(next)||!receiptDigest.equals(row.optString("receiptDigest")))throw new SecurityException("原wake终态回执不可被另一结果覆盖");continue;}
            if(!status.matches("claimed|started|unknown"))throw new SecurityException("没有原未终态continuation可核");duplicate=false;row.put("status",next).put("reason",outcome==Outcome.UNKNOWN?"original_outcome_unknown_query_only":"original_"+next).put("receiptDigest",receiptDigest);
        }
        if(!found)throw new SecurityException("原wake操作不存在，不能从JSON恢复新continuation");
        return new Change(old,state,new JSONObject().put("intentIds",selected).put("originalAttempt",attempt(original)).put("status",outcome.name().toLowerCase(Locale.ROOT)).put("duplicate",duplicate).put("queryOnly",true));
    }
    /** Process restore converts unobserved claims to query-only unknown, retaining SQL facts. */
    public static Change coldReconcile(JSONObject task,Scope nativeScope,NativeClaims nativeClaims)throws Exception {
        JSONObject old=stored(task),state=state(task,nativeScope,nativeClaims);JSONArray queries=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<state.getJSONArray("intents").length();i++){JSONObject row=state.getJSONArray("intents").getJSONObject(i);if(row.getString("status").matches("claimed|started|unknown")){row.put("status","unknown").put("reason","cold_restore_query_original_only");JSONObject attempt=row.getJSONObject("originalAttempt");if(seen.add(hash(attempt)))queries.put(new JSONObject(attempt.toString()));}}
        return new Change(old,state,new JSONObject().put("queryOriginalAttempts",queries).put("queryOnly",true));
    }
    /** One in-app native escalation candidate per original failed/unknown intent. No message is sent. */
    public static Change takeEscalation(JSONObject task,Scope nativeScope,String intentId,NativeClaims nativeClaims)throws Exception {
        JSONObject old=stored(task),state=state(task,nativeScope,nativeClaims),row=find(state,intentId);boolean eligible=row.getString("status").matches("failed|cancelled|unknown|expired");
        if(!eligible)throw new SecurityException("只有实际失败/未知/到期意图可生成本地升级候选");boolean duplicate=row.getBoolean("escalationRecorded");String escalation=row.optString("batchId",intentId);
        for(int i=0;i<state.getJSONArray("intents").length();i++){JSONObject related=state.getJSONArray("intents").getJSONObject(i);if(escalation.equals(related.optString("batchId",related.getString("id"))))related.put("escalationRecorded",true);}
        return new Change(old,state,new JSONObject().put("intentId",intentId).put("escalationId",escalation).put("escalationCandidate",!duplicate).put("duplicate",duplicate).put("reason",row.getString("reason")).put("queryOnly",true));
    }

    private static String reason(JSONObject state,JSONObject row,NativeView view,boolean active)throws Exception {
        JSONObject binding=row.getJSONObject("binding"),policy=state.optJSONObject("policy");if(!hash(scope(view.scope())).equals(hash(binding.getJSONObject("scope"))))return "stale_parent_run_epoch";
        if(!hash(target(view.target())).equals(hash(binding.getJSONObject("target"))))return "stale_target_identity_run_epoch";
        if(policy==null||!policy.getBoolean("enabled"))return "policy_disabled";
        if(row.getLong("policyRevision")!=state.getLong("policyRevision"))return "policy_revision_changed";
        if(view.now()>=policy.getLong("expiresAt"))return "expired";
        if(!contains(policy.getJSONArray("allowedSources"),binding.getString("sourceKind")))return "source_not_allowed";
        if(view.stopped())return "owner_stopped";if(view.ownerPaused())return "owner_paused";if(view.unknownEffects())return "original_effect_unknown";
        if(active)return "original_continuation_active_or_unknown";if(!view.safeIdle())return "not_safe_idle";
        SourceState source=view.sources().getOrDefault(binding.getString("sourceBindingHash"),SourceState.UNKNOWN);if(source!=SourceState.CURRENT)return source==SourceState.REVOKED?"source_revoked":"source_currentness_unknown";
        if(!view.budgetAvailable())return "original_budget_exhausted";
        if(view.claims().total()>=policy.getInt("cumulativeLimit"))return "cumulative_wake_limit";
        if(view.claims().inRound(view.target().round())>=policy.getInt("perRoundLimit"))return "round_wake_limit";return "";
    }
    private static JSONObject state(JSONObject task,Scope nativeScope,NativeClaims nativeClaims)throws Exception {
        JSONObject value=stored(task);if(value.length()==0)return new JSONObject().put("format",FORMAT).put("scope",scope(nativeScope)).put("policyRevision",0).put("nativeClaims",claims(nativeClaims)).put("nextSequence",1).put("intents",new JSONArray());
        JSONObject state=new JSONObject(value.toString());String check=state.getString("stateHash");state.remove("stateHash");if(!FORMAT.equals(state.getString("format"))||!hash(state).equals(check))throw new SecurityException("原wake持久事实格式或hash不可核对");
        if(!hash(state.getJSONObject("scope")).equals(hash(scope(nativeScope))))throw new SecurityException("原wake策略不能迁移到旧/其他parent run/epoch或预算池");
        if(!hash(state.getJSONObject("nativeClaims")).equals(hash(claims(nativeClaims))))throw new SecurityException("原wake累计事实必须与当前原SQL精确一致，不重置或猜测");
        JSONArray rows=state.getJSONArray("intents");if(rows.length()>MAX_INTENTS)throw new SecurityException("原wake记录超限，未截断");Set<String> ids=new HashSet<>();long last=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!ids.add(row.getString("id"))||row.getLong("sequence")<=last||!hash(row.getJSONObject("binding")).equals(row.getString("bindingHash")))throw new SecurityException("原wake身份、FIFO或绑定不可核对");last=row.getLong("sequence");}
        if(state.getLong("nextSequence")<=last)throw new SecurityException("原wake顺序号不可倒退");return state;
    }
    private static JSONObject stored(JSONObject task)throws Exception {if(!task.has(KEY))return new JSONObject();Object state=task.get(KEY);if(!(state instanceof JSONObject)||((JSONObject)state).length()==0)throw new SecurityException("原wake字段不可读，未覆盖");return new JSONObject(state.toString());}
    private static void seal(JSONObject state)throws Exception{state.remove("stateHash");state.put("stateHash",hash(state));}
    private static JSONObject scope(Scope s)throws Exception{return new JSONObject().put("teamRunId",s.teamRunId()).put("parentTaskId",s.parentTaskId()).put("parentRunId",s.parentRunId()).put("parentEpoch",s.parentEpoch()).put("parentBudgetRef",s.parentBudgetRef());}
    private static JSONObject target(Target t)throws Exception{return new JSONObject().put("memberId",t.memberId()).put("identityRevision",t.identityRevision()).put("runGeneration",t.runGeneration()).put("memberGeneration",t.memberGeneration()).put("round",t.round()).put("childTaskId",t.childTaskId()).put("childRunId",t.childRunId()).put("childEpoch",t.childEpoch()).put("bindingHash",t.bindingHash());}
    private static JSONObject message(Message m)throws Exception{return new JSONObject().put("scope",scope(m.scope())).put("target",target(m.target())).put("messageId",m.messageId()).put("payloadHash",m.payloadHash()).put("sourceBindingHash",m.sourceBindingHash()).put("sourceKind",m.sourceKind().name()).put("nativeCommand",m.nativeCommand()).put("intervention",m.intervention());}
    private static JSONObject claims(NativeClaims c)throws Exception{JSONObject rounds=new JSONObject();for(int r=1;r<=6;r++)rounds.put(Integer.toString(r),c.inRound(r));return new JSONObject().put("total",c.total()).put("rounds",rounds).put("digest",c.digest());}
    private static JSONObject attempt(OriginalAttempt a)throws Exception{return new JSONObject().put("operationId",a.operationId()).put("childTaskId",a.childTaskId()).put("childRunId",a.childRunId()).put("childEpoch",a.childEpoch());}
    private static String intentId(Message m){return UUID.nameUUIDFromBytes(("pocketworld.wake-intent.v1\0"+m.scope().teamRunId()+"\0"+m.target().memberId()+"\0"+m.messageId()).getBytes(StandardCharsets.UTF_8)).toString();}
    private static JSONObject find(JSONObject state,String id)throws Exception{JSONArray rows=state.getJSONArray("intents");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);throw new SecurityException("原wake意图不存在");}
    private static int priority(JSONObject row){return SourceKind.valueOf(row.optJSONObject("binding").optString("sourceKind")).ordinal();}
    private static boolean inflight(String state){return state.matches("claimed|started|unknown");}
    private static boolean contains(JSONArray rows,String value)throws Exception{for(int i=0;i<rows.length();i++)if(value.equals(rows.getString(i)))return true;return false;}
    private static void uuid(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(IllegalArgumentException bad){throw new SecurityException("需要原生固定UUID身份");}}
    private static void member(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("原成员ID无效");}
    private static void sha(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("需要原生固定SHA事实");}
    private static void positive(int n){if(n<1)throw new IllegalArgumentException("原身份修订须为正整数");}
    private static void nonnegative(long n){if(n<0)throw new IllegalArgumentException("原代次/时间不可为负");}
    private static String hash(JSONObject value)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String canonical(Object value)throws Exception{if(value instanceof JSONObject o){TreeSet<String> keys=new TreeSet<>();o.keys().forEachRemaining(keys::add);StringJoiner out=new StringJoiner(",","{","}");for(String key:keys)out.add(JSONObject.quote(key)+":"+canonical(o.get(key)));return out.toString();}if(value instanceof JSONArray a){StringJoiner out=new StringJoiner(",","[","]");for(int i=0;i<a.length();i++)out.add(canonical(a.get(i)));return out.toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
}
