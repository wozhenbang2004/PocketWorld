package dev.pocketengine.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

/** A live Activity's exact document choice. It contains no package review or installation authority. */
internal class FunctionalPackDocumentPicker(
    private val registry: ActivityResultRegistry,
    private val resolver: ContentResolver,
    private val businessCurrent: () -> Boolean,
    private val businessGeneration: () -> Long
) : AutoCloseable {
    private data class Choice(val id: String, val generation: Long, val status: String, val uri: Uri? = null)
    private val lock = Any()
    private var choice: Choice? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null

    fun snapshot(): JSONObject = synchronized(lock) {
        val original = choice?.takeIf { it.generation == businessGeneration() }
        JSONObject().put("status", original?.status ?: "idle").put("requestId", original?.id ?: JSONObject.NULL)
    }

    fun choose(): JSONObject {
        check(businessCurrent()) { "请在当前私人工作台选择功能包" }
        val original = synchronized(lock) {
            check(choice?.status != "pending") { "原文件选择尚未结束，请返回原系统选择器" }
            launcher?.unregister(); launcher = null
            Choice(UUID.randomUUID().toString(), businessGeneration(), "pending").also { choice = it }
        }
        var delivered = false
        val selected = registry.register("functional_pack_${original.id}", ActivityResultContracts.OpenDocument()) { uri ->
            delivered = true
            synchronized(lock) {
                if (choice?.id == original.id) {
                    launcher?.unregister(); launcher = null
                    choice = if (!businessCurrent() || original.generation != businessGeneration()) null
                    else original.copy(status = when { uri == null -> "cancelled"; uri.scheme == "content" -> "selected"; else -> "unavailable" }, uri = uri?.takeIf { it.scheme == "content" })
                }
            }
        }
        if (delivered) selected.unregister() else {
            synchronized(lock) { launcher = selected }
            try { selected.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
            catch (_: RuntimeException) {
                selected.unregister()
                synchronized(lock) { if (choice?.id == original.id) { launcher = null; choice = original.copy(status = "unavailable") } }
            }
        }
        return snapshot()
    }

    fun open(requestId: String): InputStream {
        val uri = synchronized(lock) {
            val original = choice
            check(businessCurrent() && original != null && original.id == requestId && original.generation == businessGeneration() && original.status == "selected") { "原功能包文件选择已变化，请重新选择" }
            original.uri ?: error("原文件不可读取，请重新选择")
        }
        return resolver.openInputStream(uri) ?: error("原文件不可读取，请重新选择")
    }

    fun discard(requestId: String) = synchronized(lock) {
        if (choice?.id == requestId) { launcher?.unregister(); launcher = null; choice = null }
    }

    override fun close() = synchronized(lock) { launcher?.unregister(); launcher = null; choice = null }
}
