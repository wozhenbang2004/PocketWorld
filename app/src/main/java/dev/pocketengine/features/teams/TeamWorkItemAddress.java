package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared value identity of an original work item, including its current assignment and owner generation. */
record TeamWorkItemAddress(String workItemId, int itemRevision, int ownerGeneration, String memberId, String assignmentId) {
    static TeamWorkItemAddress of(JSONObject value) throws Exception {
        return new TeamWorkItemAddress(value.getString("workItemId"),value.getInt("itemRevision"),
                value.getInt("ownerGeneration"),value.getString("memberId"),value.getString("assignmentId"));
    }
    JSONObject view() throws Exception {
        return new JSONObject().put("workItemId",workItemId).put("itemRevision",itemRevision).put("ownerGeneration",ownerGeneration)
                .put("memberId",memberId).put("assignmentId",assignmentId);
    }
    /** Short lookup label only. The native issuer still compares the full current address on use. */
    String peerRef(String team) throws Exception {
        String key=new JSONArray().put(team).put(workItemId).put(itemRevision).put(ownerGeneration).put(memberId).put(assignmentId).toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    }
    static boolean hasFrozenReceipts(JSONObject item)throws Exception{
        if(!"completed".equals(item.optString("status")))return false;
        JSONArray refs=item.optJSONArray("artifacts");if(refs==null||refs.length()==0)return false;
        for(int n=0;n<refs.length();n++){
            JSONObject ref=refs.optJSONObject(n);if(ref==null||ref.optString("artifactId").isEmpty()
                    ||ref.optString("runId").isEmpty()||!item.getString("assignmentId").equals(ref.optString("taskId"))
                    ||!item.getString("assignmentId").equals(ref.optString("assignmentId"))
                    ||!item.getString("memberId").equals(ref.optString("memberId")))return false;
        }return true;
    }
    static boolean ended(JSONObject item){return item.optString("status").matches("completed|cancelled");}
}
