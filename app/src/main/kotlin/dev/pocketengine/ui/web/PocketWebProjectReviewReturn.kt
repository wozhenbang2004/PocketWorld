package dev.pocketengine.ui.web

import dev.pocketengine.core.RunControl
import dev.pocketengine.features.teams.TeamsModule
import dev.pocketengine.store.TaskStore
import org.json.JSONObject

/** Locators have no authority. Both the foreground project and original team recapture this same SQL record. */
internal object PocketWebProjectReviewReturn {
    const val DIRECTORY = "tasks.projectTeamReviews"
    const val ORIGINAL = "tasks.projectTeamReview.original"
    private val fields = setOf("parentTaskId","parentRunId","parentEpoch","conversationId","teamTaskId","teamRunId","projectId","checkoutId",
        "planHash","reviewerTaskId","reviewerRunId","selectionHash","snapshotIdentity","parentSourceDigest","sourceDigest","fileCount")

    fun read(action:String, args:JSONObject, store:TaskStore, read:RunControl, module:()->TeamsModule):JSONObject {
        val exact=action==ORIGINAL
        require(exact || action==DIRECTORY)
        val keys=if(exact) setOf("taskId","runId","epoch","source") else setOf("taskId","runId","epoch","after")
        require(args.keys().asSequence().toSet()==keys)
        val parent=args.getString("taskId");val run=args.getString("runId");val epoch=args.opt("epoch")
        require(epoch is Int && epoch>0 && parent.length in 1..240 && run.length in 1..240)
        if(exact){
            val source=args.getJSONObject("source")
            check(source.opt("parentTaskId")==parent && source.opt("parentRunId")==run && source.opt("parentEpoch")==epoch)
            return JSONObject().put("source",original(module(),source,source.getString("teamRunId"),read)).put("readOnly",true).put("executionAuthorized",false)
        }
        val after=args.opt("after"); require(after is String && after.length<=240)
        return store.projectTeamFirstReviews(parent,run,epoch,after,read)
    }
    fun original(module: TeamsModule, source: JSONObject, team: String, read: RunControl): JSONObject {
        require(source.keys().asSequence().toSet() == fields && source.opt("teamRunId") == team)
        return module.projectFirstReviewOrigin(source,team,read)
    }
    fun index(files: JSONObject, decision: JSONObject, source: JSONObject) {
        check(files.opt("projectId") == source.opt("projectId") && files.opt("planHash") == source.opt("planHash")
            && files.getJSONArray("files").length() == source.getInt("fileCount") && decision.opt("state") == "eligible_project_first") {
            "原项目、独审或当前冻结目录已变，没有打开替代文件"
        }
    }
}
