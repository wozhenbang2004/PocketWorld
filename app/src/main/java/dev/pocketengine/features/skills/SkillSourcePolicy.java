package dev.pocketengine.features.skills;

import org.json.JSONObject;

/** Native owner classification of an already registered source. A layer orders equal names;
 * it never changes content provenance, file access or execution authority. */
public final class SkillSourcePolicy {
    public enum Layer {
        PROJECT(300), USER(200), BUNDLED(100);
        final int rank;
        Layer(int rank){this.rank=rank;}
    }
    public enum Invocation { MODEL_AND_OWNER, OWNER_ONLY }
    private SkillSourcePolicy(){}

    static JSONObject of(Layer layer,Invocation invocation)throws Exception {
        if(layer==null||invocation==null)throw new IllegalArgumentException("请选择明确的来源层级和调用方式");
        return new JSONObject().put("layer",layer.name()).put("invocation",invocation.name())
                .put("classificationAuthority","native_owner").put("trustedCode",false);
    }
    static JSONObject current(JSONObject record)throws Exception {
        JSONObject stored=record.optJSONObject("sourcePolicy");
        if(stored==null){if(record.has("sourcePolicy"))throw new SecurityException("技能来源策略损坏");return of(Layer.USER,Invocation.MODEL_AND_OWNER);}
        if(stored.length()!=4||!"native_owner".equals(stored.optString("classificationAuthority"))||!Boolean.FALSE.equals(stored.opt("trustedCode")))throw new SecurityException("技能来源策略损坏");
        try{return of(Layer.valueOf(stored.getString("layer")),Invocation.valueOf(stored.getString("invocation")));}
        catch(IllegalArgumentException invalid){throw new SecurityException("技能来源策略损坏",invalid);}
    }
    static int rank(JSONObject record)throws Exception{return Layer.valueOf(current(record).getString("layer")).rank;}
    static boolean modelAllowed(JSONObject record)throws Exception {
        return Invocation.MODEL_AND_OWNER.name().equals(current(record).getString("invocation"))&&!record.optBoolean("disable-model-invocation",false);
    }
    static boolean ownerInvocable(JSONObject record){return record.optBoolean("user-invocable",true);}
    static JSONObject metadata(JSONObject record)throws Exception {
        JSONObject policy=current(record);
        return policy.put("priority",Layer.valueOf(policy.getString("layer")).rank)
                .put("modelInvocable",modelAllowed(record)).put("userInvocable",ownerInvocable(record))
                .put("manifestRestrictsModel",record.optBoolean("disable-model-invocation",false));
    }
    static String kind(JSONObject record){
        if(record.has("gitSource"))return "git";
        if(record.has("mcpSource"))return "mcp_resource";
        if(record.has("origin"))return "accepted_procedure";
        return "owner_import";
    }
}
