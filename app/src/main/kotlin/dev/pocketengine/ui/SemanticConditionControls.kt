package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.R
import org.json.JSONObject

/** Place inside the original native proposal review; the parent sends its real consent value. */
@Composable
internal fun SemanticConditionUseConsent(condition: JSONObject, consent: Boolean, busy: Boolean, change: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(condition.optString("goal"), fontSize = 14.sp, lineHeight = 22.sp)
        Text(condition.optString("sourceKey"), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        Text(stringResource(R.string.semantic_condition_scope), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, change, enabled = !busy)
            Text(stringResource(R.string.semantic_condition_consent), fontSize = 13.sp, lineHeight = 21.sp)
        }
    }
}

/** Existing native plan review only. Values are display/selection; Book revalidates every CAS. */
@Composable
internal fun SemanticConditionControls(rule: JSONObject, plans: List<JSONObject>, busy: Boolean, send: (String, JSONObject) -> Unit) {
    val condition = rule.optJSONObject("semanticCondition") ?: return
    val id = rule.optString("id")
    val revision = rule.optLong("revision")
    val approval = rule.optJSONObject("semanticReadApproval")
    val approved = approval != null && approval.optBoolean("modelUseConsent") && approval.optLong("ruleRevision") == revision && approval.optString("sourceKey") == condition.optString("sourceKey") && approval.optLong("sourceRevision") == condition.optLong("sourceRevision")
    var consent by remember(id, revision) { mutableStateOf(false) }
    var chosenPlan by remember(id, revision) { mutableStateOf<String?>(null) }
    var chosenStep by remember(id, revision, chosenPlan) { mutableStateOf<String?>(null) }
    var planMenu by remember { mutableStateOf(false) }
    var stepMenu by remember { mutableStateOf(false) }
    val eligible = plans.filter { plan -> plan.optBoolean("approvalValid") && plan.optJSONObject("execution")?.optString("state") == "active" && plan.optJSONArray("steps").objects().any { !it.has("taskAdmission") && it.optString("taskId").isEmpty() && it.optString("status") !in setOf("completed", "failed", "cancelled", "skipped") } }
    val plan = eligible.find { it.optString("id") == chosenPlan }
    val steps = plan?.optJSONArray("steps").objects().filter { !it.has("taskAdmission") && it.optString("taskId").isEmpty() && it.optString("status") !in setOf("completed", "failed", "cancelled", "skipped") }
    val step = steps.find { it.optString("id") == chosenStep }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(rule.optString("title"), fontSize = 14.sp)
        if (!approved) {
            SemanticConditionUseConsent(condition, consent, busy) { consent = it }
            OutlinedButton(onClick = { send("approveSemanticRead", JSONObject().put("id", id).put("revision", revision).put("modelUseConsent", consent)) }, enabled = consent && !busy) {
                Text(stringResource(R.string.semantic_condition_approve_read))
            }
        } else {
            Text(condition.optString("goal"), fontSize = 13.sp, lineHeight = 21.sp)
            Text(condition.optString("sourceKey"), color = SecondaryInk, fontSize = 12.sp)
            Text(stringResource(R.string.semantic_condition_read_approved), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        }
        val binding = rule.optJSONObject("semanticPlanBinding")
        if (binding != null) Text(stringResource(R.string.semantic_condition_bound), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        else {
            Text(stringResource(R.string.semantic_condition_plan_note), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            Box {
                OutlinedButton(onClick = { planMenu = true }, enabled = approved && !busy && eligible.isNotEmpty()) { Text(plan?.optString("title") ?: stringResource(R.string.semantic_condition_choose_plan)) }
                DropdownMenu(planMenu, { planMenu = false }) { eligible.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.optString("title")) }, onClick = { chosenPlan = candidate.optString("id"); planMenu = false }) } }
            }
            if (plan != null) Box {
                OutlinedButton(onClick = { stepMenu = true }, enabled = !busy && steps.isNotEmpty()) { Text(step?.optString("title") ?: stringResource(R.string.semantic_condition_choose_step)) }
                DropdownMenu(stepMenu, { stepMenu = false }) { steps.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.optString("title")) }, onClick = { chosenStep = candidate.optString("id"); stepMenu = false }) } }
            }
            if (plan != null && step != null) Button(onClick = {
                send("bindSemanticPlan", JSONObject().put("id", id).put("revision", revision).put("planId", plan.getString("id")).put("planRevision", plan.getLong("revision")).put("stepId", step.getString("id")))
            }, enabled = !busy && approved) { Text(stringResource(R.string.semantic_condition_bind)) }
            if (approved && eligible.isEmpty()) Text(stringResource(R.string.semantic_condition_no_plan), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}
