package dev.pocketengine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Port of Affect UI Tool Call Card (Clean), 37b91fa302dea59e28ce1eb2ba7acd7ce23296be.
// Copyright (c) 2026 Florian Höller (adfects), MIT; see licenses/affectui-MIT.txt.
// Native differences: monochrome; actual receipt labels; no fabricated duration or automatic retry.
@Composable
internal fun AffectToolCallCard(id: String, name: String, statusLabel: String,
    output: String, needsReview: Boolean, modifier: Modifier = Modifier, arguments: String = "",
    tone: TaskExecutionPresentation.Tone = TaskExecutionPresentation.Tone.NEUTRAL) {
    var expanded by rememberSaveable(id) { mutableStateOf(needsReview) }
    var manuallyToggled by rememberSaveable(id) { mutableStateOf(false) }
    var showFull by rememberSaveable(id) { mutableStateOf(false) }
    var clipped by remember(output) { mutableStateOf(false) }
    var copied by remember(output) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(needsReview) { if (needsReview && !manuallyToggled) expanded = true }
    val ease = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val rotation by androidx.compose.animation.core.animateFloatAsState(targetValue = if (expanded) 180f else 0f, animationSpec = tween(360, easing = ease), label = "toolDisclosure")
    Surface(modifier = modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 10.dp), color = Color.White,
        shape = RoundedCornerShape(10.dp), border = BorderStroke(.5.dp, Color(0xffebebeb)),
        shadowElevation = 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                manuallyToggled = true; expanded = !expanded
            }.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AffectGlyph("tool", Modifier.size(16.dp), SecondaryInk)
                Column(Modifier.weight(1f)) {
                    Text(name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                        fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                    PocketExecutionStatus(statusLabel, tone, Modifier.padding(top = 3.dp))
                }
                if(needsReview) Text("需核对", fontSize = 12.sp, color = Ink)
                AffectGlyph("chevron", Modifier.size(12.dp).rotate(rotation), SecondaryInk)
            }
            AnimatedVisibility(visible = expanded,
                enter = expandVertically(animationSpec = tween(380, easing = ease)) + fadeIn(tween(240)),
                exit = shrinkVertically(animationSpec = tween(380, easing = ease)) + fadeOut(tween(200))) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(color = Color(0xffeeeeee), thickness = .5.dp)
                    if (arguments.isNotBlank()) {
                        Text("本次参数", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        SelectionContainer {
                            Text(arguments, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                                color = Ink, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("实际返回", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        AffectToolButton(if(copied) "check" else "copy", if(copied) "已复制" else "复制原返回",
                            enabled = output.isNotEmpty(), onClick = {
                                try { clipboard.setText(AnnotatedString(output)); copied = true } catch (_: Exception) { copied = false }
                            })
                    }
                    if(output.isBlank()) Text("没有可展示的返回文本。", color = SecondaryInk, fontSize = 13.sp)
                    else SelectionContainer {
                        Text(output, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                            color = Ink, maxLines = if(showFull) Int.MAX_VALUE else 8,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if(!showFull) clipped = it.hasVisualOverflow },
                            modifier = Modifier.fillMaxWidth().heightIn(max = if(showFull) 400.dp else 220.dp)
                                .background(Color(0xfff4f4f4), RoundedCornerShape(8.dp))
                                .then(if(showFull) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                                .padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    if(clipped || showFull) TextButton(onClick = { showFull = !showFull }, contentPadding = PaddingValues(0.dp)) {
                        Text(if(showFull) "收起全文" else "展开完整返回", fontSize = 13.sp)
                    }
                    if(needsReview) Text("返回状态仍需核对，不会自动重新执行。", color = Ink, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
