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
    static JSONObject geminiProbe()throws Exception {
        JSONObject install=new JSONObject().put("format","pocketworld.remote-install-observation.v1").put("state","observed").put("identity","b".repeat(64)).put("kind","fixed_node_package_bytes_v1").put("packageVersion","0.66.0")
            .put("nodeContentVerified",true).put("packageContentVerified",true).put("executionChainVerified",false).put("osSandboxVerified",false).put("reason","original_bytes_observed");
        JSONObject observation=new JSONObject().put("format","pocketworld.remote-backend-observation.v1").put("backendId","gemini_cli_acp_v1").put("worker","gemini_acp").put("backendVersion","0.66.0").put("protocolVersion",1)
            .put("environmentId","workspace").put("environmentBinding",BINDING).put("requestedModel","").put("modeId","default").put("available",true).put("observedAt",1000000L).put("expiresAt",1300000L)
            .put("installObservation",install).put("configurationRevision","c".repeat(64)).put("profileRevision","c".repeat(64)).put("serviceCapabilityRevision","d".repeat(64))
            .put("protocol",new JSONObject().put("state","verified").put("agentName","gemini-cli").put("agentVersion","0.66.0").put("wireVersion",1))
            .put("authentication",new JSONObject().put("state","credential_present").put("authenticated",false).put("source","owner_environment_presence_only"))
            .put("model",new JSONObject().put("state","not_checked").put("requested","").put("current","").put("inferenceVerified",false))
            .put("mode",new JSONObject().put("state","not_checked").put("requested","default").put("current",""))
            .put("reason",new JSONObject().put("code","initialize_observed").put("message","Only the original initialize response was observed"));
        return new JSONObject().put("environmentId","workspace").put("environmentBinding",BINDING).put("profileBinding","e".repeat(64)).put("worker","gemini_acp").put("available",true).put("protocolVerified",true)
            .put("accountConfigured",JSONObject.NULL).put("modelInferenceVerified",false).put("backendObservation",observation);
    }
    @Test public void geminiInitializeDoesNotClaimAuthenticationModelInferenceOrPhoneTools()throws Exception {
        HarnessCapabilityObservation value=observe("gemini_acp",geminiProbe().put("accountConfigured",true).put("executionAuthorized",true).put("capabilities",new JSONObject().put("terminal",true)));
        assertEquals(TRUE,value.fact(AVAILABLE));assertEquals(TRUE,value.fact(PROTOCOL_VERIFIED));assertEquals(UNKNOWN,value.fact(ACCOUNT_CONFIGURED));assertEquals(UNKNOWN,value.fact(SELECTED_MODEL_AVAILABLE));assertEquals(FALSE,value.fact(MODEL_INFERENCE_VERIFIED));
        JSONObject view=value.view();assertFalse(view.getBoolean("executionAuthorized"));assertEquals("none",view.getString("effectiveHostTools"));assertEquals("unsupported",view.getString("reverseTerminalSupport"));assertTrue(value.observedModels().isEmpty());
        JSONObject selection=value.backendSelection();assertEquals(11,selection.length());assertEquals(value.revision(),selection.getString("capabilityRevision"));assertEquals("d".repeat(64),selection.getString("serviceCapabilityRevision"));assertFalse(selection.getBoolean("readOnly"));assertEquals("",selection.getString("model"));assertNotEquals(selection.getString("capabilityRevision"),selection.getString("serviceCapabilityRevision"));
    }
    @Test public void geminiMissingInstallationCredentialMismatchAndUnknownStayDistinct()throws Exception {
        for(String reason:List.of("installation_missing","credential_missing","protocol_mismatch","probe_unknown")){
            JSONObject probe=geminiProbe().put("available",false),raw=probe.getJSONObject("backendObservation").put("available",false).put("serviceCapabilityRevision","");raw.getJSONObject("reason").put("code",reason);
            if(reason.equals("installation_missing"))raw.getJSONObject("installObservation").put("state","missing").put("identity","").put("packageVersion","").put("nodeContentVerified",false).put("packageContentVerified",false);
            if(reason.equals("credential_missing"))raw.getJSONObject("authentication").put("state","credential_missing");
            if(reason.equals("protocol_mismatch")||reason.equals("probe_unknown")){probe.put("protocolVerified",false);raw.getJSONObject("protocol").put("state",reason.equals("protocol_mismatch")?"mismatch":"unknown").put("agentName","").put("agentVersion","").put("wireVersion",0);}
            HarnessCapabilityObservation observed=observe("gemini_acp",probe);assertEquals(FALSE,observed.fact(AVAILABLE));assertEquals(UNKNOWN,observed.fact(ACCOUNT_CONFIGURED));assertEquals(reason,observed.view().getJSONObject("backendObservation").getJSONObject("reason").getString("code"));assertThrows(SecurityException.class,observed::backendSelection);
        }
    }
    @Test public void geminiRejectsUnsupportedClaimsLossyShapesAndReplacedOriginalIdentity()throws Exception {
        for(String key:List.of("environmentId","environmentBinding","backendVersion","backendId","modeId")){JSONObject probe=geminiProbe();probe.getJSONObject("backendObservation").put(key,"replaced");assertThrows(SecurityException.class,()->observe("gemini_acp",probe));}
        for(String key:List.of("executionChainVerified","osSandboxVerified")){JSONObject probe=geminiProbe();probe.getJSONObject("backendObservation").getJSONObject("installObservation").put(key,true);assertThrows(SecurityException.class,()->observe("gemini_acp",probe));}
        JSONObject authenticated=geminiProbe();authenticated.getJSONObject("backendObservation").getJSONObject("authentication").put("authenticated",true);assertThrows(SecurityException.class,()->observe("gemini_acp",authenticated));
        JSONObject inferred=geminiProbe();inferred.getJSONObject("backendObservation").getJSONObject("model").put("inferenceVerified",true);assertThrows(SecurityException.class,()->observe("gemini_acp",inferred));
        JSONObject secret=geminiProbe();secret.getJSONObject("backendObservation").getJSONObject("installObservation").put("executablePath","/private/unreviewed");assertThrows(SecurityException.class,()->observe("gemini_acp",secret));
        JSONObject overflow=geminiProbe();overflow.getJSONObject("backendObservation").getJSONObject("reason").put("message","x".repeat(1001));assertThrows(SecurityException.class,()->observe("gemini_acp",overflow));
        JSONObject skew=geminiProbe();skew.getJSONObject("backendObservation").put("expiresAt",1400000);assertThrows(SecurityException.class,()->observe("gemini_acp",skew));
    }
    @Test public void geminiNativeRevisionPinsOriginalProfileServiceAndRequestedModel()throws Exception {
        HarnessCapabilityObservation first=observe("gemini_acp",geminiProbe());
        JSONObject profile=geminiProbe().put("profileBinding","f".repeat(64));assertNotEquals(first.revision(),observe("gemini_acp",profile).revision());
        JSONObject service=geminiProbe();service.getJSONObject("backendObservation").put("serviceCapabilityRevision","f".repeat(64));assertNotEquals(first.revision(),observe("gemini_acp",service).revision());
        JSONObject model=geminiProbe();model.getJSONObject("backendObservation").put("requestedModel","observed-choice").getJSONObject("model").put("requested","observed-choice");assertNotEquals(first.revision(),observe("gemini_acp",model).revision());assertEquals("observed-choice",observe("gemini_acp",model).backendSelection().getString("model"));
        JSONObject selection=first.backendSelection();selection.getJSONObject("installObservation").put("identity","f".repeat(64));assertEquals("b".repeat(64),first.backendSelection().getJSONObject("installObservation").getString("identity"));assertThrows(SecurityException.class,()->HarnessCapabilityObservation.requireSameBackendSelection(first.backendSelection(),selection));
        assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendSelection(first.backendSelection().put("hostTools",true)));
        assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendSelection(first.backendSelection().put("readOnly",true)));
        assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendSelection(first.backendSelection().put("modeId","plan")));
        HarnessCapabilityObservation.requireSameBackendSelection(first.backendSelection(),new JSONObject(first.backendSelection().toString()));
    }
    static JSONObject geminiApprovalSource()throws Exception {
        return new JSONObject().put("backendSelection",HarnessCapabilityObservation.fromProbe("computer","workspace","gemini_acp",BINDING,geminiProbe()).backendSelection())
            .put("worker","gemini_acp").put("protocol","gemini_acp_v1").put("readOnly",false).put("protocolVerified",true).put("controlProtocolVerified",true).put("sessionIdentityConfirmed",true)
            .put("sessionId","original-session").put("promptRequestId","original-prompt");
    }
    static JSONObject geminiApproval()throws Exception {
        return new JSONObject().put("requestId","f334ef8b-3658-493b-8aad-f65409b65553").put("requestHash","f".repeat(64)).put("protocol","gemini_acp_v1").put("method","acp/session/request_permission").put("backendId","gemini_cli_acp_v1")
            .put("sessionId","original-session").put("promptRequestId","original-prompt").put("toolCallId","original-tool")
            .put("toolCall",new JSONObject().put("toolCallId","original-tool").put("title","审阅原工具调用").put("rawInput",new JSONObject().put("路径","src/例子.java").put("scale",1.25).put("parts",new JSONArray().put("完整正文").put(new JSONObject().put("value",2)))))
            .put("options",new JSONArray().put(new JSONObject().put("optionId","once").put("kind","allow_once").put("name","允许一次")).put(new JSONObject().put("optionId","reject").put("kind","reject_once").put("name","拒绝一次")))
            .put("canAccept",true).put("allowOnceOptionId","once").put("rejectOnceOptionId","reject").put("scopeEnforced",false).put("osReadIsolationVerified",false);
    }
    @Test public void geminiPermissionKeepsCompleteOriginalBodyAndOpaqueServiceHash()throws Exception {
        JSONObject original=geminiApproval(),checked=HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),original);
        assertEquals("f".repeat(64),checked.getString("requestHash"));
        JSONObject input=checked.getJSONObject("toolCall").getJSONObject("rawInput");assertEquals("src/例子.java",input.getString("路径"));assertEquals(1.25,input.getDouble("scale"),0);assertEquals(2,input.getJSONArray("parts").getJSONObject(1).getInt("value"));
        assertEquals(original.getJSONObject("toolCall").toString(),checked.getJSONObject("toolCall").toString());assertEquals(original.getJSONArray("options").toString(),checked.getJSONArray("options").toString());
        original.getJSONObject("toolCall").put("title","changed after capture");assertEquals("审阅原工具调用",checked.getJSONObject("toolCall").getString("title"));
    }
    @Test public void geminiPermissionAcceptRequiresOneOriginalAllowOnceNeverAlways()throws Exception {
        JSONObject always=geminiApproval();always.getJSONArray("options").getJSONObject(0).put("kind","allow_always");always.put("canAccept",false).put("allowOnceOptionId","");
        assertFalse(HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),always).getBoolean("canAccept"));
        JSONObject forged=new JSONObject(always.toString()).put("canAccept",true).put("allowOnceOptionId","once");assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),forged));
        JSONObject ambiguous=geminiApproval();ambiguous.getJSONArray("options").put(new JSONObject().put("optionId","second-once").put("kind","allow_once").put("name","另一项单次允许"));ambiguous.put("canAccept",false).put("allowOnceOptionId","");
        assertFalse(HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),ambiguous).getBoolean("canAccept"));
        assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),ambiguous.put("canAccept",true).put("allowOnceOptionId","once")));
    }
    @Test public void geminiPermissionRequiresOriginalSessionPromptMethodAndIdentity()throws Exception {
        for(String key:List.of("sessionId","promptRequestId","protocol","method","backendId","toolCallId","requestId","requestHash")){
            JSONObject changed=geminiApproval().put(key,"replacement");assertThrows(key,SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),changed));
        }
        for(String key:List.of("protocolVerified","controlProtocolVerified","sessionIdentityConfirmed")){
            JSONObject changed=geminiApprovalSource().put(key,false);assertThrows(key,SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(changed,geminiApproval()));
        }
        JSONObject readonly=geminiApprovalSource().put("readOnly",true);assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(readonly,geminiApproval()));
        for(String key:List.of("scopeEnforced","osReadIsolationVerified")){JSONObject claimed=geminiApproval().put(key,true);assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),claimed));}
    }
    @Test public void geminiPermissionRejectsOversizeOrAlteredOptionShapesWithoutTruncation()throws Exception {
        JSONObject huge=geminiApproval();huge.getJSONObject("toolCall").put("completeText","文".repeat(12000));assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),huge));
        JSONObject duplicate=geminiApproval();duplicate.getJSONArray("options").getJSONObject(1).put("optionId","once");assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),duplicate));
        JSONObject extra=geminiApproval();extra.getJSONArray("options").getJSONObject(0).put("hostTools",true);assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),extra));
        JSONObject unknown=geminiApproval();unknown.getJSONArray("options").getJSONObject(0).put("kind","allow_phone");assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),unknown));
        JSONObject empty=geminiApproval().put("options",new JSONArray());assertThrows(SecurityException.class,()->HarnessCapabilityObservation.checkedBackendApproval(geminiApprovalSource(),empty));
    }
}
