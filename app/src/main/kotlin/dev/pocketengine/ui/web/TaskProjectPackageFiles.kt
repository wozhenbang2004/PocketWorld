package dev.pocketengine.ui.web

import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.project.ProjectPackageArchive
import dev.pocketengine.features.workspace.WorkspaceModule
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.workspace.VersionedWorkspace
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pinned package reader. Each issuer verifies its own scope. No path in the live checkout, unpack, renderer or command runner is used. */
internal object TaskProjectPackageFiles {
    const val COMMAND = "tasks.projectPackageFiles"
    private const val PAGE = 24
    private const val TEXT_LIMIT = 65536

    fun read(host: EngineApplication, args: JSONObject, control: RunControl): JSONObject = readPinned(args, control, { task, operation, id, sha ->
        val visible = host.tasks.getForUi(task, false) ?: error("原工作不可读取")
        check(visible.optString("id") == task && visible.optBoolean("contextAvailable", true) &&
            !visible.optBoolean("sourceCurrentReadRequired") && visible.optString("taskProjection") != "source_unavailable") { "原工作来源暂不可读取" }
        host.tasks.ownerPackagePreviewSource(task, operation).also {
            check(it.getString("artifactId") == id && it.getString("artifactSha256") == sha) { "保存操作与所选固定包不同" }
        }
    }, { pinned ->
        val local = host.features.module("local") as? LocalModule ?: error("原项目模块不可用")
        local.sourcePackageForOwner(pinned)
    })

    // Separate native issuer: a Team selection is NOT a private task grant or an editable checkout.
    fun readTeam(host: EngineApplication, selected: TaskStore.TeamProjectPackageSelection, args: JSONObject, control: RunControl): JSONObject =
        readPinned(args, control, { task, op, id, sha ->
            val proof = teamEvidence(selected); val artifact = proof.getJSONObject("packageArtifact")
            check(task == proof.getString("taskId") && op == proof.getString("operationId") && id == artifact.getString("id") && sha == proof.getString("archiveSha256")) { "不是这个原团队签收的固定包" }
            proof.put("artifactId",id).put("artifactSha256",sha).put("epoch",proof.getInt("coordinatorEpoch"))
        }, { pinned ->
            val workspace = host.features.module("workspace") as? WorkspaceModule ?: error("原固定成果仓不可用")
            workspace.originalTaskPackage(pinned) // rechecks actual ZIP bytes and its original native manifest/evidence
        })

    private fun teamEvidence(selected: TaskStore.TeamProjectPackageSelection): JSONObject {
        selected.checkCurrent(); val facts = selected.view(); val delivery = facts.getJSONObject("delivery")
        check(delivery.getBoolean("complete") && delivery.getBoolean("fixedBaselineOnly") && !delivery.getBoolean("currentMainProjectVerified")) { "原团队尚没有已签收的完整固定成果" }
        return facts.getJSONObject("evidence")
    }
    fun teamDescriptor(selected: TaskStore.TeamProjectPackageSelection): JSONObject {
        val evidence=teamEvidence(selected);val artifact=evidence.getJSONObject("packageArtifact")
        check(artifact.getString("mode")=="source_project") { "不是原团队固定源码包" }
        return JSONObject().put("teamRunId",selected.view().getString("teamRunId")).put("taskId",evidence.getString("taskId"))
            .put("operationId",evidence.getString("operationId")).put("runId",evidence.getString("runId")).put("epoch",evidence.getInt("coordinatorEpoch"))
            .put("projectId",evidence.getString("projectId")).put("checkoutId",evidence.getString("checkoutId")).put("mode","source_project")
            .put("artifactId",artifact.getString("id")).put("archiveSha256",evidence.getString("archiveSha256")).put("archiveBytes",evidence.getLong("archiveBytes"))
            .put("sourceDigest",evidence.getString("sourceDigest")).put("manifestIdentity",evidence.getString("manifestIdentity"))
            .put("name",artifact.optString("name","完整项目包")).put("entryPath",artifact.optString("entryPath")).put("fileCount",artifact.getInt("fileCount")).put("readOnly",true)
    }

    private fun readPinned(args: JSONObject, control: RunControl, resolve:(String,String,String,String)->JSONObject, archive:(JSONObject)->ProjectPackageArchive.Frozen): JSONObject {
        val member = args.has("path")
        val keys = setOf("taskId", "operationId", "artifactId", "artifactSha256") +
            if (member) setOf("path", "fileSha256") else setOf("offset", "query")
        require(args.keys().asSequence().toSet() == keys) { "固定包文件选择不完整" }
        fun text(name: String, max: Int) = (args.opt(name) as? String)?.takeIf { it.length <= max && '\u0000' !in it }
            ?: error("固定包文件选择无效")
        val task = text("taskId", 80); val operation = text("operationId", 80)
        val id = text("artifactId", 80); val sha = text("artifactSha256", 64)
        val path = if (member) VersionedWorkspace.path(text("path", 240)) else ""
        val fileSha = if (member) text("fileSha256", 64) else ""
        val query = if (member) "" else text("query", 120)
        val offset = if (member) 0 else (args.opt("offset") as? Int)?.takeIf { it in 0 until 2000 && it % PAGE == 0 }
            ?: error("原文件清单页码无效")
        fun source(): JSONObject {control.check();return resolve(task,operation,id,sha)}
        val before = source()
        val frozen = archive(before)
        val manifest = frozen.manifest()
        check(manifest.getString("mode") == "source_project") { "这不是所选源码包" }
        val files = manifest.getJSONArray("files")
        val result = JSONObject().put("taskId", task).put("operationId", operation).put("artifactId", id).put("artifactSha256", sha)
            .put("projectId", before.getString("projectId")).put("checkoutId", before.getString("checkoutId"))
            .put("runId", before.getString("runId")).put("epoch", before.getInt("epoch")).put("readOnly", true)
        if (member) {
            val file = (0 until files.length()).asSequence().map { files.getJSONObject(it) }.firstOrNull { it.getString("path") == path }
                ?: error("这份原固定包未收录所选文件")
            check(file.getString("sha256") == fileSha && file.getString("kind") == "text" && file.getLong("bytes") in 0..TEXT_LIMIT.toLong()) {
                "这不是所选固定版本中可读取的小文本，不会作为正文打开"
            }
            val bytes = frozen.file(path)
            val content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            result.put("path", path).put("fileSha256", fileSha).put("bytes", bytes.size).put("text", content)
        } else {
            val needle = query.lowercase(Locale.ROOT)
            val matching = (0 until files.length()).asSequence().map { files.getJSONObject(it) }
                .filter { it.getString("path").lowercase(Locale.ROOT).contains(needle) }.toList()
            require(offset == 0 || offset < matching.size) { "文件清单读取位置超出所选范围" }
            val entries = JSONArray()
            matching.drop(offset).take(PAGE).forEach { file ->
                entries.put(JSONObject().put("path", file.getString("path")).put("kind", file.getString("kind"))
                    .put("bytes", file.getLong("bytes")).put("sha256", file.getString("sha256")))
            }
            result.put("offset", offset).put("query", query).put("matched", matching.size).put("total", files.length()).put("entries", entries)
        }
        check(TaskStore.capabilityFingerprint(before) == TaskStore.capabilityFingerprint(source())) { "原工作或固定包来源已变化" }
        return result
    }
}
