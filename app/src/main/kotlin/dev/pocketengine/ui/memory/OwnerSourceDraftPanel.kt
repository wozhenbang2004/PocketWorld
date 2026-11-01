package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.assistant.OwnerComposerState
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Original composer only. Metadata survives drafts; no original prose or grant is copied into it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerSourceDraftPanel(draft:OwnerComposerState.Draft,controller:PocketWorldController,viewEpoch:Int){
    val refs=remember(draft.key,draft.revision){draft.sourceRefs()}
    if(refs.length()==0)return
    var shown by remember(draft.key){mutableStateOf<JSONObject?>(null)}
    var pending by remember(draft.key){mutableStateOf<String?>(null)}
    var exact by remember(draft.key){mutableStateOf<JSONObject?>(null)}
    var error by remember(draft.key){mutableStateOf<String?>(null)}
    fun read(ref:JSONObject){
        val source=ref.getJSONObject("sourceRef")
        val locator=JSONObject().put("kind","owner_native_original").put("generation",ref.getString("generation")).put("conversationId",ref.getString("sourceConversationId"))
            .put("recordId",source.getString("sourceId")).put("recordVersion",source.getLong("revision")).put("fingerprint",ref.getString("bodyFingerprint"))
        shown=JSONObject(ref.toString());exact=null;error=null
        pending=controller.requestOwnerDomain("taskMemory","readOwnerOriginalSearchHit",JSONObject().put("ownerLocator",locator).put("offset",ref.getInt("offset")).put("maxChars",maxOf(64,ref.getInt("chars"))))
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("已选原话范围 · 发送时再次核对；不是正式记忆或新权限",style=MaterialTheme.typography.labelMedium)
        for(i in 0 until refs.length()){
            val ref=refs.getJSONObject(i)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text("原消息修订 ${ref.getJSONObject("sourceRef").getLong("revision")} · UTF-16 ${ref.getInt("offset")}＋${ref.getInt("chars")}",style=MaterialTheme.typography.bodySmall)
                    Text(ref.getString("sourceConversationId"),style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={controller.enterOwnerDomain("taskMemory");read(ref)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){Text("原文")}
                TextButton(onClick={controller.removeComposerSource(i,draft.key,viewEpoch)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){Text("移除")}
            }
        }
    }
    if(shown!=null){
        DisposableEffect(draft.key){onDispose{controller.leaveOwnerDomain("taskMemory")}}
        LaunchedEffect(pending,controller.state.ownerResponses("taskMemory")["readOwnerOriginalSearchHit"]?.toString()){
            val id=pending?:return@LaunchedEffect
            val envelope=TaskMemoryEvidenceGuard.exactEnvelope(id,controller.state.ownerResponses("taskMemory")["readOwnerOriginalSearchHit"])?:return@LaunchedEffect
            pending=null
            if(!envelope.getBoolean("ok")){exact=null;error=envelope.optString("error","原文范围未读到");return@LaunchedEffect}
            try{
                val page=envelope.getJSONObject("result");require(page.opt("ownerOnly")==true&&page.opt("modelReferenceRegistered")==false&&page.opt("modelCalled")==false)
                if(page.optString("status")!="current"){exact=null;error=page.optString("reason","原版本已变，请移除此引用并重新选择；不显示新正文")}
                else{
                    val original=shown?:return@LaunchedEffect;val locator=page.getJSONObject("ownerLocator")
                    require(locator.getString("generation")==original.getString("generation")&&locator.getString("conversationId")==original.getString("sourceConversationId")&&locator.getString("recordId")==original.getJSONObject("sourceRef").getString("sourceId")&&locator.getLong("recordVersion")==original.getJSONObject("sourceRef").getLong("revision")&&locator.getString("fingerprint")==original.getString("bodyFingerprint"))
                    exact=JSONObject(page.toString())
                }
            }catch(_:Exception){exact=null;error="原件回执不属于这个草稿引用，未展示替代正文"}
        }
        ModalBottomSheet(onDismissRequest={shown=null;exact=null;pending=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("草稿引用的原文",style=MaterialTheme.typography.titleMedium)
                Text("关闭只回原草稿，不发送、不删除、不恢复已停用的派生资格。",style=MaterialTheme.typography.bodySmall)
                if(pending!=null)Text("正在按原版本、指纹与范围读取…")
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                exact?.let{page->
                    val range=page.getJSONObject("readRange")
                    Text("本次实际读取 UTF-16 ${range.getInt("offset")}＋${range.getInt("chars")} / ${range.getInt("totalChars")}",style=MaterialTheme.typography.bodySmall)
                    if(page.optString("derivedUseStatus")=="retired_for_derived_context")Text("原话仍可查看，但已停止派生，不能随草稿再次发给模型。",style=MaterialTheme.typography.bodySmall)
                    SelectionContainer{Text(page.getString("body"))}
                }
                TextButton(onClick={shown=null;exact=null;pending=null}){Text("返回这份原草稿")}
            }
        }
    }
}
