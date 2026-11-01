package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.usage.ModelPrice
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject

/** Optional native per-model quote. It is neither a billing product nor an account balance. */
@Composable
internal fun ModelPriceControls(snapshot: JSONObject, busy: Boolean, failure: String?,
    save: (ModelBinding, String, Long, JSONObject, Boolean, () -> Unit) -> Unit) {
    var editing by remember { mutableStateOf<ModelPriceEditTarget?>(null) }
    val quote = snapshot.optJSONObject("quote")
    val binding = snapshot.optJSONObject("binding")?.let { runCatching { ModelBinding.fromJson(it) }.getOrNull() }
    Text(if (snapshot.optBoolean("known")) "费用估算：已有价目观察（不是账单）" else if (quote != null) "费用估算：旧价目已不适用，未沿用估价" else "费用估算：价目未知，不是免费", style = MaterialTheme.typography.bodySmall)
    if (quote != null) Text("${quote.optString("currency")} · ${if (quote.optString("source") == "OWNER_REVIEWED") "机主明确价目" else "提供方目录观察"} · ${quote.optString("sourceVersion")}", style = MaterialTheme.typography.bodySmall)
    if (binding != null) TextButton(onClick = { editing = ModelPriceEditTarget.capture(snapshot) }, enabled = !busy) { Text("可选：核对本模型价目") }
    editing?.let { target ->
        key(target) {
            PriceEditor(target, target.current(snapshot), busy, failure, { if (editing === target) editing = null }, { input, clear ->
                save(target.binding, target.model, target.ownerRevision, input, clear) { if (editing === target) editing = null }
            })
        }
    }
}

@Composable
private fun PriceEditor(target: ModelPriceEditTarget, targetCurrent: Boolean, busy: Boolean, failure: String?, dismiss: () -> Unit, save: (JSONObject, Boolean) -> Unit) {
    val quote = remember(target) { target.quote() }
    var currency by remember { mutableStateOf(quote?.optString("currency").orEmpty()) }
    var source by remember { mutableStateOf(quote?.optString("sourceRef").orEmpty()) }
    var version by remember { mutableStateOf(quote?.optString("sourceVersion").orEmpty()) }
    fun oldDay(key: String): String = quote?.optLong(key)?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()
    val originalFrom = oldDay("effectiveFrom"); val originalUntil = oldDay("effectiveUntil")
    var from by remember { mutableStateOf(originalFrom) }
    var until by remember { mutableStateOf(originalUntil) }
    var values by remember { mutableStateOf(ModelPrice.Rate.values().associateWith { quote?.optJSONObject("rates")?.opt(it.name)?.takeUnless { raw -> raw == JSONObject.NULL }?.toString().orEmpty() }) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("本模型的可选费用估算") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("固定原模型：${target.model} · 连接修订 ${target.binding.installationRevision()}。此草稿不会改存到其他连接或模型。", style = MaterialTheme.typography.bodySmall)
            if (!targetCurrent) Text("原连接、模型或价目修订已变化；草稿仍保留在原对象，未改绑。请保留内容并取消后重新核对，当前不能保存。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("请按明确来源填写；未填写的单价保持未知，不会填0。估价仅使用实际返回token，不是账单、套餐余额或金额硬限。只影响未来调用，历史固定原价目。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(currency, { currency = it.uppercase().take(3) }, enabled = !busy, label = { Text("币种代码，如 USD / CNY") }, singleLine = true)
            OutlinedTextField(source, { source = it }, enabled = !busy, label = { Text("公开价目来源 HTTPS 地址（不含凭据）") })
            OutlinedTextField(version, { version = it.take(160) }, enabled = !busy, label = { Text("来源价目版本或日期说明") })
            Text("下列token价格单位均为每100万tokens；可留空。缓存未返回、TTL未知或价格缺项时，不会把缺项当免费。OpenAI输入已经包含缓存，输出已含推理；Gemini推理与候选输出分开计算。", style = MaterialTheme.typography.bodySmall)
            ModelPrice.Rate.values().forEach { rate ->
                OutlinedTextField(values[rate].orEmpty(), { values = values + (rate to it.take(64)) }, enabled = !busy, label = { Text(priceRateName(rate)) }, singleLine = true)
            }
            Text("同价缓存可明确填与普通输入相同单价；不清楚写入价或TTL时保留未知。Gemini工具提示/推理未单列单价时按已填写的输入/输出单价估算；请求费只作来源记录，当前token小计不含请求费及媒体/工具附加费用。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(from, { from = it.take(10) }, enabled = !busy, label = { Text("生效日 YYYY-MM-DD，可空（UTC）") }, singleLine = true)
            OutlinedTextField(until, { until = it.take(10) }, enabled = !busy, label = { Text("失效日 YYYY-MM-DD，可空（UTC）") }, singleLine = true)
            (problem ?: failure)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (quote?.optString("source") == "OWNER_REVIEWED") TextButton(onClick = { save(JSONObject(), true) }, enabled = !busy && targetCurrent) { Text("清除此机主价目，恢复当前来源或未知") }
        }
    }, confirmButton = { TextButton(enabled = !busy && targetCurrent, onClick = {
        try {
            require(currency.length == 3 && source.isNotBlank() && version.isNotBlank())
            val rates = JSONObject(); values.forEach { (rate, text) -> if (text.isNotBlank()) rates.put(rate.name, text.trim()) }
            require(rates.length() > 0)
            val input = JSONObject().put("currency", currency).put("sourceRef", source.trim()).put("sourceVersion", version.trim()).put("rates", rates)
            fun epoch(value: String): Long = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            if (from.isNotBlank()) input.put("effectiveFrom", if (from == originalFrom && quote != null) quote.getLong("effectiveFrom") else epoch(from))
            if (until.isNotBlank()) input.put("effectiveUntil", if (until == originalUntil && quote != null) quote.getLong("effectiveUntil") else epoch(until))
            problem = null; save(input, false)
        } catch (_: Exception) { problem = "请核对币种、来源、日期与至少一项明确单价。空值不是0。" }
    }) { Text("保存为未来调用的机主价目") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("保持原价目") } })
}

private fun priceRateName(rate: ModelPrice.Rate): String = when (rate) {
    ModelPrice.Rate.INPUT -> "普通输入 / 百万tokens"
    ModelPrice.Rate.OUTPUT -> "输出 / 百万tokens"
    ModelPrice.Rate.CACHE_READ -> "缓存读取 / 百万tokens"
    ModelPrice.Rate.CACHE_WRITE -> "通用缓存写入 / 百万tokens（明确同价时填写）"
    ModelPrice.Rate.CACHE_WRITE_5M -> "缓存写入5m / 百万tokens"
    ModelPrice.Rate.CACHE_WRITE_1H -> "缓存写入1h / 百万tokens"
    ModelPrice.Rate.REASONING -> "单列推理 / 百万tokens（与输出不同价时）"
    ModelPrice.Rate.TOOL_PROMPT -> "单列工具提示输入 / 百万tokens"
    ModelPrice.Rate.REQUEST -> "请求费 / 次（来源记录，不进入token小计）"
}
