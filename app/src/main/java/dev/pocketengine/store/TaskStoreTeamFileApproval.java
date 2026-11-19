package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** An unconsumed approval by itself does not prove its file was not already sent. */
final class TaskStoreTeamFileApproval {
    private TaskStoreTeamFileApproval(){}
    static boolean candidate(JSONObject task,String review,boolean liveOnly){
        JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject a=approvals.optJSONObject(i),cap=a==null?null:a.optJSONObject("capability");
            if(cap==null||!"team_integrate_file".equals(a.opt("tool"))||!review.equals(cap.opt("reviewId")))continue;
            if(!liveOnly||"approved".equals(a.opt("state"))&&task.optString("runId").equals(a.opt("runId"))&&a.optLong("expiresAt")>System.currentTimeMillis())return true;
        }return false;
    }
    static JSONObject source(SQLiteDatabase db,JSONObject binding,JSONObject a,String run,int epoch,JSONObject pool)throws Exception{
        JSONObject wanted=a.getJSONObject("arguments"),old=a.optJSONObject("teamFileApprovalOrigin");
        String task=binding.getString("taskId"),approval=a.getString("id"),sourceOp=old==null?"":old.getString("operationId");
        String query="SELECT operation_id,CASE WHEN length(CAST(arguments AS BLOB))<=4096 THEN arguments END,CASE WHEN length(CAST(receipt AS BLOB))<=32768 THEN receipt END FROM operations WHERE task_id=? AND run_id=? AND tool='team_integrate_file' AND state='receipt'"+
                (old==null?" ORDER BY rowid DESC LIMIT 401":" AND operation_id=?");
        String match=null;try(Cursor rows=db.rawQuery(query,old==null?new String[]{task,run}:new String[]{task,run,sourceOp})){
            int count=0;while(rows.moveToNext()){
                if(++count>400)throw new SecurityException("原文件操作超出可核对范围，未选择近似回执。");
                if(rows.isNull(1)||rows.isNull(2))throw new SecurityException("原文件操作记录不可完整读取，未继续。");
                JSONObject args=new JSONObject(rows.getString(1)),r=new JSONObject(rows.getString(2));
                if(!approval.equals(r.opt("approvalId")))continue;
                if(match!=null||args.length()!=2||!wanted.getString("path").equals(args.opt("path"))||!wanted.getString("artifactId").equals(args.opt("artifactId"))
                        ||!"approval_required".equals(r.opt("code"))||!"pending".equals(r.opt("approvalState"))
                        ||!Boolean.FALSE.equals(r.opt("ok"))||!Boolean.FALSE.equals(r.opt("dispatch"))||!Boolean.FALSE.equals(r.opt("accepted"))||r.has("teamIntegrated"))
                    throw new SecurityException("原批准与唯一未派发文件回执不符，未继续。");
                match=rows.getString(0);
            }
        }
        if(match==null||!match.matches("[a-f0-9-]{36}"))throw new SecurityException("找不到这份批准对应的原未派发回执，不能仅凭按钮继续。");
        // The original claim never reached the parent's reservation; absence alone was not sufficient.
        TaskStoreTeamFileEffects.reservation(pool,new JSONObject(binding.toString()).put("runId",run).put("coordinatorEpoch",epoch),match,true);
        return new JSONObject().put("runId",run).put("epoch",epoch).put("operationId",match);
    }
}
