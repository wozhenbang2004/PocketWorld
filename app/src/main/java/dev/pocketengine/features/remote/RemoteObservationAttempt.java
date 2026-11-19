package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** An owner selection can veto a host read, never select a host, profile, worker, job or grant. */
public final class RemoteObservationAttempt {
    private final String task,run;private final int epoch;
    private RemoteObservationAttempt(String task,String run,int epoch){this.task=task;this.run=run;this.epoch=epoch;}
    private static SecurityException stale(){return new SecurityException("原任务已换执行轮次或不再可读，请回到当前本机记录再选择；未查询其他作业");}
    private static int epoch(JSONObject value,String field){Object n=value.opt(field);if(!(n instanceof Integer||n instanceof Long))throw stale();
        long number=((Number)n).longValue();if(number<0||number>Integer.MAX_VALUE)throw stale();return (int)number;}
    public static RemoteObservationAttempt shown(JSONObject row,JSONObject selection)throws Exception{
        if(row==null||!"owner".equals(row.opt("actorId"))||!"private".equals(row.opt("spaceId"))||!"remote".equals(row.opt("feature"))
                ||Boolean.FALSE.equals(row.opt("contextAvailable"))||row.optBoolean("contextWithdrawn")
                ||row.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(row.opt("taskProjection")))throw stale();
        Object id=selection.opt("taskId"),wanted=selection.opt("shownRunId");
        if(!(id instanceof String t)||!(wanted instanceof String r)||t.isBlank()||t.length()>80||r.isBlank()||r.length()>80||!t.equals(row.opt("id"))
                ||!r.equals(row.opt("runId"))||!r.equals(row.opt("operationId"))||epoch(selection,"shownEpoch")!=epoch(row,"coordinatorEpoch"))throw stale();
        return new RemoteObservationAttempt(t,r,epoch(row,"coordinatorEpoch"));
    }
    public void require(JSONObject nativeTarget){
        if(nativeTarget==null||!task.equals(nativeTarget.opt("taskId"))||!run.equals(nativeTarget.opt("runId"))
                ||!run.equals(nativeTarget.opt("jobOperationId"))||epoch!=epoch(nativeTarget,"coordinatorEpoch"))throw stale();
    }
}
