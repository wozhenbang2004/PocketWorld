package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pocketengine.R
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import dev.pocketengine.runtime.usage.InferenceTrendQuery
import org.json.JSONArray
import org.json.JSONObject

/** Read-only range view; root supplies the original private-owner TaskStore reader. */
@Composable
internal fun InferenceTrendsPanel(read: (Long, Long, String, String, (JSONObject?, String?) -> Unit) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var until by remember { mutableStateOf(LocalDate.now().toString()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var retained by remember { mutableStateOf(JSONArray()) }; var next by remember { mutableStateOf("") }
    var capturedFrom by remember { mutableLongStateOf(0) }; var capturedUntil by remember { mutableLongStateOf(0) }; var capturedZone by remember { mutableStateOf("") }; var unreadable by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    fun load(reset: Boolean) {
        if (loading) return
        runCatching { if (reset) { val z = ZoneId.of(zone); capturedFrom = LocalDate.parse(from).atStartOfDay(z).toInstant().toEpochMilli(); capturedUntil = LocalDate.parse(until).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(); capturedZone = zone; retained = JSONArray(); next = ""; unreadable = 0 }; require(capturedFrom > 0 && capturedUntil > capturedFrom) }.onFailure { error = context.getString(R.string.w2_trends_invalid_range) }.onSuccess {
            loading = true; error = null
            read(capturedFrom, capturedUntil, capturedZone, if (reset) "" else next) { page, failure ->
                loading = false
                if (page == null) error = failure ?: context.getString(R.string.w2_trends_records_unavailable)
                else runCatching { val incoming = page.getJSONArray("receipts"); require(retained.length() + incoming.length() <= 4096) { "display_limit" }; val joined = JSONArray(retained.toString()); for (i in 0 until incoming.length()) joined.put(incoming.get(i)); unreadable += page.optInt("unreadableRows"); retained = joined; next = page.optString("nextCursor"); result = InferenceTrendQuery.report(joined, capturedFrom, capturedUntil, capturedZone).put("hasMore", page.optBoolean("hasMore")).put("completeCoverage", !page.optBoolean("hasMore") && unreadable == 0) }.onFailure { error = context.getString(R.string.w2_trends_display_limit) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.w2_trends_collapse else R.string.w2_trends_expand)) }
        if (open) {
            Text(stringResource(R.string.w2_trends_group_by_time), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.w2_trends_counters_notice), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.w2_trends_start_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(until, { until = it }, label = { Text(stringResource(R.string.w2_trends_end_date)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zone, { zone = it }, label = { Text(stringResource(R.string.w2_trends_time_zone)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { load(true) }, enabled = !loading) { Text(stringResource(R.string.w2_trends_read)) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { report ->
                Text(if (report.optBoolean("completeCoverage")) stringResource(R.string.w2_trends_complete_coverage) else stringResource(R.string.w2_trends_limited_coverage), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.w2_trends_call_summary, report.optJSONObject("total")?.optInt("attempts") ?: 0, report.optJSONObject("total")?.optInt("unfinished") ?: 0), style = MaterialTheme.typography.titleSmall)
                val days = report.optJSONArray("daily") ?: JSONArray()
                for (i in 0 until days.length()) { val day = days.getJSONObject(i); val usage = day.getJSONObject("usage"); Text(stringResource(R.string.w2_trends_daily_calls, day.getString("date"), usage.optInt("attempts")), style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider()
                val models = report.optJSONArray("breakdown") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val row = models.getJSONObject(i)
                    Text("${row.getString("model")} · ${row.getString("purpose")}", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.w2_trends_historical_connection, row.getString("historicalProviderId"), row.getString("protocol")), style = MaterialTheme.typography.labelSmall)
                    val groups = row.getJSONObject("usage").getJSONArray("groups")
                    for (n in 0 until groups.length()) { val counters = groups.getJSONObject(n).getJSONObject("counters"); Text(stringResource(R.string.w2_trends_counter_summary, trendCount(counters.optJSONObject("INPUT")), trendCount(counters.optJSONObject("OUTPUT")), trendCount(counters.optJSONObject("TOTAL"))), style = MaterialTheme.typography.bodySmall) }
                }
                if (report.optBoolean("hasMore")) TextButton(onClick = { load(false) }, enabled = !loading && retained.length() < 4096) { Text(stringResource(R.string.w2_trends_read_next)) }
            }
        }
    }
}
@Composable
private fun trendCount(count: JSONObject?): String = if (count == null || count.optBoolean("overflow")) stringResource(R.string.w2_trends_unknown) else stringResource(R.string.w2_trends_count_detail, if (count.isNull("knownSubtotal")) stringResource(R.string.w2_trends_unknown) else count.optLong("knownSubtotal").toString(), count.optInt("unknownCalls"), count.optInt("partialCalls"))
