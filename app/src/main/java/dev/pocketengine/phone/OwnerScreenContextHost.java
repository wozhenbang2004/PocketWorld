package dev.pocketengine.phone;

import org.json.JSONObject;

/** Native owner entry only. The Application retains the original TaskStore and RunControl. */
public interface OwnerScreenContextHost {
    OwnerScreenContext openOwnerScreenContext(JSONObject expected) throws Exception;
    default JSONObject ownerTextContextTarget(JSONObject expected) throws Exception {
        throw new Rejected("原任务宿主未提供文字补充入口", null);
    }
    JSONObject submitOwnerScreenContext(OwnerScreenContext capture, OwnerScreenContext.Preview preview,
                                       String clientMessageId, String note) throws Exception;
    /** Explicit owner text only. This must not capture a screen or resume a paused task. */
    default JSONObject submitOwnerTextContext(JSONObject expected, String clientMessageId, String note) throws Exception {
        throw new Rejected("原任务宿主未提供文字补充入口", null);
    }
    JSONObject ownerScreenContextReceipt(JSONObject expected, String clientMessageId) throws Exception;

    /** A definite refusal before an input write began. Other failures remain unconfirmed. */
    final class Rejected extends Exception {
        public Rejected(String message, Throwable cause) { super(message, cause); }
    }
}
