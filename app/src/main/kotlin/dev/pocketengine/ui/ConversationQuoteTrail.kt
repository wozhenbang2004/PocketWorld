package dev.pocketengine.ui

import dev.pocketengine.features.assistant.OwnerSourceReferences
import org.json.JSONArray
import org.json.JSONObject

/** Metadata from an already opened owner history window. No source lookup or content is retained here. */
internal class ConversationQuoteTrail private constructor(private val originals: Map<String, Original>) {
    constructor() : this(emptyMap())

    private data class Range(val id: String, val revision: Long, val digest: String, val offset: Int, val chars: Int)
    private data class Original(val generation: String, val revision: Long, val digest: String, val length: Int, val selected: List<Range>)

    fun update(conversation: String, page: List<JSONObject>, visible: Set<String>, earlier: Boolean): ConversationQuoteTrail {
        val kept = if (earlier) originals.filterKeys { it in visible }.toMutableMap() else mutableMapOf()
        for (row in page) {
            val id = row.opt("id") as? String ?: continue
            kept.remove(id)
            if (id !in visible || row.optBoolean("importAssignedIdentity") || row.opt("role") != "user" && row.opt("role") != "assistant") continue
            val pin = row.optJSONObject("ownerOriginalLocator") ?: continue
            val revision = exact(row.opt("revision")) ?: continue
            val generation = (pin.opt("generation") as? String)?.takeIf { it.isNotBlank() } ?: continue
            val digest = (pin.opt("fingerprint") as? String)?.takeIf { SHA.matches(it) } ?: continue
            val length = (row.opt("content") as? String)?.length ?: continue
            if (pin.opt("kind") != "owner_native_original" || pin.opt("conversationId") != conversation ||
                pin.opt("recordId") != id || exact(pin.opt("recordVersion")) != revision) continue
            val refs = row.optJSONArray("ownerSourceRefs")
            val selected = if (refs == null || refs.length() == 0 || ReaderInputWork.fromMessage(row, conversation) == null) emptyList() else
                runCatching {
                    val checked = OwnerSourceReferences.checked(refs)
                    (0 until checked.length()).mapNotNull { i ->
                        val range = checked.getJSONObject(i)
                        if (range.getString("generation") != generation || range.getString("sourceConversationId") != conversation) return@mapNotNull null
                        val source = range.getJSONObject("sourceRef")
                        Range(source.getString("sourceId"), source.getLong("revision"), range.getString("bodyFingerprint"), range.getInt("offset"), range.getInt("chars"))
                    }
                }.getOrNull().orEmpty()
            kept[id] = Original(generation, revision, digest, length, selected)
        }
        return ConversationQuoteTrail(kept)
    }

    /** For an explicit new draft review only. Both sides must still be in the retained native page.
     * Never reconstruct the source from a Web locator, message text, or a past task's grant. */
    fun registeredLocator(conversation: String, request: String, requestRevision: Long,
                          target: String, revision: Long, offset: Int, chars: Int): JSONObject? {
        val input = originals[request] ?: return null
        val source = originals[target] ?: return null
        if (input.revision != requestRevision || source.revision != revision || input.generation != source.generation ||
            chars !in 1..4000 || offset < 0 || offset > source.length - chars ||
            input.selected.none { it.id == target && it.revision == revision && it.digest == source.digest && it.offset == offset && it.chars == chars }) return null
        return JSONObject().put("kind", "owner_native_original").put("generation", source.generation).put("conversationId", conversation)
            .put("recordId", target).put("recordVersion", revision).put("fingerprint", source.digest)
    }

    fun ranges(messageId: String?): JSONArray? {
        val input = messageId?.let(originals::get) ?: return null
        if (input.selected.isEmpty()) return null
        return JSONArray().also { result ->
            for (range in input.selected) {
                val entry = JSONObject().put("revision", range.revision).put("offset", range.offset).put("chars", range.chars)
                val source = originals[range.id]
                if (source != null && source.generation == input.generation && source.revision == range.revision &&
                    source.digest == range.digest && range.offset <= source.length - range.chars) entry.put("visibleMessageId", range.id)
                // Outside this retained page even the source ID is not sent to Web.
                result.put(entry)
            }
        }
    }

    private companion object {
        val SHA = Regex("sha256:[a-f0-9]{64}")
        fun exact(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }?.takeIf { it > 0 }
    }
}
