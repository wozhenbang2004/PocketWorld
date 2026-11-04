package dev.pocketengine.bootstrap;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local, bounded failure locations. Never includes Throwable messages, file paths or suppressed data. */
public final class BootstrapDiagnostics {
    private BootstrapDiagnostics() { }
    public static JSONObject capture(Throwable failure) {
        JSONObject result=new JSONObject();JSONArray causes=new JSONArray();
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for(Throwable cause=failure;cause!=null&&causes.length()<4&&seen.add(cause);cause=cause.getCause()) {
                JSONObject item=new JSONObject().put("type",symbol(cause.getClass().getName()));JSONArray frames=new JSONArray();
                for(StackTraceElement frame:cause.getStackTrace()) {
                    if(!frame.getClassName().startsWith("dev.pocketengine."))continue;
                    frames.put(new JSONObject().put("class",symbol(frame.getClassName())).put("method",symbol(frame.getMethodName())).put("line",Math.max(-1,frame.getLineNumber())));
                    if(frames.length()==5)break;
                }
                causes.put(item.put("frames",frames));
            }
            result.put("causes",causes);
        } catch(Exception unavailable) { return new JSONObject(); }
        return result;
    }
    private static String symbol(String value) { return value!=null&&value.matches("[A-Za-z0-9_.$<>]{1,200}")?value:"unknown"; }
}
