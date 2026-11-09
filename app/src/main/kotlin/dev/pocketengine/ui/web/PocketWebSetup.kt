package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.local.runtime.RuntimeSetupHost
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** The page owns preferences and short-lived reviews. The application owns installation. */
internal class PocketWebSetup(
    private val controller: PocketWorldController,
    private val visible: () -> Boolean,
) {
    private data class Review(val handle: String, val native: RuntimeSetupHost.Review, val owner: Any)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var review: Review? = null
    private var busy = false

    companion object {
        val COMMANDS = setOf("setup.read", "setup.save", "setup.permission", "setup.model.test",
            "setup.runtime.read", "setup.runtime.review", "setup.runtime.start", "setup.runtime.cancel", "setup.runtime.dismiss")
    }

    fun clear() {
        generation++; busy = false
        controller.cancelSetupModelTest()
        val prior = review; review = null
        prior?.native?.close()
    }

    private fun fields(args: JSONObject, vararg allowed: String) {
        require(args.keys().asSequence().all { it in allowed }) { "准备操作包含不适用的字段" }
    }
    private fun id(args: JSONObject, key: String): String {
        val value = args.opt(key)
        require(value is String && value.matches(Regex("[a-f0-9-]{36}"))) { "原准备记录身份不完整，请刷新后核对" }
        return value
    }
    private fun integer(args: JSONObject, key: String, fallback: Long): Long {
        if (!args.has(key)) return fallback
        val value = args.opt(key)
        require(value is Int || value is Long) { "准备读取范围无效" }
        return (value as Number).toLong().also { require(it >= 0) { "准备读取范围不能为负数" } }
    }
    private fun current(owner: Any, ticket: Long, initialRead: Boolean) =
        generation == ticket && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == owner && (initialRead || visible())

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        require(action in COMMANDS)
        val initialRead = action == "setup.read"
        check(controller.webPrivateOwnerVisible() && (initialRead || visible())) { "请在私人前台打开准备中心" }
        if (action == "setup.runtime.dismiss") {
            fields(args, "reviewHandle")
            if (review?.handle == id(args, "reviewHandle")) { review?.native?.close(); review = null }
            done(JSONObject().put("reviewClosed", true).put("installationStopped", false), null); return
        }
        check(!busy) { "请等待这次准备操作返回" }
        val owner = controller.webWindowTicket(); val ticket = generation
        if (action == "setup.permission") {
            fields(args, "kind")
            require(args.opt("kind") is String) { "请选择要检查的权限" }
            done(controller.nativeSetupCoordinator().permission(args.getString("kind")), null); return
        }
        var selected: Review? = null
        when (action) {
            "setup.read", "setup.model.test" -> fields(args)
            "setup.save" -> fields(args, "expectedRevision", "step", "capabilities", "status")
            "setup.runtime.read" -> {
                fields(args, "taskId", "afterSequence", "maxBytes")
                if (args.has("taskId")) id(args, "taskId")
                integer(args, "afterSequence", 0)
                require(integer(args, "maxBytes", 32768) in 8192L..262144L) { "日志读取范围无效" }
            }
            "setup.runtime.review" -> {
                fields(args, "mode"); require(args.optString("mode") in setOf("prepare", "repair")) { "请选择准备或修复工具" }
            }
            "setup.runtime.start" -> {
                fields(args, "reviewHandle")
                selected = review?.takeIf { it.handle == id(args, "reviewHandle") && it.owner == owner }
                    ?: error("这份工具准备说明已关闭，请重新查看")
                review = null
            }
            "setup.runtime.cancel" -> {
                fields(args, "taskId", "runId", "epoch"); id(args, "taskId"); id(args, "runId")
                require(integer(args, "epoch", -1) in 1L..Int.MAX_VALUE.toLong()) { "原准备执行身份不完整" }
            }
        }
        val input = JSONObject(args.toString()); val original = selected; busy = true
        try {
            controller.runModelConnectionWork(Runnable {
                var acquired: RuntimeSetupHost.Review? = null
                val answer = runCatching {
                    check(current(owner, ticket, initialRead)) { "原准备窗口已变化，请重新读取状态" }
                    when (action) {
                        "setup.read" -> controller.nativeSetupCoordinator().read()
                        "setup.save" -> controller.nativeSetupCoordinator().save(input)
                        "setup.model.test" -> controller.nativeSetupCoordinator().testCurrentModel { current(owner, ticket, false) }
                        "setup.runtime.read" -> controller.nativeRuntimeSetup().read(input.optString("taskId"), integer(input, "afterSequence", 0), integer(input, "maxBytes", 32768).toInt())
                        "setup.runtime.review" -> controller.nativeRuntimeSetup().review(input.getString("mode")).also { acquired = it }.view()
                        "setup.runtime.start" -> controller.nativeRuntimeSetup().start(original!!.native.confirm())
                        "setup.runtime.cancel" -> controller.nativeRuntimeSetup().cancel(input.getString("taskId"), input.getString("runId"), input.getInt("epoch"))
                        else -> error("不支持的准备操作")
                    }
                }
                original?.native?.close()
                main.post {
                    if (generation == ticket) busy = false
                    if (!current(owner, ticket, initialRead)) {
                        acquired?.close()
                        done(null, "原准备窗口已变化。返回后读取实际状态，已开始的安装会继续保留。"); return@post
                    }
                    busy = false
                    answer.fold({ result ->
                        val value = acquired?.let { native ->
                            review?.native?.close()
                            val retained = Review(UUID.randomUUID().toString(), native, owner); review = retained
                            JSONObject().put("reviewHandle", retained.handle).put("review", result)
                        } ?: result
                        done(PocketWebState.publicValue(value) as JSONObject, null)
                    }, { failure -> done(null, failure.message ?: "准备操作未完成，请刷新后核对原记录") })
                }
            })
        } catch (failure: Exception) {
            busy = false; original?.native?.close(); throw failure
        }
    }
}
