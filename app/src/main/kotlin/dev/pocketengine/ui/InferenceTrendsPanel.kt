package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import dev.pocketengine.runtime.usage.InferenceTrendQuery
import org.json.JSONArray
import org.json.JSONObject

/** Read-only range view; root supplies the original private-owner TaskStore reader. */
@Composable
internal fun InferenceTrendsPanel(read: (Long, Long, String, String, (JSONObject?, String?) -> Unit) -> Unit) {
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
        runCatching { if (reset) { val z = ZoneId.of(zone); capturedFrom = LocalDate.parse(from).atStartOfDay(z).toInstant().toEpochMilli(); capturedUntil = LocalDate.parse(until).plusDays(1).atStartOfDay(z).toInstant().toEpochMilli(); capturedZone = zone; retained = JSONArray(); next = ""; unreadable = 0 }; require(capturedFrom > 0 && capturedUntil > capturedFrom) }.onFailure { error = "请核对日期范围与时区。" }.onSuccess {
            loading = true; error = null
            read(capturedFrom, capturedUntil, capturedZone, if (reset) "" else next) { page, failure ->
                loading = false
                if (page == null) error = failure ?: "原用量记录暂不可读"
                else runCatching { val incoming = page.getJSONArray("receipts"); require(retained.length() + incoming.length() <= 4096) { "display_limit" }; val joined = JSONArray(retained.toString()); for (i in 0 until incoming.length()) joined.put(incoming.get(i)); unreadable += page.optInt("unreadableRows"); retained = joined; next = page.optString("nextCursor"); result = InferenceTrendQuery.report(joined, capturedFrom, capturedUntil, capturedZone).put("hasMore", page.optBoolean("hasMore")).put("completeCoverage", !page.optBoolean("hasMore") && unreadable == 0) }.onFailure { error = "已到本次4096条显示上限或原记录不可核对；保留已读范围，可缩小日期后重读。" }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) { Text(if (open) "收起跨任务用量趋势" else "跨任务用量趋势") }
        if (open) {
            Text("按原调用时间分组", style = MaterialTheme.typography.titleMedium)
            Text("输入/输出、缓存与未知项保留原提供方口径；历史连接删除后仍使用回执身份。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(from, { from = it }, label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(until, { until = it }, label = { Text("结束日期（含当天）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zone, { zone = it }, label = { Text("分组时区") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { load(true) }, enabled = !loading) { Text("读取原记录") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { report ->
                Text(if (report.optBoolean("completeCoverage")) "已覆盖本次保留记录范围" else "当前有界窗口，可能尚有记录未纳入；不代表全部账单。", style = MaterialTheme.typography.bodySmall)
                Text("${report.optJSONObject("total")?.optInt("attempts") ?: 0} 次原调用 · ${report.optJSONObject("total")?.optInt("unfinished") ?: 0} 次结果未确认", style = MaterialTheme.typography.titleSmall)
                val days = report.optJSONArray("daily") ?: JSONArray()
                for (i in 0 until days.length()) { val day = days.getJSONObject(i); val usage = day.getJSONObject("usage"); Text("${day.getString("date")}　${usage.optInt("attempts")} 次调用", style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider()
                val models = report.optJSONArray("breakdown") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val row = models.getJSONObject(i)
                    Text("${row.getString("model")} · ${row.getString("purpose")}", style = MaterialTheme.typography.titleSmall)
                    Text("历史连接 ${row.getString("historicalProviderId")} · ${row.getString("protocol")}", style = MaterialTheme.typography.labelSmall)
                    val groups = row.getJSONObject("usage").getJSONArray("groups")
                    for (n in 0 until groups.length()) { val counters = groups.getJSONObject(n).getJSONObject("counters"); Text("输入 ${trendCount(counters.optJSONObject("INPUT"))} · 输出 ${trendCount(counters.optJSONObject("OUTPUT"))} · 服务总计 ${trendCount(counters.optJSONObject("TOTAL"))}", style = MaterialTheme.typography.bodySmall) }
                }
                if (report.optBoolean("hasMore")) TextButton(onClick = { load(false) }, enabled = !loading && retained.length() < 4096) { Text("继续读取下一个原记录窗口") }
            }
        }
    }
}
private fun trendCount(count: JSONObject?): String = if (count == null || count.optBoolean("overflow")) "未知" else (if (count.isNull("knownSubtotal")) "未知" else count.optLong("knownSubtotal").toString()) + "（${count.optInt("unknownCalls")}次未知/${count.optInt("partialCalls")}次部分返回）"
