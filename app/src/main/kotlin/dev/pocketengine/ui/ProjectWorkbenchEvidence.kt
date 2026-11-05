package dev.pocketengine.ui

import org.json.JSONObject

internal fun runtimeActivityCaption(activity: JSONObject?, execution: String): String? {
    if (activity == null || activity.optString("format") != "pocketworld.runtime-activity.v1" || activity.optString("operationId") != execution) return null
    val state = when (activity.optString("state")) {
        "running" -> "原进程运行中"
        "active_output" -> "原执行有输出更新"
        "inferred_idle" -> "原执行暂时静默；仅为静默推测，不证明正在等输入或已经完成"
        "starting" -> "等待原进程启动确认"
        "preparing" -> "原工具仍在准备"
        "stopping" -> "原执行已请求停止，仍需真实清场回执"
        "awaiting_resource_release" -> "已观察原进程退出，仍在核对资源释放"
        "exited" -> "原进程退出与资源释放已核对；业务结果仍需验收"
        "not_launched" -> "原回执确认未创建子进程"
        else -> "原进程状态待核；只读日志，不自动重放"
    }
    val window = activity.optJSONObject("observationWindow")
    val observation = when (window?.optString("phase")) {
        "foreground_wait" -> "原批准的前台观察时窗内。"
        "background_observation" -> "前台时窗已到，仅观察同一个原执行，总期限未延长。"
        else -> ""
    }
    val input = when (activity.optJSONObject("input")?.optString("state")) {
        "delivered" -> " 原输入已取得字节ACK，未证明程序已经处理。"
        "not_dispatched" -> " 上次原输入确认未发送。"
        "unknown" -> " 原输入结果待核，只查询原操作，不重发。"
        else -> ""
    }
    return observation + state + input
}
