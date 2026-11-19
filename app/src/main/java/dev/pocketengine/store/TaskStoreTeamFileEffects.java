package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact operation addresses only. The detail window need not materialize the surrounding audit history. */
final class TaskStoreTeamFileEffects {
    private TaskStoreTeamFileEffects(){}
    static boolean integrator(JSONObject task){
        JSONObject origin=task.optJSONObject("origin");return origin!=null&&"team_integration".equals(origin.opt("kind"))&&
                "owner".equals(task.opt("actorId"))&&"private".equals(task.opt("spaceId"));
    }
    static JSONObject directory(SQLiteDatabase db,JSONObject task)throws Exception {
        JSONArray rows=new JSONArray();boolean more=false;
        try(Cursor c=db.rawQuery("SELECT operation_id,state,CASE WHEN length(CAST(arguments AS BLOB))<=4096 THEN arguments END FROM operations WHERE task_id=? AND run_id=? AND tool='team_integrate_file' AND state IN ('prepared','unknown') ORDER BY rowid LIMIT 9",
                new String[]{task.getString("id"),task.getString("runId")})){
            while(c.moveToNext()){
                if(rows.length()==8){more=true;break;}
                JSONObject args=c.isNull(2)?null:new JSONObject(c.getString(2));
                rows.put(new JSONObject().put("operationId",c.getString(0)).put("state",c.getString(1))
                        .put("path",args==null?"":args.optString("path")));
            }
        }
        return new JSONObject().put("taskId",task.getString("id")).put("runId",task.getString("runId"))
                .put("epoch",task.getInt("coordinatorEpoch")).put("rows",rows).put("hasMore",more);
    }
    static JSONObject reservation(JSONObject pool,JSONObject binding,String operation,boolean neverClaimed)throws Exception {
        JSONArray rows=pool.getJSONArray("reservations");JSONObject exact=null;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!("operation:"+operation).equals(row.opt("dedupKey")))continue;
            JSONArray ids=row.getJSONArray("operationIds");
            if(neverClaimed||exact!=null||!"integration".equals(row.opt("phase"))||!"unknown".equals(row.opt("state"))||ids.length()!=1||!operation.equals(ids.opt(0))||
                    !binding.getString("taskId").equals(row.opt("childTaskId"))||!binding.getString("runId").equals(row.opt("childRunId"))||
                    binding.getInt("coordinatorEpoch")!=row.optInt("childEpoch",-1)||!binding.getString("teamRunId").equals(row.opt("teamRunId"))||
                    !"integrator".equals(row.opt("memberId"))||!binding.getString("taskId").equals(row.opt("assignmentId"))||
                    !pool.getString("parentTaskId").equals(row.opt("parentTaskId"))||!pool.getString("parentRunId").equals(row.opt("parentRunId"))||
                    row.optInt("toolOperations")!=1||row.optInt("modelRequests",-1)!=0||row.optInt("externalJobs")!=0)
                throw new SecurityException("原文件操作没有唯一的原预算记录，未改动");
            exact=row;
        }
        if(exact==null&&!neverClaimed)throw new SecurityException("没有这笔文件操作的原派发记录；没有确认，也没有重发");
        return exact;
    }
}
