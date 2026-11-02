package dev.pocketengine.ui

import dev.pocketengine.R

import org.json.JSONObject

/** Presentation of native observations only. Every action still re-enters the existing final gate. */
internal data class PlanWaitState(
    val title: String, val lines: List<String>, val inspect: Boolean = false,
    val recheck: Boolean = false, val resumeOriginal: Boolean = false,
    val nextAt: Long? = null, val observedAt: Long? = null
)

internal fun planWaitState(plan: JSONObject): PlanWaitState? {
    val execution = plan.optJSONObject("execution") ?: return null
    val steps = plan.optJSONArray("steps").objects()
    val decision = plan.optJSONObject("runtimeDecision")
    val step = steps.find { it.optString("id") == decision?.optString("stepId") } ?: plan.optJSONObject("nextStep")
    val hasOriginal = steps.any { it.optString("taskId").isNotEmpty() || it.optJSONObject("taskAdmission") != null }
    if (plan.optString("state") == "cancelled") return PlanWaitState(taskFlowText(R.string.task_flow_helper_35ea09919477, "后继执行已取消"), listOf(taskFlowText(R.string.task_flow_helper_8a5d7e0af9a9, "此目标不会再触发后继任务。已发生的操作不会撤销，在途原任务仍按下面的实际状态核对。")), inspect = hasOriginal)
    if (decision == null) return null
    val reason = decision.optString("reason")
    val observedState = step?.optJSONObject("taskObservation")?.optString("status").orEmpty()
    val state = step?.optString("taskState", observedState).orEmpty()
    val active = execution.optString("state") == "active" && execution.optBoolean("started") && plan.optBoolean("approvalValid") && execution.optBoolean("modelObservationConsent")
    if (state in setOf("unknown", "reconciling")) return PlanWaitState(taskFlowText(R.string.task_flow_helper_ac98edaeed79, "原操作结果待核对"), listOf(taskFlowText(R.string.task_flow_helper_d48c3795e88c, "保留同一次触发和原任务。这里只查询原记录，不会补发已有操作；请打开原任务核对未决结果。")), inspect = true)
    if (step?.optBoolean("taskNeedsApproval") == true) return PlanWaitState(taskFlowText(R.string.task_flow_helper_de078a5a919e, "原任务需要具体操作审批"), listOf(taskFlowText(R.string.task_flow_helper_f0d738318a21, "目标范围授权仍保留。请在原任务查看并批准或拒绝这次具体参数；这里不会代你授权。")), inspect = true)
    val next = decision.optLong("nextAt").takeIf { it > 0 }
    if (reason.startsWith("business_wait_") || reason == "business_source_consent_required") {
        val line = when (reason) {
            "business_wait_pending" -> taskFlowText(R.string.task_flow_helper_a39536125d8c, "原认证来源尚无可消费的新事件。这里只检查本机原inbox窗口，不请求模型或补造消息。")
            "business_wait_retention_gap" -> taskFlowText(R.string.task_flow_helper_81b62034b10a, "原anchor或扫描位置已不在保留窗口，未自动跳过缺口或前移已读位置。请暂停后重新选择仍可核对的来源并审阅。")
            "business_wait_consumed_event_changed" -> taskFlowText(R.string.task_flow_helper_c925358c8f28, "本次已经消费的原事件版本已变化。保留同一次触发身份，不换另一条事件重新触发目标。")
            "business_source_consent_required" -> taskFlowText(R.string.task_flow_helper_79d9b4dcf698, "请在原生范围页明确确认所选来源与这个固定目标。")
            else -> taskFlowText(R.string.task_flow_helper_fab340d4fa50, "原渠道绑定、来源或本机记录暂不可核对；已撤销来源不能产生新的消费或派发。")
        }
        return PlanWaitState(taskFlowText(R.string.task_flow_helper_09da9d09c045, "等待认证来源事件"), listOf(line, taskFlowText(R.string.task_flow_helper_f429d3763ab0, "只核验事件存在，不把消息正文当新指令；已合法启动的目标仍受独立原plan授权、预算和取消控制。")), inspect = hasOriginal, recheck = active, nextAt = next)
    }
    if (reason.startsWith("remote_wait_")) {
        val remote = decision.optJSONObject("remoteWaitStatus")
        val detail = when (reason) {
            "remote_wait_pending" -> taskFlowText(R.string.task_flow_helper_d1249f0b4cf0, "原 command Job 尚未返回进程终态与退出码 0。当前未启动这一步。")
            "remote_wait_unknown" -> taskFlowText(R.string.task_flow_helper_61e49b2d2328, "原电脑回执仍待核对。不会重提原命令，也不把失联当作完成。")
            "remote_wait_failed" -> taskFlowText(R.string.task_flow_helper_f8b5157e7113, "原进程回执为 %1\$s，退出码 %2\$s，未确认成功退出。", remote?.optString("executorStatus"), remote?.optLong("exitCode")) + if (step?.optJSONObject("taskAdmission") != null) taskFlowText(R.string.task_flow_helper_f673593273f4, "这一步已保留原触发身份，不能借修改换来源。可核对原任务，或取消此目标后另建经过审阅的目标。") else taskFlowText(R.string.task_flow_helper_ea32a3c28cc9, "请打开原电脑任务处理；需要换新的原任务时，暂停修改这一步并重新审阅。")
            "remote_wait_cancelled" -> taskFlowText(R.string.task_flow_helper_9aca96e5fdb7, "原电脑命令已被取消或请求停止，没有取得退出码 0 条件。这里不会恢复它。")
            "remote_wait_identity_changed" -> taskFlowText(R.string.task_flow_helper_f7fc508dbac4, "所选原任务的 run、代次、电脑或 job 身份已变化。没有自动跟随新执行；请暂停后明确重选并审阅。")
            "remote_wait_exit_unavailable" -> taskFlowText(R.string.task_flow_helper_d8c9423d1694, "已收到原进程结束信息，但没有有效整数退出码，不能按 0 处理。")
            else -> taskFlowText(R.string.task_flow_helper_195cf1602b52, "原任务、进程或本机回执暂不可核实，未取得这一步的执行条件。")
        }
        return PlanWaitState(if (reason == "remote_wait_failed") taskFlowText(R.string.task_flow_helper_359ac1480cb4, "电脑前置未满足") else if (reason == "remote_wait_unknown") taskFlowText(R.string.task_flow_helper_3fe799afdb76, "电脑原结果待核对") else taskFlowText(R.string.task_flow_helper_b941d0d635ca, "等待电脑原任务"), listOf(detail, taskFlowText(R.string.task_flow_helper_d22806c5ed53, "这里只核对本机已保存的原回执；需要重新查询电脑时，请打开下面的电脑原任务。进程退出码不证明命令业务成果。")), inspect = hasOriginal, recheck = active && reason in setOf("remote_wait_pending", "remote_wait_unknown", "remote_wait_unavailable"), nextAt = next, observedAt = remote?.optLong("observedAt")?.takeIf { it > 0 })
    }
    when (reason) {
        "conditions_blocked" -> {
            val status = decision.optJSONObject("conditionStatus")
            val actual = status?.optJSONObject("snapshot")
            val blockers = status?.optJSONArray("blockers")
            val codes = (0 until (blockers?.length() ?: 0)).map { blockers!!.optString(it) }
            val lines = codes.map { code -> when (code) {
                "network_unavailable" -> if (actual?.optString("networkReason") == "permission_missing") taskFlowText(R.string.task_flow_helper_e2a314bdf2c1, "未获得网络状态权限，当前不会按已联网处理。") else taskFlowText(R.string.task_flow_helper_9cf7a5f14981, "系统网络状态暂不可读取，当前不会按已联网处理。")
                "network_not_validated" -> taskFlowText(R.string.task_flow_helper_101a3f159b7d, "网络尚未经系统验证可用；这也不等于模型接口已可达。")
                "network_required" -> taskFlowText(R.string.task_flow_helper_fce87872643f, "等待系统已验证的网络。")
                "charging_unavailable" -> taskFlowText(R.string.task_flow_helper_98f6d26d76a5, "系统未提供可用的充电状态。")
                "charging_required" -> taskFlowText(R.string.task_flow_helper_70aae635e70e, "等待充电。")
                "battery_unavailable" -> taskFlowText(R.string.task_flow_helper_fe2d3f270460, "系统未提供有效电量，未当作 0% 或满电处理。")
                "battery_below_minimum" -> taskFlowText(R.string.task_flow_helper_87e18d01a788, "当前电量 %1\$s%%，需要至少 %2\$s%%。", actual?.optInt("batteryPercent") ?: taskFlowText(R.string.task_flow_helper_4d8c1c5b4283, "未知"), status?.optJSONObject("requirements")?.optInt("minBatteryPercent"))
                else -> taskFlowText(R.string.task_flow_helper_334430e29b28, "运行条件暂不可核实。")
            } } + if (step?.optJSONObject("taskAdmission") != null) listOf(taskFlowText(R.string.task_flow_helper_43fd7f22ba0b, "已经保留同一次触发记录。条件恢复后继续核对该原任务，不重新计算任务次数。")) else emptyList()
            return PlanWaitState(if (codes.any { it.endsWith("_unavailable") }) taskFlowText(R.string.task_flow_helper_559d2fbd6770, "运行条件暂不可读取") else taskFlowText(R.string.task_flow_helper_73bd13d7105f, "等待运行条件"), lines, inspect = hasOriginal, recheck = active, nextAt = next, observedAt = actual?.optLong("observedAt")?.takeIf { it > 0 })
        }
        "not_due", "quiet_or_not_due" -> return PlanWaitState(if (reason == "not_due") taskFlowText(R.string.task_flow_helper_d811bcc6a56f, "等待计划时间") else taskFlowText(R.string.task_flow_helper_1732738b125e, "等待计划时间或安静时段结束"), listOf(taskFlowText(R.string.task_flow_helper_47db97120191, "当前没有取得提前执行许可。系统计时可能延后；手动核对也会重新检查原时间、条件和范围。")), inspect = hasOriginal, recheck = active, nextAt = next)
        "expired", "approval_expired", "expires_before_eligible" -> return PlanWaitState(if (reason == "expires_before_eligible") taskFlowText(R.string.task_flow_helper_38d56b8c50b9, "最早执行时间超出原窗口") else taskFlowText(R.string.task_flow_helper_c88478bb76ce, "本次执行窗口已结束"), listOf(if (reason == "expires_before_eligible") taskFlowText(R.string.task_flow_helper_73b3b6ffd861, "原窗口会早于最早可执行时间结束，未启动任务。") else taskFlowText(R.string.task_flow_helper_7096303e1b4f, "不会把过期当作完成，也不会恢复旧执行许可。"), if (step?.optJSONObject("taskAdmission") != null) taskFlowText(R.string.task_flow_helper_651f660fe268, "原触发记录的截止时间不会因重新审阅而延长；仍可核对原任务和已有成果。") else taskFlowText(R.string.task_flow_helper_18d51965a096, "尚无原任务时，可以暂停修改未来步骤，并重新审阅有效窗口。")), inspect = hasOriginal)
        "admission_unknown" -> return PlanWaitState(taskFlowText(R.string.task_flow_helper_075f238d08b1, "原触发记录待核对"), listOf(taskFlowText(R.string.task_flow_helper_2a890b38f57d, "已经保存这一次触发的身份。先查询原任务；核对后继续仍使用同一记录，不补造第二次触发。")), inspect = true, recheck = active)
        "bound_task" -> {
            if (step?.optString("taskId").orEmpty().isNotEmpty() && step?.optString("taskRunId").orEmpty().isEmpty()) return PlanWaitState(taskFlowText(R.string.task_flow_helper_a9678a93eae0, "原任务已登记，尚未开始执行"), listOf(taskFlowText(R.string.task_flow_helper_323a9632499b, "将先核对同一次触发的原任务，再按当前条件与原截止时间继续，不建立第二份任务。")), inspect = true, recheck = active)
            return PlanWaitState(if (state in setOf("unverified_result", "needs_confirmation")) taskFlowText(R.string.task_flow_helper_1f82b006a254, "原任务结果待核对") else if (state in setOf("interrupted", "paused", "awaiting_user")) taskFlowText(R.string.task_flow_helper_eeadd22646f3, "原任务仍需处理") else taskFlowText(R.string.task_flow_helper_989d7d8101a8, "原任务正在处理"), listOf(taskFlowText(R.string.task_flow_helper_ce74fc2ad1f6, "原任务关联已保留。核对状态不会创建任务或重新执行，已有成果仍从同一个原任务查看。")), inspect = true, resumeOriginal = active && state in setOf("interrupted", "paused", "awaiting_user"))
        }
        "ready" -> return PlanWaitState(taskFlowText(R.string.task_flow_helper_112c5bee91a9, "可以核对并继续此目标"), listOf(taskFlowText(R.string.task_flow_helper_c8929525157d, "将重新检查本目标的原任务、时间、条件和授权，再进入现有执行路径。")), inspect = hasOriginal, recheck = active)
        "dependency_task_state_unavailable", "other_original_task_state_unavailable", "other_original_task_unsettled", "dependency_task_not_settled" -> return PlanWaitState(taskFlowText(R.string.task_flow_helper_9bd3718e8767, "前置或原任务尚未核实"), listOf(taskFlowText(R.string.task_flow_helper_8b39a4f35c61, "当前只核对原任务状态，未把缺少回执当作成功、失败或再次执行的许可。")), inspect = hasOriginal)
        "dependency_pending" -> return PlanWaitState(taskFlowText(R.string.task_flow_helper_1639ca42c6cb, "等待前置的真实成果"), listOf(taskFlowText(R.string.task_flow_helper_0b01c1aa74a7, "每项前置都需满足已审阅的结果条件。当前没有启动此后继步骤。")), inspect = hasOriginal)
        "task_bridge_unavailable" -> return PlanWaitState(taskFlowText(R.string.task_flow_helper_cffa88e95f16, "原任务连接暂不可用"), listOf(taskFlowText(R.string.task_flow_helper_4990761851c5, "没有启动或替换任务。请稍后刷新原任务连接。")), inspect = hasOriginal)
    }
    return null
}
