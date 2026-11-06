package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;

/** Presence in the original task document only, not source validity or permission to transmit. */
public final class TaskAttachmentInputs {
    private TaskAttachmentInputs() { }
    public static boolean hasAny(JSONObject task) {
        if(task==null)return false;
        JSONArray selected=task.optJSONArray("attachmentSources");
        if(selected!=null&&selected.length()>0)return true;
        if(hasInitialOwnerWork(task))return true;
        JSONArray rows=task.optJSONArray("ownerInputs");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            JSONArray refs=row==null?null:row.optJSONArray("attachments");
            if(refs!=null&&refs.length()>0)return true;
        }
        return false;
    }
    public static boolean hasInitialOwnerWork(JSONObject task) {
        JSONObject work=task==null?null:task.optJSONObject("ownerWork");
        JSONArray rows=work==null?null:work.optJSONArray("initialMessages");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            JSONArray refs=row==null?null:row.optJSONArray("ownerAttachmentRefs");
            if(refs!=null&&refs.length()>0)return true;
        }
        return false;
    }
}
