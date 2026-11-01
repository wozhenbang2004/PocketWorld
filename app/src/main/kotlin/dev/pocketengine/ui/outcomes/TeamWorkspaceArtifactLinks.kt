package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost
import org.json.JSONObject

/** Original team snapshot rows are locators only; opening still resolves SQL receipts and real WorkspaceArtifacts. */
@Composable
internal fun TeamWorkspaceArtifactLinks(run:JSONObject,memberId:String?,onOpen:(TeamWorkspaceOutcomeHost.Selection)->Unit){
    val seen=mutableSetOf<String>()
    for(member in run.optJSONArray("members").objects().filter{memberId==null||it.optString("id")==memberId}){
        val refs=member.optJSONObject("assignmentTask")?.optJSONArray("artifacts").objects()+member.optJSONArray("roundStates").objects().flatMap{it.optJSONArray("artifacts").objects()}
        val unique=refs.filter{seen.add(it.optString("artifactId")+":"+it.optString("sha256"))};if(unique.isEmpty())continue
        Text("${member.optString("name",member.optString("id"))}的冻结文件",fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=14.dp,bottom=6.dp))
        for(ref in unique){
            val selected=try{if(ref.getString("teamRunId")!=run.getString("id")||ref.getString("memberId")!=member.getString("id"))throw SecurityException("原团队来源不一致");TeamWorkspaceOutcomeHost.Selection.fromManifest(ref)}catch(_:Exception){null}
            if(selected==null){Text("原成果定位当前不可核对，请刷新原分工。",color=Danger,fontSize=12.sp);continue}
            Row(Modifier.fillMaxWidth().clickable{onOpen(selected)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(ref.optString("path"),fontSize=14.sp);Text((if(ref.has("assignmentRevision"))"分工修订 ${ref.optInt("assignmentRevision")} · "else "")+"固定文件版本 ${ref.optInt("draftVersion")}",color=SecondaryInk,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))}
                Text("查看",color=SecondaryInk,fontSize=12.sp)
            }
            HorizontalDivider(color=Line)
        }
    }
}
