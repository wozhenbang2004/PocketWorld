package dev.pocketengine.runtime;

import java.util.List;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the real recovery projection's captured multi-secret detector seam, without Android stubs. */
public final class BedrockRecoveryProjectionTest {
    private static final List<String> SECRETS=List.of("fixture-access-key","fixture-secret-key","fixture-session-token");
    private static final Predicate<String> SENSITIVE=value->SECRETS.stream().anyMatch(value::contains);
    private static JSONObject row(String id)throws Exception {
        return new JSONObject().put("operationId",id).put("runId","run-original").put("tool","project_execute").put("state","receipt");
    }
    private static JSONObject facts(RecoveryProjection view)throws Exception {
        String text=view.referenceData(),prefix="<native_recovery_facts>\n";
        return new JSONObject(text.substring(text.indexOf(prefix)+prefix.length(),text.indexOf("\n</native_recovery_facts>")));
    }
    @Test public void allCapturedCredentialFieldsSuppressNativeLocatorsWithoutSyntheticReplacement()throws Exception {
        JSONArray source=new JSONArray();for(String secret:SECRETS)source.put(row("operation-"+secret));source.put(row("operation-safe"));
        RecoveryProjection view=RecoveryProjection.fromJournalWithRedaction("task-fixture-session-token",source,SENSITIVE);
        JSONObject fact=facts(view);assertTrue(fact.isNull("taskId"));assertEquals(1,fact.getInt("shownRows"));assertEquals(3,fact.getInt("redactedFields"));
        assertEquals("operation-safe",fact.getJSONArray("records").getJSONObject(0).getString("operationId"));
        for(String secret:SECRETS)assertFalse(view.referenceData().contains(secret));assertFalse(view.referenceData().contains("密钥已隐藏"));
    }
    @Test public void metadataAndReceiptFactsUseTheSameCapturedDetectorWithoutMutatingJournal()throws Exception {
        JSONArray source=new JSONArray().put(row("operation-safe").put("runId","run-fixture-access-key").put("tool","fixture-secret-key")
                .put("receipt",new JSONObject().put("status","fixture-session-token").put("accepted",true)));
        String before=source.toString();JSONObject fact=facts(RecoveryProjection.fromJournalWithRedaction("task-original",source,SENSITIVE));
        JSONObject result=fact.getJSONArray("records").getJSONObject(0);assertTrue(result.isNull("runId"));assertTrue(result.isNull("tool"));
        assertTrue(result.getJSONObject("receiptFacts").isNull("status"));assertTrue(result.getJSONObject("receiptFacts").getBoolean("accepted"));
        assertEquals(before,source.toString());assertFalse(fact.getBoolean("executionAuthorized"));assertFalse(fact.getBoolean("automaticReplay"));
    }
    @Test public void legacySingleSecretProjectionKeepsOriginalBehavior()throws Exception {
        JSONArray source=new JSONArray().put(row("fixture-access-key")).put(row("operation-safe").put("tool","fixture-access-key"));
        String original=RecoveryProjection.fromJournal("task-original",source,"fixture-access-key").referenceData();
        String adapted=RecoveryProjection.fromJournalWithRedaction("task-original",source,value->value.contains("fixture-access-key")).referenceData();
        assertEquals(original,adapted);
    }
    @Test public void emptyJournalDoesNotInventRecoveryFacts()throws Exception {
        Predicate<String> neverCalled=value->{throw new AssertionError("empty journal should not scan credential identities");};
        assertEquals("",RecoveryProjection.fromJournalWithRedaction("task-original",new JSONArray(),neverCalled).referenceData());
        assertEquals("",RecoveryProjection.fromJournalWithRedaction("task-original",null,neverCalled).referenceData());
    }
}
