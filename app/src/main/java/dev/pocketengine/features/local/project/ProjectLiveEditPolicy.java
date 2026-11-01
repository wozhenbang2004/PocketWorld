package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** A reviewed dev-watch source set. This value alone never grants a write or launches a process. */
public final class ProjectLiveEditPolicy {
    public static final String FORMAT="pocketworld.dev-watch-edit.v1";
    public static final int MAX_FILES=128,MAX_EDIT_BYTES=256*1024;
    private static final Set<String> EXTENSIONS=Set.of("js","mjs","jsx","ts","tsx","css","html","htm","json","svg","txt");
    private static final Set<String> CONTROL_NAMES=Set.of("package.json","package-lock.json","npm-shrinkwrap.json","pnpm-lock.yaml","yarn.lock","bun.lock","bun.lockb","requirements.txt","pyproject.toml","pipfile","pipfile.lock","poetry.lock","tsconfig.json","jsconfig.json");
    private ProjectLiveEditPolicy(){}

    /** Flat native plan fields fit the existing six-level tool argument contract.
     * Do not stringify a nested JSON policy or relax the shared plugin validator. */
    public static void putOnPayload(JSONObject payload,JSONObject original)throws Exception {
        JSONObject policy=verified(original);JSONArray paths=new JSONArray(),hashes=new JSONArray(),sizes=new JSONArray(),files=policy.getJSONArray("editableFiles");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);paths.put(file.getString("path"));hashes.put(file.getString("initialSha256"));sizes.put(file.getLong("initialBytes"));}
        payload.put("devWatch",true).put("devWatchProtocol","pocketworld.vite-dev.v1").put("liveEditPaths",paths).put("liveEditInitialSha256",hashes).put("liveEditInitialBytes",sizes).put("liveEditProtectedSourceDigest",policy.getString("protectedSourceDigest")).put("liveEditPolicyDigest",policy.getString("policyDigest"));
    }
    /** Reconstruct the exact reviewed value; this is still data, never a write lease or approval. */
    public static JSONObject fromExact(JSONObject exact)throws Exception {
        JSONObject payload=exact.getJSONObject("payload");if(!payload.has("devWatch"))return null;
        if(!Boolean.TRUE.equals(payload.opt("devWatch"))||!"pocketworld.vite-dev.v1".equals(payload.optString("devWatchProtocol"))||!"managed_process_v1".equals(exact.optString("runtimeId"))||!"project.runtime.run".equals(exact.optString("command"))||!"owned_service".equals(payload.optString("runMode"))||!"node".equals(payload.optString("executable")))throw new SecurityException("不是原本机Node dev-watch方案");
        JSONArray argv=payload.getJSONArray("argv");if(argv.length()!=1||!"tools/dev.mjs".equals(argv.getString(0)))throw new SecurityException("热改不可更换启动工具或参数");
        JSONArray paths=payload.getJSONArray("liveEditPaths"),hashes=payload.getJSONArray("liveEditInitialSha256"),sizes=payload.getJSONArray("liveEditInitialBytes"),files=new JSONArray();
        if(paths.length()<1||paths.length()>MAX_FILES||hashes.length()!=paths.length()||sizes.length()!=paths.length())throw new SecurityException("热改原路径/SHA/大小清单不完整或超限");
        for(int i=0;i<paths.length();i++){if(!(paths.opt(i) instanceof String)||!(hashes.opt(i) instanceof String)||!(sizes.opt(i) instanceof Integer||sizes.opt(i) instanceof Long))throw new SecurityException("热改清单必须使用真实字符串与整数");files.put(new JSONObject().put("path",paths.getString(i)).put("initialSha256",hashes.getString(i)).put("initialBytes",sizes.getLong(i)));}
        return verified(new JSONObject().put("format",FORMAT).put("projectId",exact.getString("projectId")).put("checkoutId",exact.getString("checkoutId")).put("initialSourceDigest",exact.getString("sourceDigest")).put("editableFiles",files).put("protectedSourceDigest",payload.getString("liveEditProtectedSourceDigest")).put("subsequentSourceExecution",true).put("configurationChangesAllowed",false).put("createsFiles",false).put("lifetime","same_original_host_run").put("policyDigest",payload.getString("liveEditPolicyDigest")));
    }

    public static JSONObject create(JSONObject actualSnapshot,JSONArray selectedPaths)throws Exception {
        JSONObject source=ProjectSources.describe(actualSnapshot);Map<String,byte[]> files=ProjectSources.byteFiles(actualSnapshot);TreeSet<String> selected=paths(selectedPaths);JSONArray mutable=new JSONArray();
        for(String path:selected){byte[] bytes=files.get(path);if(bytes==null)throw new IllegalArgumentException("热改批准只能包含已存在文件："+path);text(bytes);mutable.put(new JSONObject().put("path",path).put("initialSha256",ProjectSources.sha256(bytes)).put("initialBytes",bytes.length));}
        JSONObject policy=new JSONObject().put("format",FORMAT).put("projectId",source.getString("projectId")).put("checkoutId",source.getString("checkoutId")).put("initialSourceDigest",source.getString("sourceDigest"))
            .put("editableFiles",mutable).put("protectedSourceDigest",protectedDigest(source.getJSONArray("files"),selected)).put("subsequentSourceExecution",true).put("configurationChangesAllowed",false).put("createsFiles",false).put("lifetime","same_original_host_run");
        policy.put("policyDigest",ProjectSources.digest(policy));return policy;
    }
    public static JSONObject verified(JSONObject original)throws Exception {
        JSONObject policy=ProjectSources.copy(original);keys(policy,Set.of("format","projectId","checkoutId","initialSourceDigest","editableFiles","protectedSourceDigest","subsequentSourceExecution","configurationChangesAllowed","createsFiles","lifetime","policyDigest"));String digest=policy.getString("policyDigest");policy.remove("policyDigest");
        if(!digest.matches("[a-f0-9]{64}")||!digest.equals(ProjectSources.digest(policy))||!FORMAT.equals(policy.getString("format"))||!"same_original_host_run".equals(policy.getString("lifetime"))||!Boolean.TRUE.equals(policy.opt("subsequentSourceExecution"))||!Boolean.FALSE.equals(policy.opt("configurationChangesAllowed"))||!Boolean.FALSE.equals(policy.opt("createsFiles")))throw new SecurityException("受控热改方案已变化或范围不支持");
        ProjectBuildPlan.identity(policy.getString("projectId"));ProjectBuildPlan.identity(policy.getString("checkoutId"));for(String name:new String[]{"initialSourceDigest","protectedSourceDigest"})if(!policy.getString(name).matches("[a-f0-9]{64}"))throw new SecurityException("热改源码版本无效");
        editable(policy);policy.put("policyDigest",digest);return policy;
    }
    /** SQL authority can verify original approved manifest metadata without calling runtime/LocalModule under its lock. */
    public static void requireLaunchManifest(JSONObject original,JSONArray manifest,String project,String checkout,String sourceDigest)throws Exception {
        JSONObject policy=verified(original);if(!policy.getString("projectId").equals(project)||!policy.getString("checkoutId").equals(checkout)||!policy.getString("initialSourceDigest").equals(sourceDigest)||!sourceDigest.equals(ProjectSources.manifestDigest(manifest)))throw new SecurityException("原热改批准与启动清单不一致");
        Map<String,JSONObject> files=new TreeMap<>();for(int i=0;i<manifest.length();i++){JSONObject file=manifest.getJSONObject(i);files.put(file.getString("path"),file);}TreeSet<String> selected=editable(policy);JSONArray mutable=policy.getJSONArray("editableFiles");for(int i=0;i<mutable.length();i++){JSONObject row=mutable.getJSONObject(i),file=files.get(row.getString("path"));if(file==null||!row.getString("initialSha256").equals(file.getString("sha256"))||row.getLong("initialBytes")!=file.getLong("bytes"))throw new SecurityException("热改批准包含非原始启动文件");}
        if(!policy.getString("protectedSourceDigest").equals(protectedDigest(manifest,selected)))throw new SecurityException("受保护启动源码清单不一致");
    }
    public static void requirePlannedEdit(JSONObject original,JSONArray currentManifest,String path)throws Exception {
        JSONObject policy=verified(original);TreeSet<String> selected=editable(policy);if(!selected.contains(VersionedWorkspace.path(path))||!policy.getString("protectedSourceDigest").equals(protectedDigest(currentManifest,selected)))throw new SecurityException("编辑范围或保护源码不是原启动批准的范围");TreeSet<String> present=new TreeSet<>();for(int i=0;i<currentManifest.length();i++)present.add(currentManifest.getJSONObject(i).getString("path"));if(!present.containsAll(selected))throw new SecurityException("原热改文件清单已删除成员");
    }
    /** Native dispatch checks the policy against the actual launch snapshot, not a claimed accepted flag. */
    public static void requireStartup(JSONObject policy,JSONObject actualSnapshot)throws Exception {
        JSONObject checked=verified(policy),source=ProjectSources.describe(actualSnapshot);identity(checked,source);
        if(!checked.getString("initialSourceDigest").equals(source.getString("sourceDigest")))throw new SecurityException("热改批准不是实际启动源码版本");Map<String,byte[]> files=ProjectSources.byteFiles(actualSnapshot);TreeSet<String> selected=editable(checked);
        JSONArray rows=checked.getJSONArray("editableFiles");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);byte[] bytes=files.get(row.getString("path"));if(bytes==null||bytes.length!=row.getInt("initialBytes")||!ProjectSources.sha256(bytes).equals(row.getString("initialSha256")))throw new SecurityException("热改文件不是实际启动文件");text(bytes);}
        if(!checked.getString("protectedSourceDigest").equals(protectedDigest(source.getJSONArray("files"),selected)))throw new SecurityException("受保护启动配置或工具脚本清单不同");
    }
    /** Recheck all noneditable source inputs and the exact approved members before each native CAS. */
    public static void requireCurrent(JSONObject policy,JSONObject actualSnapshot)throws Exception {
        JSONObject checked=verified(policy),source=ProjectSources.describe(actualSnapshot);identity(checked,source);TreeSet<String> selected=editable(checked);Map<String,byte[]> files=ProjectSources.byteFiles(actualSnapshot);
        if(!checked.getString("protectedSourceDigest").equals(protectedDigest(source.getJSONArray("files"),selected)))throw new SecurityException("配置、锁文件、工具脚本或其他未批准源码已变化，不能沿旧服务热改");
        for(String path:selected){byte[] value=files.get(path);if(value==null)throw new SecurityException("原批准文件已移除，不能新增替代文件："+path);text(value);}
    }
    public static void requireEdit(JSONObject policy,JSONObject actualSnapshot,String path,String expectedSha,String replacement)throws Exception {
        JSONObject checked=verified(policy);if(!editable(checked).contains(VersionedWorkspace.path(path)))throw new SecurityException("此文件不在原服务启动批准的热改范围");requireCurrent(checked,actualSnapshot);byte[] current=ProjectSources.byteFiles(actualSnapshot).get(path);
        if(expectedSha==null||!expectedSha.matches("[a-f0-9]{64}")||!expectedSha.equals(ProjectSources.sha256(current)))throw new SecurityException("文件已变化，请读取原文件最新SHA后重新决定");text(encode(replacement));
    }
    public static boolean allowedPath(String value) {
        try{String path=VersionedWorkspace.path(value);StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(path));String[] parts=path.toLowerCase(java.util.Locale.ROOT).split("/");for(String part:parts)if(part.startsWith(".")||Set.of("node_modules","venv","__pycache__").contains(part))return false;
            if(Set.of("tools","scripts","bin","build","dist","out","target","coverage").contains(parts[0]))return false;String name=parts[parts.length-1];if(CONTROL_NAMES.contains(name)||name.endsWith(".lock")||name.matches("(?:vite|vitest|webpack|rollup|postcss|babel|eslint|prettier)\\.config\\..*")||name.startsWith("tsconfig.")||name.startsWith("jsconfig."))return false;int dot=name.lastIndexOf('.');return dot>=0&&EXTENSIONS.contains(name.substring(dot+1));
        }catch(Exception invalid){return false;}
    }
    public static JSONArray selectedPaths(JSONObject policy)throws Exception{return new JSONArray(editable(verified(policy)));}
    private static TreeSet<String> paths(JSONArray values)throws Exception {
        if(values==null||values.length()<1||values.length()>MAX_FILES)throw new IllegalArgumentException("请明确选择1至128个既有源码文件，不能整体放开工作区写入");TreeSet<String> paths=new TreeSet<>();for(int i=0;i<values.length();i++){Object raw=values.get(i);if(!(raw instanceof String)||!allowedPath((String)raw)||!paths.add((String)raw))throw new IllegalArgumentException("热改文件重复、越界或属于配置/锁/工具脚本："+String.valueOf(raw));}return paths;
    }
    private static TreeSet<String> editable(JSONObject policy)throws Exception {
        JSONArray rows=policy.getJSONArray("editableFiles"),paths=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);keys(row,Set.of("path","initialSha256","initialBytes"));if(!row.getString("initialSha256").matches("[a-f0-9]{64}")||!(row.opt("initialBytes") instanceof Integer||row.opt("initialBytes") instanceof Long)||row.getLong("initialBytes")<0||row.getLong("initialBytes")>MAX_EDIT_BYTES)throw new SecurityException("热改文件初始摘要或大小无效");paths.put(row.getString("path"));}return paths(paths);
    }
    private static String protectedDigest(JSONArray manifest,Set<String> selected)throws Exception {JSONArray protectedFiles=new JSONArray();for(int i=0;i<manifest.length();i++){JSONObject file=manifest.getJSONObject(i);if(!selected.contains(file.getString("path")))protectedFiles.put(file);}return ProjectSources.manifestDigest(protectedFiles);}
    private static void identity(JSONObject policy,JSONObject source)throws Exception {for(String key:new String[]{"projectId","checkoutId"})if(!policy.getString(key).equals(source.getString(key)))throw new SecurityException("热改不能替换原项目或副本");}
    private static byte[] encode(String text)throws Exception {if(text==null)throw new IllegalArgumentException("缺少热改文件正文");ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
    private static void text(byte[] bytes)throws Exception {if(bytes.length>MAX_EDIT_BYTES)throw new IllegalArgumentException("单个热改文件超过256KiB");String value=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();if(value.indexOf('\0')>=0)throw new IllegalArgumentException("热改仅支持无NUL的UTF-8源码");}
    private static void keys(JSONObject value,Set<String> allowed)throws Exception {var keys=value.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new SecurityException("热改方案有未支持字段，不能恢复授权");}
}
