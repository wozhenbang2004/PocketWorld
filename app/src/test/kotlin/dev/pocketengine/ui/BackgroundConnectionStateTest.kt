package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionStateTest {
    private fun display() = JSONObject().put("supportedAndroid", true).put("authorizationBackend", "wireless_adb").put("adb", JSONObject().put("phase", "idle").put("busy", false))

    @Test fun protocolAndPairingAcknowledgementsNeverBecomeHelperReadiness() {
        val snapshot = display()
        for (phase in listOf("paired", "ready", "helper_started")) {
            snapshot.getJSONObject("adb").put("phase", phase).put("helperAuthenticated", true).put("identityPresent", true)
            val state = backgroundConnectionState(snapshot, null)
            assertFalse(state.helperConnected)
            assertFalse(state.screenEstablished)
            assertNotEquals("后台助手已连接", backgroundConnectionHeadline(state))
        }
    }
    @Test fun helperAndActualDisplayHaveSeparateSuccessStates() {
        val snapshot = display().put("helperConnected", true)
        assertEquals("后台助手已连接", backgroundConnectionHeadline(backgroundConnectionState(snapshot, null)))
        snapshot.put("sessionActive", true).put("readyForTask", true)
        assertFalse(backgroundConnectionState(snapshot, null).screenEstablished)
        snapshot.put("displayId", 8).put("sessionId", "real-session")
        assertTrue(backgroundConnectionState(snapshot, null).screenEstablished)
        snapshot.put("readyForTask", false)
        assertFalse(backgroundConnectionState(snapshot, null).screenEstablished)
    }
    @Test fun unactivatedOrUnsupportedBuildDoesNotExposeWirelessActions() {
        val absent = backgroundConnectionState(JSONObject().put("supportedAndroid", true), null)
        assertFalse(absent.wirelessAvailable); assertFalse(absent.canPair); assertFalse(absent.canConnect)
        assertFalse(backgroundConnectionState(display().put("supportedAndroid", false), null).canPair)
    }
    @Test fun activeTaskAndTerminationBarrierDisableAllMutatingSetup() {
        val snapshot = display()
        val owner = backgroundConnectionState(snapshot, "original-task")
        assertFalse(owner.canPair); assertFalse(owner.canConnect); assertFalse(owner.canForget); assertFalse(owner.canChangeBackend)
        snapshot.getJSONObject("adb").put("processTerminationPending", true)
        val terminating = backgroundConnectionState(snapshot, null)
        assertTrue(terminating.busy); assertFalse(terminating.canPair); assertFalse(terminating.canConnect); assertFalse(terminating.canForget)
        val pending = backgroundConnectionState(display(), null, setOf("display.adb.pair"))
        assertTrue(pending.busy); assertFalse(pending.canConnect)
    }
    @Test fun unknownPairingCanOnlyBeQueriedOrExplicitlyForgotten() {
        val snapshot = display()
        snapshot.getJSONObject("adb").put("recoveryRequired", true).put("phase", "pair_outcome_unknown")
        val unknown = backgroundConnectionState(snapshot, null)
        assertFalse(unknown.canPair); assertFalse(unknown.canChangeBackend); assertTrue(unknown.canConnect); assertTrue(unknown.canForget)
        snapshot.put("sessionActive", true)
        assertFalse(backgroundConnectionState(snapshot, null).canForget)
        assertTrue(backgroundConnectionState(snapshot, null).canConnect)
        snapshot.put("authorizationBackend", "shizuku")
        assertFalse(backgroundConnectionState(snapshot, null).canConnect)
    }
    @Test fun connectedSocketWithPendingReleaseStillRequiresOriginalConnectionCheck() {
        val snapshot = display().put("helperConnected", true).put("releasePending", true)
        val state = backgroundConnectionState(snapshot, null)
        assertTrue(state.requiresConnectionCheck); assertTrue(state.canConnect); assertFalse(state.canPair)
        assertEquals("上次连接结果待核对", backgroundConnectionHeadline(state))
        snapshot.put("releasePending", false).put("recoveryRequired", true)
        assertTrue(backgroundConnectionState(snapshot, null).requiresConnectionCheck)
    }
    @Test fun pendingReconnectDoesNotAnnounceThePreviousSocketAsNewSuccess() {
        val state = backgroundConnectionState(display().put("helperConnected", true), null, setOf("display.adb.connect"))
        assertEquals("正在处理本次连接", backgroundConnectionHeadline(state))
    }
    @Test fun confirmedAuthorizationRejectionReturnsToPairingWhileUnknownNeverDoes() {
        val snapshot = display()
        snapshot.getJSONObject("adb").put("phase", "needs_pairing")
        assertFalse(wirelessConnectStep(true, backgroundConnectionState(snapshot, null)))
        snapshot.getJSONObject("adb").put("recoveryRequired", true)
        assertTrue(wirelessConnectStep(false, backgroundConnectionState(snapshot, null)))
        snapshot.getJSONObject("adb").put("recoveryRequired", false).put("phase", "identity_forgotten")
        assertFalse(wirelessConnectStep(true, backgroundConnectionState(snapshot, null)))
    }
    @Test fun discoveriesKeepPairAndConnectSeparateAndRejectStalePorts() {
        val now = 1_000_000L
        fun endpoint(port: Int, kind: String, at: Long) = JSONObject().put("host", "127.0.0.1").put("port", port).put("kind", kind).put("resolvedAt", at)
        val discovery = JSONObject().put("pairing", JSONArray().put(endpoint(37111, "pairing", now)).put(endpoint(37111, "pairing", now)).put(endpoint(37112, "connect", now)).put(endpoint(37113, "pairing", now - 180_001)).put(endpoint(0, "pairing", now)))
            .put("connect", JSONArray().put(endpoint(37112, "connect", now)))
        assertEquals(listOf(37111), wirelessEndpoints(discovery, "pairing", now).map { it.port })
        assertEquals(listOf(37112), wirelessEndpoints(discovery, "connect", now).map { it.port })
        assertTrue(wirelessEndpoints(discovery, "pairing", now + 180_001).isEmpty())
    }
    @Test fun pendingMetadataDropsThePairingCodeWithoutChangingTransientPayload() {
        val payload = JSONObject().put("host", "127.0.0.1").put("pairingPort", 37111).put("pairingCode", "123456")
        val metadata = displayPendingMetadata("adb.pair", payload)
        assertFalse(metadata.has("pairingCode"))
        assertEquals("123456", payload.getString("pairingCode"))
        assertEquals(37111, metadata.getInt("pairingPort"))
    }
    @Test fun currentCodeAndPortInputsHaveStrictBoundaries() {
        assertTrue(wirelessPairCodeValid("123456")); assertFalse(wirelessPairCodeValid("12345")); assertFalse(wirelessPairCodeValid("１２３４５６"))
        assertEquals(1, wirelessPort("1")); assertEquals(65535, wirelessPort("65535")); assertNull(wirelessPort("65536")); assertNull(wirelessPort("-1")); assertNull(wirelessPort("abc"))
    }
    @Test fun openingSetupPreservesTheSavedBackendAndFreshInstallUsesBuiltInGuide() {
        assertEquals("wireless_adb", backgroundSetupMode(JSONObject()))
        assertEquals("shizuku", backgroundSetupMode(display().put("authorizationBackend", "shizuku")))
        assertEquals("adb", backgroundSetupMode(display().put("authorizationBackend", "adb")))
    }
    @Test fun savedWirelessIdentityReconnectsInsteadOfAskingToPairAgain() {
        val snapshot = display()
        snapshot.getJSONObject("adb").put("identityPresent", true)
        assertTrue(wirelessConnectStep(false, backgroundConnectionState(snapshot, null)))
        snapshot.getJSONObject("adb").put("phase", "needs_pairing")
        assertFalse(wirelessConnectStep(true, backgroundConnectionState(snapshot, null)))
    }

    @Test fun visibleStepsUseActualMissingPermissionsAndNeverClaimTaskStarted() {
        val connection = backgroundConnectionState(display(), null)
        val both = backgroundSetupSteps(connection, false, false)
        assertFalse(both[0].complete); assertTrue(both[0].detail.contains("界面读取")); assertTrue(both[0].detail.contains("任务通知"))
        assertTrue(backgroundSetupSteps(connection, true, false)[0].detail.contains("任务通知"))
        assertTrue(backgroundSetupSteps(connection, false, true)[0].detail.contains("界面读取"))
        assertTrue(backgroundSetupSteps(connection, true, true)[0].complete)
        assertFalse(both.last().complete)
    }
    @Test fun visibleHelperSuccessIsNeitherEstablishedDisplayNorStartedTask() {
        val snapshot = display().put("helperConnected", true)
        val steps = backgroundSetupSteps(backgroundConnectionState(snapshot, null), true, true)
        assertTrue(steps[2].complete); assertTrue(steps[2].detail.contains("不表示已开始任务")); assertTrue(steps.last().detail.contains("另行建立")); assertFalse(steps.last().complete)
        snapshot.put("sessionActive", true).put("readyForTask", true).put("displayId", 8).put("sessionId", "native")
        assertTrue(backgroundSetupSteps(backgroundConnectionState(snapshot, "original"), true, true).last().detail.contains("原任务持有资源"))
    }
    @Test fun unknownConnectionDoesNotShowCompleteFromCachedHelperOrIdentity() {
        val snapshot = display().put("helperConnected", true).put("releasePending", true)
        snapshot.getJSONObject("adb").put("identityPresent", true)
        val steps = backgroundSetupSteps(backgroundConnectionState(snapshot, null), true, true)
        assertFalse(steps[1].complete); assertFalse(steps[2].complete); assertTrue(steps[1].attention); assertTrue(steps[2].detail.contains("结果待核对"))
    }
    @Test fun savedAlternateBackendIsNotPresentedAsNeedingWirelessPairing() {
        for (backend in listOf("shizuku", "adb")) {
            val steps = backgroundSetupSteps(backgroundConnectionState(display().put("authorizationBackend", backend), null), true, true)
            assertEquals("已选连接授权", steps[1].title); assertTrue(steps[1].detail.contains("不要求无线配对")); assertTrue(steps[2].detail.contains("不更换后端"))
        }
        assertFalse(backgroundSetupSteps(backgroundConnectionState(display().put("supportedAndroid", false), null), true, true)[0].complete)
    }
}
