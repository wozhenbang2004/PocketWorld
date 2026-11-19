package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Selects an existing file work item, not another job or another copy of its mailbox. */
final class TeamWorkItemMail {
    private TeamWorkItemMail() {}

    static JSONObject select(JSONObject run, JSONObject request, String member) throws Exception {
        JSONObject plan=run.optJSONObject("workPlan");
        boolean named=request.has("workItemId"), byAssignment=request.has("assignmentId");
        if(!named&&(plan==null||!byAssignment)){
            if(request.has("itemRevision")||request.has("ownerGeneration"))throw new SecurityException("制作补充缺少原工作项");
            return null;
        }
        if(plan==null)throw new SecurityException("该团队没有这份工作计划");
        JSONArray items=plan.getJSONArray("items");
        JSONObject item=named?TeamWorkItems.find(items,request.getString("workItemId")):null;
        if(!named)for(int i=0;i<items.length();i++){JSONObject candidate=items.getJSONObject(i);
            if(request.getString("assignmentId").equals(candidate.optString("assignmentId"))){if(item!=null)throw new SecurityException("原分工身份不唯一");item=candidate;}
        }
        if(item==null||!member.equals(item.getString("memberId"))||byAssignment&&!request.getString("assignmentId").equals(item.getString("assignmentId"))
                ||request.has("itemRevision")&&request.getInt("itemRevision")!=item.getInt("itemRevision")
                ||request.has("ownerGeneration")&&request.getInt("ownerGeneration")!=item.getInt("ownerGeneration"))
            throw new SecurityException("原工作项、分工版本或负责人已变化；未改投成员的其他工作");
        return item;
    }
    static boolean sameDestination(JSONObject letter, JSONObject item) throws Exception {
        JSONObject bound=letter.optJSONObject("workItemTarget");
        // Legacy rows without a complete binding are never reused for a scoped request.
        if(bound==null)return item==null&&!letter.has("workItemId");
        return item!=null&&TeamWorkItems.sameIdentity(bound,item);
    }
    static boolean forTurn(JSONObject letter, JSONObject current) throws Exception {
        return !letter.has("workItemTarget") || current!=null&&TeamWorkItems.sameIdentity(letter.getJSONObject("workItemTarget"),current);
    }
    static boolean ended(JSONObject item) {return item.optString("status").matches("completed|cancelled");}

    /** A reassignment/revision retains the original delivery; it is never made generic again. */
    static void retireWaiting(JSONArray inbox, JSONArray items) throws Exception {
        for(int n=0;inbox!=null&&n<inbox.length();n++){
            JSONObject letter=inbox.getJSONObject(n),bound=letter.optJSONObject("workItemTarget");
            if(bound==null||letter.optBoolean("nativeAssignmentInput")||!"pending".equals(letter.optString("status")))continue;
            JSONObject current=null;
            for(int i=0;i<items.length();i++)if(bound.getString("workItemId").equals(items.getJSONObject(i).optString("workItemId"))){current=items.getJSONObject(i);break;}
            if(current==null||!TeamWorkItems.sameIdentity(bound,current)||ended(current))
                letter.put("status","historical").put("notDeliveredReason","work_item_no_longer_current");
        }
    }
}
