package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.teams.TeamsModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Explicitly opened original private task surface. No file body goes into persistent Web state or the chat. */
internal fun PocketWorldController.readWebOriginalTeamFileDiff(source:JSONObject,current:()->Boolean,done:(JSONObject?,String?)->Unit){
    val task=source.getString("taskId");val run=source.getString("runId");val approval=source.getString("approvalId");val proof=source.getString("proof")
    val epoch=source.opt("epoch");val at=source.opt("offset");val previous=source.getString("diffHash")
    require(epoch is Int && epoch>=1 && at is Int && at in 0..65536 && proof.matches(Regex("[a-f0-9]{64}")) &&
        (at==0 && previous.isEmpty() || at>0 && previous.matches(Regex("[a-f0-9]{64}")))) { "原审批范围或差异页码无效" }
    val reader=RunControl.forOriginalRecovery(task,run,epoch)
    reader.setAuthorityCheck { check(current()){ "原任务窗口已关闭" } }
    val main=Handler(Looper.getMainLooper())
    runTeamPhoneWork {
        val result=runCatching {
            reader.check()
            val teams=webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队读取暂不可用")
            teams.readOwnerFileApprovalPage(task,run,epoch,approval,proof,previous,at,reader).also { reader.check() }
        }
        main.post { if(!current())done(null,"原任务窗口已关闭") else result.fold({done(it,null)},{done(null,it.message?:"未能核对这份原文件差异")}) }
    }
}
