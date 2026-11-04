package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TeamMemberModelsTest {
    private static ModelConfig config(String name){return new ModelConfig("http://127.0.0.1/v1","never-persist-this-key",name,false,24,60,"openai_chat",512);}
    private static ModelConfig captured(){ModelConfig main=config("global-main"),summary=config("independent-summary");return main.withRoutePlan(ModelRoutePlan.exact(main,"MAIN","APP_OWNER")).withPurposePlans(Map.of("DEFAULT",ModelRoutePlan.exact(main,"DEFAULT","APP_OWNER"),"SUMMARY",ModelRoutePlan.exact(summary,"SUMMARY","APP_OWNER")));}
    @Test public void genericMemberJsonCannotRetainACopiedNativeModelPin() throws Exception {
        JSONObject member=new JSONObject().put("id","a").put("name","A").put("role","Native slot").put("runner","model").put("device","phone")
            .put("memberModelSelection",new JSONObject().put("format","pocketworld.team-member-model.v1"));
        assertFalse(AgentSlot.normalize(member,0).has("memberModelSelection"));
    }
    @Test public void legacyMemberNameCannotBeOverwrittenByMainDefaultAndSummaryIsIndependent()throws Exception {
        var selected=TeamMemberModels.capture(new JSONObject().put("id","a").put("model","member-A"),captured(),null);
        assertEquals("member-A",selected.config.forPurpose("MEMBER").model);assertEquals("member-A",selected.config.forPurpose("MAIN").model);assertEquals("independent-summary",selected.config.forPurpose("SUMMARY").model);
        assertEquals("MEMBER",selected.config.forPurpose("MEMBER").routePlan.purpose);assertEquals("SUMMARY",selected.config.forPurpose("SUMMARY").routePlan.purpose);assertFalse(selected.publicIdentity().toString().contains("never-persist"));
    }
    @Test public void exactMemberReferenceUsesOnlyNativeCaptureAndHasNoHiddenGroupRetry()throws Exception {
        AtomicInteger calls=new AtomicInteger();var ref=ModelTargetRef.group("reviewed-group",7);
        TeamMemberModels.Capture nativeCapture=(target,purpose,global)->{calls.incrementAndGet();assertEquals(ref,target);assertEquals("MEMBER",purpose);return config("native-A").withRoutePlan(new ModelRoutePlan("reviewed-group",7,purpose,"APP_OWNER",List.of(new ModelRoutePlan.Candidate("A",config("native-A"),""),new ModelRoutePlan.Candidate("B",config("native-B"),"")),3,1));};
        var member=new JSONObject().put("id","a").put("model","misleading-legacy-name").put("modelTarget",ref.toJson());var selected=TeamMemberModels.capture(member,captured(),nativeCapture);
        assertEquals(1,calls.get());assertEquals("native-A",selected.config.forPurpose("MEMBER").model);assertEquals(1,selected.config.forPurpose("MEMBER").routePlan.maxAttempts);assertEquals(0,selected.config.forPurpose("MEMBER").routePlan.maxRouteSwitches);assertEquals("independent-summary",selected.config.forPurpose("SUMMARY").model);
    }
    private static ModelConfig group(int attempts,int switches,String exclusion){
        return api("native-A").withRoutePlan(new ModelRoutePlan("reviewed-group",7,"MEMBER","APP_OWNER",List.of(new ModelRoutePlan.Candidate("A",api("native-A"),""),new ModelRoutePlan.Candidate("B",api("native-B"),exclusion)),attempts,switches));
    }
    private static ModelConfig api(String name){return config(name).withApiConnection(new ModelBinding("connection-"+name,"openai",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,java.net.URI.create("http://127.0.0.1/v1/models"),"credential-"+name,1,"installation",1,false));}
    private static JSONObject groupMember()throws Exception{return new JSONObject().put("id","a").put("identityRevision",1).put("modelTarget",ModelTargetRef.group("reviewed-group",7).toJson());}
    @Test public void newNativeGroupPermitsOnlyOneSwitchAndKeepsSummarySingle()throws Exception {
        var member=groupMember();var selected=TeamMemberModels.capture(member,captured(),(target,purpose,global)->group(3,1,""));
        assertEquals(2,selected.config.routePlan.maxAttempts);assertEquals(1,selected.config.routePlan.maxRouteSwitches);assertEquals(2,selected.config.routePlan.candidates.size());
        assertEquals(1,selected.config.forPurpose("SUMMARY").routePlan.maxAttempts);assertEquals(0,selected.config.forPurpose("SUMMARY").routePlan.maxRouteSwitches);
        member.put("memberModelSelection",selected.publicIdentity());TeamMemberModels.requireSame(TeamMemberModels.capture(new JSONObject(member.toString()),captured(),(target,purpose,global)->group(3,1,"")),selected.publicIdentity());
        assertFalse(selected.publicIdentity().toString().contains("never-persist"));
    }
    @Test public void lowerOriginalPolicyOrExcludedSecondCandidateCannotGainFallback()throws Exception {
        for(ModelConfig source:List.of(group(1,1,""),group(3,0,""),group(3,1,"purpose_not_allowed"))){
            var selected=TeamMemberModels.capture(groupMember(),captured(),(target,purpose,global)->source);
            assertEquals(1,selected.config.routePlan.maxAttempts);assertEquals(0,selected.config.routePlan.maxRouteSwitches);
        }
        var unbound=config("unbound").withRoutePlan(new ModelRoutePlan("unbound-group",1,"MEMBER","APP_OWNER",List.of(new ModelRoutePlan.Candidate("A",api("native-A"),""),new ModelRoutePlan.Candidate("B",config("no-native-binding"),"")),3,1));
        assertEquals(1,TeamMemberModels.capture(groupMember(),captured(),(target,purpose,global)->unbound).config.routePlan.maxAttempts);
    }
    @Test public void oldSingleAttemptPinIsNotUpgradedEvenWhenItsOriginalGroupAllowedThree()throws Exception {
        var member=groupMember();var current=TeamMemberModels.capture(member,captured(),(target,purpose,global)->group(3,1,""));
        // This is the persisted v1 pin produced before research fallback existed:
        // identical native candidate/options identity, but the former cap1 policy.
        JSONObject old=current.publicIdentity();old.getJSONObject("memberRoute").put("maxAttempts",1).put("maxRouteSwitches",0);member.put("memberModelSelection",old);
        var cold=TeamMemberModels.capture(new JSONObject(member.toString()),captured(),(target,purpose,global)->group(3,1,""));
        assertEquals(1,cold.config.routePlan.maxAttempts);assertEquals(0,cold.config.routePlan.maxRouteSwitches);TeamMemberModels.requireSame(cold,old);
        assertThrows(SecurityException.class,()->TeamMemberModels.capture(member,captured(),(target,purpose,global)->group(3,1,"revoked")));
    }
    @Test public void coldSnapshotIgnoresRandomSnapshotIdButRejectsChangedOriginalOptions()throws Exception {
        var member=new JSONObject().put("id","a").put("identityRevision",2).put("model","member-A");var first=TeamMemberModels.capture(member,captured(),null);member.put("memberModelSelection",first.publicIdentity());var cold=TeamMemberModels.capture(member,captured(),null);TeamMemberModels.requireSame(cold,first.publicIdentity());
        member.put("model","other-A");assertThrows(SecurityException.class,()->TeamMemberModels.capture(member,captured(),null));
    }
    @Test public void missingNativeTargetCaptureNeverFallsBackToGlobal()throws Exception {
        var member=new JSONObject().put("modelTarget",ModelTargetRef.exact("connection-a","model-a").toJson());assertThrows(SecurityException.class,()->TeamMemberModels.capture(member,captured(),null));
        member.getJSONObject("modelTarget").put("apiKey","model supplied key");assertThrows(IllegalArgumentException.class,()->TeamMemberModels.capture(member,captured(),null));
    }
    @Test public void nativeInheritedMemberPurposeWinsOverCapturedMainPurpose()throws Exception {
        var main=captured();var target=config("configured-member");main=main.withPurposePlans(Map.of("MEMBER",ModelRoutePlan.exact(target,"MEMBER","APP_OWNER"),"SUMMARY",main.forPurpose("SUMMARY").routePlan));
        var selected=TeamMemberModels.capture(new JSONObject().put("modelInherited",true).put("model","display-only-default"),main,null);assertEquals("configured-member",selected.config.forPurpose("MEMBER").model);assertEquals("independent-summary",selected.config.forPurpose("SUMMARY").model);
    }
    @Test public void staleGroupRevisionIsNotUpgradedOrRetried()throws Exception {
        AtomicInteger calls=new AtomicInteger();var member=new JSONObject().put("modelTarget",ModelTargetRef.group("stale",2).toJson());assertThrows(SecurityException.class,()->TeamMemberModels.capture(member,captured(),(target,purpose,global)->{calls.incrementAndGet();throw new SecurityException("stale native group");}));assertEquals(1,calls.get());assertFalse(member.has("memberModelSelection"));
    }
}
