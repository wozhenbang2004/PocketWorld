package dev.pocketengine.ui

import android.graphics.BitmapFactory
import android.util.Base64
import dev.pocketengine.ui.memory.TaskMemoryWorkflowSheet
import dev.pocketengine.ui.memory.TaskMemorySearchSheet
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.conversation.ConversationHistory
import dev.pocketengine.features.conversation.TaskPresentation


internal val Ink = Color(0xff181818)
internal val SecondaryInk = Color(0xff666666)
internal val Line = Color(0xffe4e4e4)
internal val Soft = Color(0xfff2f2f2)
internal val Danger = Color(0xff242424)
internal val Rounded = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PocketWorldApp(controller: PocketWorldController) {
    val state = controller.state
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var connections by rememberSaveable { mutableStateOf(false) }
    var connectionSection by rememberSaveable { mutableStateOf<String?>(null) }
    var teamLaunch by rememberSaveable { mutableStateOf(false) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskMemorySearch by remember { mutableStateOf(false) }
    var conversationPicker by rememberSaveable { mutableStateOf(false) }
    var projectDialog by rememberSaveable { mutableStateOf(false) }
    var fileDialog by rememberSaveable { mutableStateOf(false) }
    var phoneDraft by rememberSaveable { mutableStateOf("") }
    var phoneIntent by rememberSaveable { mutableStateOf(false) }
    val privateDraft=controller.composerDraft
    val composerKey=privateDraft?.key.orEmpty()
    val composerEpoch=controller.composerViewEpoch
    val phoneComposerVisible=phoneIntent
    val draft=if(phoneIntent)phoneDraft else privateDraft?.text.orEmpty()
    val changeDraft: (String) -> Unit = { value -> if(phoneIntent==phoneComposerVisible){if(phoneComposerVisible){phoneDraft=value;controller.voiceDraftTextChanged(value)}else controller.editComposerText(composerKey,composerEpoch,value)} }
    val changePhoneIntent: (Boolean) -> Unit = { next ->
        if(phoneIntent!=next){
            if(next&&phoneDraft.isEmpty())phoneDraft=privateDraft?.text.orEmpty()
            else if(!next&&privateDraft?.text?.isEmpty()==true&&phoneDraft.isNotEmpty())controller.editComposerText(composerKey,composerEpoch,phoneDraft)
            controller.voiceInputTypeChanged(next)
        };phoneIntent=next
    }
    var executionMode by rememberSaveable { mutableStateOf("background") }
    var consentGoal by remember { mutableStateOf<String?>(null) }
    var suggestedGoal by remember { mutableStateOf<String?>(null) }
    var appliedSuggestedRevision by rememberSaveable { mutableIntStateOf(0) }
    val initialReading=remember(composerKey){privateDraft}
    val chatScroll=remember(composerKey){LazyListState(initialReading?.scrollIndex?:0,initialReading?.scrollOffset?:0)}
    var observedMessage by remember(composerKey){mutableStateOf(initialReading?.observedMessage.orEmpty())}
    var newReplies by remember(composerKey){mutableStateOf(initialReading?.newReplies?:false)}
    var readingReady by remember(composerKey){mutableStateOf(false)}
    var missingReadingAnchor by remember(composerKey){mutableStateOf(false)}
    LaunchedEffect(composerKey,state.conversationId,state.messages.isNotEmpty()) {
        if(readingReady||initialReading==null||initialReading.conversationId!=state.conversationId.orEmpty())return@LaunchedEffect
        if(state.messages.isEmpty()&&initialReading.conversationId.isNotEmpty())return@LaunchedEffect
        if(state.messages.isNotEmpty()) {
            val anchor=state.messages.indexOfFirst{(it.id?:(it.role+it.at))==initialReading.anchorMessage}
            missingReadingAnchor=initialReading.anchorMessage.isNotEmpty()&&anchor<0
            val index=if(anchor>=0)anchor else initialReading.scrollIndex.coerceIn(0,state.messages.lastIndex)
            chatScroll.scrollToItem(index,initialReading.scrollOffset)
        };readingReady=true
    }
    LaunchedEffect(composerKey,readingReady) {
        if(!readingReady||composerKey.isEmpty())return@LaunchedEffect
        snapshotFlow{Triple(Pair(chatScroll.firstVisibleItemIndex,chatScroll.firstVisibleItemScrollOffset),Pair(observedMessage,newReplies),chatScroll.isScrollInProgress)}.collect{position->
            if(position.third)missingReadingAnchor=false
            if(!missingReadingAnchor){val index=position.first.first;val row=controller.state.messages.getOrNull(index);controller.saveComposerReading(composerKey,index,position.first.second,row?.let{it.id?:(it.role+it.at)}.orEmpty(),position.second.first,position.second.second)}
        }
    }
    var taskReturnTab by rememberSaveable { mutableIntStateOf(1) }
    var taskReturnConnection by rememberSaveable { mutableStateOf<String?>(null) }
    var projectNestedTask by rememberSaveable { mutableStateOf<String?>(null) }
    var projectNestedTaskTab by rememberSaveable { mutableIntStateOf(1) }
    var projectNestedTaskConnection by rememberSaveable { mutableStateOf<String?>(null) }
    fun openTask(id: String) {
        if (selectedTaskId != id) {
            val hasAppSource = tab != 0 || connections || state.messages.any { (it.taskId ?: it.submission?.optString("taskId")) == id }
            taskReturnTab = if (hasAppSource) tab else 1
            taskReturnConnection = connectionSection.takeIf { connections }
        }
        if (state.inspectionTaskId != null) controller.closeInspection()
        selectedTaskId = id; tab = taskReturnTab; connections = false; settings = false; controller.getTask(id)
    }
    fun closeTask() { selectedTaskId = null; tab = taskReturnTab; if (taskReturnConnection != null) { connectionSection = taskReturnConnection; connections = true } }
    val selectedTask = state.tasks.find { it.id == selectedTaskId }
    LaunchedEffect(state.requestedPhoneModeRevision) { if (state.requestedPhoneModeRevision > 0 && state.requestedPhoneMode == "main_screen") { executionMode = "main_screen"; changePhoneIntent(true); tab = 0; settings = false; connections = false; selectedTaskId = null } }
    LaunchedEffect(controller.phoneDraftAcceptedRevision) { if(phoneDraft.trim()==controller.phoneDraftAcceptedText)phoneDraft="" }
    LaunchedEffect(state.requestedTaskRevision) {
        if (state.requestedTaskRevision > 0) state.requestedTaskId?.let { id ->
            if (projectNestedTask == id && state.projectTaskReturn?.taskId == id) {
                selectedTaskId = id; taskReturnTab = projectNestedTaskTab; taskReturnConnection = projectNestedTaskConnection
                tab = taskReturnTab; connections = false; settings = false; projectNestedTask = null
                controller.consumeWorkspaceProjectReturn(); controller.getTask(id)
            } else openTask(id)
        }
    }
    LaunchedEffect(state.requestedProjectRevision) {
        if (state.requestedProjectRevision > 0 && state.requestedProjectId == state.projectId) {
            projectNestedTask = selectedTaskId?.takeIf { it == state.projectTaskReturn?.taskId }
            projectNestedTaskTab = taskReturnTab; projectNestedTaskConnection = taskReturnConnection
            selectedTaskId = null; tab = 2; connections = false; settings = false
        }
    }
    LaunchedEffect(state.requestedConnectionRevision) { if (state.requestedConnectionRevision > 0) { if (state.inspectionTaskId != null) controller.closeInspection(); selectedTaskId = null; connectionSection = state.requestedConnectionSection; connections = true; settings = false } }
    LaunchedEffect(controller.developmentNavigationRevision) { if (controller.developmentNavigationRevision > 0) { if (state.inspectionTaskId != null) controller.closeInspection(); selectedTaskId = null; tab = 2; connections = false; settings = false } }
    LaunchedEffect(state.suggestedInputRevision,composerKey) {
        if (state.suggestedInputRevision > appliedSuggestedRevision && privateDraft!=null) {
            appliedSuggestedRevision=state.suggestedInputRevision
            if (state.suggestedPhoneTask == true && draft.isNotBlank()) suggestedGoal = state.suggestedInput
            else {
                changeDraft(if (draft.isBlank()) state.suggestedInput else draft + "\n" + state.suggestedInput)
                state.suggestedPhoneTask?.let(changePhoneIntent)
                tab = 0
            }
        }
    }
    LaunchedEffect(tab, settings, connections, selectedTaskId, conversationPicker, teamLaunch) {
        if(tab!=0||settings||connections||selectedTaskId!=null||conversationPicker||teamLaunch)controller.voiceInputModeChanged()
    }
    PocketWorldTheme {
        Scaffold(
            containerColor = PageSurface,
            topBar = {
                Row(Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("PocketWorld", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text(" / ", fontSize = 14.sp, color = SecondaryInk)
                    Text(listOf("对话", "任务", "项目")[tab], fontSize = 14.sp, color = SecondaryInk)
                    Spacer(Modifier.weight(1f))
                    if(tab == 0) IconButton(onClick = { conversationPicker = true },
                        enabled = controller.composerDraft != null,
                        modifier = Modifier.semantics { contentDescription = "历史对话" }) {
                        AffectGlyph("clock", color = SecondaryInk)
                    }
                    IconButton(onClick = { settings = true }, modifier = Modifier.semantics { contentDescription = "设置与连接" }) { LineIcon(3) }
                }
            },
            bottomBar = {
                Column(Modifier.background(Color.White).navigationBarsPadding()) {
                    HorizontalDivider(color = Line.copy(alpha = .65f), thickness = .5.dp)
                    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("对话", "任务", "项目").forEachIndexed { index, label ->
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if(tab == index) Soft else Color.Transparent)
                                .selectable(selected = tab == index, role = Role.Tab, onClick = { tab = index })
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center) {
                                LineIcon(index)
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = 13.sp, fontWeight = if(tab == index) FontWeight.Medium else FontWeight.Normal,
                                    color = if(tab == index) Ink else SecondaryInk)
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                state.error?.let { text -> Notice(text, true, controller::dismissError) }
                state.notice?.let { text -> Notice(text, false, controller::dismissNotice) }
                when (tab) {
                    0 -> ChatScreen(state, draft, changeDraft, phoneIntent, changePhoneIntent, executionMode, { executionMode = it }, {
                        if(phoneIntent==phoneComposerVisible){if (phoneComposerVisible) consentGoal = draft else if (draft.isNotBlank()) { controller.sendChat(draft,composerKey,composerEpoch) }}
                    }, controller, { settings = true }, { task -> openTask(task.id) }, { teamLaunch = true }, chatScroll, observedMessage, { observedMessage = it }, newReplies, { newReplies = it }, readingReady, missingReadingAnchor)
                    1 -> TaskAuditList(state, controller, { openTask(it.id) }, { taskMemorySearch = true })
                    2 -> ProjectsScreen(state, controller, { projectDialog = true }, { fileDialog = true })
                }
            }
        }
        if (settings) SettingsSheet(state, controller, { settings = false }) { settings = false; connectionSection = null; connections = true }
        if (connections) ConnectionCenter(state, controller, connectionSection) { connections = false }
        if (taskMemorySearch) TaskMemorySearchSheet(controller) { taskMemorySearch = false }
        if (selectedTask != null) TaskSheet(selectedTask, state.activeTaskId == selectedTask.id, controller, if (taskReturnConnection != null) "返回原入口" else "返回" + listOf("对话", "任务", "项目")[taskReturnTab], ::closeTask)
        if (teamLaunch) TeamLaunchSheet(state, controller, draft) { teamLaunch = false }
        if (state.teamRunId != null) TeamRunDetail(state, controller)
        if (state.inspectionTaskId != null) DeviceInspector(state, controller)
        if (state.backgroundSetupOpen) BackgroundPhoneSetup(state, controller)
        if (conversationPicker) ModalBottomSheet(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), onDismissRequest = { conversationPicker = false }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding()) {
                ConversationHistoryBrowser(state.conversations.map { ConversationHistory.Item(it.id, it.title, it.updatedAt) }, state.conversationId, state.chatBusy || state.chatSubmitting,
                    { controller.openConversation(it); conversationPicker = false }, { controller.newConversation(); conversationPicker = false }, allowReadWhenBlocked = true)
            }
        }
        if (projectDialog) NameDialog("新建项目", "项目名称", "例如：我的小工具", { projectDialog = false }) { name -> controller.createProject(name); projectDialog = false }
        if (fileDialog) NameDialog("新建文件", "项目内路径", "例如：src/main.py", { fileDialog = false }) { name -> controller.newFile(name); fileDialog = false }
        if (state.filePath != null) FileEditor(state, controller)
        state.workspaceArtifactView?.let { WorkspaceArtifactSheet(it, state, controller) }
        suggestedGoal?.let { goal -> AlertDialog(onDismissRequest = { suggestedGoal = null }, containerColor = Color.White, title = { Text("收到新的手机任务") }, text = { Text("当前还有未发送的草稿。是否用收到的任务替换？\n\n$goal") }, confirmButton = { Button(onClick = { phoneDraft = goal; changePhoneIntent(true); tab = 0; suggestedGoal = null }) { Text("填入新任务") } }, dismissButton = { TextButton(onClick = { suggestedGoal = null }) { Text("保留草稿") } }) }
        consentGoal?.let { goal -> AlertDialog(onDismissRequest = { consentGoal = null }, containerColor = Color.White, title = { Text(if (executionMode == "background") "在后台独立屏执行" else "交给助手操作主屏") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(goal, fontWeight = FontWeight.Medium)
            Text(if (executionMode == "background") "需要已连接的后台助手。无法建立独立屏幕时会停止等待。" else "会切换手机应用并占用当前屏幕。暂停后再自行操作，停止不会撤回已发生的动作。", color = SecondaryInk)
            Text("任务中的可见文字会发给你配置的模型；启用视觉后也会发送截图。", color = SecondaryInk, fontSize = 12.sp)
            Text("原目标内累计 ${controller.newTaskToolBudget} 次工具、${controller.newTaskModelBudget} 次模型；" + if(controller.newTaskScopeMinutes==0) "持续至完成、主人停止或预算耗尽。" else "${controller.newTaskScopeMinutes}分钟后停止新派发。",color=SecondaryInk,fontSize=12.sp)
        } }, confirmButton = { Button(onClick = { controller.startPhone(goal, executionMode); consentGoal = null }) { Text("开始任务") } }, dismissButton = { TextButton(onClick = { consentGoal = null }) { Text("取消") } }) }
        BackHandler(enabled = !settings && !connections && !teamLaunch && state.teamRunId == null && state.inspectionTaskId == null && !state.backgroundSetupOpen && selectedTaskId == null && !conversationPicker && state.filePath == null && tab != 0) { tab = 0 }
        NativeInferenceHistory(controller)
        ConversationReader(controller)
        QueuedOwnerInputsPanel(controller)
        AttachmentOutcomeOverlay(controller.attachmentOutcomes)
    }
}

