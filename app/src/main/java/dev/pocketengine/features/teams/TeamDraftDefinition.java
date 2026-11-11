package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** An immutable, one-use model roster; it carries no device or file execution grant. */
public final class TeamDraftDefinition {
    private TeamDraftDefinition() { }

    /** Data capture only. The native caller must still prove the original owner-work,
     * prepared operation, exact approval and captured model before dispatch. */
    public static JSONObject forOwnerWork(String parentTaskId, JSONObject arguments) throws Exception {
        if (!UUID.fromString(parentTaskId).toString().equals(parentTaskId))
            throw new SecurityException("本次分工缺少原工作身份");
        fields(arguments, Set.of("goal", "name", "mode", "rounds", "leaderId", "members"));
        TeamParentTools.validate("teams_start", arguments);
        String goal = JsonFiles.text(arguments, "goal", 8000);
        JSONArray members = arguments.getJSONArray("members");
        for (int index = 0; index < members.length(); index++)
            fields(members.getJSONObject(index), Set.of("id", "name", "role", "assignment", "dependsOn"));
        // Approval retries keep the same roster identity; an actual dispatch still uses
        // its separate, original prepared operation ID and is never replayed by this key.
        String id = UUID.nameUUIDFromBytes(("pocketworld.owner-work-team.v1\n" + parentTaskId
                + "\n" + TaskStore.capabilityFingerprint(arguments)).getBytes(StandardCharsets.UTF_8)).toString();
        return capture(new JSONObject().put("id", id)
                .put("name", arguments.has("name") ? arguments.getString("name") : goal.substring(0, Math.min(48, goal.length())))
                .put("mode", arguments.optString("mode", "collaborate"))
                .put("leaderId", arguments.has("leaderId") ? arguments.getString("leaderId") : members.getJSONObject(0).getString("id"))
                .put("members", new JSONArray(members.toString())).put("definitionKind", "one_time"));
    }

    public static JSONObject capture(JSONObject supplied) throws Exception {
        fields(supplied, Set.of("id", "name", "mode", "leaderId", "members", "discussionFormat", "definitionKind"));
        String id = JsonFiles.text(supplied, "id", 36);
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("本次协作身份无效");
        String mode = JsonFiles.text(supplied, "mode", 24);
        if (!mode.equals("collaborate") && !mode.equals("debate"))
            throw new SecurityException("文件和设备协作请使用已配置编组的原范围审阅");
        if (supplied.has("definitionKind") && !"one_time".equals(supplied.opt("definitionKind")))
            throw new IllegalArgumentException("本次协作不是可复用编组");

        JSONArray raw = supplied.getJSONArray("members"), members = new JSONArray();
        if (raw.length() < 1 || raw.length() > 4) throw new IllegalArgumentException("请选择一至四名成员");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < raw.length(); index++) {
            JSONObject input = raw.getJSONObject(index);
            fields(input, Set.of("id", "name", "avatarShape", "role", "model", "runner", "device", "assignment", "dependsOn"));
            if (!"model".equals(input.optString("runner", "model"))
                    || input.has("device") && !"phone".equals(input.opt("device")))
                throw new SecurityException("快速协作使用已配置模型；手机与电脑成员请选择原编组");
            JSONObject member = AgentSlot.normalize(input, index);
            if (!ids.add(member.getString("id"))) throw new IllegalArgumentException("成员身份不能重复");
            members.put(member);
        }
        TeamWorkPlan.validate(members);
        String leader = JsonFiles.text(supplied, "leaderId", 64);
        if (!ids.contains(leader)) throw new IllegalArgumentException("请选择本次负责汇总的成员");
        String discussion = mode.equals("debate") ? TeamEvidenceDiscussion.FORMAT : "classic";
        if (supplied.has("discussionFormat") && !discussion.equals(supplied.opt("discussionFormat")))
            throw new IllegalArgumentException("协作方式与讨论阶段不一致");
        if (mode.equals("debate")) {
            if (members.length() < 2) throw new IllegalArgumentException("方案讨论需要至少两名独立成员");
            for (int index = 0; index < members.length(); index++)
                if (members.getJSONObject(index).getJSONArray("dependsOn").length() != 0)
                    throw new IllegalArgumentException("方案讨论的首轮独立展开，不设置前置成员");
        }
        return new JSONObject().put("id", id).put("name", JsonFiles.text(supplied, "name", 80))
                .put("mode", mode).put("leaderId", leader).put("members", members)
                .put("discussionFormat", discussion).put("definitionKind", "one_time");
    }

    private static void fields(JSONObject value, Set<String> allowed) {
        for (var keys = value.keys(); keys.hasNext(); )
            if (!allowed.contains(keys.next())) throw new SecurityException("本次协作包含不适用的配置字段");
    }
}
