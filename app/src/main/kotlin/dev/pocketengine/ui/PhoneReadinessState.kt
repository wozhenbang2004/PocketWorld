package dev.pocketengine.ui

import org.json.JSONObject

internal enum class PhoneCapabilityState(val label: String) { READY("可用"), BLOCKED("需准备"), UNSUPPORTED("本机不支持"), UNKNOWN("尚未核实") }
internal data class PhoneCapabilityCheck(val id: String, val state: PhoneCapabilityState, val code: String, val action: String, val message: String)
internal data class PhoneCapabilityGroup(val id: String, val title: String, val state: PhoneCapabilityState, val checks: List<PhoneCapabilityCheck>)
internal data class PhoneReadinessState(val checkedAtUptimeMs: Long = -1, val groups: List<PhoneCapabilityGroup> = emptyList()) {
    companion object {
        private fun status(value: String, code: String = "") = when (value) { "ready" -> PhoneCapabilityState.READY; "blocked" -> if (code.endsWith("unsupported")) PhoneCapabilityState.UNSUPPORTED else PhoneCapabilityState.BLOCKED; else -> PhoneCapabilityState.UNKNOWN }
        fun parse(source: JSONObject?): PhoneReadinessState {
            val names = linkedMapOf("mainObservation" to "界面与窗口读取", "mainExecution" to "主屏输入与停止", "mainScreenshot" to "主屏截图", "taskNotificationsForStart" to "任务通知", "backgroundPreparation" to "后台连接准备", "backgroundExecution" to "独立显示执行", "backgroundInput" to "后台输入注入", "backgroundCapture" to "后台真实取帧", "backgroundIme" to "独立输入法隔离")
            return PhoneReadinessState(source?.optLong("checkedAtUptimeMs", -1) ?: -1, names.map { (id, title) ->
                val group = source?.optJSONObject(id); val checks = group?.optJSONArray("checks")
                val projected = (0 until (checks?.length() ?: 0)).mapNotNull { index -> checks?.optJSONObject(index)?.let { PhoneCapabilityCheck(it.optString("id"), status(it.optString("state"), it.optString("code")), it.optString("code"), it.optString("action"), it.optString("message")) } }
                val groupState = if (projected.any { it.state == PhoneCapabilityState.UNSUPPORTED }) PhoneCapabilityState.UNSUPPORTED else status(group?.optString("state") ?: "")
                PhoneCapabilityGroup(id, title, groupState, projected)
            })
        }
    }
}
