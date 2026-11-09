package dev.pocketengine.ui.web

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.remoteinbox.RemoteInboxJobService
import dev.pocketengine.features.remoteinbox.RemoteInboxReceiveService
import dev.pocketengine.features.remoteinbox.RemoteInboxStore
import dev.pocketengine.features.remoteinbox.RemoteInboxSync
import dev.pocketengine.ui.PocketWorldController
import java.util.concurrent.Executors
import org.json.JSONObject

/** Owner-only display adapter. A remote message has no task/model/tool authority. */
internal class PocketWebRemoteInbox(
    private val controller: PocketWorldController,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf(
            "remoteInbox.state", "remoteInbox.configure", "remoteInbox.sync",
            "remoteInbox.mark", "remoteInbox.open", "remoteInbox.startLive", "remoteInbox.stopLive",
            "remoteInbox.disconnect", "remoteInbox.notificationPermission"
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
                } else done(JSONObject().put("status", "granted"), null)
            }
            "remoteInbox.configure" -> {
                fields("endpoint", "token")
                store().configure(string("endpoint", 512), string("token", 2048))
                activity.stopService(Intent(activity, RemoteInboxReceiveService::class.java))
                store().live(false)
                RemoteInboxJobService.schedule(activity)
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.disconnect" -> {
                fields()
                store().disconnect()
                activity.stopService(Intent(activity, RemoteInboxReceiveService::class.java))
                RemoteInboxJobService.cancel(activity)
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.open" -> {
                fields("seq")
                val raw = args.opt("seq")
                require(raw is Number && raw.toLong() > 0) { "通知序号无效" }
                val seq = raw.toLong()
                val event = store().find(seq)
                val url = android.net.Uri.parse(event.getString("url"))
                check(url.scheme == "https" && url.host != null &&
                    (!event.getString("kind").startsWith("github_") || url.host == "github.com")) { "通知链接无效" }
                val view = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
                activity.startActivity(view)
                store().read(seq)
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.mark" -> {
                fields("seq")
                val raw = args.opt("seq")
                require(raw is Number && raw.toLong() > 0 && raw.toLong().toDouble() == raw.toDouble()) { "通知序号无效" }
                store().read(raw.toLong())
                done(snapshot(), null)
                changed()
            }
            "remoteInbox.startLive" -> {
                fields()
                check(store().configured()) { "请先配置 HTTPS 消息服务" }
                val manager = activity.getSystemService(android.app.NotificationManager::class.java)
                check(manager != null && manager.areNotificationsEnabled() &&
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
