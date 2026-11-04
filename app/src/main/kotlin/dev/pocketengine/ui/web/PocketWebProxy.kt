package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.util.UUID

/** Presentation reviews around the existing phone-local relay; never a second model route. */
internal class PocketWebProxy(private val controller: PocketWorldController) {
    private data class Review(val input: String, val selection: Long, val expires: Long, val generation: Long)
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val reviews = mutableMapOf<String, Review>()
    companion object { val COMMANDS = setOf("proxy.snapshot", "proxy.status", "proxy.reviewStart", "proxy.confirmStart", "proxy.stop", "proxy.closeReview") }
    fun clear() { generation++; snapshot = JSONObject(); reviews.clear() }
    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "本机服务操作包含不适用的字段" } }
    private fun text(args: JSONObject, key: String, max: Int = 256): String {
        val value = args.opt(key)
        require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "请核对$key" }
        return value
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人本机服务页面" }
        val original = generation; val window = controller.webWindowTicket()
        fun current() = generation == original && controller.webWindowTicket() == window && controller.webPrivateOwnerVisible()
        fun native(command: String, input: JSONObject = JSONObject(), selection: Long = -1, session: String = "") {
            controller.requestWebProxyReceipt(command, input, selection, session, ::current) { value, error ->
                if (current()) {
                    if (value != null && error == null) snapshot = JSONObject(value.toString())
                    done(value?.let { PocketWebState.publicValue(it) as JSONObject }, error)
                }
            }
        }
        when (action) {
            "proxy.snapshot", "proxy.status" -> { fields(args); native("snapshot") }
            "proxy.reviewStart" -> {
                fields(args, "port", "token")
                check(controller.state.moduleBusy.none { it in setOf("proxy.start", "proxy.stop") }) { "原服务操作还在等待回执，请先核对" }
                check(snapshot.has("selectionRevision") && !snapshot.optBoolean("running")) { "请先刷新本机服务和当前模型选择" }
                check(snapshot.optBoolean("externalRelayAllowed")) { "当前账号模型仅供本应用使用，请明确选择自己的 API-key 连接" }
                val port = args.opt("port")
                require(port is Number && java.math.BigDecimal(port.toString()).intValueExact() in 0..65535) { "端口须为 0（自动）或 1–65535" }
                require(text(args, "token").matches(Regex("[A-Za-z0-9_-]{24,256}"))) { "访问令牌须为 24–256 位字母、数字、下划线或短横线" }
                reviews.clear()
                val handle = UUID.randomUUID().toString()
                reviews[handle] = Review(JSONObject(args.toString()).toString(), snapshot.getLong("selectionRevision"), SystemClock.elapsedRealtime() + 120_000, generation)
                done(JSONObject().put("reviewHandle", handle).put("port", port).put("model", snapshot.optString("model"))
                    .put("protocol", snapshot.optString("protocol")).put("endpoints", snapshot.optJSONArray("endpoints"))
                    .put("scope", "loopback_only").put("phoneExecution", false).put("tokenProvided", true), null)
            }
            "proxy.confirmStart" -> {
                fields(args, "reviewHandle")
                val review = reviews.remove(text(args, "reviewHandle")) ?: error("原启动审阅已使用或关闭，请先刷新实际服务")
                check(review.generation == generation && review.expires > SystemClock.elapsedRealtime()) { "原启动审阅已过期，请重新核对" }
                native("start", JSONObject(review.input), review.selection)
            }
            "proxy.stop" -> {
                fields(args, "sessionId")
                val session = text(args, "sessionId")
                check(session == snapshot.optString("sessionId")) { "原本机服务已变化，请刷新后再停止" }
                native("stop", session = session)
            }
            "proxy.closeReview" -> { fields(args, "reviewHandle"); reviews.remove(text(args, "reviewHandle")); done(JSONObject().put("closed", true), null) }
            else -> error("本机服务操作尚未接入")
        }
    }
}
