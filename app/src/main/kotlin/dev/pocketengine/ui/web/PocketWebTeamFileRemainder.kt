package dev.pocketengine.ui.web

import java.util.UUID
import org.json.JSONObject

/** Only the foreground owner holds the opaque, one-shot native review. Opening it never enters the runner. */
internal class PocketWebTeamFileRemainder<R : Any>(
    private val work: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
    private val view: (R) -> JSONObject,
    private val publish: () -> Unit
) {
    private data class Held<R>(val task: String, val handle: String, val review: R)
    private var held: Held<R>? = null
    private var generation = 0L
    fun clear() { held = null; generation++ }
    fun read(task: String, current: () -> Boolean, read: () -> R, reply: (JSONObject?, String?) -> Unit) {
        clear()
        val page = generation
        work(Runnable {
            val result = runCatching {
                check(current()) { "原窗口已关闭，没有启动" }
                val review = read()
                (review to view(review)).also { check(it.second.getString("taskId") == task) }
            }
            main(Runnable {
                if (!current() || page != generation) reply(null, "原文件审阅已关闭，没有启动")
                else result.fold({ (review, observed) ->
                    val handle = UUID.randomUUID().toString()
                    held = Held(task, handle, review)
                    reply(observed.put("reviewHandle", handle), null)
                }, { reply(null, it.message ?: "原权限或文件不可核实，没有启动") })
            })
        })
    }
    fun dismiss(task: String, handle: String) {
        if (held?.let { it.task == task && it.handle == handle } == true) clear()
    }
    fun submit(task: String, handle: String, current: () -> Boolean, run: (R, (JSONObject) -> Unit, (Exception?) -> Unit) -> Unit, reply: (JSONObject?, String?) -> Unit) {
        val original = held ?: error("请重新查看剩余原文件")
        require(current() && original.task == task && original.handle == handle) { "不是这次原文件审阅" }
        clear()
        // Native run owns its original serial queue. Keep the owner's IO queue available for approval/stop.
        val admitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val ended: (Exception?) -> Unit = { error ->
            main(Runnable {
                if (!admitted.get()) reply(null, if (current()) error?.message ?: "未取得原任务启动回执" else "请回原任务核对，没有再次提交")
                publish()
            })
        }
        try {
            run(original.review, { receipt ->
                admitted.set(true)
                main(Runnable {
                    if (current()) reply(receipt, null)
                    else reply(null, "原任务已登记续接；请回原任务查看，不要重试")
                    publish()
                })
            }, ended)
        } catch (failure: Exception) {
            ended(failure)
        }
    }
}
