package dev.pocketengine.browser

import org.json.JSONObject

/** The owner sees the same concrete native boundary before approving the original browser request. */
internal fun browserApprovalDetail(request: JSONObject, profileLabel: String): String = buildList {
    val tool = request.optString("tool")
    val args = request.child("arguments")
    val capability = request.child("capability")
    add(if (tool == "browser_task_read_public") "匿名公共读取，不使用登录资料或系统代理凭据。" else "登录资料：${profileLabel.ifBlank { "当前资料" }}")
    when (tool) {
        "browser_task_native_input" -> add(runCatching { BrowserNativeInput.approvalSummary(args, capability) }.getOrElse { "原输入目标或视口已不可核对，请拒绝并重新观察。" })
        "browser_task_site_mode", "browser_task_resize", "browser_task_back", "browser_task_forward", "browser_task_reload" ->
            add(runCatching { BrowserSiteMode.approvalSummary(tool.removePrefix("browser_task_"), args, capability) }.getOrElse { "原网页设置、尺寸或历史目的地已不可核对，请拒绝并重新读取。" })
        "browser_task_network" -> add(if (args.optString("backend", "witness") == "native")
            "原生网络观察：${args.optString("action")}。只观察已批准的原任务页；首次使用须在更多菜单允许本次进程调试。最多120秒、128条脱敏元数据，不包括正文、凭据头或URL参数。只覆盖启用后开始的主文档请求，不覆盖子frame、worker与旧请求，不证明全局网络空闲。导航、接管或取消即停，不补发请求。"
            else "网络witness：${args.optString("action")}。在当前页面包装fetch/XHR，只在本机保留有界元数据，读取时才交给原模型。无正文、Cookie、Authorization或URL查询；路径仍可能含个人标识。页面可修改或绕过，导航即停；停止采集不撤回已发网络。")
        "browser_task_upload_artifact" -> {
            val artifact = capability.child("uploadArtifact"); val target = capability.child("targetPreview")
            add("仅把这个原任务文件版本交给当前网页文件控件一次。交给浏览器后仍须核对站点接收结果；未知不自动重传。")
            add("文件：${artifact.optString("name")}\n来源：${if (artifact.optString("store") == "workspace") "任务工作区" else "任务附件"}\n大小：${artifact.optLong("size")} 字节 · ${artifact.optString("mimeType")}")
            add("网页：${capability.optString("pageUrl")}\n文件控件：${target.optString("ref")} · ${target.optString("id")} · ${target.optString("name")}\n表单目标（页面声明）：${target.optString("formAction")} · ${target.optString("formMethod")}")
            if (artifact.optString("store") == "workspace") add("版本：${artifact.optInt("sourceVersion")}\n修订：${artifact.optString("sourceRevisionId")}\n项目：${artifact.optString("projectId")}")
            add("文件ID：${artifact.optString("id")}\nSHA-256：${artifact.optString("sha256")}\n原文档：${capability.optString("documentId")}\n原控件：${target.optString("nodeKey")}")
            add("网页脚本可能处理所选文件，表单地址不是文件唯一去向。请确认你信任这个站点。")
        }
        "browser_task_network_body" -> {
            val response = capability.child("networkResponse")
            add("把这一个已展示响应的脱敏JSON正文交给原任务模型，可能仍含个人或账号内容；本次不授权其他响应，不重新抓取或发送请求。")
            add("原响应：${response.optString("url")}\n状态：${response.optInt("status")} · ${response.optString("mimeType")}\n披露上限：${response.optInt("maxDisclosureBytes")} 字节")
            add("只读原文档后端缓存；敏感端点、非JSON、旧文档和已读取对象会拒绝。已知敏感字段与明显凭据值会脱敏，超限截断；缓存丢失、取消或结果未知都不自动重取。")
        }
        "browser_task_script" -> {
            add("真实页面脚本会使用当前登录网页权限，可能读取隐私、修改DOM、发网络或提交。不是只读脚本，也不受隔离世界保护。只批准下方源码一次；异步效果可能继续，未知不自动重跑。")
            add("模型声明的目的与效果（未经核实）：\n${args.optString("effectReview")}")
            add("本次实际源码：\n${args.optString("source")}")
        }
        "browser_task_evaluate" -> add("真实DOM只读表达式（受限语言，不是任意JavaScript）：\n${args.optString("expression")}")
        "browser_task_retain_source" -> {
            val source = capability.child("sourceRetentionPreview")
            add(if (source.optString("mode") in setOf("table", "list")) "保留模型本次已读的 ${source.optInt("rows")} 行、${source.optInt("fields")} 个字段${if (source.optBoolean("partial")) "（片段）" else ""}；不另抓整页。" else "仅保留本次实际已读文字摘录。")
            if (source.optString("excerptPreview").isNotBlank()) add(source.getString("excerptPreview"))
        }
        "browser_task_read_public" -> {
            val policy = capability.child("publicHttpPolicy")
            add("实际请求URL：${policy.optString("requestUrl")}\n最多3次跳转、全链8秒、正文 ${policy.optInt("maxBodyBytes", 262144)} 字节；私有网络始终拒绝。")
            add("额外跳转origin：${policy.array("approvedRedirectOrigins")}；另仅允许同origin与同主机HTTPS升级，禁止降级。")
            add("JS或登录页不自动换后端；需在原WebView明确打开，登录由你处理并交还原任务。")
        }
        "browser_task_select_option", "browser_task_set_checked" -> add(runCatching { BrowserFormControls.approvalSummary(capability.child("targetPreview"), args) }.getOrElse { "原控件状态已变化，请拒绝并重新观察。" })
        BrowserVisualCapture.TOOL -> {
            add("把当前已确认网页的真实可见图像发送给模型，可能包含可见个人信息。图像不默认保存，不用于自动授权或坐标点击。")
            add("确认后收起工作区，等同一页回到原显示区域再取一次图。检测到密码、验证码或未经核对的嵌入内容时阻止截图；其他可见个人信息仍请你确认。")
        }
    }
    val embedded = capability.child("readFrame")
    if (embedded.length() > 0) add("只读已展示的嵌入来源：${embedded.optString("sourceOrigin")}。页面或frame变化将拒绝旧引用。")
    val target = capability.child("targetPreview")
    if (target.length() > 0) {
        add("页面标注（未经核实）：${target.optString("text").ifBlank { target.optString("tag") }}")
        if (target.optString("href").isNotBlank()) add(target.getString("href"))
    }
    if (args.optString("condition") == "target") {
        val predicate = args.child("target")
        add("只读等待原已展示区域：${predicate.child("selector").optString("name")}\n状态：${predicate.optString("state")}\n值：${predicate.opt("value") ?: predicate.opt("expected") ?: ""}")
        add("最长 ${args.optInt("timeoutMs", 3000)} 毫秒，目标状态须持续 ${predicate.optInt("stableMs")} 毫秒；不点击或重发提交，页面状态不等于服务器业务成功。")
    }
    if (args.optString("condition") == "barrier") {
        val barrier = args.child("barrier")
        add("只读等待主文档 ${barrier.optString("kind")} 的 ${barrier.optInt("quietMs", 500)} 毫秒安静窗口，最长 ${args.optInt("timeoutMs", 3000)} 毫秒。不会自行开启采集或重放操作；frame、旧请求及未来定时工作仍未知，不证明服务器业务完成。")
    }
    listOf("url" to "目标网页", "text" to "填写内容", "excerpt" to "保留摘录", "condition" to "等待条件", "value" to "等待内容", "dy" to "滚动距离").forEach { (key, label) -> if (args.has(key)) add("$label：${args.get(key)}") }
    add("网页文字与操作回执未经核实；确认仅适用于这次原操作。")
}.joinToString("\n\n")

