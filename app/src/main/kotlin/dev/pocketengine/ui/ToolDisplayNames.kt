package dev.pocketengine.ui

/** Presentation only. Exact IDs remain visible in details and are never used to route a call. */
internal fun toolDisplayName(name: String): String = when (name) {
    "read_project_file", "project_read", "project_read_file" -> "读取项目文件"
    "write_project_file", "project_write_file", "project_apply_patch" -> "修改项目文件"
    "project_search", "project_search_files" -> "搜索项目内容"
    "project_execute" -> "运行项目任务"
    "project_status", "project_process_status" -> "查看运行状态"
    "project_stop" -> "停止项目运行"
    "project_list", "project_list_files" -> "浏览项目文件"
    "remote_job_status", "remote_job_get" -> "查询远程任务"
    "remote_job_submit", "remote_submit" -> "提交远程任务"
    "remote_job_cancel" -> "停止远程任务"
    "phone_observe" -> "查看手机界面"
    "phone_list_apps" -> "查看可用应用"
    "phone_launch_app" -> "打开手机应用"
    "phone_click_node", "phone_tap" -> "点击界面控件"
    "phone_type" -> "输入文字"
    "phone_swipe" -> "滚动手机界面"
    "phone_back" -> "返回上一页"
    "phone_home" -> "返回手机桌面"
    "browser_observe", "browser_read" -> "读取网页内容"
    "browser_open", "browser_navigate" -> "打开网页"
    "browser_click" -> "点击网页控件"
    "browser_type" -> "填写网页内容"
    "browser_scroll" -> "滚动网页"
    "browser_wait" -> "等待网页更新"
    "teams_plan" -> "安排团队分工"
    "teams_start" -> "启动团队协作"
    "teams_get", "teams_wait" -> "查看团队进展"
    "teams_message" -> "与团队成员沟通"
    "teams_control" -> "调整团队任务"
    "skills_execute" -> "运行所选技能"
    else -> name
}

internal fun executionToneCaption(tone: TaskExecutionPresentation.Tone): String = when (tone) {
    TaskExecutionPresentation.Tone.RUNNING -> "进行中"
    TaskExecutionPresentation.Tone.WAITING -> "待确认"
    TaskExecutionPresentation.Tone.UNCERTAIN -> "待核对"
    TaskExecutionPresentation.Tone.ERROR -> "遇到问题"
    TaskExecutionPresentation.Tone.RETURNED -> "已返回"
    TaskExecutionPresentation.Tone.STOPPED -> "已停止"
    TaskExecutionPresentation.Tone.NEUTRAL -> "记录"
}
