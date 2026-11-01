package dev.pocketengine.features.remote;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordinary native-selected public project versions, not team scope or implicit attachment access. */
public final class RemoteProjectHistory {
    public static final String PROTOCOL="ordinary_project_history_v1";
    private RemoteProjectHistory() { }
    public static JSONObject selection(String root)throws Exception {
        if(root==null||root.length()>240||root.getBytes(StandardCharsets.UTF_8).length>240)throw new SecurityException("请选择所配置工作区内的确切项目目录");
        if(!root.isEmpty())safePath(root);
        return new JSONObject().put("protocol",PROTOCOL).put("projectRoot",root).put("publicSourceReadAcknowledged",true).put("externalChangesMayBeUnattributed",true);
    }
    public static String safePath(String path)throws Exception {
        String checked=VersionedWorkspace.path(path),lower=checked.toLowerCase(java.util.Locale.ROOT);
        for(int n=0;n<checked.length();n++)if(Character.isSurrogate(checked.charAt(n))&&(Character.isLowSurrogate(checked.charAt(n))||n+1>=checked.length()||!Character.isLowSurrogate(checked.charAt(++n))))throw new SecurityException("项目路径含无效Unicode");
        for(String part:lower.split("/"))if(Set.of(".git",".ssh",".aws",".azure",".gnupg",".codex",".claude",".config",".npm",".cache","node_modules",".venv","venv","__pycache__",".gradle",".pocketworld-inputs","build","dist","target").contains(part)||part.startsWith(".env")||part.startsWith(".pocket-"))throw new SecurityException("运行私密目录、凭据和依赖/构建缓存不在项目版本范围内");
        String name=lower.substring(lower.lastIndexOf('/')+1);for(String marker:new String[]{"secret","credential","private_key","id_rsa","id_ed25519"})if(name.contains(marker))throw new SecurityException("敏感文件不能作为项目公开源码版本");
        if(name.matches(".*\\.(pem|key|p12|pfx|jks|keystore|ppk|gpg)")||Set.of(".netrc",".npmrc",".pypirc","accounts.json","token.json","tokens.json").contains(name))throw new SecurityException("私密配置不在项目版本范围内");return checked;
    }
    public static void requireCapability(JSONObject env)throws Exception {
        JSONObject cap=env.optJSONObject("projectHistoryCapabilities");
        if(cap==null||!PROTOCOL.equals(cap.optString("protocol"))||cap.opt("supported")!=Boolean.TRUE||!cap.optBoolean("prelaunchBaseline")||!cap.optBoolean("privateFilesExcluded")||!cap.optBoolean("binaryVersions")||cap.optInt("maxFiles")<2000||cap.optLong("maxFileBytes")<262144||cap.optLong("maxTotalBytes")<8388608)throw new SecurityException("此执行端未支持原任务前置项目版本；不将任务结束后的快照伪装成基线，请保留普通任务或更新执行端");
    }
    public static JSONObject page(JSONObject actual,JSONObject source)throws Exception {
        original(actual,source);JSONObject history=actual.getJSONObject("history");if(!PROTOCOL.equals(history.optString("protocol"))||history.opt("authorshipVerified")!=Boolean.FALSE||!history.optBoolean("sameUserExternalWritersMayExist"))throw new SecurityException("项目版本缺少明确来源/外部修改边界");
        snapshot(history.getJSONObject("baseline"),source);if(history.has("after"))snapshot(history.getJSONObject("after"),source);
        JSONArray files=actual.getJSONArray("files");int offset=actual.getInt("offset"),next=actual.getInt("nextOffset"),total=actual.getInt("totalFiles");if(!actual.optString("pageVersion").matches("[a-f0-9]{64}")||files.length()>100||offset<0||next!=Math.min(total,offset+files.length())||total<0||total>4000||offset>total||next>total||actual.optBoolean("hasMore")!=(next<total)||actual.optBoolean("executionAuthorized")||actual.optBoolean("fileRestoreAuthorized"))throw new SecurityException("项目版本分页范围不一致");
        for(int n=0;n<files.length();n++){JSONObject file=files.getJSONObject(n);safePath(file.getString("path"));if(file.opt("authorshipVerified")!=Boolean.FALSE||!file.optString("change").matches("added|deleted|modified|unchanged|baseline_only"))throw new SecurityException("不能将工作区变化冒认为Agent独占创作");for(String key:new String[]{"before","after"})if(file.optJSONObject(key)!=null){metadata(file.getJSONObject(key),false);if(!file.getString("path").equals(file.getJSONObject(key).getString("path")))throw new SecurityException("项目行与旧/新文件路径不同");}}
        return new JSONObject(actual.toString());
    }
    private static void snapshot(JSONObject value,JSONObject source)throws Exception {
        original(value,source);for(String key:new String[]{"environmentId","worker","workspaceIdentity"})if(!source.get(key).equals(value.opt(key)))throw new SecurityException("历史项目版本改变了原环境/物理工作区");if(!value.optString("snapshotId").matches("[a-f0-9-]{36}")||!value.optString("snapshotVersion").matches("[a-f0-9]{64}")||!value.optBoolean("immutableArtifact")||value.opt("authorshipVerified")!=Boolean.FALSE||value.opt("atomicWorkspaceSnapshot")!=Boolean.FALSE||!value.optString("projectPhase").matches("before_worker|after_worker")||!value.optString("captureSource").equals("owner_selected_public_project_"+value.getString("projectPhase")))throw new SecurityException("原项目不可变版本回执不完整");
    }
    public static void original(JSONObject actual,JSONObject source)throws Exception {for(String key:new String[]{"jobId","jobOperationId"})if(!source.getString(key).equals(actual.optString(key)))throw new SecurityException("项目版本回执不属于原job/operation");}
    public static JSONObject metadata(JSONObject file,boolean absent)throws Exception {
        String path=safePath(file.getString("path")),sha=file.getString("sha256"),version=file.getString("fileVersion");long bytes=file.getLong("bytes");Object number=file.opt("bytes");if(!(number instanceof Integer||number instanceof Long)||bytes<0||bytes>262144||!(sha.matches("[a-f0-9]{64}")&&version.matches("[a-f0-9]{64}")||absent&&sha.equals("absent")&&version.equals("absent")&&bytes==0))throw new SecurityException("原文件实际字节/hash/版本无效");
        JSONObject result=new JSONObject().put("path",path).put("bytes",bytes).put("sha256",sha).put("fileVersion",version);if(file.has("executable"))result.put("executable",file.getBoolean("executable"));return result;
    }
    public static JSONObject exactFile(JSONObject file,JSONObject source,String snapshot,String version,String path)throws Exception {
        original(file,source);if(!snapshot.equals(file.optString("snapshotId"))||!version.equals(file.optString("snapshotVersion"))||!path.equals(file.optString("path")))throw new SecurityException("所选旧文件不是原项目版本");
        JSONObject metadata=metadata(file,false);byte[] bytes=java.util.Base64.getDecoder().decode(file.getString("base64"));try{if(bytes.length!=metadata.getLong("bytes")||!dev.pocketengine.features.local.project.ProjectSources.sha256(bytes).equals(metadata.getString("sha256")))throw new SecurityException("原冻结文件实际字节校验失败");}finally{java.util.Arrays.fill(bytes,(byte)0);}return metadata;
    }
}