internal fun browserScopeDetail(read: BrowserReadScope.Consent, actions: BrowserActionScope.Consent): String = buildList {
    add("仅对显示的原任务、原执行和登录资料有效；暂停、接管或更换资料后须重新授权。累计预算不重置，未知操作不重发。")
    if (read.enabled()) add("只读范围最长5分钟、最多32次只读/来源调用、匿名正文累计最多1MiB。仅显示的逐字固定公共URL可匿名读取；来源保留以本次选项为准，不开放点击、输入或导航。")
    if (actions.enabled()) {
        val policy = actions.review()
        add("连续浏览最多 ${policy.optInt("maxActions")} 次，仍受原任务期限和累计预算约束；仅在列出的网站与动作范围内执行。")
        add(if (policy.optBoolean("ordinaryInput")) "普通字段输入已明确选中，可能触发页面联动或自动保存；密码、验证码和文件选择不包括在内。" else "普通字段输入未授权，仍逐次核对。")
        if (policy.array("interactionActions").length() > 0) add("控件交互只包含列出的完整名称/#id和当前文档观察；页面处理器可能联动或自动保存。不是全站按钮授权，提交、敏感与后果性控件不包括在内。")
    }
    add("发送、提交、删除、付款、页面脚本、下载、上传与越界操作仍需精确审阅；网页登录与设备权限由你处理。额外嵌入来源仅限下方明确列出的项，不会重载旧登录页。")
}.joinToString("\n\n")
