package dev.pocketengine.runtime.models;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.ModelDiagnosticsFixtureSettings;
import dev.pocketengine.store.SettingsStore;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SettingsStore/catalog route admission; no provider inference or substitute policy gate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class StepModelRoutingGroupAdmissionTest {
    @Test public void lightStepKeepsTheCapturedGroupCandidateAndOriginalLiveRevocationGate()throws Exception {
        var prefs=RuntimeEnvironment.getApplication().getSharedPreferences("step-group-admission",0);prefs.edit().clear().commit();
        SettingsStore settings=ModelDiagnosticsFixtureSettings.create(prefs,new SettingsStore.ApiSecrets(){public String get(String slot){return "";}public void put(String slot,String value){throw new AssertionError("No credential writes in this fixture");}});
        settings.save(new JSONObject().put("baseUrl","https://fixture.invalid/v1").put("model","main-model").put("sendScreenshots",false));settings.apiConnections();
        ModelConnectionService service=new ModelConnectionService(settings,Files.createTempDirectory("step-group-catalog").resolve("catalog.json"),Clock.systemUTC());service.install();
        try{service.configure(null,ModelBinding.Format.OPENAI,URI.create("https://fixture.invalid/v1/models"));ModelBinding binding=settings.apiConfigForCatalog().capabilityBinding;
            service.editField(binding,"light-model",0,ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true),false);
            ModelTargetRef main=ModelTargetRef.exact(binding.connectionId(),"main-model"),light=ModelTargetRef.exact(binding.connectionId(),"light-model");
            JSONObject definition=new ModelGroup("definition",1,"main with a light read candidate",Set.of(binding.connectionId()),List.of(
                new ModelGroup.Candidate("main",main,true,Set.of("MAIN"),Set.of("APP_OWNER")),
                new ModelGroup.Candidate("light",light,true,Set.of("MAIN"),Set.of("APP_OWNER"))),3,1).toJson();
            JSONObject group=settings.saveModelGroup("",0,"main with a light read candidate",definition);settings.selectModelPurpose("MAIN",ModelTargetRef.group(group.getString("groupId"),group.getLong("revision")));service.saveStepRouting(true,light);
            ModelConfig original=settings.get();var session=StepModelRouting.forRun(original,new RunControl());JSONObject observation=new JSONObject().put("packageName","fixture.read.only");
            session.pick(0,observation,0);session.after("phone_query",new JSONObject(),new JSONObject().put("ok",true),observation);session.pick(1,observation,0);session.after("phone_query",new JSONObject(),new JSONObject().put("ok",true),observation);
            ModelConfig selected=session.pick(2,observation,0);assertEquals("light-model",selected.model);ModelRoutePlan plan=selected.routePlan;String candidate=plan.candidates.get(0).candidateId();settings.requireModelGroupAdmission(plan,candidate,selected);
            JSONObject revoked=new JSONObject(group.toString());revoked.getJSONArray("candidates").getJSONObject(1).put("enabled",false);settings.saveModelGroup(group.getString("groupId"),group.getLong("revision"),group.getString("name"),revoked);
            assertThrows("Disabling the original native candidate must also revoke an already captured light step",SecurityException.class,()->settings.requireModelGroupAdmission(plan,candidate,selected));
            assertEquals(group.getString("groupId"),plan.requestedAlias);assertEquals(group.getLong("revision"),plan.groupRevision);assertEquals("light",candidate);assertEquals(1,plan.maxAttempts);assertEquals(0,plan.maxRouteSwitches);assertSame(original.purposePlans.get("SUMMARY"),selected.purposePlans.get("SUMMARY"));
        }finally{StepModelRouting.install(null);}
    }
}
