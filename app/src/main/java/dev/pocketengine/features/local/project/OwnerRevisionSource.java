package dev.pocketengine.features.local.project;

import org.json.JSONArray;
import org.json.JSONObject;

/** A single, explicitly selected saved revision. The checkout identifiers describe ancestry, not a file to open. */
public final class OwnerRevisionSource {
    public static final String FIELD="ownerRevision";
    private OwnerRevisionSource(){}
    private static SecurityException changed(){return new SecurityException("所选的已存稿或原任务已变化，没有改用最新稿或工程文件");}
    public static JSONObject pin(JSONObject value)throws Exception{
        if(value==null||value.length()!=4)throw changed();
        JSONObject out=new JSONObject();
        for(String k:new String[]{"taskId","draftId","revisionId","sha256"}){
            Object raw=value.opt(k);
            if(!(raw instanceof String s)||!(k.equals("taskId")?s.matches("[A-Za-z0-9_-]{1,80}"):s.matches("[a-f0-9]{64}")))throw changed();
            out.put(k,s);
        }
        if(out.getString("draftId").equals(out.getString("revisionId")))throw changed(); // only an owner-saved text
        return out;
    }
    public static JSONObject metadata(JSONObject row)throws Exception{
        JSONObject ref=new JSONObject();for(String k:new String[]{"taskId","draftId","revisionId","sha256"})ref.put(k,row.get(k));
        int ordinal=row.getInt("ordinal"),bytes=row.getInt("bytes");String path=row.getString("path");
        if(ordinal<1||ordinal>8||bytes<0||bytes>24576||!FixedProjectDraftSource.eligible(path))throw changed();
        return new JSONObject().put(FIELD,pin(ref)).put("ordinal",ordinal).put("bytes",bytes).put("path",path);
    }
    /** In-memory one-file view of SQLite-held bytes; never calls LocalModule, reads a directory, or creates a checkout. */
    public static JSONObject snapshot(JSONObject row,String project,String checkout)throws Exception{
        JSONObject info=metadata(row),ref=info.getJSONObject(FIELD);
        JSONObject file=new JSONObject().put("path",info.getString("path")).put("sha256",ref.getString("sha256")).put("bytes",info.getInt("bytes")).put("content",row.getString("text"));
        JSONObject raw=new JSONObject().put(FIELD,ref).put("checkout",new JSONObject().put("id",ProjectBuildPlan.identity(checkout)).put("projectId",ProjectBuildPlan.identity(project))).put("files",new JSONArray().put(file));
        requireSnapshot(raw,ref);return raw;
    }
    public static void requireSnapshot(JSONObject snapshot,JSONObject expected)throws Exception{
        JSONObject ref=pin(expected);if(!ProjectSources.canonical(ref).equals(ProjectSources.canonical(pin(snapshot.optJSONObject(FIELD)))))throw changed();
        JSONArray files=snapshot.getJSONArray("files");if(files.length()!=1)throw changed();
        var file=files.getJSONObject(0);Object value=file.opt("content");
        if(!(value instanceof String content))throw changed();byte[] encoded=ProjectEditDraft.bytes(content,24576);
        if(file.optBoolean("binary")||!FixedProjectDraftSource.eligible(file.getString("path"))||!ref.getString("sha256").equals(file.opt("sha256"))||
           !ref.getString("sha256").equals(ProjectSources.sha256(encoded))||file.getInt("bytes")!=encoded.length)throw changed();
    }
    public static String digest(String path,String sha,int bytes)throws Exception{
        return ProjectSources.manifestDigest(new JSONArray().put(new JSONObject().put("path",path).put("sha256",sha).put("bytes",bytes)));
    }
}
