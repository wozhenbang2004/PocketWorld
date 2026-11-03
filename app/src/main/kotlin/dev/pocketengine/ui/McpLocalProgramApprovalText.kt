package dev.pocketengine.ui

import org.json.JSONObject

/** Disclosure for the existing concrete approval, not a new approval object or permission gate. */
internal object McpLocalProgramApprovalText {
    @JvmStatic fun describe(capability: JSONObject): String? {
        val program = capability.optJSONObject("nativeProgram") ?: return null
        if (capability.optString("feature") != "plugins" || capability.optString("command") != "invoke" || program.optString("purpose") != "native_mcp_invoke") return null
        return "本机程序：${program.optString("executable")} · ${program.optString("entryPoint")}\n" +
            "启动本身可能产生效果；App 同 UID 不是 OS 安全沙箱。本次独立可见副本的相对写入不自动回写原项目，不保证跨调用目录状态。\n" +
            "源 ${program.optString("sourceDigest").take(12)}… · 配置 ${program.optString("configurationDigest").take(12)}…；完整参数、程序与运行环境摘要见下方操作详情。"
    }
}
