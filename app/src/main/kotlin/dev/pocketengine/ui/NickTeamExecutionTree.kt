package dev.pocketengine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Only renders the original WorkPlanPage snapshot. JSON has no execution or approval authority. */
@Composable
internal fun NickTeamExecutionTree(tree: JSONObject?, teamRunId: String) {
    if (tree == null) return // Older snapshots remain readable through their existing work-item controls.
    val steps = nickTeamSteps(tree, teamRunId)
    if (steps == null) {
        Text("执行过程暂不可用，原工作项仍可查看。", Modifier.fillMaxWidth(),
            color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        NickExecutionTree(steps)
        if (tree.optBoolean("partial")) Text("仅展示最近的工具记录，可打开工作项查看完整过程。",
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

internal fun nickTeamSteps(tree: JSONObject, expectedTeamRunId: String): List<NickStepData>? {
    if (expectedTeamRunId.isBlank() || tree.optString("format") != "pocketworld.team-execution-tree.v1" ||
        tree.optString("teamRunId") != expectedTeamRunId) return null
    val roots = tree.optJSONArray("roots") ?: return null
    val ids = HashSet<String>()
    val result = ArrayList<NickStepData>()
    for (i in 0 until roots.length()) {
        val root = roots.optJSONObject(i) ?: return null
        val id = root.optString("id")
        if (id.isBlank() || !ids.add(id) || root.optString("kind") != "work_item" ||
            root.optString("workItemId") != id || !root.isNull("parentId")) return null
        val children = root.optJSONArray("children") ?: return null
        val childIds = HashSet<String>()
        val substeps = ArrayList<NickSubstepData>()
        for (j in 0 until children.length()) {
            val child = children.optJSONObject(j) ?: return null
            val childId = child.optString("id")
            if (childId.isBlank() || !childIds.add(childId) || child.optString("kind") != "operation" ||
                child.optString("parentId") != id || child.optString("assignmentId") != root.optString("assignmentId")) return null
            val childState = when (child.optString("state")) {
                "awaiting_admission" -> NickStepState.WAITING
                "running" -> NickStepState.RUNNING
                "settled", "owner_reconciled" -> NickStepState.RETURNED
                else -> NickStepState.UNKNOWN
            }
            substeps += NickSubstepData(childId, child.optString("title"), childState, child.optString("stateLabel"))
        }
        val state = root.optString("state")
        val visualState = when (state) {
            "recorded_delivered" -> NickStepState.COMPLETE
            "contributed" -> NickStepState.RETURNED
            "working" -> NickStepState.RUNNING
            "awaiting_owner_approval" -> NickStepState.LOCKED
            "awaiting_owner", "awaiting_input", "waiting_dependency", "blocked_dependency", "paused" -> NickStepState.WAITING
            "queued", "not_started" -> NickStepState.PENDING
            "failed" -> NickStepState.ERROR
            "cancelled", "interrupted" -> NickStepState.STOPPED
            else -> NickStepState.UNKNOWN
        }
        val badge = when (state) {
            "contributed" -> "待整合"
            "awaiting_owner_approval" -> "需确认"
            "awaiting_owner", "awaiting_input" -> "待回答"
            "waiting_dependency", "blocked_dependency" -> "待前置"
            "paused" -> "已暂停"
            "awaiting_reconciliation" -> "待核对"
            "failed" -> "失败"
            "cancelled" -> "已停止"
            "interrupted" -> "已中断"
            else -> null
        }
        // v1 has no stage timings. Do not derive elapsed time from preparedAt, createdAt or the wall clock.
        result += NickStepData(id, root.optString("title"), visualState, durationMillis = null,
            children = substeps, gateLabel = badge, stateLabel = root.optString("stateLabel"))
    }
    return result
}
