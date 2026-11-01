package dev.pocketengine.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native JSON fixtures only: these tests do not claim OAuth, UI rendering or atomic gateway admission. */
public class AccountConnectionsStateTest {
    private static final long NOW = 100_000L;
    private JSONObject row(long version) throws Exception {
        return new JSONObject().put("id", "account-a").put("provider", "chatgpt_plan").put("version", version)
            .put("label", "个人账号").put("email", "user@example.invalid").put("enabled", true)
            .put("status", "connected").put("planUsageEnabled", true).put("cooldownUntil", 0);
    }
    private JSONObject snapshot(long version) throws Exception {
        return new JSONObject().put("accounts", new JSONArray().put(row(version))).put("selectedAccountId", "account-a");
    }
    private AccountConnectionsRequest request(String command, long version) {
        return new AccountConnectionsRequest("request-new", command, "account-a", version, "session-new");
    }
    private JSONObject envelope(String command, long expected, JSONObject result) throws Exception {
        return new JSONObject().put("requestId", "request-new").put("ok", true)
            .put("accountId", "account-a").put("expectedVersion", expected).put("result", result);
    }
    private JSONObject catalog(long version) throws Exception {
        JSONObject ref = new JSONObject().put("provider", "chatgpt_plan").put("accountId", "account-a")
            .put("accountVersion", version).put("modelId", "model-a");
        return new JSONObject().put("accountId", "account-a").put("accountVersion", version)
            .put("models", new JSONArray().put(new JSONObject().put("id", "model-a").put("displayName", "模型 A").put("nativeRef", ref)));
    }
    private JSONObject modelsEnvelope(long expected, long actual) throws Exception { return envelope("models", expected, catalog(actual)); }
    private JSONObject firstRow(JSONObject snapshot) throws Exception { return snapshot.getJSONArray("accounts").getJSONObject(0); }

    @Test public void requestsCarryReviewedVersionAndOnlyAllowedPayloadFields() throws Exception {
        JSONObject source = row(7).put("accessToken", "must-not-copy").put("authorizationUrl", "must-not-copy");
        JSONObject payload = AccountConnectionsPageKt.accountConnectionsPayload("setEnabled", source, false, "ignored");
        assertEquals(3, payload.length());
        assertEquals("account-a", payload.getString("accountId"));
        assertEquals(7, payload.getLong("expectedVersion"));
        assertFalse(payload.getBoolean("enabled"));
        assertFalse(payload.has("accessToken"));
        assertFalse(payload.has("authorizationUrl"));
        JSONObject cancel = AccountConnectionsPageKt.accountConnectionsPayload("cancelLogin", source, false, "session-new");
        assertEquals("session-new", cancel.getString("sessionId"));
        assertEquals(3, cancel.length());
    }

