package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.plugins.McpLocalPackage;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** One private draft checkout's frozen registry lock. No package I/O happens while reviewing. */
public final class OwnerDraftNodeDependencies {
    public static final String FIELD="ownerDraftNodeDependencies";
    public static final String STEP="dependencies";
    public static final String REGISTRY="https://registry.npmjs.org/";
    private static final String USER_CONFIG=".pw-locked-npm-no-user-config", GLOBAL_CONFIG=".pw-locked-npm-no-global-config", PNPM_CONFIG=".pw-locked-pnpm-no-config";
    private static final String NAME="(?:@[a-z0-9][a-z0-9_.-]*/)?[a-z0-9_][a-z0-9_.-]*";
    private static final String VERSION="[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?";
    private static final Set<String> CONFIG=Set.of(".npmrc",".pnpmrc",".pnpmfile.cjs","pnpmfile.cjs","pnpm-workspace.yaml",
        ".npm-extension.mjs",".npm-extension.cjs",".npm-extension.js","npm-shrinkwrap.json",USER_CONFIG,GLOBAL_CONFIG,PNPM_CONFIG);
    private static final String[] DEPENDENCIES={"dependencies","devDependencies","optionalDependencies","peerDependencies"};
    private OwnerDraftNodeDependencies(){}

    public static JSONObject review(JSONObject snapshot,String manager,JSONObject runtime)throws Exception{
        Map<String,String> files=ProjectSources.files(snapshot);
        String manifest=files.get("package.json");
        if(manifest==null||bytes(manifest)>65536)throw new IllegalArgumentException("需要完整可核对的 package.json（64 KiB 以内）");
        JSONObject pkg=McpJson.parse(manifest);TreeSet<String> required=new TreeSet<>();int declared=0;
        for(String kind:DEPENDENCIES){
            String policy="peerDependencies".equals(kind)?"optionalDependencies":kind;
            TreeSet<String> names=dependencyNames(pkg,kind);
            if(!"optionalDependencies".equals(policy))required.addAll(names);
            if("optionalDependencies".equals(kind))required.removeAll(names);
            declared+=names.size();
        }
        if(declared==0)return null;
        if(required.isEmpty())throw new IllegalArgumentException("这里只准备明确必需的直接依赖；仅可选包或 peer 依赖时不猜测，未下载。");
        if(declared>128)throw new IllegalArgumentException("这份项目的直接依赖超过本次受审准备上限");
        requirePlainProject(pkg,files.keySet());
        String lockPath="npm".equals(manager)?"package-lock.json":"pnpm-lock.yaml",other="npm".equals(manager)?"pnpm-lock.yaml":"package-lock.json";
        String body=files.get(lockPath);
        if(body==null||files.containsKey(other)||bytes(body)>524288)
            throw new IllegalArgumentException("需要这一版唯一的 "+lockPath+"（512 KiB 以内），未改用无锁安装");
        String version=managerVersion(manager,runtime);
        if(pkg.has("packageManager")&&!String.valueOf(manager+'@'+version).equals(pkg.opt("packageManager")))
            throw new IllegalArgumentException("packageManager 与本机实测版本不同，未下载或切换包管理器");
        int count=McpLocalPackage.verifiedPublicRegistryLock(manager,body,declared);
        if(count<1||count>8192)throw new IllegalArgumentException("锁文件没有可核对的固定注册表依赖，或数量超限");
        JSONObject direct=new JSONObject();String platformPackage="";
        if("npm".equals(manager)){
            JSONObject lock=McpJson.parse(body),packages=lock.getJSONObject("packages");
            checkNpmList(pkg,packages);
            for(String name:required)direct.put(name,packages.getJSONObject("node_modules/"+name).getString("version"));
            if(direct.has("esbuild")){
                platformPackage=esbuildPlatform(runtime);
                String versionForPlatform=packages.getJSONObject("node_modules/"+platformPackage).getString("version");
                if(!direct.getString("esbuild").equals(versionForPlatform))throw new SecurityException("锁内 esbuild JavaScript 与此运行位置的可选平台包版本不同");
                direct.put(platformPackage,versionForPlatform);
            }
        }else {
            if(required.contains("esbuild"))throw new IllegalStateException("尚不能核对 pnpm 目录结构内实际使用的 esbuild 平台包；不改锁或切换到 npm。");
            if(body.indexOf('\\')>=0||body.contains("yaml:")||body.contains("registries:")||body.contains("packageExtensions"))
                throw new IllegalArgumentException("此 pnpm 锁含不能确认的转义或注册表配置，未使用");
            for(String name:required)direct.put(name,""); // pnpm itself verifies the frozen actual resolution and every SHA-512.
        }
        return new JSONObject().put("manager",manager).put("managerVersion",version).put("registry",REGISTRY)
            .put("lockPath",lockPath).put("lockSha256",hash(body)).put("manifestSha256",hash(manifest))
            .put("packages",count).put("direct",direct).put("platformPackage",platformPackage);
    }
    static void requirePlainProject(JSONObject pkg,Set<String> paths)throws Exception{
        for(String key:Set.of("workspaces","pnpm","overrides","resolutions","config","bundledDependencies","bundleDependencies","devEngines"))
            if(pkg.has(key))throw new IllegalArgumentException("此流程不读取运行工具的项目级自定义配置或工作区；没有运行："+key);
        for(String path:CONFIG)if(paths.contains(path))throw configuration();
    }
    private static TreeSet<String> dependencyNames(JSONObject packageRow,String kind)throws Exception{
        TreeSet<String> names=new TreeSet<>();if(!packageRow.has(kind))return names;
        JSONObject map=packageRow.getJSONObject(kind);
        for(var fields=map.keys();fields.hasNext();){
            String name=fields.next();Object spec=map.get(name);
            if(name.length()>160||!name.matches(NAME)||!(spec instanceof String value)||value.length()>100||
                !value.matches("[0-9v<>=~^.*| -]+(?:-[A-Za-z0-9.-]+)?"))throw new IllegalArgumentException("只准备锁定的普通注册表依赖，不使用目录、git、URL、别名、标签或工作区");
            names.add(name);
        }
        return names;
    }
    private static void checkNpmList(JSONObject manifest,JSONObject packages)throws Exception{
        JSONObject root=packages.getJSONObject("");
        for(String k:DEPENDENCIES)if(!ProjectSources.canonical(manifest.opt(k)).equals(ProjectSources.canonical(root.opt(k))))
            throw new IllegalArgumentException("package.json 的依赖与 package-lock.json 不一致，不更新锁或下载");
        for(var paths=packages.keys();paths.hasNext();){
            String path=paths.next();if(path.isEmpty())continue;
            JSONObject row=packages.getJSONObject(path);
            if(!path.matches("(?:node_modules/"+NAME+"/)*node_modules/"+NAME)||row.optBoolean("inBundle"))
                throw new IllegalArgumentException("npm 锁包含非标准包目录或 bundled 依赖");
            for(String k:DEPENDENCIES)dependencyNames(row,k);
            String name=path.substring(path.lastIndexOf("node_modules/")+"node_modules/".length()),leaf=name.substring(name.lastIndexOf('/')+1);
            String expected="/"+name+"/-/"+leaf+"-"+row.getString("version")+".tgz";
            if(!expected.equals(URI.create(row.getString("resolved")).getRawPath()))
                throw new IllegalArgumentException("锁定包的注册表路径与名称/版本不一致");
        }
    }
    private static String managerVersion(String manager,JSONObject runtime)throws Exception{
        JSONArray tools=runtime.getJSONArray("tools");
        for(int i=0;i<tools.length();i++){
            JSONObject item=tools.getJSONObject(i);
            if(!manager.equals(item.opt("name"))||!item.optBoolean("hashVerified")&&!item.optBoolean("probeVerified"))continue;
            String observed=item.optString("observedVersion",item.optString("version","")).split("\\R",2)[0].strip();
            if(observed.matches(VERSION)&&observed.startsWith("npm".equals(manager)?"11.":"10."))return observed;
        }
        throw new IllegalStateException("尚未确认本机的 "+("npm".equals(manager)?"npm 11":"pnpm 10")+"，不会下载或切换包管理器");
    }
    private static String esbuildPlatform(JSONObject runtime){
        if("glibc".equals(runtime.opt("guestLibc"))&&"arm64-v8a".equals(runtime.opt("abi")))return "@esbuild/linux-arm64";
        if(!"Linux".equals(System.getProperty("os.name"))||runtime.has("abi"))throw new IllegalStateException("尚未核实此本机运行位置的 esbuild 平台");
        return switch(System.getProperty("os.arch","")){
            case "amd64","x86_64"->"@esbuild/linux-x64";
            case "aarch64","arm64"->"@esbuild/linux-arm64";
            default->throw new IllegalStateException("尚未核实此本机运行位置的 esbuild 平台");
        };
    }
    private static long bytes(String s){return s.getBytes(StandardCharsets.UTF_8).length;}
    private static String hash(String s)throws Exception{return ProjectSources.sha256(s.getBytes(StandardCharsets.UTF_8));}
    private static SecurityException configuration(){return new SecurityException("此流程不读取或沿用用户的包管理器配置。请用无 npmrc、工作区或安装扩展的独立副本，未下载。");}
    static JSONArray argv(String manager)throws Exception{
        List<String> fixed=new ArrayList<>("npm".equals(manager)
            ?List.of("ci","--ignore-scripts","--no-audit","--no-fund","--workspaces=false","--include=dev","--include=optional")
            :List.of("install","--frozen-lockfile","--ignore-scripts","--ignore-pnpmfile","--config.manage-package-manager-versions=false",
                "--config.package-manager-strict-version=true","--config.side-effects-cache=false","--config.verify-store-integrity=true","--config.recursive-install=false"));
        fixed.addAll(List.of("--registry="+REGISTRY,"--strict-ssl=true","--userconfig=./"+USER_CONFIG,"--globalconfig=./"+GLOBAL_CONFIG,
            "--fetch-retries=0","--fetch-timeout=45000"));
        return new JSONArray(fixed.toArray(new String[0]));
    }
    /** Kept outside the model command environment allowlist. pnpm v10 also loads XDG_CONFIG_HOME/pnpm/rc. */
    public static Map<String,String> nativeEnvironment(){
        return Map.of("XDG_CONFIG_HOME","./"+PNPM_CONFIG,"COREPACK_ENABLE_PROJECT_SPEC","0","COREPACK_ENABLE_NETWORK","0","CI","true",
            "npm_config_userconfig","./"+USER_CONFIG,"npm_config_globalconfig","./"+GLOBAL_CONFIG,"npm_config_registry",REGISTRY,
            "npm_config_strict_ssl","true","npm_config_verify_deps_before_run","false","npm_config_manage_package_manager_versions","false");
    }
    public static boolean exactEnvironment(String key,String value){return value.equals(nativeEnvironment().get(key));}
    public static boolean exact(JSONObject exact){
        JSONObject payload=exact.optJSONObject("payload");return payload!=null&&payload.has(FIELD);
    }
    public static void workspace(RuntimeWorkspace root)throws Exception{
        for(String file:CONFIG){
            try{
                Files.readAttributes(root.resolve(file,false).toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                throw configuration();
            }catch(NoSuchFileException missing){/* Absence verified without reading the file. */}
        }
        Path modules=root.resolve("node_modules",false).toPath();
        try{
            if(!Files.readAttributes(modules,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isDirectory())
                throw new SecurityException("副本的 node_modules 不是可核对的本地目录，没有安装");
        }catch(NoSuchFileException missing){/* A fresh checkout is expected to have none. */}
    }
    private static final class Missing extends IllegalStateException {
        Missing(String message){super(message);}
    }
    public static boolean ready(RuntimeWorkspace root,JSONObject review)throws Exception{
        try{requireInstalled(root,review);return true;}catch(Missing absent){return false;}
    }
    /** These current local facts let an owner separately request a build, never a build verdict. */
    public static void requireInstalled(RuntimeWorkspace root,JSONObject review)throws Exception{
        workspace(root);
        JSONObject direct=review.getJSONObject("direct");Path modules=root.resolve("node_modules",false).toPath();
        for(var names=direct.keys();names.hasNext();){
            String name=names.next(),version=direct.getString(name);
            if(!name.matches(NAME))throw new SecurityException("不是原锁定包名");
            Path child=modules.resolve(name),pkg=child.resolve("package.json");
            try{
                // pnpm's own package links are allowed only within this checkout's actual node_modules tree.
                Path real=pkg.toRealPath();
                if(!real.startsWith(modules.toRealPath())||!"pnpm".equals(review.getString("manager"))&&!real.equals(pkg))throw new SecurityException("已安装包指向了原副本以外的路径");
                BasicFileAttributes before=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!before.isRegularFile()||before.size()>65536)throw new SecurityException("安装包的版本事实不是有界的普通文件");
                byte[] data;
                try(InputStream in=Files.newInputStream(real,LinkOption.NOFOLLOW_LINKS)){data=readAtMost(in,65537);}
                BasicFileAttributes after=Files.readAttributes(real,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!after.isRegularFile()||data.length!=before.size()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||
                    !Objects.equals(before.fileKey(),after.fileKey()))throw new SecurityException("核对期间副本的依赖已变化");
                JSONObject observed=McpJson.parse(new String(data,StandardCharsets.UTF_8));
                if(!name.equals(observed.opt("name"))||!version.isEmpty()&&!version.equals(observed.opt("version")))
                    throw new Missing("副本里 "+name+" 的版本与原锁不同，未运行构建。");
                if(name.equals(review.opt("platformPackage"))){
                    Path executable=child.resolve("bin/esbuild"),binary=executable.toRealPath();
                    if(!executable.equals(binary)||!Files.isRegularFile(binary,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(binary)||!linuxElf(binary,name))
                        throw new Missing("未找到这个本机平台实际可执行的 esbuild 文件；退出 0 不代表依赖齐全");
                }
            }catch(NoSuchFileException missing){throw new Missing("本机依赖未实际齐全（"+name+"）。请回原准备记录查看日志，核对网络或重新批准同一份锁；未运行构建。");}
        }
    }
    /** Keep the same extra-byte and ELF prefix limits on Android 11 and 12 as on newer Android. */
    private static byte[] readAtMost(InputStream in,int maximum)throws IOException{
        byte[] bytes=new byte[maximum];int length=0;
        while(length<maximum){
            int count=in.read(bytes,length,maximum-length);
            if(count<0)break;
            if(count==0){
                int value=in.read();
                if(value<0)break;
                bytes[length++]=(byte)value;
            }else length+=count;
        }
        return length==maximum?bytes:Arrays.copyOf(bytes,length);
    }
    private static boolean linuxElf(Path binary,String name)throws Exception{
        byte[] b;try(InputStream in=Files.newInputStream(binary,LinkOption.NOFOLLOW_LINKS)){b=readAtMost(in,20);}
        int machine="@esbuild/linux-arm64".equals(name)?183:62;
        return b.length==20&&b[0]==0x7f&&b[1]=='E'&&b[2]=='L'&&b[3]=='F'&&b[4]==2&&b[5]==1&&(b[18]&255)==machine&&b[19]==0;
    }
    public static void requireTypedStep(JSONObject step,JSONObject source,JSONObject review)throws Exception{
        JSONObject p=step.getJSONObject("payload");String manager=review.getString("manager");
        Set<String> fields=new HashSet<>();p.keys().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("checkoutId","expectedSourceDigest","executable","argv",FIELD))||
            !Set.of("npm","pnpm").contains(manager)||!REGISTRY.equals(review.opt("registry"))||
            !STEP.equals(step.opt("id"))||!"project.runtime.run".equals(step.opt("localCommand"))||!Boolean.TRUE.equals(step.opt("approvalRequired"))||
            !manager.equals(p.opt("executable"))||!ProjectSources.canonical(argv(manager)).equals(ProjectSources.canonical(p.opt("argv")))||
            !"managed_process_v1".equals(step.getJSONObject("effects").opt("runtime"))||!step.getJSONObject("effects").optBoolean("networkMayBeUsed")||
            !source.getString("checkoutId").equals(p.opt("checkoutId"))||!source.getString("sourceDigest").equals(p.opt("expectedSourceDigest")))
            throw new SecurityException("锁定依赖不是固定注册表、原副本和原命令");
    }
    public static void requireLaunch(JSONObject exact,JSONObject snapshot,JSONObject runtime,RuntimeWorkspace root)throws Exception{
        if(!exact(exact))return;JSONObject p=exact.getJSONObject("payload");
        JSONObject shown=p.getJSONObject(FIELD),current=review(snapshot,shown.getString("manager"),runtime);
        if(current==null||!ProjectSources.canonical(shown).equals(ProjectSources.canonical(current))||
            !ProjectSources.canonical(argv(shown.getString("manager"))).equals(ProjectSources.canonical(p.opt("argv")))||
            !shown.getString("manager").equals(p.opt("executable"))||!"dependencies".equals(exact.opt("intent"))||!STEP.equals(exact.opt("stepId")))
            throw new SecurityException("当前锁、平台、包管理器或准备参数已改变，没有下载");
        workspace(root);
    }
}
