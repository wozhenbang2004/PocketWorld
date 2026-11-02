package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Expands one original prepared parent message into the original member inbox.
 * The operation arguments freeze the audience; this class owns no store, worker or wake policy. */
public final class TeamParentBroadcast {
    public static final String FORMAT="pocketworld.team.parent-broadcast.v1";
    private TeamParentBroadcast(){}
    public interface Mailbox {
        void checkCurrent()throws Exception;
        JSONObject snapshot()throws Exception;
        JSONObject send(JSONObject exactInput)throws Exception;
    }
    public static void validate(JSONObject args)throws Exception {
        Set<String> allowed=Set.of("teamRunId","expectedTeamRevision","expectedGeneration","delivery","messageId","text","quoteMessageId","recipients");
        for(var keys=args.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("广播不能夹带单人成果、回答、继续或权限参数");
        for(String key:new String[]{"teamRunId","expectedTeamRevision","expectedGeneration","delivery","messageId","text","recipients"})if(!args.has(key)||args.isNull(key))throw new IllegalArgumentException("广播缺少准确参数："+key);
        uuid(args.getString("messageId"));if(!"queue".equals(args.getString("delivery")))throw new SecurityException("广播只定向排队，未授予自动唤醒");
        String text=args.getString("text");if(text.trim().isEmpty()||text.length()>4000)throw new IllegalArgumentException("广播需1至4000字");
        integer(args,"expectedTeamRevision",1);integer(args,"expectedGeneration",0);
        JSONArray targets=args.getJSONArray("recipients");if(targets.length()<1||targets.length()>4)throw new IllegalArgumentException("广播需1至4名准确原成员");
        Set<String> seen=new HashSet<>();for(int i=0;i<targets.length();i++){
            JSONObject target=targets.getJSONObject(i);Set<String> keys=new HashSet<>();target.keys().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("memberId","expectedMemberGeneration","identityRevision")))throw new SecurityException("广播目标只能携准确成员身份与代次");
            String id=target.getString("memberId");if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!seen.add(id))throw new IllegalArgumentException("广播目标不能用名称、通配符、重复或失效成员身份");
            integer(target,"expectedMemberGeneration",0);integer(target,"identityRevision",1);
        }
    }
    /** Caller serializes on the ORIGINAL coordinator monitor, including each original enqueue. */
    public static JSONObject deliver(JSONObject nativeOrigin,JSONObject args,JSONArray originalOperations,Mailbox mailbox)throws Exception {
        validate(args);String parent=uuid(nativeOrigin.getString("parentTaskId")),parentRun=uuid(nativeOrigin.getString("parentRunId")),operation=uuid(nativeOrigin.getString("preparedOperationId"));
        int epoch=nativeOrigin.getInt("parentEpoch");if(epoch<1)throw new SecurityException("广播没有原父任务代次");
        boolean previous=false,queryOnly=false;JSONObject intent=intent(args);
        // Read only original SQL operations from this native parent. A reused message ID
        // cannot silently change its audience/text, or replay an uncertain older operation.
        for(int i=0;i<originalOperations.length();i++){
            JSONObject row=originalOperations.getJSONObject(i),old=row.optJSONObject("arguments");
            if(!"teams_message".equals(row.optString("tool"))||old==null||!args.getString("messageId").equals(old.optString("messageId")))continue;
            if(!old.has("recipients")||!same(intent,intent(old))||!parentRun.equals(row.optString("runId")))throw new SecurityException("原广播编号已有不同内容、受众或父执行，不能重定向");
            if(!operation.equals(row.optString("operationId"))){previous=true;if(!"receipt".equals(row.optString("state")))queryOnly=true;}
        }
        mailbox.checkCurrent();JSONObject first=mailbox.snapshot();String team=args.getString("teamRunId");
        if(!team.equals(first.getString("id")))throw new SecurityException("广播不属于原团队");
        boolean expanded=false;JSONArray targets=args.getJSONArray("recipients");for(int i=0;i<targets.length();i++)if(letter(first,deliveryId(team,args.getString("messageId"),targets.getJSONObject(i)))!=null)expanded=true;
        if(!previous&&!expanded&&(first.getLong("revision")!=args.getLong("expectedTeamRevision")||first.getInt("generation")!=args.getInt("expectedGeneration")))throw new IllegalStateException("原广播创建快照已变化，请重新审阅");
        JSONArray receipts=new JSONArray();int accepted=0,consumed=0,unknown=0,failed=0;
        for(int i=0;i<targets.length();i++){
            mailbox.checkCurrent();JSONObject target=targets.getJSONObject(i),current=mailbox.snapshot();String member=target.getString("memberId"),message=deliveryId(team,args.getString("messageId"),target);
            JSONObject row=letter(current,message),receipt;
            if(directCollision(originalOperations,message))receipt=receipt(target,message,"failed","message_identity_collision");
            else if(row!=null)receipt=observed(row,parent,target,message);
            else if(queryOnly)receipt=receipt(target,message,"unknown","original_operation_unconfirmed");
            else {
                JSONObject actual=member(current,member);String denial=actual==null?"member_missing":actual.optBoolean("exited")?"member_exited":actual.getInt("generation")!=target.getInt("expectedMemberGeneration")||actual.optInt("identityRevision",1)!=target.getInt("identityRevision")?"member_identity_changed":current.getInt("generation")!=args.getInt("expectedGeneration")?"team_generation_changed":"";
                if(!denial.isEmpty())receipt=receipt(target,message,"failed",denial);
                else {
                    JSONObject input=new JSONObject().put("runId",team).put("expectedRevision",current.getLong("revision")).put("expectedGeneration",current.getInt("generation"))
                        .put("memberId",member).put("recipientMemberId",member).put("expectedMemberGeneration",actual.getInt("generation")).put("messageId",message).put("text",args.getString("text")).put("mode","queue");
                    if(args.has("quoteMessageId"))input.put("quoteMessageId",args.getString("quoteMessageId"));
                    try{mailbox.send(input);mailbox.checkCurrent();row=letter(mailbox.snapshot(),message);receipt=row==null?receipt(target,message,"unknown","original_receipt_missing"):observed(row,parent,target,message);}
                    catch(Exception uncertain){mailbox.checkCurrent();row=letter(mailbox.snapshot(),message);receipt=row==null?receipt(target,message,"unknown","original_delivery_unconfirmed"):observed(row,parent,target,message);}
                }
            }
            String state=receipt.getString("state");if(state.equals("consumed"))consumed++;if(state.matches("queued|delivering|consumed"))accepted++;else if(state.equals("unknown"))unknown++;else failed++;
            receipts.put(receipt);
        }
        mailbox.checkCurrent();String state=unknown>0?"unknown":accepted==targets.length()?consumed==targets.length()?"consumed":"queued":accepted>0?"partially_queued":"rejected";
        JSONObject result=new JSONObject().put("format",FORMAT).put("parentMessageId",args.getString("messageId")).put("teamRunId",team).put("parentTaskId",parent).put("parentRunId",parentRun).put("parentEpoch",epoch).put("operationId",operation)
            .put("state",state).put("status",state).put("targetCount",targets.length()).put("acceptedCount",accepted).put("consumedCount",consumed).put("failedCount",failed).put("unknownCount",unknown).put("deliveries",receipts)
            .put("audienceFrozen",true).put("queryOnly",queryOnly).put("automaticWake",false).put("executionAuthorized",false).put("businessVerified",false).put("ok",unknown==0&&failed==0);
        if(unknown>0)result.put("accepted",JSONObject.NULL);return result;
    }
    public static String deliveryId(String team,String parentMessage,JSONObject target)throws Exception {
        String exact=FORMAT+"\n"+team+"\n"+parentMessage+"\n"+target.getString("memberId")+"\n"+target.getInt("expectedMemberGeneration")+"\n"+target.getInt("identityRevision");
        return UUID.nameUUIDFromBytes(exact.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static JSONObject observed(JSONObject row,String parent,JSONObject target,String message)throws Exception {
        if(!parent.equals(row.optString("senderParentTaskId"))||!target.getString("memberId").equals(row.optString("targetMemberId"))||!"queue".equals(row.optString("mode")))return receipt(target,message,"failed","message_identity_collision");
        boolean by=false,proof=false;JSONArray consumed=row.optJSONArray("consumedBy"),deliveries=row.optJSONArray("deliveries");for(int i=0;consumed!=null&&i<consumed.length();i++)by|=target.getString("memberId").equals(consumed.getString(i));
        for(int i=0;deliveries!=null&&i<deliveries.length();i++){JSONObject d=deliveries.getJSONObject(i);proof|=target.getString("memberId").equals(d.optString("memberId"))&&"consumed".equals(d.optString("state"));}
        String nativeState=row.optString("status"),state=nativeState.equals("historical")?"expired":nativeState.equals("consumed")?by&&proof?"consumed":"unknown":nativeState.equals("delivering")?"delivering":nativeState.matches("pending|unread")?"queued":"unknown";
        return receipt(target,message,state,row.optString("notDeliveredReason")).put("originalMailboxState",nativeState).put("consumptionVerified",state.equals("consumed")).put("queryOnly",true);
    }
    private static JSONObject receipt(JSONObject target,String message,String state,String reason)throws Exception{return new JSONObject().put("memberId",target.getString("memberId")).put("memberGeneration",target.getInt("expectedMemberGeneration")).put("identityRevision",target.getInt("identityRevision")).put("messageId",message).put("state",state).put("reason",reason).put("consumptionVerified",false).put("executionAuthorized",false);}
    private static JSONObject member(JSONObject run,String id)throws Exception{JSONArray members=run.getJSONArray("members");for(int i=0;i<members.length();i++)if(id.equals(members.getJSONObject(i).getString("id")))return members.getJSONObject(i);return null;}
    private static JSONObject letter(JSONObject run,String id)throws Exception{JSONArray inbox=run.getJSONArray("inbox");for(int i=0;i<inbox.length();i++)if(id.equals(inbox.getJSONObject(i).getString("id")))return inbox.getJSONObject(i);return null;}
    private static JSONObject intent(JSONObject args)throws Exception {JSONObject out=new JSONObject();for(String key:new String[]{"teamRunId","messageId","text","delivery","recipients","expectedGeneration"})out.put(key,args.get(key));return out.put("quoteMessageId",args.optString("quoteMessageId"));}
    private static boolean directCollision(JSONArray operations,String id)throws Exception{for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i),args=op.optJSONObject("arguments");if("teams_message".equals(op.optString("tool"))&&args!=null&&!args.has("recipients")&&id.equals(args.optString("messageId")))return true;}return false;}
    private static Set<String> keys(JSONObject value){Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);return keys;}
    private static boolean same(Object a,Object b)throws Exception {if(a instanceof JSONObject x&&b instanceof JSONObject y){Set<String> keys=keys(x);if(!keys.equals(keys(y)))return false;for(String key:keys)if(!same(x.get(key),y.get(key)))return false;return true;}if(a instanceof JSONArray x&&b instanceof JSONArray y){if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}return a.equals(b);}
    private static String uuid(String id){if(!id.matches("[a-f0-9-]{36}"))throw new SecurityException("广播需原任务/操作UUID");return id;}
    private static void integer(JSONObject value,String key,long min)throws Exception{Object n=value.get(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<min||((Number)n).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("广播代次需准确整数："+key);}
}
