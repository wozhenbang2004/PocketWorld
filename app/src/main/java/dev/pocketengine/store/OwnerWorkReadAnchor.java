package dev.pocketengine.store;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A restriction on an existing private work read, never a grant, a lookup or a stored result. */
record OwnerWorkReadAnchor(String task, String run, int epoch, boolean ownerSelected) {
    private static boolean exactId(String s) {
        try { return s != null && UUID.fromString(s).toString().equalsIgnoreCase(s); }
        catch (IllegalArgumentException bad) { return false; }
    }
    static OwnerWorkReadAnchor bind(JSONObject source,String sourceRun,int sourceEpoch,String task,String requestedRun,int requestedEpoch)throws Exception {
        OwnerWorkReadAnchor asked=null, attached=null;
        if(requestedRun!=null || requestedEpoch!=0) {
            if(!exactId(task)||!exactId(requestedRun)||requestedEpoch<1)throw new IllegalArgumentException("读取原工作须同时给出确切任务、runId及正epoch");
            asked=new OwnerWorkReadAnchor(task,requestedRun,requestedEpoch,false);
        }
        // A legacy directory read and an automatic delivery takeover retain their own admission.
        // Pending/withdrawn messages, other runs and forwarded work inputs cannot choose this target.
        if(OwnerWorkTask.isButlerSource(source)&&!source.has("ownerWorkTakeover")) {
            JSONArray rows=source.optJSONArray("ownerInputs");
            for(int i=0;rows!=null&&i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);JSONArray refs=row.optJSONArray("ownerObjectRefs");
                if(refs==null||row.has("ownerWorkRoute")||!source.getString("id").equals(row.optString("acceptedTaskId")))continue;
                String state=OwnerInputQueue.state(row);
                boolean included="materialized".equals(state)&&sourceRun.equals(row.opt("materializedRunId"))&&sourceEpoch==OwnerInputQueue.integer(row.opt("materializedEpoch"));
                boolean initial="admitted".equals(state)&&"task_goal".equals(row.opt("queueRole"))&&sourceRun.equals(row.opt("acceptedRunId"))&&sourceEpoch==OwnerInputQueue.integer(row.opt("acceptedEpoch"));
                if(!included&&!initial)continue;
                for(int j=0;j<refs.length();j++) {JSONObject ref=refs.getJSONObject(j);
                    if(!"work".equals(ref.opt("kind"))||!task.equals(ref.opt("id")))continue;
                    long n=OwnerInputQueue.integer(ref.opt("epoch"));String run=ref.getString("runId");
                    if(!exactId(task)||!exactId(run)||n<1||n>Integer.MAX_VALUE)throw new IllegalStateException("原输入的工作轮次不能核对，未读取正文");
                    OwnerWorkReadAnchor next=new OwnerWorkReadAnchor(task,run,(int)n,true);
                    if(attached!=null&&!attached.equals(next))throw new IllegalStateException("本轮输入关联了同一任务的不同轮次，请先明确哪一轮，未读取正文");
                    attached=next;
                }
            }
        }
        if(attached!=null&&asked!=null&&(!attached.run.equals(asked.run)||attached.epoch!=asked.epoch))
            throw new IllegalStateException("所填轮次与主人在本轮关联的原工作不同，未读取正文");
        return attached!=null?attached:asked;
    }
    boolean matches(JSONObject value,String epochField) { return run.equals(value.opt("runId"))&&epoch==OwnerInputQueue.integer(value.opt(epochField)); }
    boolean checkpointed(JSONObject row) {return task.equals(row.opt("acceptedTaskId"))&&"materialized".equals(OwnerInputQueue.state(row))
            &&run.equals(row.opt("materializedRunId"))&&epoch==OwnerInputQueue.integer(row.opt("materializedEpoch"));}
    void requireTask(JSONObject original) {if(!task.equals(original.opt("id"))||!matches(original,"coordinatorEpoch"))
        throw new IllegalStateException("原工作已换轮；未读取新一轮的要求或成果。请在原件中核对是否改接新一轮。");}
    JSONObject receipt()throws Exception{return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch)
            .put("source",ownerSelected?"owner_link_on_current_input":"exact_reader_arguments").put("executionAuthorized",false);}
    JSONObject scoped(JSONObject reply)throws Exception {
        JSONObject result=reply.getJSONObject("result");if(result.opt("available")==Boolean.TRUE){
            if(!task.equals(result.opt("taskId"))||!matches(result,"epoch"))throw new IllegalStateException("交回的结果不属于所选轮次，未提供正文");
            result.put("requiredRunId",run).put("requiredEpoch",epoch);
        }
        JSONObject basis=receipt().put("resultState",result.opt("available")==Boolean.TRUE?"retained_delivery_from_selected_round":"no_readable_delivery_for_selected_round")
                .put("contractMeaning","work_order_not_verbatim_owner_approval").put("thisReadStartedWork",false);
        if(result.opt("available")==Boolean.TRUE)basis.put("deliveryId",result.getString("deliveryId"));
        return reply.put("readTarget",basis);
    }
}
