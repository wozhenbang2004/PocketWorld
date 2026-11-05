package dev.pocketengine.ui

import dev.pocketengine.features.plugins.catalog.PluginCatalogSession
import org.json.JSONObject

internal object PluginCatalogPageGuard {
    @JvmStatic fun canConfirm(view: PluginCatalogSession.View, checked: Boolean): Boolean {
        val data = view.data()
        return checked && view.approval != null && !data.optBoolean("busy") && !data.optBoolean("unresolved") && data.optString("status") == "ready"
    }
    @JvmStatic fun sameRequest(page: String, revision: Long, data: JSONObject): Boolean =
        page == data.optString("pageRequestId") && revision == data.optLong("revision", -1)
}

/** Root supplies its existing worker and journal callback. No model feature route, file picker, runner or executor is created here. */
