package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.models.ModelTargetRef;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONObject;

/** Native member route snapshots. Public refs/pins contain no key and are not execution grants. */
public final class TeamMemberModels {
    private TeamMemberModels(){}
    @FunctionalInterface public interface Capture {
        ModelConfig capture(ModelTargetRef reference,String purpose,ModelConfig capturedGlobal)throws Exception;
    }
    public static final class Selection {
        public final ModelConfig config;
        private final String identity;
        private Selection(ModelConfig config,JSONObject identity){this.config=config;this.identity=identity.toString();}
        public JSONObject publicIdentity()throws Exception{return new JSONObject(identity);}
    }
    /** No later default is read. Cold reopen recaptures the original public target, then compares every pin. */
    public static Selection capture(JSONObject member,ModelConfig global,Capture nativeCapture)throws Exception {
        return captureWithDefault(member,global,nativeCapture,null);
    }
    /** Only the opaque native planning source may supply a lower-priority Profile default. */
    public static Selection capture(JSONObject member,ModelConfig global,Capture nativeCapture,
            dev.pocketengine.features.controlplane.NativeWorkProfileHost.MemberPlanModels defaults)throws Exception {
        return captureWithDefault(member,global,nativeCapture,defaults==null?null:defaults.defaultForMember(member));
    }
    public static Selection capture(JSONObject member,ModelConfig global,Capture nativeCapture,
            dev.pocketengine.features.controlplane.NativeWorkProfileHost.MemberStartModels defaults)throws Exception {
        return captureWithDefault(member,global,nativeCapture,defaults.defaultForMember(member));
    }
    private static Selection captureWithDefault(JSONObject member,ModelConfig global,Capture nativeCapture,ModelConfig profileDefault)throws Exception {
        JSONObject previous=member.optJSONObject("memberModelSelection");
        ModelTargetRef explicit=member.optJSONObject("modelTarget")==null?null:ModelTargetRef.fromJson(member.getJSONObject("modelTarget"));
        ModelTargetRef chosen=explicit!=null?explicit:target(previous,"memberTarget");
        String purpose="MEMBER";
        if(previous!=null&&previous.optJSONObject("memberRoute")!=null)purpose=previous.getJSONObject("memberRoute").getString("purpose");
        if(!purpose.equals("MEMBER")&&!purpose.equals("MEMBER."+member.getString("id")))throw new SecurityException("成员模型用途不是实际原slot身份");
        if(profileDefault!=null){if(previous!=null)throw new SecurityException("原planning成员不能夹带已有模型pin");chosen=reference(profileDefault);purpose=profileDefault.routePlan==null?"MEMBER":profileDefault.routePlan.purpose;}
        if(!purpose.equals("MEMBER")&&!purpose.equals("MEMBER."+member.getString("id")))throw new SecurityException("工作配置成员用途不是实际原slot身份");
        ModelConfig selected;
        if(profileDefault!=null)selected=profileDefault;
        else if(chosen!=null){if(nativeCapture==null)throw new SecurityException("原成员精确模型连接尚未接入，未使用默认连接替代");selected=nativeCapture.capture(chosen,purpose,global);}
        else if(member.optBoolean("modelInherited"))selected=global.forPurpose("MEMBER");
        else selected=global.withModel(member.optString("model",global.model)).withRoutePlan(null).withPurposePlans(Map.of());
        ModelTargetRef summaryTarget=target(previous,"summaryTarget");
        ModelConfig summary=summaryTarget==null?global.forPurpose("SUMMARY"):requireCapture(nativeCapture).capture(summaryTarget,"SUMMARY",global);
        boolean nativeRoutes=nativeCapture!=null||global.routePlan!=null||!global.purposePlans.isEmpty()||chosen!=null;
        ModelConfig memberConfig=selected.withRoutePlan(null).withPurposePlans(Map.of());
        JSONObject pin=new JSONObject().put("format","pocketworld.team-member-model.v1").put("identityRevision",member.optInt("identityRevision",1));
        if(nativeRoutes){
            ModelRoutePlan memberPlan=singleAttempt(selected,purpose),summaryPlan=singleAttempt(summary,"SUMMARY");
            memberConfig=memberPlan.primary().withRoutePlan(memberPlan).withPurposePlans(Map.of("MEMBER",memberPlan,"MAIN",memberPlan,"SUMMARY",summaryPlan));
            pin.put("memberRoute",stable(memberPlan)).put("summaryRoute",stable(summaryPlan));
            ModelTargetRef memberTarget=chosen==null?reference(selected):chosen,actualSummary=summaryTarget==null?reference(summary):summaryTarget;
            if(memberTarget!=null)pin.put("memberTarget",memberTarget.toJson());if(actualSummary!=null)pin.put("summaryTarget",actualSummary.toJson());
        }else pin.put("legacyMemberDigest",ModelRoutePlan.configurationDigest(selected)).put("legacySummaryDigest",ModelRoutePlan.configurationDigest(summary));
        if(previous!=null&&!dev.pocketengine.runtime.context.CompactionPlan.fingerprint(previous).equals(dev.pocketengine.runtime.context.CompactionPlan.fingerprint(pin)))throw new SecurityException("成员模型原连接、版本或摘要配置已变化；请在本轮成员设置明确重新选择，原预算未追加");
        return new Selection(memberConfig,pin);
    }
    public static void requireSame(Selection selection,JSONObject stored)throws Exception {
        if(stored==null||!dev.pocketengine.runtime.context.CompactionPlan.fingerprint(selection.publicIdentity()).equals(dev.pocketengine.runtime.context.CompactionPlan.fingerprint(stored)))throw new SecurityException("原成员模型快照已换版");
    }
    private static Capture requireCapture(Capture capture){if(capture==null)throw new SecurityException("原模型连接捕获口不可用");return capture;}
    private static ModelTargetRef target(JSONObject pin,String key)throws Exception{return pin==null||pin.optJSONObject(key)==null?null:ModelTargetRef.fromJson(pin.getJSONObject(key));}
    private static ModelTargetRef reference(ModelConfig source){
        if(source.routePlan!=null&&source.routePlan.groupRevision>0)return ModelTargetRef.group(source.routePlan.requestedAlias,source.routePlan.groupRevision);
        if(source.capabilityBinding!=null)return ModelTargetRef.exact(source.capabilityBinding.connectionId(),source.model);
        if(source.usesAccount())return ModelTargetRef.exact("account:"+source.accountModel.accountId,source.model);return null;
    }
    private static ModelRoutePlan singleAttempt(ModelConfig source,String purpose){
        ModelRoutePlan plan=source.routePlan;var rows=new ArrayList<ModelRoutePlan.Candidate>();
        if(plan==null)rows.add(new ModelRoutePlan.Candidate("exact",source.withRoutePlan(null).withPurposePlans(Map.of()),""));
        else for(ModelRoutePlan.Candidate row:plan.candidates)rows.add(new ModelRoutePlan.Candidate(row.candidateId(),row.config()==null?null:row.config().withRoutePlan(null).withPurposePlans(Map.of()),row.exclusion()));
        // The original team site reserves one physical call. A group never adds a hidden retry.
        return new ModelRoutePlan(plan==null?source.model:plan.requestedAlias,plan==null?0:plan.groupRevision,purpose,"APP_OWNER",rows,1,0);
    }
    private static JSONObject stable(ModelRoutePlan plan)throws Exception{JSONObject publicPlan=plan.toJson();publicPlan.remove("snapshotId");return publicPlan;}
}
