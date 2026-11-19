package dev.pocketengine.features.teams;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Occupancy of the existing member worker pool. Access only under the original coordinator monitor. */
final class TeamWorkSlots {
    static final int LIMIT=2;
    static final class Ticket {
        final TeamWorkItemAddress to;final int team,member;final String turn;boolean started;
        Ticket(JSONObject item,int team,int member,String turn)throws Exception{
            to=TeamWorkItemAddress.of(item);this.team=team;this.member=member;this.turn=turn;
        }
        boolean same(JSONObject item,int team,int member)throws Exception{
            return this.team==team&&this.member==member&&to.equals(TeamWorkItemAddress.of(item));
        }
    }
    private final Map<String,Ticket> active=new HashMap<>(),tried=new HashMap<>();
    private long returns;
    long returns(){return returns;}
    boolean busy(){return !active.isEmpty();}
    boolean working(int generation){for(Ticket t:active.values())if(t.team==generation&&t.started)return true;return false;}
    boolean full(){return active.size()>=LIMIT;}
    boolean alreadyTried(JSONObject item,int team,int member)throws Exception{
        Ticket old=tried.get(item.getString("workItemId"));return old!=null&&old.same(item,team,member);
    }
    boolean predecessorInSlot(JSONObject item)throws Exception{
        JSONArray edges=item.getJSONArray("dependsOn");for(int n=0;n<edges.length();n++)if(active.containsKey(edges.getString(n)))return true;
        return false;
    }
    Ticket claim(JSONObject item,int team,int member,String turn)throws Exception{
        Ticket ticket=new Ticket(item,team,member,turn);String id=ticket.to.workItemId();
        if(full()||active.containsKey(id)||alreadyTried(item,team,member))throw new IllegalStateException("原工作项已占工位或本次已派发");
        tried.put(id,ticket);active.put(id,ticket);return ticket;
    }
    void returned(Ticket ticket){if(active.remove(ticket.to.workItemId(),ticket))returns++;}
    void showLive(JSONObject item)throws Exception{
        Ticket ticket=active.get(item.getString("workItemId"));
        if(ticket!=null&&ticket.to.equals(TeamWorkItemAddress.of(item))&&queueState(item.optString("status"))){
            item.put("status",ticket.started?"running":"queued").put("turnId",ticket.turn).put("generation",ticket.member);
        }
    }
    /** No live worker for this member. "completed" requires every owned item to have its original frozen receipts. */
    static void settleIdleMember(JSONObject slot,JSONArray items)throws Exception {
        if(!"running".equals(slot.optString("status"))||slot.optBoolean("paused")||slot.optBoolean("exited"))return;
        String state="";boolean seen=false;
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);if(!slot.getString("id").equals(item.getString("memberId")))continue;
            seen=true;if(TeamWorkItemAddress.hasFrozenReceipts(item))continue;
            String actual=item.optString("status");if(actual.isEmpty()||"completed".equals(actual))actual="blocked_unknown";
            state=state.isEmpty()?actual:state.equals(actual)?state:"partial";
        }if(seen)slot.put("status",state.isEmpty()?"completed":state);
    }
    static boolean queueState(String status){return status.matches("queued|waiting_dependency|blocked_dependency");}

    /** The native coordinator observed its own failed call. This is not a TaskStore terminal/effect receipt. */
    static void failure(JSONObject run,Ticket ticket,boolean interrupted,String type,String detail)throws Exception{
        JSONObject rows=run.optJSONObject("workDispatchProblems");if(rows==null){rows=new JSONObject();run.put("workDispatchProblems",rows);}
        rows.put(ticket.to.workItemId(),ticket.to.view().put("teamGeneration",ticket.team).put("memberGeneration",ticket.member)
                .put("turnId",ticket.turn).put("at",System.currentTimeMillis()).put("status",interrupted?"interrupted":"failed")
                .put("nativeErrorType",type).put("nativeErrorMessage",detail.length()>800?detail.substring(0,800):detail)
                .put("message","原成员派发没有正常结算；仍以原任务记录核对，不会自动重试。"));
    }
    static void showFailure(JSONObject run,JSONObject item)throws Exception{
        JSONObject problems=run.optJSONObject("workDispatchProblems"),issue=problems==null?null:problems.optJSONObject(item.getString("workItemId"));
        if(issue==null||issue.getInt("teamGeneration")!=run.optInt("generation")||!TeamWorkItemAddress.of(item).equals(TeamWorkItemAddress.of(issue)))return;
        JSONArray members=run.getJSONArray("members");boolean current=false;
        for(int n=0;n<members.length();n++){JSONObject m=members.getJSONObject(n);if(issue.getString("memberId").equals(m.getString("id")))current=issue.getInt("memberGeneration")==m.getInt("generation");}
        if(!current)return;
        JSONObject previous=item.optJSONObject("dispatchProblem");
        if(previous!=null&&issue.getString("turnId").equals(previous.optString("turnId")))return;
        String plan=item.optString("status");JSONObject child=item.optJSONObject("assignmentTask");
        JSONObject problem=new JSONObject().put("status",issue.getString("status")).put("nativePlanStatus",plan)
                .put("turnId",issue.getString("turnId")).put("at",issue.getLong("at")).put("hasChildRecord",child!=null)
                .put("nativeErrorType",issue.getString("nativeErrorType")).put("effectVerified",false);
        if(child!=null)problem.put("nativeChildStatus",child.optString("status"));
        item.put("dispatchProblem",problem);
        if("completed".equals(plan)&&!TeamWorkItemAddress.hasFrozenReceipts(item)){
            item.put("status","blocked_unknown").put("message","原成员任务已返回，但该工作项未取得已核实的冻结成果；不放行后续。");
        }else if(queueState(plan)){
            String actual=child==null?"":child.optString("status");
            if(child==null||queueState(actual))item.put("status",issue.getString("status")).put("message",issue.getString("message"));
            else if(actual.matches("failed|interrupted|cancelled|awaiting_user|awaiting_input|awaiting_approval|blocked_unknown|running|preparing|stopping"))
                item.put("status",actual).put("message","原协调调用未正常结算；当前状态取自这项原成员任务。");
            else item.put("status","blocked_unknown").put("message","原协调调用没有取得可放行的冻结回执，请核对原成员任务。");
        }
        // Active, known-terminal, paused or undecided native work is not replaced by a coordinator outcome.
        if(!TeamWorkItemAddress.hasFrozenReceipts(item))item.put("nativeErrorMessage",issue.getString("nativeErrorMessage"));
    }
}
