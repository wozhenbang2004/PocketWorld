package dev.pocketengine.ui
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RemoteOwnerUiStateTest {
 @Test fun reviewIsNativePageAndTaskBoundAndNeedsFreshRead() {
  val snapshot=JSONObject().put("taskId","task-a").put("windowId","native-window").put("reviewId","native-review").put("fresh",true)
  assertTrue(remoteOwnerReviewMatches(snapshot,"task-a"));assertFalse(remoteOwnerReviewMatches(snapshot,"task-b"));assertFalse(remoteOwnerReviewMatches(JSONObject(snapshot.toString()).put("fresh",false),"task-a"));assertFalse(remoteOwnerReviewMatches(JSONObject(snapshot.toString()).put("reviewId",""),"task-a"))
 }
 @Test fun approvalClaimBelongsToOriginalRunAndExactRequestNotModelText() {
  val old=JSONObject().put("operationId","old").put("tool","remote_approval").put("runId","old-run").put("arguments",JSONObject().put("requestId","request-one"))
  val current=JSONObject().put("operationId","native-claim").put("tool","remote_approval").put("runId","run-a").put("arguments",JSONObject().put("requestId","request-one"))
  val task=JSONObject().put("runId","run-a").put("operations",JSONArray().put(old).put(current))
  assertEquals("native-claim",remoteApprovalClaim(task,"request-one")?.getString("operationId"));assertNull(remoteApprovalClaim(task,"request-two"));assertNull(remoteApprovalClaim(null,"request-one"))
 }
 @Test fun previousRemoteEpochCannotReplaceNewRunAtSameOrNewerWallClock() {
  fun task(epoch: Int, at: Long) = taskFromJson(JSONObject().put("id","task-a").put("feature","remote").put("coordinatorEpoch",epoch).put("runId","run-$epoch").put("updatedAt",at))
  val current=task(2,100)
  assertSame(current,mergeTaskSummary(task(1,100),current));assertSame(current,mergeTaskSummary(task(1,200),current));assertEquals(3,mergeTaskSummary(task(3,90),current).raw.getInt("coordinatorEpoch"))
 }

}
