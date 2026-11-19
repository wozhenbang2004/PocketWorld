package dev.pocketengine.ui

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Each explicit source choice owns a distinct system result registration.
 * A delayed or restored result cannot become the document for a later choice. */
internal class OwnerSourceDocumentPicker(private val registry: ActivityResultRegistry,
    private val activeOwner: () -> Boolean, private val generation: () -> Long) {
    private var selected: String? = null
    private var launcher: ActivityResultLauncher<*>? = null

    fun choose(id: String, folder: Boolean, result: (String, Uri?) -> Unit) {
        check(activeOwner() && selected == null) { "原文件选择尚未结束" }
        require(id.matches(Regex("[a-f0-9-]{36}")))
        if (folder) launch(id, ActivityResultContracts.OpenDocumentTree(), null, result)
        else launch(id, ActivityResultContracts.OpenDocument(), arrayOf("application/zip", "application/octet-stream"), result)
    }
    private fun <I> launch(id: String, contract: ActivityResultContract<I, Uri?>, input: I, result: (String, Uri?) -> Unit) {
        val owner = generation(); selected = id
        try {
            val current = registry.register("owner_local_source_$id", contract) { uri ->
                if (selected == id) {
                    cancel(id)
                    if (activeOwner() && generation() == owner) result(id, uri)
                }
            }
            if (selected != id) { current.unregister(); return }
            launcher = current; current.launch(input)
        } catch (e: RuntimeException) { cancel(id); throw e }
    }
    fun cancel(id: String?) {
        if (id == null || id != selected) return
        selected = null; launcher?.unregister(); launcher = null
    }
    fun close() = cancel(selected)
}
