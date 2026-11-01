package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same outcome overlay, with a separate native provider; workspace artifact IDs never enter the private attachment reader. */
@Composable
internal fun TeamWorkspaceArtifactPage(flow:AttachmentOutcomeCoordinator){
    val state=flow.state;val selected=state.team?:return
    var difference by remember(selected.toJson().toString()){mutableStateOf(false)}
    var details by remember(selected.toJson().toString()){mutableStateOf(false)}
    val actual=state.snapshot;val meta=actual?.optJSONObject("artifact");val result=if(difference)state.teamDifference else actual
    BackHandler(onBack=flow::close)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick=flow::close){Text("返回")}
            Text("成员文件成果",fontWeight=FontWeight.SemiBold,fontSize=17.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={if(difference)flow.readTeamDifference()else flow.readTeamFile()},enabled=!state.busy){Text("刷新")}
        }
        HorizontalDivider(color=Line)
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Ink,trackColor=Soft)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(meta?.optString("path")?:"正在核对原成员成果",fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold)
            actual?.optJSONObject("reference")?.let{ref->Text("${ref.optString("memberId")} · "+(if(ref.has("assignmentRevision"))"分工修订 ${ref.optInt("assignmentRevision")} · "else "")+"成果固定版本 ${ref.optInt("draftVersion")}",color=SecondaryInk,fontSize=13.sp)}
            Text("查看成员已冻结的真实文件。后续草稿、原项目与本份成果分别保留。",color=SecondaryInk,fontSize=13.sp,lineHeight=22.sp)
            if(flow.canReadTeamDifference())TextButton(onClick=flow::readNewTeamArtifact,enabled=!state.busy){Text("查看本分工的新冻结成果")}
            if(state.teamPrevious!=null)TextButton(onClick=flow::readPreviousTeamArtifact,enabled=!state.busy){Text("查看上一份固定成果")}
            state.error?.let{Text(it,color=Danger,fontSize=13.sp,lineHeight=21.sp)}
            state.notice?.let{Text(it,color=SecondaryInk,fontSize=13.sp,lineHeight=21.sp)}
            if(state.busy)TextButton(onClick=flow::cancel){Text("停止本次读取")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!difference,onClick={difference=false;if(actual==null)flow.readTeamFile()},label={Text("实际文件内容")})
                FilterChip(selected=difference,onClick={difference=true;if(state.teamDifference==null)flow.readTeamDifference()},enabled=flow.canReadTeamDifference(),label={Text("与原项目比较")})
            }
            if(difference&&result!=null){
                Text("比较时原项目版本 ${result.optInt("currentVersion")}",fontWeight=FontWeight.Medium,fontSize=14.sp)
                if(!result.optBoolean("matchesOwnerBase"))Text("原项目已偏离本分工开始时的基线；请回团队重新核对，查看差异不会整合文件。",color=Danger,fontSize=13.sp,lineHeight=22.sp)
                if(result.optBoolean("truncated"))Text("完整差异超出原比较范围，当前结果不完整；请回团队核对。",color=Danger,fontSize=13.sp,lineHeight=22.sp)
            }
            result?.let{page->
                val text=page.optString(if(difference)"diff"else "content")
                if(!difference&&page.optBoolean("contentAvailable",true)==false)Text(page.optString("message","原文件保留完整字节，可以查看原件或导出。"),color=SecondaryInk,fontSize=13.sp)
                else if(text.isEmpty())Text(if(difference)"当前比较范围没有文字差异。"else "这是一个实际保存的空文本文件。",color=SecondaryInk,fontSize=13.sp)
                else SelectionContainer{Text(text,fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=23.sp)}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(page.optInt("offset")>0)TextButton(onClick={if(difference)flow.readTeamDifference()else flow.readTeamFile()},enabled=!state.busy){Text("从开头查看")}
                    if(!page.isNull("nextOffset")&&page.opt("nextOffset") is Number)OutlinedButton(onClick={if(difference)flow.readTeamDifference(page.getInt("nextOffset"))else flow.readTeamFile(page.getInt("nextOffset"))},enabled=!state.busy){Text("读取下一部分")}
                }
            }
            if(!flow.canReadTeamDifference())Text("原团队比较入口尚未挂载，当前仅显示真实冻结文件。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
            TextButton(onClick={details=!details}){Text(if(details)"收起来源与版本"else "来源与固定版本")}
            if(details){
                actual?.optJSONObject("reference")?.let{ref->SelectionContainer{Text("原分工 · ${ref.optString("assignmentId")}\n原执行 · ${ref.optString("runId")}\n成果 SHA-256\n${ref.optString("sha256")}",color=SecondaryInk,fontSize=11.sp,lineHeight=18.sp)}}
                Text("读取和导出不授予项目写入权限，也不会恢复历史执行。继续修改请回原分工，由原团队控制处理。",color=SecondaryInk,fontSize=12.sp,lineHeight=20.sp)
            }
        }
        HorizontalDivider(color=Line)
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(meta!=null&&meta.optString("kind")!="file_tombstone")OutlinedButton(onClick=flow::openOriginalFile,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("查看这份原件")}
            OutlinedButton(onClick=flow::prepareExport,enabled=!state.busy&&meta!=null&&meta.optString("kind")!="file_tombstone",modifier=Modifier.fillMaxWidth()){Text("导出这份固定版本")}
            Button(onClick=flow::continueTask,enabled=!state.busy&&actual!=null,modifier=Modifier.fillMaxWidth()){Text("回原分工处理修改")}
        }
    }
}
