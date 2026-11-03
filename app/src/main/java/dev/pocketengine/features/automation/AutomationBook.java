package dev.pocketengine.features.automation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persist claims before delivery. Unknown delivery is retained and never automatically replayed. */
public final class AutomationBook {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final File file;
    private final Object lock;
    private final GoalTaskResolver goalTasks;
    private final AutomationConditions.Source conditions;
    /** Read the original TaskStore task only. Native facts, never caller/model assertions. */
    public interface GoalTaskResolver {
        JSONObject resolve(String occurrenceId)throws Exception;
        /** Existing native provenance transaction only; default absence never grants source use. */
        default JSONObject withOriginalScheduleSource(JSONObject origin,java.util.concurrent.Callable<JSONObject> bookAction)throws Exception {throw new ScheduleSourceState.Unavailable();}
    }
    public AutomationBook(File file)throws Exception {this(file,null);}
    public AutomationBook(File file,GoalTaskResolver resolver)throws Exception {this(file,resolver,null);}
    public AutomationBook(File file,GoalTaskResolver resolver,AutomationConditions.Source conditions)throws Exception {this.file=file;goalTasks=resolver;this.conditions=conditions;lock=LOCKS.computeIfAbsent(file.getCanonicalPath(),key->new Object());}

    /** Body-free origin inventory. No resolver/callback is entered under the Book monitor. */
    public JSONArray nativeScheduleOrigins()throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray result=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
            for(String section:new String[]{"rules","proposals"}){JSONArray rows=state.optJSONArray(section);if(rows==null)continue;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),origin=row.optJSONObject(section.equals("rules")?"nativeOrigin":"origin");if(origin!=null&&!row.optBoolean("sourceInvalidated")&&seen.add(proposalOriginKey(origin)))result.put(copy(origin));}}return result;
        }
    }
    private static String proposalOriginKey(JSONObject origin)throws Exception{return new JSONArray().put(origin.optString("ownerId")).put(origin.optString("spaceId")).put(origin.optString("taskId")).put(origin.optString("conversationId")).put(origin.optString("runId")).put(origin.optInt("epoch",-1)).toString();}
    public JSONObject receiptNativeOrigin(String id)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray rows=state.getJSONArray("receipts");int at=indexOf(rows,id);if(at<0)throw new IllegalArgumentException("原提醒不存在");JSONObject receipt=rows.getJSONObject(at),origin=receipt.optJSONObject("nativeOrigin");
            // Older original receipts may omit the carrier. Only their exact original
            // rule/proposal ID can supply provenance, never a caller-supplied origin.
            for(String section:new String[]{"rules","proposals"})if(origin==null){JSONArray parents=state.optJSONArray(section);int parent=parents==null?-1:indexOf(parents,receipt.getString("ruleId"));if(parent>=0)origin=parents.getJSONObject(parent).optJSONObject(section.equals("rules")?"nativeOrigin":"origin");}
            return origin==null?null:copy(origin);
        }
    }

    /** Retryable quarantine in original rows. No body/revision/enablement/digest is destroyed
     * or reauthorized; the same occurrence remains unclaimed until proof is available. */
    public void nativeScheduleSourcePending(JSONObject original,boolean pending,long now)throws Exception {
        synchronized(lock){JSONObject state=read();String key=proposalOriginKey(original);boolean changed=false;java.util.Set<String> ids=new java.util.HashSet<>();
            for(String section:new String[]{"rules","proposals"}){JSONArray rows=state.optJSONArray(section);for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i),origin=row.optJSONObject(section.equals("rules")?"nativeOrigin":"origin");if(origin!=null&&key.equals(proposalOriginKey(origin)))ids.add(row.getString("id"));}}
            for(String section:new String[]{"rules","receipts","proposals"}){JSONArray rows=state.optJSONArray(section);for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i),origin=row.optJSONObject(section.equals("proposals")?"origin":"nativeOrigin");boolean belongs=origin!=null&&key.equals(proposalOriginKey(origin))||section.equals("receipts")&&ids.contains(row.optString("ruleId"));if(!belongs||row.optBoolean("sourceInvalidated")||row.optBoolean("sourceCheckPending")==pending&&(!pending||row.optLong("sourceRetryAt")>now))continue;
                row.put("sourceCheckPending",pending);if(pending){row.put("sourceRetryAt",Math.min(AutomationPolicy.MAX_TIME,now+60_000L));if(section.equals("receipts"))row.put("notificationClearPending",true);}else{row.remove("sourceRetryAt");if(section.equals("receipts")&&row.optBoolean("sourceDispatchDeferred")){row.put("dispatchAfter",now);row.remove("sourceDispatchDeferred");}}changed=true;
            }}if(changed)write(state);
        }
    }
    /** Called only before the original notification IO has been entered. Reuses the
     * same receipt; an already published/unknown notification can never enter this path. */
    public void deferSourceUnavailableReceipt(String id,long now)throws Exception {synchronized(lock){JSONObject state=read();JSONArray rows=state.getJSONArray("receipts");int at=indexOf(rows,id);if(at<0)return;JSONObject row=rows.getJSONObject(at);if(!"dispatching".equals(row.optString("status")))return;row.put("status","deferred").put("sourceDispatchDeferred",true).put("dispatchAfter",Math.min(AutomationPolicy.MAX_TIME,now+60_000L)).put("message","原来源暂时无法核对，通知未派发，保留本次原发生").put("updatedAt",now);write(state);}}
    /** Sticky negative fact in the ORIGINAL rule/receipt/proposal rows. IDs/audit survive,
     * old title/goal/draft do not. No new rule, approval, queue or dispatch is created. */
    public JSONObject revokeNativeScheduleOrigin(JSONObject original,long now)throws Exception {
        synchronized(lock){JSONObject state=read();String key=proposalOriginKey(original);boolean changed=false;int affected=0;java.util.Set<String> nativeRuleIds=new java.util.HashSet<>();
            for(String section:new String[]{"rules","proposals"}){JSONArray rows=state.optJSONArray(section);for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i),origin=row.optJSONObject(section.equals("rules")?"nativeOrigin":"origin");if(origin!=null&&key.equals(proposalOriginKey(origin)))nativeRuleIds.add(row.getString("id"));}}
            for(String section:new String[]{"rules","receipts","proposals"}){JSONArray rows=state.optJSONArray(section);if(rows==null)continue;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),origin=row.optJSONObject(section.equals("proposals")?"origin":"nativeOrigin");boolean belongs=origin!=null&&key.equals(proposalOriginKey(origin))||section.equals("receipts")&&nativeRuleIds.contains(row.optString("ruleId"));if(!belongs||row.optBoolean("sourceInvalidated"))continue;
                row.put("sourceInvalidated",true).put("sourceAvailable",false).put("sourceCheckPending",false).put("executionAuthorized",false).put("updatedAt",now);row.remove("sourceRetryAt");row.remove("sourceDispatchDeferred");row.remove("draft");row.remove("goal");row.remove("title");
                if(section.equals("rules")){row.put("title","原来源不可用").put("goal","").put("enabled",false).put("taskRequestsRevoked",true).put("nextAt",JSONObject.NULL).put("ruleState","source_unavailable").put("revision",row.getInt("revision")+1);}
                else if(section.equals("receipts")){row.put("title","原来源不可用").put("goal","").put("status","cancelled").put("message","原来源已撤回或不可核对，旧提醒已撤销").put("notificationClearPending",true);}
                else row.put("status","source_unavailable");changed=true;affected++;
            }}if(changed)write(state);return new JSONObject().put("revokedRows",affected).put("bodyRetained",false).put("executionAuthorized",false);
        }
    }
    public JSONArray sourceNotificationClears()throws Exception {synchronized(lock){JSONArray result=new JSONArray(),rows=read().getJSONArray("receipts");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optBoolean("notificationClearPending"))result.put(rows.getJSONObject(i).getString("id"));return result;}}
    public void sourceNotificationsCleared(JSONArray exactIds)throws Exception {synchronized(lock){JSONObject state=read();JSONArray rows=state.getJSONArray("receipts");boolean changed=false;for(int i=0;i<exactIds.length();i++){int at=indexOf(rows,exactIds.getString(i));if(at>=0&&rows.getJSONObject(at).optBoolean("notificationClearPending")){rows.getJSONObject(at).put("notificationClearPending",false);changed=true;}}if(changed)write(state);}}
    public JSONObject save(JSONObject input,long now)throws Exception {return save(input,now,null,null);}
    private JSONObject save(JSONObject input,long now,String forcedId,JSONObject nativeOrigin)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray rules=state.getJSONArray("rules");String id=forcedId==null?input.optString("id"):forcedId;int index=indexOf(rules,id);
            JSONObject old=index<0?null:rules.getJSONObject(index);if(old!=null&&old.optBoolean("sourceInvalidated"))throw new SecurityException("旧来源已撤回，不能修改或恢复此规则，请重新明确提出");
            if(!id.isEmpty()&&old==null&&forcedId==null)throw new IllegalArgumentException("规则不存在，请刷新后重试");
            if(old==null&&rules.length()>=100)throw new IllegalStateException("最多保存100条规则");
            if(old!=null&&input.has("revision")&&input.optInt("revision",-1)!=old.getInt("revision"))throw new IllegalStateException("规则已被修改，请刷新后再保存");
            String title=text(input.optString("title"),1,200,"提醒名称");String kind=input.optString("kind",old==null?"reminder":old.optString("kind"));
            if(!kind.equals("reminder")&&!kind.equals("phone_goal"))throw new IllegalArgumentException("未知规则类型");
            String goal=kind.equals("phone_goal")?text(input.optString("goal",old==null?"":old.optString("goal")),1,4000,"手机目标"):"";
            JSONObject schedule=validateSchedule(input.getJSONObject("schedule"));boolean enabled=input.optBoolean("enabled",old==null||old.optBoolean("enabled"));
            JSONObject policy=AutomationPolicy.rule(input.has("policy")?input.getJSONObject("policy"):old==null?null:old.optJSONObject("policy"),schedule.optString("zone","UTC"));
            JSONObject rule=new JSONObject().put("id",old==null?(forcedId==null?UUID.randomUUID().toString():forcedId):id).put("title",title).put("kind",kind).put("goal",goal)
                    .put("schedule",schedule).put("enabled",enabled).put("revision",old==null?1:old.getInt("revision")+1)
                    .put("createdAt",old==null?now:old.getLong("createdAt")).put("updatedAt",now)
                    .put("policy",policy).put("claimedCount",old==null?0:claimedCount(state,old))
                    .put("taskRequestsRevoked",!enabled).put("executionPolicy","notify_then_user_start");
            if(nativeOrigin!=null)rule.put("nativeOrigin",copy(nativeOrigin));else if(old!=null&&old.optJSONObject("nativeOrigin")!=null)rule.put("nativeOrigin",copy(old.getJSONObject("nativeOrigin")));
            arm(rule,initialAt(schedule,now));
            if(index<0)rules.put(rule);else{rules.put(index,rule);cancelPending(state,id,"规则已修改，旧版本未投递的提醒已取消",now);}write(state);return copy(rule);
        }
    }
    /** A conversation proposal is inert until native owner review. Stored in the original book. */
    public JSONObject proposeSchedule(JSONObject nativeOrigin,String nativeOperation,JSONObject requested,long now)throws Exception {
        if(requested==null)throw new IllegalArgumentException("请提供原计划草案");if(requested.has("semanticCondition"))throw new SecurityException("当前普通计划入口未接入语义条件；未创建或降级为无条件目标");
        if(nativeOrigin==null||nativeOrigin.length()!=6||!"owner".equals(nativeOrigin.optString("ownerId"))||!"private".equals(nativeOrigin.optString("spaceId"))||!nativeOrigin.optString("taskId").matches("[a-f0-9-]{36}")||nativeOrigin.optString("conversationId").isBlank()||nativeOrigin.optString("conversationId").length()>160||!nativeOrigin.optString("runId").matches("[a-f0-9-]{36}")||!(nativeOrigin.opt("epoch") instanceof Integer)||nativeOrigin.optInt("epoch")<1||nativeOperation==null||!nativeOperation.matches("[a-f0-9-]{36}"))throw new SecurityException("计划草案缺原本机对话身份");
        for(var keys=requested.keys();keys.hasNext();)if(!java.util.Set.of("title","kind","goal","schedule").contains(keys.next()))throw new SecurityException("计划提议不能携带批准、权限或替代来源");
        JSONObject draft=new JSONObject().put("title",text(requested.optString("title"),1,200,"提醒名称")).put("kind",requested.optString("kind","reminder"));
        if(!java.util.Set.of("reminder","phone_goal").contains(draft.getString("kind")))throw new IllegalArgumentException("计划类型无效");
        if(draft.getString("kind").equals("phone_goal"))draft.put("goal",text(requested.optString("goal"),1,4000,"手机目标"));
        draft.put("schedule",validateSchedule(requested.getJSONObject("schedule")));
        String digest=proposalDigest(nativeOrigin,draft),identity=nativeOrigin.getString("taskId")+":"+nativeOperation;
        synchronized(lock){JSONObject state=read();JSONArray proposals=state.optJSONArray("proposals");if(proposals==null){proposals=new JSONArray();state.put("proposals",proposals);}
            for(int i=0;i<proposals.length();i++){JSONObject prior=proposals.getJSONObject(i);if(identity.equals(prior.optString("identity"))){if(!digest.equals(prior.getString("digest")))throw new SecurityException("同一个计划提议操作不能更换内容或原对话");return copy(prior);}}
            if(proposals.length()>=100)throw new IllegalStateException("待审及历史计划草案已达100项，请先在原管理入口核对");
            JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("identity",identity).put("origin",copy(nativeOrigin)).put("draft",draft).put("digest",digest).put("status","pending").put("createdAt",now).put("ruleCreationEntered",false).put("ruleCreationState","not_entered").put("executionAuthorized",false);proposals.put(row);write(state);return copy(row);
        }
    }
    public JSONArray scheduleProposals(String originalTask)throws Exception {
        synchronized(lock){JSONArray rows=read().optJSONArray("proposals"),result=new JSONArray();for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(originalTask==null||originalTask.equals(row.getJSONObject("origin").optString("taskId")))result.put(copy(row));}return result;}
    }
    /** The exact displayed digest is approved by the existing native owner window only. */
    public JSONObject approveScheduleProposal(String proposal,String digest,long now)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray rows=state.optJSONArray("proposals");int at=rows==null?-1:indexOf(rows,proposal);if(at<0)throw new IllegalArgumentException("计划草案不存在");JSONObject row=rows.getJSONObject(at);
            reviewedProposalForDisplay(row);
            if(!digest.equals(row.getString("digest"))||!digest.equals(proposalDigest(row.getJSONObject("origin"),row.getJSONObject("draft"))))throw new SecurityException("显示的计划草案已变化");
            JSONObject exactSchedule=row.getJSONObject("draft").getJSONObject("schedule");if(AutomationSchedule.nextAt(exactSchedule,Math.max(0,now-1))<0)throw new IllegalStateException("草案时间已过或日历已耗尽，请在原对话明确重新提出安排");
            if(!row.getString("status").matches("pending|approved"))throw new SecurityException("此草案不能批准");
            JSONObject rule=null;JSONArray rules=state.getJSONArray("rules");int existing=indexOf(rules,proposal);
            if(existing>=0){rule=rules.getJSONObject(existing);requireOriginalProposalRule(row,rule);if("approved".equals(row.getString("status")))return new JSONObject().put("proposal",copy(row)).put("rule",copy(rule)).put("duplicate",true).put("executionAuthorized",false);}
            else{
                if(!Boolean.FALSE.equals(row.opt("ruleCreationEntered"))||!"not_entered".equals(row.optString("ruleCreationState"))||"approved".equals(row.getString("status")))throw new SecurityException("原计划创建已进入、已撤回或未确认；不能用旧草案重建");
                // Persist in this SAME original proposal before the first rule write. Any
                // later missing/uncertain rule is query-only, never a new creation attempt.
                row.put("ruleCreationEntered",true).put("ruleCreationState","unknown").put("ruleCreationEnteredAt",now);write(state);
                rule=save(row.getJSONObject("draft"),now,proposal,row.getJSONObject("origin"));state=read();rows=state.getJSONArray("proposals");at=indexOf(rows,proposal);row=rows.getJSONObject(at);
            }
            row.put("status","approved").put("ruleId",rule.getString("id")).put("approvedAt",now).put("ruleCreationEntered",true).put("ruleCreationState","observed_original_rule");write(state);return new JSONObject().put("proposal",copy(row)).put("rule",copy(rule)).put("duplicate",false).put("executionAuthorized",false);
        }
    }
    private static void requireOriginalProposalRule(JSONObject row,JSONObject rule)throws Exception {
        JSONObject draft=row.getJSONObject("draft"),origin=rule.optJSONObject("nativeOrigin");
        if(!sameInteger(rule.opt("revision"),1)||!Boolean.TRUE.equals(rule.opt("enabled"))||!Boolean.FALSE.equals(rule.opt("taskRequestsRevoked"))||!"notify_then_user_start".equals(rule.optString("executionPolicy"))||rule.has("semanticCondition")||origin==null
                ||!canonicalObject(row.getJSONObject("origin")).equals(canonicalObject(origin))||!draft.getString("title").equals(rule.optString("title"))||!draft.getString("kind").equals(rule.optString("kind"))||!draft.optString("goal").equals(rule.optString("goal"))||!canonicalObject(draft.getJSONObject("schedule")).equals(canonicalObject(rule.getJSONObject("schedule"))))throw new SecurityException("原规则的内容、版本、来源或撤回状态已变化，旧草案不能批准新目标");
    }
    /** Pure display integrity only, not owner approval or task/source authority. */
    public static JSONObject reviewedProposalForDisplay(JSONObject submitted)throws Exception{
        if(submitted==null||submitted.optBoolean("sourceInvalidated"))throw new SecurityException("原草案来源已撤回，不显示或恢复旧正文");
        JSONObject row=copy(submitted),origin=row.getJSONObject("origin"),draft=row.getJSONObject("draft");
        if(draft.has("semanticCondition"))throw new SecurityException("当前普通计划入口不能审阅或执行语义条件草案");
        if(!row.optString("id").matches("[a-f0-9-]{36}")||!row.optString("status").matches("pending|approved")||origin.length()!=6||!"owner".equals(origin.optString("ownerId"))||!"private".equals(origin.optString("spaceId"))||!origin.optString("taskId").matches("[a-f0-9-]{36}")||!origin.optString("runId").matches("[a-f0-9-]{36}")||!(origin.opt("epoch") instanceof Integer)||origin.optInt("epoch")<1||origin.optString("conversationId").isBlank()||origin.optString("conversationId").length()>160)throw new SecurityException("草案缺准确原对话执行身份，请在原对话明确重提");
        for(var keys=draft.keys();keys.hasNext();)if(!java.util.Set.of("title","kind","goal","schedule").contains(keys.next()))throw new SecurityException("草案包含未审阅字段");
        text(draft.optString("title"),1,200,"提醒名称");if(!java.util.Set.of("reminder","phone_goal").contains(draft.optString("kind")))throw new SecurityException("草案类型无效");if("phone_goal".equals(draft.optString("kind")))text(draft.optString("goal"),1,4000,"原目标");
        JSONObject normalized=validateSchedule(draft.getJSONObject("schedule"));if(!canonicalObject(normalized).equals(canonicalObject(draft.getJSONObject("schedule"))))throw new SecurityException("草案日历定义不是原规范化版本");
        if(!row.optString("digest").matches("[a-f0-9]{64}")||!row.getString("digest").equals(proposalDigest(origin,draft)))throw new SecurityException("草案显示内容与原digest不符，请重新读取");return row;
    }
    private static String proposalDigest(JSONObject origin,JSONObject draft)throws Exception {
        // Fixed field order is reconstructed from validated semantic data, independent of JSON map order.
        String value=new JSONArray().put(origin.getString("ownerId")).put(origin.getString("spaceId")).put(origin.getString("taskId")).put(origin.getString("conversationId")).put(origin.getString("runId")).put(origin.getInt("epoch")).put(draft.getString("title")).put(draft.getString("kind")).put(draft.optString("goal")).put(canonicalObject(draft.getJSONObject("schedule"))).put(JSONObject.NULL).toString();
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private static String canonicalObject(JSONObject value)throws Exception{java.util.List<String> keys=new java.util.ArrayList<>();value.keys().forEachRemaining(keys::add);keys.sort(String::compareTo);JSONArray result=new JSONArray();for(String key:keys)result.put(new JSONArray().put(key).put(value.get(key)));return result.toString();}
    public JSONObject setEnabled(String id,boolean enabled,long now)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray rules=state.getJSONArray("rules");int index=indexOf(rules,id);
            if(index<0)throw new IllegalArgumentException("规则不存在");JSONObject rule=rules.getJSONObject(index);if(enabled&&rule.optBoolean("sourceInvalidated"))throw new SecurityException("旧来源规则已撤回，不能重新启用");
            // Once rules are disabled at claim time. An explicit pause must still cancel their
            // outstanding claim instead of returning early and delivering an obsolete goal.
            if(rule.getBoolean("enabled")==enabled){if(!enabled){boolean changed=cancelPending(state,id,"规则已暂停，尚未投递的提醒已取消",now);if(!rule.optBoolean("taskRequestsRevoked")){rule.put("taskRequestsRevoked",true).put("revision",rule.getInt("revision")+1).put("updatedAt",now);changed=true;}if(changed)write(state);}return copy(rule);}
            rule.put("enabled",enabled).put("taskRequestsRevoked",!enabled).put("revision",rule.getInt("revision")+1).put("updatedAt",now)
                    .put("claimedCount",claimedCount(state,rule));
            arm(rule,initialAt(rule.getJSONObject("schedule"),now));
            if(!enabled)cancelPending(state,id,"规则已暂停，尚未投递的提醒已取消",now);
            write(state);return copy(rule);
        }
    }
    public JSONArray delete(String id)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray rules=state.getJSONArray("rules"),receipts=state.getJSONArray("receipts");int index=indexOf(rules,id);
            if(index<0)throw new IllegalArgumentException("规则不存在");rules.remove(index);JSONArray removed=new JSONArray(),kept=new JSONArray();
            for(int i=0;i<receipts.length();i++){JSONObject value=receipts.getJSONObject(i);if(id.equals(value.optString("ruleId")))removed.put(value);else kept.put(value);}
            state.put("receipts",kept);write(state);return removed;
        }
    }
    public JSONArray rules()throws Exception{synchronized(lock){return read().getJSONArray("rules");}}
    public JSONArray receipts()throws Exception{
        JSONArray values;synchronized(lock){values=read().getJSONArray("receipts");}
        // TaskStore callbacks are deliberately outside the rule lock.
        for(int i=0;i<values.length();i++){JSONObject receipt=values.getJSONObject(i);if("phone_goal".equals(receipt.optString("kind")))receipt.put("goalState",goalState(receipt));}
        return values;
    }
    public JSONObject goalState(String occurrenceId)throws Exception {
        JSONObject receipt;synchronized(lock){JSONObject state=read();int at=indexOf(state.getJSONArray("receipts"),occurrenceId);if(at<0)throw new IllegalArgumentException("提醒记录不存在");receipt=copy(state.getJSONArray("receipts").getJSONObject(at));}
        if(!"phone_goal".equals(receipt.optString("kind")))throw new IllegalArgumentException("此提醒没有手机目标");
        return goalState(receipt);
    }
    public JSONObject proactivityState(long now)throws Exception {
        JSONObject state;synchronized(lock){state=read();}
        JSONArray rules=state.getJSONArray("rules"),receipts=state.getJSONArray("receipts"),summary=new JSONArray(),goals=new JSONArray();long next=-1;
        for(int i=0;i<rules.length();i++){
            JSONObject rule=rules.getJSONObject(i),policy=policy(rule);String status=rule.optString("ruleState",rule.optBoolean("enabled")?"scheduled":"paused");
            long count=claimedCount(state,rule);
            if(!policy.isNull("expiresAt")&&now>=policy.getLong("expiresAt"))status="expired";
            else if(!policy.isNull("maxOccurrences")&&count>=policy.getLong("maxOccurrences"))status="budget_exhausted";
            else if(rule.optBoolean("enabled")&&!rule.isNull("nextAt")&&rule.getLong("nextAt")<=now)status="due";
            if(rule.optBoolean("enabled")&&!rule.isNull("nextAt")){long at=rule.getLong("nextAt");next=next<0?at:Math.min(next,at);}
            summary.put(new JSONObject().put("ruleId",rule.getString("id")).put("state",status).put("enabled",rule.optBoolean("enabled"))
                    .put("claimedCount",count).put("maxOccurrences",policy.opt("maxOccurrences")).put("nextAt",rule.opt("nextAt")));
        }
        int totalGoals=0;
        for(int i=receipts.length()-1;i>=0;i--){JSONObject receipt=receipts.getJSONObject(i);
            if("deferred".equals(receipt.optString("status"))){long at=receipt.getLong("dispatchAfter");next=next<0?at:Math.min(next,at);}
            if("phone_goal".equals(receipt.optString("kind"))){totalGoals++;if(goals.length()<20)goals.put(goalState(receipt));}
        }
        return new JSONObject().put("rules",summary).put("recentGoalStates",goals).put("goalStatesTruncated",totalGoals>20)
                .put("nextWakeAt",next<0?JSONObject.NULL:next).put("timing","inexact_best_effort").put("executionAuthorized",false);
    }
    private JSONObject goalState(JSONObject receipt)throws Exception {
        JSONObject result=new JSONObject().put("occurrenceId",receipt.getString("id")).put("executionAuthorized",false).put("authority","TaskStore");
        if(goalTasks==null)return result.put("state","host_evidence_unavailable");
        JSONObject task;
        try{task=goalTasks.resolve(receipt.getString("id"));}catch(Exception unavailable){return result.put("state","host_evidence_unavailable");}
        if(task==null){String status=receipt.optString("status");return result.put("state",status.matches("expired|missed_window|cancelled|budget_exhausted|deferred|delivery_unknown")?status:"dispatching".equals(status)?"notification_pending":"awaiting_user");}
        if(!receipt.getString("id").equals(task.optString("occurrenceId"))||!receipt.getString("ruleId").equals(task.optString("ruleId"))
                ||!sameInteger(task.opt("ruleRevision"),receipt.getLong("revision"))||!receipt.getString("goal").equals(task.optString("goal"))
                ||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optString("taskId").trim().isEmpty())
            return result.put("state","evidence_mismatch");
        String status=task.optString("status");
        result.put("taskId",task.getString("taskId")).put("taskStatus",status).put("authorizationValid",Boolean.TRUE.equals(task.opt("authorizationValid")));
        if("completed".equals(status)){
            JSONObject evidence=task.optJSONObject("evidenceRef");
            if(!Boolean.TRUE.equals(task.opt("resultVerified"))||evidence==null||!evidence.optString("kind").matches("owner_confirmation|task_result")||evidence.optString("id").trim().isEmpty())return result.put("state","unverified_result");
            return result.put("state","succeeded").put("evidenceRef",new JSONObject().put("kind",evidence.getString("kind")).put("id",evidence.getString("id")));
        }
        if(status.matches("failed|cancelled|reconciling|paused|waiting_user|awaiting_user|stopping"))return result.put("state",status);
        return result.put("state",Boolean.TRUE.equals(task.opt("authorizationValid"))&&status.matches("running|observing|planning|acting")?"running":"awaiting_authorization");
    }
    /** Read a durable occurrence for host admission. This grants no screen/network execution. */
    public JSONObject taskRequest(String occurrenceId,long now)throws Exception {
        synchronized(lock){JSONObject state=read();int position=indexOf(state.getJSONArray("receipts"),occurrenceId);if(position<0)throw new IllegalArgumentException("提醒已删除或不在当前记录中");
            JSONObject receipt=state.getJSONArray("receipts").getJSONObject(position);if(!receipt.optString("kind").equals("phone_goal"))throw new IllegalArgumentException("此提醒没有手机任务目标");
            if(!receipt.optString("status").matches("awaiting_user|blocked_notifications|delivery_unknown"))throw new IllegalStateException("此提醒尚未交由机主处理或已取消");
            int ruleIndex=indexOf(state.getJSONArray("rules"),receipt.getString("ruleId"));if(ruleIndex<0)throw new IllegalStateException("提醒规则已删除");JSONObject rule=state.getJSONArray("rules").getJSONObject(ruleIndex);
            if(rule.getInt("revision")!=receipt.getInt("revision")||!rule.getString("goal").equals(receipt.getString("goal")))throw new IllegalStateException("提醒规则已更新，旧目标不能继续授权");
            if(rule.optBoolean("sourceCheckPending"))throw new ScheduleSourceState.Unavailable();
            if(rule.optBoolean("taskRequestsRevoked"))throw new IllegalStateException("提醒目标授权已撤销，不能由旧提醒启动执行");
            if(!rule.optBoolean("enabled")&&!rule.getJSONObject("schedule").getString("type").equals("once"))throw new IllegalStateException("提醒规则已暂停，不能由旧提醒启动执行");
            if(receipt.getLong("occurrenceAt")>now)throw new IllegalStateException("提醒尚未到期");
            JSONObject policy=policy(rule);long deadline=AutomationPolicy.deadline(policy,receipt.getLong("occurrenceAt"));
            if(now>=deadline)throw new IllegalStateException("提醒目标已过有效窗口，不能继续旧目标");
            if(AutomationPolicy.quietEnd(policy,now)>now)throw new IllegalStateException("当前处于安静时段，请在时段结束后重新核对目标");
            if(!policy.isNull("maxOccurrences")&&receipt.optLong("occurrenceNumber",1)>policy.getLong("maxOccurrences"))throw new IllegalStateException("此提醒超出规则次数预算");
            JSONObject condition=AutomationConditions.evaluate(policy,conditions,now);
            if(!condition.getBoolean("allowed"))throw new IllegalStateException("当前运行条件不满足，旧目标未获准执行："+condition.getJSONArray("blockers"));
            JSONObject request=new JSONObject().put("occurrenceId",receipt.getString("id")).put("ruleId",rule.getString("id")).put("ruleRevision",rule.getInt("revision"))
                    .put("revision",rule.getInt("revision")).put("overlapPolicy",policy.getString("overlap")).put("validUntil",deadline)
                    .put("goal",receipt.getString("goal")).put("title",receipt.getString("title")).put("actorId","owner").put("spaceId","private")
                    .put("origin",new JSONObject().put("kind","automation").put("occurrenceId",receipt.getString("id"))).put("requiresHostAdmission",true).put("executionAuthorized",false);
            JSONObject nativeOrigin=receipt.optJSONObject("nativeOrigin");if(nativeOrigin!=null)request.getJSONObject("origin").put("originConversationId",nativeOrigin.getString("conversationId")).put("originTaskId",nativeOrigin.getString("taskId")).put("nativeOrigin",copy(nativeOrigin));return request;
        }
    }
    public long nextAt()throws Exception {
        synchronized(lock){long earliest=Long.MAX_VALUE;JSONObject state=read();JSONArray rules=state.getJSONArray("rules");
            for(int i=0;i<rules.length();i++){JSONObject rule=rules.getJSONObject(i);if(rule.optBoolean("enabled")&&!rule.isNull("nextAt"))earliest=Math.min(earliest,rule.optBoolean("sourceCheckPending")?Math.max(rule.getLong("nextAt"),Math.max(System.currentTimeMillis()+1000,rule.optLong("sourceRetryAt"))):rule.getLong("nextAt"));}
            JSONArray receipts=state.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if("deferred".equals(receipt.optString("status")))earliest=Math.min(earliest,receipt.optBoolean("sourceCheckPending")?Math.max(receipt.getLong("dispatchAfter"),Math.max(System.currentTimeMillis()+1000,receipt.optLong("sourceRetryAt"))):receipt.getLong("dispatchAfter"));}
            return earliest==Long.MAX_VALUE?-1:earliest;
        }
    }
    /** Resolve original goal tasks outside the rule lock. Missing/changed evidence never
     * authorizes parallel work; the native TaskStore admission also enforces non-overlap. */
    private java.util.Map<String,JSONObject> overlapFacts(long now)throws Exception{
        JSONArray pending=new JSONArray();synchronized(lock){JSONObject state=read();JSONArray rules=state.getJSONArray("rules"),rows=state.getJSONArray("receipts");
            java.util.Map<String,Integer> due=new java.util.HashMap<>();
            for(int i=0;i<rules.length();i++){JSONObject rule=rules.getJSONObject(i);
                if(rule.optBoolean("enabled")&&!rule.isNull("nextAt")&&rule.getLong("nextAt")<=now&&!"allow".equals(policy(rule).getString("overlap")))due.put(rule.getString("id"),rule.getInt("revision"));
            }
            // The original file is already bounded to 8MB. Select only exact due-rule revisions,
            // but never let another rule's recent rows hide a retained old completion proof.
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);Integer revision=due.get(row.optString("ruleId"));
                if(revision!=null&&revision==row.optInt("revision")&&"phone_goal".equals(row.optString("kind"))&&row.optString("status").matches("awaiting_user|blocked_notifications"))pending.put(copy(row));
            }}
        java.util.Map<String,JSONObject> result=new java.util.HashMap<>();
        // Native resolver calls remain outside the rule monitor. Each page is bounded, and all
        // selected pages are checked: an absent/changed proof still blocks inFlight below.
        for(int start=0;start<pending.length();start+=100)for(int i=start;i<Math.min(start+100,pending.length());i++){
            JSONObject row=pending.getJSONObject(i);result.put(row.getString("id"),new JSONObject().put("receipt",row).put("goal",goalState(row)));
        }return result;
    }
    private static boolean inFlight(JSONArray receipts,JSONObject rule,java.util.Map<String,JSONObject> facts){
        for(int i=0;i<receipts.length();i++){JSONObject row=receipts.optJSONObject(i);if(row==null||!rule.optString("id").equals(row.optString("ruleId"))||row.optInt("revision")!=rule.optInt("revision"))continue;
            String status=row.optString("status");if(status.matches("dispatching|delivery_unknown|deferred"))return true;
            if("phone_goal".equals(row.optString("kind"))&&status.matches("awaiting_user|blocked_notifications")){
                JSONObject proof=facts.get(row.optString("id")),before=proof==null?null:proof.optJSONObject("receipt"),goal=proof==null?null:proof.optJSONObject("goal");
                if(before==null||!before.toString().equals(row.toString())||goal==null||!goal.optString("state").matches("succeeded|cancelled"))return true;
            }
        }return false;
    }
    public JSONArray claimDue(long now,int limit)throws Exception {
        java.util.Map<String,JSONObject> facts=overlapFacts(now);
        synchronized(lock){JSONObject state=read();JSONArray claimed=new JSONArray(),rules=state.getJSONArray("rules"),receipts=state.getJSONArray("receipts");boolean changed=false;int maximum=Math.min(20,Math.max(1,limit)),historyBudget=64;
            for(int i=0;i<receipts.length()&&claimed.length()<maximum;i++){
                JSONObject receipt=receipts.getJSONObject(i);if(!"deferred".equals(receipt.optString("status"))||receipt.getLong("dispatchAfter")>now)continue;
                String blocked=receiptBlocked(state,receipt,now);changed=true;
                if(blocked==null){receipt.put("status","dispatching").put("updatedAt",now);claimed.put(copy(receipt));}
                else if(!deferredBlock(blocked))receipt.put("status",blocked).put("message","旧提醒已停止："+blocked).put("updatedAt",now);
            }
            for(int i=0;i<rules.length()&&claimed.length()<maximum&&historyBudget>0;i++){
                JSONObject rule=rules.getJSONObject(i);if(!rule.optBoolean("enabled")||rule.optBoolean("sourceCheckPending")||rule.isNull("nextAt")||rule.getLong("nextAt")>now)continue;
                JSONObject policy=policy(rule),schedule=rule.getJSONObject("schedule");long first=rule.optLong("scheduledAt",rule.getLong("nextAt"));
                if(first>now){arm(rule,first);changed=true;continue;}
                AutomationMisfire.Window window=AutomationMisfire.enumerate(schedule,first,now);java.util.List<Long> due=window.occurrences();
                String strategy=policy.getString("misfire"),overlap=policy.getString("overlap");int catchUp=0;long next=window.next();boolean stopped=false,held=false;
                for(int j=0;j<due.size();j++){
                    long occurrence=due.get(j);if(historyBudget==0||claimed.length()>=maximum){next=occurrence;stopped=true;break;}
                    boolean latest=window.coversNow()&&j==due.size()-1;
                    boolean omitted=!"catch_up".equals(strategy)&&!latest;
                    String terminal=!policy.isNull("expiresAt")&&now>=policy.getLong("expiresAt")?"expired":omitted?(strategy.equals("coalesce")?"coalesced":"missed_schedule"):null;
                    long deadline=AutomationPolicy.deadline(policy,occurrence);long count=claimedCount(state,rule);
                    if(terminal==null&&now>=deadline)terminal=now>=policy.optLong("expiresAt",AutomationPolicy.MAX_TIME)?"expired":"missed_window";
                    if(terminal==null&&!policy.isNull("maxOccurrences")&&count>=policy.getLong("maxOccurrences")){rule.put("nextAt",JSONObject.NULL).put("ruleState","budget_exhausted");stopped=true;next=-1;changed=true;break;}
                    if(terminal==null&&AutomationPolicy.quietEnd(policy,now)>now){rule.put("scheduledAt",occurrence).put("nextAt",Math.min(deadline,AutomationPolicy.quietEnd(policy,now))).put("ruleState","quiet_hours");held=true;stopped=true;changed=true;break;}
                    if(terminal==null){JSONObject condition=AutomationConditions.evaluate(policy,conditions,now);rule.put("conditionStatus",condition);if(!condition.getBoolean("allowed")){rule.put("scheduledAt",occurrence).put("nextAt",Math.min(deadline,condition.getLong("nextCheckAt"))).put("ruleState","conditions_blocked");held=true;stopped=true;changed=true;break;}}
                    if(terminal==null&&!overlap.equals("allow")&&inFlight(receipts,rule,facts)){
                        if(overlap.equals("skip"))terminal="overlap_skipped";
                        else{rule.put("scheduledAt",occurrence).put("nextAt",AutomationConditions.later(now)).put("ruleState","overlap_buffered");held=true;stopped=true;changed=true;break;}
                    }
                    String key=rule.getString("id")+":"+rule.getInt("revision")+":"+occurrence;boolean exists=false;
                    for(int k=0;k<receipts.length();k++)if(key.equals(receipts.getJSONObject(k).optString("instanceKey"))){exists=true;break;}
                    if(!exists){JSONObject receipt=new JSONObject().put("id",UUID.randomUUID().toString()).put("instanceKey",key).put("ruleId",rule.getString("id"))
                            .put("revision",rule.getInt("revision")).put("occurrenceAt",occurrence).put("claimedAt",now).put("updatedAt",now)
                            .put("status",terminal==null?"dispatching":terminal).put("kind",rule.getString("kind")).put("title",rule.getString("title")).put("goal",rule.getString("goal"))
                            .put("validUntil",deadline).put("occurrenceNumber",terminal==null?count+1:JSONObject.NULL).put("misfirePolicy",strategy).put("overlapPolicy",overlap)
                            .put("message",terminal==null?"提醒已领取，尚未确认通知投递":"此日历实例未投递或执行："+terminal);
                        if(rule.optJSONObject("nativeOrigin")!=null)receipt.put("nativeOrigin",copy(rule.getJSONObject("nativeOrigin")));
                        JSONObject pending=rule.optJSONObject("coalescedPending");
                        if(strategy.equals("coalesce")){
                            if(omitted){if(pending==null)pending=new JSONObject().put("firstAt",occurrence).put("count",0);pending.put("lastAt",occurrence).put("count",pending.getLong("count")+1);rule.put("coalescedPending",pending);}
                            else if(pending!=null){receipt.put("coalescedOccurrences",copy(pending)).put("message",terminal==null?"已合并错过的日历实例，尚未确认本次投递":"合并实例未投递："+terminal);rule.remove("coalescedPending");}
                        }
                        receipts.put(receipt);if(terminal==null){rule.put("claimedCount",count+1);claimed.put(copy(receipt));catchUp++;}historyBudget--;changed=true;
                    }
                    if("expired".equals(terminal)){rule.put("enabled",false).put("ruleState","expired").put("scheduledAt",JSONObject.NULL).put("nextAt",JSONObject.NULL);next=-1;stopped=true;break;}
                    if(strategy.equals("catch_up")&&catchUp>=policy.getInt("maxCatchUp")&&j+1<due.size()){next=due.get(j+1);stopped=true;break;}
                }
                rule.put("historyWindowCoveredToNow",window.coversNow()&&!stopped).put("historyWindowTruncated",!window.coversNow()||stopped&&next>=0);
                // Quiet/condition/overlap holds already pinned the exact occurrence and wake.
                if(!held&&next>=0)arm(rule,next);
                if(next<0&&!stopped){rule.put("enabled",false).put("scheduledAt",JSONObject.NULL).put("nextAt",JSONObject.NULL).put("ruleState",schedule.getString("type").equals("once")?"awaiting_user":"schedule_exhausted");}
                changed=true;
            }
            while(receipts.length()>200){int removable=-1;for(int i=0;i<receipts.length();i++)if(receipts.getJSONObject(i).optString("status").matches("notified|cancelled|expired|missed_window|missed_schedule|coalesced|overlap_skipped")){removable=i;break;}if(removable<0)break;JSONObject gone=receipts.getJSONObject(removable);receipts.remove(removable);JSONObject retention=state.optJSONObject("receiptRetention");if(retention==null)retention=new JSONObject().put("prunedTerminalCount",0);retention.put("prunedTerminalCount",retention.getLong("prunedTerminalCount")+1).put("lastPrunedOccurrenceAt",gone.optLong("occurrenceAt")).put("terminalWindowLimit",200);state.put("receiptRetention",retention);}
            if(changed)write(state);return claimed;
        }
    }
    public JSONObject delivered(String receiptId,String status,String message,long now)throws Exception {
        if(!status.equals("notified")&&!status.equals("awaiting_user")&&!status.equals("blocked_notifications")&&!status.equals("delivery_unknown"))throw new IllegalArgumentException("无效提醒回执状态");
        synchronized(lock){JSONObject state=read();JSONArray receipts=state.getJSONArray("receipts");int index=indexOf(receipts,receiptId);if(index<0)return new JSONObject().put("deleted",true);
            JSONObject receipt=receipts.getJSONObject(index);if(!receipt.optString("status").matches("dispatching|delivery_unknown"))return copy(receipt);
            receipt.put("status",status).put("message",text(message,1,500,"回执说明")).put("updatedAt",now);write(state);return copy(receipt);
        }
    }
    public interface Delivery {JSONObject send(JSONObject receipt)throws Exception;}
    public JSONObject dispatchReceipt(String receiptId,Delivery delivery,long now)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray receipts=state.getJSONArray("receipts");int index=indexOf(receipts,receiptId);
            if(index<0)return new JSONObject().put("deleted",true);JSONObject receipt=receipts.getJSONObject(index);
            if(!receipt.optString("status").equals("dispatching"))return copy(receipt);
            String blocked=receiptBlocked(state,receipt,now);
            if(blocked!=null){receipt.put("status",deferredBlock(blocked)?"deferred":blocked).put("message","提醒未投递："+blocked).put("updatedAt",now);write(state);return copy(receipt);}
            JSONObject result;
            try{result=delivery.send(copy(receipt));}catch(Exception error){result=new JSONObject().put("status","delivery_unknown").put("message","通知投递遇到异常，请在应用内检查本次提醒");}
            String status=result.optString("status");if(!status.equals("notified")&&!status.equals("awaiting_user")&&!status.equals("blocked_notifications")&&!status.equals("delivery_unknown"))throw new IllegalArgumentException("无效通知结果");
            receipt.put("status",status).put("message",text(result.optString("message"),1,500,"回执说明")).put("updatedAt",now);write(state);return copy(receipt);
        }
    }
    public void recoverInterrupted(long now)throws Exception {
        synchronized(lock){JSONObject state=read();JSONArray receipts=state.getJSONArray("receipts");boolean changed=false;
            for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if(receipt.optString("status").equals("dispatching")){receipt.put("status","delivery_unknown").put("message","进程在通知投递时结束，投递情况未知，未自动重发").put("updatedAt",now);changed=true;}}
            if(changed)write(state);
        }
    }
    private static boolean cancelPending(JSONObject state,String ruleId,String message,long now)throws Exception{
        boolean changed=false;JSONArray receipts=state.getJSONArray("receipts");
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);
            if(ruleId.equals(receipt.optString("ruleId"))&&receipt.optString("status").matches("dispatching|deferred")){receipt.put("status","cancelled").put("message",message).put("updatedAt",now);changed=true;}
        }return changed;
    }
    private static boolean deferredBlock(String reason){return "quiet_hours".equals(reason)||"conditions_blocked".equals(reason)||"not_due".equals(reason)||"source_unavailable".equals(reason);}
    private String receiptBlocked(JSONObject state,JSONObject receipt,long now)throws Exception {
        int index=indexOf(state.getJSONArray("rules"),receipt.getString("ruleId"));if(index<0)return "cancelled";
        JSONObject rule=state.getJSONArray("rules").getJSONObject(index);
        if(rule.optBoolean("sourceCheckPending")){receipt.put("dispatchAfter",Math.max(now+60_000L,rule.optLong("sourceRetryAt")));return "source_unavailable";}
        if(rule.getInt("revision")!=receipt.getInt("revision")||rule.optBoolean("taskRequestsRevoked"))return "cancelled";
        JSONObject policy=policy(rule);long deadline=AutomationPolicy.deadline(policy,receipt.getLong("occurrenceAt"));
        if(now>=deadline)return now>=policy.optLong("expiresAt",AutomationPolicy.MAX_TIME)?"expired":"missed_window";
        if(!policy.isNull("maxOccurrences")&&receipt.optLong("occurrenceNumber",1)>policy.getLong("maxOccurrences"))return "budget_exhausted";
        if(now<receipt.getLong("occurrenceAt")){receipt.put("dispatchAfter",Math.min(deadline,receipt.getLong("occurrenceAt")));return "not_due";}
        long quiet=AutomationPolicy.quietEnd(policy,now);
        if(quiet>now){receipt.put("dispatchAfter",Math.min(deadline,quiet));return "quiet_hours";}
        JSONObject condition=AutomationConditions.evaluate(policy,conditions,now);receipt.put("conditionStatus",condition);
        if(!condition.getBoolean("allowed")){receipt.put("dispatchAfter",Math.min(deadline,condition.getLong("nextCheckAt")));return "conditions_blocked";}
        return null;
    }
    /** Local replan only. Existing alarms own the wake; this never claims, sends or starts a task. */
    public void recheckConditions(long now)throws Exception {
        synchronized(lock){JSONObject state=read();boolean changed=false;JSONArray rules=state.getJSONArray("rules"),receipts=state.getJSONArray("receipts");
            for(int i=0;i<rules.length();i++){JSONObject rule=rules.getJSONObject(i);
                if(!rule.optBoolean("enabled")||!"conditions_blocked".equals(rule.optString("ruleState")))continue;
                JSONObject policy=policy(rule),decision=AutomationConditions.evaluate(policy,conditions,now);long deadline=AutomationPolicy.deadline(policy,rule.getLong("scheduledAt"));
                long eligible=Math.max(rule.getLong("scheduledAt"),decision.getBoolean("allowed")?now:decision.getLong("nextCheckAt"));
                rule.put("conditionStatus",decision).put("nextAt",Math.min(deadline,eligible));changed=true;
            }
            for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);JSONObject prior=receipt.optJSONObject("conditionStatus");
                if(!"deferred".equals(receipt.optString("status"))||prior==null||prior.optBoolean("allowed",true))continue;
                String blocked=receiptBlocked(state,receipt,now);
                if(blocked==null)receipt.put("dispatchAfter",Math.max(now,receipt.getLong("occurrenceAt")));else if(!deferredBlock(blocked))receipt.put("status",blocked).put("updatedAt",now);
                changed=true;
            }
            if(changed)write(state);
        }
    }
    private static JSONObject policy(JSONObject rule)throws Exception {return AutomationPolicy.rule(rule.optJSONObject("policy"),rule.getJSONObject("schedule").optString("zone","UTC"));}
    private static long claimedCount(JSONObject state,JSONObject rule)throws Exception {
        if(rule.has("claimedCount"))return rule.getLong("claimedCount");
        long count=0;JSONArray receipts=state.getJSONArray("receipts");for(int i=0;i<receipts.length();i++)if(rule.getString("id").equals(receipts.getJSONObject(i).optString("ruleId")))count++;
        return count;
    }
    private static void arm(JSONObject rule,long scheduled)throws Exception {
        if(scheduled<0){rule.put("scheduledAt",JSONObject.NULL).put("nextAt",JSONObject.NULL).put("ruleState","schedule_exhausted").put("enabled",false);return;}
        rule.put("scheduledAt",scheduled);
        if(!rule.optBoolean("enabled")){rule.put("nextAt",JSONObject.NULL).put("ruleState","paused");return;}
        JSONObject policy=policy(rule);
        if(!policy.isNull("maxOccurrences")&&rule.optLong("claimedCount")>=policy.getLong("maxOccurrences")){rule.put("nextAt",JSONObject.NULL).put("ruleState","budget_exhausted");return;}
        long quiet=AutomationPolicy.quietEnd(policy,scheduled),deadline=AutomationPolicy.deadline(policy,scheduled);
        rule.put("nextAt",Math.min(quiet,deadline)).put("ruleState",quiet>scheduled?"quiet_hours":"scheduled");
    }
    private static boolean sameInteger(Object value,long expected){return value instanceof Number&&((Number)value).longValue()==expected&&((Number)value).doubleValue()==expected;}
    public static JSONObject validateSchedule(JSONObject input)throws Exception {
        return AutomationSchedule.normalize(input);
    }
    static long initialAt(JSONObject schedule,long now)throws Exception {return schedule.getString("type").equals("once")?schedule.getLong("at"):AutomationSchedule.nextAt(schedule,now);}
    public static long nextDaily(JSONObject schedule,long after)throws Exception {
        return AutomationSchedule.nextAt(new JSONObject(schedule.toString()).put("type","daily"),after);
    }
    private JSONObject read()throws Exception {
        if(!file.isFile())return new JSONObject().put("version",1).put("rules",new JSONArray()).put("receipts",new JSONArray());
        if(file.length()>8_000_000)throw new IllegalStateException("规则文件过大，请检查现有数据");
        JSONObject value=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        if(value.optJSONArray("rules")==null||value.optJSONArray("receipts")==null)throw new IllegalStateException("规则记录格式无效，未覆盖原文件");return value;
    }
    private void write(JSONObject value)throws Exception {
        File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("无法建立规则目录");
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8_000_000)throw new IllegalStateException("规则记录已达到容量上限，请先删除旧规则");
        File temporary=new File(file.getPath()+".new");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
        try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static int indexOf(JSONArray values,String id)throws Exception{for(int i=0;i<values.length();i++)if(id.equals(values.getJSONObject(i).optString("id")))return i;return -1;}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static String text(String value,int min,int max,String label){String text=value.trim();if(text.length()<min||text.length()>max)throw new IllegalArgumentException(label+"长度应为"+min+"至"+max);return text;}
}
