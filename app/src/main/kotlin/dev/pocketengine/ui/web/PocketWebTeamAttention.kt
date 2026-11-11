package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.teams.TeamsModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Private owner navigation projection; never a model capability or execution grant. */
internal fun PocketWorldController.readWebTeamAttention(afterRunId: String, afterItemId: String, limit: Int, done: (JSONObject?, String?) -> Unit) {
    check(webPrivateOwnerVisible()) { "请在前台私人工作区读取团队待处理" }
    val window = webWindowTicket()
    val main = Handler(Looper.getMainLooper())
    runTeamPhoneWork {
        val result = runCatching {
            check(webPrivateOwnerVisible() && webWindowTicket() == window) { "原工作页面已关闭" }
            (webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主尚未接入"))
                .ownerAttention(afterRunId, afterItemId, limit)
        }
        main.post {
            if (!webPrivateOwnerVisible() || webWindowTicket() != window) done(null, "原工作页面已变化，请重新读取")
            else result.fold({ done(it, null) }, { done(null, it.message ?: "团队待处理尚未读取") })
        }
    }
}
