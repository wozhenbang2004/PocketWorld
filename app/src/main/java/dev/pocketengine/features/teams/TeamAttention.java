package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only navigation candidates. TaskStore and the original run retain all decision authority. */
public final class TeamAttention {
    private TeamAttention() { }

    /** Same read-only candidates for the owner inbox and an already opened run. */
    public static JSONArray forRun(JSONObject run) throws Exception {
        JSONArray output = new JSONArray();
        append(run, output);
        return output;
    }

    static void append(JSONObject run, JSONArray output) throws Exception {
        JSONArray members = run.optJSONArray("members");
        if (members == null || run.optString("status").matches("cancelled|completed")) return;
        JSONObject plan = run.optJSONObject("workPlan");
        for (int index = 0; index < members.length(); index++) {
            JSONObject member = members.getJSONObject(index);
            if (member.optBoolean("exited")) continue;
            if (plan == null) {
                if (member.optString("status").matches("cancelled|completed")) continue;
                JSONObject phase = currentPhase(member);
                JSONObject assignment = currentAssignment(run, member, member, member.optJSONObject("assignmentTask"));
                boolean hasQuestion = questions(run, member, assignment, null, output);
                JSONObject child = currentChild(run, member, phase);
                if (child != null && attentionStatus(null, child).length() > 0) {
                    String kind = phase.has("researchTaskId") ? "research" : "phone";
                    JSONObject row = base(run, member)
                            .put("id", member.getString("id") + ":" + kind + ":" + phase.getInt("round") + ":" + child.getString("taskId"))
                            .put("kind", "member_attention").put("childKind", kind).put("round", phase.getInt("round"))
                            .put("status", attentionStatus(null, child)).put("title", member.optString("assignment", member.optString("role")));
                    taskLink(row, child);
                    output.put(row);
                    continue;
                }
                String status = attentionStatus(member, assignment);
                if (!status.isEmpty() && !(hasQuestion && status.equals("awaiting_input"))) {
                    JSONObject row = base(run, member).put("id", member.getString("id") + ":member").put("kind", "member_attention")
                            .put("status", status).put("title", member.optString("assignment", member.optString("role")));
                    taskLink(row, assignment);
                    output.put(row);
                }
            } else {
                JSONArray items = plan.optJSONArray("items");
                for (int itemIndex = 0; items != null && itemIndex < items.length(); itemIndex++) {
                    JSONObject item = items.getJSONObject(itemIndex);
                    if (!member.getString("id").equals(item.optString("memberId"))) continue;
                    if (item.optString("status").matches("completed|cancelled")) continue;
                    JSONObject assignment = currentAssignment(run, member, item, item.optJSONObject("assignmentTask"));
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

    private static String attentionStatus(JSONObject owner, JSONObject child) {
        String ownStatus = owner == null ? "" : owner.optString("status");
        // A retained child receipt cannot turn a stopped/failed attempt back into a live request.
        String status = child == null || ownStatus.matches("completed|cancelled|failed|interrupted|paused") ? ownStatus : child.optString("status");
        if (!requiresAttention(status)) status = ownStatus;
        return requiresAttention(status) ? status : "";
    }

    private static boolean requiresAttention(String status) {
        return status.matches("awaiting_approval|awaiting_input|awaiting_user|needs_confirmation|blocked_unknown|failed|blocked|blocked_dependency|unsupported|interrupted");
    }

    private static JSONObject currentPhase(JSONObject member) {
        int round = member.optInt("round", -1);
        JSONArray phases = member.optJSONArray("roundStates");
        if (round < 1 || round > 6 || phases == null) return null;
        JSONObject selected = null;
        for (int index = 0; index < phases.length(); index++) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null || phase.optInt("round", -1) != round) continue;
            if (selected != null) return null;
            selected = phase;
        }
        return selected;
    }

    private static JSONObject currentChild(JSONObject run, JSONObject member, JSONObject phase) {
        if (phase == null || member.optBoolean("paused") || member.optString("status").matches("completed|cancelled|failed|interrupted|paused")
                || phase.optString("status").matches("completed|cancelled|failed|interrupted")
                || !sameNumber(phase, "generation", member.optInt("generation"))) return null;
        boolean research = phase.has("researchTaskId");
        JSONObject child = phase.optJSONObject(research ? "researchTask" : "phoneTask");
        if (child == null || !validTaskId(child.optString("taskId"))
                || research && !child.optString("taskId").equals(phase.optString("researchTaskId"))
                || !matchesIdentity(run, member, child)
                || child.has("round") && !sameNumber(child, "round", phase.optInt("round"))) return null;
        return child;
    }

    private static JSONObject currentAssignment(JSONObject run, JSONObject member, JSONObject owner, JSONObject assignment) {
        if (assignment == null || owner.optString("status").matches("completed|cancelled|failed|interrupted|paused")
                || assignment.optString("status").matches("completed|cancelled|failed|interrupted")
                || !matchesIdentity(run, member, assignment)
                || owner.has("assignmentId") && !owner.optString("assignmentId").equals(assignment.optString("assignmentId"))) return null;
        return assignment;
    }

    private static boolean matchesIdentity(JSONObject run, JSONObject member, JSONObject child) {
        return (!child.has("teamRunId") || run.optString("id").equals(child.optString("teamRunId")))
                && (!child.has("memberId") || member.optString("id").equals(child.optString("memberId")))
                && (!child.has("runGeneration") || sameNumber(child, "runGeneration", run.optInt("generation")))
                && (!child.has("memberGeneration") || sameNumber(child, "memberGeneration", member.optInt("generation")))
                && (!child.has("identityRevision") || sameNumber(child, "identityRevision", member.optInt("identityRevision", 1)));
    }

    private static boolean sameNumber(JSONObject value, String key, int expected) {
        Object actual = value.opt(key);
        return actual instanceof Number && ((Number) actual).doubleValue() == expected;
    }

    private static boolean validTaskId(String id) {
        return id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }
    private static void taskLink(JSONObject row, JSONObject task) throws Exception {
        if (task != null && validTaskId(task.optString("taskId"))) row.put("taskId", task.getString("taskId"));
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
            taskLink(row, assignment);
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
                .put("memberGeneration", member.optInt("generation")).put("identityRevision", member.optInt("identityRevision", 1)).put("updatedAt", run.optLong("updatedAt"))
                .put("source", "retained_team_snapshot").put("readOnly", true).put("executionAuthorized", false);
    }
}
