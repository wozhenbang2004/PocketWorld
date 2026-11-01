package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import dev.pocketengine.features.ssh.SshPreviewWorkflows
import dev.pocketengine.features.ssh.SshModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Only native-owned loopback lease reaches the existing isolated dev-profile viewer. */
@Composable
internal fun RemotePreviewPanel(snapshot:JSONObject,controller:PocketWorldController){
    if(snapshot.optBoolean("teamRemoteWriter"))return
    var open by remember(snapshot.optString("taskId")){mutableStateOf(false)}
    OutlinedButton(onClick={open=true},enabled=snapshot.optBoolean("fresh")){Text("预览这个项目的已运行网页")}
    if(open)RemotePreviewDialog(snapshot.optString("taskId"),controller){open=false}
}
@Composable
private fun RemotePreviewDialog(taskId:String,controller:PocketWorldController,close:()->Unit){
    val page=remember(taskId){controller.newRemotePreviewPage(taskId)};val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember{mutableStateOf(lifecycle.currentState==Lifecycle.State.RESUMED)}
    var profileId by remember{mutableStateOf<String?>(null)};var port by remember{mutableStateOf("5173")};var seconds by remember{mutableStateOf("300")}
    var review by remember{mutableStateOf<SshPreviewWorkflows.Review?>(null)};var risk by remember{mutableStateOf(false)};var receipt by remember{mutableStateOf<JSONObject?>(null)}
    var mount by remember{mutableStateOf<SshModule.PreviewLease?>(null)};var viewer by remember{mutableStateOf<ProjectLiveWebView?>(null)};var problem by remember{mutableStateOf<String?>(null)}
    var starting by remember{mutableStateOf(false)}
    DisposableEffect(page,lifecycle){page.setForeground(foreground);val observer=LifecycleEventObserver{_,_->foreground=lifecycle.currentState==Lifecycle.State.RESUMED;page.setForeground(foreground);if(!foreground){viewer?.release();viewer=null;mount=null;review=null;risk=false;starting=false}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);viewer?.release();page.close()}}
    LaunchedEffect(mount,foreground){while(mount!=null&&foreground){try{mount?.requireCurrent();viewer?.requireOwnedMountCurrent()}catch(failure:Exception){viewer?.release();viewer=null;mount=null;problem="原隧道已失效；本页已关闭预览，不重启远端或重新连接。";break};delay(1000)}}
    val profiles=controller.state.feature("ssh").optJSONArray("profiles").objects().filter{it.optBoolean("hostKeyTrusted")&&it.optBoolean("credentialsConfigured")}
    val selected=profiles.find{it.optString("id")==profileId}
    fun stop(){viewer?.release();viewer=null;mount=null;page.stop();starting=false;review=null;risk=false}
    Dialog(onDismissRequest={stop();close()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row{Text("原项目网页预览",fontSize=20.sp,modifier=Modifier.weight(1f));TextButton(onClick={stop();close()}){Text("关闭")}}
                if(mount==null)Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("先在电脑上正常运行开发服务器。本页仅建立所选SSH连接到电脑127.0.0.1单一端口的限时转发，不启动服务、修改代码或复用账号浏览器。",fontSize=14.sp)
                    for(profile in profiles)Row{RadioButton(profileId==profile.optString("id"),{profileId=profile.optString("id");review=null;risk=false},enabled=!starting);Column(Modifier.weight(1f).padding(top=10.dp)){Text(profile.optString("name"),fontSize=14.sp);Text(profile.optString("host")+" · "+profile.optString("workspace"),fontSize=13.sp)}}
                    if(profiles.isEmpty())TextButton(onClick={controller.openConnection("ssh")}){Text("先设置并核对SSH连接")}
                    OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5);review=null;risk=false},label={Text("电脑上已运行的开发端口")},enabled=!starting,singleLine=true)
                    OutlinedTextField(seconds,{seconds=it.filter(Char::isDigit).take(3);review=null;risk=false},label={Text("隧道保留秒数（10–600）")},enabled=!starting,singleLine=true)
                    OutlinedButton(onClick={try{review=page.review(selected!!.optString("id"),selected.optString("bindingHash"),port.toInt(),seconds.toInt());risk=false;problem=null}catch(failure:Exception){problem=failure.message}},enabled=foreground&&!starting&&selected!=null&&(port.toIntOrNull()?:0)in 1024..65535&&(seconds.toIntOrNull()?:0)in 10..600){Text("核对准确转发范围")}
                    review?.view()?.let{view->val input=view.getJSONObject("input");val source=input.getJSONObject("previewSource");Text("原电脑任务 ${source.optString("taskId")}\n原job ${source.optString("jobId")}\nSSH ${view.getJSONObject("sshProfile").optString("host")} → 127.0.0.1:${input.optInt("remotePort")}\n本机仅127.0.0.1随机端口，${input.optInt("timeoutSeconds")}秒。",fontSize=14.sp)
                        Text("需你核对这是原项目对应的SSH机器与服务；相同路径不是身份或OS隔离证明。网页脚本及同端口WebSocket会运行，可能按该应用能力操作服务。不会转发手机凭据/账号Cookie；其他来源、跳转和不同WS端口拒绝。其他本机App可能访问本机端口。离页/后台/到期会断开本隧道，远端服务继续状态另行核对。",fontSize=14.sp)
                        Row{Checkbox(risk,{risk=it},enabled=foreground&&!starting);Text("已核对准确机器、服务与上述风险",fontSize=14.sp,modifier=Modifier.weight(1f).padding(top=12.dp))}
                        Button(onClick={try{starting=true;page.open(review!!,view.getString("digest"),risk){actual->scope.launch{starting=false;receipt=actual;try{mount=page.mount();problem=null}catch(failure:Exception){problem=failure.message}}}}catch(failure:Exception){starting=false;problem=failure.message}},enabled=foreground&&!starting&&risk){Text(if(starting)"连接原SSH…"else"明确打开这一个预览隧道")}
                    }
                }else{
                    Text("仅这个原生隧道的隔离开发资料 · ${mount?.baseUrl()}",fontSize=13.sp)
                    val actual=mount!!
                    AndroidView(factory={context->android.widget.FrameLayout(context).also{container->
                        try{val fresh=ProjectLiveWebView.create(context,actual,object:ProjectLiveWebView.Events{
                            override fun error(message:String){scope.launch{stop();problem=message}}
                            override fun console(level:String,text:String,source:String,line:Int){if(level=="ERROR")scope.launch{problem="开发页面报告资源或脚本问题："+text.take(180)}}
                        });viewer=fresh;container.addView(fresh,android.widget.FrameLayout.LayoutParams(-1,-1))}
                        catch(failure:Exception){scope.launch{stop();problem=failure.message?:"此WebView提供者无法创建隔离预览，未使用账号浏览器。"}}
                    }},onReset=null,onRelease={container->for(i in 0 until container.childCount)(container.getChildAt(i)as?ProjectLiveWebView)?.release()},modifier=Modifier.weight(1f).fillMaxWidth())
                    Text("同端口网页/WS可用不代表所有HMR框架可用。脚本写死其他WS地址会被拒绝，不改写未知脚本。",fontSize=13.sp)
                    OutlinedButton(onClick=::stop){Text("断开本机隧道（不停止远端服务）")}
                }
                receipt?.optString("hostTaskId")?.takeIf{it.isNotEmpty()}?.let{id->TextButton(onClick={controller.focusTask(id)}){Text("查看隧道原任务与关闭回执")}}
                problem?.let{Text(it,color=Danger,fontSize=14.sp)}
            }
        }
    }
}
