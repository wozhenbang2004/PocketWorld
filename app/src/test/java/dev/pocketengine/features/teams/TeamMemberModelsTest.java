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
