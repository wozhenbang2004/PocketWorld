package dev.pocketengine.ui

import dev.pocketengine.features.remote.RemoteHarnessSelection
import org.json.JSONObject

internal fun configuredTeamHarness(id: String): Boolean = RemoteHarnessSelection.isGenericWorker(id)

internal fun externalTeamHarness(id: String): Boolean = RemoteHarnessSelection.isTeamWorker(id)

internal fun nativeHarnessProbeReady(probe: JSONObject?, worker: JSONObject?): Boolean = probe != null && worker != null &&
    worker.optString("configurationRevision") == probe.optString("configurationRevision") &&
    runCatching { RemoteHarnessSelection.fromProbe(probe, worker.optString("id")); true }.getOrDefault(false)
