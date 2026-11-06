package dev.pocketengine.ui.web

import android.content.ComponentCallbacks
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.UiAppearance
import dev.pocketengine.ui.UiTypography
import java.util.concurrent.Executors
import org.json.JSONObject

/** The same local appearance preference and system TTS used by the native UI. */
internal class PocketWebPresentation(
    private val activity: ComposeActivity,
    private val changed: () -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val preferences = UiAppearance.preferences(activity)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    private var saving = false
    private var error: String? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == UiAppearance.KEY || key == UiTypography.KEY) main.post { refreshAppearance() }
    }
    private val configurationListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(configuration: Configuration) { main.post { refreshAppearance() } }
        override fun onLowMemory() = Unit
    }

    companion object {
        val COMMANDS = setOf("settings.appearance", "settings.typography", "speech.prepare", "speech.openSettings")
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) return
        active = true
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        activity.application.registerComponentCallbacks(configurationListener)
        UiAppearance.window(activity)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("mode", UiAppearance.mode(preferences)).put("dark", UiAppearance.dark(activity))
        .put("typography", UiTypography.snapshot(preferences))
        .put("saving", saving).put("error", error ?: JSONObject.NULL)

    fun speechInfo(): JSONObject = activity.media.systemSpeechInfo()

    private fun refreshAppearance() {
        if (!active || activity.isDestroyed || activity.isFinishing) return
        UiAppearance.window(activity)
        changed()
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(active && !activity.isDestroyed && !activity.isFinishing) { "原设置窗口已关闭" }
        when (action) {
            "settings.appearance" -> {
                require(args.keys().asSequence().toSet() == setOf("mode")) { "外观设置参数不完整" }
                val mode = args.opt("mode")
                require(mode is String && mode in setOf("system", "light", "dark")) { "请选择跟随系统、浅色或深色" }
                check(!saving) { "请等待原外观设置回执" }
                saving = true
                error = null
                changed()
                try {
                    io.execute {
                        val result = runCatching {
                            check(active) { "原设置窗口已关闭" }
                            check(preferences.edit().putString(UiAppearance.KEY, mode).commit()) { "外观设置的本机保存未确认，请重试" }
                        }
                        main.post {
                            saving = false
                            error = result.exceptionOrNull()?.message
                            if (active) {
                                refreshAppearance()
                                done(if (result.isSuccess) snapshot().put("saved", true) else null, error)
                            }
                        }
                    }
                } catch (failure: Exception) {
                    saving = false
                    error = failure.message ?: "外观设置未保存"
                    changed()
                    done(null, error)
                }
            }
            "settings.typography" -> {
                val selection = UiTypography.validate(args)
                check(!saving) { "请等待原展示设置回执" }
                saving = true
                error = null
                changed()
                try {
                    io.execute {
                        val result = runCatching {
                            check(active) { "原设置窗口已关闭" }
                            UiTypography.save(preferences, selection)
                        }
                        main.post {
                            saving = false
                            error = result.exceptionOrNull()?.message
                            if (active) {
                                refreshAppearance()
                                done(if (result.isSuccess) snapshot().put("saved", true) else null, error)
                            }
                        }
                    }
                } catch (failure: Exception) {
                    saving = false
                    error = failure.message ?: "字体设置未保存"
                    changed()
                    done(null, error)
                }
            }
            "speech.prepare" -> {
                require(args.length() == 0) { "准备系统中文朗读不接受其他参数" }
                // No recording or playback. The existing engine emits its real readiness later.
                activity.media.prepareSpeech()
                done(speechInfo(), null)
            }
            "speech.openSettings" -> {
                require(args.length() == 0) { "系统语音设置不接受其他参数" }
                // Some devices omit this entry; the system settings fallback stays user-operated.
                val location = try {
                    activity.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    "text_to_speech"
                } catch (_: android.content.ActivityNotFoundException) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                    "system_settings"
                } catch (_: SecurityException) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                    "system_settings"
                }
                done(JSONObject().put("opened", location), null)
            }
            else -> error("未知的外观或系统朗读操作")
        }
    }

    override fun close() {
        if (!active) return
        active = false
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        activity.application.unregisterComponentCallbacks(configurationListener)
        io.shutdown()
    }
}
