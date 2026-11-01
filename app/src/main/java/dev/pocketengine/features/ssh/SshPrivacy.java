package dev.pocketengine.features.ssh;

import org.json.JSONObject;

/** Diagnostic body belongs only to the currently open native private page. */
public final class SshPrivacy {
    private SshPrivacy(){}
    public static JSONObject doctorMetadata(JSONObject value)throws Exception {
        JSONObject clean=new JSONObject();if(value==null)return clean;
        for(String key:new String[]{"id","operationId","hostTaskId","taskId","profileId","runId","epoch","hostManaged","status","accepted","verified","readOnly","persistReport","replayAllowed","interrupted","effectsUnknown","cancelRequested"})if(value.has(key))clean.put(key,value.get(key));
        return clean.put("diagnosticBodyOmitted",true).put("readOnly",true).put("persistReport",false);
    }
}
