package dev.pocketengine.features.teams;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A proposal is never execution authority. These are display and identity checks for
 * the existing private, post-integration, selected-file WorkPlanPage only. */
public final class TeamParentRevisionOffer {
    private TeamParentRevisionOffer() {}
    private static JSONObject one(JSONArray rows,String key,String id) {
        JSONObject result=null;
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null&&id.equals(row.optString(key))){if(result!=null)return null;result=row;}
        }
        return result;
    }
    static boolean local(JSONObject run) {
        JSONObject scope=run.optJSONObject("productionScope"),plan=run.optJSONObject("workPlan");
        if(scope==null||scope.has("projectSelection")||plan==null||!"produce".equals(run.optString("mode"))||run.optString("status").matches("completed|stopping|cancelled"))return false;
        JSONArray slots=scope.optJSONArray("assignments");
        if(slots==null||slots.length()==0)return false;
        for(int i=0;i<slots.length();i++){JSONObject slot=slots.optJSONObject(i);if(slot==null||slot.has("remoteSelection")||slot.has("projectSelection"))return false;}
        return true;
    }
    static JSONObject basis(JSONObject run,JSONObject parent,JSONObject actual)throws Exception {
        if(actual==null||!local(run)||!run.optString("id").equals(actual.optString("teamRunId"))||!"completed".equals(actual.optString("status"))
                ||!Boolean.TRUE.equals(actual.opt("currentFilesComplete"))||!Boolean.FALSE.equals(actual.opt("activeAttempt"))||!Boolean.FALSE.equals(actual.opt("hasUnresolved")))return null;
        JSONObject observed=TeamIntegrationDeliveryNote.observe(run,actual);if(observed==null||!observed.optBoolean("settledAtObservation"))return null;
        JSONObject plan=run.getJSONObject("workPlan"),progress=actual.optJSONObject("progress");
        if(progress==null||!progress.optBoolean("complete")||!actual.optString("planHash").matches("[a-f0-9]{64}")||!actual.optString("reviewId").matches("[a-f0-9-]{36}"))return null;
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!plan.has(key)||!plan.get(key).equals(parent.opt(key)))return null;
        JSONObject bound=new JSONObject().put("teamRunId",run.getString("id")).put("teamGeneration",run.getInt("generation")).put("planId",plan.getString("planId")).put("planRevision",plan.getInt("revision"));
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})bound.put(key,plan.get(key));
        for(String key:new String[]{"taskId","runId","reviewId","planHash"})bound.put(key,actual.getString(key));return bound;
    }
    public static JSONObject exact(JSONObject run,String operation)throws Exception {
        JSONObject request=one(run.optJSONArray("parentRequests"),"operationId",operation),plan=run.optJSONObject("workPlan");
        if(!local(run)||request==null||!"revision_proposal".equals(request.optString("kind"))||!"awaiting_owner_review".equals(request.optString("state"))||!Boolean.FALSE.equals(request.opt("executionAuthorized")))throw changed();
        JSONObject bound=request.optJSONObject("integratedBasis"),item=one(plan.optJSONArray("items"),"workItemId",request.optString("workItemId"));
        if(bound==null||item==null||!"completed".equals(item.optString("status"))||!run.getString("id").equals(bound.optString("teamRunId"))||run.getInt("generation")!=bound.optInt("teamGeneration",-1)
                ||!plan.getString("planId").equals(bound.optString("planId"))||plan.getInt("revision")!=bound.optInt("planRevision",-1))throw changed();
        for(String key:new String[]{"workItemId","assignmentId","memberId","itemRevision","ownerGeneration"})if(!item.has(key)||!item.get(key).equals(request.opt(key)))throw changed();
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!plan.get(key).equals(bound.opt(key)))throw changed();
        String note=request.getString("text");if(note.isBlank()||note.length()>4000||!bound.getString("parentTaskId").equals(request.optString("parentTaskId")))throw changed();
        JSONObject author=one(run.optJSONArray("members"),"id",item.getString("memberId"));
        if(author==null||author.optBoolean("exited")||!"model".equals(author.optString("runner","model"))||!"phone".equals(author.opt("device"))||author.has("remoteProduction"))throw changed();
        return new JSONObject(request.toString()).put("title",item.getString("title")).put("teamRunId",run.getString("id")).put("author",author.optString("name",author.getString("id")));
    }
    private static SecurityException changed(){return new SecurityException("这份提案、已写回版本或原分工已改变，未开始修订。");}
    public static JSONArray available(JSONObject run)throws Exception {
        JSONArray result=new JSONArray(),requests=run.optJSONArray("parentRequests");
        if(!local(run))return result;
        for(int i=0;requests!=null&&i<requests.length();i++){
            JSONObject row=requests.optJSONObject(i);if(row==null||!row.optString("state").matches("awaiting_owner_review|needs_fresh_basis")||!"revision_proposal".equals(row.optString("kind"))||!Boolean.FALSE.equals(row.opt("executionAuthorized")))continue;
            try{
                JSONObject plan=run.getJSONObject("workPlan"),item=one(plan.optJSONArray("items"),"workItemId",row.getString("workItemId"));
                if(item==null||!plan.getString("parentTaskId").equals(row.getString("parentTaskId"))||!item.getString("assignmentId").equals(row.getString("assignmentId"))
                        ||!item.getString("memberId").equals(row.getString("memberId"))||item.getInt("itemRevision")!=row.getInt("itemRevision")||item.getInt("ownerGeneration")!=row.getInt("ownerGeneration"))continue;
                JSONObject author=one(run.optJSONArray("members"),"id",item.getString("memberId"));if(author==null)continue;
                String text=row.getString("text");if(text.isBlank()||text.length()>4000)continue;
                JSONObject display=new JSONObject();for(String key:new String[]{"operationId","workItemId","assignmentId"})display.put(key,row.get(key));
                boolean action=false;if(row.optJSONObject("integratedBasis")!=null)try{exact(run,row.getString("operationId"));action=true;}catch(SecurityException changed){/* Retain the actual suggestion as read-only. */}
                result.put(display.put("title",item.getString("title")).put("author",author.optString("name",author.getString("id"))).put("text",text).put("canReview",action));
            }catch(org.json.JSONException incomplete){/* Older incomplete records cannot authorize or impersonate a proposal. */}
        }
        return result;
    }
    static JSONObject preview(JSONObject proposal,JSONObject facts)throws Exception {
        JSONObject basis=proposal.getJSONObject("integratedBasis"),plan=facts.getJSONObject("workPlan"),parent=facts.getJSONObject("parent"),integration=facts.getJSONObject("integration");
        if(!"work_revision".equals(facts.optString("kind"))||!proposal.getString("teamRunId").equals(facts.optString("teamRunId"))||!proposal.getString("workItemId").equals(facts.optString("workItemId"))
                ||!proposal.getString("text").equals(facts.opt("text"))||!plan.getString("planId").equals(basis.getString("planId"))||plan.getInt("revision")!=basis.getInt("planRevision")
                ||facts.has("projectResult")||facts.getJSONObject("originalScope").has("projectSelection")||!Boolean.FALSE.equals(facts.opt("parentBudgetIncreaseRequired"))
                ||facts.getInt("parentOperationShortfall")!=0||!Boolean.FALSE.equals(facts.opt("originalProjectWriteAuthorized"))
                ||!"completed".equals(integration.optString("status"))||integration.optBoolean("activeAttempt")||integration.optBoolean("hasUnresolved"))throw changed();
        for(String key:new String[]{"taskId","reviewId","planHash"})if(!basis.get(key).equals(integration.opt(key)))throw changed();
        if(!basis.getString("runId").equals(integration.getJSONObject("binding").optString("runId")))throw changed();
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","targetProjectId"})if(!basis.get(key).equals(parent.opt(key)))throw changed();
        JSONArray affected=facts.getJSONArray("affected"),members=plan.getJSONObject("memberSelection").getJSONArray("members"),all=plan.getJSONArray("items"),rows=new JSONArray();
        // "members" here is the plan's reviewed roster, not a recent team/identity lookup.
        Set<String> items=new HashSet<>(),paths=new HashSet<>();
        if(affected.length()<1||affected.length()>8)throw changed();
        for(int i=0;i<affected.length();i++){
            JSONObject frozen=affected.getJSONObject(i),item=one(all,"workItemId",frozen.getString("workItemId")),member=one(members,"id",frozen.getString("memberId"));
            if(item==null||member==null||member.optBoolean("exited")||!"phone".equals(member.opt("device"))||!"model".equals(member.optString("runner","model"))||member.has("remoteProduction")
                    ||!items.add(item.getString("workItemId"))||frozen.has("remoteSelection")||frozen.has("projectSelection"))throw changed();
            for(String key:new String[]{"workItemId","assignmentId","memberId","itemRevision","ownerGeneration"})if(!item.get(key).equals(frozen.opt(key)))throw changed();
            JSONArray next=frozen.getJSONArray("newFiles"),files=new JSONArray();if(next.length()<1||next.length()>8)throw changed();
            for(int f=0;f<next.length();f++){JSONObject file=next.getJSONObject(f);if(!paths.add(file.getString("path"))||paths.size()>8)throw changed();files.put(new JSONObject().put("path",file.getString("path")).put("version",file.getInt("baseVersion")));}
            rows.put(new JSONObject().put("workItemId",item.getString("workItemId")).put("title",item.getString("title")).put("memberId",member.getString("id")).put("author",member.optString("name",member.getString("id"))).put("files",files));
        }
        if(!items.contains(proposal.getString("workItemId")))throw changed();
        JSONObject budget=facts.getJSONObject("parentBudget"),shown=new JSONObject();
        for(String key:new String[]{"operationId","workItemId","assignmentId","title","author","text"})shown.put(key,proposal.get(key));
        return shown.put("runId",facts.getString("teamRunId")).put("projectId",basis.getString("targetProjectId")).put("affected",rows).put("retainedItems",all.length()-rows.length())
                .put("modelLimit",budget.getInt("modelRequestLimit")).put("nextModelLimit",facts.getInt("proposedModelRequestLimit")).put("usedModels",budget.getInt("modelRequestsReservedOrUsed"))
                .put("toolLimit",budget.getInt("toolOperationLimit")).put("nextToolLimit",facts.getInt("proposedToolOperationLimit")).put("usedTools",budget.getInt("toolOperationsReservedOrUsed"));
    }
    static void recordApplied(JSONObject run,JSONObject receipt)throws Exception {
        JSONArray requests=run.optJSONArray("parentRequests");if(requests==null)return;
        String operation=receipt.optString("appliedParentProposal");JSONObject plan=receipt.getJSONObject("workPlan");
        for(int i=0;i<requests.length();i++){JSONObject row=requests.getJSONObject(i);if(!"awaiting_owner_review".equals(row.optString("state")))continue;
            if(!operation.isEmpty()&&operation.equals(row.optString("operationId"))){row.put("state","applied_to_work_revision").put("revisionRequestId",plan.getString("lastRevisionRequestId"));continue;}
            JSONObject basis=row.optJSONObject("integratedBasis"),next=one(plan.getJSONArray("items"),"workItemId",row.optString("workItemId"));
            if(basis!=null&&basis.optString("planId").equals(plan.optString("planId"))&&basis.optInt("planRevision")<plan.optInt("revision")||
                    next!=null&&row.optInt("itemRevision")!=next.optInt("itemRevision"))
                row.put("state","needs_fresh_basis"); // The suggestion is retained; it did not authorize the new draft.
        }
    }
}
