package dev.pocketengine.media;
import org.json.JSONObject;
/** Native host only. Both methods use the one TaskStore; create never starts a Run or model/tool. */
public interface RealtimeTurnCommitter {
    JSONObject create(RealtimeReviewedTurn turn)throws Exception;
    /** Read-only reconciliation of the same identity after an uncertain persistence result. */
    JSONObject lookup(RealtimeReviewedTurn turn)throws Exception;
}