    @Test public void exactRequestAndReviewedVersionRequiredForSuccessAndDenial() throws Exception {
        AccountConnectionsRequest request = request("models", 7);
        JSONObject reply = modelsEnvelope(7, 7);
        assertNotNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, reply));
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, new JSONObject(reply.toString()).put("requestId", "old")));
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, new JSONObject(reply.toString()).put("accountId", "other")));
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, new JSONObject(reply.toString()).put("expectedVersion", 8)));
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, new JSONObject(reply.toString()).put("expectedVersion", 7.5)));
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, new JSONObject(reply.toString()).put("ok", "true")));
        JSONObject denial = new JSONObject(reply.toString()).put("ok", false).put("error", "PLAN_USAGE_PERMISSION_REQUIRED");
        assertNotNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, denial));
        denial.remove("expectedVersion");
        assertNull(AccountConnectionsPageKt.accountConnectionsEnvelope(request, denial));
    }

    @Test public void catalogMayUseRealRefreshVersionButNotLaterSnapshotVersion() throws Exception {
        AccountConnectionsRequest req = request("models", 7);
        assertNotNull(AccountConnectionsPageKt.accountConnectionsCatalog(req, modelsEnvelope(7, 9), snapshot(9), NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsCatalog(req, modelsEnvelope(7, 9), snapshot(10), NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsCatalog(req, modelsEnvelope(7, 6), snapshot(6), NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsCatalog(req, modelsEnvelope(8, 9), snapshot(9), NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsCatalog(request("refresh", 7), modelsEnvelope(7, 7), snapshot(7), NOW));
    }

    @Test public void missingOrStaleNativeReferenceNeverBecomesSelectable() throws Exception {
        AccountConnectionsRequest req = request("models", 7);
        JSONObject state = snapshot(7);
        JSONObject reply = modelsEnvelope(7, 7);
        assertNotNull(AccountConnectionsPageKt.accountConnectionsModelReference(req, reply, state, "model-a", NOW));
        JSONObject model = reply.getJSONObject("result").getJSONArray("models").getJSONObject(0);
        model.remove("nativeRef");
        assertNull(AccountConnectionsPageKt.accountConnectionsModelReference(req, reply, state, "model-a", NOW));
        for (String key : new String[]{"provider", "accountId", "modelId", "accountVersion"}) {
            reply = modelsEnvelope(7, 7);
            JSONObject ref = reply.getJSONObject("result").getJSONArray("models").getJSONObject(0).getJSONObject("nativeRef");
            ref.put(key, key.equals("accountVersion") ? 8 : "other");
            assertNull(key, AccountConnectionsPageKt.accountConnectionsModelReference(req, reply, state, "model-a", NOW));
        }
        reply = modelsEnvelope(7, 7);
        reply.getJSONObject("result").getJSONArray("models").getJSONObject(0).getJSONObject("nativeRef").put("accountVersion", 7.5);
        assertNull(AccountConnectionsPageKt.accountConnectionsModelReference(req, reply, state, "model-a", NOW));
    }

    @Test public void modelReferenceIsDetachedWhitelistedAndCannotCopySecretExtras() throws Exception {
        JSONObject state = snapshot(7), reply = modelsEnvelope(7, 7);
        JSONObject source = reply.getJSONObject("result").getJSONArray("models").getJSONObject(0).getJSONObject("nativeRef");
        source.put("accessToken", "private").put("authorizationUrl", "private");
        JSONObject ref = AccountConnectionsPageKt.accountConnectionsModelReference(request("models", 7), reply, state, "model-a", NOW);
        assertNotNull(ref);
        assertEquals(4, ref.length());
        assertFalse(ref.has("accessToken"));
        assertFalse(ref.has("authorizationUrl"));
        source.put("modelId", "changed");
        assertEquals("model-a", ref.getString("modelId"));
    }

    @Test public void duplicateModelsAreNotSelectable() throws Exception {
        JSONObject reply = modelsEnvelope(7, 7);
        JSONArray models = reply.getJSONObject("result").getJSONArray("models");
        models.put(new JSONObject(models.getJSONObject(0).toString()));
        assertNull(AccountConnectionsPageKt.accountConnectionsModelReference(request("models", 7), reply, snapshot(7), "model-a", NOW));
    }

    @Test public void disabledScopeDeniedCoolingAndDisconnectedAccountsCannotSelectModels() throws Exception {
        for (String field : new String[]{"enabled", "planUsageEnabled", "cooldownUntil", "status"}) {
            JSONObject state = snapshot(7);
            Object value = field.equals("cooldownUntil") ? NOW + 10 : field.equals("status") ? "signed_out" : false;
            firstRow(state).put(field, value);
            assertNull(field, AccountConnectionsPageKt.accountConnectionsModelReference(request("models", 7), modelsEnvelope(7, 7), state, "model-a", NOW));
        }
        assertNull(AccountConnectionsPageKt.accountConnectionsModelReference(request("models", 7), modelsEnvelope(7, 7), snapshot(7).put("closed", true), "model-a", NOW));
        JSONObject cooling = row(7).put("status", "cooldown").put("cooldownUntil", NOW);
        assertTrue(AccountConnectionsPageKt.accountConnectionsCanUse(cooling, NOW));
    }

    private JSONObject authorizing() throws Exception {
        JSONObject state = snapshot(7);
        firstRow(state).put("status", "authorizing");
        state.put("login", new JSONObject().put("sessionId", "session-new").put("accountId", "account-a")
            .put("status", "pending").put("expiresAt", NOW + 100));
        return state;
    }

    @Test public void browserOpenerReceivesOnlyLiveSessionIdentity() throws Exception {
        JSONObject state = authorizing();
        state.getJSONObject("login").put("authorizationUrl", "must-not-return");
        assertEquals("session-new", AccountConnectionsPageKt.accountConnectionsAuthorization(state, NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsAuthorization(state, NOW + 100));
        firstRow(state).put("enabled", false);
        assertNull(AccountConnectionsPageKt.accountConnectionsAuthorization(state, NOW));
        state = authorizing();
        state.getJSONObject("login").put("accountId", "another-account");
        assertNull(AccountConnectionsPageKt.accountConnectionsAuthorization(state, NOW));
        state = authorizing();
        state.getJSONObject("login").put("status", "exchanging");
        assertNull(AccountConnectionsPageKt.accountConnectionsAuthorization(state, NOW));
    }

    @Test public void cancelledAuthorizationReceiptCannotReplaceNewSessionOrCompletion() throws Exception {
        JSONObject state = authorizing();
        state.getJSONObject("login").put("status", "cancelled");
        JSONObject result = new JSONObject(state.getJSONObject("login").toString());
        JSONObject reply = envelope("cancelLogin", 7, result);
        assertTrue(AccountConnectionsPageKt.accountConnectionsReceipt(request("cancelLogin", 7), reply, state).contains("已取消"));
        state.getJSONObject("login").put("sessionId", "newer-session");
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("cancelLogin", 7), reply, state));
        state.getJSONObject("login").put("sessionId", "session-new").put("status", "complete");
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("cancelLogin", 7), reply, state));
    }

    @Test public void logoutConfirmationIsBoundToReviewedAccountVersion() throws Exception {
        AccountConnectionsLogout draft = new AccountConnectionsLogout("account-a", 7, "个人账号");
        assertEquals(7, AccountConnectionsPageKt.accountConnectionsLogoutPayload(draft, snapshot(7)).getLong("expectedVersion"));
        try { AccountConnectionsPageKt.accountConnectionsLogoutPayload(draft, snapshot(8)); fail("stale confirmation"); }
        catch (IllegalArgumentException expected) { }
        try { AccountConnectionsPageKt.accountConnectionsLogoutPayload(draft, new JSONObject().put("accounts", new JSONArray())); fail("removed account"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void logoutDistinguishesLocalCleanupAndUnconfirmedRemoteRevocation() throws Exception {
        JSONObject state = snapshot(9);
        firstRow(state).put("status", "signed_out");
        JSONObject result = new JSONObject().put("accountId", "account-a").put("signedOutLocally", true)
            .put("credentialCleanupRequired", true).put("remoteRevocationConfirmed", false).put("accountVersion", 9);
        String receipt = AccountConnectionsPageKt.accountConnectionsReceipt(request("logout", 7), envelope("logout", 7, result), state);
        assertTrue(receipt.contains("已在本机退出"));
        assertTrue(receipt.contains("清理尚未完成"));
        assertTrue(receipt.contains("远端撤销尚未确认"));
        result.put("accountVersion", 8);
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("logout", 7), envelope("logout", 7, result), state));
        result.put("accountVersion", 9);
        firstRow(state).put("status", "connected");
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("logout", 7), envelope("logout", 7, result), state));
    }

    @Test public void staleRefreshOrSelectionReceiptCannotDescribeCurrentState() throws Exception {
        JSONObject result = snapshot(8), current = snapshot(9);
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("refresh", 7), envelope("refresh", 7, result), current));
        result = snapshot(7);
        current = snapshot(7).put("selectedAccountId", "other");
        assertNull(AccountConnectionsPageKt.accountConnectionsReceipt(request("select", 7), envelope("select", 7, result), current));
    }

    @Test public void errorsAreBoundedSafeCodesAndOldVersionDoesNotShowCurrentDenial() throws Exception {
        String secret = "https://private.example/?token=private";
        assertFalse(AccountConnectionsPageKt.accountConnectionsFailure(secret).contains("private"));
        JSONObject reply = envelope("models", 7, new JSONObject()).put("ok", false).put("error", "PLAN_USAGE_PERMISSION_REQUIRED");
        assertTrue(AccountConnectionsPageKt.accountConnectionsReceipt(request("models", 7), reply, snapshot(7)).contains("尚未授权"));
        assertTrue(AccountConnectionsPageKt.accountConnectionsReceipt(request("models", 7), reply, snapshot(8)).contains("旧请求"));
    }

    @Test public void readingResponsesDoesNotMutateNativeSnapshotOrEnvelope() throws Exception {
        JSONObject state = snapshot(7), reply = modelsEnvelope(7, 7);
        String beforeState = state.toString(), beforeReply = reply.toString();
        AccountConnectionsPageKt.accountConnectionsCatalog(request("models", 7), reply, state, NOW);
        AccountConnectionsPageKt.accountConnectionsModelReference(request("models", 7), reply, state, "model-a", NOW);
        AccountConnectionsPageKt.accountConnectionsReceipt(request("models", 7), reply, state);
        assertEquals(beforeState, state.toString());
        assertEquals(beforeReply, reply.toString());
    }

    @Test public void malformedVersionsDoNotTurnIntoValidZeroVersion() throws Exception {
        assertEquals(-1, AccountConnectionsPageKt.accountConnectionsVersion(new JSONObject()));
        assertEquals(-1, AccountConnectionsPageKt.accountConnectionsVersion(row(7).put("version", "7")));
        assertEquals(-1, AccountConnectionsPageKt.accountConnectionsVersion(row(7).put("version", 7.2)));
        assertEquals(-1, AccountConnectionsPageKt.accountConnectionsVersion(row(-1)));
    }
    @Test public void browserStartsOnlyFromTheVisibleCurrentLoginResponseAndDoesNotReplayAfterRecreation()throws Exception {
        JSONObject state=authorizing();JSONObject result=new JSONObject(state.getJSONObject("login").toString());JSONObject reply=envelope("beginLogin",7,result);
        assertEquals("session-new",AccountConnectionsPageKt.accountConnectionsBrowserRequest(request("beginLogin",7),reply,state,NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsBrowserRequest(null,reply,state,NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsBrowserRequest(request("models",7),reply,state,NOW));
        assertNull(AccountConnectionsPageKt.accountConnectionsBrowserRequest(request("beginLogin",7),new JSONObject(reply.toString()).put("requestId","old"),state,NOW));
        state.getJSONObject("login").put("sessionId","replacement");assertNull(AccountConnectionsPageKt.accountConnectionsBrowserRequest(request("beginLogin",7),reply,state,NOW));
    }
    @Test public void aPlanUsageHoldBlocksModelsWithoutPretendingThatTheLoginWasRevoked()throws Exception {
        JSONObject state=snapshot(7);firstRow(state).put("usageLimited",true);
        assertFalse(AccountConnectionsPageKt.accountConnectionsCanUse(firstRow(state),NOW));assertEquals("connected",firstRow(state).getString("status"));
        assertNull(AccountConnectionsPageKt.accountConnectionsModelReference(request("models",7),modelsEnvelope(7,7),state,"model-a",NOW));
        JSONObject retry=AccountConnectionsPageKt.accountConnectionsPayload("retryPlanUsage",firstRow(state),false,"");assertEquals(2,retry.length());assertEquals(7,retry.getLong("expectedVersion"));
        assertTrue(AccountConnectionsPageKt.accountConnectionsFailure("CHATGPT_USAGE_LIMIT_REACHED").contains("Manage usage"));
    }

}
