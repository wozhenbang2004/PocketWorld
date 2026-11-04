package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pocketengine.features.attachments.documents.DocumentContent
import org.json.JSONObject

/** A single original-task journey: source → bounded reading → actual media receipt → saved file → export/delivery evidence. */
@Composable
internal fun AttachmentOutcomeOverlay(flow:AttachmentOutcomeCoordinator) {
    val state=flow.state;if(!state.open)return
    state.thumbnail?.let{image->DisposableEffect(image){onDispose{image.recycle()}}}
    Dialog(onDismissRequest=flow::close,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            val file=state.file
            if(state.comparison!=null){
                NativeImageVersionComparison(state.comparison,flow::closeComparison)
            }else if(state.processingSource!=null&&state.processingInfo!=null){
                NativeFileProcessComposer(state,flow)
            }else if(state.documentDraft!=null){
                NativeDocumentComposer(state.documentDraft,state.busy,state.error,state.pendingDocumentTask,flow::createDocument,flow::queryDocument,flow::closeDocumentDraft,flow::updateDocumentDraft)
            }else if(state.originalFile!=null){
                NativeOriginalFilePage(state.originalFile,flow::backFromOriginalFile)
            }else if(state.team!=null){
                TeamWorkspaceArtifactPage(flow)
            }else if(state.documentOpen&&file!=null){
                Column(Modifier.fillMaxSize()){
                    Box(Modifier.weight(1f)){
                        AttachmentDocumentPage(file.optString("id"),file.optString("sha256"),file.optString("name","原任务文件"),state.document,state.busy,
                            flow.canRequestOcr(),state.error,{_,_,range->flow.read(range)},flow::cancel,flow::backFromDocument)
                    }
                    if(flow.canSaveRead()){
                        HorizontalDivider(color=Line)
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=22.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Button(onClick=flow::saveReadAsFile,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("保存当前已读范围为文件")}
                            Text("保存为带来源定位的文字摘录，只包含本次已查看的部分。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
                        }
                    }
                }
            } else {
                BackHandler { if(file!=null)flow.backFromFile() else flow.close() }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={if(file!=null)flow.backFromFile()else flow.close()}){Text("返回")}
                        Text(if(file!=null)"文件来源" else if(state.taskId==null)"文件与成果" else "原任务的文件与成果",fontWeight=FontWeight.SemiBold,fontSize=17.sp,modifier=Modifier.weight(1f))
                        TextButton(onClick=flow::refresh,enabled=!state.busy){Text("刷新")}
                    }
                    HorizontalDivider(color=Line)
                    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Ink,trackColor=Soft)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        state.error?.let{Text(it,color=Danger,fontSize=13.sp,lineHeight=21.sp)}
                        state.notice?.let{Text(it,color=SecondaryInk,fontSize=13.sp,lineHeight=21.sp)}
                        state.pendingProcessingTask?.let{Text("原加工动作 ${it.take(12)} 待核，不会自动重复。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=flow::queryProcessing,enabled=!state.busy){Text("核对原加工结果")}}
                        state.pendingDocumentTask?.let{Text("原文档动作待核：${it.take(12)}，不会自动重新生成。",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=flow::queryDocument,enabled=!state.busy){Text("核对原文档结果")}}
                        if(state.busy)TextButton(onClick=flow::cancel){Text("停止本次读取")}
                        if(file!=null){OutcomeFileDetail(file,state,flow);if(state.group==null&&file.optString("mimeType").let{it.startsWith("image/")||it=="video/mp4"||it=="audio/mp4"}){OutlinedButton(onClick=flow::openProcessing,enabled=!state.busy&&state.pendingProcessingTask==null,modifier=Modifier.fillMaxWidth()){Text(if(file.optString("mimeType").startsWith("image/"))"裁剪、缩放或转换原图"else "剪辑原媒体片段")};if(file.optString("mimeType").startsWith("image/")&&(file.optJSONObject("parentArtifact")!=null||file.optJSONObject("source")?.optJSONObject("parentArtifact")!=null))TextButton(onClick=flow::compareImageParent,enabled=!state.busy){Text("对照这个版本与父原图")}};if(state.group==null&&(file.optJSONObject("editableSource")!=null||file.optJSONObject("source")?.optJSONObject("editableSource")!=null))OutlinedButton(onClick=flow::continueDocument,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("从这个原版本继续生成文档")}}
                        else {
                            Text(if(state.choosingSource)"选择当前目标需要的来源"else "来源和成果留在同一个任务里",fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=30.sp)
                            Text(if(state.group!=null)"仅查看原群、原受众的真实文件。生成、保存和群消息回执分别显示。" else "按当前目标选择来源、读取需要的部分，再继续处理。任务结束后，真实文件和原操作仍可回来查看。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
                            if(state.taskId==null&&state.group==null)state.snapshot?.optJSONObject("documentActions")?.let{page->
                                val actions=page.optJSONArray("actions").objects();val gaps=page.optJSONArray("gaps").objects()
                                if(actions.isNotEmpty()||gaps.isNotEmpty()||page.optBoolean("hasMore")){
                                    Text("原文档生成记录",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                                    Text("这里只定位原动作。已完成或待核的结果都需明确打开，不会重新生成。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
                                    for(action in actions){
                                        val id=action.getString("taskId")
                                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                                            Text("${action.optString("name")} · ${action.optString("format").uppercase()}",fontWeight=FontWeight.Medium,fontSize=14.sp)
                                            Text("原动作 $id · ${action.optString("status")}",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
                                            OutlinedButton(onClick={flow.openDocumentAction(id)},enabled=!state.busy){Text("打开原文档动作 ${id.take(12)}")}
                                        }
                                    }
                                    for(gap in gaps)Text("原记录 ${gap.optString("taskId")} 详情暂不可完整核对，请从原任务查看。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
                                    if(page.optBoolean("hasMore"))TextButton(onClick=flow::olderDocumentActions,enabled=!state.busy){Text("读取更早的原文档记录")}
                                    Text("这是有界任务摘要窗口，不是全部记录；刷新会回到最新窗口。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
                                }
                            }
                            if(state.group==null){
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    OutlinedButton(onClick=flow::importSource,enabled=!state.busy){Text("选择手机文件")}
                                    TextButton(onClick=flow::newDocument,enabled=!state.busy){Text("创建文档")}
                                    if(state.taskId!=null&&!state.choosingSource)TextButton(onClick=flow::chooseSource,enabled=!state.busy){Text("从附件库关联")}
                                }
                            }
                            val sources=state.snapshot?.optJSONArray("sources").objects()
                            val outputs=state.snapshot?.optJSONArray("artifacts").objects()
                            if(sources.isNotEmpty())OutcomeFiles(if(state.choosingSource)"可用文件"else "目标来源",sources,state.busy){if(state.choosingSource)flow.attach(it)else flow.selectFile(it)}
                            if(outputs.isNotEmpty())OutcomeFiles("已保存成果",outputs,state.busy){if(state.choosingSource)flow.attach(it)else flow.selectFile(it)}
                            if(state.snapshot!=null&&sources.isEmpty()&&outputs.isEmpty())Text(if(state.group!=null)"当前尚无已关联的群文件。可以查询原生成操作，取回它实际返回的产物。" else "当前还没有文件。可以从手机选择，或让原任务按目标生成文件。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
                            val jobs=state.snapshot?.optJSONArray("media").objects().ifEmpty{if(state.snapshot?.optString("kind")=="group_media_result")listOf(JSONObject(state.snapshot.toString()).put("operationId",state.snapshot.optString("sourceOperationId")).put("status",state.snapshot.optString("mediaState")))else emptyList()}
                            for(job in jobs)OutcomeMedia(job,state,flow)
                            if(state.group!=null)OutcomeDelivery(state.snapshot,flow)
                            if(state.taskId!=null)OutlinedButton(onClick=flow::continueTask,modifier=Modifier.fillMaxWidth()){Text(if(state.group!=null)"回原任务核对与群投递" else "继续处理同一原任务")}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeFiles(title:String,files:List<JSONObject>,busy:Boolean,open:(JSONObject)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(title,color=Ink,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
        for(file in files){
            val available=file.optString("availability","available")=="available"
            Row(Modifier.fillMaxWidth().clickable(enabled=!busy&&available){open(file)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(file.optString("name","原文件"),fontSize=15.sp);Text(if(available)"${file.optString("mimeType")} · ${outcomeSize(file.optLong("size"))}"else "原文件或版本已不可用",color=if(available)SecondaryInk else Danger,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))}
                Text(if(available)"查看"else "待核对",color=SecondaryInk,fontSize=12.sp)
            }
            HorizontalDivider(color=Line)
        }
    }
}

@Composable
private fun OutcomeFileDetail(file:JSONObject,state:AttachmentOutcomeState,flow:AttachmentOutcomeCoordinator){
    var details by remember(file.optString("id"),file.optString("sha256")){mutableStateOf(false)}
    var page by remember(file.optString("id")){mutableStateOf("1")}
    var change by remember(file.optString("id"),file.optString("sha256")){mutableStateOf("")}
    state.thumbnail?.let{image->Image(image.asImageBitmap(),contentDescription="原文件的有界图片预览",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().heightIn(max=300.dp))}
    Text(file.optString("name","原任务文件"),fontWeight=FontWeight.SemiBold,fontSize=23.sp,lineHeight=32.sp)
    Text("${file.optString("mimeType")} · ${outcomeSize(file.optLong("size"))}",color=SecondaryInk,fontSize=13.sp)
    Text(if(state.group!=null)"文件属于原群任务与原受众，保持在群文件空间。打开或导出不会发送群消息。"else if(file.optString("sourceKind")=="document_extract")"这是当前已读范围的文字摘录，带有原文件版本、来源定位和读取说明。原文件与尚未读取的后续内容分别保留。"else "这是实际保存的文件。预览和文档提取用于查看来源；内容是否达到目标仍由原任务的证据核对。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Button(onClick=flow::openOriginalFile,enabled=!state.busy){Text("查看原件")}
        OutlinedButton(onClick=flow::prepareExport,enabled=!state.busy&&!flow.exportPending){Text("导出到手机")}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick=flow::openExternal,enabled=!state.busy){Text("用其他应用打开")}
        if(state.group==null)TextButton(onClick=flow::shareOriginalFile,enabled=!state.busy){Text("系统分享…")}
    }
    val mime=file.optString("mimeType")
    if(mime.startsWith("text/")||mime.contains("pdf")||mime.contains("officedocument")||mime.matches(Regex("application/(json|xml|octet-stream)"))||mime.startsWith("image/")){
        HorizontalDivider(color=Line)
        if(mime.startsWith("image/")){
            Text("识别图片文字",fontWeight=FontWeight.SemiBold,fontSize=16.sp)
            Text("使用本机 OCR 识别这份原图；识别文字会单独标明，仍需核对图片。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
            OutlinedButton(onClick=flow::readImageText,enabled=!state.busy&&flow.canRequestOcr()){Text("识别图片文字")}
            if(!flow.canRequestOcr())Text(if(mime in setOf("image/png","image/jpeg","image/webp"))"当前设备尚未提供本机文字识别，仍可打开或导出原图。"else "当前文字识别支持 PNG、JPEG 和 WebP 图片，仍可打开或导出原图。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
        }else{
            Text("按来源读取",fontWeight=FontWeight.SemiBold,fontSize=16.sp)
            Text("只提取当前需要的页或范围，保留页码、表格位置和识别说明。不会要求先读完整份文件。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
            if(mime=="application/pdf")OutlinedTextField(page,{page=it.filter(Char::isDigit).take(5)},label={Text("从第几页开始")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={val start=page.toIntOrNull()?.coerceIn(1,10000)?:1;flow.read(DocumentContent.Request(start,0,4,12000,48,"text"))},enabled=!state.busy){Text(if(mime=="application/pdf")"读取所选页"else "读取文字与表格")}
        }
    }
    TextButton(onClick={details=!details}){Text(if(details)"收起来源信息"else "来源与文件版本")}
    if(details){
        Text(if(state.group!=null)"原群空间 · "+if(state.group.audience=="group")"原群成员受众"else "原发起人受众"else "本机主人 · 私人文件",color=SecondaryInk,fontSize=12.sp)
        SelectionContainer{Text("SHA-256\n"+file.optString("sha256"),color=SecondaryInk,fontSize=11.sp,lineHeight=18.sp)}
        for((key,label) in listOf("sourceTaskId" to "原任务","sourceTaskRunId" to "来源执行","sourceOperationId" to "原操作","sourceJobId" to "原服务任务"))if(file.optString(key).isNotBlank())SelectionContainer{Text(label+" · "+file.optString(key),color=SecondaryInk,fontSize=11.sp,lineHeight=18.sp)}
        if(file.optString("sourceKind")=="document_extract")SelectionContainer{Text("原文档 · "+file.optString("sourceAttachmentId")+"\n原文档 SHA-256\n"+file.optString("sourceSha256"),color=SecondaryInk,fontSize=11.sp,lineHeight=18.sp)}
    }
    if(state.taskId!=null&&state.group==null&&!mime.startsWith("image/")&&!mime.startsWith("audio/")&&!mime.startsWith("video/")){
        HorizontalDivider(color=Line)
        Text("从这份固定来源继续",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
        OutlinedTextField(change,{change=it.take(2000)},label={Text("接下来需要怎样处理")},modifier=Modifier.fillMaxWidth(),minLines=2)
        OutlinedButton(onClick={flow.continueFromVersion(change)},enabled=!state.busy&&change.isNotBlank()){Text("保存要求到原任务")}
        Text("保留这份文件和摘要，只把所需范围交给原任务。新的处理结果另存为成果；不会改写原件或自动外发。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
    }
    if(mime.startsWith("image/"))Text("当前可预览、打开和导出。基于原图编辑或重生成需独立的新输入通路，尚未接入。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
    if(state.taskId!=null)TextButton(onClick=flow::continueTask){Text("回到原任务继续处理")}
}

@Composable
private fun OutcomeMedia(job:JSONObject,state:AttachmentOutcomeState,flow:AttachmentOutcomeCoordinator){
    val operation=job.optString("operationId");if(operation.isBlank())return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        HorizontalDivider(color=Line)
        Text("媒体处理 · "+mediaOutcomeStatus(job.optString("status")),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
        Text(job.optString("mediaMessage",job.optString("message","原操作与真实服务回执单独保留")),color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
        if(state.progress!=null){val value=state.progress.optDouble("value",Double.NaN);val max=state.progress.optDouble("max",Double.NaN)
            if(value.isFinite()&&max.isFinite()&&max>0)LinearProgressIndicator(progress={ (value/max).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth(),color=Ink,trackColor=Soft)
            Text(if(value.isFinite()&&max.isFinite())"服务步骤 ${value.toInt()} / ${max.toInt()}"else "进度连接："+state.progress.optString("observationState","等待原任务事件"),color=SecondaryInk,fontSize=12.sp)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(onClick={flow.refreshMedia(operation)},enabled=!state.busy&&!state.observing){Text("查询原操作")}
            TextButton(onClick={flow.refreshMedia(operation,true)},enabled=!state.busy&&!state.observing){Text("取回原产物")}
        }
        if(job.optString("provider")=="comfyui"||job.optBoolean("providerQuerySupported"))Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(onClick={if(state.observing)flow.stopObservation()else flow.observe(operation)},enabled=!state.busy){Text(if(state.observing)"停止观察"else "查看实时进度")}
            TextButton(onClick={flow.cancelMedia(operation)},enabled=!state.busy&&!state.observing){Text("停止原生成")}
        }
        Text("服务进度不代表文件已保存或目标已完成。断网后可继续查询同一原操作。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
    }
}

@Composable
private fun OutcomeDelivery(snapshot:JSONObject?,flow:AttachmentOutcomeCoordinator){
    val delivery=snapshot?.optJSONObject("delivery")?:return;val evidence=snapshot.optJSONObject("deliveryEvidence")
    HorizontalDivider(color=Line)
    Text("原群消息回执",fontWeight=FontWeight.SemiBold,fontSize=15.sp)
    Text(delivery.optString("message","尚未取得群消息回执"),fontSize=13.sp,color=SecondaryInk,lineHeight=22.sp)
    if(evidence?.optBoolean("ownerConfirmedDelivery")==true)Text("主人已核对送达；平台回执只证明受理，未提供成员已读证明。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
    TextButton(onClick=flow::continueTask){Text("查看原任务与投递记录")}
}
private fun mediaOutcomeStatus(raw:String):String=when(raw){"prepared"->"尚未提交";"submitting"->"等待提交回执";"queued"->"服务已受理，排队中";"running"->"正在生成";"response_received"->"服务回执已到";"unknown"->"原操作待核对";"awaiting_approval"->"等待必要审批";"receipt_unavailable","host_receipt_unavailable"->"原回执暂不可读";"failed"->"未完成";"cancelled"->"有取消回执";else->"保留原记录"}
private fun outcomeSize(bytes:Long):String=if(bytes>=1024*1024)String.format(java.util.Locale.ROOT,"%.1f MiB",bytes/(1024.0*1024))else if(bytes>=1024)"${bytes/1024} KiB"else "$bytes 字节"
