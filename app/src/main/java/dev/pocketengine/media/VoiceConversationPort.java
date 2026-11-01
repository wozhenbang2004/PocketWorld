package dev.pocketengine.media;

import dev.pocketengine.core.RunControl;

/** Native host only. Local preparation freezes the selected text-model route before capture. */
public interface VoiceConversationPort {
    /** Validate local configuration only; no network, account refresh, recording or model request. */
    Request prepare() throws Exception;
    interface Request {
        /** Native retained authority; recheck captured owner-controlled routing at later admissions. */
        default void checkCurrent() throws Exception {}
        /** Read-only original receipt locator, not a task/tool grant. */
        default org.json.JSONObject usageReference()throws Exception{return null;}
        /** Exactly one final text request with no tools, task execution, planner or automatic retry. */
        String complete(String finalText,RunControl control) throws Exception;
    }
}
