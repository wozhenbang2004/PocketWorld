package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Same SettingsSurface content, Switch, segmented selection and text actions as task preferences.
 * No new dialog/design system; a toggle request alone never authorizes cloud processing. */
@Composable
internal fun AutomaticUserLearningControls(controller: PocketWorldController) {
    var policy by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var readGeneration by remember { mutableIntStateOf(0) }
    var consentMaximum by remember { mutableStateOf<Int?>(null) }
    val current = policy
    val available = current != null && current.opt("enabled") is Boolean && current.optLong("revision", -1) >= 0 && current.optInt("maxUserSourcesPerTask") in 1..5
    val enabled = available && current?.optBoolean("enabled") == true
    val maximum = current?.optInt("maxUserSourcesPerTask", 3)?.coerceIn(1, 5) ?: 3
    fun save(nextEnabled: Boolean, count: Int, consent: Boolean) {
        if (!available || busy) return
        val revision = current?.optLong("revision", -1) ?: return
        busy = true
        error = null
        controller.saveAutomaticUserLearningPolicy(revision, nextEnabled, count, consent) { actual, failure ->
            policy = actual?.let { JSONObject(it.toString()) }
            busy = false
            consentMaximum = null
            error = failure
        }
    }
    LaunchedEffect(controller, readGeneration) {
        busy = true
        consentMaximum = null
        controller.readAutomaticUserLearningPolicy { actual, failure ->
            policy = actual?.let { JSONObject(it.toString()) }
            busy = false
            error = failure
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动提取原话候选", fontSize = 14.sp)
                Text("默认关闭。私人对话任务完成后，只读取本次真正采用的你的原话；网页、工具和他人的消息不在此范围。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            }
            Switch(enabled, { next ->
                if (next) consentMaximum = maximum else save(false, maximum, false)
            }, enabled = available && !busy && consentMaximum == null)
        }
        Text("每次任务最多读取", style = MaterialTheme.typography.labelLarge)
        PocketSegmentedTabs((1..5).map { "$it 条" }, maximum - 1, { index ->
            val count = index + 1
            if (count != maximum) {
                if (enabled) consentMaximum = count else save(false, count, false)
            }
        }, available && !busy && consentMaximum == null)
        Text("候选仍须你在记忆管理中审阅后采用。关闭立即阻止后续自动请求和候选提交，不删除已保存的候选；原来源撤回和遗忘继续生效。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        consentMaximum?.let { count ->
            Text("开启后，最多 $count 条本次原话将交给当前配置的云模型 API 或 ChatGPT 推理，可能包含个人信息并产生费用。只生成待审候选，不自动采用，也不授予执行权限。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { save(true, count, true) }, enabled = available && !busy) { Text("同意并开启") }
                TextButton(onClick = { consentMaximum = null }, enabled = !busy) { Text("取消") }
            }
        }
        if (!available && !busy) Text("尚未读到真实设置，不能确认当前开关；重新读取后才能修改。", color = SecondaryInk, fontSize = 12.sp)
        if (busy) Text("正在核对设置…", color = SecondaryInk, fontSize = 12.sp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (!busy && (!available || error != null)) TextButton(onClick = { error = null; readGeneration++ }) { Text("重新读取设置") }
    }
}
