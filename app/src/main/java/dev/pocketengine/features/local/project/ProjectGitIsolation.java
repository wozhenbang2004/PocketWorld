package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Set;
import org.json.JSONObject;

/** Native provenance for an independently owned common repository, not access to the user's common dir.
 * Only LocalModule's original owner Git operation allocates it. No automatic merge, retry or cleanup. */
public final class ProjectGitIsolation {
    public static final String FORMAT="pocketworld.private-git-worktree.v1";
    public static final String OWNER_FILE="git-isolation-owner.json";
    public static final String ENV="POCKETWORLD_PRIVATE_GIT_POOL",GUEST="/pocketworld-private-git";
    private static final Set<String> KEYS=Set.of("format","targetCheckoutId","sourceCheckoutId","projectId","baseCommit","branch","ownerTaskId","ownerRunId","ownerOperationId","ownerEpoch","originPlanDigest","originSourceDigest");
    private ProjectGitIsolation(){}
    public static String branch(String value)throws Exception {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,79}")||value.contains("..")||value.contains("@{")||value.endsWith("/")||value.contains("//"))throw new IllegalArgumentException("请输入普通本地分支名，不使用选项、引用表达式或路径跳转");
        for(String part:value.split("/"))if(part.startsWith(".")||part.endsWith(".lock")||part.endsWith("."))throw new IllegalArgumentException("分支名包含不支持的Git引用段");return value;
    }
    public static String commit(String value)throws Exception {if(value==null||!value.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})"))throw new IllegalArgumentException("请选择本仓库中完整提交ID，不接受任意revision表达式");return value;}
    public static JSONObject ownership(JSONObject raw)throws Exception {
        JSONObject result=ProjectSources.copy(raw);java.util.Set<String> keys=new java.util.HashSet<>();result.keys().forEachRemaining(keys::add);
        if(!KEYS.equals(keys)||!FORMAT.equals(result.optString("format")))throw new SecurityException("原隔离工作副本归属合同不符");
        for(String key:new String[]{"targetCheckoutId","sourceCheckoutId","projectId","ownerTaskId","ownerRunId","ownerOperationId"})ProjectBuildPlan.identity(result.getString(key));
        commit(result.getString("baseCommit"));branch(result.getString("branch"));if(result.getInt("ownerEpoch")<1||!result.getString("originPlanDigest").matches("[a-f0-9]{64}")||!result.getString("originSourceDigest").matches("[a-f0-9]{64}"))throw new SecurityException("原隔离创建缺少真实操作/版本");
        if(result.getString("targetCheckoutId").equals(result.getString("sourceCheckoutId")))throw new SecurityException("隔离副本不能覆盖原副本");return result;
    }
    public static JSONObject owner(JSONObject exact,JSONObject binding)throws Exception {
        JSONObject request=ProjectGit.verifiedRequest(exact.getJSONObject("payload").getJSONObject("gitRequest"));if(!"isolate".equals(request.getString("action")))throw new SecurityException("不是原隔离创建操作");
        return ownership(new JSONObject().put("format",FORMAT).put("targetCheckoutId",request.getString("targetCheckoutId")).put("sourceCheckoutId",exact.getString("checkoutId")).put("projectId",exact.getString("projectId")).put("baseCommit",request.getString("baseCommit")).put("branch",request.getString("branch")).put("ownerTaskId",binding.getString("taskId")).put("ownerRunId",binding.getString("runId")).put("ownerOperationId",binding.getString("operationId")).put("ownerEpoch",binding.getInt("coordinatorEpoch")).put("originPlanDigest",exact.getString("planDigest")).put("originSourceDigest",exact.getString("sourceDigest")));
    }
    public static File pool(File checkouts,JSONObject owner)throws Exception {
        ownership(owner);File directory=new File(checkouts,owner.getString("targetCheckoutId")).getAbsoluteFile();
        if(!directory.equals(directory.getCanonicalFile())||Files.isSymbolicLink(checkouts.toPath())||Files.isSymbolicLink(directory.toPath()))throw new SecurityException("隔离池路径不是原生私有目录");return directory;
    }
    /** Called after original approval/admission. Existing or partial operations are never overwritten. */
    public static RuntimeWorkspace allocate(File checkouts,File sourceRoot,JSONObject exact,JSONObject binding)throws Exception {
        JSONObject owner=owner(exact,binding);File directory=pool(checkouts,owner);File[] saved=checkouts.listFiles();if(saved==null||saved.length>=40)throw new IllegalStateException("本地副本池已满或不可读；失败现场没有自动删除");
        Files.createDirectory(directory.toPath());JsonFiles.write(new File(directory,OWNER_FILE),owner);
        return workspace(sourceRoot,exact.getString("projectId"),exact.getString("checkoutId"),directory,owner,true);
    }
    public static RuntimeWorkspace workspace(File active,String project,String checkout,File pool,JSONObject owner,boolean creation)throws Exception {
        JSONObject verified=ownership(owner);requirePool(pool,verified);
        if(!project.equals(verified.getString("projectId"))||!checkout.equals(verified.getString(creation?"sourceCheckoutId":"targetCheckoutId")))throw new SecurityException("隔离池不是此原项目副本");
        if(!creation&&!active.getCanonicalFile().equals(new File(pool,"files").getCanonicalFile()))throw new SecurityException("隔离活动目录不是原工作树");
        return RuntimeWorkspace.privateGitForHost(active,project,checkout,pool,verified,creation);
    }
    public static void requirePool(File directory,JSONObject owner)throws Exception {
        File root=directory.getAbsoluteFile();if(!root.equals(root.getCanonicalFile())||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root.toPath())||!root.getName().equals(owner.getString("targetCheckoutId")))throw new SecurityException("原隔离池目录已变化");
        File marker=new File(root,OWNER_FILE);if(!Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(marker.toPath())||marker.length()>8192||!ProjectSources.canonical(ownership(JsonFiles.read(marker))).equals(ProjectSources.canonical(owner)))throw new SecurityException("原隔离池归属事实已变化");
    }
    public static void requireReadyFiles(File pool,JSONObject owner)throws Exception {
        requirePool(pool,owner);File files=new File(pool,"files"),common=new File(pool,"common"),admin=new File(common,"worktrees/files");
        for(File directory:new File[]{files,common,new File(common,"worktrees"),admin})if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile())||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory.toPath()))throw new SecurityException("原隔离Git目录已变化");
        String actual=smallText(new File(files,".git"));String host="gitdir: "+admin.getCanonicalPath()+"\n",guest="gitdir: "+GUEST+"/common/worktrees/files\n";
        if(!actual.equals(host)&&!actual.equals(guest)||!smallText(new File(admin,"commondir")).equals("../..\n")||!smallText(new File(admin,"HEAD")).equals("ref: refs/heads/"+owner.getString("branch")+"\n"))throw new SecurityException("原隔离gitfile/commondir/分支定位已变化");
        String back=smallText(new File(admin,"gitdir"));if(!back.equals(new File(files,".git").getCanonicalPath()+"\n")&&!back.equals(GUEST+"/files/.git\n"))throw new SecurityException("原隔离Git回指不符");
    }
    public static String sourceStamp(JSONObject facts)throws Exception {java.util.TreeMap<String,JSONObject> rows=new java.util.TreeMap<>();org.json.JSONArray files=facts.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);rows.put(row.getString("path"),row);}StringBuilder value=new StringBuilder();for(var entry:rows.entrySet()){JSONObject row=entry.getValue();String path=entry.getKey();value.append(path.getBytes(StandardCharsets.UTF_8).length).append(':').append(path).append(':').append(row.getString("sha256")).append(':').append(row.getLong("bytes")).append('\n');}return ProjectSources.sha256(value.toString().getBytes(StandardCharsets.UTF_8));}
    private static String smallText(File file)throws Exception {if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||file.length()>8192)throw new SecurityException("原隔离Git定位文件不可读");return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
}
