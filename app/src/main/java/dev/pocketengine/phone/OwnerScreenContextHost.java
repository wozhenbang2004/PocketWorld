package dev.pocketengine.phone;

import org.json.JSONObject;

/** Native owner entry only. The Application retains the original TaskStore and RunControl. */
public interface OwnerScreenContextHost {
    OwnerScreenContext openOwnerScreenContext(JSONObject expected) throws Exception;
    JSONObject submitOwnerScreenContext(OwnerScreenContext capture, OwnerScreenContext.Preview preview,
                                       String clientMessageId, String note) throws Exception;
    JSONObject ownerScreenContextReceipt(JSONObject expected, String clientMessageId) throws Exception;

    /** A definite refusal before an input write began. Other failures remain unconfirmed. */
    final class Rejected extends Exception {
        public Rejected(String message, Throwable cause) { super(message, cause); }
    }
}