@Composable
private fun ChatScreen(state: PocketUiState, draft: String, onDraft: (String) -> Unit, phone: Boolean, onPhone: (Boolean) -> Unit, mode: String, onMode: (String) -> Unit, send: () -> Unit, controller: PocketWorldController, settings: () -> Unit, task: (UiTask) -> Unit, collaborate: () -> Unit,
    scroll: LazyListState, observedMessage: String, observeMessage: (String) -> Unit, newReplies: Boolean, setNewReplies: (Boolean) -> Unit, readingReady:Boolean, missingReadingAnchor:Boolean) {
    var chatTools by remember { mutableStateOf(false) }
    var composerDetails by remember { mutableStateOf(false) }
    ChatAttachmentChooser(state,controller)
    var taskMemoryReference by remember { mutableStateOf<JSONObject?>(null) }
    taskMemoryReference?.let { TaskMemoryWorkflowSheet(controller, it) { taskMemoryReference = null } }
    val ownerDraft=controller.composerDraft
    val ownerKey=ownerDraft?.key.orEmpty();val ownerEpoch=controller.composerViewEpoch
    if(!phone&&ownerDraft!=null&&ownerDraft.voiceText.isNotEmpty()&&!controller.canReviewVoiceDraft(false))RecoveredComposerVoiceReview(ownerDraft,controller)
    else VoiceInputDraftReview(state.voiceInput,controller.canReviewVoiceDraft(phone),{ id,text -> controller.useVoiceDraft(id,text,phone)?.let { onDraft(if(draft.isBlank())it else draft+"\n"+it) } },controller::discardVoiceDraft)
    val lastAssistant=state.messages.lastOrNull { it.role=="assistant"&&it.content.isNotBlank() }
    val realtimeAudioActive = state.feature("realtime").optJSONObject("session")?.optString("state") in setOf("connecting", "connected", "reconnecting", "disconnecting")
    val usesChatGptPlan = controller.usesChatGptPlan(phone)
    val planUsageLimited = usesChatGptPlan && controller.chatGptPlanUsageLimited(phone)
    var showLatest by remember { mutableIntStateOf(0) }
    val lastMessage = state.messages.lastOrNull()?.let { it.id ?: (it.role + it.at) }.orEmpty()
    LaunchedEffect(lastMessage,readingReady) {
        if (!readingReady) return@LaunchedEffect
        if (lastMessage.isNotBlank() && lastMessage != observedMessage) {
            val nearLatest = observedMessage.isBlank() || scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= scroll.layoutInfo.totalItemsCount - 2 } != false
            observeMessage(lastMessage)
            if (nearLatest) { scroll.animateScrollToItem(state.messages.lastIndex); setNewReplies(false) } else setNewReplies(true)
        }
    }
    LaunchedEffect(showLatest) { if (showLatest > 0 && state.messages.isNotEmpty()) { scroll.animateScrollToItem(state.messages.lastIndex); setNewReplies(false) } }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (state.messages.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("想聊聊，\n还是交给我办？", fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
            Text("你的对话、任务和项目，都留在同一个口袋世界。", fontSize = 14.sp, lineHeight = 23.sp, color = SecondaryInk, modifier = Modifier.padding(top = 15.dp, bottom = 30.dp))
            Suggestion("聊个问题", "一起理清思路") { onPhone(false) }
            Suggestion("帮我操作手机", "在独立后台屏执行") { onPhone(true); onMode("background") }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation_messages"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.messages, key = { it.id ?: (it.role + it.at + it.content.hashCode()) }) { message -> Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start) {
                var messageDetails by remember(message.id, message.at) { mutableStateOf(false) }
                val hasMessageActions = message.id != null || message == lastAssistant || message.memoryReference != null
                if (message.role != "user") Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                        .border(.5.dp, Line, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        AffectGlyph("spark", Modifier.size(14.dp))
                    }
                    Text("口袋世界", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ActionInk)
                    Spacer(Modifier.weight(1f))
                    if (hasMessageActions) AffectToolButton("more", if(messageDetails) "收起消息操作与来源" else "消息操作与来源", { messageDetails = !messageDetails })
                }
                if (message.role == "assistant") RichConversationMessageBody(message.content, Modifier.fillMaxWidth())
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                    if (message.role == "user" && hasMessageActions) AffectToolButton("more", if(messageDetails) "收起消息操作与来源" else "消息操作与来源", { messageDetails = !messageDetails })
                    SelectionContainer(Modifier.weight(1f, fill = false)) { Text(message.content, color = Ink, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.widthIn(max = 310.dp).clip(Rounded).background(Soft).padding(horizontal = 15.dp, vertical = 12.dp)) }
                }
                if(message==lastAssistant && (messageDetails || state.speaking))Column {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { TextButton(onClick={controller.speak(message.content)},enabled=!state.voiceInput.optBoolean("active")&&!state.voiceInput.optBoolean("leaseHeld")) { Text("朗读这条原回复",fontSize=12.sp) };if(state.speaking)TextButton(onClick=controller::stopSpeaking){Text("停止朗读，任务继续",fontSize=12.sp)} }
                    if(state.speaking||state.systemSpeech.optString("ttsStatus") in setOf("unavailable","language_data_missing","language_unsupported"))Text(systemTtsDescription(state.systemSpeech),fontSize=12.sp,lineHeight=19.sp,color=SecondaryInk)
                }
                message.attachments.forEach { file -> ConversationFileRow(file, state, { message.id?.let { controller.openConversationAttachment(it, file) } }) }
                if(message.attachmentCount>message.attachments.size)Text("${message.attachmentCount-message.attachments.size} 个附件（名称未保存，无法精确打开）",fontSize=12.sp,color=SecondaryInk)
                if(message.attachmentCount>0&&message.attachments.isEmpty()){
                    if(message.attachmentFiles.isEmpty())Text("原消息有 "+message.attachmentCount+" 个附件引用 · 来源暂不可读",fontSize=11.sp,color=SecondaryInk)
                    for(file in message.attachmentFiles)TextButton(onClick={controller.openAttachmentOutcome(file)},enabled=file.optString("availability")=="available",contentPadding=PaddingValues(0.dp)){Text(file.optString("name","原消息附件")+" · "+file.optString("mimeType"),fontSize=12.sp,maxLines=2)}
                }
                message.submission?.let{ChatInputReceipt(it,controller)}
                if(messageDetails) {
                    message.id?.let { id -> SourceMemoryControl("message", id, message.content, state, controller, compact = true) }
                    message.memoryReference?.let { reference -> TextButton(onClick = { taskMemoryReference = JSONObject(reference.toString()) }) { Text("本次提供的记忆来源", fontSize = 11.sp) } }
                }
                (message.taskId ?: message.submission?.optString("taskId"))?.takeIf { it.isNotBlank() }?.let { id ->
                    if (state.messages.lastOrNull { (it.taskId ?: it.submission?.optString("taskId")) == id }?.id == message.id) state.tasks.find { it.id == id }?.let { linked ->
                        ConversationTaskJournal(TaskPresentation.fromTaskStore(linked.raw), { if (teamRunReferences(linked).isNotEmpty()) controller.openTeam(teamRunReferences(linked).first()) else controller.focusTask(it) }, controller::getTask)
                    }
                }
            } }
        }
        if(missingReadingAnchor)Text("原阅读锚不在当前窗口，仍保留原位置；滚动或点击回到最新后才更新。",fontSize=11.sp,color=SecondaryInk,modifier=Modifier.padding(horizontal=22.dp))
        if (newReplies) TextButton(onClick = { showLatest++ }, modifier = Modifier.fillMaxWidth().testTag("conversation_new_replies")) { Text("有新回复 · 回到最新", fontSize = 13.sp) }
        state.chatReceipts.values.lastOrNull{it.optString("status")=="unconfirmed" && state.messages.none { row -> row.submission?.optString("clientMessageId") == it.optString("clientMessageId") }}?.let{Box(Modifier.padding(horizontal=22.dp)){ChatInputReceipt(it,controller)}}
        state.tasks.find { it.id == state.activeTaskId }?.let { active -> Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp)) { CompactTask(active, { task(active) }); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { if (active.status == "paused") controller.continuePhone() else controller.pausePhone() }) { Text(if (active.status == "paused") "继续任务" else "暂停") }; TextButton(onClick = controller::stopPhone, colors = ButtonDefaults.textButtonColors(contentColor = Danger)) { Text("停止") } } } }
        state.modelDrafts.values.filter { draft -> state.tasks.any { it.id == draft.taskId && it.raw.optString("conversationId").isNotBlank() && it.raw.optString("conversationId") == state.conversationId } }.forEach { preview -> Box(Modifier.padding(horizontal = 22.dp)) { ModelStreamPreview(preview) } }
        if (state.chatBusy) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Ink); Text("正在回复…", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.weight(1f).padding(start = 9.dp)); TextButton(onClick = controller::cancelChat) { Text("停止回复", fontSize = 12.sp) } }
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (phone) Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onMode(if (mode == "background") "main_screen" else "background") }, contentPadding = PaddingValues(horizontal = 2.dp)) { Text(if (mode == "background") "后台独立屏 ▾" else "主屏操作 ▾", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f)); TextButton(onClick = { if (mode == "background") controller.openBackgroundSetup() else settings() }) { Text(if (mode == "background" && !state.feature("display").optBoolean("helperConnected")) "启用手机控制" else "执行设置", fontSize = 12.sp) }
            }
            if(phone||!state.chatBusy)ExecutionScopeStartControls(phone,controller.newTaskToolBudget,controller.newTaskModelBudget,controller.newTaskScopeMinutes,controller::selectNewTaskDeadline)
            if(!phone&&state.chatBusy)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=state.chatSendMode=="queue",onClick={controller.setChatSendMode("queue",ownerKey,ownerEpoch)},label={Text("本轮结束后处理",fontSize=11.sp)})
                FilterChip(selected=state.chatSendMode=="steer",onClick={controller.setChatSendMode("steer",ownerKey,ownerEpoch)},label={Text("下个安全步骤加入",fontSize=11.sp)})
            }
            if(!phone&&state.chatBusy&&composerDetails)Text("连续输入仍是原任务；不撤销已派发动作，不增加预算或授予权限。",fontSize=10.sp,color=SecondaryInk)
            if (usesChatGptPlan && (composerDetails || planUsageLimited)) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("使用 ChatGPT 套餐", fontSize = 12.sp, color = SecondaryInk); if (planUsageLimited) Text("当前用量受限", fontSize = 11.sp, color = Danger) }
                if (planUsageLimited) Button(onClick = controller::openChatGptUsage, modifier = Modifier.testTag("composer_chatgpt_usage")) { Text("查看用量", fontSize = 12.sp) }
                else TextButton(onClick = controller::openChatGptUsage, modifier = Modifier.testTag("composer_chatgpt_usage")) { Text("查看用量", fontSize = 12.sp) }
            }
            AffectComposerFrame(
                editor = {
            if(!phone&&ownerDraft!=null)dev.pocketengine.ui.memory.OwnerSourceDraftPanel(ownerDraft,controller,ownerEpoch)
            if(!phone&&state.selectedChatAttachments.isNotEmpty())Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) { state.selectedChatAttachments.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ConversationFileRow(file, state, { controller.openSelectedChatAttachment(file) }) }
                TextButton(onClick = { controller.removeChatAttachment(file.id,ownerKey,ownerEpoch) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("移除", fontSize = 12.sp) }
            } } }
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft, onValueChange = onDraft,
                        enabled = phone || ownerDraft != null,
                        modifier = Modifier.fillMaxWidth().testTag("conversation_composer")
                            .heightIn(min = 40.dp, max = 144.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                        maxLines = 5,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp, color = Ink),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text(if (phone) "告诉我，要在手机上做什么…" else "输入目标，或补充上下文…",
                                    fontSize = 16.sp, lineHeight = 24.sp, color = SecondaryInk)
                                inner()
                            }
                        }
                    )
                },
                toolbar = {
                    AffectToolButton("plus", if (state.attachmentImporting) "正在导入附件" else "添加附件",
                        onClick = { if(phone) controller.pickAttachments() else controller.openChatAttachmentPicker() },
                        enabled = !state.attachmentImporting && !state.chatSubmitting)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = settings, modifier = Modifier.widthIn(max = 156.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                            AffectGlyph("spark", Modifier.size(16.dp), SecondaryInk)
                            Spacer(Modifier.width(5.dp))
                            Text(state.modelName.ifBlank { "选择模型" }, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            AffectGlyph("chevron", Modifier.size(12.dp), SecondaryInk)
                        }
                    }
                    AffectToolButton(if(state.voiceInput.optBoolean("active") || state.listening) "close" else "mic",
                        if(realtimeAudioActive) "语音会话" else if(state.voiceInput.optBoolean("active") || state.listening) "取消本次听写" else "语音输入",
                        onClick = { if (realtimeAudioActive) controller.openConnection("realtime") else if (state.voiceInput.optBoolean("active") || state.listening) controller.stopListening() else controller.startSystemVoiceInput(phone) },
                        enabled = realtimeAudioActive || state.voiceInput.optBoolean("active") || state.listening || state.speechAvailable && !state.voiceInput.optBoolean("reviewAvailable") && !state.voiceInput.optBoolean("staged") && !state.voiceInput.optBoolean("resourceReleaseUnknown"))
                    val canSend = ChatInputRules.canSend(state,phone,draft) && (phone || ownerDraft != null) && !state.voiceInput.optBoolean("active") && !planUsageLimited
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = send, enabled = canSend,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if(canSend) Ink else Color(0xffdedede))
                                .semantics { contentDescription = if(phone) "开始办事" else if(state.chatSubmitting) "正在登记" else if(state.chatBusy) "加入原任务" else "发送" }) {
                            if(state.chatSubmitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = SecondaryInk)
                            else AffectGlyph("arrow", color = if(canSend) Color.White else Color(0xff999999))
                        }
                    }
                },
                footer = {
                    Box {
                        TextButton(onClick = { chatTools = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(if(phone) if(mode == "background") "手机 · 后台独立屏" else "手机 · 主屏" else "当前对话", fontSize = 13.sp, color = SecondaryInk)
                            AffectGlyph("chevron", Modifier.size(12.dp), SecondaryInk)
                        }
                        DropdownMenu(expanded = chatTools, onDismissRequest = { chatTools = false }) {
                            DropdownMenuItem(text = { Text("对话") }, onClick = { chatTools = false; onPhone(false) })
                            DropdownMenuItem(text = { Text("操作手机") }, onClick = { chatTools = false; onPhone(true) })
                            DropdownMenuItem(text = { Text("团队协作") }, onClick = { chatTools = false; collaborate() })
                            DropdownMenuItem(text = { Text("网页研究") }, onClick = { chatTools = false; controller.openBrowserResearch() })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { composerDetails = !composerDetails }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(if(composerDetails) "收起选项" else "输入选项", fontSize = 13.sp, color = SecondaryInk)
                    }
                }
            )
            if(!phone) {
                if (ownerDraft == null || controller.composerSaving || composerDetails) Text(
                    if (ownerDraft == null) "正在恢复输入…" else if (controller.composerSaving) "正在保存草稿…" else "草稿已保存在本机",
                    fontSize = 13.sp, color = SecondaryInk)
                if(controller.composerPending.isNotEmpty())Column(Modifier.heightIn(max=170.dp).verticalScroll(rememberScrollState())) {
                    controller.composerPending.forEach{item->Column { Row(verticalAlignment=Alignment.CenterVertically){Text(if(controller.canRetryLocallyUnsent(item.clientId))"本进程确认未派发，原输入仍保留" else if(item.draftKey==ownerKey)"本输入登记待核对，未自动重发" else "另有原输入待核对",fontSize=11.sp,color=SecondaryInk,modifier=Modifier.weight(1f));if(item.draftKey!=ownerKey)TextButton(onClick={controller.returnToPendingComposer(item.clientId)},enabled=!state.chatBusy&&!state.chatSubmitting){Text("回原输入",fontSize=11.sp)};TextButton(onClick={controller.queryChatSubmission(item.clientId)}){Text("查询原回执",fontSize=11.sp)}};if(controller.canRetryLocallyUnsent(item.clientId))TextButton(onClick={controller.retryLocallyUnsent(item.clientId)}){Text("重新保存并发送这条原输入",fontSize=11.sp)} } }
                }
            }
            if (composerDetails || state.voiceInput.optString("message").isNotBlank()
                || state.voiceInput.optBoolean("active") || state.voiceInput.optBoolean("leaseHeld")
                || state.voiceInput.optBoolean("reviewAvailable") || state.voiceInput.optBoolean("resourceReleaseUnknown")) {
                SystemVoiceInputStatus(state.voiceInput, state.systemSpeech)
            }
        }
    }
}

