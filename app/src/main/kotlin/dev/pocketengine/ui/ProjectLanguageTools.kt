package dev.pocketengine.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.language.*
import dev.pocketengine.features.local.project.ProjectFileDraft
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private data class OriginalLanguageAnswer(val snapshot:ProjectLanguageSnapshot,val result:JSONObject,val draft:ProjectFileDraft)

/** Same original file editor: real TS service over a native VFS, with explicit owner actions.
 * Results can navigate or prepare drafts; they cannot save/execute or load project JS/plugins. */
@Composable
internal fun ProjectLanguageTools(
    controller:PocketWorldController, draft:ProjectFileDraft, selection:TextRange, composing:Boolean, enabled:Boolean,
    select:(Int,Int)->Unit, complete:(String,Int)->Boolean, staged:(ProjectFileDraft)->Boolean,
    syntax:(String,JSONArray)->Unit
) {
    if(!ProjectLanguageSnapshot.supports(draft.path))return
    val app=LocalContext.current.applicationContext as? EngineApplication?:return
    val lifecycle=LocalLifecycleOwner.current.lifecycle;val scope=rememberCoroutineScope()
    var expanded by remember(draft.checkoutId,draft.path){mutableStateOf(false)}
    var busy by remember(draft.checkoutId,draft.path){mutableStateOf(false)}
    var notice by remember(draft.checkoutId,draft.path){mutableStateOf<String?>(null)}
    var answer by remember(draft.checkoutId,draft.path){mutableStateOf<OriginalLanguageAnswer?>(null)}
    var rename by remember(draft.checkoutId,draft.path){mutableStateOf("")}
    var pending by remember(draft.checkoutId,draft.path){mutableStateOf<Pair<OriginalLanguageAnswer,List<ProjectLanguageEdits.FileEdit>>?>(null)}
    var running by remember{mutableStateOf<RunControl?>(null)}
    var service by remember{mutableStateOf<AndroidProjectLanguageService?>(null)}
    var work by remember{mutableStateOf<Job?>(null)}
    var generation by remember{mutableLongStateOf(0)}
    val currentDraft by rememberUpdatedState(draft)
    val currentSelection by rememberUpdatedState(selection)
    val currentComposing by rememberUpdatedState(composing)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentStaged by rememberUpdatedState(staged)
    fun ownerCurrent():Boolean=controller.originalProjectEditorCurrent(draft.projectId,draft.checkoutId)&&controller.projectWorkbench.opened(draft.checkoutId)?.first==draft.path&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun cancel(){generation++;running?.cancel();running=null;service?.close();service=null;work?.cancel();work=null;busy=false;pending=null}
    DisposableEffect(lifecycle,draft.checkoutId,draft.path){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP){cancel();notice="分析已停止；原稿保留。"}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);cancel()}
    }
    fun local():LocalModule=app.features.module("local") as? LocalModule?:error("项目读取尚未就绪")
    fun matches(original:OriginalLanguageAnswer):Boolean=ownerCurrent()&&currentDraft.revision==original.draft.revision&&currentDraft.text==original.draft.text&&currentDraft.path==original.draft.path
    fun query(action:String){
        if(!ownerCurrent()||currentComposing||!currentEnabled)return
        cancel();val token=generation;val original=currentDraft;val caret=currentSelection.start;val newName=rename;val control=RunControl();running=control;busy=true;notice=null
        work=scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    control.check();val snapshot=local().languageSnapshotForOwner(original.checkoutId,original.path,original.text,original.revision)
                    val binding=app.pluginHost?.sharedSandbox()?:error("本机语言分析暂不可用；文件编辑和查找仍可用")
                    val reader=AndroidProjectLanguageService(app,binding);service=reader
                    try{OriginalLanguageAnswer(snapshot,reader.query(snapshot,action,caret,if(action=="rename")newName else null,control),original)}finally{reader.close()}
                }
                if(token!=generation||!matches(result))return@launch
                answer=result
                if(action=="diagnostics")result.result.optJSONArray("syntax")?.let{syntax(original.text,it)}
                if(action=="rename"||action=="format"){
                    val edits=withContext(Dispatchers.Default){ProjectLanguageEdits.review(result.snapshot,result.result)}
                    if(token==generation&&matches(result)){if(edits.isEmpty())notice="当前原稿不需要这项修改。"else pending=result to edits}
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){if(token==generation)notice=failure.message?.take(240)?:"语言分析未完成，文件和草稿未修改。"}
            finally{if(token==generation){busy=false;running=null;service=null}}
        }
    }
    fun navigate(original:OriginalLanguageAnswer,row:JSONObject){
        if(!matches(original)||currentComposing||row.optBoolean("library"))return
        val token=generation;val target=row.getString("path");val start=row.getInt("start");val end=start+row.getInt("length");val from=currentSelection
        work=scope.launch{try{
            withContext(Dispatchers.IO){local().requireLanguageSnapshotForOwner(original.snapshot)}
            if(token!=generation||!matches(original)||currentComposing)return@launch
            if(target==draft.path)select(start,end)else controller.projectWorkbench.navigateCode(draft.checkoutId,draft.path,from.start,from.end,target,start,end)
        }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){notice=failure.message?.take(240)}}
    }
    val valid=answer?.takeIf(::matches)
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
            TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起代码智能"else "代码智能")}
            if(controller.projectWorkbench.hasCodeReturn(draft.checkoutId))TextButton(onClick={cancel();controller.projectWorkbench.returnFromCode(draft.checkoutId)},enabled=!composing){Text("返回来源位置")}
        }
        if(expanded){
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                for((action,label) in listOf("diagnostics" to "检查","complete" to "补全","definition" to "定义","references" to "引用","format" to "格式化…"))TextButton(onClick={query(action)},enabled=!busy&&enabled&&!composing){Text(label)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(rename,{rename=it.take(100)},label={Text("新符号名")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
                TextButton(onClick={query("rename")},enabled=!busy&&enabled&&!composing&&rename.isNotBlank()){Text("预览重命名…")}
            }
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={cancel();notice="分析已停止，文件与草稿保留。"}){Text("停止等待或分析")}}
            notice?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(answer!=null&&valid==null)Text("文字或项目已变化，请重新分析；旧结果不能改稿。",style=MaterialTheme.typography.bodySmall)
            valid?.let{original->
                val result=original.result;val rows=result.getJSONArray("rows");val action=result.getString("action")
                Column(Modifier.fillMaxWidth().heightIn(max=160.dp).verticalScroll(rememberScrollState())){
                    if(rows.length()==0&&!busy)Text(if(action=="diagnostics")"当前快照未返回语法或类型问题；这不是构建通过回执。"else "当前符号没有这类结果。",style=MaterialTheme.typography.bodySmall)
                    if(action !in setOf("rename","format"))for(i in 0 until rows.length()){
                        val row=rows.getJSONObject(i)
                        if(action=="complete")TextButton(onClick={
                            try{if(!matches(original)||currentComposing)error("草稿或输入状态已变化，请重新补全")
                                val next=ProjectLanguageEdits.applyCompletion(original.snapshot,result,i,currentDraft.text);val at=row.getJSONObject("replacementSpan").getInt("start")+row.getString("insertText").length
                                if(complete(next,at)){answer=null;notice="已补全到草稿，尚未保存。"}
                            }catch(failure:Exception){notice=failure.message}
                        },enabled=enabled&&!composing){Text(row.getString("name"))}
                        else if(row.optBoolean("library"))Text("库定义 · ${row.optString("path")} · ${row.optString("name")}（只读）",style=MaterialTheme.typography.bodySmall)
                        else TextButton(onClick={navigate(original,row)},enabled=!busy&&!composing){Text(if(action=="diagnostics")"${row.optInt("line")}:${row.optInt("column")} · TS${row.optInt("code")} ${row.optString("message")}"else "${row.optString("path")}:${row.optInt("line")}:${row.optInt("column")} · ${row.optString("preview")}",maxLines=3)}
                    }
                }
                if(result.optBoolean("truncated"))Text("结果超出本次范围；尚有未显示项目，请缩小查询。",style=MaterialTheme.typography.bodySmall)
            }
            Text("TypeScript 5.9.3 · 本项目快照；不执行项目脚本或插件。最多128个文件/1MiB，其他未保存文件的草稿需先处理。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    pending?.let{(original,edits)->AlertDialog(onDismissRequest={pending=null},title={Text("把完整修改放入 ${edits.size} 个文件草稿？")},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){
        Text("只准备草稿，不保存或运行。之后逐文件比较并按原 CAS 保存；不是跨文件事务。",style=MaterialTheme.typography.bodySmall)
        for(edit in edits){Text("${edit.path} · ${edit.replacements} 处",style=MaterialTheme.typography.titleSmall);Text(edit.afterText.take(360),style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={TextButton(onClick={
        if(!matches(original)||currentComposing){notice="预览后原稿已变化，请重新分析。";pending=null;return@TextButton}
        val token=generation;val control=RunControl();running=control;busy=true
        work=scope.launch{try{
            val drafts=withContext(Dispatchers.IO){ProjectLanguageDrafts.stage(local(),controller.projectWorkbench.fileDrafts,original.snapshot,original.result,original.draft,control)}
            if(token==generation&&ownerCurrent()){
                drafts.firstOrNull{it.path==draft.path}?.let{if(currentDraft.text==original.draft.text)currentStaged(it)}
                pending=null;answer=null;notice="已准备 ${drafts.size} 份未保存草稿。请逐文件比较后保存；缓存失败会在原草稿入口显示。"
            }
        }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){if(token==generation)notice=failure.message?.take(240)}finally{if(token==generation){busy=false;running=null}}}
    },enabled=!busy&&enabled&&!composing&&matches(original)){Text("准备完整草稿")}},dismissButton={TextButton(onClick={pending=null}){Text("返回，不修改")}})}
}
