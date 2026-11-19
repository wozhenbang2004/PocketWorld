package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Original on-phone receipt only; no server/profile lookup, file creation or re-download. */
public final class RemoteSavedOriginal {
    private RemoteSavedOriginal() {}
    private static final String[] VERSION={"snapshotId","snapshotVersion","path","fileVersion","sha256"};
    public static JSONObject claim(JSONObject row)throws Exception {
        JSONObject c=new JSONObject().put("taskId",text(row,"taskId")).put("attachmentId",text(row.getJSONObject("attachment"),"id"));
        for(String k:VERSION)c.put(k,text(row,k));
        return c;
    }
    private static String text(JSONObject value,String name)throws Exception {
        Object v=value.opt(name);
        if(!(v instanceof String s)||s.isBlank()||s.length()>2048||s.indexOf(0)>=0)throw fail();
        return s;
    }
    private static SecurityException fail(){return new SecurityException("这份手机原件已变化或不属于所选主机任务，未打开其他文件。");}
    private static boolean same(JSONObject a,JSONObject b,String key){return a.opt(key) instanceof String s && s.equals(b.opt(key));}
    private static long count(JSONObject v,String key){Object n=v.opt(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)throw fail();return ((Number)n).longValue();}
    /** Only the real TaskStore task can resolve a path/version to its saved AttachmentBook item. */
    public static JSONObject resolve(JSONObject task,JSONObject wanted)throws Exception {
        if(!"remote".equals(task.opt("feature"))||!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||
                !"submitJob".equals(task.opt("command"))||!text(task,"id").equals(wanted.opt("taskId"))||!text(wanted,"sha256").matches("[0-9a-f]{64}"))throw fail();
        JSONArray refs=task.optJSONArray("remoteSnapshotAttachments");if(refs==null||refs.length()>64)throw fail();
        JSONObject chosen=null;boolean seenId=false;
        for(int i=0;i<refs.length();i++){
            JSONObject r=refs.optJSONObject(i),a=r==null?null:r.optJSONObject("attachment");
            if(a==null||!text(wanted,"attachmentId").equals(a.opt("id"))||!same(r,wanted,"taskId"))continue;
            if(seenId)throw fail();seenId=true; // Never restore one AttachmentBook ID as two different frozen files.
            boolean exact=true;for(String k:VERSION)if(!same(r,wanted,k))exact=false;
            if(!exact)continue;
            JSONObject source=a.optJSONObject("source");
            if(chosen!=null||source==null||!"remote_result_snapshot".equals(source.opt("kind"))||source.opt("immutableArtifact")!=Boolean.TRUE||
                    !same(source,r,"taskId")||!same(a,r,"sha256")||count(a,"size")!=count(r,"bytes")||count(source,"bytes")!=count(r,"bytes"))throw fail();
            for(String k:VERSION)if(!same(source,r,k))throw fail();
            chosen=a;
        }
        if(chosen==null)throw fail();return new JSONObject(chosen.toString());
    }
    /** Called by the private original-format reader against current AttachmentBook metadata, also after restore. */
    public static void requireLive(JSONObject task,JSONObject actual)throws Exception {
        JSONObject current=actual.getJSONObject("source"),wanted=new JSONObject().put("taskId",text(task,"id")).put("attachmentId",text(actual,"id"));
        for(String k:VERSION)wanted.put(k,text(current,k));
        JSONObject archived=resolve(task,wanted),source=archived.getJSONObject("source");
        if(!same(archived,actual,"sha256")||count(archived,"size")!=count(actual,"size")||!same(current,source,"taskId")||
                !"remote_result_snapshot".equals(current.opt("kind"))||current.opt("immutableArtifact")!=Boolean.TRUE||count(current,"bytes")!=count(source,"bytes"))throw fail();
        for(String k:new String[]{"operationId","jobId","jobOperationId","environmentId","worker","workspaceIdentity","profileId","serviceBase","captureSource"})
            if(!same(current,source,k))throw fail();
    }
}
