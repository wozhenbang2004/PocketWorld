package dev.pocketengine.ui

import dev.pocketengine.R

/** Presentation only. Exact IDs remain visible in details and are never used to route a call. */
internal fun toolDisplayName(name: String): String = when (name) {
    "read_project_file", "project_read", "project_read_file" -> taskFlowText(R.string.task_flow_helper_6417ad182c89, "读取项目文件")
    "write_project_file", "project_write_file", "project_apply_patch" -> taskFlowText(R.string.task_flow_helper_70f00151a265, "修改项目文件")
    "project_search", "project_search_files" -> taskFlowText(R.string.task_flow_helper_a4b667cf90e4, "搜索项目内容")
    "project_execute" -> taskFlowText(R.string.task_flow_helper_7d38fa6ade01, "运行项目任务")
    "project_status", "project_process_status" -> taskFlowText(R.string.task_flow_helper_9aa8502f07f8, "查看运行状态")
    "project_stop" -> taskFlowText(R.string.task_flow_helper_61719b8faf64, "停止项目运行")
    "project_list", "project_list_files" -> taskFlowText(R.string.task_flow_helper_2f983f303d90, "浏览项目文件")
    "remote_job_status", "remote_job_get" -> taskFlowText(R.string.task_flow_helper_12a6bee20018, "查询远程任务")
    "remote_job_submit", "remote_submit" -> taskFlowText(R.string.task_flow_helper_dec449d6b8af, "提交远程任务")
    "remote_job_cancel" -> taskFlowText(R.string.task_flow_helper_89c936008913, "停止远程任务")
    "phone_observe" -> taskFlowText(R.string.task_flow_helper_ba349e7d93be, "查看手机界面")
    "phone_list_apps" -> taskFlowText(R.string.task_flow_helper_27fc31a0d60a, "查看可用应用")
    "phone_launch_app" -> taskFlowText(R.string.task_flow_helper_fca8d9704215, "打开手机应用")
    "phone_click_node", "phone_tap" -> taskFlowText(R.string.task_flow_helper_6438a7eb77ad, "点击界面控件")
    "phone_type" -> taskFlowText(R.string.task_flow_helper_7159815dd39f, "输入文字")
    "phone_swipe" -> taskFlowText(R.string.task_flow_helper_590ab92f1128, "滚动手机界面")
    "phone_back" -> taskFlowText(R.string.task_flow_helper_b6050ccef309, "返回上一页")
    "phone_home" -> taskFlowText(R.string.task_flow_helper_b085faa05ba1, "返回手机桌面")
    "browser_observe", "browser_read" -> taskFlowText(R.string.task_flow_helper_27a6dca15f9b, "读取网页内容")
    "browser_open", "browser_navigate" -> taskFlowText(R.string.task_flow_helper_82d9ce3a5b37, "打开网页")
    "browser_click" -> taskFlowText(R.string.task_flow_helper_09bbe4715fe3, "点击网页控件")
    "browser_type" -> taskFlowText(R.string.task_flow_helper_f478ead1b9ee, "填写网页内容")
    "browser_scroll" -> taskFlowText(R.string.task_flow_helper_f3e6fb138f57, "滚动网页")
    "browser_wait" -> taskFlowText(R.string.task_flow_helper_9380bea6eb37, "等待网页更新")
    "teams_plan" -> taskFlowText(R.string.task_flow_helper_4072b287a386, "安排团队分工")
    "teams_start" -> taskFlowText(R.string.task_flow_helper_8701828a9bd8, "启动团队协作")
    "teams_get", "teams_wait" -> taskFlowText(R.string.task_flow_helper_d36596ce1f5e, "查看团队进展")
    "teams_message" -> taskFlowText(R.string.task_flow_helper_d92a22bb4f7b, "与团队成员沟通")
    "teams_control" -> taskFlowText(R.string.task_flow_helper_13c364bf77a9, "调整团队任务")
    "skills_execute" -> taskFlowText(R.string.task_flow_helper_7d3d81a5a74b, "运行所选技能")
    else -> name
}

internal fun executionToneCaption(tone: TaskExecutionPresentation.Tone): String = when (tone) {
    TaskExecutionPresentation.Tone.RUNNING -> taskFlowText(R.string.task_flow_helper_dc9591e56d50, "进行中")
    TaskExecutionPresentation.Tone.WAITING -> taskFlowText(R.string.task_flow_helper_a8b0bd9c65f7, "待确认")
    TaskExecutionPresentation.Tone.UNCERTAIN -> taskFlowText(R.string.task_flow_helper_33df16b4afab, "待核对")
    TaskExecutionPresentation.Tone.ERROR -> taskFlowText(R.string.task_flow_helper_292825b7f8c8, "遇到问题")
    TaskExecutionPresentation.Tone.RETURNED -> taskFlowText(R.string.task_flow_helper_4be334f6b7c5, "已返回")
    TaskExecutionPresentation.Tone.STOPPED -> taskFlowText(R.string.task_flow_helper_f006455e3baf, "已停止")
    TaskExecutionPresentation.Tone.NEUTRAL -> taskFlowText(R.string.task_flow_helper_9fad24e2c377, "记录")
}
