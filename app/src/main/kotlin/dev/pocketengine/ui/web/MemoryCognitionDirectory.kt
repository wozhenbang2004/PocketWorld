package dev.pocketengine.ui.web

import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Temporary read layout for one explicit private subject. No record bodies are stored in a page cursor. */
internal object MemoryCognitionDirectory {
    private const val PAGE_SIZE = 24
    internal const val MAX_REVIEW = 20 // MemoryBook.resolveAssertionConflictReviewed's existing write ceiling.
    data class Conflict(val all: List<JSONObject>, val visible: List<JSONObject>)
    private data class UnitOfRead(val ids: List<String>, val group: Conflict? = null)
    data class Page(val visible: Set<String>, val conflicts: List<Conflict>, val number: Int, val pages: Int,
                    val count: Int, val version: String, val current: List<JSONObject>, val history: List<JSONObject>, val pending: List<JSONObject>)
    fun group(row: JSONObject): List<String> = row.getJSONObject("assertion").let { a ->
        listOf(row.getString("ownerId"), row.getString("spaceId"), a.getString("subjectId"), a.getString("predicate"), a.getString("condition"))
    }
    private fun rows(a: JSONArray): List<JSONObject> = (0 until a.length()).map(a::getJSONObject)
    private fun id(row: JSONObject) = row.getString("id")
    // The oversized-group pin keeps only the visible bodies plus this digest for the whole original set.
    fun groupVersion(members: List<JSONObject>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (row in members.sortedBy(::id)) {
            val bytes = row.toString().toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun page(value: JSONObject, number: Int): Page {
        val scope = value.getJSONObject("scope"); val subject = value.getString("subjectId")
        check(value.getString("format") == "pocketworld.memory-cognition-view.v1" && scope.getString("ownerId") == "owner" && scope.getString("spaceId") == "private")
        val current = rows(value.getJSONArray("current")); val history = rows(value.getJSONArray("history")); val pending = rows(value.getJSONArray("pendingConflicts"))
        val source = listOf(current, history, pending)
        check(source.all { bucket -> bucket.map(::id).toSet().size == bucket.size && bucket.all { group(it).take(3) == listOf("owner", "private", subject) && it.getLong("revision") > 0 } })
        val currentIds = current.map(::id).toSet(); val historyIds = history.map(::id).toSet()
        check(currentIds.intersect(historyIds).isEmpty() && pending.none { id(it) in historyIds || (id(it) in currentIds) != (it.optString("reviewRole") == "protected_current") })
        val units = mutableListOf<UnitOfRead>()
        for (members in pending.groupBy(::group).values) {
            // A reviewable conflict never straddles pages. A larger group is for evidence only, never a partial decision.
            for (part in members.chunked(PAGE_SIZE)) units += UnitOfRead(part.map(::id), Conflict(members, part))
        }
        val pendingIds = pending.map(::id).toSet()
        val withoutConflicts = (current + history).filter { id(it) !in pendingIds }
        // Keep a past decision's versions together when present, rather than separating its kept and historical rows.
        val families = withoutConflicts.groupBy { row ->
            val refs = row.optJSONObject("assertionResolution")?.optJSONArray("records")
            if (refs == null || refs.length() !in 1..MAX_REVIEW) listOf(id(row))
            else group(row) + rows(refs).sortedBy(::id).flatMap { listOf(id(it), it.getLong("revision").toString()) }
        }
        for (family in families.values) for (part in family.chunked(PAGE_SIZE)) units += UnitOfRead(part.map(::id))
        val pages = mutableListOf<MutableList<UnitOfRead>>(mutableListOf()); var used = 0
        for (unit in units) {
            if (used > 0 && used + unit.ids.size > PAGE_SIZE) { pages.add(mutableListOf()); used = 0 }
            pages.last().add(unit); used += unit.ids.size
        }
        require(number in pages.indices) { "原主体目录页已变化，请重新读取" }
        val selected = pages[number]; val visible = selected.flatMap { it.ids }.toSet()
        // An id/version, classification, change of order, or change of cohort may not silently shift a page under its cursor.
        val stamp = JSONArray().put(subject).put(JSONArray(source.map { bucket -> JSONArray(bucket.map { row ->
            JSONArray().put(id(row)).put(row.getLong("revision")).put(row.getString("assertionStatus")).put(row.optString("reviewRole"))
        }) })).put(JSONArray(pages.map { part -> JSONArray(part.map { JSONArray(it.ids) }) }))
        val digest = MessageDigest.getInstance("SHA-256").digest(stamp.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return Page(visible, selected.mapNotNull { it.group }, number, pages.size, units.sumOf { it.ids.size }, digest,
            current.filter { id(it) in visible }, history.filter { id(it) in visible }, pending.filter { id(it) in visible })
    }
}
