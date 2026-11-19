package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.ProjectRuntimeHealth
import org.json.JSONObject

/** Reads the selected original project's runtime DTO. This adapter has no install/repair/grant command. */
internal class PocketWebProjectRuntime(private val controller: PocketWorldController) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var busy = false
    companion object { val COMMANDS = setOf("projectRuntime.read") }
    fun clear() { generation++ }
    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(action == "projectRuntime.read" && controller.webPrivateOwnerVisible() && !busy)
        val checkoutGuide = input.opt("checkoutGuide") == true
        require(input.keys().asSequence().toSet() == if (checkoutGuide) setOf("taskId", "checkoutGuide") else setOf("taskId"))
        val id = input.get("taskId"); require(id is String && id.isNotBlank() && id.length <= 200)
        val owner = controller.webWindowTicket(); val epoch = generation
        fun current() = epoch == generation && owner == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        busy = true
        controller.readTaskForReview(id) { task, error ->
            if (!current() || task == null || error != null) { busy = false; done(null, error ?: "原项目读取窗口已变化"); return@readTaskForReview }
            try {
                check(task.id == id && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" && task.raw.optJSONObject("origin") == null)
                val context = task.raw.optJSONObject("developmentContext") ?: error("原任务没有项目开发范围")
                val checkout = context.getString("checkoutId"); val runtime = context.getString("runtime")
                check(checkout.isNotBlank() && runtime.isNotBlank())
                controller.runEcosystemControlWork(Runnable {
                    val result = runCatching {
                        check(current())
                        if (checkoutGuide) {
                            check(runtime == "managed_process_v1" && task.raw.optString("kind") == "assistant" &&
                                task.raw.optBoolean("contextAvailable", true) && !task.raw.optBoolean("sourceCurrentReadRequired") &&
                                task.raw.optString("taskProjection") != "source_unavailable") { "这不是可核对的原本机托管任务" }
                            val source = controller.projectDevelopmentForWeb().sourceForOwner(context.getString("projectId"), checkout, true)
                            val tooling = source.optJSONObject("checkoutTooling") ?: error("未取得原副本的本机工具观察；没有运行命令")
                            return@runCatching JSONObject().put("readOnly", true).put("taskId", id).put("runId", task.raw.getString("runId"))
                                .put("epoch", task.raw.getInt("coordinatorEpoch")).put("checkoutTooling", tooling)
                        }
                        val local = controller.localTerminalHost().module("local") as? LocalModule ?: error("原手机项目环境不可用")
                        val actual = local.projectCheckoutForHost(checkout)
                        check(actual.getString("projectId") == context.getString("projectId")) { "原工作副本已变化" }
                        val facts = local.projectRuntimeForHost(runtime)
                        ProjectRuntimeHealth.presentation(facts, task).put("taskId", id).put("checkoutId", checkout).put("runtimeId", runtime)
                    }
                    main.post { busy = false; if (!current()) done(null, "原项目窗口已变化，没有采用迟到的环境观察")
                        else result.fold({ done(it, null) }, { done(null, it.message ?: "原环境观察暂不可核对") }) }
                })
            } catch (failure: Exception) { busy = false; done(null, failure.message ?: "原环境读取未开始") }
        }
    }
}
