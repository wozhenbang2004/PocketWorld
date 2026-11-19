package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Split already-returned text from one bound host job. No re-query, log collector, inferred failure or new byte transfer. */
public final class RemoteOutputExcerpt {
    private RemoteOutputExcerpt(){}
    public static final int VIEW_UNITS=65_536;
    private static final int HEAD=49_152,TAIL=VIEW_UNITS-HEAD;
    static boolean add(JSONObject view,JSONObject job,JSONObject source,String text)throws Exception{
        if(text.length()<=VIEW_UNITS)return false;
        for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"}){
            String reported=key.equals("jobId")?"id":key.equals("jobOperationId")?"operationId":key;
            if(!(source.opt(key) instanceof String s)||s.isEmpty()||!s.equals(job.opt(reported)))return false;
        }
        if(!source.getString("workspaceIdentity").matches("[a-f0-9]{64}"))return false;
        int head=HEAD,tail=text.length()-TAIL;
        if(Character.isHighSurrogate(text.charAt(head-1))&&Character.isLowSurrogate(text.charAt(head)))head--;
        if(Character.isHighSurrogate(text.charAt(tail-1))&&Character.isLowSurrogate(text.charAt(tail)))tail++;
        JSONObject excerpt=new JSONObject().put("protocol","original_received_output_parts_v1").put("headEnd",head).put("tailStart",tail)
            .put("receivedUnits",text.length()).put("tail",text.substring(tail));
        for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"})excerpt.put(key,source.getString(key));
        view.put("workspaceIdentity",source.getString("workspaceIdentity")).put("output",text.substring(0,head))
            .put("outputViewTruncated",true).put("outputExcerpt",excerpt);return true;
    }
}
