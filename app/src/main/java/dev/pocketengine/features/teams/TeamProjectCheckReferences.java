package dev.pocketengine.features.teams;

import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model-facing handles for the original worker's owner-selected checks. A handle only resolves
 * an existing literal command for a proposal; it is not an approval, execution, or successful check. */
final class TeamProjectCheckReferences {
    static final String FIELD = "teamCheckRef";
    private TeamProjectCheckReferences() {}

    private static String ref(JSONObject binding,JSONObject context,String id)throws Exception {
        JSONObject selected=context.getJSONObject("selection");
        JSONArray key=new JSONArray().put("pocketworld.team-project-check-ref.v1")
                .put(binding.getString("teamRunId")).put(binding.getString("memberId")).put(binding.getString("assignmentId"))
                .put(binding.getString("taskId")).put(binding.getString("runId")).put(binding.getInt("coordinatorEpoch"))
                .put(context.getString("checkoutId")).put(selected.getString("selectionHash")).put(id);
        return ProjectSources.sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }
    static JSONObject index(JSONObject binding,JSONObject context)throws Exception {
        JSONObject result=new JSONObject().put("ready",context!=null).put("executionAuthorized",false).put("checksPassedInferred",false);
        if(context==null)return result;
        JSONObject selected=context.getJSONObject("selection");JSONArray templates=selected.getJSONArray("templates"),rows=new JSONArray();
        if(templates.length()<1||templates.length()>8)throw new SecurityException("原团队工作项的显选检查范围异常");
        Set<String> selectedCommands=new java.util.HashSet<>();
        for(int i=0;i<templates.length();i++){
            JSONObject template=templates.getJSONObject(i);String id=template.getString("id");
            JSONObject row=new JSONObject().put("id",id).put("requiredForFreeze",template.getBoolean("requiredForFreeze"));
            // Native receipts name the FIRST matching literal. Never invite a second run as proof for an alias.
            if(selectedCommands.add(ProjectSources.canonical(template.getJSONObject("command"))))row.put(FIELD,ref(binding,context,id));
            else row.put("cannotAttributeSeparateReceipt",true);
            rows.put(row);
        }
        return result.put("role",selected.getString("role")).put("templates",rows);
    }
    static JSONObject request(JSONObject binding,JSONObject context,JSONObject args)throws Exception {
        // No executable, argv, env, directory, PTY, timeout can be overridden; the original builder separately verifies the supplied live source digest.
        java.util.Set<String> keys=new java.util.HashSet<>();args.keys().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("intent","expectedSourceDigest",FIELD))||!"command".equals(args.opt("intent"))
                ||!(args.opt(FIELD) instanceof String wanted)||!wanted.matches("[a-f0-9]{64}")
                ||!(args.opt("expectedSourceDigest") instanceof String source)||!source.matches("[a-f0-9]{64}"))
            throw new SecurityException("显选检查只接受原检查引用和本次实际读取的准确sourceDigest，不混用自填命令参数");
        JSONArray templates=context.getJSONObject("selection").getJSONArray("templates");
        Set<String> selectedCommands=new java.util.HashSet<>();
        for(int i=0;i<templates.length();i++){
            JSONObject template=templates.getJSONObject(i);String command=ProjectSources.canonical(template.getJSONObject("command"));
            if(!selectedCommands.add(command)||!wanted.equals(ref(binding,context,template.getString("id"))))continue;
            JSONObject literal=new JSONObject(template.getJSONObject("request").toString());
            if(!ProjectSources.canonical(ProjectCommandSpec.normalize(literal)).equals(command))
                throw new SecurityException("原显选命令与本机固定模板不一致，不生成替代方案");
            return literal.put("intent","command").put("expectedSourceDigest",source);
        }
        throw new SecurityException("该检查引用不属于此原成员、工作副本或本轮；请重新读取本项project_inspect");
    }
    /** The ordinary plan reference includes per-step argv. They were not in the model's handle
     * request. Keep the full literal in original SQL / owner approval, not in the model tool return. */
    static JSONObject proposal(JSONObject original,String ref)throws Exception {
        if(!original.optBoolean("ok")||!"project_plan_ready".equals(original.optString("code"))){
            boolean stale="项目源码已变化，未生成旧版本命令方案".equals(original.optString("message"));
            return new JSONObject().put("ok",false).put("accepted",false).put("status","not_dispatched").put("code",stale?"team_check_source_changed":"team_check_plan_not_ready")
                    .put("message",stale?"原副本源码已变化。先用project_inspect重新读取当前源码，不重放旧引用调用。":"原检查未产生可用方案；核对此分工的当前源码、环境及范围，不重放旧调用。").put("projectEffectDispatched",false);
        }
        JSONObject out=new JSONObject();
        for(String key:new String[]{"ok","accepted","status","code","format","planRef","planDigest","checkoutId","sourceDigest","intent","available"})
            out.put(key,original.get(key));
        JSONArray steps=original.getJSONArray("steps"),ids=new JSONArray();
        for(int i=0;i<steps.length();i++)ids.put(new JSONObject().put("id",steps.getJSONObject(i).getString("id")));
        return out.put(FIELD,ref).put("steps",ids).put("executionAuthorized",false).put("businessVerified",false).put("fullPlanStoredInOriginalTaskStore",true)
                .put("message",out.getBoolean("available")?"这是当前源码的待审方案，不是检查结果。沿原project_execute提交planRef/planDigest/stepId，仍须主人准确批准。":"方案已记录，但原环境当前不能执行此检查。未运行，不改用别的命令或环境。");
    }
}
