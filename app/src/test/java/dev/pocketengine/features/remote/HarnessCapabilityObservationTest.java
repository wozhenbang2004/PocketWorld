package dev.pocketengine.features.remote;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.features.remote.HarnessCapabilityObservation.Fact.*;
import static dev.pocketengine.features.remote.HarnessCapabilityObservation.Truth.*;

/** Executes only the bounded observation leaf; it does not claim a mounted option applier. */
public final class HarnessCapabilityObservationTest {
    private static final String BINDING="a".repeat(64);
    private JSONObject probe(String worker)throws Exception {
        return new JSONObject().put("environmentId","workspace").put("worker",worker).put("environmentBinding",BINDING)
                .put("available",true).put("modelInferenceVerified",false);
    }
    private JSONObject codex()throws Exception {
        return probe("codex_app_server").put("protocolVerified",true).put("accountConfigured",true).put("configReadVerified",true)
                .put("selectedModelAvailable",true).put("modelCatalogTruncated",false)
                .put("models",new JSONArray().put(new JSONObject().put("id","model-b")).put(new JSONObject().put("id","model-a")));
    }
    private HarnessCapabilityObservation observe(String worker,JSONObject probe)throws Exception {
        return HarnessCapabilityObservation.fromProbe("computer", "workspace",worker,BINDING,probe);
    }
    @Test public void observedCodexCatalogIsBoundedDataAndGrantsNoApplyOrHostTools()throws Exception {
        JSONObject raw=codex().put("runtimeApplied",true).put("executionAuthorized",true).put("supportsChangeWhileRunning",true)
                .put("capabilities",new JSONObject().put("terminal",true).put("mcp",true));
        HarnessCapabilityObservation value=observe("codex_app_server",raw);JSONObject view=value.view();
        assertEquals(TRUE,value.fact(PROTOCOL_VERIFIED));assertEquals(List.of("model-a","model-b"),value.observedModels());
        assertTrue(value.hasObservedModel("model-a"));assertFalse(value.hasObservedModel("forged"));
        assertEquals("observed_catalog",view.getJSONObject("modelOption").getString("state"));
        assertFalse(view.getJSONObject("modelOption").getBoolean("supportsChangeWhileRunning"));
        assertEquals("adapter_unimplemented",view.getJSONObject("modelOption").getString("applicationSupport"));
        assertEquals("adapter_unimplemented",view.getString("reverseTerminalSupport"));assertEquals("unknown",view.getString("effectiveHostTools"));
        assertFalse(view.getBoolean("executionAuthorized"));assertEquals("unknown",view.getString("runtimeState"));assertEquals("unknown",view.getString("configurationState"));
    }
    @Test public void failedCodexProbeDoesNotTurnUnreadAccountOrUnseenModelsIntoAbsentFacts()throws Exception {
        HarnessCapabilityObservation value=observe("codex_app_server",probe("codex_app_server").put("available",false).put("protocolVerified",false).put("accountConfigured",false));
        assertEquals(FALSE,value.fact(AVAILABLE));assertEquals(FALSE,value.fact(PROTOCOL_VERIFIED));assertEquals(UNKNOWN,value.fact(ACCOUNT_CONFIGURED));
        assertEquals(UNKNOWN,value.fact(CONFIG_READ_VERIFIED));assertTrue(value.observedModels().isEmpty());assertEquals("unknown",value.view().getString("installationState"));
    }
    @Test public void missingNullAndMalformedBooleanFactsRemainUnknown()throws Exception {
        HarnessCapabilityObservation missing=observe("codex_app_server",null);for(var fact:HarnessCapabilityObservation.Fact.values())assertEquals(UNKNOWN,missing.fact(fact));
        JSONObject raw=probe("codex_app_server").put("available","true").put("protocolVerified",1).put("accountConfigured",JSONObject.NULL);
        HarnessCapabilityObservation value=observe("codex_app_server",raw);assertEquals(UNKNOWN,value.fact(AVAILABLE));assertEquals(UNKNOWN,value.fact(PROTOCOL_VERIFIED));assertEquals(UNKNOWN,value.fact(ACCOUNT_CONFIGURED));
    }
    @Test public void claudeVersionHelpCannotProveCatalogAccountOrStreamSession()throws Exception {
        JSONObject raw=probe("claude").put("cliVersion","2.1.259").put("cliFlagsVerified",true).put("protocolVerified",false)
                .put("accountConfigured",true).put("models",new JSONArray().put(new JSONObject().put("id","claimed"))).put("threadId","claimed-thread");
        HarnessCapabilityObservation value=observe("claude",raw);assertEquals(TRUE,value.fact(CLI_FLAGS_VERIFIED));assertEquals(UNKNOWN,value.fact(ACCOUNT_CONFIGURED));
        assertTrue(value.observedModels().isEmpty());assertEquals("unknown",value.view().getJSONObject("session").getString("activeSession"));assertFalse(value.view().has("threadId"));
        assertEquals("2.1.259",value.view().getString("observedCliVersion"));assertEquals("unknown",value.view().getJSONObject("modelOption").getString("state"));
        assertThrows(SecurityException.class,()->observe("claude",raw.put("protocolVerified",true)));
    }
    @Test public void unknownAdapterDoesNotInheritDeclaredCapabilities()throws Exception {
        HarnessCapabilityObservation value=observe("kimi",probe("kimi").put("protocolVerified",true).put("accountConfigured",true));
        for(var fact:HarnessCapabilityObservation.Fact.values())assertEquals(UNKNOWN,value.fact(fact));
        assertEquals("adapter_unimplemented",value.view().getString("adapterSupport"));assertTrue(value.observedModels().isEmpty());
    }
    @Test public void probeMustMatchExactOriginalEnvironmentWorkerAndGeneration()throws Exception {
        for(String field:List.of("environmentId","environmentBinding","worker")){
            JSONObject changed=codex().put(field,"other");assertThrows(SecurityException.class,()->observe("codex_app_server",changed));
            JSONObject missing=codex();missing.remove(field);assertThrows(SecurityException.class,()->observe("codex_app_server",missing));
        }
        assertThrows(SecurityException.class,()->HarnessCapabilityObservation.fromProbe("computer","workspace","codex_app_server","bad",codex()));
        assertThrows(SecurityException.class,()->observe("codex_app_server",codex().put("modelInferenceVerified",true)));
    }
    @Test public void malformedCatalogCannotProducePartialChoicesOrLossyUnicode()throws Exception {
        for(Object bad:List.of(new JSONArray().put(new JSONObject().put("id","good")).put(new JSONObject().put("id","good")),
                new JSONArray().put(new JSONObject().put("id","good")).put(new JSONObject().put("id","bad\uD800")),
                new JSONArray().put(new JSONObject().put("id","good")).put(42),"not-an-array")){
            HarnessCapabilityObservation value=observe("codex_app_server",codex().put("models",bad));assertTrue(value.observedModels().isEmpty());assertEquals("unknown",value.view().getJSONObject("modelOption").getString("state"));
        }
        JSONArray excessive=new JSONArray();for(int i=0;i<101;i++)excessive.put(new JSONObject().put("id","model-"+i));assertTrue(observe("codex_app_server",codex().put("models",excessive)).observedModels().isEmpty());
    }
    @Test public void unobservedModelInAnIncompleteCatalogDoesNotBecomeAProvenAbsence()throws Exception {
        for(Object incomplete:List.of(true,"not-a-boolean",JSONObject.NULL)){
            HarnessCapabilityObservation value=observe("codex_app_server",codex().put("modelCatalogTruncated",incomplete).put("selectedModelAvailable",false));
            assertEquals(UNKNOWN,value.fact(SELECTED_MODEL_AVAILABLE));assertFalse(value.hasObservedModel("not-observed"));
        }
        assertEquals(FALSE,observe("codex_app_server",codex().put("selectedModelAvailable",false)).fact(SELECTED_MODEL_AVAILABLE));
    }
    @Test public void revisionIncludesNativeIdentityAndFactsButNotProbeSecretsOrObjectKeyOrder()throws Exception {
        JSONObject raw=codex().put("secret","never-export-this").put("message","Bearer private-value");HarnessCapabilityObservation original=observe("codex_app_server",raw);
        assertFalse(original.view().toString().contains("never-export"));assertFalse(original.view().toString().contains("private-value"));
        assertEquals(original.revision(),observe("codex_app_server",codex().put("models",new JSONArray().put(new JSONObject().put("id","model-a")).put(new JSONObject().put("id","model-b")))).revision());
        assertNotEquals(original.revision(),observe("codex_app_server",codex().put("accountConfigured",false)).revision());
        assertNotEquals(original.revision(),HarnessCapabilityObservation.fromProbe("other-computer","workspace","codex_app_server",BINDING,raw).revision());
        assertNotEquals(original.revision(),observe("codex_app_server",codex().put("modelCatalogTruncated",true)).revision());
        assertEquals("FALSE",observe("codex_app_server",codex().put("modelCatalogTruncated",true)).view().getJSONObject("modelOption").getString("catalogComplete"));
    }
    @Test public void snapshotsAndObservedChoicesRemainImmutable()throws Exception {
        JSONObject raw=codex();HarnessCapabilityObservation value=observe("codex_app_server",raw);String revision=value.revision();
        raw.getJSONArray("models").getJSONObject(0).put("id","changed");value.view().put("runtimeApplied",true).getJSONObject("identity").put("profileId","other");
        assertEquals(List.of("model-a","model-b"),value.observedModels());assertThrows(UnsupportedOperationException.class,()->value.observedModels().add("changed"));
        assertFalse(value.view().has("runtimeApplied"));assertEquals("unknown",value.view().getString("runtimeState"));assertEquals("computer",value.view().getJSONObject("identity").getString("profileId"));assertEquals(revision,value.revision());
    }
}
