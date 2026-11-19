package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Delivery metadata of ONE message from this parent. Never return inbox rows, text, quoted data or peer mail. */
final class TeamParentMessageDelivery {
    private TeamParentMessageDelivery() {}
    static JSONObject read(JSONObject run, String parent, String id) throws Exception {
        JSONArray inbox=run.optJSONArray("inbox");JSONObject original=null;
        for(int i=0;inbox!=null&&i<inbox.length();i++){
            JSONObject row=inbox.getJSONObject(i);
            if(!id.equals(row.optString("id")))continue;
            if(original!=null||parent.isEmpty()||!parent.equals(row.optString("senderParentTaskId"))||!"queue".equals(row.optString("mode")))throw unavailable();
            original=row;
        }
        if(original==null||original.optString("targetMemberId").isEmpty()||"*".equals(original.optString("targetMemberId")))throw unavailable();
        String member=original.getString("targetMemberId");
        JSONObject receipt=new JSONObject().put("teamRunId",run.getString("id")).put("messageId",id).put("recipientMemberId",member)
                .put("saved",true).put("executionAuthorized",false).put("changeAppliedVerified",false);
        int round=original.optInt("researchRound",-1);if(round>=1&&round<=6)receipt.put("researchRound",round);
        for(String key:new String[]{"researchTaskId","workItemId","assignmentId"}){
            Object identity=original.opt(key);if(identity instanceof String&&((String)identity).matches("[a-f0-9-]{36}"))receipt.put(key,identity);
        }
        JSONObject bound=original.optJSONObject("workItemTarget");
        if(bound!=null&&member.equals(bound.optString("memberId")))for(String key:new String[]{"itemRevision","ownerGeneration"})if(bound.has(key))receipt.put(key,bound.getInt(key));
        if(original.optBoolean("nativeAssignmentInput"))
            return receipt.put("source","assignment_inbox").put("state","unconfirmed");
        boolean selected=false,acknowledged=false,byMember=false;
        JSONArray deliveries=original.optJSONArray("deliveries"),by=original.optJSONArray("consumedBy");
        for(int i=0;by!=null&&i<by.length();i++)if(member.equals(by.optString(i)))byMember=true;
        for(int i=0;deliveries!=null&&i<deliveries.length();i++){
            JSONObject d=deliveries.getJSONObject(i);
            if(!member.equals(d.optString("memberId"))||d.optString("turnId").isEmpty()
                    ||original.has("workItemId")&&!original.getString("workItemId").equals(d.optString("workItemId"))
                    ||original.has("researchRound")&&original.getInt("researchRound")!=d.optInt("round")
                    ||original.has("researchTaskId")&&!original.getString("researchTaskId").equals(d.optString("researchTaskId")))continue;
            if("consumed".equals(d.optString("state")))acknowledged=true;
            if("claimed".equals(d.optString("state")))selected=true;
        }
        String status=original.optString("status");
        String state=byMember&&acknowledged?"consumed":"historical".equals(status)?"historical":selected?"selected_for_input":
                status.matches("pending|unread|queued")?"queued":"unconfirmed";
        return receipt.put("source","original_member_turn").put("state",state);
    }
    private static SecurityException unavailable(){return new SecurityException("不能核对该父工作自己的原补充，不列出其他消息");}
}
