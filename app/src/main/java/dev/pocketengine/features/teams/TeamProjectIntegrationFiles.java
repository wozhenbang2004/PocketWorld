package dev.pocketengine.features.teams;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;

/** The existing unique integrator first performs original CAS, then prepares one fixed complete result copy. */
final class TeamProjectIntegrationFiles implements CapabilityPort {
    static final String PREPARE="team_prepare_integrated_project";
    private final CapabilityPort original;private final TeamProjectWorkspaces projects;private final JSONObject binding;private final RunControl control;
    TeamProjectIntegrationFiles(CapabilityPort original,TeamProjectWorkspaces projects,JSONObject binding,RunControl control)throws Exception{this.original=original;this.projects=projects;this.binding=new JSONObject(binding.toString());this.control=control;}
    @Override public JSONArray tools()throws Exception {JSONArray tools=original.tools();tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",PREPARE).put("description","仅原唯一整合者在准确CAS全部完成后，以主人完整基线和每份已审原件的真实应用版本准备固定完整结果副本。不会复制成员额外文件、覆盖父活跃checkout或运行代码。").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));return tools;}
    @Override public boolean supports(String name){return PREPARE.equals(name)||original.supports(name);}
    @Override public boolean sideEffecting(String name){return PREPARE.equals(name)||original.sideEffecting(name);}
    @Override public void validate(String name,JSONObject args)throws Exception{if(PREPARE.equals(name)){if(args.length()!=0)throw new SecurityException("组合副本只能使用原审查ID，不能提供文件或scope");}else original.validate(name,args);}
    @Override public JSONObject execute(String name,JSONObject input,RunControl live)throws Exception {
        if(!PREPARE.equals(name))return original.execute(name,input,live);if(live!=control)throw new SecurityException("不是原唯一整合者控制");JSONObject args=new JSONObject(input.toString());String operation=args.optString("_hostOperationId");args.remove("_hostOperationId");validate(name,args);if(!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("组合副本必须由原Runner登记prepared动作");return projects.prepareIntegrated(binding,operation,control);
    }
    @Override public JSONObject contextSnapshot(String query)throws Exception{return original.contextSnapshot(query).put("fullProjectResult",new JSONObject().put("fixedBaselineOnly",true).put("requiresExactAppliedHeads",true).put("parentActiveCheckoutOverwritten",false).put("currentMainProjectVerified",false));}
    @Override public void close(){original.close();}
}
