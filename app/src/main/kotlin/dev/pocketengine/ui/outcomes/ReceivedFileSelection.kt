package dev.pocketengine.ui

import org.json.JSONObject

/** Source-only transition object held in native memory. Never decoded from a Web request. */
internal data class ReceivedFileSelection(
    val task:String,val op:String,val device:String,val instance:String,val request:String,val run:String,val epoch:Int,
    val id:String,val sha:String,val size:Long,val artifact:String,val transfer:String
){
    companion object {
        fun fromNative(checked:JSONObject):ReceivedFileSelection{
            require(checked.getString("format")=="pocketworld.owner-local-received-file.v1" && checked.getString("state")=="verified_here")
            return ReceivedFileSelection(checked.getString("taskId"),checked.getString("operationId"),checked.getString("targetDeviceId"),
                checked.getString("instanceId"),checked.getString("argumentsDigest"),checked.getString("requestRunId"),checked.getInt("requestExecutorEpoch"),
                checked.getString("attachmentId"),checked.getString("sha256"),checked.getLong("bytes"),checked.getString("sourceArtifactId"),checked.getString("sourceTransferId"))
        }
    }
    fun requireBookOrigin(meta:JSONObject){
        val origin=meta.getJSONObject("source")
        require(meta.getString("id")==id && meta.getString("sha256")==sha && meta.getLong("size")==size
            && meta.getString("principalId")=="owner" && meta.getString("spaceId")=="private" && !meta.optBoolean("outputSourceRetired")
            && origin.getString("kind")=="device_received_file" && origin.getString("artifactKind")=="file" && !origin.has("groupSpaceId")
            && origin.getString("sourceOperationId")==op && origin.getString("sourceDeviceId")==device && origin.getString("sourceTaskId")==task
            && origin.getString("artifactId")==artifact && origin.getString("transferId")==transfer && origin.getString("versionId")==sha
            && origin.getString("taskId")==task && origin.getString("receivingOriginalTaskId")==task && origin.getString("receivingOriginalRunId")==run
            && origin.getInt("receivingOriginalEpoch")==epoch && origin.getString("sourceRequestRunId")==run && origin.getInt("sourceRequestExecutorEpoch")==epoch
            && origin.getString("contentDigest")==sha && origin.getJSONObject("nativePublication").getString("operationId")==op)
            { "原任务中的附件已不再是刚才核实的固定交付，没有换用同名文件" }
    }
}
