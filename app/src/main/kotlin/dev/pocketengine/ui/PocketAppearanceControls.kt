package dev.pocketengine.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.pocketengine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PocketAppearance(val stored: String, val label: Int) {
    SYSTEM("system", R.string.appearance_system), LIGHT("light", R.string.appearance_light), DARK("dark", R.string.appearance_dark)
}
private fun readAppearance(preferences: SharedPreferences) = PocketAppearance.entries.firstOrNull { it.stored == UiAppearance.mode(preferences) } ?: PocketAppearance.SYSTEM
internal fun pocketWindowTheme(context: Context): Int = UiAppearance.theme(context)

/** Presentation preference only; independent of model selection, task identities and execution grants. */
@Composable
internal fun rememberPocketAppearance(): Pair<SharedPreferences, PocketAppearance> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { UiAppearance.preferences(context) }
    var selected by remember(preferences) { mutableStateOf(readAppearance(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == UiAppearance.KEY) selected = readAppearance(preferences) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        selected = readAppearance(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return preferences to selected
}

@Composable
internal fun PocketAppearanceControls() {
    val (preferences, selected) = rememberPocketAppearance()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsSurface(stringResource(R.string.appearance_title), stringResource(R.string.appearance_description)) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            PocketAppearance.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = selected == choice, enabled = !saving, role = Role.RadioButton, onClick = {
                        if (selected != choice) scope.launch {
                            saving = true; failed = false
                            try { failed = !withContext(Dispatchers.IO) { preferences.edit().putString(UiAppearance.KEY, choice.stored).commit() } }
                            catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped }
                            catch (_: Exception) { failed = true }
                            finally { saving = false }
                        }
                    }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == choice, onClick = null, enabled = !saving)
                    Text(stringResource(choice.label), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.appearance_save_unconfirmed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
