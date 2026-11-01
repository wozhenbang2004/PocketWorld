package dev.pocketengine.core;
import org.json.JSONObject;
public final class RunOutcome {
    public final String status;
    public final String summary;
    public final JSONObject evidence;
    public RunOutcome(String status, String summary, JSONObject evidence) {
        this.status=status; this.summary=summary; this.evidence=evidence == null ? new JSONObject() : evidence;
    }
}
