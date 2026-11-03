package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable native run snapshot. Credentials remain in transient original configs, never its JSON. */
public final class ModelRoutePlan {
    public record Candidate(String candidateId,ModelConfig config,String exclusion) { }
    public final String snapshotId,requestedAlias,purpose,audience;
    public final long groupRevision;
    public final int maxAttempts,maxRouteSwitches;
    public final List<Candidate> candidates;
    private record NativeStepDispatch(Candidate candidate,int maxAttempts) { }
    private final NativeStepDispatch nativeStep;

    public ModelRoutePlan(String alias,long revision,String purpose,String audience,List<Candidate> candidates,int attempts,int switches){
        if(candidates.isEmpty()||candidates.size()>8||attempts<1||attempts>3||switches<0||switches>1||!ModelGroup.AUDIENCES.contains(audience))throw new IllegalArgumentException("invalid_native_route_plan");
        nativeStep=null;snapshotId=java.util.UUID.randomUUID().toString();requestedAlias=alias;groupRevision=revision;this.purpose=purpose;this.audience=audience;this.candidates=List.copyOf(candidates);maxAttempts=attempts;maxRouteSwitches=switches;
    }
    private ModelRoutePlan(ModelRoutePlan original,Candidate candidate,int attempts){
        snapshotId=original.snapshotId;requestedAlias=original.requestedAlias;purpose=original.purpose;audience=original.audience;
        groupRevision=original.groupRevision;maxAttempts=original.maxAttempts;maxRouteSwitches=original.maxRouteSwitches;candidates=original.candidates;
        nativeStep=new NativeStepDispatch(candidate,attempts);
    }
    /** Package-native transient preference only. The original complete owner snapshot is unchanged. */
    ModelRoutePlan withNativeStepDispatch(Candidate candidate,int attempts){
        if(attempts<1||attempts>maxAttempts||candidate==null||candidate.config()==null||!candidate.exclusion().isEmpty()
                ||candidates.stream().noneMatch(original->original==candidate))throw new SecurityException("native_step_not_in_original_plan");
        return new ModelRoutePlan(this,candidate,attempts);
    }
    public boolean dispatches(Candidate candidate){return nativeStep==null||nativeStep.candidate==candidate;}
    public int dispatchMaxAttempts(){return nativeStep==null?maxAttempts:nativeStep.maxAttempts;}
    public static ModelRoutePlan exact(ModelConfig c,String purpose,String audience){return new ModelRoutePlan(c.model,0,purpose,audience,List.of(new Candidate("exact",c,"")),1,0);}
    public ModelConfig primary(){if(nativeStep!=null)return nativeStep.candidate.config();for(Candidate c:candidates)if(c.config!=null&&c.exclusion.isEmpty())return c.config;throw new IllegalStateException("模型组没有获准候选，请检查原连接、用途与账号范围；没有回退到全局默认。");}
    /** Stable request options only. Never hashes, serializes, or copies API key bytes. */
    public static String configurationDigest(ModelConfig m)throws Exception {
        JSONArray options=new JSONArray().put(m.provider).put(m.baseUrl).put(m.model).put(m.maxOutputTokens)
                .put(m.requestTimeoutSeconds).put(m.reasoning.mode().name()).put(m.reasoning.level()).put(m.reasoning.budgetTokens())
                .put(m.imageTokenReserve).put(m.catalogEnforced).put(m.supportsImageInput).put(m.sendScreenshots)
                .put(m.localCaptureAllowed).put(m.localOcrAllowed);
        if(m.compatibility!=null)options.put(m.compatibility.toJson());
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(options.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
    public JSONObject toJson()throws Exception{JSONArray rows=new JSONArray();for(Candidate c:candidates){JSONObject r=new JSONObject().put("candidateId",c.candidateId).put("exclusion",c.exclusion);if(c.config!=null){ModelConfig m=c.config;r.put("configurationDigest",configurationDigest(m)).put("model",m.model).put("provider",m.provider).put("connectionId",m.capabilityBinding==null?m.usesAccount()?"account:"+m.accountModel.accountId:"legacy":m.capabilityBinding.connectionId());if(m.capabilityBinding!=null)r.put("binding",m.capabilityBinding.toJson());if(m.usesAccount())r.put("accountRef",m.accountModel.toJson());}rows.put(r);}return new JSONObject().put("format","pocketworld.model-route-plan.v1").put("snapshotId",snapshotId).put("requestedAlias",requestedAlias).put("groupRevision",groupRevision).put("purpose",purpose).put("audience",audience).put("maxAttempts",maxAttempts).put("maxRouteSwitches",maxRouteSwitches).put("orderedCandidates",rows).put("quota",new JSONObject().put("state","unknown").put("value",JSONObject.NULL).put("source","not_observed")).put("authorizationFrozen",false);}
}
