package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class RunContextBudgetTest {
    private static ModelConfig config(String provider, String model, String key) {
        return new ModelConfig("https://fixture.invalid/v1", key, model, false, 40, 10, provider, 1000);
    }
    private static ContextBudgetPolicy.RouteBudget route(ModelConfig config, long bytes, long tokens, long imageCost) {
        return ContextBudgetPolicy.RouteBudget.forRoute(config,
                new ContextWindow.Budget(tokens, 1000, 1000, bytes, 64, imageCost),
                ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE, 7, 1000);
    }
    private static JSONArray base() throws Exception {
        return new JSONArray().put(message("system", "host invariant"))
                .put(message("user", "original owner goal; permission required before sending"));
    }
    private static JSONObject message(String role, Object content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }
    private static JSONObject observation(String value) throws Exception {
        return message("user", new JSONArray().put(new JSONObject().put("type", "text").put("text", value)));
    }

    @Test public void unknownWindowHasAFiniteByteLimitButNoInventedTokenOrImagePrice() throws Exception {
        ModelConfig config=config("openai_chat", "custom", "secret-never-log");
        RunContextBudget budget=new RunContextBudget(selected -> null);
        JSONArray messages=base().put(message("user", new JSONArray().put(new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/png;base64,AAAA")))));
        RunContextBudget.Projection p=budget.request(messages, null, config);
        assertTrue(p.plan().canSend());
        assertEquals(ContextBudgetPolicy.REQUEST_BYTES,p.diagnostics().getLong("maxCanonicalBytes"));
        assertFalse(p.diagnostics().getBoolean("modelWindowKnown"));
        assertTrue(p.diagnostics().isNull("modelContextTokens"));
        assertTrue(p.diagnostics().getJSONObject("after").isNull("estimatedInputTokens"));
        assertFalse(p.diagnostics().toString().contains("secret-never-log"));
        assertEquals("unknown",p.diagnostics().getJSONObject("metadata").getString("metadataSource"));
    }

    @Test public void onlyNativeProvenDuplicatesAreEligibleNotAnOwnerLookalike() throws Exception {
        ModelConfig config=config("openai_chat", "custom", "");
        RunContextBudget budget=new RunContextBudget(selected -> route(config, 6000, 100000, 0));
        JSONObject owner=observation("Owner attachment: " + "u".repeat(200));
        JSONObject actual=new JSONObject().put("observationId","old-id").put("nodes",new JSONArray()
                .put(new JSONObject().put("id","old-node").put("text","same actual screen-"+"x".repeat(5000))));
        JSONObject old=observation(actual.toString());budget.observed(old,actual);
        actual.put("observationId","new-id").getJSONArray("nodes").getJSONObject(0).put("id","new-node");
        JSONObject newest=observation(actual.toString());budget.observed(newest,actual);
        JSONArray history=base().put(owner).put(old).put(newest);String original=history.toString();
        RunContextBudget.Projection p=budget.request(history,new JSONArray(),config);
        assertEquals(1,p.plan().removed.size());assertEquals(3,p.plan().removed.get(0).index());
        String after=p.plan().messages().toString();
        assertTrue(after.contains("Owner attachment"));assertTrue(after.contains("new-id"));
        assertFalse(after.contains("old-id"));assertTrue(after.contains("permission required"));
        assertEquals(original,history.toString());
        assertEquals("host_proven_duplicate_observations_only",p.diagnostics().getString("retentionPolicy"));
    }

    @Test public void expiredReferencesDoNotMakeUniqueTextPixelsAppWindowSourceOrCaptureTimeDisposable() throws Exception {
        ModelConfig config=config("openai_chat","custom","");
        JSONObject first=new JSONObject().put("observationId","old-id").put("packageName","first.app").put("displayId",0)
                .put("windowId",1).put("sessionId","native-session").put("sourceRevision",1).put("observedAtUptimeMs",1000)
                .put("imageBase64","AAAA").put("imageMimeType","image/png")
                .put("nodes",new JSONArray().put(new JSONObject().put("id","old-node").put("text","task knowledge-"+"x".repeat(4000))));
        for(String changed:new String[]{"text","imageBase64","packageName","displayId","windowId","sessionId","sourceRevision","observedAtUptimeMs"}) {
            RunContextBudget budget=new RunContextBudget(selected->route(config,6000,100000,0));
            JSONObject second=new JSONObject(first.toString()).put("observationId","new-id");
            second.getJSONArray("nodes").getJSONObject(0).put("id","new-node");
            if(changed.equals("text"))second.getJSONArray("nodes").getJSONObject(0).put("text","different task knowledge-"+"x".repeat(4000));
            else if(second.opt(changed) instanceof Number)second.put(changed,second.getLong(changed)+1);
            else second.put(changed,second.getString(changed)+"-changed");
            JSONObject old=observation(first.toString()),current=observation(second.toString());
            budget.observed(old,first);budget.observed(current,second);
            try { budget.request(base().put(old).put(current),null,config);fail("must retain unique "+changed); }
            catch(RunContextBudget.Blocked blocked) {
                assertEquals(changed,"budget_exceeded",blocked.status);
                assertEquals(changed,0,blocked.diagnostics.getJSONArray("removed").length());
            }
        }
    }

    @Test public void nativeProofSurvivesExactWireCopiesButNeverTransfersToOwnerLookalikes() throws Exception {
        ModelConfig config=config("openai_chat","custom","");
        RunContextBudget budget=new RunContextBudget(selected -> route(config,11000,100000,0));
        JSONObject data=new JSONObject().put("observationId","old-id").put("screen","x".repeat(5000));
        JSONObject old=observation(data.toString());budget.observed(old,data);
        JSONObject owner=new JSONObject(old.toString()).put("ownerMessageId","owner-original");
        data.put("observationId","new-id");JSONObject current=observation(data.toString());budget.observed(current,data);
        JSONArray original=base().put(owner).put(old).put(current), wire=new JSONArray(original.toString());
        String exact=original.toString();budget.projected(original,wire);
        RunContextBudget.Projection p=budget.request(wire,null,config);
        assertEquals(1,p.plan().removed.size());assertEquals(3,p.plan().removed.get(0).index());
        assertTrue(p.plan().messages().toString().contains("owner-original"));
        assertTrue(p.plan().messages().toString().contains("new-id"));assertEquals(exact,original.toString());
        assertNull(budget.observationFingerprint(wire.getJSONObject(2)));
    }

    @Test public void changedProjectedTextPixelsOrRowCountCannotInheritNativeProof() throws Exception {
        for (String changed : new String[]{"text","pixels","row_count"}) {
            ModelConfig config=config("openai_chat","custom","");
            RunContextBudget budget=new RunContextBudget(selected -> route(config,6000,100000,100));
            JSONObject data=new JSONObject().put("observationId","old-id").put("screen","x".repeat(5000)).put("imageBase64","AAAA");
            JSONObject old=observation(data.toString());old.getJSONArray("content").put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,AAAA")));budget.observed(old,data);
            data.put("observationId","new-id");JSONObject current=observation(data.toString());budget.observed(current,data);
            JSONArray original=base().put(old).put(current),wire=new JSONArray(original.toString());
            JSONObject copied=wire.getJSONObject(2);
            if(changed.equals("text"))copied.getJSONArray("content").getJSONObject(0).put("text","changed-"+"x".repeat(5000));
            else if(changed.equals("pixels"))copied.getJSONArray("content").getJSONObject(1).getJSONObject("image_url").put("url","data:image/png;base64,BBBB");
            else wire.put(message("user","extra row"));
            budget.projected(original,wire);assertNull(changed,budget.observationFingerprint(copied));
            try{budget.request(wire,null,config);fail(changed);}
            catch(RunContextBudget.Blocked blocked){assertEquals(changed,"budget_exceeded",blocked.status);assertEquals(changed,0,blocked.diagnostics.getJSONArray("removed").length());}
        }
    }

    @Test public void successiveBoundariesReleaseOldWireCopiesAndKeepTheOriginalNativeProof() throws Exception {
        RunContextBudget budget=new RunContextBudget(selected -> null);
        JSONObject data=new JSONObject().put("observationId","native").put("screen","real page");
        JSONObject nativeRow=observation(data.toString());budget.observed(nativeRow,data);
        JSONArray original=base().put(nativeRow),first=new JSONArray(original.toString()),second=new JSONArray(original.toString());
        budget.projected(original,first);assertNotNull(budget.observationFingerprint(first.getJSONObject(2)));
        budget.projected(original,second);assertNull(budget.observationFingerprint(first.getJSONObject(2)));
        assertEquals(budget.observationFingerprint(nativeRow),budget.observationFingerprint(second.getJSONObject(2)));
        budget.projected(original,new JSONArray());assertNull(budget.observationFingerprint(second.getJSONObject(2)));
        assertNotNull(budget.observationFingerprint(nativeRow));
    }

    @Test public void successfulReadBatchIsNotAutomaticallyDispensableEvenUnderPressure() throws Exception {
        ModelConfig config=config("openai_chat", "custom", "");
        RunContextBudget budget=new RunContextBudget(selected -> route(config, 1000, 100000, 0));
        JSONArray history=base().put(new JSONObject().put("role", "assistant").put("tool_calls",new JSONArray()
                .put(ContextWindowTest.call("old-read"))))
                .put(ContextWindowTest.receipt("old-read",new JSONObject().put("ok",true).put("content","x".repeat(5000))))
                .put(message("user", "Compare the old result with the next one."));
        try { budget.request(history,null,config);fail(); }
        catch (RunContextBudget.Blocked blocked) {
            assertEquals("budget_exceeded",blocked.status);
            assertEquals(0,blocked.diagnostics.getJSONArray("removed").length());
            assertFalse(blocked.diagnostics.getBoolean("toolResultsAutomaticallyEvicted"));
        }
    }

    @Test public void currentSchemasAreChargedAfterPreparationNotStaleCachedToolLists() throws Exception {
        ModelConfig config=config("openai_chat", "custom", "");
        RunContextBudget budget=new RunContextBudget(selected -> route(config, 1500, 100000, 0));
        assertTrue(budget.request(base(),new JSONArray(),config).plan().canSend());
        JSONArray newTools=new JSONArray().put(new JSONObject().put("description","schema-"+"x".repeat(5000)));
        try { budget.request(base(),newTools,config);fail(); }
        catch (RunContextBudget.Blocked blocked) { assertEquals("budget_exceeded",blocked.status); }
    }

    @Test public void routeBudgetCannotCrossModelProviderEndpointOrCredentialGeneration() throws Exception {
        ModelConfig original=config("openai_chat", "same-id", "first-key");
        ContextBudgetPolicy.RouteBudget metadata=route(original, 10000, 100000, 0);
        ModelConfig[] changed={original.withModel("other-id"),config("openai_responses","same-id","first-key"),
                config("openai_chat","same-id","next-key"),new ModelConfig("https://other.invalid/v1","first-key","same-id",false,40,10,"openai_chat",1000)};
        for (ModelConfig config:changed) {
            try { new RunContextBudget(selected -> metadata).request(base(),null,config);fail(); }
            catch (RunContextBudget.Blocked blocked) { assertEquals("stale_context_budget_route",blocked.status); }
        }
    }

    @Test public void accountAuthorizationVersionIsPartOfTheWindowBinding() throws Exception {
        ModelConfig base=config("openai_chat","unused","");
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                .put("accountId","fixture-account").put("accountVersion",4).put("modelId","same-id"));
        ModelConfig original=base.withAccountModel(ref);
        ContextBudgetPolicy.RouteBudget metadata=route(original,10000,100000,0);
        try { new RunContextBudget(selected -> metadata).request(base(),null,base.withAccountModel(ref.withVersion(5)));fail(); }
        catch (RunContextBudget.Blocked blocked) { assertEquals("stale_context_budget_route",blocked.status); }
    }

    @Test public void configuredOutputReserveCannotUndercutTheActualConfiguredCeiling() {
        ModelConfig config=config("openai_chat","custom","");
        try {
            ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(10000,999,1000,10000,0,0),
                    ContextBudgetPolicy.SourceKind.PROVIDER_METADATA,1,1000);
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void freshMetadataResolvedOncePerBoundaryAndRouteShrinkBlocksTheNextRequest() throws Exception {
        ModelConfig config=config("openai_chat","custom","");AtomicInteger reads=new AtomicInteger();
        RunContextBudget budget=new RunContextBudget(selected -> route(config,reads.incrementAndGet()==1?10000:100,100000,0));
        assertTrue(budget.request(base(),null,config).plan().canSend());assertEquals(1,reads.get());
        try { budget.request(base(),null,config);fail(); }
        catch (RunContextBudget.Blocked blocked) { assertEquals("budget_exceeded",blocked.status); }
        assertEquals(2,reads.get());
    }

    @Test public void checkpointAccountsForTheActualEnvelopeAndKeepsNormalMultiTurnText() throws Exception {
        ModelConfig config=config("openai_chat","custom","");
        RunContextBudget budget=new RunContextBudget(selected -> null);
        JSONArray history=base();
        for(int n=0;n<40;n++) {
            String id="read-"+n;
            history.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(ContextWindowTest.call(id))));
            history.put(ContextWindowTest.receipt(id,new JSONObject().put("ok",true).put("content","x".repeat(16000))));
        }
        RunContextBudget.Projection p=budget.checkpoint(history,java.util.Map.of(),40,new JSONObject().put("nodes","actual current"),config);
        assertEquals(history.toString(),p.plan().messages().toString());
        assertTrue(p.diagnostics().getJSONObject("after").getLong("canonicalUtf8Bytes")>640000);
        try { budget.checkpoint(base(),java.util.Map.of(),0,new JSONObject().put("nodes","x".repeat((int)ContextBudgetPolicy.CHECKPOINT_BYTES)),config);fail(); }
        catch(RunContextBudget.Blocked blocked){assertEquals("checkpoint_envelope_exceeded",blocked.status);}
    }

    @Test public void base64AndPlatformJsonSlashExpansionAreBothCountedBeforeAdmission() throws Exception {
        // Small exact sample, not an image-quality test or giant-image performance benchmark.
        byte[] raw=new byte[3072];java.util.Arrays.fill(raw,(byte)255);
        String encoded=Base64.getEncoder().encodeToString(raw);
        assertEquals(4096,encoded.length()); // 4 * ceil(raw / 3), not raw bytes.
        JSONArray history=base();
        for(int n=0;n<2;n++)history.put(message("user",new JSONArray().put(new JSONObject().put("type","image_url")
                .put("image_url",new JSONObject().put("url","data:image/jpeg;base64,"+encoded)))));
        long actual=new JSONObject().put("messages",history).put("tools",new JSONArray()).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ContextWindow.Plan p=ContextWindow.plan(history,null,ContextWindow.Budget.localBytes(12288),ContextWindow.Retention.preserveAll());
        assertEquals(actual,p.before.canonicalUtf8Bytes());
        assertEquals(actual<=12288,p.canSend());
        if (JSONObject.quote("/").contains("\\/")) {
            assertEquals("budget_exceeded",p.status); // Android JSON escapes every slash.
            assertTrue(2L*8*1024*1024*2>ContextBudgetPolicy.REQUEST_BYTES);
        } else assertTrue(p.canSend());
        assertTrue(p.diagnostics().getJSONObject("before").isNull("estimatedInputTokens"));
    }

    @Test public void knownWindowWithUnknownImagePricingStopsRatherThanSilentlyDisablingImages() throws Exception {
        ModelConfig config=config("openai_chat","custom","");
        JSONArray history=base().put(message("user",new JSONArray().put(new JSONObject().put("type","image_url")
                .put("image_url",new JSONObject().put("url","data:image/png;base64,AAAA")))));
        try { new RunContextBudget(selected -> route(config,10000,100000,0)).request(history,null,config);fail(); }
        catch(RunContextBudget.Blocked blocked){assertEquals("image_budget_unknown",blocked.status);}
        assertEquals("data:image/png;base64,AAAA",history.getJSONObject(history.length()-1).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url"));
    }
}
