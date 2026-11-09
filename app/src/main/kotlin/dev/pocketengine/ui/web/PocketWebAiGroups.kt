package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.runtime.models.ModelTargetRef
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Owner model groups share the existing native route plans and the single selection mutation gate. */
internal class PocketWebAiGroups(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean
) {
    companion object {
        val COMMANDS = setOf("models.groups.snapshot", "models.groups.save", "models.groups.select", "models.groups.delete", "models.groups.unbind")
    }
    private val main = Handler(Looper.getMainLooper())
    private fun fields(args: JSONObject, vararg allowed: String) {
        require(args.keys().asSequence().all { it in allowed }) { "模型组操作含不适用的字段" }
    }
    private fun version(args: JSONObject): Long {
        val value = args.opt("expectedRevision")
        require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "请重新读取原模型组修订" }
        return (value as Number).toLong()
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        check(action in COMMANDS && ownerCurrent() && controller.webPrivateOwnerVisible()) { "原模型管理窗口已关闭" }
        val owner = controller.webWindowTicket()
        fun current() = ownerCurrent() && controller.webPrivateOwnerVisible() && owner == controller.webWindowTicket()
        val service = controller.nativeModelConnections() ?: error("本机模型连接尚未准备")
        if (action == "models.groups.snapshot") {
            fields(args)
            controller.runModelConnectionWork(Runnable {
                val result = runCatching {
                    check(current())
                    val snapshot = controller.nativeModelSelection().readStable { service.connections().getJSONObject("modelGroups") }
                    JSONObject(snapshot.value().toString()).put("selectionRevision", snapshot.revision())
                }
                main.post {
                    if (!current()) done(null, "原模型管理窗口已变化，请重新读取", "not_accepted")
                    else result.fold({ done(it, null, "not_accepted") }, { done(null, it.message ?: "模型组暂不可读取", "not_accepted") })
                }
            }); return
        }
        val state = controller.state
        check(!state.chatSubmitting && !state.phoneSubmitting && !controller.modelSelectionPending()) { "请等待原输入或模型设置回执" }
        when (action) {
            "models.groups.save" -> fields(args, "id", "expectedRevision", "name", "definition")
            "models.groups.unbind" -> fields(args, "purpose", "groupId", "expectedRevision")
            else -> fields(args, "groupId", "expectedRevision")
        }
        val expected = version(args)
        controller.runModelSelectionWrite({
            check(current()) { "原模型管理窗口已关闭，未保存排队操作" }
            val result = when (action) {
                "models.groups.save" -> {
                    val definition = args.getJSONObject("definition")
                    require(definition.keys().asSequence().all { it in setOf("allowedAccounts", "candidates", "attemptPolicy") }) { "模型组定义只能包含候选、用途和重试范围" }
                    service.saveGroup(args.optString("id"), expected, args.getString("name"), definition)
                }
                "models.groups.select" -> service.selectPurpose("MAIN", ModelTargetRef.group(args.getString("groupId"), expected))
                "models.groups.delete" -> service.deleteGroup(args.getString("groupId"), expected)
                "models.groups.unbind" -> {
                    val purpose = args.getString("purpose")
                    require(purpose in setOf("MAIN", "SUMMARY", "RELAY")) { "请选择原用途" }
                    val root = service.connections().getJSONObject("modelGroups")
                    val binding = root.getJSONObject("purposeBindings").optJSONObject(purpose)
                    check(binding != null && binding.optString("kind") == "group" && binding.optString("groupId") == args.getString("groupId") && binding.optLong("groupRevision") == expected) { "原用途已改用其他模型，请重新读取" }
                    service.selectPurpose(purpose, null)
                }
                else -> error("不是模型组操作")
            }
            JSONObject().put("status", "saved").put("snapshot", PocketWebState.publicValue(result))
        }) { result ->
            controller.modelConnectionChanged()
            if (!current()) done(null, "原窗口已变化，请核对实际保存结果；没有自动重试", "outcome_unknown")
            else result.fold({ done(it, null, "not_accepted") }, { done(null, it.message ?: "模型组保存结果待核对", "outcome_unknown") })
        }
    }
}
