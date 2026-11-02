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

/** Content of the existing private owner SettingsSurface. No feature/model command route. */
@Composable
internal fun AutomaticUserLearningControls(controller: PocketWorldController) {
    var policy by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var consentMaximum by remember { mutableStateOf<Int?>(null) }
    var readGeneration by remember { mutableIntStateOf(0) }
    val current = policy
    val available = current != null && current.opt("enabled") is Boolean && current.optLong("revision", -1) >= 0 && current.optInt("maxUserSourcesPerTask") in 1..5
    val enabled = available && current?.optBoolean("enabled") == true
    val maximum = current?.optInt("maxUserSourcesPerTask", 3)?.coerceIn(1, 5) ?: 3
    val revision = current?.optLong("revision", -1) ?: -1
    fun save(nextEnabled: Boolean, nextMaximum: Int, consent: Boolean) {
        if (!available || busy) return
        busy = true
        error = null
        controller.saveAutomaticUserLearningPolicy(revision, nextEnabled, nextMaximum, consent) { actual, failure ->
            busy = false
            if (actual != null) policy = JSONObject(actual.toString())
            error = failure
            if (failure == null) consentMaximum = null
        }
    }
    LaunchedEffect(controller, readGeneration) {
        busy = true
        error = null
        controller.readAutomaticUserLearningPolicy { actual, failure ->
            busy = false
            policy = actual?.let { JSONObject(it.toString()) }
            error = failure
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.automatic_user_learning_title), fontSize = 14.sp)
                Text(stringResource(R.string.automatic_user_learning_detail), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            }
            Switch(enabled, { next ->
                if (next) consentMaximum = maximum else save(false, maximum, false)
            }, enabled = available && !busy)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.automatic_user_learning_limit), Modifier.weight(1f), fontSize = 13.sp)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = available && !busy) {
                    Text(stringResource(R.string.automatic_user_learning_count, maximum))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    (1..5).forEach { count ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.automatic_user_learning_count, count)) }, onClick = {
                            expanded = false
                            if (count != maximum) {
                                if (enabled) consentMaximum = count else save(false, count, false)
                            }
                        })
                    }
                }
            }
        }
        Text(stringResource(R.string.automatic_user_learning_review), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        if (busy) Text(stringResource(R.string.automatic_user_learning_working), color = SecondaryInk, fontSize = 12.sp)
        if (!available && !busy) Text(stringResource(R.string.automatic_user_learning_unavailable), color = SecondaryInk, fontSize = 12.sp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (!busy && (!available || error != null)) TextButton(onClick = { readGeneration++ }) {
            Text(stringResource(R.string.automatic_user_learning_reload))
        }
    }
    consentMaximum?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) consentMaximum = null },
            title = { Text(stringResource(R.string.automatic_user_learning_consent_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.automatic_user_learning_consent, selected), fontSize = 13.sp, lineHeight = 22.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            } },
            confirmButton = { Button(onClick = { save(true, selected, true) }, enabled = !busy && available) {
                Text(stringResource(R.string.automatic_user_learning_consent_accept))
            } },
            dismissButton = { TextButton(onClick = { consentMaximum = null }, enabled = !busy) {
                Text(stringResource(R.string.automatic_user_learning_cancel))
            } }
        )
    }
}