@Composable
private fun RecoveredComposerVoiceReview(draft:dev.pocketengine.features.assistant.OwnerComposerState.Draft,controller:PocketWorldController) {
    var edited by remember(draft.key,draft.voiceInputId){mutableStateOf(draft.voiceText)}
    AlertDialog(onDismissRequest={controller.discardRecoveredComposerVoice(draft.key,draft.revision)},title={Text("核对保留的原听写文字")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("这是原私聊保留的最终文字，不会恢复录音或旧执行授权。请重新核对，放入本输入后仍需你发送。",fontSize=12.sp);OutlinedTextField(edited,{if(it.length<=4000)edited=it},minLines=3,maxLines=8)}},
        confirmButton={TextButton(onClick={controller.useRecoveredComposerVoice(draft.key,draft.revision,edited)},enabled=edited.isNotBlank()){Text("放入原输入，暂不发送")}},
        dismissButton={TextButton(onClick={controller.discardRecoveredComposerVoice(draft.key,draft.revision)}){Text("丢弃这份听写")}})
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TaskSheet(task: UiTask, active: Boolean, controller: PocketWorldController, returnLabel: String = "返回", close: () -> Unit) {
    if (task.raw.optString("kind") == "inference") { InferenceRecordSheet(task, controller, returnLabel, close); return }
    var taskMemoryReference by remember(task.id) { mutableStateOf<JSONObject?>(null) }
    taskMemoryReference?.let { TaskMemoryWorkflowSheet(controller, it) { taskMemoryReference = null } }
    var feedback by rememberSaveable(task.id) { mutableStateOf("") }
    var resumeConsent by remember(task.id) { mutableStateOf(false) }
    var reconciliationNote by rememberSaveable(task.id) { mutableStateOf("") }
    var capabilityPreparation by remember(task.id) { mutableStateOf(false) }
    var auditDetails by rememberSaveable(task.id) { mutableStateOf(false) }
    val host = controller.state
    LaunchedEffect(host.savedFeedbackRevision) { if (host.savedFeedbackTaskId == task.id && feedback.trim() == host.savedFeedback.trim()) feedback = "" }
    val phoneTask = isPhoneTask(task)
    val groupTask = task.raw.optJSONObject("origin")?.optString("kind") == "group"
    DisposableEffect(task.id, groupTask) {
        val page = if (groupTask) controller.enterGroupTask(task.id) else null
        onDispose { if (page != null) controller.leaveGroupTask(page) }
    }
    val sourceLearningTask = task.raw.optJSONObject("sourceLearningJob") != null
    val memoryConsolidationTask = sourceLearningTask || task.raw.optJSONObject("nativeEventTypeFlags")?.optBoolean("memory_owner_consolidation") == true || task.raw.optJSONArray("events").objects().any { it.optString("type") == "memory_owner_consolidation" }
    val mediaTask = MediaOwnerUi.isTask(task.raw)
    LaunchedEffect(task.id, groupTask) { if (!groupTask) controller.refreshWorkspaceArtifacts() }
    val assistantTask = task.raw.optString("kind") == "assistant" && !mediaTask && !memoryConsolidationTask
    val assistantExecuting = assistantTask && task.status in setOf("preparing", "running", "pausing", "stopping")
    val unknown = unresolvedOperations(task)
    val processScope = rememberCoroutineScope()
    val approvalAnchor = remember(task.id) { BringIntoViewRequester() }
    val reconciliationAnchor = remember(task.id) { BringIntoViewRequester() }
    val resultAnchor = remember(task.id) { BringIntoViewRequester() }
    val feedbackAnchor = remember(task.id) { BringIntoViewRequester() }
    ModalBottomSheet(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
            TextButton(onClick = close, modifier = Modifier.testTag("task_return_source")) { Text(returnLabel, fontSize = 14.sp) }
            Text("${taskStatus(task.status)} · ${executionLocation(task.executionMode)}", color = SecondaryInk, fontSize = 14.sp)
            Text(task.goal, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
            if (!groupTask && !mediaTask && !memoryConsolidationTask) ExecutionScopeControls(task,controller)
            if (!groupTask && !mediaTask && !memoryConsolidationTask && (phoneTask || assistantTask) && teamRunReferences(task).isEmpty()) TaskExecutionProcess(task, controller) { target ->
                val anchor = when (target) {
                    TaskExecutionPresentation.Target.APPROVAL -> approvalAnchor
                    TaskExecutionPresentation.Target.RECONCILIATION -> reconciliationAnchor
                    TaskExecutionPresentation.Target.RESULT -> resultAnchor
                    else -> feedbackAnchor
                }
                processScope.launch { anchor.bringIntoView() }
            }
            if (assistantTask && !groupTask && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" && task.raw.optJSONObject("origin")?.optString("kind") != "channel" && teamRunReferences(task).isEmpty()) TextButton(onClick = { controller.openBrowserResearch(task.id) }, modifier = Modifier.testTag("task_browser_research")) { Text("网页与研究工作区") }
            val queuedConversation=task.raw.optString("conversationId")
            if(assistantTask&&!groupTask&&queuedConversation.isNotBlank()&&task.raw.optString("actorId")=="owner"&&task.raw.optString("spaceId")=="private"&&task.raw.optJSONObject("origin")?.optString("kind") !in setOf("channel","group"))TextButton(onClick={controller.openQueuedOwnerInputs(task.id,queuedConversation)},modifier=Modifier.testTag("task_queued_inputs")){Text("查看待处理输入")}
            task.raw.optJSONObject("developmentContext")?.let { project -> TextButton(onClick = { controller.returnToDevelopmentProject(project.getString("projectId")); close() }) { Text("回到项目文件与网页预览") } }
            if (taskOriginLabel(task) == "主人私聊") {
                val origin = task.raw.getJSONObject("origin")
                Text("来自已认证主人私聊 · " + origin.optString("senderId"), fontSize = 12.sp, color = SecondaryInk)
                Text("原任务与渠道投递分别记录。写入与外发仍需本机审批；关闭或重绑渠道后不能沿用旧授权继续。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                TextButton(onClick = { controller.openConnection("channels"); close() }) { Text("查看来源与投递", fontSize = 12.sp) }
            }
            if (groupTask) {
                val origin = task.raw.getJSONObject("origin")
                Text("认证群原任务 · " + origin.optString("groupId") + " · " + if (origin.optString("audience") == "group") "结果回到原群" else "发起人受众（须由通道支持）", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
                Text("群目标、来源和受众保持不变；不会读私人记忆或导入私人补充。审批与继续只在当前本机原任务窗口生效。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk)
                if (!controller.groupTaskReady) TextButton(onClick = controller::refreshGroupTaskWindow) { Text("重新核对本机审核窗口") }
                TextButton(onClick = { controller.openConnection("groups"); close() }) { Text("查看群授权、成员与投递") }
            }
            if (taskOriginLabel(task) == "语音复核") Text("来自你核对的本机最终字幕；服务来源标记不是身份认证。保存只建立待执行任务，后续工具与外发仍需原任务授权。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(bottom = 10.dp))
            if (active) Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { if (task.status == "paused") controller.continuePhone() else controller.pausePhone() }) { Text(if (task.status == "paused") "继续" else "暂停") }; TextButton(onClick = controller::stopPhone, colors = ButtonDefaults.textButtonColors(contentColor = Danger)) { Text("停止后续操作") } }
            if (assistantExecuting) TextButton(onClick = { controller.stopReviewedTask(task) }, colors = ButtonDefaults.textButtonColors(contentColor = Danger), modifier = Modifier.padding(top = 12.dp)) { Text("停止后续工具操作") }
            if (!groupTask && task.raw.optJSONObject("origin")?.optString("kind") != "channel" && !mediaTask && !memoryConsolidationTask && (phoneTask || assistantTask) && task.status in setOf("preparing", "running", "pausing", "paused", "awaiting_user")) {
                val mainScreenBusy = phoneTask && task.executionMode == "main_screen" && task.status !in setOf("paused", "awaiting_user")
                TextButton(onClick = { controller.openQuickPanel(task) }, enabled = !mainScreenBusy, modifier = Modifier.testTag("task_quick_panel")) { Text("在其他 App 补充上下文") }
                if (mainScreenBusy) Text("先暂停主屏任务，再切到需要的 App 捕获内容。", color = SecondaryInk, fontSize = 12.sp)
            }
            host.taskActionErrors[task.id]?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp)) }
            Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(approvalAnchor))
            if (!mediaTask && (!groupTask || controller.groupTaskReady) && pendingCapabilityApprovals(task).isNotEmpty()) CapabilityApprovals(task, controller)
            Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(reconciliationAnchor))
            if (unknown.isNotEmpty() && !mediaTask && !sourceLearningTask) Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(Rounded).background(Soft).padding(16.dp)) {
                Text("先核对可能已发生的动作", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("确认前任务会等待，避免重复发送或提交。", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                unknown.forEach { operation -> val id = operation.optString("operationId", operation.optString("id")); Column(Modifier.padding(top = 14.dp)) { Text(operation.optString("summary", operation.optString("tool", "待核对操作")), fontSize = 13.sp); Row { TextButton(onClick = { controller.resolveReviewedTask(task, id, "occurred") }, enabled = id.isNotBlank()) { Text("已经发生") }; TextButton(onClick = { controller.resolveReviewedTask(task, id, "not_occurred") }, enabled = id.isNotBlank()) { Text("没有发生") } } } }
            }
            host.modelDrafts[task.id]?.let { ModelStreamPreview(it) }
            Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(resultAnchor))
            if (task.summary.isNotBlank()) {
                Text("已有结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                RichConversationMessageBody(task.summary)
            }
            val resultFiles = taskResultAttachments(task)
            if (resultFiles.isNotEmpty()) {
                Text("已保存成果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                resultFiles.forEach { file -> ConversationFileRow(file, host, { controller.openTaskResultAttachment(task.id, file) }) }
            }
            if (!groupTask && task.raw.optString("spaceId", "private") == "private") TextButton(onClick = { taskMemoryReference = task.raw.optJSONObject("evidence")?.optJSONObject("memoryReference")?.let { JSONObject(it.toString()) } ?: JSONObject().put("taskId", task.id).put("runId", task.raw.optString("runId").ifBlank { "unavailable" }).put("requestId", "unavailable").put("answerId", "unavailable") }) { Text("这次请求的记忆来源与原证据", fontSize = 12.sp) }
            val projectArtifacts = if (groupTask) emptyList() else workspaceArtifacts(host, taskId = task.id)
            if (projectArtifacts.isNotEmpty()) {
                Text("项目文件成果", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                projectArtifacts.forEach { WorkspaceArtifactRow(it, controller, task.id) }
            }
            host.error?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
            if(!groupTask&&!memoryConsolidationTask)TextButton(onClick={controller.openTaskOutcomes(task.id)}){Text("文件、来源与可继续成果")}
            if(groupTask)for(operation in task.raw.optJSONArray("operations").objects().filter{it.optString("tool").startsWith("group_media_")})TextButton(onClick={controller.openGroupMediaOutcome(task,operation.optString("operationId"))}){Text("查看原媒体操作与成果")}
            if (memoryConsolidationTask) {
                Text("这是你明确请求的候选提炼记录；完成只表示提炼处理结束，不代表已采用记忆。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
                TextButton(onClick = { controller.openConnection("memoryReview"); close() }) { Text("打开原记忆候选审阅") }
            }
            if (mediaTask) MediaTaskPanel(task, host, controller, showArtifacts = resultFiles.isEmpty())
            else if ((!groupTask || controller.groupTaskReady) && pendingCapabilityApprovals(task).isEmpty()) CapabilityApprovals(task, controller)
            if (!groupTask && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private") TextButton(onClick = { capabilityPreparation = true }) { Text("这项任务的技能与工具", fontSize = 12.sp) }
            RemoteTaskApprovals(task, controller)
            RemoteSteerPanel(task.raw, host.moduleBusy.any { it in setOf("remote.steerJob", "remote.getSteerMessage") },
                host.moduleErrors["remote.steerJob"] ?: host.moduleErrors["remote.getSteerMessage"],
                host.moduleResults["remote.steerJob"], host.moduleResultInputs["remote.steerJob"]) { command, payload -> controller.requestFeature("remote", command, payload) }
            teamRunReferences(task).forEach { runId -> OutlinedButton(onClick = { controller.openTeam(runId) }, modifier = Modifier.padding(top = 12.dp)) { Text("查看团队成员、会话与任务") } }
            if (phoneTask && task.executionMode == "background" && task.status != "completed") OutlinedButton(onClick = { controller.openInspection(task.id) }, modifier = Modifier.padding(top = 14.dp)) { Text("查看独立屏与接管") }
            if (phoneTask && task.status == "reconciling" && unknown.isEmpty()) Text("正在读取待核对的动作，请稍候或重新打开此任务。", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            if (!phoneTask && task.raw.optString("kind") != "assistant" && task.status == "reconciling" && unknown.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 18.dp).clip(Rounded).background(Soft).padding(16.dp)) {
                Text("核对外部执行结果", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("先到对应服务或电脑检查实际结果，再记录核对结论。这里不会自动重试。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(reconciliationNote, { reconciliationNote = it }, label = { Text("核对说明") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { controller.reconcileExternalTask(task.id, "not_occurred", reconciliationNote) }, enabled = reconciliationNote.isNotBlank()) { Text("确认未发生") }; Button(onClick = { controller.reconcileExternalTask(task.id, "occurred", reconciliationNote) }, enabled = reconciliationNote.isNotBlank()) { Text("确认已发生") } }
            }
            if (task.status == "completed" && !groupTask && !memoryConsolidationTask) SourceMemoryControl("task", task.id, task.summary, host, controller)
            TaskImage(task)
            if (!active && task.status == "needs_confirmation") Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { controller.confirmReviewedTask(task, true, if (groupTask) "" else feedback) }) { Text("确认已完成") }; OutlinedButton(onClick = { controller.confirmReviewedTask(task, false, if (groupTask) "" else feedback) }) { Text("还需处理") } }
            if (groupTask && task.status != "completed" && !assistantExecuting) Button(onClick = { resumeConsent = true }, enabled = controller.groupTaskReady && !task.raw.optBoolean("cancelRequested") && unknown.isEmpty() && pendingCapabilityApprovals(task).isEmpty() && task.status in setOf("paused", "awaiting_user", "needs_confirmation", "interrupted"), modifier = Modifier.padding(top = 12.dp)) { Text("按原群授权继续原任务") }
            Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(feedbackAnchor))
            if (task.status != "completed" && !groupTask && !memoryConsolidationTask && !mediaTask) {
                OutlinedTextField(value = feedback, onValueChange = { feedback = it }, label = { Text("补充要求") }, placeholder = { Text("补充目标或说明你刚处理了什么…") }, modifier = Modifier.fillMaxWidth().padding(top = 22.dp), minLines = 2, maxLines = 5, shape = RoundedCornerShape(14.dp))
                if (assistantExecuting) Text("先停止当前执行，再将补充保存并继续原任务。", fontSize = 11.sp, color = SecondaryInk, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { controller.updateTask(task.id, feedback) }, enabled = feedback.isNotBlank() && !assistantExecuting) { Text("保存补充") }; if (!active && !assistantExecuting && (phoneTask || assistantTask)) Button(onClick = { resumeConsent = true }, enabled = unknown.isEmpty() && pendingCapabilityApprovals(task).isEmpty() && task.status != "reconciling" && task.status != "stopping") { Text("继续此任务") } }
            }
            if (!phoneTask && !assistantTask && !mediaTask) Text(if (task.raw.optString("feature") == "remote") "原电脑会话与本次授权可从上方入口核对；未知结果仅查询，不会重提任务。" else "这是${executionLocation(task.executionMode)}任务，后续操作请回到对应能力入口；此处保留执行结果与记录。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp))
            if (sourceLearningTask) dev.pocketengine.ui.memory.SourceLearningJobPanel(task.raw, controller) { close(); controller.openConnection("memoryReview") }
            AssistantTaskNoticeStatus(task.raw,controller)
            TextButton(onClick = { auditDetails = !auditDetails }, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (auditDetails) "收起用量与原始记录" else "用量与原始记录", fontSize = 14.sp)
            }
            if (auditDetails) {
                if (task.raw.optJSONObject("historyEventPage")?.optBoolean("hasMore") == true || task.raw.optJSONObject("historyEventPage")?.optBoolean("readLimited") == true)
                    Text("用量来自当前记录窗口；完整记录可继续按页查看。", color = SecondaryInk, fontSize = 13.sp)
                TaskInferencePanel(task.raw, controller::reestimateInference, controller::openChatGptUsage)
                TaskAuditPanel(task.id, controller)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (resumeConsent) AlertDialog(onDismissRequest = { resumeConsent = false }, containerColor = Color.White, title = { Text("继续原任务") }, text = { Text(if (groupTask) "保留原群目标、来源、受众和累计额度，只从原任务续接；群或成员授权已变化、媒体已生成或效果未知时会拒绝，不会创建替代任务。" else if (assistantTask) "保留原任务目标、上下文与补充要求，继续调用已授权的工具。新的写操作仍需宿主授权。" else if (task.executionMode == "background") "将使用后台独立屏，读取保留的上下文并重新观察当前现场；独立屏不可用时会等待。" else "将操作手机主屏并占用当前屏幕；任务会先重新观察，再根据保留的上下文继续。") }, confirmButton = { Button(onClick = { controller.resumeTask(task, if (groupTask) "" else feedback); resumeConsent = false }) { Text("继续执行") } }, dismissButton = { TextButton(onClick = { resumeConsent = false }) { Text("取消") } })
    if (capabilityPreparation) TaskCapabilityPage(task.id, controller.skillExecutionHost(), controller::runPluginCatalogWork,
        { controller.taskCapabilitySnapshot(task.id) }, { operation, artifact, sha -> controller.readTaskSkillArtifact(task.id, operation, artifact, sha) },
        { plugin, tool -> capabilityPreparation = false; close(); controller.openTaskMcpPreparation(task.id, plugin, tool) }, { capabilityPreparation = false }, controller::runEcosystemControlWork, jsHost = controller.pluginExecutionHost())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProjectsScreen(state: PocketUiState, controller: PocketWorldController, create: () -> Unit, newFile: () -> Unit) {
    var choosing by remember(state.projectId) { mutableStateOf(state.projectId == null) }
    var creating by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("web") }
    val view = state.projectId?.let { controller.projectWorkbench.view(it) } ?: 0
    var choosingCheckout by remember(state.projectId) { mutableStateOf(false) }
    var versions by remember(state.projectId) { mutableStateOf(false) }
    val projectCheckouts = state.feature("local").optJSONArray("checkouts").objects().filter { it.optString("projectId") == state.projectId }.sortedByDescending { it.optLong("createdAt") }
    val pinnedCheckout = state.projectId?.let { controller.projectWorkbench.checkout(it) }
    val active = projectCheckouts.firstOrNull { it.optString("id") == pinnedCheckout }
    ProjectPackageOwnerScope(active, state.projectId, controller)
    LaunchedEffect(state.projectId) { state.projectId?.let { controller.openDevelopmentProject(it) } }
    LazyColumn(Modifier.fillMaxSize(), state = controller.projectWorkbench.page(state.projectId ?: "project-selection", view), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        state.projectTaskReturn?.takeIf { it.projectId == state.projectId }?.let { source -> item { TextButton(onClick = controller::returnFromWorkspaceProject, enabled = state.tasks.any { it.id == source.taskId }, modifier = Modifier.testTag("project_return_original_task")) { Text("返回刚才的原任务") } } }
        stickyHeader(key = "project_header") {
            Column(Modifier.fillMaxWidth().background(PageSurface)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.projects.firstOrNull { it.id == state.projectId }?.name ?: "选择一个项目",
                        style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { choosing = !choosing }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (choosing) "收起项目列表" else "切换项目", fontSize = 14.sp)
                    }
                }
                TextButton(onClick = { versions = true }) { Text("项目工具", fontSize = 14.sp) }
            }
            if (state.projectId == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }) { Text("新建项目") }
                OutlinedButton(onClick = controller.projectPackages::openSourceImport, enabled = !controller.projectPackages.state.busy) { Text("导入项目") }
            }
                if (state.projectId != null && active != null) {
                    PocketSegmentedTabs(listOf("Agent", "文件", "运行", "预览"), view) { index ->
                        controller.projectWorkbench.selectView(state.projectId!!, index)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        if (state.projects.isEmpty()) item { EmptyState("从一个真实项目开始", "选择Web或Python模板，或从系统文件选择器导入已有源码。文件保存在这台手机，工具准备状态会单独显示。") }
        if (choosing || state.projectId == null) items(state.projects, key = { it.id }) { project -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (state.projectId == project.id) Soft else Color.White).clickable { controller.selectProject(project.id); choosing = false }.padding(14.dp)) { Text(project.name, fontSize = 15.sp, fontWeight = if (state.projectId == project.id) FontWeight.SemiBold else FontWeight.Normal) } }
        if (state.projectId != null) {
            item {
                if (active == null) { if (pinnedCheckout == null) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (pinnedCheckout == null) "正在打开原项目工作区" else "原工作副本暂不可用。草稿仍绑定原副本，不会移到更新的副本。", color = SecondaryInk, fontSize = 13.sp); state.moduleErrors["owner.openDevelopmentProject"]?.let { Text(it, color = Danger, fontSize = 13.sp) }; TextButton(onClick = { controller.openDevelopmentProject(state.projectId!!) }) { Text("重新核对项目") } }
                else {
                    if (view == ProjectWorkbenchFacet.Files.index) LocalFiles(active.getString("id"), state, controller)
                    else NativeProjectDevelopment(active, state, controller, onManualTools = { controller.openConnection("local") }, surface = ProjectWorkbenchFacet.entries.firstOrNull { it.index == view } ?: ProjectWorkbenchFacet.Agent)
                }
            }
        }
    }
    if (versions) ModalBottomSheet(
        modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp),
        onDismissRequest = { versions = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Text("项目工具", style = MaterialTheme.typography.titleLarge)
                Text("版本、附件与交付，不打断当前工作区。", color = SecondaryInk,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { versions = false; creating = true }) { Text("新建项目") }
                    TextButton(onClick = { controller.projectPackages.openSourceImport() }, enabled = !controller.projectPackages.state.busy) { Text("导入已有项目") }
                    TextButton(onClick = { controller.openConnection("local") }) { Text("运行环境") }
                }
            }
            if (state.projectId != null && projectCheckouts.isNotEmpty()) item {
                TextButton(onClick = { choosingCheckout = !choosingCheckout }) {
                    Text(if (choosingCheckout) "收起工作副本" else "选择工作副本")
                }
                if (choosingCheckout) projectCheckouts.forEach { checkout ->
                    TextButton(onClick = {
                        controller.closeProjectPreview()
                        controller.projectWorkbench.selectCheckout(state.projectId!!, checkout.getString("id"), replace = true)
                        choosingCheckout = false
                    }) { Text("副本 ${checkout.getString("id").take(8)}" + if (checkout.getString("id") == pinnedCheckout) " · 当前" else " · 保留独立草稿") }
                }
            }
            item { NativeProjectPackageWorkbench(active, state.projectId, controller) }
            if (state.projectId != null) {
                item {
                    Text("已保存版本", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = controller::exportProject, enabled = !state.exportPending && "workspace.prepareExport" !in state.moduleBusy) { Text("导出文字版本库") }
                        TextButton(onClick = { versions = false; newFile() }) { Text("新建版本文件") }
                    }
                    if (state.files.isEmpty()) Text("保存的版本会显示在这里。", color = SecondaryInk)
                }
                items(state.files, key = { "version:" + it.path }) { file ->
                    Row(Modifier.fillMaxWidth().clickable { controller.openFile(file.path) }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(file.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Text("v${file.version}", color = SecondaryInk, fontSize = 13.sp)
                    }
                    HorizontalDivider(color = Line)
                }
                item { Text("这里保存的是版本。正在编辑的文件位于项目的「文件」切面，导回前仍需审阅差异。",
                    color = SecondaryInk, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
                val artifacts = workspaceArtifacts(state, projectId = state.projectId)
                item { WorkspaceArtifactHeader(state, controller) }
                items(artifacts, key = { "artifact:" + it.id }) { artifact -> WorkspaceArtifactRow(artifact, controller) }
            }
            val attachments = state.feature("attachments").optJSONArray("attachments").objects().filter { item ->
                val project = item.optJSONObject("source")?.optString("projectId").orEmpty()
                if (state.projectId == null) project.isBlank() else project == state.projectId
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.projectId == null) "附件" else "项目附件", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { controller.pickAttachments(state.projectId) }, enabled = !state.attachmentImporting) { Text("导入附件") }
                }
            }
            items(attachments, key = { "attachment:" + it.optString("id") }) { attachment ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(attachment.optString("name"), fontSize = 14.sp)
                    Text(attachment.optString("mimeType"), fontSize = 13.sp, color = SecondaryInk)
                    TextButton(onClick = { controller.openAttachmentOutcome(attachment) }) { Text("打开附件") }
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("创建项目") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(projectName, { projectName = it }, label = { Text("项目名称") }, singleLine = true)
        listOf(listOf("web" to "Web网页", "web-live" to "Web热更新"), listOf("python" to "Python", "blank" to "空项目")).forEach { choices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { (id, label) -> FilterChip(selected = template == id, onClick = { template = id }, label = { Text(label) }) } }
        }
        Text(if (template == "web-live") "实际多文件Vite项目。首次npm ci需网络与真实平台依赖；边运行边改需在原任务明确批准既有源码范围，不自动启动。" else "创建实际多文件项目。编程工具的安装与版本由本机环境面板显示。", color = SecondaryInk, fontSize = 13.sp)
    } }, confirmButton = { Button(onClick = { controller.createDevelopmentProject(projectName.trim(), template); creating = false; projectName = ""; choosing = false }, enabled = projectName.isNotBlank() && "owner.createDevelopmentProject" !in state.moduleBusy) { Text("创建并打开") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FileEditor(state: PocketUiState, controller: PocketWorldController) {
    var discard by remember { mutableStateOf(false) }
    var replaceDraft by remember { mutableStateOf(false) }
    var history by rememberSaveable(state.filePath) { mutableStateOf(false) }
    fun requestClose() { if (!state.fileSaving) { if (state.fileContent != state.fileBaseline) discard = true else controller.closeFile() } }
    val currentEditorState by rememberUpdatedState(state)
    val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        // Ask before the native sheet hides, so cancelling keeps the draft visibly mounted.
        when {
            target != SheetValue.Hidden -> true
            currentEditorState.fileSaving -> false
            currentEditorState.fileContent != currentEditorState.fileBaseline -> { discard = true; false }
            else -> true
        }
    })
    ModalBottomSheet(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), onDismissRequest = ::requestClose,
        sheetState = editorSheetState, containerColor = PageSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::requestClose, enabled = !state.fileSaving, modifier = Modifier.testTag("project_file_return")) { Text(if (state.workspaceArtifactEditorOrigin != null) "返回原成果" else "返回项目") }
                Spacer(Modifier.weight(1f))
                Button(onClick = controller::saveFile, enabled = state.fileReady && !state.fileLoading && !state.fileSaving,
                    shape = RoundedCornerShape(12.dp)) { Text(if (state.fileSaving) "保存中" else "保存") }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(state.filePath ?: "", fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)
                Text(if (!state.fileReady) "正在读取项目文件" else if (state.fileVersion == 0) "新文件 · 保存后创建" else "当前版本 v${state.fileVersion}", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val editorHeight = (maxHeight - 86.dp).coerceAtLeast(260.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                    state.workspaceArtifactEditorOrigin?.let { source -> Text("你正在修改已保存的项目版本；原成果 v${source.source.version} 仍保留。保存会核对版本，不会自动同步运行副本或执行。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(bottom = 12.dp)) }
                    if (state.fileLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    state.fileError?.let { error ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(Soft).padding(12.dp)) {
                            Text(error, fontSize = 12.sp, lineHeight = 20.sp, color = Danger)
                            if (!state.fileReady) TextButton(onClick = controller::retryFile) { Text("重新读取文件") }
                        }
                    }
                    Column(Modifier.fillMaxWidth().height(editorHeight).pocketDepth(PocketDepth.INPUT, 16.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.White).border(.5.dp, Line, RoundedCornerShape(16.dp))) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AffectGlyph("file", color = SecondaryInk)
                            Text("编辑内容", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text(if (!state.fileReady) "等待读取" else if (state.fileContent != state.fileBaseline) "未保存修改" else if (state.fileVersion == 0) "尚未创建" else "与已保存版本一致", fontSize = 12.sp, color = SecondaryInk)
                        }
                        HorizontalDivider(color = Line)
                        OutlinedTextField(value = state.fileContent, onValueChange = controller::editFile,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("project_file_editor"),
                            enabled = state.fileReady && !state.fileLoading && !state.fileSaving,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp),
                            shape = RoundedCornerShape(0.dp), colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Ink, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, disabledContainerColor = Soft))
                        Row(Modifier.fillMaxWidth().background(Soft).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.lastSavedVersion?.let { "已保存为 v$it" } ?: "保存时核对文件版本", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            if (state.fileReady && state.fileVersion > 0) TextButton(onClick = { history = !history; if (history) controller.fileHistory() }) { Text(if (history) "收起版本记录" else "查看版本记录", fontSize = 12.sp) }
                        }
                    }
                    if (state.fileReady && state.fileVersion > 0) FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = controller::exportFile, enabled = !state.exportPending && "workspace.prepareExport" !in state.moduleBusy) { Text("导出已保存版本", fontSize = 12.sp) }
                        TextButton(onClick = controller::createArtifact, enabled = state.fileContent == state.fileBaseline && "workspace.createArtifact" !in state.moduleBusy) { Text("保存为成果", fontSize = 12.sp) }
                    }
                    if (history) Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
                        Text("版本记录", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
                        state.fileHistory.forEach { revision ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("v${revision.version} · ${date(revision.at)}", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { controller.previewRevision(revision.version) }, enabled = !revision.deleted && !state.revisionLoading) { Text("查看", fontSize = 12.sp) }
                            }
                            HorizontalDivider(color = Line)
                        }
                        if (state.revisionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.revisionContent?.let { content ->
                            Text("v${state.revisionNumber} 的内容", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                            SelectionContainer { Text(content, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)) }
                            OutlinedButton(onClick = { if (state.fileContent != state.fileBaseline) replaceDraft = true else controller.useRevision() }, enabled = !state.fileSaving, modifier = Modifier.padding(top = 8.dp)) { Text("放入编辑区", fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (replaceDraft) AlertDialog(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), tonalElevation = 0.dp, onDismissRequest = { replaceDraft = false }, containerColor = Color.White, title = { Text("用历史内容替换编辑区？") }, text = { Text("当前未保存的修改将被替换。文件已保存的版本会保留，下一次保存会创建新版本。") }, confirmButton = { Button(onClick = { controller.useRevision(); replaceDraft = false }) { Text("替换编辑内容") } }, dismissButton = { TextButton(onClick = { replaceDraft = false }) { Text("保留当前修改") } })
    if (discard) AlertDialog(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), tonalElevation = 0.dp, onDismissRequest = { discard = false }, containerColor = Color.White, title = { Text("关闭文件编辑？") }, text = { Text("尚未保存的修改会离开当前编辑区。") }, confirmButton = { TextButton(onClick = { discard = false; controller.closeFile() }) { Text("放弃修改并关闭") } }, dismissButton = { Button(onClick = { discard = false }) { Text("继续编辑") } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(state: PocketUiState, controller: PocketWorldController, close: () -> Unit, connections: () -> Unit) {
    var url by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var model by remember(state.modelName) { mutableStateOf(if (state.modelRoute == "account") "" else state.modelName) }
    var key by remember { mutableStateOf("") }
    LaunchedEffect(state.modelSavedRevision) { if (state.modelSavedRevision > 0) key = "" }
    var screenshots by remember(state.screenshots) { mutableStateOf(state.screenshots) }
    var provider by remember(state.modelProvider) { mutableStateOf(if (state.modelRoute == "account") "openai_chat" else state.modelProvider) }
    var supportsImageInput by remember(provider, url, model, state.modelProvider, state.baseUrl, state.modelName, state.supportsImageInput, state.modelRoute) { mutableStateOf(state.modelRoute != "account" && provider == state.modelProvider && url.trim() == state.baseUrl && model.trim() == state.modelName && state.supportsImageInput) }
    var editApi by remember(state.modelRoute) { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }
    var providerOptions by remember { mutableStateOf(false) }
    var outputTokens by remember(state.maxOutputTokens) { mutableStateOf(state.maxOutputTokens.toString()) }
    val configuredOutput = if (provider == "openai_chat") state.maxOutputTokens else outputTokens.toIntOrNull() ?: 0
    val requiredCleartextConsent = remember(provider, url) { try { ModelConfig.cleartextConsentFor(provider, url) } catch (_: Exception) { null } }
    var acceptedCleartextConsent by remember { mutableStateOf(state.cleartextConsent.takeIf { it == requiredCleartextConsent }.orEmpty()) }
    var previousConsentTarget by remember { mutableStateOf(requiredCleartextConsent) }
    var cleartextDialog by remember { mutableStateOf(false) }
    LaunchedEffect(provider, requiredCleartextConsent) { if (previousConsentTarget != requiredCleartextConsent) { acceptedCleartextConsent = ""; previousConsentTarget = requiredCleartextConsent } }
    val transportConfirmed = requiredCleartextConsent.isNullOrEmpty() || acceptedCleartextConsent == requiredCleartextConsent
    val consentPayload = acceptedCleartextConsent.takeIf { !requiredCleartextConsent.isNullOrEmpty() && it == requiredCleartextConsent }.orEmpty()
    val providers = mapOf("openai_chat" to "OpenAI Chat Completions", "openai_responses" to "OpenAI Responses", "anthropic" to "Anthropic Messages", "gemini" to "Gemini")
    ModalBottomSheet(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PageSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设置与连接", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                AffectToolButton("close", "关闭设置与连接", close)
            }
            Box(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
                PocketSegmentedTabs(listOf("模型", "手机", "连接"), settingsSection) { settingsSection = it }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (settingsSection == 0) SettingsSurface(state.modelName.ifBlank { "选择模型" }, if (state.modelRoute == "account") "ChatGPT 套餐" else "API 连接") {
                    SettingEntry("ChatGPT 账号与模型目录", "连接账号，选择可用模型") { controller.openConnection("accounts") }
                    HorizontalDivider(color = Line)
                    SettingEntry("API 模型目录", "核对能力与请求参数") { controller.openConnection("models") }
                    HorizontalDivider(color = Line)
                    SettingEntry(if (editApi) "收起连接编辑" else "编辑 API 连接", "接口地址、密钥与输入能力") { editApi = !editApi }
            if (state.modelRoute == "account") {
                Text("当前账号模型：" + state.modelName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                Text("支持文字与工具调用，暂不支持截图、音频和视频。账号刷新、停用或退出后可能需要重新选择目录模型；不会自动换号、降级或重试付费请求。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
                OutlinedButton(onClick = controller::useApiKeyModel, enabled = !state.modelSaving, modifier = Modifier.padding(top = 12.dp)) { Text(if (state.modelSaving) "切换中…" else "切回已保存的 API 密钥模型") }
                TextButton(onClick = { editApi = !editApi }) { Text(if (editApi) "收起 API 连接编辑" else "配置新的 API 密钥模型") }
                state.error?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
                }
            if (settingsSection == 0 && editApi) SettingsSurface("API 模型配置", "连接参数、输入能力与回复预算") {
            if (state.modelRoute == "account") Text("保存后会明确切换到这个 API 连接；测试连接不会改变当前账号模型。", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft)
                .clickable { providerOptions = !providerOptions }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("协议", fontSize = 12.sp, color = SecondaryInk)
                    Text(providers[provider].orEmpty(), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(if (providerOptions) "⌃" else "⌄", fontSize = 20.sp, color = SecondaryInk)
            }
            if (providerOptions) Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Soft)) {
                providers.forEach { (id, title) -> Row(Modifier.fillMaxWidth().clickable { if (provider != id) acceptedCleartextConsent = ""; provider = id; providerOptions = false }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(provider == id, { if (provider != id) acceptedCleartextConsent = ""; provider = id; providerOptions = false }); Text(title, fontSize = 14.sp) } }
            }
            OutlinedTextField(url, { nextUrl -> val nextConsent = try { ModelConfig.cleartextConsentFor(provider, nextUrl) } catch (_: Exception) { null }; if (nextConsent != requiredCleartextConsent) acceptedCleartextConsent = ""; url = nextUrl }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
            if (!requiredCleartextConsent.isNullOrEmpty()) {
                Text("远端 HTTP 会明文发送 API 凭据、屏幕截图和文本，网络中的第三方可能读取或篡改。", color = Danger, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
                if (transportConfirmed) Text("已明确允许当前协议与地址", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp))
                else OutlinedButton(onClick = { cleartextDialog = true }, modifier = Modifier.padding(top = 8.dp)) { Text("查看并确认这个明文连接", fontSize = 12.sp) }
            }
            OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth().padding(top = 13.dp), singleLine = true, shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
            OutlinedTextField(key, { key = it }, label = { Text("API 密钥") }, modifier = Modifier.fillMaxWidth().padding(top = 13.dp), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
            Text(if (state.hasApiKey && provider == state.modelProvider && sameOrigin(url, state.baseUrl)) "此协议与服务已保存密钥，留空可继续使用。" else "填写此协议与服务的密钥；更换协议不沿用旧密钥。", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 7.dp))
            HorizontalDivider(Modifier.padding(top = 18.dp), color = Line)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("此模型支持图像输入", fontSize = 14.sp); Text(if (state.modelCatalogEnabled || state.modelRoute == "account") "请在 API 模型目录页核对精确能力与机主覆盖。" else "这是你的能力配置，不代表已实测效果。更换协议、地址或模型需重新确认。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp) }; Checkbox(supportsImageInput, { supportsImageInput = it }, enabled = !state.modelCatalogEnabled && state.modelRoute != "account") }
            HorizontalDivider(Modifier.padding(top = 18.dp), color = Line)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许按需发送截图", fontSize = 14.sp); Text("优先读取节点，节点不足且已配置图像能力时才发送；可见文字也会发送。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp) }; Switch(screenshots, { screenshots = it }) }
            if (!supportsImageInput) Text("当前模型不接收像素；如果允许本地取图和 OCR，仍可发送有来源的识别文字。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 7.dp))
            OutlinedTextField(outputTokens, { outputTokens = it.filter(Char::isDigit).take(5) }, label = { Text("每次回复的输出上限") }, supportingText = { Text(if (state.modelCatalogEnabled) "在模型目录页选择输出参数字段与预算；发送前会核对已知容量" else if (provider == "openai_chat") "兼容 Chat 协议的输出限制由服务端配置" else "128–32768 tokens", fontSize = 12.sp) }, enabled = provider != "openai_chat", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp), colors = settingsFieldColors())
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { OutlinedButton(onClick = { controller.testModel(url, model, key, screenshots, provider, configuredOutput, consentPayload) }, enabled = !state.modelTesting && configuredOutput in 128..32768 && transportConfirmed) { Text(if (state.modelTesting) "测试中" else "测试连接") }; Button(onClick = { controller.saveModel(url, model, key, screenshots, provider, configuredOutput, consentPayload, supportsImageInput) }, enabled = !state.modelSaving && configuredOutput in 128..32768 && transportConfirmed) { Text(if (state.modelSaving) "保存中" else if (state.modelRoute == "account") "保存并切换 API 模型" else "保存模型") } }
            state.modelTest?.let { Text(it, fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 10.dp)) }
            state.modelTestTaskId?.let { id -> TextButton(onClick = { controller.focusTask(id) }) { Text("查看这次连接测试用量") } }
            state.error?.let { Text(it, fontSize = 12.sp, lineHeight = 20.sp, color = Danger, modifier = Modifier.padding(top = 10.dp)) }
            }
                if (settingsSection == 1) SettingsSurface("手机执行", "授权与后台任务入口") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许本地按需取图", fontSize = 14.sp); Text("独立于云截图发送；关闭后不会为 OCR 采集像素。更改用于后续运行。", color = SecondaryInk, fontSize = 12.sp) }; Switch(state.localCaptureAllowed, { controller.saveLocalObservation(it, state.localOcrAllowed) }) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许本地 OCR", fontSize = 14.sp); Text("优先本地识字。像素不外发，但识别文字会作为有来源的上下文发送给所选推理服务。", color = SecondaryInk, fontSize = 12.sp) }; Switch(state.localOcrAllowed, { controller.saveLocalObservation(state.localCaptureAllowed, it) }) }
                    BackgroundConnectionSummary(state, controller, compact = true)
                    SettingEntry("视觉补充", if (state.screenshots) "已允许按需发送截图，优先读取结构树" else "未允许发送截图，可在模型连接中调整") { settingsSection = 0; editApi = true }
                    HorizontalDivider(color = Line)
                    SettingEntry("任务通知", if (state.notificationsEnabled) "已开启，可以从通知暂停或停止" else "未开启，后台手机任务需要此入口", controller::openNotifications)
                    HorizontalDivider(color = Line)
                    SettingEntry("界面读取与主屏控制", if (state.accessibilityEnabled) "无障碍服务已连接，可读取系统允许访问的节点" else "后台节点和文字读取、主屏控制需要无障碍授权", controller::openAccessibility)
                }
                if (settingsSection == 1) SettingsSurface("浏览器与语音", "网页研究、独立浏览与系统语音") {
                    SettingEntry("网页研究工作区", "选择登录资料，多页比较、保留依据与下载，随时接管", controller::openBrowserResearch)
                    HorizontalDivider(color = Line)
                    SettingEntry("内置浏览器", if (state.feature("browser").optBoolean("open")) "有打开的页面，点击后进入人工浏览" else "使用独立登录环境，不共享系统 Chrome 账号", controller::openBrowser)
                    HorizontalDivider(color = Line)
                    Text(if (state.speechAvailable) "系统听写可用，识别结果只填入草稿，由你发送。" else "系统听写服务不可用，可以继续使用文字。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp))
                    val latestReply = state.messages.lastOrNull { it.role == "assistant" }?.content
                    OutlinedButton(onClick = { if (state.speaking) controller.stopSpeaking() else latestReply?.let(controller::speak) }, enabled = state.speaking || latestReply != null, modifier = Modifier.padding(top = 10.dp)) { Text(if (state.speaking) "停止朗读" else "朗读最近回复") }
                }
                if (settingsSection == 2) SettingsSurface("更多能力", "沿用同一引擎与已有配置") {
                    SettingEntry("连接与能力管理", "记忆与定时、消息渠道、电脑环境、插件和团队", connections)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (cleartextDialog && !requiredCleartextConsent.isNullOrEmpty()) AlertDialog(modifier = Modifier.pocketDepth(PocketDepth.FLOATING, 28.dp), tonalElevation = 0.dp, onDismissRequest = { cleartextDialog = false }, containerColor = Color.White, title = { Text("允许这个明文模型连接？") }, text = { Text("协议：" + providers[provider].orEmpty() + "\n地址：" + requiredCleartextConsent.substringAfter('|') + "\n\nAPI 凭据、屏幕截图和文本将通过 HTTP 明文传输。更换协议或地址后需要重新确认。") }, confirmButton = { Button(onClick = { acceptedCleartextConsent = requiredCleartextConsent; cleartextDialog = false }) { Text("允许此协议与地址") } }, dismissButton = { TextButton(onClick = { cleartextDialog = false }) { Text("取消") } })
}

// Layout adaptation of the retained Affect Integration Settings sections and action footer
// (reference 0e69029b, public-preview independent implementation; not paid upstream TSX).
// One content surface per task group; rows and field explanations stay on that same plane.
@Composable
private fun SettingsSurface(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).pocketDepth(PocketDepth.CONTENT, 18.dp)
        .clip(RoundedCornerShape(18.dp)).background(Color.White).border(.5.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(detail, fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(top = 3.dp, bottom = 16.dp))
        content()
    }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink, unfocusedBorderColor = Line,
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
    disabledContainerColor = Soft, disabledBorderColor = Line)

@Composable
private fun SettingEntry(title: String, detail: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp))
        .clickable(onClick = open).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
        }
        Text("›", fontSize = 24.sp, color = SecondaryInk, modifier = Modifier.padding(start = 14.dp, end = 4.dp))
    }
}
@Composable
private fun Notice(text: String, error: Boolean, dismiss: () -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(if (error) Color(0xffe8e8e8) else Soft).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, fontSize = 12.sp, lineHeight = 20.sp, color = if (error) Danger else SecondaryInk, modifier = Modifier.weight(1f).padding(vertical = 10.dp)); TextButton(onClick = dismiss, contentPadding = PaddingValues(5.dp)) { Text("关闭", fontSize = 11.sp) } } }
@Composable
private fun ModeChip(text: String, selected: Boolean, click: () -> Unit) { Text(text, fontSize = 11.sp, color = if (selected) Ink else SecondaryInk, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Soft else Color.White).clickable(onClick = click).padding(horizontal = 14.dp, vertical = 8.dp)) }
@Composable
private fun Suggestion(title: String, caption: String, click: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(caption, fontSize = 11.sp, color = SecondaryInk); Text(" ›", fontSize = 18.sp, color = SecondaryInk) }; HorizontalDivider(color = Line) }
@Composable
private fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(subtitle, fontSize = 13.sp, lineHeight = 22.sp, color = SecondaryInk, modifier = Modifier.padding(top = 9.dp)) } }
@Composable
private fun CompactTask(task: UiTask, open: () -> Unit) { Column(Modifier.fillMaxWidth().clip(Rounded).background(Soft).clickable(onClick = open).padding(14.dp)) { Text(taskStatus(task.status), color = SecondaryInk, fontSize = 11.sp); Text(task.goal, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) } }
@Composable
private fun NameDialog(title: String, label: String, example: String, close: () -> Unit, save: (String) -> Unit) { var value by rememberSaveable { mutableStateOf("") }; AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, placeholder = { Text(example) }, singleLine = true, shape = RoundedCornerShape(13.dp)) }, confirmButton = { Button(onClick = { save(value) }, enabled = value.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } }) }
@Composable
private fun TaskImage(task: UiTask) { val url = task.raw.optJSONObject("evidence")?.optString("imageDataUrl").orEmpty(); val bitmap = remember(url) { try { if (!url.startsWith("data:image/jpeg;base64,") && !url.startsWith("data:image/png;base64,")) null else { val data = Base64.decode(url.substringAfter(','), Base64.DEFAULT); if (data.size > 4_000_000) null else BitmapFactory.decodeByteArray(data, 0, data.size) } } catch (_: Exception) { null } }; if (bitmap != null) { Text("结果截图", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)); androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = "本任务保存的结果截图", modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(Rounded)) } }
@Composable
private fun LineIcon(kind: Int) { Canvas(Modifier.size(22.dp)) { val w = size.width; val h = size.height; val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round); when (kind) { 0 -> { val p = Path().apply { moveTo(w * .14f, h * .15f); lineTo(w * .86f, h * .15f); lineTo(w * .86f, h * .72f); lineTo(w * .43f, h * .72f); lineTo(w * .22f, h * .9f); lineTo(w * .22f, h * .72f); lineTo(w * .14f, h * .72f); close() }; drawPath(p, Ink, style = stroke) }; 1 -> { drawRoundRect(Ink, topLeft = androidx.compose.ui.geometry.Offset(w * .2f, h * .13f), size = androidx.compose.ui.geometry.Size(w * .6f, h * .75f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke); val p = Path().apply { moveTo(w * .34f, h * .5f); lineTo(w * .46f, h * .62f); lineTo(w * .68f, h * .37f) }; drawPath(p, Ink, style = stroke) }; 2 -> { val p = Path().apply { moveTo(w * .12f, h * .3f); lineTo(w * .4f, h * .3f); lineTo(w * .48f, h * .2f); lineTo(w * .86f, h * .2f); lineTo(w * .86f, h * .82f); lineTo(w * .12f, h * .82f); close() }; drawPath(p, Ink, style = stroke) }; else -> { for (y in listOf(.25f, .5f, .75f)) drawLine(Ink, androidx.compose.ui.geometry.Offset(w * .15f, h * y), androidx.compose.ui.geometry.Offset(w * .85f, h * y), strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round) } } } }
private fun date(value: Long): String = if (value <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
private fun sameOrigin(first: String, second: String): Boolean = try {
    val a = java.net.URI(first)
    val b = java.net.URI(second)
    fun port(uri: java.net.URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    a.host != null && b.host != null && a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
} catch (_: Exception) { false }
