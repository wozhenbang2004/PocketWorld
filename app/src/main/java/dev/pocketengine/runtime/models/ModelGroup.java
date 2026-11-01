package dev.pocketengine.runtime.models;

import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Flat ordered, versioned references into the existing connection/account owners. */
public final class ModelGroup {
    public static final int MAX_CANDIDATES=8,MAX_GROUPS=32;
    public static final Set<String> AUDIENCES=Set.of("APP_OWNER","EXTERNAL_RELAY","EXTERNAL_GROUP");
    public record Candidate(String candidateId,ModelTargetRef target,boolean enabled,Set<String> allowedPurposes,Set<String> allowedAudiences) {
        public Candidate {ModelTargetRef.checked(candidateId,100);if(target.kind()!=ModelTargetRef.Kind.EXACT)throw new IllegalArgumentException("nested_groups_not_supported");allowedPurposes=Set.copyOf(allowedPurposes);allowedAudiences=Set.copyOf(allowedAudiences);for(String purpose:allowedPurposes)if(!purpose.matches("MAIN|SUMMARY|MEMBER(?:\\.[A-Za-z0-9_-]{1,60})?|CONNECTION_TEST|RELAY|VOICE_TURN|SKILL_DRAFT"))throw new IllegalArgumentException("invalid_model_purpose");if(!AUDIENCES.containsAll(allowedAudiences))throw new IllegalArgumentException("invalid_model_audience");}
        JSONObject json()throws Exception{return new JSONObject().put("candidateId",candidateId).put("target",target.toJson()).put("enabled",enabled).put("allowedPurposes",new JSONArray(new java.util.TreeSet<>(allowedPurposes))).put("allowedAudiences",new JSONArray(new java.util.TreeSet<>(allowedAudiences)));}
    }
    public final String groupId,name;
    public final long revision;
    public final Set<String> allowedAccounts;
    public final List<Candidate> candidates;
    public final int maxAttempts,maxRouteSwitches;
    public ModelGroup(String id,long revision,String name,Set<String> allowed,List<Candidate> candidates,int attempts,int switches){
        groupId=ModelTargetRef.checked(id,100);if(revision<1||name==null||name.isBlank()||name.length()>100||name.chars().anyMatch(c->c<32))throw new IllegalArgumentException("invalid_model_group");this.revision=revision;this.name=name.trim();
        allowedAccounts=Set.copyOf(allowed);for(String ref:allowedAccounts)ModelTargetRef.checked(ref,200);
        this.candidates=List.copyOf(candidates);if(candidates.isEmpty()||candidates.size()>MAX_CANDIDATES||attempts<1||attempts>3||switches<0||switches>1)throw new IllegalArgumentException("model_group_limits");
        Set<String> ids=new java.util.HashSet<>();for(Candidate c:candidates)if(!ids.add(c.candidateId))throw new IllegalArgumentException("duplicate_candidate_id");maxAttempts=attempts;maxRouteSwitches=switches;
    }
    public JSONObject toJson()throws Exception{JSONArray rows=new JSONArray();for(Candidate c:candidates)rows.put(c.json());return new JSONObject().put("groupId",groupId).put("revision",revision).put("name",name).put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",new JSONArray(new java.util.TreeSet<>(allowedAccounts)))).put("candidates",rows).put("attemptPolicy",new JSONObject().put("maxAttempts",maxAttempts).put("maxRouteSwitches",maxRouteSwitches));}
    public static ModelGroup fromJson(JSONObject j)throws Exception{
        JSONObject accounts=j.getJSONObject("allowedAccounts"),policy=j.getJSONObject("attemptPolicy");if(!"EXPLICIT".equals(accounts.getString("mode")))throw new IllegalArgumentException("explicit_account_allowlist_required");
        JSONArray rows=j.getJSONArray("candidates");List<Candidate> out=new java.util.ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject c=rows.getJSONObject(i);out.add(new Candidate(c.getString("candidateId"),ModelTargetRef.fromJson(c.getJSONObject("target")),bool(c.get("enabled")),strings(c.getJSONArray("allowedPurposes")),strings(c.getJSONArray("allowedAudiences"))));}
        return new ModelGroup(j.getString("groupId"),integer(j.get("revision"),1,Long.MAX_VALUE),j.getString("name"),strings(accounts.getJSONArray("refs")),out,(int)integer(policy.get("maxAttempts"),1,3),(int)integer(policy.get("maxRouteSwitches"),0,1));
    }
    private static boolean bool(Object value){if(!(value instanceof Boolean))throw new IllegalArgumentException("boolean_required");return (Boolean)value;}
    private static long integer(Object value,long low,long high){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<low||((Number)value).longValue()>high)throw new IllegalArgumentException("bounded_integer_required");return ((Number)value).longValue();}
    private static Set<String> strings(JSONArray a)throws Exception{Set<String> out=new java.util.LinkedHashSet<>();if(a.length()>32)throw new IllegalArgumentException("model_scope_limit");for(int i=0;i<a.length();i++)if(!out.add(a.getString(i)))throw new IllegalArgumentException("duplicate_scope_reference");return out;}
}
