package dev.pocketengine.runtime.models;

import java.util.Set;
import org.json.JSONObject;

/** Public native selection only. It contains neither a credential nor an execution grant. */
public record ModelTargetRef(Kind kind,String connectionId,String modelId,String groupId,long groupRevision) {
    public enum Kind { EXACT, GROUP }
    public ModelTargetRef {
        java.util.Objects.requireNonNull(kind);
        if(kind==Kind.EXACT){checked(connectionId,200);checked(modelId,200);if(!groupId.isEmpty()||groupRevision!=0)throw new IllegalArgumentException("exact_model_reference_required");}
        else {checked(groupId,100);if(groupRevision<1||!connectionId.isEmpty()||!modelId.isEmpty())throw new IllegalArgumentException("versioned_model_group_required");}
    }
    public static ModelTargetRef exact(String connection,String model){return new ModelTargetRef(Kind.EXACT,connection,model,"",0);}
    public static ModelTargetRef group(String group,long revision){return new ModelTargetRef(Kind.GROUP,"","",group,revision);}
    public JSONObject toJson()throws Exception{return kind==Kind.EXACT?new JSONObject().put("kind","exact").put("connectionId",connectionId).put("modelId",modelId):new JSONObject().put("kind","group").put("groupId",groupId).put("groupRevision",groupRevision);}
    public static ModelTargetRef fromJson(JSONObject j)throws Exception {
        Set<String> keys=new java.util.HashSet<>();j.keys().forEachRemaining(keys::add);
        if("exact".equals(j.optString("kind"))&&keys.equals(Set.of("kind","connectionId","modelId")))return exact(j.getString("connectionId"),j.getString("modelId"));
        if("group".equals(j.optString("kind"))&&keys.equals(Set.of("kind","groupId","groupRevision"))){Object v=j.get("groupRevision");if(!(v instanceof Integer||v instanceof Long))throw new IllegalArgumentException("integer_revision_required");return group(j.getString("groupId"),((Number)v).longValue());}
        throw new IllegalArgumentException("invalid_model_target_reference");
    }
    static String checked(String s,int length){if(s==null||s.isEmpty()||s.length()>length||!s.matches("[A-Za-z0-9_.:/@+-]+"))throw new IllegalArgumentException("invalid_model_reference");return s;}
}
