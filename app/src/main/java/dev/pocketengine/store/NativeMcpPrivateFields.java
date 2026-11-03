package dev.pocketengine.store;

import org.json.JSONObject;

/** Public data projection only, never a proof/approval parser. The SQL row retains the original
 * reserved top-level native ref. Nested RPC business content and all other keys are untouched. */
public final class NativeMcpPrivateFields {
    public static final String HOST_KEY="_nativeMcpJob";
    private NativeMcpPrivateFields() {}
    public static Object publicArguments(Object original)throws Exception {
        if(!(original instanceof JSONObject))return original;
        JSONObject projected=new JSONObject(original.toString());projected.remove(HOST_KEY);return projected;
    }
    public static JSONObject publicOperation(JSONObject original)throws Exception {
        JSONObject projected=new JSONObject(original.toString());if(projected.has("arguments"))projected.put("arguments",publicArguments(projected.get("arguments")));return projected;
    }
}
