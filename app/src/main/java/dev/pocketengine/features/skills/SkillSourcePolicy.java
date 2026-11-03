package dev.pocketengine.features.skills;

import org.json.JSONObject;

/** Native owner policy over an already registered source. Project eligibility uses the
 * original project namespace; it never changes provenance or execution authority. */
public final class SkillSourcePolicy {
    public enum Layer {
        PROJECT(300), USER(200), BUNDLED(100);
        final int rank;
        Layer(int rank){this.rank=rank;}
    }
    public enum Invocation { MODEL_AND_OWNER, OWNER_ONLY }
    private SkillSourcePolicy(){}

    static JSONObject of(Layer layer,Invocation invocation)throws Exception {
        return of(layer,invocation,null);
    }
    static JSONObject of(Layer layer,Invocation invocation,String projectId)throws Exception {
        if(layer==null||invocation==null)throw new IllegalArgumentException("请选择明确的来源层级和调用方式");
        JSONObject value=new JSONObject().put("layer",layer.name()).put("invocation",invocation.name())
                .put("classificationAuthority","native_owner").put("trustedCode",false);
        if(layer==Layer.PROJECT)value.put("projectId",project(projectId));
        else if(projectId!=null&&!projectId.isEmpty())throw new SecurityException("全局技能不能保留另一个项目范围");
        return value;
    }
    static JSONObject current(JSONObject record)throws Exception {
        JSONObject stored=record.optJSONObject("sourcePolicy");
        if(stored==null){if(record.has("sourcePolicy"))throw new SecurityException("技能来源策略损坏");return of(Layer.USER,Invocation.MODEL_AND_OWNER);}
        if(!"native_owner".equals(stored.optString("classificationAuthority"))||!Boolean.FALSE.equals(stored.opt("trustedCode")))throw new SecurityException("技能来源策略损坏");
        try{
            Layer layer=Layer.valueOf(stored.getString("layer"));Invocation invocation=Invocation.valueOf(stored.getString("invocation"));
            // A previously saved PROJECT label is retained for owner repair, never promoted
            // to a global source or silently bound to whichever project is open now.
            if(layer==Layer.PROJECT&&!stored.has("projectId")&&stored.length()==4)return new JSONObject(stored.toString());
            if(stored.length()!=(layer==Layer.PROJECT?5:4))throw new SecurityException("技能来源策略损坏");
            return of(layer,invocation,layer==Layer.PROJECT?stored.getString("projectId"):null);
        }
        catch(IllegalArgumentException invalid){throw new SecurityException("技能来源策略损坏",invalid);}
    }
    static boolean bindingRequired(JSONObject record)throws Exception {JSONObject policy=current(record);return Layer.PROJECT.name().equals(policy.getString("layer"))&&!policy.has("projectId");}
    static boolean applicable(JSONObject record,JSONObject authenticatedScope)throws Exception {
        JSONObject policy=current(record),scope=checkedScope(authenticatedScope);
        return !Layer.PROJECT.name().equals(policy.getString("layer"))||policy.has("projectId")&&policy.getString("projectId").equals(scope.optString("projectId"));
    }
    static void requireApplicable(JSONObject record,JSONObject authenticatedScope)throws Exception {
        if(!applicable(record,authenticatedScope))throw new SecurityException(bindingRequired(record)?"原项目技能尚未绑定真实项目，请由主人重新审阅来源":"技能不属于原任务当前选择的项目");
    }
    /** Native scope metadata only. Callers must derive it from the original task and project
     * catalogue; no model/feature command accepts these fields as a grant. */
    static JSONObject checkedScope(JSONObject input)throws Exception {
        if(input==null)throw new SecurityException("缺少原技能范围");String owner=input.optString("ownerId"),space=input.optString("spaceId");
        if(!owner.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||!space.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))throw new SecurityException("原技能范围无效");
        JSONObject value=new JSONObject().put("ownerId",owner).put("spaceId",space);
        if(input.has("projectId")||input.has("checkoutId")||input.has("projectRevision")){
            String id=project(input.getString("projectId")),checkout=project(input.getString("checkoutId")),revision=input.getString("projectRevision");
            if(!revision.matches("[a-f0-9]{64}"))throw new SecurityException("原项目来源身份无效");
            value.put("projectId",id).put("checkoutId",checkout).put("projectRevision",revision);
        }
        return value;
    }
    public static String scopeFingerprint(JSONObject authenticatedScope)throws Exception {return SkillExecution.digestJson(checkedScope(authenticatedScope));}
    static String project(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("请选择原生登记的真实项目");return value;}
    static int rank(JSONObject record)throws Exception{return Layer.valueOf(current(record).getString("layer")).rank;}
    static boolean modelAllowed(JSONObject record)throws Exception {
        return Invocation.MODEL_AND_OWNER.name().equals(current(record).getString("invocation"))&&!record.optBoolean("disable-model-invocation",false);
    }
    static boolean ownerInvocable(JSONObject record){return record.optBoolean("user-invocable",true);}
    static JSONObject metadata(JSONObject record)throws Exception {
        JSONObject policy=current(record);
        return policy.put("priority",Layer.valueOf(policy.getString("layer")).rank)
                .put("modelInvocable",modelAllowed(record)).put("userInvocable",ownerInvocable(record))
                .put("manifestRestrictsModel",record.optBoolean("disable-model-invocation",false))
                .put("projectBindingRequired",bindingRequired(record)).put("namespace",policy.has("projectId")?"project:"+policy.getString("projectId"):bindingRequired(record)?"unbound_project":record.optString("spaceId","private"));
    }
    static String kind(JSONObject record){
        if(record.has("gitSource"))return "git";
        if(record.has("mcpSource"))return "mcp_resource";
        if(record.has("origin"))return "accepted_procedure";
        return "owner_import";
    }
}
