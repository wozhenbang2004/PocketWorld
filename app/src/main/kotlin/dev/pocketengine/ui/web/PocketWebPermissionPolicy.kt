package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Trusted private owner UI only. The model/tool command surface does not expose these writes. */
internal object PocketWebPermissionPolicy {
    val COMMANDS = setOf("permissions.snapshot", "permissions.save", "permissions.catalog")
    fun dispatch(controller: PocketWorldController, action: String, args: JSONObject): JSONObject {
        check(controller.webPrivateOwnerVisible()) { "请回到私人对话设置执行权限" }
        val conversation = controller.state.conversationId.orEmpty()
        val settings = controller.nativeTokenBudgetHost().settings
        if (action == "permissions.catalog") {
            require(args.length() == 0)
            val app = controller.nativeTokenBudgetHost()
            return dev.pocketengine.features.capabilities.PermissionToolCatalog.snapshot(app.features, app.tasks)
        }
        if (action == "permissions.snapshot") {
            require(args.length() == 0)
            return settings.permissionPolicySnapshot(conversation).put("conversationId", conversation)
        }
        require(action == "permissions.save")
        require(args.keys().asSequence().all { it in setOf("conversationId", "global", "policy", "expectedRevision") })
        check(args.getString("conversationId") == conversation) { "对话已切换，请重新打开权限设置" }
        val revision = args.get("expectedRevision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() >= 0) { "请重新读取权限设置" }
        require(args.get("global") is Boolean)
        require(args.has("policy"))
        val policy = if (args.isNull("policy")) null else args.getJSONObject("policy")
        return settings.savePermissionPolicy(conversation, args.getBoolean("global"), policy, (revision as Number).toLong())
            .put("conversationId", conversation)
    }
}
