package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real private-file journeys; no replacement preference store or process-global model selection. */
public final class ConversationModelPreferencesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path file;
    private ConversationModelPreferences preferences;

    @Before public void createStore() throws Exception {
        file = temporary.newFolder("conversation-models").toPath().resolve("preferences.json");
        preferences = reopen();
    }

    private ConversationModelPreferences reopen() { return new ConversationModelPreferences(file); }
    private static String conversation(String id) { return ConversationModelPreferences.conversation(id); }
    private static String draft(String id) { return ConversationModelPreferences.draft(id); }
    private static JSONObject choice(String account, String model) throws Exception {
        JSONObject reference = ModelConfig.AccountModelRef.fromJson(new JSONObject()
                .put("provider", ModelConfig.ACCOUNT_PROVIDER).put("accountId", account)
                .put("accountVersion", 3).put("modelId", model)).toJson();
        return new JSONObject().put("kind", "account").put("nativeRef", reference)
                .put("outputTokens", 4096).put("reasoning", ReasoningOptions.DEFAULT.toJson())
                .put("imageTokenReserve", 0);
    }
    private static JSONObject apiChoice(String connection, String model) throws Exception {
        ModelBinding binding = new ModelBinding(connection, "provider-a", ModelBinding.Protocol.OPENAI_CHAT,
                ModelBinding.Format.OPENAI, URI.create("https://api.example.test/v1/models"), "credential-a", 1,
                "installation-a", 1, false);
        return new JSONObject().put("kind", "api").put("connectionId", connection).put("connectionRevision", 3)
                .put("binding", binding.toJson()).put("modelId", model).put("outputTokens", 2048)
                .put("reasoning", new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT, "low", 0).toJson())
                .put("imageTokenReserve", 256);
    }

    @Test public void explicitApiRecheckRetainsNativeOptionsWithoutEditingAnotherScopeOrAnInFlightChoice() throws Exception {
        String a = conversation("a"), b = conversation("b");
        preferences.save(a, 0, apiChoice("service-a", "model-m"));
        preferences.save(b, 0, apiChoice("service-b", "model-n").put("outputTokens", 8192));
        preferences.pinSubmission("input-a", a);
        JSONObject original = preferences.snapshot(a);
        byte[] before = Files.readAllBytes(file);

        JSONObject retained = ConversationModelPreferences.apiReselectionTarget(original, a, 1, "service-a", "model-m");
        assertNotNull(retained);
        assertEquals(2048, retained.getInt("outputTokens"));
        assertEquals(new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT, "low", 0),
                ReasoningOptions.fromJson(retained.getJSONObject("reasoning")));
        assertEquals(256, retained.getInt("imageTokenReserve"));
        assertEquals("A metadata revision is not a binding validity decision", 3, retained.getLong("connectionRevision"));
        assertTrue(retained.similar(original.getJSONObject("target")));

        retained.put("outputTokens", 4096);
        retained.getJSONObject("reasoning").put("level", "high");
        assertEquals("Returned options are a copy, not live preference state", 2048, original.getJSONObject("target").getInt("outputTokens"));
        assertEquals("low", original.getJSONObject("target").getJSONObject("reasoning").getString("level"));
        assertEquals(2048, reopen().targetForAdmission("a", "input-a").getInt("outputTokens"));
        assertEquals(8192, reopen().snapshot(b).getJSONObject("target").getInt("outputTokens"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test public void differentApiTargetsAndInheritedOrAccountChoicesDoNotBorrowTheOriginalOptions() throws Exception {
        String key = conversation("a");
        assertNull(ConversationModelPreferences.apiReselectionTarget(preferences.snapshot(key), key, 0, "service-a", "model-m"));
        preferences.save(key, 0, apiChoice("service-a", "model-m"));
        JSONObject original = preferences.snapshot(key);
        assertNull(ConversationModelPreferences.apiReselectionTarget(original, key, 1, "service-b", "model-m"));
        assertNull(ConversationModelPreferences.apiReselectionTarget(original, key, 1, "service-a", "model-n"));
        preferences.save(key, 1, choice("account-a", "model-m"));
        assertNull(ConversationModelPreferences.apiReselectionTarget(preferences.snapshot(key), key, 2, "service-a", "model-m"));
        preferences.save(key, 2, null);
        assertNull(ConversationModelPreferences.apiReselectionTarget(preferences.snapshot(key), key, 3, "service-a", "model-m"));
    }

    @Test public void apiRecheckRejectsForeignOrStaleScopeAndInvalidStoredOptions() throws Exception {
        String key = conversation("a");
        preferences.save(key, 0, apiChoice("service-a", "model-m"));
        JSONObject original = preferences.snapshot(key);
        assertThrows(IllegalStateException.class, () -> ConversationModelPreferences.apiReselectionTarget(original,
                conversation("b"), 1, "service-a", "model-m"));
        assertThrows(IllegalStateException.class, () -> ConversationModelPreferences.apiReselectionTarget(original,
                key, 0, "service-a", "model-m"));
        for (Object invalid : List.of("1", 1.5, -1)) {
            JSONObject wrongRevision = new JSONObject(original.toString()).put("revision", invalid);
            assertThrows(Exception.class, () -> ConversationModelPreferences.apiReselectionTarget(wrongRevision,
                    key, 1, "service-a", "model-m"));
        }
        JSONObject invalidTarget = new JSONObject(original.toString());
        invalidTarget.getJSONObject("target").put("outputTokens", 1);
        assertThrows(IllegalArgumentException.class, () -> ConversationModelPreferences.apiReselectionTarget(invalidTarget,
                key, 1, "service-a", "model-m"));
        assertTrue(reopen().snapshot(key).similar(original));
    }
    private static void assertChoice(JSONObject actual, String account, String model) throws Exception {
        assertNotNull("An explicit native selection must not become inherited", actual);
        assertEquals("account", actual.getString("kind"));
        ModelConfig.AccountModelRef reference = ModelConfig.AccountModelRef.fromJson(actual.getJSONObject("nativeRef"));
        assertEquals(account, reference.accountId);
        assertEquals(model, reference.modelId);
        assertEquals(3, reference.accountVersion);
        assertEquals(4096, actual.getInt("outputTokens"));
        assertEquals(ReasoningOptions.DEFAULT, ReasoningOptions.fromJson(actual.getJSONObject("reasoning")));
        assertEquals(0, actual.getInt("imageTokenReserve"));
    }
    private void assertStoredChoice(String scope, String account, String model) throws Exception {
        assertChoice(reopen().snapshot(scope).getJSONObject("target"), account, model);
    }
    private static void assertInherited(JSONObject snapshot) throws Exception {
        assertTrue(snapshot.getBoolean("inherited"));
        assertTrue(snapshot.isNull("target"));
    }

    @Test public void firstDraftCanSelectAndSubmitWithoutAnyGlobalOrConversationDefault() throws Exception {
        String source = draft("first-draft");
        assertInherited(preferences.snapshot(source));
        assertEquals(0, preferences.snapshot(source).getLong("revision"));
        assertNull(preferences.targetForAdmission("", ""));

        preferences.save(source, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("first-input", source);
        assertChoice(preferences.targetForAdmission("", "first-input"), "account-a", "model-m");
        preferences.bindAllocatedConversation("first-input", "conversation-a");
        preferences.confirmSubmission("first-input", "conversation-a");

        assertStoredChoice(conversation("conversation-a"), "account-a", "model-m");
        assertInherited(reopen().snapshot(source));
        assertInherited(reopen().snapshot(draft("another-new-draft")));
    }

    @Test public void conversationsAndAnExistingSubmissionKeepIndependentChoices() throws Exception {
        String a = conversation("a"), b = conversation("b");
        preferences.save(a, 0, choice("account-a", "model-m"));
        preferences.save(b, 0, choice("account-b", "model-n"));
        preferences.pinSubmission("input-a", a);

        preferences.save(b, 1, choice("account-b", "model-q"));
        preferences.save(a, 1, null);
        assertChoice(preferences.targetForAdmission("a", "input-a"), "account-a", "model-m");
        assertNull(preferences.targetForAdmission("a", "next-input-a"));
        assertChoice(preferences.targetForAdmission("b", "next-input-b"), "account-b", "model-q");
        assertInherited(reopen().snapshot(a));
        assertStoredChoice(b, "account-b", "model-q");
    }

    @Test public void lateAcknowledgementMovesEditedDraftChoiceWithoutChangingSubmittedModel() throws Exception {
        String source = draft("draft-a"), destination = conversation("a");
        preferences.save(source, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("input-a", source);
        preferences.bindAllocatedConversation("input-a", "a");
        preferences.save(source, 1, choice("account-b", "model-n"));

        // A receipt retry must retain the submitted M, even though the editor now shows N.
        preferences.pinSubmission("input-a", source);
        assertChoice(preferences.targetForAdmission("a", "input-a"), "account-a", "model-m");
        assertStoredChoice(destination, "account-a", "model-m");

        preferences.confirmSubmission("input-a", "a");
        assertStoredChoice(destination, "account-b", "model-n");
        assertInherited(reopen().snapshot(source));
        assertChoice(preferences.targetForAdmission("a", "next-input"), "account-b", "model-n");
        long confirmedRevision = preferences.snapshot(destination).getLong("revision");
        preferences.confirmSubmission("input-a", "a");
        assertEquals("Duplicate receipts must be idempotent", confirmedRevision,
                reopen().snapshot(destination).getLong("revision"));
    }

    @Test public void returningDraftToInheritedAfterSendingDoesNotRewriteTheInFlightChoice() throws Exception {
        String source = draft("draft-a"), destination = conversation("a");
        preferences.save(source, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("input-a", source);
        preferences.bindAllocatedConversation("input-a", "a");
        preferences.save(source, 1, null);

        assertChoice(preferences.targetForAdmission("a", "input-a"), "account-a", "model-m");
        preferences.confirmSubmission("input-a", "a");
        assertInherited(reopen().snapshot(destination));
        assertNull(preferences.targetForAdmission("a", "next-input"));
    }

    @Test public void lateDraftReceiptCannotOverwriteAnIndependentlyEditedDestination() throws Exception {
        String source = draft("draft-a"), destination = conversation("a");
        preferences.save(source, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("input-a", source);
        preferences.bindAllocatedConversation("input-a", "a");
        long destinationRevision = preferences.snapshot(destination).getLong("revision");
        preferences.save(destination, destinationRevision, choice("account-c", "model-q"));
        preferences.save(source, 1, choice("account-b", "model-n"));

        assertChoice(preferences.targetForAdmission("a", "input-a"), "account-a", "model-m");
        preferences.confirmSubmission("input-a", "a");
        assertStoredChoice(destination, "account-c", "model-q");
        assertStoredChoice(source, "account-b", "model-n");
        assertChoice(preferences.targetForAdmission("a", "next-input"), "account-c", "model-q");
    }

    @Test public void restartingPreservesAnUnconfirmedPinAndTheLatestDraftUntilItsReceipt() throws Exception {
        String source = draft("draft-a"), destination = conversation("a");
        preferences.save(source, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("input-a", source);
        preferences.bindAllocatedConversation("input-a", "a");
        preferences.save(source, 1, choice("account-b", "model-n"));

        ConversationModelPreferences restarted = reopen();
        assertChoice(restarted.targetForAdmission("a", "input-a"), "account-a", "model-m");
        assertChoice(restarted.snapshot(source).getJSONObject("target"), "account-b", "model-n");
        restarted.pinSubmission("input-a", source);
        assertChoice(restarted.targetForAdmission("a", "input-a"), "account-a", "model-m");
        restarted.confirmSubmission("input-a", "a");

        assertStoredChoice(destination, "account-b", "model-n");
        assertInherited(reopen().snapshot(source));
    }

    @Test public void definiteRejectionsReleaseCapacityWhileUnknownReceiptsKeepTheirOriginalPin() throws Exception {
        String scope = conversation("a");
        preferences.save(scope, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("still-unconfirmed", scope);
        preferences.save(scope, 1, choice("account-b", "model-n"));

        // More than the pending limit over one session; each definite rejection releases its slot.
        for (int i = 0; i < 257; i++) {
            String client = "rejected-input-" + i;
            preferences.pinSubmission(client, scope);
            preferences.rejectSubmission(client, "a");
        }
        preferences.save(scope, 2, choice("account-c", "model-q"));
        ConversationModelPreferences restarted = reopen();
        assertChoice(restarted.targetForAdmission("a", "still-unconfirmed"), "account-a", "model-m");
        assertChoice(restarted.targetForAdmission("a", "rejected-input-256"), "account-c", "model-q");
        restarted.pinSubmission("fresh-input", scope);
        assertChoice(restarted.targetForAdmission("a", "fresh-input"), "account-c", "model-q");
    }

    @Test public void theSamePendingIdentityCannotMoveToOrBeAcknowledgedForAnotherConversation() throws Exception {
        String a = conversation("a"), b = conversation("b");
        preferences.save(a, 0, choice("account-a", "model-m"));
        preferences.save(b, 0, choice("account-b", "model-n"));
        preferences.pinSubmission("input-a", a);

        assertThrows(SecurityException.class, () -> preferences.pinSubmission("input-a", b));
        assertThrows(SecurityException.class, () -> preferences.pinSubmission("input-a", draft("another-draft")));
        assertThrows(SecurityException.class, () -> preferences.targetForAdmission("b", "input-a"));
        assertThrows(SecurityException.class, () -> preferences.bindAllocatedConversation("input-a", "b"));
        assertThrows(SecurityException.class, () -> preferences.confirmSubmission("input-a", "b"));
        assertThrows(SecurityException.class, () -> preferences.rejectSubmission("input-a", "b"));

        assertChoice(reopen().targetForAdmission("a", "input-a"), "account-a", "model-m");
        assertStoredChoice(a, "account-a", "model-m");
        assertStoredChoice(b, "account-b", "model-n");
    }

    @Test public void staleEditorsCannotOverwriteAConfirmedChoiceOrAnExplicitReturnToDefault() throws Exception {
        String scope = conversation("a");
        preferences.save(scope, 0, choice("account-a", "model-m"));
        long reviewed = preferences.snapshot(scope).getLong("revision");
        ConversationModelPreferences otherEditor = reopen();
        otherEditor.save(scope, reviewed, choice("account-b", "model-n"));

        assertThrows(IllegalStateException.class,
                () -> preferences.save(scope, reviewed, choice("account-c", "model-q")));
        assertStoredChoice(scope, "account-b", "model-n");
        long changed = otherEditor.snapshot(scope).getLong("revision");
        otherEditor.save(scope, changed, null);
        assertThrows(IllegalStateException.class,
                () -> preferences.save(scope, changed, choice("account-c", "model-q")));
        JSONObject inherited = reopen().snapshot(scope);
        assertInherited(inherited);
        assertTrue("Returning to defaults must retain a CAS revision", inherited.getLong("revision") > changed);
    }

    @Test public void secretFieldsAreRejectedAtEveryStoredSelectionBoundary() throws Exception {
        String scope = conversation("a");
        preferences.save(scope, 0, choice("account-a", "model-m"));
        byte[] before = Files.readAllBytes(file);
        for (String key : List.of("apiKey", "accessToken", "refreshToken")) {
            JSONObject topLevel = choice("account-b", "model-n").put(key, "fixture-secret-must-not-persist");
            assertThrows(IllegalArgumentException.class, () -> preferences.save(scope, 1, topLevel));
            JSONObject nativeReference = choice("account-b", "model-n");
            nativeReference.getJSONObject("nativeRef").put(key, "fixture-secret-must-not-persist");
            assertThrows(IllegalArgumentException.class, () -> preferences.save(scope, 1, nativeReference));
            JSONObject reasoning = choice("account-b", "model-n");
            reasoning.getJSONObject("reasoning").put(key, "fixture-secret-must-not-persist");
            assertThrows(IllegalArgumentException.class, () -> preferences.save(scope, 1, reasoning));
        }
        assertArrayEquals("Rejected payloads must not modify the prior private document", before, Files.readAllBytes(file));
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("fixture-secret-must-not-persist"));
        assertStoredChoice(scope, "account-a", "model-m");
    }

    @Test public void damagedPrivateDocumentsFailClosedAndAreNeverReplacedWithEmptyDefaults() throws Exception {
        String scope = conversation("a");
        preferences.save(scope, 0, choice("account-a", "model-m"));
        preferences.pinSubmission("input-a", scope);
        String valid = Files.readString(file, StandardCharsets.UTF_8);
        List<String> damaged = new ArrayList<>();
        damaged.add("{\"format\":");
        damaged.add(valid + " trailing-data");
        damaged.add(new JSONObject(valid).put("format", "unknown-format").toString());
        JSONObject invalidRevision = new JSONObject(valid);
        invalidRevision.getJSONObject("scopes").getJSONObject(scope).put("revision", 1.5);
        damaged.add(invalidRevision.toString());
        JSONObject invalidPending = new JSONObject(valid);
        invalidPending.getJSONObject("pending").getJSONObject("input-a").getJSONObject("target")
                .getJSONObject("nativeRef").put("accountVersion", 0);
        damaged.add(invalidPending.toString());
        JSONObject forbiddenField = new JSONObject(valid);
        forbiddenField.getJSONObject("scopes").getJSONObject(scope).getJSONObject("target")
                .getJSONObject("reasoning").put("apiKey", "fixture-secret-must-not-persist");
        damaged.add(forbiddenField.toString());

        for (String broken : damaged) {
            Files.writeString(file, broken, StandardCharsets.UTF_8);
            ConversationModelPreferences restarted = reopen();
            assertThrows(Exception.class, () -> restarted.snapshot(scope));
            assertThrows(Exception.class, () -> restarted.targetForAdmission("a", "input-a"));
            assertThrows(Exception.class, () -> preferences.targetForAdmission("a", "input-a"));
            assertThrows(Exception.class, () -> restarted.save(scope, 0, choice("account-b", "model-n")));
            assertThrows(Exception.class, () -> restarted.pinSubmission("new-input", scope));
            assertThrows(Exception.class, () -> restarted.rejectSubmission("input-a", "a"));
            assertEquals("A failed read or write must preserve the damaged evidence", broken,
                    Files.readString(file, StandardCharsets.UTF_8));
        }
    }
}
