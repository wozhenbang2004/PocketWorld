package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Routing for already-admitted work in the existing coordinator. Never a grant or a restart journal. */
final class TeamWorkItemDispatch {
    private record Target(int teamGeneration,int memberGeneration,long serial,String memberId,String itemId,
                          String assignmentId,int itemRevision,int ownerGeneration) {
        boolean identifies(JSONObject item) {
            return itemId.equals(item.optString("workItemId")) && assignmentId.equals(item.optString("assignmentId"))
                    && memberId.equals(item.optString("memberId")) && itemRevision==item.optInt("itemRevision",-1)
                    && ownerGeneration==item.optInt("ownerGeneration",-1);
        }
    }
    private final Map<String,Target> targets=new LinkedHashMap<>(),standingPlan=new LinkedHashMap<>();
    private int wholePlanGeneration=-1;
    private long serial;
    // Keep the independently scheduled plan, not whatever happens to be readable
    // when a later single-item request makes its member available again.
    void requestPlan(int generation,JSONArray members,JSONArray items){
        standingPlan.clear();wholePlanGeneration=generation;
        for(int i=0;items!=null&&i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;
            for(int j=0;members!=null&&j<members.length();j++){
                JSONObject member=members.optJSONObject(j);
                if(member==null||!member.optString("id").equals(item.optString("memberId"))||member.optBoolean("paused")||member.optBoolean("exited"))continue;
                Target target=target(generation,member,item,0);
                if(!target.itemId().isEmpty()&&!target.assignmentId().isEmpty()&&target.itemRevision()>0&&target.ownerGeneration()>=0&&target.memberGeneration()>=0)standingPlan.put(target.itemId(),target);
                break;
            }
        }
    }
    boolean hasPlan(int generation){return wholePlanGeneration==generation;}
    boolean takePlan(int generation){boolean same=hasPlan(generation);wholePlanGeneration=-1;return same;}
    long serial(){return serial;}
    boolean pendingSince(int generation,long mark){
        for(Target target:targets.values())if(target.teamGeneration()==generation&&target.serial()>mark)return true;
        return false;
    }
    /** Invalidated revisions are never moved to a different file, assignment or owner. */
    void prune(int generation,JSONArray members,JSONArray items) {
        targets.values().removeIf(target->{
            if(target.teamGeneration()!=generation)return true;
            boolean live=false;
            for(int i=0;i<members.length();i++){JSONObject member=members.optJSONObject(i);if(member!=null&&target.memberId().equals(member.optString("id"))){
                live=!member.optBoolean("paused")&&!member.optBoolean("exited")&&member.optInt("generation",-1)==target.memberGeneration();break;}}
            if(!live)return true;
            for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&target.identifies(item))return false;}
            return true;
        });
    }
    boolean hasMember(String member){return targets.containsKey(member);}
    private static Target target(int generation,JSONObject member,JSONObject item,long seq){
        return new Target(generation,member.optInt("generation",-1),seq,member.optString("id"),item.optString("workItemId"),
                item.optString("assignmentId"),item.optInt("itemRevision",-1),item.optInt("ownerGeneration",-1));
    }
    void add(int generation,JSONObject member,JSONObject item) {
        Target target=target(generation,member,item,++serial);targets.put(target.memberId(),target);
    }
    boolean accepts(int generation,JSONObject member,JSONObject item,boolean planEligible) {
        Target requested=targets.get(member.optString("id"));
        if(requested!=null)return requested.identifies(item);
        Target original=standingPlan.get(item.optString("workItemId"));
        return planEligible&&original!=null&&original.teamGeneration()==generation&&original.memberGeneration()==member.optInt("generation",-1)&&original.identifies(item);
    }
    /** Only a real frozen predecessor can unblock its already scheduled successors. */
    static boolean newlyUnblocked(Map<String,JSONObject> submitted,JSONArray items,JSONObject item) {
        JSONArray depends=item.optJSONArray("dependsOn");
        for(int i=0;depends!=null&&i<depends.length();i++){
            String id=depends.optString(i);JSONObject prior=submitted.get(id);if(prior==null)continue;
            for(int j=0;j<items.length();j++){JSONObject now=items.optJSONObject(j);
                if(now!=null&&id.equals(now.optString("workItemId"))&&prior.optString("assignmentId").equals(now.optString("assignmentId"))
                        &&prior.optInt("itemRevision",-1)==now.optInt("itemRevision",-1)&&prior.optInt("ownerGeneration",-1)==now.optInt("ownerGeneration",-1)
                        &&prior.optString("memberId").equals(now.optString("memberId"))&&"completed".equals(now.optString("status"))
                        &&now.optJSONArray("artifacts")!=null&&now.optJSONArray("artifacts").length()>0)return true;
            }
        }
        return false;
    }
    void submitted(JSONObject item) {
        Target target=targets.get(item.optString("memberId"));if(target!=null&&target.identifies(item))targets.remove(target.memberId());
    }
}
