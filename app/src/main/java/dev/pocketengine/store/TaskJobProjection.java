package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.ProjectDevelopment;
import dev.pocketengine.features.local.project.ProjectPlanRecords;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read-only view of the ORIGINAL TaskStore tables. No job registry,
 * recovery ledger, PID lookup, live polling, authorization or state mutation. */
final class TaskJobProjection {
    private static final String FILTER="'project_execute','remote_job','remote_start_task','teams_start','media_workflow','media_generate_image','media_generate_video','media_edit_image','media_synthesize_speech'";
    private static final int HEADER_BUDGET=65536,PLAN_BUDGET=131072,RELATION_BUDGET=131072;
    private TaskJobProjection(){}

    static JSONObject ownerHeader(SQLiteDatabase db,String id)throws Exception {
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(ui_header AS BLOB))<=32768 THEN ui_header END FROM tasks WHERE task_id=?",new String[]{id})){
            if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("原任务的有界主人来源暂不可核对");
            JSONObject header=new JSONObject(row.getString(0));if(!id.equals(header.optString("id")))throw new SecurityException("原任务摘要身份不匹配");return header;
        }
    }
    private static final class Budget {
        int headers,bodies,plans,relations;boolean limited,headerWindowFull;
        final int bodyLimit;final Map<String,JSONObject> headerCache=new HashMap<>();
        Budget(int bodyLimit){this.bodyLimit=bodyLimit;}
        JSONObject header(SQLiteDatabase db,String task)throws Exception {
            if(headerCache.containsKey(task))return headerCache.get(task);
            JSONObject value=null;
            try(Cursor row=db.rawQuery("SELECT length(CAST(ui_header AS BLOB)) FROM tasks WHERE task_id=?",new String[]{task})){
                if(row.moveToFirst()&&!row.isNull(0)&&row.getLong(0)>=0&&row.getLong(0)<=32768){
                    if(row.getLong(0)>HEADER_BUDGET-headers){limited=true;headerWindowFull=true;return null;}
                    headers+=row.getInt(0);try{value=ownerHeader(db,task);}catch(org.json.JSONException|SecurityException unavailable){limited=true;}
                }else limited=true;
            }
            headerCache.put(task,value);return value;
        }
    }
    static JSONObject page(TaskStore authority,SQLiteDatabase db,JSONObject anchor,String cursor,int limit,int maxBytes)throws Exception {
        String task=anchor.getString("id"),conversation=anchor.optString("conversationId");
        if(cursor==null||cursor.length()>2048||limit<1||limit>40||maxBytes<1024||maxBytes>131072)throw new IllegalArgumentException("原作业目录读取范围无效");
        long upper,last;
        if(cursor.isEmpty()){try(Cursor row=db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM operations WHERE tool IN ("+FILTER+")",null)){row.moveToFirst();upper=row.getLong(0);}last=upper;}
        else{
            JSONObject supplied=decode(cursor);
            if(supplied.length()!=7||supplied.optInt("v")!=1||supplied.optInt("filterVersion")!=1||!"jobs".equals(supplied.optString("section"))||!task.equals(supplied.optString("anchorTaskId"))||!conversation.equals(supplied.optString("conversationId")))throw new SecurityException("原作业目录来源或游标已变化，请重新读取");
            upper=exactLong(supplied,"upper");last=exactLong(supplied,"last");if(upper<0||last<1||last>upper)throw new IllegalArgumentException("原作业目录游标边界无效");
        }
        Budget budget=new Budget(maxBytes);JSONArray entries=new JSONArray();int scanned=0,filtered=0,collapsed=0;boolean more=false;long next=last;
        String boundary=cursor.isEmpty()?"":" AND o.rowid<?";
        String[] parameters=cursor.isEmpty()?new String[]{Long.toString(upper),Integer.toString(limit+1)}:new String[]{Long.toString(upper),Long.toString(last),Integer.toString(limit+1)};
        String query="SELECT o.rowid,o.task_id,o.run_id,r.epoch,o.operation_id,o.tool,o.state,o.created_at,length(CAST(o.arguments AS BLOB)),COALESCE(length(CAST(o.receipt AS BLOB)),0) FROM operations o LEFT JOIN runs r ON r.task_id=o.task_id AND r.run_id=o.run_id WHERE o.tool IN ("+FILTER+") AND o.rowid<=?"+boundary+" ORDER BY o.rowid DESC LIMIT ?";
        try(Cursor rows=db.rawQuery(query,parameters)){
            while(rows.moveToNext()){
                if(scanned==limit){more=true;break;}String ownerTask=rows.getString(1);
                JSONObject header=budget.header(db,ownerTask);
                // A page budget cannot consume an unread source; the next page retries this row.
                if(budget.headerWindowFull){more=true;break;}scanned++;next=rows.getLong(0);
                if(header==null||!"owner".equals(header.optString("actorId"))||!"private".equals(header.optString("spaceId"))||!(conversation.isEmpty()?task.equals(ownerTask):conversation.equals(header.optString("conversationId")))){filtered++;continue;}
                String run=rows.getString(2),operation=rows.getString(4),tool=rows.getString(5),domain=domain(tool);
                Integer epoch=rows.isNull(3)?null:rows.getInt(3);String state=rows.getString(6);
                JSONObject item=new JSONObject().put("position",next).put("taskId",ownerTask).put("runId",run).put("epoch",epoch==null?JSONObject.NULL:epoch)
                        .put("operationId",operation).put("tool",tool).put("domain",domain).put("createdAt",rows.getLong(7))
                        .put("state",state!=null&&state.matches("[a-z][a-z0-9_]{0,119}")?state:"unrecognized_native_state")
                        .put("jobKey",domain+":"+ownerTask+":"+run+":"+(epoch==null?"unknown":epoch)+":"+operation)
                        .put("statusSource","original_operation_journal").put("entryKind","original_launch_record")
                        .put("executionAuthorized",false).put("observingOnly",true).put("liveStatusQueried",false).put("projectLogCandidate",false)
                        .put("receiptRecorded",rows.getLong(9)>0).put("status","available");
                long bytes=rows.getLong(8)+rows.getLong(9);item.put("bodyBytes",bytes);
                if(bytes<0||bytes>budget.bodyLimit-budget.bodies){budget.limited=true;item.put("status","read_limited").put("detailReason","original_body_exceeds_directory_budget");entries.put(item);continue;}
                JSONObject arguments,receipt;
                try(Cursor body=db.rawQuery("SELECT arguments,receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{ownerTask,run,operation})){
                    if(!body.moveToFirst())throw new SecurityException("原作业操作已不可读");arguments=new JSONObject(body.getString(0));receipt=body.isNull(1)?null:new JSONObject(body.getString(1));budget.bodies+=(int)bytes;
                }catch(org.json.JSONException malformed){budget.bodies+=(int)bytes;item.put("status","source_unreadable").put("detailReason","original_body_not_verified");entries.put(item);continue;}
                if(receipt!=null&&"approval_required".equals(receipt.optString("code")))item.put("entryKind","approval_intent");
                if("remote_start_task".equals(tool)&&!conversation.isEmpty()&&originalRemoteChildRepresented(authority,db,ownerTask,run,operation,conversation,upper,budget)){collapsed++;continue;}
                if("project_execute".equals(tool)){
                    if(epoch==null){item.put("detailReason","original_run_epoch_unavailable");}
                    else if(!project(db,item,arguments,receipt,budget)){filtered++;continue;}
                }else{
                    JSONObject facts=new JSONObject();
                    if(receipt!=null){
                        Object originalStatus=receipt.opt("status");if(originalStatus instanceof String&&((String)originalStatus).matches("[a-z][a-z0-9_]{0,79}"))facts.put("originalReceiptStatus",originalStatus);
                        for(String name:new String[]{"executorStatus","providerState","resultStatus","cancelStatus","queryStatus"}){
                            Object value=receipt.opt(name);if(value instanceof String&&((String)value).matches("[a-z][a-z0-9_]{0,79}"))facts.put(name,value);
                        }
                        for(String name:new String[]{"processStarted","notStartedConfirmed","nativeSettled","resourcesHeld","providerJobIdConfirmed"})if(receipt.opt(name) instanceof Boolean)facts.put(name,receipt.get(name));
                        JSONArray approvals=receipt.optJSONArray("pendingApprovals");if(approvals!=null)facts.put("reportedPendingApprovalCount",approvals.length());
                    }
                    item.put("reportedFacts",facts).put("reportedFactsSource","original_operation_receipt_not_a_live_observation");
                    if("remote_start_task".equals(tool)&&!"approval_intent".equals(item.optString("entryKind")))item.put("entryKind","delegation_intent").put("detailReason","child_relationship_not_collapsed_without_original_native_proof");
                    else if("teams_start".equals(tool)){
                        if(!"approval_intent".equals(item.optString("entryKind")))item.put("entryKind","team_start_intent");
                        try(Cursor link=db.rawQuery("SELECT child_task_id FROM team_parent_plans WHERE task_id=? AND start_run_id=? AND start_epoch=? AND start_operation_id=?",new String[]{ownerTask,run,String.valueOf(epoch),operation})){
                            if(link.moveToFirst()&&!link.isNull(0))item.put("relatedTaskId",link.getString(0)).put("relationshipSource","original_team_parent_plan");
                        }
                    }
                }
                entries.put(item);
            }
        }
        String nextCursor=more?encode(new JSONObject().put("v",1).put("filterVersion",1).put("section","jobs").put("anchorTaskId",task).put("conversationId",conversation).put("upper",upper).put("last",next)):"";
        return new JSONObject().put("format","pocketworld.task-audit-page.v1").put("section","jobs").put("taskId",task).put("conversationId",conversation)
                .put("scope",conversation.isEmpty()?"original_task_without_conversation":"original_private_conversation")
                .put("entries",entries).put("nextCursor",nextCursor).put("hasMore",more).put("upperPosition",upper).put("rowsScanned",scanned)
                .put("filteredCandidates",filtered).put("collapsedOriginalDelegations",collapsed).put("headerBytesRead",budget.headers).put("bodyBytesRead",budget.bodies)
                .put("planBytesRead",budget.plans).put("relationshipSourceBytesBudgeted",budget.relations).put("readLimited",budget.limited)
                .put("executionAuthorized",false).put("completeHistory",false).put("liveStatusQueried",false)
                .put("coverage","fixed_original_launch_operations_in_bounded_keyset_window;legacy_tasks_without_launch_operations_and_teams_without_conversation_proof_not_included;not_a_live_execution_snapshot");
    }
    private static boolean project(SQLiteDatabase db,JSONObject item,JSONObject arguments,JSONObject receipt,Budget budget)throws Exception {
        try{
            if(arguments.has("planRef")){
                try(Cursor plan=db.rawQuery("SELECT bytes FROM project_plans WHERE plan_ref=?",new String[]{arguments.getString("planRef")})){
                    if(!plan.moveToFirst()||plan.getLong(0)<0||plan.getLong(0)>PLAN_BUDGET-budget.plans){budget.limited=true;item.put("status","read_limited").put("detailReason","original_plan_exceeds_directory_budget");return true;}budget.plans+=plan.getInt(0);
                }
            }
            JSONObject binding=new JSONObject().put("taskId",item.getString("taskId")).put("runId",item.getString("runId")).put("coordinatorEpoch",item.getInt("epoch")).put("operationId",item.getString("operationId"));
            JSONObject request=ProjectPlanRecords.resolve(db,binding,arguments),plan=request.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,request.getString("stepId")));
            String command=exact.optString("command");if(!"managed_process_v1".equals(exact.optString("runtimeId"))||!Set.of("project.runtime.run","project.runtime.prepare").contains(command))return false;
            if(!"approval_intent".equals(item.optString("entryKind")))item.put("entryKind",command.equals("project.runtime.prepare")?"runtime_preparation_record":"project_process_record");
            if(receipt==null){item.put("detailReason","original_launch_receipt_not_available");return true;}
            JSONObject route=receipt.optJSONObject("projectExecution"),backend=receipt.optJSONObject("executionReceipt");
            if(route==null||backend==null){item.put("detailReason","original_launch_receipt_not_available");return true;}
            for(String key:new String[]{"taskId","runId","coordinatorEpoch","operationId"})if(!binding.get(key).equals(route.opt(key)))throw new SecurityException("原作业回执归属不一致");
            RuntimeExecutionRef.fromHost(exact,route).requireReceipt(backend);
            JSONObject facts=new JSONObject();for(String key:new String[]{"nativeStartObserved","processExited","processGroupAbsent","resourcesReleased","stopConfirmed","receiptReceived","ownedWorkerLive","cancelRequested"})if(backend.opt(key) instanceof Boolean)facts.put(key,backend.get(key));
            item.put("reportedFacts",facts).put("reportedFactsSource","original_project_execution_receipt").put("projectLogCandidate",command.equals("project.runtime.run"));
            return true;
        }catch(SecurityException|IllegalArgumentException|org.json.JSONException invalid){item.put("detailReason","original_project_source_not_verified").put("projectLogCandidate",false);return true;}
    }
    private static boolean originalRemoteChildRepresented(TaskStore authority,SQLiteDatabase db,String task,String run,String operation,String conversation,long upper,Budget budget)throws Exception {
        String child=UUID.nameUUIDFromBytes((task+":"+run+":"+operation+":remote-child").getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject childHeader=budget.header(db,child);if(childHeader==null||!"owner".equals(childHeader.optString("actorId"))||!"private".equals(childHeader.optString("spaceId"))||!conversation.equals(childHeader.optString("conversationId")))return false;
        try(Cursor original=db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool='remote_job' AND rowid<=?",new String[]{child,child,child,Long.toString(upper)})){if(!original.moveToFirst())return false;}
        long bytes=0;for(String id:new String[]{task,child})try(Cursor source=db.rawQuery("SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=?",new String[]{id})){if(!source.moveToFirst()||source.isNull(0))return false;bytes+=source.getLong(0);}
        try(Cursor source=db.rawQuery("SELECT length(CAST(arguments AS BLOB)) FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{task,run,operation})){if(!source.moveToFirst())return false;bytes+=source.getLong(0);}
        if(bytes<0||bytes>RELATION_BUDGET-budget.relations){budget.limited=true;return false;}budget.relations+=(int)bytes;
        try{JSONObject proof=authority.requirePrivateRemoteChild(child);return task.equals(proof.optString("parentTaskId"))&&run.equals(proof.optString("parentRunId"))&&operation.equals(proof.optString("parentOperationId"));}
        catch(SecurityException|IllegalArgumentException|org.json.JSONException unavailable){return false;}
    }
    private static String domain(String tool){return tool.equals("project_execute")?"project":tool.startsWith("remote_")?"remote":tool.equals("teams_start")?"child_work":"media";}
    private static long exactLong(JSONObject value,String key)throws Exception{Object number=value.get(key);if(!(number instanceof Integer||number instanceof Long))throw new IllegalArgumentException("原作业目录游标不是整数");return ((Number)number).longValue();}
    private static String encode(JSONObject value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject decode(String value)throws Exception{try{return new JSONObject(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8));}catch(Exception invalid){throw new IllegalArgumentException("原作业目录游标不可读",invalid);}}
}
