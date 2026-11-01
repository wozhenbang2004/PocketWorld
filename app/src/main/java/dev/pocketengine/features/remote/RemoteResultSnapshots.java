package dev.pocketengine.features.remote;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable selected-byte receipts, distinct from driver diffs and current-workspace reads. */
public final class RemoteResultSnapshots {
    private RemoteResultSnapshots() { }
    /** Exact versions actually read by the native owner before requesting a capture. */
    public static JSONArray expectedFiles(JSONArray supplied,JSONArray requested)throws Exception {
        JSONArray selected=paths(requested),result=new JSONArray();long total=0;
        if(supplied==null||supplied.length()!=selected.length())throw new SecurityException("请重新读取每个所选文件的实际版本");
        for(int i=0;i<supplied.length();i++){
            JSONObject file=supplied.getJSONObject(i);
            if(file.length()!=4||!file.has("path")||!file.has("bytes")||!file.has("sha256")||!file.has("fileVersion"))throw new SecurityException("已阅文件范围含无关字段");
            Object raw=file.opt("bytes");long bytes=file.getLong("bytes");
            if(!(raw instanceof Number)||raw instanceof Double||raw instanceof Float||bytes<0||bytes>262144||!selected.getString(i).equals(file.getString("path"))||!file.getString("sha256").matches("[a-f0-9]{64}")||!file.getString("fileVersion").matches("[a-f0-9]{64}"))throw new SecurityException("已阅文件路径、字节或版本不完整");
            total=Math.addExact(total,bytes);result.put(new JSONObject(file.toString()));
        }
        if(total>524288)throw new SecurityException("所选文件超过512KiB");
        return result;
    }
    public static void requireExpected(JSONObject snapshot,JSONArray expected)throws Exception {
        if(expected==null)return;JSONArray actual=snapshot.getJSONArray("files");
        if(actual.length()!=expected.length())throw new SecurityException("快照改变了已阅文件范围");
        for(int i=0;i<actual.length();i++)for(String key:new String[]{"path","bytes","sha256","fileVersion"})
            if(key.equals("bytes")?actual.getJSONObject(i).getLong(key)!=expected.getJSONObject(i).getLong(key):!actual.getJSONObject(i).getString(key).equals(expected.getJSONObject(i).getString(key)))throw new SecurityException("文件在审阅后已变化，未接受旧版本快照");
    }
    public static JSONArray paths(JSONArray supplied)throws Exception {
        if(supplied==null||supplied.length()<1||supplied.length()>8)throw new IllegalArgumentException("请明确选择1至8个文件，不扫描目录");
        JSONArray result=new JSONArray();Set<String> unique=new HashSet<>();for(int i=0;i<supplied.length();i++){
            if(!(supplied.opt(i) instanceof String))throw new IllegalArgumentException("快照路径需为明确文件名");String path=VersionedWorkspace.path(supplied.getString(i));
            String lower=path.toLowerCase(java.util.Locale.ROOT);for(String part:lower.split("/"))if(Set.of(".git",".ssh",".aws",".gnupg",".codex",".claude","node_modules",".pocketworld-inputs",".pocket-assignment.json").contains(part)||part.startsWith(".env"))throw new SecurityException("凭据、私密运行目录与依赖目录不允许作为成果快照");
            String name=lower.substring(lower.lastIndexOf('/')+1);for(String marker:new String[]{"secret","credential","private_key","id_rsa","id_ed25519"})if(name.contains(marker))throw new SecurityException("敏感文件不允许作为成果快照");
            if(!unique.add(path))throw new IllegalArgumentException("快照文件路径重复");result.put(path);
        }return result;
    }
    public static JSONObject metadata(JSONObject actual,JSONObject source,String operation,JSONArray requested)throws Exception {
        JSONArray selected=paths(requested);for(String key:new String[]{"jobId","jobOperationId","environmentId","worker"})if(!source.getString(key).equals(actual.optString(key)))throw new SecurityException("快照回执不属于原job/环境/执行器");
        if(!operation.equals(actual.optString("operationId"))||!actual.optString("snapshotId").matches("[a-f0-9-]{36}")||!actual.optString("snapshotVersion").matches("[a-f0-9]{64}")||!actual.optString("status").equals("completed")||!actual.optBoolean("immutableArtifact")||actual.opt("authorshipVerified")!=Boolean.FALSE||!actual.optString("captureSource").equals("selected_current_workspace_after_settled_original_job"))throw new SecurityException("尚无原操作的不可变成果快照回执");
        if(!actual.optString("workspaceIdentity").matches("[a-f0-9]{64}")||source.has("workspaceIdentity")&&!source.getString("workspaceIdentity").equals(actual.optString("workspaceIdentity"))||!actual.optString("consistency").equals("two_read_content_stat_fence")||!Boolean.FALSE.equals(actual.opt("atomicWorkspaceSnapshot")))throw new SecurityException("快照缺少原物理工作区身份/文件版本检查证据，不能当作原成果");
        for(String key:new String[]{"threadId","turnId"})if(source.has(key)&&!source.getString(key).equals(actual.optString(key)))throw new SecurityException("快照回执改变了原session/turn");
        JSONArray files=actual.getJSONArray("files"),safe=new JSONArray();if(files.length()!=selected.length())throw new SecurityException("快照文件范围与本次明确选择不一致");long total=0;
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);long size=file.getLong("bytes");if(!selected.getString(i).equals(file.optString("path"))||size<0||size>262144||!file.optString("sha256").matches("[a-f0-9]{64}")||!file.optString("fileVersion").matches("[a-f0-9]{64}"))throw new SecurityException("快照文件路径/大小/hash/版本不完整");total=Math.addExact(total,size);safe.put(new JSONObject().put("path",file.getString("path")).put("bytes",size).put("sha256",file.getString("sha256")).put("fileVersion",file.getString("fileVersion")));}
        if(total>524288||total!=actual.getLong("bytes"))throw new SecurityException("快照字节总量不一致或超过512KiB");
        JSONObject result=new JSONObject().put("source",new JSONObject(source.toString())).put("files",safe).put("bytes",total).put("immutableArtifact",true).put("authorshipVerified",false).put("captureSource",actual.getString("captureSource"));
        for(String key:new String[]{"snapshotId","snapshotVersion","operationId","jobId","jobOperationId","environmentId","worker","capturedAt","status","threadId","sessionId","turnId","workspaceIdentity","consistency","atomicWorkspaceSnapshot"})if(actual.has(key))result.put(key,actual.get(key));return result;
    }
    public static JSONObject file(JSONObject actual,JSONObject nativeSnapshot,String path)throws Exception {
        JSONObject source=nativeSnapshot.getJSONObject("source"),manifest=null;JSONArray files=nativeSnapshot.getJSONArray("files");for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path")))manifest=files.getJSONObject(i);
        if(manifest==null)throw new SecurityException("此文件不在原生快照明确选择范围内");
        for(String key:new String[]{"snapshotId","snapshotVersion","operationId","jobId","jobOperationId","environmentId","worker","workspaceIdentity","consistency"})if(!nativeSnapshot.getString(key).equals(actual.optString(key)))throw new SecurityException("读取回执不是本次原快照/版本");
        if(manifest.getLong("bytes")!=actual.getLong("bytes"))throw new SecurityException("不可变文件大小回执与原快照manifest不一致");
        for(String key:new String[]{"sha256","fileVersion"})if(!manifest.getString(key).equals(actual.optString(key)))throw new SecurityException("不可变文件内容回执与原快照manifest不一致");
        if(!actual.optBoolean("immutableArtifact")||actual.opt("authorshipVerified")!=Boolean.FALSE||!nativeSnapshot.getString("captureSource").equals(actual.optString("captureSource")))throw new SecurityException("读取回执未证明原冻结来源");
        JSONObject result=RemoteResultSnapshot.artifact(actual,source,path).put("kind","immutable_result_snapshot_bytes").put("immutableArtifact",true).put("authorshipVerified",false).put("captureSource",nativeSnapshot.getString("captureSource"));
        for(String key:new String[]{"snapshotId","snapshotVersion","operationId","fileVersion","capturedAt","workspaceIdentity","consistency","atomicWorkspaceSnapshot"})if(actual.has(key))result.put(key,actual.get(key));return result;
    }
}
