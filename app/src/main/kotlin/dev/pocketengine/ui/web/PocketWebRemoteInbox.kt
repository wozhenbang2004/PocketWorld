package dev.pocketengine.ui.web

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.remoteinbox.RemoteInboxNotifications
import dev.pocketengine.features.remoteinbox.RemoteInboxJobService
import dev.pocketengine.features.remoteinbox.RemoteInboxReceiveService
import dev.pocketengine.features.remoteinbox.RemoteInboxStore
import dev.pocketengine.features.remoteinbox.RemoteInboxPush
import dev.pocketengine.features.remoteinbox.RemoteInboxSync
import dev.pocketengine.ui.PocketWorldController
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONArray

/** Owner-only display adapter. A remote message has no task/model/tool authority. */
internal class PocketWebRemoteInbox(
    private val controller: PocketWorldController,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf(
            "remoteInbox.state", "remoteInbox.configure", "remoteInbox.sync",
            "remoteInbox.mark", "remoteInbox.open", "remoteInbox.startLive", "remoteInbox.stopLive",
            "remoteInbox.disconnect", "remoteInbox.notificationPermission", "remoteInbox.notificationSettings", "remoteInbox.update", "remoteInbox.schedule", "remoteInbox.enablePush", "remoteInbox.disablePush"
        )
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var generation = 0L

    private val activity get() = controller.webPresentationActivity()
    private fun store() = RemoteInboxStore.get(activity)
    private val storeObserver = Runnable { main.post { if (!closed) changed() } }
    init { store().observe(storeObserver) }
    fun snapshot(): JSONObject {
        if (!controller.webPrivateOwnerVisible()) return JSONObject().put("available", false)
        return try {
            store().snapshot().put("available", true)
                .put("running", RemoteInboxReceiveService.isRunning())
                .put("push", RemoteInboxPush.snapshot(activity))
                .put("backgroundScheduled", RemoteInboxJobService.isScheduled(activity))
                .put("notificationPermission", RemoteInboxNotifications.permissionState(activity))
        } catch (failure: Exception) {
            JSONObject().put("available", true).put("error", "远程通知存储暂不可读取，请检查本机密钥或备份")
        }
    }

    fun clear() { generation++ }
    fun close() { closed = true; generation++; store().unobserve(storeObserver); worker.shutdownNow() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(!closed && controller.webPrivateOwnerVisible()) { "仅可在本机主人界面管理远程消息" }
        fun fields(vararg accepted: String) {
            require(args.keys().asSequence().all { it in accepted.toSet() }) { "通知操作含有未授权参数" }
        }
        fun string(name: String, max: Int): String {
            val raw = args.opt(name)
            require(raw is String && raw.length <= max) { "通知配置字段无效" }
            return raw
        }
        when (action) {
            "remoteInbox.state" -> {
                fields()
                done(snapshot(), null)
            }
            "remoteInbox.notificationPermission" -> {
                fields()
                if (Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 30218)
                    done(JSONObject().put("status", "permission_requested"), null)
                } else done(JSONObject().put("status", "permission_available").put("permission", RemoteInboxNotifications.permissionState(activity)), null)
            }
            "remoteInbox.notificationSettings" -> {
                fields()
                activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
                done(JSONObject().put("status", "settings_opened"), null)
            }
            "remoteInbox.schedule" -> {
                fields()
                check(store().configured()) { "请先配置 HTTPS 消息服务" }
                RemoteInboxJobService.schedule(activity)
                RemoteInboxJobService.requestCatchUp(activity)
                done(snapshot(), null); changed()
            }
            "remoteInbox.update" -> {
                fields("configId", "items", "field", "value")
                val raw = args.opt("value")
                require(raw is Boolean) { "通知状态无效" }
                val identity = string("configId", 80)
                val items = args.getJSONArray("items")
                val updated = store().updateAttention(identity, items, string("field", 20), raw)
                if (raw) for (index in 0 until updated.length()) RemoteInboxNotifications.dismiss(activity, identity, updated.getJSONObject(index))
                done(snapshot(), null); changed()
            }
            "remoteInbox.configure" -> {
                fields("endpoint", "token")
                val previousIdentity = store().configId()
                val endpoint = string("endpoint", 512)
                val token = string("token", 2048)
                if (!store().matchesConfiguration(endpoint, token)) RemoteInboxPush.disable(activity)
                store().configure(endpoint, token)
                if (previousIdentity != store().configId()) RemoteInboxNotifications.dismissConnection(activity, previousIdentity)
                activity.stopService(Intent(activity, RemoteInboxReceiveService::class.java))
                store().live(false)
                RemoteInboxJobService.schedule(activity)
                RemoteInboxJobService.requestCatchUp(activity)
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.disconnect" -> {
                fields()
                val previousIdentity = store().configId()
                RemoteInboxPush.disable(activity)
                store().disconnect()
                RemoteInboxNotifications.dismissConnection(activity, previousIdentity)
                activity.stopService(Intent(activity, RemoteInboxReceiveService::class.java))
                RemoteInboxJobService.cancel(activity)
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.open", "remoteInbox.mark" -> {
                fields("configId", "seq", "source_key")
                val raw = args.opt("seq")
                require((raw is Int || raw is Long) && (raw as Number).toLong() in 1..9007199254740991L) { "通知序号无效" }
                val seq = (raw as Number).toLong()
                val identity = string("configId", 80)
                val key = string("source_key", 180)
                val event = store().requireEvent(identity, seq, key)
                if (action == "remoteInbox.open") {
                    val url = android.net.Uri.parse(event.getString("url"))
                    check(url.scheme == "https" && url.host != null && url.userInfo == null && url.fragment == null &&
                        (!event.getString("kind").startsWith("github_") || url.host.equals("github.com", ignoreCase = true))) { "通知链接无效" }
                    activity.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                }
                store().updateAttention(identity, JSONArray().put(JSONObject().put("seq", seq).put("source_key", key)), "read", true)
                RemoteInboxNotifications.dismiss(activity, identity, event)
                done(snapshot(), null); changed()
            }
            "remoteInbox.startLive" -> {
                fields()
                check(store().configured()) { "请先配置 HTTPS 消息服务" }
                val manager = activity.getSystemService(android.app.NotificationManager::class.java)
                check(manager != null && RemoteInboxNotifications.channelEnabled(activity, RemoteInboxNotifications.SERVICE) && manager.areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < 33 ||
                        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                    "请先允许 PocketWorld 显示系统通知"
                }
                store().live(true)
                try {
                    activity.startForegroundService(Intent(activity, RemoteInboxReceiveService::class.java)
                        .setAction(RemoteInboxReceiveService.START))
                } catch (failure: Exception) {
                    store().live(false)
                    throw failure
                }
                done(JSONObject().put("status", "start_requested").put("state", snapshot()), null)
                changed()
            }
            "remoteInbox.stopLive" -> {
                fields()
                if (store().configured()) store().live(false)
                activity.stopService(Intent(activity, RemoteInboxReceiveService::class.java))
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.disablePush" -> {
                fields()
                RemoteInboxPush.disable(activity)
                done(snapshot(), null); changed()
            }
            "remoteInbox.enablePush" -> {
                fields("configId", "distributor", "registrationToken")
                val identity = string("configId", 80)
                val origin = synchronized(store()) {
                    check(identity == store().configId()) { "通知连接已变化，登记凭据没有发送，请重新打开设置" }
                    store().url()
                }
                val distributor = string("distributor", 200)
                val token = string("registrationToken", 2048)
                val captured = generation
                worker.execute {
                    val failure = try {
                        RemoteInboxPush.enable(activity.applicationContext, distributor, token, identity, origin)
                        null
                    } catch (error: Exception) { error.message ?: "推送登记失败，请核对服务配置" }
                    main.post {
                        if (!closed && captured == generation) {
                            done(if (failure == null) snapshot() else null, failure)
                            changed()
                        } else done(null, "当前通知界面已变化，请重新查看登记状态")
                    }
                }
            }
            "remoteInbox.sync" -> {
                fields()
                val captured = generation
                worker.execute {
                    val result = try {
                        RemoteInboxSync.run(activity.applicationContext)
                    } catch (failure: Exception) {
                        JSONObject().put("status", "failed")
                            .put("message", failure.message ?: "同步失败，请检查网络和服务配置")
                    }
                    main.post {
                        if (!closed && captured == generation) {
                            done(result.put("state", snapshot()), null)
                            changed()
                        } else done(null, "当前通知界面已变化，请重新读取本机记录")
                    }
                }
            }
            else -> error("通知操作不受支持")
        }
    }
}
