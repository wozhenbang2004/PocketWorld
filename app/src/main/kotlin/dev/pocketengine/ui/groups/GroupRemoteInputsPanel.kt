package dev.pocketengine.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Original authenticated group -> exact selected bytes -> API-mode job -> second native return review. */
@Composable
internal fun GroupRemoteInputsPanel(requestId:String,responses:Map<String,JSONObject>,onRequest:(String,JSONObject)->String,onBack:()->Unit) {
    var data by remember(requestId){mutableStateOf(JSONObject())}
    var pending by remember(requestId){mutableStateOf<Pair<String,String>?>(null)}
    var notice by remember(requestId){mutableStateOf("")}
    var profile by remember(requestId){mutableStateOf("")};var generation by remember(requestId){mutableStateOf("")}
    var environments by remember(requestId){mutableStateOf(emptyList<JSONObject>())};var environmentId by remember(requestId){mutableStateOf("")}
    var prompt by remember(requestId){mutableStateOf("")};var model by remember(requestId){mutableStateOf("")};var seconds by remember(requestId){mutableStateOf("300")}
    var sourceConsent by remember(requestId){mutableStateOf(false)};var returnConsent by remember(requestId){mutableStateOf(false)}
    var selected by remember(requestId){mutableStateOf(emptySet<String>())};var selectedJob by remember(requestId){mutableStateOf("")};var replyEvent by remember(requestId){mutableStateOf("")}
    var details by remember(requestId){mutableStateOf(false)};var textPage by remember(requestId){mutableStateOf(0)}
    fun request(command:String,value:JSONObject=JSONObject()) {
        if(pending!=null)return
        try{val payload=JSONObject(value.toString());if(command!="openPage")payload.put("pageId",data.optString("pageId"));pending=onRequest("remoteInputs.$command",payload) to command;notice=""}catch(failure:Exception){notice=failure.message?:"操作未完成，原记录保留"}
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    val latestPage by rememberUpdatedState(data.optString("pageId"))
    DisposableEffect(requestId){onDispose{if(latestPage.isNotBlank())try{onRequest("remoteInputs.leave",JSONObject().put("pageId",latestPage))}catch(_:Exception){}}}
    BackHandler{onBack()}
    LaunchedEffect(requestId,foreground){
        // The real owner window is revoked on pause. Never keep its confirmation or apply a late receipt.
        pending=null;sourceConsent=false;returnConsent=false;data=JSONObject();generation="";environments=emptyList();environmentId=""
        if(foreground)request("openPage",JSONObject().put("requestId",requestId))
        else notice="页面已离开前台，旧确认已失效；回来只读核对原记录，不重发文件或新建job"
    }
    LaunchedEffect(responses,pending,foreground){
        if(!foreground)return@LaunchedEffect
        val active=pending?:return@LaunchedEffect;val envelope=GroupUiState.envelope(active.first,responses[active.first])?:return@LaunchedEffect;pending=null
        if(!envelope.optBoolean("ok")){notice=envelope.optString("message","这次原生范围未通过，请重新核对");return@LaunchedEffect}
        val actual=envelope.optJSONObject("result")?:return@LaunchedEffect
        if(actual.optString("requestId")!=requestId){notice="迟到结果不属于当前原群请求，未应用";return@LaunchedEffect}
        if(active.second!="openPage"&&data.optString("pageId").isNotBlank()&&actual.optString("pageId")!=data.optString("pageId")){notice="原子页已变更，迟答未应用";return@LaunchedEffect}
        data=JSONObject(actual.toString())
        if(active.second=="environments"){environments=GroupUiState.rows(actual.optJSONObject("remoteEnvironments")?.optJSONArray("environments"));generation=actual.optString("selectedProfileGeneration");environmentId=""}
        actual.optJSONObject("input")?.let{input->selected=GroupUiState.rows(input.optJSONArray("files")).map{it.optString("attachmentId")}.toSet();if(input.optBoolean("registered"))selectedJob=input.optString("taskId")}
        if(active.second in setOf("open","reviewResult")){sourceConsent=false;returnConsent=false;textPage=0}
        if(active.second=="confirmResult")notice="已按原产物/投递编号核对；平台受理、未知和失败分别看真实回执，不代表成员已读"
    }
    val latestData by rememberUpdatedState(data);val latestPending by rememberUpdatedState(pending)
    // Bounded foreground GET observation only. No permanent receiver, cursor fiction, create or body replay.
    LaunchedEffect(data.optString("pageId"),selectedJob,foreground){
        if(!foreground||selectedJob.isBlank())return@LaunchedEffect
        repeat(30){delay(1500);val job=latestData.optJSONObject("originalJob");if(job?.optString("status")in setOf("completed","failed","cancelled","unknown","blocked","interrupted"))return@LaunchedEffect;if(latestPending==null)request("query",JSONObject().put("taskId",selectedJob))}
    }
    val input=data.optJSONObject("input");val registered=input?.optBoolean("registered")==true
    val inputScope=input?.optJSONObject("scope");val inputReview=input?.optJSONObject("review")
    val returnReview=data.optJSONObject("returnReview");val busy=pending!=null||input?.optBoolean("busy")==true
    LaunchedEffect(inputReview?.optString("digest")){sourceConsent=false}
    LaunchedEffect(returnReview?.optString("digest")){returnConsent=false;textPage=0}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("原群文件到电脑分析",fontSize=21.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f));TextButton(onClick=onBack){Text("返回")}}
        Text("从已认证原群任务选择原件，不进入私人附件库。只在实际确认API-key模式和凭据代次后，另审一个只读远端job；结果需再次确认内容与原群目标才发送。",fontSize=16.sp,lineHeight=24.sp)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(notice.isNotBlank())Text(notice,fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.primary)
        Text("离页使旧确认失效；已经派发的原job不因此撤回。需停止时，明确点原job停止；上传或部分副本不保证已删除。",fontSize=14.sp,lineHeight=22.sp)
        if(data.optString("pageId").isBlank()||!foreground)OutlinedButton(onClick={request("openPage",JSONObject().put("requestId",requestId))},enabled=!busy){Text("重新打开本原群入口（不创建job）")}
        val source=data.optJSONObject("groupSource")
        source?.let{val origin=it.optJSONObject("origin")?:JSONObject();Text("原受众：${origin.optString("provider")} · 账号 ${origin.optString("accountId")}\n会话 ${origin.optString("roomId")} · ${origin.optString("conversationKind","group")}\n${it.optString("spaceId")}",fontSize=14.sp,lineHeight=22.sp)}
        if(source==null)Text("原群尚不能授新外传：先完成原任务核验、结清未决动作或检查群授权。已有远端记录仍可只读查询/停止；不借私人入口绕过。",fontSize=14.sp,lineHeight=22.sp)
        if(input==null&&source!=null){
            Text("1. 选择真实电脑与环境",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            GroupUiState.rows(data.optJSONArray("remoteProfiles")).forEach{host->FilterChip(selected=profile==host.optString("id"),onClick={profile=host.optString("id");generation="";environments=emptyList();environmentId="";request("environments",JSONObject().put("profileId",profile))},enabled=!busy,label={Text(host.optString("name"))})}
            environments.forEach{env->val cap=env.optJSONObject("externalGroupInputCapabilities");FilterChip(selected=environmentId==env.optString("id"),onClick={environmentId=env.optString("id")},enabled=!busy&&cap?.optBoolean("supported")==true,label={Text("${env.optString("label")} · ${env.optString("workspace")}")})}
            if(environments.any{it.optJSONObject("externalGroupInputCapabilities")?.optBoolean("supported")!=true})Text("未启用当前协议的环境不能接群资料。需要明确配置Codex home、file凭据源与当前实际API-key account/read；configured、订阅账号或普通确认不作证明。私人DM原路径不受此限制。",fontSize=14.sp,lineHeight=22.sp)
            OutlinedTextField(prompt,{prompt=it},label={Text("这次如何使用所选群文件")},minLines=3,maxLines=6,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(model,{model=it},label={Text("远端模型（留空为其实际默认模型）")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(seconds,{seconds=it.filter(Char::isDigit).take(3)},label={Text("新执行时限：10–900秒")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            val env=environments.firstOrNull{it.optString("id")==environmentId}
            Button(onClick={val first=JSONObject().put("profileId",profile).put("bindingHash",generation).put("environmentId",environmentId).put("environmentBinding",env!!.optString("bindingHash")).put("worker","codex_app_server").put("readOnly",true).put("prompt",prompt).put("timeoutSeconds",seconds.toInt());if(model.isNotBlank())first.put("model",model);request("open",JSONObject().put("request",first))},enabled=!busy&&env!=null&&generation.isNotBlank()&&prompt.isNotBlank()&&prompt.length<=8000&&(seconds.toIntOrNull()?:0)in 10..900){Text("选择原群文件并核对这个目标")}
        }
        input?.let{native->
            Text("2. 明确选择原群字节",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text("${native.optJSONObject("request")?.optString("profileId")} · ${native.optJSONObject("request")?.optString("environmentId")}\n工作区：${native.optJSONObject("environment")?.optString("workspace")}",fontSize=14.sp,lineHeight=22.sp)
            if(!registered){val files=GroupUiState.rows(native.optJSONArray("availableFiles"));if(files.isEmpty())Text("此消息没有符合单文件256KiB限制的原件；未把超限文件截断后发送。",fontSize=14.sp)
                files.forEach{file->val id=file.optString("id");Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{choose->val next=if(choose)selected+id else selected-id;selected=next;val refs=JSONArray();files.filter{it.optString("id")in next}.forEach{refs.put(JSONObject().put("id",it.optString("id")).put("sha256",it.optString("sha256")))};request("input",JSONObject().put("command","selectInputs").put("payload",JSONObject().put("files",refs)))},enabled=!busy&&(id in selected||selected.size<8));Column(Modifier.weight(1f)){Text(file.optString("name"),fontSize=14.sp);Text("${file.optLong("size")}字节 · ${file.optString("mimeType")}",fontSize=13.sp);Text(file.optString("sha256"),fontSize=13.sp)}}}
                Text("最多8文件、每个256KiB、总计512KiB。只发送所列原字节；显示名由原件ID生成，不冒充平台原文件名。不会带私人附件、cookies或登录密钥。",fontSize=14.sp,lineHeight=22.sp)
                OutlinedButton(onClick={request("input",JSONObject().put("command","reviewInputs").put("payload",JSONObject()))},enabled=!busy&&selected.isNotEmpty()){Text("读取实际API模式并审阅这次外传")}
                inputReview?.let{review->val proof=inputScope?.optJSONObject("externalGroupApiMode");Text("准确目标：${inputScope?.optString("serviceBase")}\n实际模式：${proof?.optString("authMode")} · 代次 ${proof?.optString("credentialGeneration")}",fontSize=14.sp,lineHeight=22.sp);Text("独立授权1个新远端job，执行时限${native.optJSONObject("request")?.optInt("timeoutSeconds")}秒、输出最多192KiB；CLI内部模型次数/tokens/费用未知且不可硬限，不扣成1次模型，也不复用原群预算。API-key观察和代次一致不证明最终计费后端。",fontSize=14.sp,lineHeight=22.sp);Text("只读不等于读路径/操作系统隔离：请使用没有其它秘密的专用工作区/CLI账户。禁止借本次批准执行更广命令或写入；返回结果仍需你核对。",fontSize=14.sp,lineHeight=22.sp);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(sourceConsent,{sourceConsent=it},enabled=!busy);Text("只同意这份原群数据、确切远端目标和本次独立job风险范围",fontSize=14.sp,modifier=Modifier.weight(1f))};Button(onClick={request("input",JSONObject().put("command","confirmInputs").put("payload",JSONObject().put("digest",review.optString("digest")).put("billingAcknowledged",sourceConsent)))},enabled=!busy&&sourceConsent){Text("确认这一次外传与只读分析")}}
            }else{
                Text(native.optString("message"),fontSize=14.sp,lineHeight=22.sp);native.optJSONObject("inputState")?.let{state->Text("原暂存：${state.optString("phase")} · 远端已核对${state.optLong("verifiedBytes")}字节\n本机传输已写${native.optLong("writtenBytes")}字节，不等于接收确认",fontSize=14.sp,lineHeight=22.sp)}
            }
        }
        val jobs=GroupUiState.rows(data.optJSONArray("jobs"))
        if(jobs.isNotEmpty()){HorizontalDivider();Text("原远端记录（只读查询不新建工作）",fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
        jobs.forEach{job->val id=job.optString("taskId");Text("${job.optString("executorStatus").ifBlank{job.optString("status")}} · $id",fontSize=14.sp);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={selectedJob=id;request("query",JSONObject().put("taskId",id))},enabled=!busy){Text("查询原job")};TextButton(onClick={request("cancel",JSONObject().put("taskId",id))},enabled=!busy){Text("请求停止原job")}}
            job.optJSONObject("groupReturn")?.let{returned->Text("原群成果已冻结，不等于已发送",fontSize=14.sp);TextButton(onClick={request("queryReturn",JSONObject().put("taskId",id).put("operationId",returned.optString("operationId")))},enabled=!busy){Text("核对原冻结回执")};TextButton(onClick={request("inspectDelivery",JSONObject().put("taskId",id).put("operationId",returned.optString("operationId")))},enabled=!busy){Text("只查原群投递状态")}}
        }
        data.optJSONObject("originalJob")?.let{job->Text("当前只读job快照：${job.optString("status")}\n${job.optString("message")}",fontSize=14.sp,lineHeight=22.sp);job.optJSONObject("progress")?.let{progress->Text("有界原生步骤：${progress.optJSONArray("events")?.length()?:0}；不代表全部平台历史或实时流",fontSize=13.sp)}}
        if(selectedJob.isNotBlank()){
            Text("3. 选择同原群的实际回复消息",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text("以下只是本机最近收件窗口中的可核上下文；旧窗口过期可先回原群 /status 取得新消息，再只读刷新，不自动改群或重发未知。",fontSize=14.sp,lineHeight=22.sp)
            GroupUiState.rows(data.optJSONArray("replyContexts")).forEach{target->FilterChip(selected=replyEvent==target.optString("eventId"),onClick={replyEvent=target.optString("eventId")},enabled=!busy,label={Text("${target.optString("preview").ifBlank{target.optString("messageId")}}\n${target.optString("roomId")} · ${target.optString("conversationKind")}")})}
            OutlinedButton(onClick={request("describe")},enabled=!busy){Text("只读刷新原群上下文")}
            OutlinedButton(onClick={request("reviewResult",JSONObject().put("taskId",selectedJob).put("replyEventId",replyEvent))},enabled=!busy&&replyEvent.isNotBlank()){Text("读取完整原成果并审阅原群回发")}
        }
        returnReview?.let{review->val scope=review.optJSONObject("scope")?:JSONObject();val target=scope.optJSONObject("replyContext")?:JSONObject();val text=review.optString("text");val pages=maxOf(1,(text.length+3999)/4000);val index=textPage.coerceIn(0,pages-1)
            HorizontalDivider();Text("再次确认准确内容与原群受众",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text("${target.optString("provider")} · ${target.optString("accountId")}\n群/频道 ${target.optString("roomId")} · ${target.optString("conversationKind")}\n回复原消息 ${target.optString("messageId")}",fontSize=14.sp,lineHeight=22.sp)
            Text("完整UTF-8原文字结果 ${scope.optLong("bytes")}字节，将作为群文件冻结并发送。不是只发下列预览；未作独立业务成功保证。",fontSize=14.sp,lineHeight=22.sp)
            SelectionContainer{Text(text.substring(index*4000,minOf(text.length,(index+1)*4000)),fontSize=14.sp,lineHeight=22.sp)}
            Text("当前仅展示第${index+1}/${pages}页；不表示全部内容已经读完或理解。",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={textPage=index-1},enabled=index>0){Text("上一页")};TextButton(onClick={textPage=index+1},enabled=index+1<pages){Text("下一页")}}
            Text("SHA-256 ${scope.optString("sha256")}",fontSize=13.sp)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(returnConsent,{returnConsent=it},enabled=!busy);Text("确认这份完整内容及以上原群范围，仅这一次冻结并投递；不转私人或其它群",fontSize=14.sp,lineHeight=22.sp,modifier=Modifier.weight(1f))}
            Button(onClick={request("confirmResult",JSONObject().put("digest",review.optString("digest")).put("contentAndAudienceAcknowledged",returnConsent))},enabled=!busy&&returnConsent){Text("冻结准确成果并按原群发送一次")}
        }
        data.optJSONObject("delivery")?.let{delivery->Text("原群投递：${GroupUiState.deliveryLabel(delivery.optString("state"))}\n${delivery.optString("deliveryId")}",fontSize=14.sp,lineHeight=22.sp);Text("平台受理不等于成员已读；未知只查原outbox，不创建第二次发送或重跑模型。",fontSize=14.sp,lineHeight=22.sp)}
        data.optJSONObject("returnOperation")?.let{row->if(row.optString("state")=="unknown")OutlinedButton(onClick={request("queryReturn",JSONObject().put("taskId",row.optString("taskId")).put("operationId",row.optString("operationId")))},enabled=!busy){Text("原冻结效果未知：仅核对物理原件与原操作")}}
        TextButton(onClick={details=!details}){Text(if(details)"收起来源和版本细节" else "查看原来源/任务/版本细节")}
        if(details)SelectionContainer{Text(data.optJSONObject("groupSource")?.toString(2)?:"原来源当前不可授新派发",fontSize=13.sp,lineHeight=20.sp)}
    }
}
