package dev.pocketengine.features.local.project;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Typed local Git policy over the original immutable plan/runtime/operation contracts.
 * Git is not the Workspace version store. No clone/push, arbitrary shell or repository hook API. */
public final class ProjectGit {
    public static final String INTENT="git",OWNER_COMMAND="project.git.owner",FORMAT="pocketworld.project-git.v1";
    public static final int MAX_SELECTED=40,MAX_STATUS_PAGE=40,MAX_DIFF_PAGE_BYTES=16000,MAX_RESULT_BYTES=262144;
    private static final Set<String> ACTIONS=Set.of("status","history","diff","init","stage","unstage","resolve","commit","isolate");
    private static final Set<String> MUTATIONS=Set.of("init","stage","unstage","resolve","commit","isolate");
    private static final Set<String> PIN_KEYS=Set.of("head","headRef","headFileSha256","indexSha256","configSha256","mergeHeadSha256","worktreeSha256","statusSha256","snapshotDigest");
    private ProjectGit(){}
    public static boolean writes(String action){return MUTATIONS.contains(action);}
    public static boolean plan(JSONObject plan){return INTENT.equals(plan.optString("intent"));}
    public static boolean exact(JSONObject exact){JSONObject p=exact.optJSONObject("payload");return "project.runtime.run".equals(exact.optString("command"))&&p!=null&&Integer.valueOf(1).equals(p.opt("gitProtocolVersion"));}
    public static String action(JSONObject plan)throws Exception{return verifiedRequest(step(plan).getJSONObject("payload").getJSONObject("gitRequest")).getString("action");}
    public static JSONObject step(JSONObject plan)throws Exception {JSONObject verified=ProjectBuildPlan.verified(plan);if(!plan(verified))throw new SecurityException("不是固定本地Git方案");return verified.getJSONArray("steps").getJSONObject(0);}
    public static String path(String value)throws Exception {
        String path=VersionedWorkspace.path(value);StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(path));
        if(path.length()>240||path.split("/").length>40||!path.equals(path.trim())||path.chars().anyMatch(c->c<32)||path.indexOf('\\')>=0)throw new IllegalArgumentException("请选择普通UTF-8项目文件路径");
        for(String part:path.split("/"))if(part.equalsIgnoreCase(".git"))throw new SecurityException("Git元数据不能当作项目文件暂存或编辑");
        if(ProjectPackageArchive.excluded(path,false,false)!=null)throw new SecurityException("此Git入口不暂存或读取依赖缓存、生成目录、已知凭据与私人配置；请选择项目源码文件");return path;
    }
    private static String hash(JSONObject in,String key,boolean absent)throws Exception {String value=in.getString(key);if(!(value.matches("[a-f0-9]{64}")||absent&&value.equals("absent")))throw new SecurityException("Git审阅缺少真实摘要："+key);return value;}
    public static JSONObject pin(JSONObject input)throws Exception {
        if(!keys(input).equals(PIN_KEYS))throw new SecurityException("Git审阅必须保留同一HEAD/index/工作树完整定位");JSONObject result=new JSONObject(input.toString());String head=result.getString("head"),ref=result.getString("headRef");
        if(!head.equals("unborn")&&!head.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})"))throw new SecurityException("原Git HEAD身份无效");
        if(!ref.equals("detached")&&(!ref.matches("refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,180}")||ref.contains("..")||ref.contains("@{")||ref.endsWith("/")||ref.endsWith(".lock")))throw new SecurityException("只支持普通本地分支或明确detached状态");
        for(String key:PIN_KEYS)if(!key.equals("head")&&!key.equals("headRef"))hash(result,key,key.equals("indexSha256")||key.equals("mergeHeadSha256"));return result;
    }
    /** Normalized only from the explicitly advertised fixed Git fields, not arbitrary argv/env. */
    public static JSONObject request(JSONObject input,String gitSha)throws Exception {
        String action=input.getString("gitAction");if(!ACTIONS.contains(action)||!gitSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("本次Git动作或实际工具摘要无效");
        JSONObject r=new JSONObject().put("version",1).put("action",action).put("gitExecutableSha256",gitSha);
        if(input.has("gitPin"))r.put("expected",pin(input.getJSONObject("gitPin")));
        if(action.equals("init"))r.put("expectedWorktreeSha256",hash(input,"gitWorktreeSha256",false));
        if(action.equals("status")){r.put("offset",integer(input,"gitOffset",0,0,MAX_STATUS_PAGE*50)).put("limit",integer(input,"gitLimit",MAX_STATUS_PAGE,1,MAX_STATUS_PAGE));if(r.getInt("offset")>0&&!r.has("expected"))throw new SecurityException("下一页Git状态必须带原版本定位");}
        else if(action.equals("history")){if(!r.has("expected"))throw new SecurityException("提交历史必须保留原Git版本定位");r.put("offset",integer(input,"gitOffset",0,0,2000)).put("limit",integer(input,"gitLimit",10,1,10));}
        else if(action.equals("diff")){if(!r.has("expected"))throw new SecurityException("先读取原Git状态，再选择真实比较基线");String layer=input.getString("gitLayer");if(!Set.of("head_index","index_worktree").contains(layer))throw new IllegalArgumentException("请选择HEAD→暂存区或暂存区→工作树");r.put("path",path(input.getString("path"))).put("layer",layer).put("offset",integer(input,"gitOffset",0,0,4*1024*1024));}
        else if(action.equals("isolate")){
            if(!r.has("expected")||"unborn".equals(r.getJSONObject("expected").getString("head")))throw new SecurityException("隔离副本须来自本仓库已审提交，不猜未提交内容");
            r.put("baseCommit",ProjectGitIsolation.commit(input.getString("baseCommit"))).put("branch",ProjectGitIsolation.branch(input.getString("branch"))).put("targetCheckoutId",ProjectBuildPlan.identity(input.getString("targetCheckoutId")));
        }
        else if(!action.equals("init")){
            if(!r.has("expected"))throw new SecurityException("Git变更必须使用刚审阅的HEAD/index/工作树定位");JSONArray values=input.getJSONArray("gitPaths"),paths=new JSONArray();Set<String> seen=new HashSet<>();if(values.length()<1||values.length()>MAX_SELECTED)throw new IllegalArgumentException("请明确选择1至40个Git文件，不默认全部暂存");
            for(int i=0;i<values.length();i++){String p=path(values.getString(i));if(!seen.add(p))throw new IllegalArgumentException("Git文件选择重复");paths.put(p);}r.put("paths",paths);
            if(action.equals("commit")){String message=input.getString("commitMessage"),name=input.getString("authorName"),email=input.getString("authorEmail");strict(message);strict(name);strict(email);if(message.trim().isEmpty()||message.getBytes(StandardCharsets.UTF_8).length>4096||message.indexOf('\0')>=0||name.isEmpty()||name.length()>100||name.chars().anyMatch(c->c<32||c=='<'||c=='>')||!email.matches("[^\\s<>@]{1,100}@[^\\s<>@]{1,150}"))throw new IllegalArgumentException("请核对提交说明与本次明确作者姓名、邮箱；不要填令牌或密码");r.put("message",message).put("authorName",name).put("authorEmail",email).put("reviewedEntries",commitEntries(input.getJSONArray("gitEntries"),paths)).put("parents",parents(input.getJSONArray("gitParents"),r.getJSONObject("expected")));}
        }
        if(input.has("privateWorktree"))r.put("privateWorktree",ProjectGitIsolation.ownership(input.getJSONObject("privateWorktree")));
        return verifiedRequest(r);
    }
    public static JSONObject verifiedRequest(JSONObject raw)throws Exception {
        JSONObject r=new JSONObject(raw.toString());if(!Integer.valueOf(1).equals(r.opt("version"))||!ACTIONS.contains(r.optString("action"))||!r.optString("gitExecutableSha256").matches("[a-f0-9]{64}"))throw new SecurityException("固定Git请求版本或工具摘要无效");
        Set<String> expected=new HashSet<>(Set.of("version","action","gitExecutableSha256"));String a=r.getString("action");
        if(r.has("expected")){pin(r.getJSONObject("expected"));expected.add("expected");}
        if(a.equals("init")){expected.add("expectedWorktreeSha256");hash(r,"expectedWorktreeSha256",false);if(r.has("expected"))throw new SecurityException("初始化只接受原无Git状态的源码定位");}
        else if(a.equals("status")){expected.addAll(Set.of("offset","limit"));integer(r,"offset",0,0,2000);integer(r,"limit",MAX_STATUS_PAGE,1,MAX_STATUS_PAGE);if(r.getInt("offset")>0&&!r.has("expected"))throw new SecurityException("Git翻页不能丢失原版本");}
        else if(a.equals("history")){expected.addAll(Set.of("expected","offset","limit"));pin(r.getJSONObject("expected"));integer(r,"offset",0,0,2000);integer(r,"limit",10,1,10);}
        else if(a.equals("diff")){expected.addAll(Set.of("path","layer","offset","expected"));path(r.getString("path"));pin(r.getJSONObject("expected"));if(!Set.of("head_index","index_worktree").contains(r.getString("layer")))throw new SecurityException("Git diff比较层无效");integer(r,"offset",0,0,4*1024*1024);}
        else if(a.equals("isolate")){expected.addAll(Set.of("expected","baseCommit","branch","targetCheckoutId"));pin(r.getJSONObject("expected"));if("unborn".equals(r.getJSONObject("expected").getString("head")))throw new SecurityException("隔离副本需要真实已提交HEAD");ProjectGitIsolation.commit(r.getString("baseCommit"));ProjectGitIsolation.branch(r.getString("branch"));ProjectBuildPlan.identity(r.getString("targetCheckoutId"));}
        else {expected.addAll(Set.of("expected","paths"));pin(r.getJSONObject("expected"));JSONArray paths=r.getJSONArray("paths");if(paths.length()<1||paths.length()>MAX_SELECTED)throw new SecurityException("Git选择数量无效");Set<String> seen=new HashSet<>();for(int i=0;i<paths.length();i++)if(!seen.add(path(paths.getString(i))))throw new SecurityException("Git路径重复");if(a.equals("commit")){expected.addAll(Set.of("message","authorName","authorEmail","reviewedEntries","parents"));commitEntries(r.getJSONArray("reviewedEntries"),paths);parents(r.getJSONArray("parents"),r.getJSONObject("expected"));JSONObject again=new JSONObject().put("gitAction",a).put("gitPin",r.getJSONObject("expected")).put("gitPaths",paths).put("commitMessage",r.getString("message")).put("authorName",r.getString("authorName")).put("authorEmail",r.getString("authorEmail"));validateCommit(again);}}
        if(r.has("privateWorktree")){if(a.equals("init")||a.equals("isolate"))throw new SecurityException("此入口不嵌套隔离或重置原工作树");ProjectGitIsolation.ownership(r.getJSONObject("privateWorktree"));expected.add("privateWorktree");}
        if(!keys(r).equals(expected)||r.toString().getBytes(StandardCharsets.UTF_8).length>16000)throw new SecurityException("Git请求含未审阅字段或超过本次范围");return r;
    }
    /** UI selection projection only. The original program independently rechecks every pin at dispatch. */
    public static JSONObject selectionRequest(JSONObject snapshot,String action,JSONArray paths)throws Exception {
        if(!Set.of("stage","unstage","resolve","commit").contains(action)||paths.length()<1||paths.length()>MAX_SELECTED)throw new IllegalArgumentException("请选择本次Git动作的1至40个文件");
        JSONObject request=new JSONObject().put("gitAction",action).put("gitPin",pin(snapshot.getJSONObject("pin"))).put("gitPaths",new JSONArray(paths.toString()));
        JSONArray rows=snapshot.getJSONArray("entries"),selectedEntries=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<paths.length();i++){
            String selected=path(paths.getString(i));if(!seen.add(selected))throw new IllegalArgumentException("文件重复选择");JSONObject row=null;
            for(int n=0;n<rows.length();n++)if(selected.equals(rows.getJSONObject(n).getString("path"))){if(row!=null)throw new SecurityException("原Git页路径重复");row=rows.getJSONObject(n);}
            if(row==null||!row.optBoolean("sourceScope"))throw new SecurityException("所选文件不在已读取的当前Git源码页，请重新读取");
            boolean conflict=row.getBoolean("conflict");String index=row.getString("indexStatus"),worktree=row.getString("worktreeStatus");
            if(action.equals("resolve")?!conflict:conflict)throw new IllegalArgumentException("冲突文件须先保存解决稿，再用明确标为解决动作；不混入普通暂存/撤暂存/提交");
            if(action.equals("stage")&&worktree.equals(".")||Set.of("unstage","commit").contains(action)&&(index.equals(".")||index.equals("?")))throw new IllegalArgumentException("选中的文件不都属于本次Git层，请分别选择工作树修改或已暂存修改");
            if(action.equals("commit")){JSONArray entries=row.getJSONArray("indexEntries");if(entries.length()>1||entries.length()==1&&entries.getJSONObject(0).getInt("stage")!=0)throw new SecurityException("仍有未解决的index阶段");JSONObject entry=entries.length()==0?new JSONObject().put("mode","0").put("objectId","absent"):entries.getJSONObject(0);selectedEntries.put(new JSONObject().put("path",selected).put("mode",entry.getString("mode")).put("objectId",entry.getString("objectId")));}
        }
        if(action.equals("commit")){JSONArray parents=new JSONArray();String head=snapshot.getJSONObject("pin").getString("head");if(!head.equals("unborn"))parents.put(head);JSONArray merge=snapshot.getJSONArray("mergeParentIds");for(int i=0;i<merge.length();i++){String candidate=merge.getString(i);boolean duplicate=false;for(int n=0;n<parents.length();n++)duplicate|=candidate.equals(parents.getString(n));if(!duplicate)parents.put(candidate);}request.put("gitEntries",commitEntries(selectedEntries,paths)).put("gitParents",parents(parents,snapshot.getJSONObject("pin")));}
        return request;
    }
    private static JSONArray commitEntries(JSONArray values,JSONArray paths)throws Exception {
        if(values.length()!=paths.length())throw new SecurityException("提交树必须逐项对应已选择的暂存文件");JSONArray result=new JSONArray();
        for(int i=0;i<values.length();i++){JSONObject row=values.getJSONObject(i);if(!keys(row).equals(Set.of("path","mode","objectId"))||!path(row.getString("path")).equals(paths.getString(i)))throw new SecurityException("提交树路径不同");String mode=row.getString("mode"),oid=row.getString("objectId");if(!(Set.of("100644","100755").contains(mode)&&oid.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")||mode.equals("0")&&oid.equals("absent")))throw new SecurityException("提交树blob/mode或删除定位无效");result.put(ProjectSources.copy(row));}return result;
    }
    private static JSONArray parents(JSONArray values,JSONObject pin)throws Exception {
        if(values.length()>8)throw new SecurityException("本次Git合并父节点超过有界范围");Set<String> seen=new HashSet<>();for(int i=0;i<values.length();i++)if(!values.getString(i).matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")||!seen.add(values.getString(i)))throw new SecurityException("提交parent身份无效或重复");String head=pin.getString("head");if(head.equals("unborn")&&values.length()!=0||!head.equals("unborn")&&(values.length()==0||!head.equals(values.getString(0))))throw new SecurityException("提交第一parent必须是原审阅HEAD，首次提交不猜父节点");return new JSONArray(values.toString());
    }
    private static void validateCommit(JSONObject v)throws Exception {String m=v.getString("commitMessage"),n=v.getString("authorName"),e=v.getString("authorEmail");strict(m);strict(n);strict(e);if(m.trim().isEmpty()||m.getBytes(StandardCharsets.UTF_8).length>4096||m.indexOf('\0')>=0||n.isEmpty()||n.length()>100||n.chars().anyMatch(c->c<32||c=='<'||c=='>')||!e.matches("[^\\s<>@]{1,100}@[^\\s<>@]{1,150}"))throw new SecurityException("提交身份或说明无效");}
    private static void strict(String text)throws Exception {StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));}
    private static int integer(JSONObject in,String key,int fallback,int min,int max)throws Exception {Object value=in.has(key)?in.get(key):fallback;if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new IllegalArgumentException("Git页范围无效："+key);return ((Number)value).intValue();}
    private static Set<String> keys(JSONObject object){Set<String> result=new HashSet<>();object.keys().forEachRemaining(result::add);return result;}
    private static JSONObject tool(JSONObject runtime,String name)throws Exception {JSONArray tools=runtime.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++){JSONObject t=tools.getJSONObject(i);if(name.equals(t.optString("name"))&&(t.optBoolean("hashVerified")||t.optBoolean("probeVerified"))&&t.optString("sha256").matches("[a-f0-9]{64}"))return t;}return null;}
    public static JSONObject create(EngineModule local,JSONObject input)throws Exception {if("isolate".equals(input.optString("gitAction")))throw new SecurityException("隔离创建只在原主人Git审阅入口，不由模型计划发起");return createNative(local,input,false);}
    public static JSONObject createIsolationForOwner(EngineModule local,JSONObject input)throws Exception {if(!"isolate".equals(input.optString("gitAction")))throw new SecurityException("不是主人隔离审阅");return createNative(local,input,true);}
    private static JSONObject createNative(EngineModule local,JSONObject input,boolean isolation)throws Exception {
        input=ProjectSources.copy(input);if(input.has("privateWorktree"))throw new SecurityException("不能从请求JSON提供私有Git归属证明");
        if(local instanceof dev.pocketengine.features.local.LocalModule){JSONObject actual=((dev.pocketengine.features.local.LocalModule)local).projectCheckoutForHost(input.getString("checkoutId"));JSONObject owned=actual.optJSONObject("isolatedGit");if(owned!=null){if(isolation)throw new SecurityException("请返回原副本创建另一份隔离，不嵌套隔离池");input.put("privateWorktree",ProjectGitIsolation.ownership(owned));}}
        if(!"managed_process_v1".equals(input.optString("runtimeId")))throw new SecurityException("此Git界面只使用本App原托管运行环境，不切到外部终端");
        for(String key:Set.of("executable","argv","env","cwd","timeoutMillis","terminal","runMode","content","edits","devWatch","liveEditServiceExecutionId","revertOperationId","packageMode"))if(input.has(key))throw new SecurityException("固定Git动作不能夹入任意命令、终端、网络或文件写入");
        JSONObject inspection=ProjectBuildPlan.inspect(local,input.getString("checkoutId"),"managed_process_v1"),source=inspection.getJSONObject("source"),runtime=inspection.getJSONObject("runtime");if(!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest")))throw new SecurityException("源码已变化，请重新读取Git与项目版本后审阅");
        JSONObject git=tool(runtime,"git"),python=tool(runtime,"python3");String gitSha=git==null?"0".repeat(64):git.getString("sha256");JSONObject request=request(input,gitSha);String action=request.getString("action");
        JSONObject payload=new JSONObject().put("checkoutId",input.getString("checkoutId")).put("expectedSourceDigest",source.getString("sourceDigest")).put("executable","python3").put("workingDirectory",".").put("timeoutMillis",120000).put("runMode","foreground").put("terminalMode","pipes").put("gitProtocolVersion",1).put("gitProgramDigest",ProjectGitProgram.identity()).put("gitRequest",request);
        JSONObject step=new JSONObject().put("id","git_"+action).put("stepId","git_"+action).put("localCommand","project.runtime.run").put("payload",payload).put("label",label(action)).put("approvalRequired",true).put("cancelReliable",false).put("effects",new JSONObject().put("writes",writes(action)).put("networkMayBeUsed",false).put("scopeEnforced",false).put("runtime","managed_process_v1").put("fixedGitProtocol",true).put("repositoryHooks",false).put("arbitraryShell",false));
        JSONArray warnings=new JSONArray().put("Git只作用于当前原生选定副本的普通.git仓库，或本入口完整创建的私有common隔离，不是原正式项目版本库。HEAD→暂存区与暂存区→工作树分别核对；暂存与提交不会自动推送，也不会自动导回正式项目或共享成果。").put("不启用仓库hooks、fsmonitor、filter、外部diff、pager或网络Git；未经本入口原生归属核验的外部gitdir/worktree、共享对象库、链接、超限文件和不支持配置仍明确拒绝。每次变更按刚审阅HEAD/index/工作树核对，使用Git索引锁与原分支CAS；同UID绕过Git锁的任意进程不是OS隔离保证。派发后失败可能保留未引用Git对象；未发布不是全部零写入。单次Git语义请求上限16KiB。");
        if(action.equals("isolate"))warnings.put("本次仅从已审baseCommit及其可达历史对象建立私有common库和真实Git worktree；不带原仓配置/hooks/remote凭据，不读网络，不复制未提交WIP。原仓refs与文件不变。成功后另行选择新副本；失败现场保留，不自动合并或删除。此副本与用户原common库不直接链接；同UID/PRoot不是安全沙箱。");
        if(action.equals("commit"))warnings.put("仅提交本次列明且已暂存的文件；其他已暂存内容和工作树修改保留。合并中不能部分提交；须先明确解决全部冲突。作者姓名/邮箱和提交说明会写入本地Git历史，不要填凭据。");
        if(action.equals("resolve"))warnings.put("这一步只把已审阅保存的当前文件标记为解决并暂存，不替你合并或丢弃内容；请先在原文件编辑器按SHA保存解决结果。");
        if(git==null||python==null||!runtime.optBoolean("canExecute"))warnings.put("本机Git/Python工具尚未实际就绪；本方案不会自动安装或换宿主，请先核对工具准备。");
        return ProjectBuildPlan.seal(new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",input.getString("checkoutId")).put("projectId",source.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("runtimeId","managed_process_v1").put("runtimeIdentity",runtime.getString("identity")).put("intent",INTENT).put("ecosystem","native_git").put("runMode","foreground").put("steps",new JSONArray().put(step)).put("available",runtime.optBoolean("canExecute")&&git!=null&&python!=null).put("warnings",warnings).put("automaticChaining",false));
    }
    public static void verify(JSONObject core,JSONArray steps)throws Exception {
        if(!"managed_process_v1".equals(core.optString("runtimeId"))||!"native_git".equals(core.optString("ecosystem"))||steps.length()!=1||!Boolean.FALSE.equals(core.opt("automaticChaining"))||!"foreground".equals(core.optString("runMode")))throw new SecurityException("Git必须是原本机运行环境的一个固定普通步骤");
        JSONObject s=steps.getJSONObject(0),p=s.getJSONObject("payload"),r=verifiedRequest(p.getJSONObject("gitRequest"));String action=r.getString("action");JSONObject effects=s.getJSONObject("effects");
        if(!("git_"+action).equals(s.getString("id"))||!s.getString("id").equals(s.getString("stepId"))||!"project.runtime.run".equals(s.optString("localCommand"))||!Boolean.TRUE.equals(s.opt("approvalRequired"))||!Boolean.FALSE.equals(s.opt("cancelReliable"))||!Integer.valueOf(1).equals(p.opt("gitProtocolVersion"))||!ProjectGitProgram.identity().equals(p.optString("gitProgramDigest"))||!"python3".equals(p.optString("executable"))||!".".equals(p.optString("workingDirectory"))||!Long.valueOf(120000).equals(Long.valueOf(p.getLong("timeoutMillis")))||!"foreground".equals(p.optString("runMode"))||!"pipes".equals(p.optString("terminalMode"))||!core.getString("checkoutId").equals(p.getString("checkoutId"))||!core.getString("sourceDigest").equals(p.getString("expectedSourceDigest")))throw new SecurityException("Git步骤、固定协议或源版本绑定不同");
        if(!keys(p).equals(Set.of("checkoutId","expectedSourceDigest","executable","workingDirectory","timeoutMillis","runMode","terminalMode","gitProtocolVersion","gitProgramDigest","gitRequest"))||!Boolean.valueOf(writes(action)).equals(effects.opt("writes"))||!Boolean.FALSE.equals(effects.opt("networkMayBeUsed"))||!Boolean.FALSE.equals(effects.opt("scopeEnforced"))||!"managed_process_v1".equals(effects.optString("runtime"))||!Boolean.TRUE.equals(effects.opt("fixedGitProtocol"))||!Boolean.FALSE.equals(effects.opt("repositoryHooks"))||!Boolean.FALSE.equals(effects.opt("arbitraryShell")))throw new SecurityException("Git含未审阅参数或效果声明不同");
    }
    /** Native construction only. Immutable plans carry semantic Git request + fixed program SHA, never a huge nested script. */
    public static JSONArray arguments(JSONObject payload)throws Exception {if(!ProjectGitProgram.identity().equals(payload.optString("gitProgramDigest"))||!Integer.valueOf(1).equals(payload.opt("gitProtocolVersion")))throw new SecurityException("原Git程序版本已变化");return new JSONArray().put("-c").put(ProjectGitProgram.source()).put(ProjectSources.canonical(verifiedRequest(payload.getJSONObject("gitRequest"))));}
    public static void requireCurrentTools(JSONObject exact,JSONObject runtime)throws Exception {
        if(!exact(exact))throw new SecurityException("不是原固定Git执行");JSONObject git=tool(runtime,"git"),python=tool(runtime,"python3"),r=verifiedRequest(exact.getJSONObject("payload").getJSONObject("gitRequest"));if(git==null||python==null||!runtime.optBoolean("canExecute")||!git.getString("sha256").equals(r.getString("gitExecutableSha256")))throw new ProjectDevelopment.NotDispatched(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","project_git_tools_unavailable").put("message","原Git/Python工具未就绪或版本已变；本次没有派发，请重新准备并审阅"));
    }
    public static JSONObject semantic(JSONObject payload)throws Exception {
        JSONObject r=verifiedRequest(payload.getJSONObject("gitRequest")),result=new JSONObject().put("action",r.getString("action")).put("label",label(r.getString("action"))).put("writesGit",writes(r.getString("action"))).put("writesWorktree",r.getString("action").equals("isolate")).put("network",false).put("hooks",false).put("fixedProgramDigest",payload.getString("gitProgramDigest"));
        for(String key:Set.of("expected","expectedWorktreeSha256","paths","path","layer","offset","limit","message","authorName","authorEmail","reviewedEntries","parents","baseCommit","branch","targetCheckoutId","privateWorktree"))if(r.has(key))result.put(key,r.get(key));return result;
    }
    public static String label(String action){return switch(action){case "status"->"读取当前Git双状态";case "history"->"读取原HEAD的真实本地提交历史";case "diff"->"读取选定基线的真实Git差异";case "init"->"在此工作副本初始化本地Git（不暂存）";case "stage"->"只暂存所选文件";case "unstage"->"只撤销所选文件的暂存（保留工作树）";case "resolve"->"将已审阅文件明确标为解决并暂存";case "commit"->"只将所选已暂存内容提交到本地Git";case "isolate"->"基于已审提交创建私有隔离副本";default->throw new IllegalArgumentException("Git动作无效");};}
    /** Native owner value: no generic feature/model JSON entry can manufacture or restore it. */
    public static final class OwnerRequest {
        private final JSONObject plan,capability;public final String requestId,planDigest,checkoutId,projectId,action;private final long createdAt;
        private OwnerRequest(JSONObject plan,JSONObject capability)throws Exception {this.plan=ProjectBuildPlan.verified(plan);step(this.plan);this.capability=ProjectSources.copy(capability);requestId=UUID.randomUUID().toString();planDigest=this.plan.getString("planDigest");checkoutId=this.plan.getString("checkoutId");projectId=this.plan.getString("projectId");action=ProjectGit.action(this.plan);createdAt=System.currentTimeMillis();}
        public JSONObject review()throws Exception{return semantic(step(plan).getJSONObject("payload")).put("requestId",requestId).put("planDigest",planDigest).put("projectId",projectId).put("checkoutId",checkoutId).put("sourceDigest",plan.getString("sourceDigest")).put("available",plan.getBoolean("available")).put("warnings",plan.getJSONArray("warnings"));}
        public Confirmed confirm()throws Exception {if(System.currentTimeMillis()-createdAt>120000)throw new SecurityException("这份Git审阅已过期，请重新核对；未执行");return new Confirmed(this);}
    }
    public static final class Confirmed {
        private final OwnerRequest original;private Confirmed(OwnerRequest original){this.original=original;}
        public String requestId(){return original.requestId;}
        public JSONObject plan()throws Exception{return ProjectSources.copy(original.plan);}
        public JSONObject capability()throws Exception{return ProjectSources.copy(original.capability);}
        public JSONObject review()throws Exception{return original.review();}
        public void requireUnexpired()throws Exception{if(System.currentTimeMillis()-original.createdAt>120000)throw new SecurityException("原主人Git审阅已过期；不能恢复确认或派发");}
    }
    /** Original native entry proves that it has not called Runtime.dispatch yet; not a JSON claim. */
    public static final class NotDispatched extends Exception {
        private final Confirmed selected;public final String reason;
        private NotDispatched(Confirmed selected,Exception cause){super("Git审阅或原执行条件已变化，本次未进入运行时派发",cause);this.selected=selected;String value=cause.getMessage();reason=value==null?cause.getClass().getSimpleName():value.substring(0,Math.min(500,value.length()));}
        public boolean matches(Confirmed request){return selected==request;}
    }
    public static NotDispatched rejectedBeforeRuntime(Confirmed request,Exception nativePreflight){return new NotDispatched(request,nativePreflight);}
    /** Called by LocalModule's native owner selection only; raw JSON has no route to this value. */
    public static OwnerRequest ownerRequest(JSONObject nativePlan,JSONObject nativeCapability)throws Exception{return new OwnerRequest(nativePlan,nativeCapability);}
}
