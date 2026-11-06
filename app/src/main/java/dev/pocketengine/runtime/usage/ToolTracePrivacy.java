package dev.pocketengine.runtime.usage;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Privacy projection for tool trajectory copies only, never executable inputs or receipts.
 * Keeps ordinary tool bodies. This is not a detector for arbitrary secrets in free text. */
public final class ToolTracePrivacy {
    private static final String OMITTED = "[credential omitted]";
    private static final Set<String> CREDENTIAL_FIELDS = Set.of("authorization", "proxyauthorization",
            "cookie", "setcookie", "apikey", "xapikey", "accesstoken", "refreshtoken", "idtoken",
            "clientsecret", "password", "passwd", "privatekey", "credentials", "sessiontoken",
            "awsaccesskeyid", "awssecretaccesskey", "awssessiontoken", "secretaccesskey");
    private ToolTracePrivacy() { }

    /** Applied again after detail expansion so checkpoint/operation fallback cannot undo redaction. */
    public static JSONObject tool(JSONObject original) throws Exception {
        JSONObject copy = new JSONObject(original.toString());
        if (copy.has("input")) copy.put("input", arguments(copy.opt("input")));
        for (String field : new String[]{"output", "schema", "error"})
            if (copy.has(field)) copy.put(field, redact(copy.opt(field), 0));
        JSONObject error = copy.optJSONObject("error");
        if (error != null && error.has("kind")) {
            boolean receiptAvailable = Boolean.TRUE.equals(error.opt("receiptAvailable"));
            error.put("code", receiptAvailable ? "TOOL_RECEIPT_FOLLOWUP_FAILED" : "TOOL_EXECUTION_EXCEPTION")
                    .put("message", receiptAvailable ? "工具已返回回执，但后续处理未完成，请核对原操作" : "工具执行或回执处理未完成，请核对原操作");
        }
        return copy;
    }

    /** Invalid argument text cannot pass around structural credential filtering.
     * This marker intentionally does not request detail fallback with available=false. */
    public static Object arguments(Object input) throws Exception {
        if (input instanceof String text) {
            try {
                JSONTokener parser = new JSONTokener(text);
                Object parsed = parser.nextValue();
                if (parsed instanceof JSONObject && parser.nextClean() == 0) return redact(parsed, 0);
            } catch (Exception malformed) { /* Preserve shape, never rejected raw argument text. */ }
            return new JSONObject().put("redacted", true).put("reason", "invalid_tool_arguments")
                    .put("characterCount", text.length());
        }
        return redact(input, 0);
    }

    private static Object redact(Object value, int depth) throws Exception {
        if (depth > 48) return new JSONObject().put("redacted", true).put("reason", "trace_structure_depth_limit");
        if (value instanceof JSONObject object) {
            JSONObject copy = new JSONObject();
            boolean credentialPair = credential(object.optString("name")) || credential(object.optString("key"));
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                boolean pairValue = credentialPair && ("value".equalsIgnoreCase(key) || "values".equalsIgnoreCase(key));
                copy.put(key, credential(key) || pairValue ? OMITTED : redact(object.opt(key), depth + 1));
            }
            return copy;
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (int i = 0; i < array.length(); i++) copy.put(redact(array.opt(i), depth + 1));
            return copy;
        }
        return value == null ? JSONObject.NULL : value;
    }

    private static boolean credential(String name) {
        return CREDENTIAL_FIELDS.contains(name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""));
    }
}
