package dev.pocketengine.features.plugins;

import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, exact dependency preparation proposal. JSON requests a review; only the original
 * owner Review and prepared plugin operation authorize it. No package manager is downloaded. */
public final class McpLocalPackage {
    public static final String FIELD="dependencyPreparation",PLAN="packagePreparation";
    public static final String FORMAT="pocketworld.native-mcp-package.v1";
    public static final int INITIALIZATION_MILLIS=120000;
    private static final String REGISTRY="https://registry.npmjs.org/";
    private static final Pattern VERSION=Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?");
    private static final Pattern INTEGRITY=Pattern.compile("sha512-[A-Za-z0-9+/]{86}==");
    private McpLocalPackage() {}

    /** The requested manager/version is explicit and pinned in package.json as well. */
    static JSONObject request(JSONObject configuration)throws Exception {
        if(!configuration.has(FIELD))return null;
        JSONObject input=configuration.getJSONObject(FIELD);Set<String> keys=new HashSet<>();input.keys().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("manager","version"))||!(input.opt("manager") instanceof String)||!(input.opt("version") instanceof String))throw new IllegalArgumentException("依赖准备需要准确manager和version；不接受脚本、下载器或额外安装选项");
        String manager=input.getString("manager"),version=input.getString("version");
        if(!Set.of("npm","pnpm").contains(manager)||!VERSION.matcher(version).matches()||!version.startsWith(manager.equals("npm")?"11.":"10."))throw new IllegalArgumentException("本机MCP固定包准备当前支持已安装npm 11或pnpm 10的准确版本；不自动安装或选择latest");
        return new JSONObject().put("manager",manager).put("version",version);
    }
    static Proposal inspect(JSONObject config,JSONObject source,ManagedProjectRuntime runtime)throws Exception {
        JSONObject requested=request(config);if(requested==null)return null;
        if(!"node".equals(config.getString("executable")))throw new IllegalArgumentException("npm/pnpm依赖准备只用于当前明确node入口，不混用Python或Shell");
        return inspect(config,source,runtime.mcpPackageToolsForHost(requested.getString("manager")));
    }
    /** Native tool facts seam; never called from a model/feature JSON route. */
    static Proposal inspect(JSONObject config,JSONObject source,JSONObject tools)throws Exception {
        JSONObject requested=request(config);if(requested==null)return null;String manager=requested.getString("manager"),version=requested.getString("version");
        if(!version.equals(tools.getString("managerVersion")))throw new SecurityException("已安装包管理器版本与本次准确选择不同，请先从原运行环境入口核对");
        for(String field:new String[]{"bootstrapSha256","nodeSha256","managerSha256"})if(!tools.getString(field).matches("[a-f0-9]{64}"))throw new SecurityException("原包管理器或解释器缺少已核对SHA-256");
        for(String field:new String[]{"pythonArgv","nodeArgv","managerArgv"}){JSONArray argv=tools.getJSONArray(field);if(argv.length()<1||argv.length()>16||!argv.getString(0).startsWith("/"))throw new SecurityException("本机包准备需要原运行环境实际工具路径");for(int i=0;i<argv.length();i++){String argument=argv.getString(i);ProjectCommandSpec.encode(argument);if(Set.of("-c","-e","--eval","--command").contains(argument))throw new SecurityException("包管理器工具映射不能包含eval或Shell");}}
        Map<String,String> files=ProjectSources.files(source);String manifest=files.get("package.json"),lockPath=manager.equals("npm")?"package-lock.json":"pnpm-lock.yaml",lock=files.get(lockPath);
        if(manifest==null||lock==null||manifest.getBytes(StandardCharsets.UTF_8).length>65536||lock.getBytes(StandardCharsets.UTF_8).length>524288)throw new IllegalArgumentException("需要当前冻结源内完整package.json与固定锁文件（64KiB/512KiB范围）");
        JSONObject pkg=McpJson.parse(manifest);
        if(!String.valueOf(manager+"@"+version).equals(pkg.opt("packageManager")))throw new IllegalArgumentException("package.json必须声明与本次选择一致的准确packageManager版本");
        for(String field:new String[]{"workspaces","pnpm","overrides","resolutions","config"})if(pkg.has(field))throw new IllegalArgumentException("本机MCP包准备当前不支持工作区或自定义包管理配置："+field);
        if(files.containsKey("pnpm-workspace.yaml")||files.containsKey("npm-shrinkwrap.json")||files.containsKey(manager.equals("npm")?"pnpm-lock.yaml":"package-lock.json"))throw new IllegalArgumentException("存在冲突锁文件/工作区配置，请先明确整理原项目");
        JSONArray dependencies=new JSONArray();
        for(String kind:new String[]{"dependencies","devDependencies","optionalDependencies"}){
            if(!pkg.has(kind))continue;JSONObject rows=pkg.getJSONObject(kind);
            for(var names=rows.keys();names.hasNext();){String name=names.next();Object value=rows.get(name);if(!name.matches("(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+")||!(value instanceof String)||!((String)value).matches("[0-9v<>=~^.*| -]+(?:-[A-Za-z0-9.-]+)?"))throw new IllegalArgumentException("仅支持固定注册表的SemVer依赖；本机目录、URL、git、别名和workspace依赖需另行审阅");dependencies.put(new JSONObject().put("name",name).put("spec",value).put("kind",kind));}
        }
        int count=manager.equals("npm")?npmLock(lock):pnpmLock(lock,dependencies.length());
        JSONObject facts=ProjectSources.describe(source),plan=new JSONObject().put("format",FORMAT).put("manager",manager).put("managerVersion",version).put("registry",REGISTRY)
                .put("manifestPath","package.json").put("manifestSha256",ProjectSources.sha256(manifest.getBytes(StandardCharsets.UTF_8))).put("lockfilePath",lockPath).put("lockfileSha256",ProjectSources.sha256(lock.getBytes(StandardCharsets.UTF_8)))
                .put("sourceDigest",facts.getString("sourceDigest")).put("runtimeIdentity",tools.getString("runtimeIdentity")).put("managerSha256",tools.getString("managerSha256")).put("bootstrapSha256",tools.getString("bootstrapSha256")).put("nodeSha256",tools.getString("nodeSha256"))
                .put("programDigest",McpLocalPackageProgram.identity()).put("lockedPackageCount",count).put("dependencies",dependencies).put("frozenLockfile",true).put("packageIntegrity","sha512_lock_enforced_by_selected_manager").put("lifecycleScriptsAllowed",false).put("packageManagerDownloadAllowed",false).put("credentialsUsed",false).put("networkMayBeUsed",true)
                .put("preparationTimeoutMillis",90000).put("dependencyWorkspace","same_original_job_visible_copy").put("dependencyCachesReused",false).put("diskQuotaEnforced",false).put("osSandbox",false).put("toolInvocationAuthorized",false);
        return new Proposal(plan,tools,facts.getJSONArray("files"),config.getJSONArray("argv"));
    }
    private static int npmLock(String lock)throws Exception {
        JSONObject data=McpJson.parse(lock);if(data.optInt("lockfileVersion")!=3)throw new IllegalArgumentException("npm准备需要lockfileVersion 3");JSONObject packages=data.getJSONObject("packages");int count=0;
        for(var names=packages.keys();names.hasNext();){String name=names.next();JSONObject row=packages.getJSONObject(name);if(name.isEmpty())continue;
            if(!name.startsWith("node_modules/")||row.optBoolean("link")||!VERSION.matcher(row.optString("version")).matches()||!INTEGRITY.matcher(row.optString("integrity")).matches())throw new IllegalArgumentException("npm锁中存在非固定注册表包、链接或缺少SHA-512的包");
            URI uri=new URI(row.getString("resolved"));if(!"https".equals(uri.getScheme())||!"registry.npmjs.org".equals(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().endsWith(".tgz"))throw new IllegalArgumentException("npm锁包来源必须是默认HTTPS注册表的准确tgz");count++;
        }
        return count;
    }
    /** Deliberately bounded pnpm v9 lock subset. The selected pnpm still parses and verifies the
     * entire frozen lock; unsupported YAML/source forms are rejected, never silently rewritten. */
    private static int pnpmLock(String lock,int declaredDependencies)throws Exception {
        if(!Pattern.compile("(?m)^lockfileVersion: ['\"]?9\\.0['\"]?\\s*$").matcher(lock).find())throw new IllegalArgumentException("pnpm准备需要lockfileVersion 9.0");
        if(Pattern.compile("(?im)(?:file:|link:|workspace:|git\\+|github:|https?://|tarball:|directory:|patchedDependencies:|patchFile:|pnpmfile|!!|<<:|[&*][A-Za-z_]|:\\s*[>|][+-]?\\s*$)").matcher(lock).find()||lock.indexOf('\t')>=0)throw new IllegalArgumentException("此pnpm锁含自定义来源、patch、YAML引用或配置，未当成受支持的注册表锁");
        String section="",currentPackage=null;int packages=0,resolution=0;Set<String> sections=new HashSet<>(),names=new HashSet<>();
        for(String line:lock.split("\\r?\\n")){
            if(line.isBlank()||line.stripLeading().startsWith("#"))continue;
            if(!line.startsWith(" ")){
                if(currentPackage!=null&&resolution!=1)throw new IllegalArgumentException("pnpm锁中的每个包必须有一条SHA-512完整性记录");currentPackage=null;resolution=0;
                int colon=line.indexOf(':');if(colon<1)throw new IllegalArgumentException("pnpm锁需要普通映射字段");section=line.substring(0,colon);
                if(!Set.of("lockfileVersion","settings","importers","packages","snapshots").contains(section)||!sections.add(section))throw new IllegalArgumentException("pnpm锁含重复或未支持的根配置");
                if(!section.equals("lockfileVersion")&&!line.matches(section+":(?: \\{\\})? *"))throw new IllegalArgumentException("pnpm锁不支持内联根映射或其它YAML变体");
            } else if("packages".equals(section)&&line.startsWith("  ")&&!line.startsWith("   ")){
                if(currentPackage!=null&&resolution!=1)throw new IllegalArgumentException("pnpm锁中的每个包必须有一条SHA-512完整性记录");
                String key=line.substring(2);if(!key.endsWith(":"))throw new IllegalArgumentException("pnpm锁需要完整包条目");key=key.substring(0,key.length()-1);
                if(key.startsWith("'")&&key.endsWith("'")||key.startsWith("\"")&&key.endsWith("\""))key=key.substring(1,key.length()-1);
                if(!key.matches("(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+@[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")||!names.add(key))throw new IllegalArgumentException("pnpm锁需要准确、唯一的注册表包版本");currentPackage=key;resolution=0;packages++;
            } else if(line.stripLeading().startsWith("resolution:")){
                if(!"packages".equals(section)||currentPackage==null||!line.matches("    resolution: \\{integrity: ['\"]?sha512-[A-Za-z0-9+/]{86}==['\"]?\\}")||++resolution!=1)throw new IllegalArgumentException("pnpm锁包需要明确单行SHA-512 resolution；不猜测或丢弃其它来源字段");
            }
        }
        if(currentPackage!=null&&resolution!=1||!sections.contains("importers")||declaredDependencies>0&&packages==0)throw new IllegalArgumentException("pnpm锁没有完整可核对的包完整性记录");return packages;
    }
    static final class Proposal {
        private final String plan,job,jobDigest,command;
        private Proposal(JSONObject plan,JSONObject tools,JSONArray files,JSONArray serverArgv)throws Exception {
            this.plan=plan.toString();JSONObject privateJob=new JSONObject().put("format","pocketworld.native-mcp-package-job.v1").put("plan",plan).put("tools",tools).put("files",files).put("serverArgv",serverArgv);job=ProjectSources.canonical(privateJob);if(job.getBytes(StandardCharsets.UTF_8).length>524288)throw new IllegalArgumentException("本机MCP包准备原生描述超过512KiB");jobDigest=ProjectSources.sha256(job.getBytes(StandardCharsets.UTF_8));
            command=ProjectCommandSpec.normalize(new JSONObject().put("executable","python3").put("argv",new JSONArray().put("-I").put("-c").put(McpLocalPackageProgram.source()).put(jobDigest)).put("cwd",".").put("terminal","pipes").put("runMode","owned_service").put("timeoutMillis",McpLocalDiscovery.TIMEOUT_MILLIS)).toString();
        }
        JSONObject plan()throws Exception{return new JSONObject(plan);}
        String digest()throws Exception{return ProjectSources.digest(plan());}
        JSONObject command()throws Exception{return new JSONObject(command);}
        void addPayload(JSONObject payload)throws Exception{payload.put("nativeMcpPackageProgramDigest",McpLocalPackageProgram.identity()).put("nativeMcpPackageJobDigest",jobDigest);}
        void write(RuntimeWorkspace copy)throws Exception{java.io.File target=copy.resolve(".native-mcp-package.json",false);Files.writeString(target.toPath(),job,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
    }
    /** Called only at original native pipe admission, after the typed TaskStore authority check. */
    public static boolean prepared(JSONObject exact){JSONObject payload=exact.optJSONObject("payload");return payload!=null&&payload.has("nativeMcpPackageProgramDigest");}
    public static void requireProgram(JSONObject exact)throws Exception {
        if(!Set.of(McpLocalDiscovery.INTENT,McpLocalInvocation.INTENT).contains(exact.optString("intent")))throw new SecurityException("不是原本机MCP包准备动作");JSONObject p=exact.getJSONObject("payload"),command=ProjectCommandSpec.fromPayload(p);JSONArray argv=command.getJSONArray("argv");
        if(!"python3".equals(command.getString("executable"))||argv.length()!=4||!"-I".equals(argv.getString(0))||!"-c".equals(argv.getString(1))||!McpLocalPackageProgram.source().equals(argv.getString(2))||!argv.getString(3).matches("[a-f0-9]{64}")||!argv.getString(3).equals(p.getString("nativeMcpPackageJobDigest"))||!McpLocalPackageProgram.identity().equals(p.getString("nativeMcpPackageProgramDigest"))||!".".equals(command.getString("workingDirectory"))||!"pipes".equals(command.getString("terminalMode"))||!"owned_service".equals(command.getString("runMode"))||command.getJSONObject("environment").length()!=0)throw new SecurityException("本机MCP包准备的固定程序或准确参数已变化");
    }
}
