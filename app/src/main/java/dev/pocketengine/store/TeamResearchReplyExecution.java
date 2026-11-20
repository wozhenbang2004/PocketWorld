package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.teams.TeamWorkFiles;
import org.json.JSONObject;

/** Optional owner presentation over the exact final answer, never a model or execution grant. */
final class TeamResearchReplyExecution {
    private TeamResearchReplyExecution() { }

    static JSONObject read(SQLiteDatabase db,JSONObject reference,String team,String member,int round)throws Exception {
        String task=reference.getString("taskId"),run=reference.getString("runId");
        int epoch=reference.getInt("coordinatorEpoch"),index=reference.getInt("messageIndex"),chars=reference.getInt("chars");
        String hash=reference.getString("sha256");
        if(!"pocketworld.team-research-original.v1".equals(reference.opt("format"))||!uuid(task)||!uuid(run)||
                epoch<1||index<0||chars<1||!hash.matches("[a-f0-9]{64}"))return null;
        String call;
        // Do not materialize long histories just to show a model label on the team screen.
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(checkpoint AS BLOB))<=65536 THEN checkpoint END FROM runs WHERE task_id=? AND run_id=? AND epoch=? AND status='completed'",
                new String[]{task,run,Integer.toString(epoch)})){
            if(!row.moveToFirst()||row.isNull(0))return null;
            JSONObject saved=new JSONObject(row.getString(0));if(row.moveToNext())return null;
            JSONObject message=saved.getJSONArray("messages").optJSONObject(index);
            if(message==null||!"assistant".equals(message.opt("role"))||message.has("tool_calls")||
                    !(message.opt("content") instanceof String text)||text.length()!=chars||!hash.equals(TeamWorkFiles.hash(text)))return null;
            call=message.optString("nativeTraceCallId");if(!uuid(call))return null;
        }
        try(Cursor row=db.rawQuery("SELECT CASE WHEN e.body_bytes BETWEEN 1 AND 65536 AND length(CAST(e.event_json AS BLOB))=e.body_bytes THEN e.event_json END,q.logical_request_id FROM task_events e JOIN inference_requests q ON q.task_id=e.task_id AND q.chosen_call_id=e.call_id WHERE e.task_id=? AND e.call_id=? AND e.event_type='inference_receipt' AND q.run_id=? AND q.epoch=? AND q.state='SUCCEEDED' AND q.semantic_committed=1 LIMIT 2",
                new String[]{task,call,run,Integer.toString(epoch)})){
            if(!row.moveToFirst()||row.isNull(0))return null;
            JSONObject receipt=new JSONObject(row.getString(0));String logical=row.getString(1);if(row.moveToNext())return null;
            JSONObject context=receipt.optJSONObject("context"),route=receipt.optJSONObject("route");
            if(context==null||route==null||!call.equals(receipt.opt("callId"))||!"finished".equals(receipt.opt("state"))||
                    !"COMPLETED".equals(receipt.opt("outcome"))||!"HOST_RUN".equals(context.opt("bindingKind"))||!"MEMBER".equals(context.opt("purpose"))||
                    !task.equals(context.opt("taskId"))||!run.equals(context.opt("runId"))||epoch!=context.optInt("epoch",-1)||
                    !member.equals(context.opt("memberId"))||!logical.equals(route.opt("logicalRequestId")))return null;
            String kind=route.optString("kind");if(!kind.equals("api_key")&&!kind.equals("account"))return null;
            JSONObject shown=new JSONObject().put("format","pocketworld.team-research-reply-execution.v1")
                    .put("teamRunId",team).put("memberId",member).put("round",round).put("taskId",task).put("runId",run).put("epoch",epoch)
                    .put("answerSha256",hash).put("routeKind",kind).put("readOnly",true);
            String reported=model(route.opt("responseModel")),requested=model(route.opt("requestedModel"));
            if(!reported.isEmpty())shown.put("reportedModel",reported);else if(!requested.isEmpty())shown.put("requestedModel",requested);
            return shown;
        }
    }

    static boolean uuid(String value){return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static String model(Object value){
        if(!(value instanceof String text)||text.length()>200||text.trim().isEmpty()||
                text.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT))return "";
        return text.trim();
    }
}
