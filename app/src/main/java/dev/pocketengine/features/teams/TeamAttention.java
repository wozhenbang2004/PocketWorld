package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only navigation candidates. TaskStore and the original run retain all decision authority. */
public final class TeamAttention {
    private TeamAttention() { }

    static void append(JSONObject run, JSONArray output) throws Exception {
        JSONArray members = run.optJSONArray("members");
        if (members == null || "cancelled".equals(run.optString("status"))) return;
        JSONObject plan = run.optJSONObject("workPlan");
        for (int index = 0; index < members.length(); index++) {
            JSONObject member = members.getJSONObject(index);
            if (member.optBoolean("exited")) continue;
            if (plan == null) {
                JSONObject assignment = member.optJSONObject("assignmentTask");
                boolean hasQuestion = questions(run, member, assignment, null, output);
                String status = attentionStatus(member, assignment);
                if (!status.isEmpty() && !(hasQuestion && status.equals("awaiting_input"))) {
                    JSONObject row = base(run, member).put("id", member.getString("id") + ":member").put("kind", "member_attention")
                            .put("status", status).put("title", member.optString("assignment", member.optString("role")));
                    JSONObject child = member.optJSONObject("phoneTask");
                    if (child == null) child = assignment;
                    taskLink(row, child);
                    output.put(row);
                }
            } else {
                JSONArray items = plan.optJSONArray("items");
                for (int itemIndex = 0; items != null && itemIndex < items.length(); itemIndex++) {
                    JSONObject item = items.getJSONObject(itemIndex);
                    if (!member.getString("id").equals(item.optString("memberId"))) continue;
                    JSONObject assignment = item.optJSONObject("assignmentTask");
                    boolean hasQuestion = questions(run, member, assignment, item, output);
                    String status = attentionStatus(item, assignment);
                    if (status.isEmpty() || hasQuestion && status.equals("awaiting_input")) continue;
                    JSONObject row = base(run, member).put("id", member.getString("id") + ":item:" + item.getString("workItemId"))
                            .put("kind", "member_attention").put("workItemId", item.getString("workItemId"))
                            .put("status", status).put("title", item.optString("title", item.optString("instruction")));
                    if (!item.optString("assignmentId").isEmpty()) row.put("assignmentId", item.getString("assignmentId"));
                    taskLink(row, assignment);
                    output.put(row);
                }
            }
        }
    }

    private static String attentionStatus(JSONObject owner, JSONObject assignment) {
        String status = assignment == null ? "" : assignment.optString("status");
        if (!status.matches("awaiting_approval|awaiting_input|awaiting_user|blocked_unknown")) status = owner.optString("status");
        return status.matches("awaiting_approval|awaiting_input|awaiting_user|blocked_unknown") ? status : "";
    }
    private static void taskLink(JSONObject row, JSONObject task) throws Exception {
        if (task != null && !task.optString("taskId").isEmpty()) row.put("taskId", task.getString("taskId"));
    }

    private static boolean questions(JSONObject run, JSONObject member, JSONObject assignment, JSONObject item, JSONArray output) throws Exception {
        if (assignment == null || assignment.optString("assignmentId").isEmpty()) return false;
        boolean found = false;
        JSONArray questions = assignment.optJSONArray("questions");
        for (int index = 0; questions != null && index < questions.length(); index++) {
            JSONObject question = questions.getJSONObject(index);
            if (!"pending".equals(question.optString("state")) || question.optString("questionId").isEmpty()) continue;
            JSONObject row = base(run, member).put("id", member.getString("id") + ":question:" + assignment.getString("assignmentId") + ":" + question.getString("questionId")).put("kind", "question").put("status", "awaiting_input")
                    .put("assignmentId", assignment.getString("assignmentId")).put("questionId", question.getString("questionId"))
                    .put("title", question.optString("question")).put("askedAt", question.optLong("askedAt"));
            if (!assignment.optString("taskId").isEmpty()) row.put("taskId", assignment.getString("taskId"));
            if (item != null) row.put("workItemId", item.getString("workItemId"));
            JSONObject identity = question.optJSONObject("memberSnapshot");
            if (identity != null) row.put("memberName", identity.optString("name", member.optString("name"))).put("avatarShape", identity.optString("avatarShape"));
            output.put(row); found = true;
        }
        return found;
    }

    private static JSONObject base(JSONObject run, JSONObject member) throws Exception {
        return new JSONObject().put("runId", run.getString("id")).put("teamName", run.optString("teamName"))
                .put("goal", run.optString("goal")).put("memberId", member.getString("id")).put("memberName", member.optString("name"))
                .put("avatarShape", member.optString("avatarShape")).put("generation", run.optInt("generation"))
                .put("memberGeneration", member.optInt("generation")).put("updatedAt", run.optLong("updatedAt"))
                .put("source", "retained_team_snapshot").put("readOnly", true).put("executionAuthorized", false);
    }
}
