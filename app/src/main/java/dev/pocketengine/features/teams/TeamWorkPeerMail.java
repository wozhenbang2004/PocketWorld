package dev.pocketengine.features.teams;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A destination within the original, owner-reviewed phone work plan. Not a grant, delivery or wake. */
final class TeamWorkPeerMail {
    private final String run;
    private final JSONArray routes = new JSONArray();
    private final Set<String> localMembers = new HashSet<>();
    private boolean scopeVerified;

    private TeamWorkPeerMail(String run) { this.run = run; }

    static TeamWorkPeerMail capture(JSONObject run, JSONObject source, java.util.function.UnaryOperator<String> displayLabel) throws Exception {
        if (source == null) return null;
        JSONObject plan = run.getJSONObject("workPlan"), scope = run.getJSONObject("productionScope");
        JSONArray assignments = scope.getJSONArray("assignments"), members = run.getJSONArray("members"), items = plan.getJSONArray("items");
        String sender = source.getString("memberId");
        if (!onPhone(members, sender) || !localAssignment(assignments, source)) return null;
        TeamWorkPeerMail result = new TeamWorkPeerMail(run.getString("id"));
        if (!result.run.equals(plan.getString("teamRunId"))
                || !plan.getString("parentTaskId").equals(scope.getString("parentTaskId"))
                || !plan.getString("parentRunId").equals(scope.getString("parentRunId"))
                || !plan.getString("targetProjectId").equals(scope.getString("targetProjectId"))
                || !plan.optString("inventoryHash").equals(scope.optString("workItemInventoryHash")) || plan.optString("inventoryHash").isEmpty())
            return result; // Optional addressing cannot stop an otherwise valid original author.
        result.scopeVerified=true;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i); String member = item.getString("memberId");
            if (member.equals(sender) || !onPhone(members, member) || !localAssignment(assignments, item)) continue;
            result.localMembers.add(member);
            if (TeamWorkItemAddress.ended(item)) continue;
            TeamWorkItemAddress address = TeamWorkItemAddress.of(item);
            result.routes.put(address.view().put("targetWorkRef",address.peerRef(result.run)).put("title",displayLabel.apply(item.getString("title"))));
        }
        return result;
    }

    private static boolean onPhone(JSONArray members, String id) throws Exception {
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.getJSONObject(i);
            if (id.equals(member.getString("id"))) return !member.optBoolean("exited")
                    && "model".equals(member.optString("runner","model")) && "phone".equals(member.opt("device"));
        }
        return false;
    }

    private static boolean localAssignment(JSONArray assignments, JSONObject item) throws Exception {
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject row = assignments.getJSONObject(i);
            if (item.getString("workItemId").equals(row.optString("workItemId")))
                return item.getString("memberId").equals(row.optString("memberId")) && !row.has("remoteSelection");
        }
        return false;
    }

    /** Show only routing coordinates and the reviewed short item title, never paths, bytes, prompts or credentials. */
    JSONArray view() throws Exception {
        JSONArray visible = new JSONArray();
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.getJSONObject(i);
            visible.put(new JSONObject().put("targetMemberId",r.getString("memberId"))
                    .put("title",r.getString("title")).put("targetWorkRef",r.getString("targetWorkRef")));
        }
        return visible;
    }

    /** Resolve the exact ref that THIS author turn could see. No inference from a unique/live/latest item. */
    JSONObject select(JSONObject args) throws Exception {
        if(!scopeVerified)throw new SecurityException("原获准工作计划通讯范围不可核对，未发消息");
        String member = args.getString("targetMemberId");
        if (!args.has("targetWorkRef") && !localMembers.contains(member)) return null; // unchanged remote/legacy member route
        String ref = args.optString("targetWorkRef");
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.getJSONObject(i);
            if (r.getString("targetWorkRef").equals(ref) && member.equals(r.getString("memberId")))
                return new JSONObject(r.toString());
        }
        throw new SecurityException("请从 team_list_files 的 peerWorkItems 选择原收件工作项；没有按成员或标题改投");
    }

    static void checkCurrent(JSONObject run, JSONObject source, JSONObject args, JSONObject selected) throws Exception {
        TeamWorkPeerMail latest = capture(run,source,ignored -> "");
        if (latest == null) throw new SecurityException("原本机工作项通讯范围已变化");
        JSONObject now = latest.select(args);
        if (now == null && selected == null) return; // Original non-work recipient, still not a local file worker.
        if (now == null || selected == null || !TeamWorkItemAddress.of(selected).equals(TeamWorkItemAddress.of(now)))
            throw new SecurityException("收件工作项或原负责人已变化，消息未改投");
    }

    static boolean canReceive(JSONObject run, TeamWorkItemAddress original, JSONObject now, JSONObject letter) throws Exception {
        if (!letter.has("workItemTarget")) return true;
        if (original == null || !original.equals(TeamWorkItemAddress.of(now))
                || !original.equals(TeamWorkItemAddress.of(letter.getJSONObject("workItemTarget")))) return false;
        JSONObject scope=run.optJSONObject("productionScope");
        return scope!=null && onPhone(run.getJSONArray("members"),original.memberId())
                && localAssignment(scope.getJSONArray("assignments"),now);
    }

    static void retire(JSONObject run, JSONArray items) throws Exception {
        JSONArray mailbox=run.optJSONArray("mailbox");
        for (int m = 0; mailbox != null && m < mailbox.length(); m++) {
            JSONObject letter = mailbox.getJSONObject(m), target = letter.optJSONObject("workItemTarget");
            String status = letter.optString("status");
            if (target == null || !(status.equals("unread") || status.equals("delivering"))) continue;
            JSONObject now = null;
            for (int i = 0; i < items.length(); i++)
                if (target.getString("workItemId").equals(items.getJSONObject(i).getString("workItemId"))) now = items.getJSONObject(i);
            boolean replaced = now == null || !canReceive(run,TeamWorkItemAddress.of(target),now,letter);
            // The currently finishing original turn still owns its claimed letter until its real result settles.
            if (replaced || (status.equals("unread") && TeamWorkItemAddress.ended(now)))
                letter.put("status","historical").put("notDeliveredReason",replaced ? "peer_work_replaced" : "peer_work_ended");
        }
    }
}
