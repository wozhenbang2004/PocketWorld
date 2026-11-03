package dev.pocketengine.features.local.project;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independently authored project discovery and exact, individually reviewed backend requests. */
public final class ProjectBuildPlan {
    private ProjectBuildPlan() {}
    public static JSONObject inspect(EngineModule local,String checkout)throws Exception {
        return inspect(local,checkout,"termux");
    }
    public static JSONObject inspect(EngineModule local,String checkout,String runtimeId)throws Exception {
        JSONObject snapshot=local.handle("export",new JSONObject().put("checkoutId",checkout));
        JSONObject source=ProjectSources.describe(snapshot);Map<String,String> files=ProjectSources.files(snapshot);
        JSONArray ecosystems=new JSONArray(),dependencies=new JSONArray(),warnings=new JSONArray();
        if(files.containsKey("package.json")){
            JSONObject pkg=null;try{pkg=new JSONObject(files.get("package.json"));}catch(org.json.JSONException invalid){
                ecosystems.put(new JSONObject().put("id","node").put("evidence",new JSONArray().put("package.json")).put("packageManager","invalid_manifest").put("manifestValid",false));
                warnings.put("package.json不是有效JSON对象；请先读取真实文件并按当前SHA修复，再生成依赖、构建或运行方案");
            }
            if(pkg!=null){
                String manager=manager(files,pkg,warnings);
                ecosystems.put(new JSONObject().put("id","node").put("evidence",new JSONArray().put("package.json")).put("packageManager",manager));
                for(String kind:new String[]{"dependencies","devDependencies","optionalDependencies"}){
                    JSONObject values=pkg.optJSONObject(kind);if(values!=null)for(String name:jsonKeys(values))
                        dependencies.put(new JSONObject().put("ecosystem","node").put("name",name).put("spec",String.valueOf(values.get(name))).put("kind",kind).put("sourcePath","package.json"));
                }
            }
        }else if(files.keySet().stream().anyMatch(p->p.endsWith(".js")||p.endsWith(".mjs")||p.endsWith(".cjs")))ecosystems.put(ecosystem("node",files,p->p.endsWith(".js")||p.endsWith(".mjs")||p.endsWith(".cjs")));
        boolean gradle=files.keySet().stream().anyMatch(p->p.equals("build.gradle")||p.equals("build.gradle.kts")||p.equals("settings.gradle")||p.equals("settings.gradle.kts"));
        if(gradle){ecosystems.put(ecosystem("gradle",files,p->p.equals("build.gradle")||p.equals("build.gradle.kts")||p.equals("settings.gradle")||p.equals("settings.gradle.kts")));warnings.put("Gradle会执行项目构建逻辑并可能联网；依赖需明确执行dependencies解析；Android SDK和APK工具链未验证，不等于可在手机构建APK");}
        if(files.keySet().stream().anyMatch(p->p.endsWith(".py"))||files.containsKey("requirements.txt")||files.containsKey("pyproject.toml")){
            JSONObject python=ecosystem("python",files,p->p.endsWith(".py")||p.equals("requirements.txt")||p.equals("pyproject.toml"));
            python.put("dependencyEnvironment",runtimeId.equals("termux")?"compatibility_target_directory":"checkout_venv");
            if(files.containsKey("requirements.txt"))python.put("hashCheckingDeclared",pythonRequirements(files.get("requirements.txt"),dependencies,warnings));
            ecosystems.put(python);
            if(files.containsKey("pyproject.toml"))warnings.put("pyproject.toml已识别；不猜测其构建backend或自动安装包");
        }
        if(!gradle&&files.keySet().stream().anyMatch(p->p.endsWith(".java")))ecosystems.put(ecosystem("java",files,p->p.endsWith(".java")));
        if(files.containsKey("index.html"))ecosystems.put(ecosystem("static",files,p->p.equals("index.html")));
        JSONObject probe=local.handle("probe",new JSONObject()),termux=probe.optJSONObject("termux");
        JSONArray observed=new JSONArray();String probeId="";long newest=-1;
        JSONArray jobs=local.handle("listJobs",new JSONObject()).optJSONArray("termuxJobs");
        if(jobs!=null)for(int i=0;i<jobs.length();i++){
            JSONObject summary=jobs.getJSONObject(i);
            if(!"tool_probe".equals(summary.optString("kind"))||!"completed".equals(summary.optString("status"))||summary.optLong("createdAt")<newest)continue;
            JSONObject receipt=local.handle("getJob",new JSONObject().put("jobId",summary.getString("id")).put("termux",true));
            Object exit=receipt.opt("exitCode");if(!Boolean.TRUE.equals(receipt.opt("receiptReceived"))||!(exit instanceof Integer||exit instanceof Long)||((Number)exit).longValue()!=0||receipt.optBoolean("outputTruncated"))continue;
            observed=new JSONArray();for(String line:receipt.optString("stdout").split("\\R")){
                int equals=line.indexOf('=');if(equals<1)continue;String name=line.substring(0,equals),path=line.substring(equals+1);
                if(Set.of("node","npm","pnpm","python","python3","java","javac","git","gradle").contains(name)&&path.equals("/data/data/com.termux/files/usr/bin/"+name))observed.put(name);
            }
            newest=summary.optLong("createdAt");probeId=receipt.getString("id");
        }
        JSONObject runtime;
        if(runtimeId.equals("termux"))runtime=termux==null?new JSONObject():ProjectSources.copy(termux);
        else{runtime=local.handle("project.runtime.describe",new JSONObject().put("runtimeId",runtimeId));observed=new JSONArray();JSONArray facts=runtime.optJSONArray("tools");for(int i=0;facts!=null&&i<facts.length();i++){JSONObject fact=facts.getJSONObject(i);if(fact.optBoolean("hashVerified")||fact.optBoolean("probeVerified"))observed.put(fact.getString("name"));}probeId="";newest=-1;}
        return new JSONObject().put("checkoutId",checkout).put("projectId",source.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("runtimeId",runtimeId).put("runtime",runtime)
                .put("source",source).put("ecosystems",ecosystems).put("dependencies",dependencies).put("warnings",warnings)
                .put("termux",termux==null?new JSONObject():termux).put("observedTools",observed).put("toolProbeJobId",probeId).put("toolProbeCreatedAt",newest)
                .put("dependencyResolutionComplete",false).put("nativeProgrammingExecution",false).put("untrustedProjectContent",true);
    }
    private static JSONObject ecosystem(String id,Map<String,String> files,java.util.function.Predicate<String> relevant)throws Exception{JSONArray evidence=new JSONArray();for(String path:files.keySet())if(relevant.test(path))evidence.put(path);return new JSONObject().put("id",id).put("evidence",evidence);}
    /** Display declarations, not a second pip resolver or proof of installation. */
    private static boolean pythonRequirements(String source,JSONArray dependencies,JSONArray warnings)throws Exception {
        boolean hashes=false,options=false;JSONObject previous=null;
        java.util.regex.Pattern hashPattern=java.util.regex.Pattern.compile("--hash=(?:sha256|sha384|sha512):[a-fA-F0-9]+"),namePattern=java.util.regex.Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)(.*)$");
        for(String line:source.split("\\R")) {
            String value=line.trim();if(value.isEmpty()||value.startsWith("#"))continue;
            if(value.equals("--require-hashes")){hashes=true;continue;}
            java.util.regex.Matcher hash=hashPattern.matcher(value);int count=0;while(hash.find())count++;
            if(value.startsWith("--hash=")) {if(previous!=null)previous.put("hashCount",previous.optInt("hashCount")+count).put("hashesDeclared",true);hashes|=count>0;continue;}
            if(value.startsWith("-")){options=true;previous=null;continue;}
            java.util.regex.Matcher name=namePattern.matcher(value);
            if(!name.matches()){options=true;previous=null;continue;}
            String spec=name.group(2).split("\\s+--hash=",2)[0].trim();if(spec.endsWith("\\"))spec=spec.substring(0,spec.length()-1).trim();
            previous=new JSONObject().put("ecosystem","python").put("name",name.group(1)).put("spec",spec).put("kind","requirement").put("sourcePath","requirements.txt").put("hashCount",count).put("hashesDeclared",count>0);dependencies.put(previous);hashes|=count>0;
        }
        if(hashes)warnings.put("requirements.txt声明了哈希校验；实际安装仍由已选运行位置的pip执行并返回真实成功或错误，不因声明而视为已安装");
        if(options)warnings.put("requirements.txt含pip选项或嵌套来源；此处不将选项伪装成包，执行前请读取真实声明，最终解析以pip回执为准");
        return hashes;
    }
    private static String manager(Map<String,String> files,JSONObject pkg,JSONArray warnings){
        String declared=pkg.optString("packageManager","").split("@",2)[0];
        boolean npm=files.containsKey("package-lock.json")||files.containsKey("npm-shrinkwrap.json"),pnpm=files.containsKey("pnpm-lock.yaml");
        if(npm&&pnpm||!declared.isEmpty()&&!Set.of("npm","pnpm").contains(declared)||declared.equals("npm")&&pnpm||declared.equals("pnpm")&&npm){warnings.put("包管理器声明/锁文件冲突或暂不支持，请先明确修正");return "unsupported_or_conflicting";}
        return !declared.isEmpty()?declared:pnpm?"pnpm":"npm";
    }
    public static JSONObject create(EngineModule local,JSONObject input)throws Exception {return create(local,input,16000);}
    /** Native private editor only: same reviewed dev-watch contract and immutable plan store,
     * without pretending the model's smaller argument budget can carry a 256KiB source file. */
    public static JSONObject ownerLiveEdit(EngineModule local,ProjectFileDraft.SaveIntent original,String service)throws Exception{
        if(original==null||"absent".equals(original.expectedHash))throw new SecurityException("活服务只能保存已存在的原批准源码文件");JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",original.checkoutId)));if(!original.projectId.equals(source.getString("projectId")))throw new SecurityException("原草稿不属于这个项目副本");
        return create(local,new JSONObject().put("checkoutId",original.checkoutId).put("runtimeId","managed_process_v1").put("intent","edit").put("expectedSourceDigest",source.getString("sourceDigest")).put("path",original.path).put("content",original.text).put("expectedHash",original.expectedHash).put("liveEditServiceExecutionId",identity(service)),262144);
    }
    private static JSONObject create(EngineModule local,JSONObject input,int editByteLimit)throws Exception {
        String checkout=input.getString("checkoutId"),intent=input.getString("intent");
        if(input.has("includePaths")&&!"freeze_package".equals(intent))throw new IllegalArgumentException("includePaths仅属于原freeze_package源码子集，不混入其它操作");
        if(intent.equals(ProjectGit.INTENT))return ProjectGit.create(local,input);
        if(intent.equals("freeze_package"))return freezePackage(local,input);
        if(intent.equals("command"))return command(local,input);
        if(intent.equals("collect"))return collection(local,input);
        if(intent.equals("patch"))return patch(local,input,null,null,null);
        if(!Set.of("prepare","probe","dependencies","build","test","run","edit").contains(intent))throw new IllegalArgumentException("项目动作无效");
        String runtimeId=input.optString("runtimeId","termux");JSONObject inspection=inspect(local,checkout,runtimeId);String digest=inspection.getString("sourceDigest");
        String runMode=input.optString("runMode","foreground");
        if(!Set.of("foreground","owned_service").contains(runMode)||!runMode.equals("foreground")&&(!intent.equals("run")||!runtimeId.equals("managed_process_v1")))throw new IllegalArgumentException("受控开发服务仅适用于明确的本App run方案");
        if(!digest.equals(input.getString("expectedSourceDigest")))throw new SecurityException("项目源码已变化，请重新检查");
        JSONObject snapshot=local.handle("export",new JSONObject().put("checkoutId",checkout));
        if(!digest.equals(ProjectSources.describe(snapshot).getString("sourceDigest")))throw new SecurityException("项目检查期间源码已变化");
        Map<String,String> files=ProjectSources.files(snapshot);JSONArray ecosystems=inspection.getJSONArray("ecosystems");String ecosystem=input.optString("ecosystem","");
        if(!intent.equals("prepare")&&!intent.equals("probe")&&!intent.equals("edit")){
            if(ecosystem.isEmpty()){if(ecosystems.length()!=1)throw new IllegalArgumentException("请明确选择此项目的构建类型");ecosystem=ecosystems.getJSONObject(0).getString("id");}
            boolean present=false;for(int i=0;i<ecosystems.length();i++)present|=ecosystem.equals(ecosystems.getJSONObject(i).getString("id"));if(!present)throw new IllegalArgumentException("项目没有该类型的源码证据");
        }
        boolean devWatch=input.optBoolean("devWatch");
        if(input.has("devWatch")&&!(input.opt("devWatch") instanceof Boolean)||devWatch&&(!intent.equals("run")||!runMode.equals("owned_service")||!ecosystem.equals("node")))throw new IllegalArgumentException("dev-watch需要本机 Node 的明确owned_service run方案");
        boolean retainAfterTurn=input.optBoolean("retainAfterTurn");
        if(input.has("retainAfterTurn")&&!(input.opt("retainAfterTurn") instanceof Boolean)||retainAfterTurn&&!devWatch)throw new IllegalArgumentException("仅明确的本机热改服务可申请模型轮次后保留");
        long serviceTimeout=300000;
        if(retainAfterTurn)serviceTimeout=ProjectCommandSpec.integer(input,"timeoutMillis",-1,1,86400000);
        if(retainAfterTurn&&serviceTimeout<1)throw new IllegalArgumentException("保留开发服务必须明确选择期限；原任务授权更早到期时仍停止");
        if(!retainAfterTurn&&input.has("timeoutMillis"))throw new IllegalArgumentException("普通run期限不变；较长试用需明确保留开发服务");
        JSONObject livePolicy=null;
        if(devWatch){
            JSONObject pkg=new JSONObject(files.getOrDefault("package.json","{}"));JSONObject dependencies=pkg.optJSONObject("devDependencies");
            if(!files.containsKey("tools/dev.mjs")||dependencies==null||!ProjectLiveProjectTemplate.VITE_VERSION.equals(dependencies.optString("vite")))throw new IllegalArgumentException("请先在停止状态审阅并加入正式开发入口 tools/dev.mjs 与固定 Vite "+ProjectLiveProjectTemplate.VITE_VERSION+"，再安装锁定依赖；不会暗中迁移项目");
            livePolicy=ProjectLiveEditPolicy.create(snapshot,input.getJSONArray("liveEditPaths"));
        }
        JSONArray steps=new JSONArray(),warnings=new JSONArray(inspection.getJSONArray("warnings").toString());
        JSONObject base=new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest);
        if(intent.equals("edit")){
            String path=VersionedWorkspace.path(input.getString("path")),content=input.getString("content");byte[] bytes=ProjectExactPatch.encode(content);
            if(bytes.length>editByteLimit||content.indexOf('\0')>=0)throw new IllegalArgumentException("本次副本修改限"+editByteLimit+"字节UTF-8文本；没有截断正文");
            String expected=files.containsKey(path)?ProjectSources.sha256(files.get(path).getBytes(java.nio.charset.StandardCharsets.UTF_8)):"absent";
            if(!expected.equals(input.getString("expectedHash")))throw new SecurityException("文件版本已变化，请先读取当前文件");
            if(!files.containsKey(path)&&inspection.getJSONObject("source").getInt("fileCount")>=ProjectSources.MAX_FILES||inspection.getJSONObject("source").getLong("bytes")-(files.containsKey(path)?files.get(path).getBytes(java.nio.charset.StandardCharsets.UTF_8).length:0)+bytes.length>ProjectSources.MAX_TOTAL_BYTES)throw new IllegalArgumentException("修改后项目超过源码清单上限");
            JSONObject edit=new JSONObject().put("checkoutId",checkout).put("path",path).put("content",content).put("expectedHash",expected);
            if(input.has("liveEditServiceExecutionId")){String service=identity(input.getString("liveEditServiceExecutionId"));if(!runtimeId.equals("managed_process_v1")||expected.equals("absent"))throw new SecurityException("热改只能针对原本机服务已批准的既有文件");edit.put("liveEditServiceExecutionId",service);}
            steps.put(step("edit","writeFile",edit,"修改这一项目副本文件",false));
        }
        else if(intent.equals("probe"))steps.put(step("probe","termux.probe",new JSONObject().put("checkoutId",checkout),runtimeId.equals("termux")?"读取Termux实际工具路径":"探测此手机环境的实际工具和版本",false));
        else{
            boolean staged=false;JSONArray jobs=local.handle("listJobs",new JSONObject()).getJSONArray("termuxJobs");
            for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);Object exit=job.opt("exitCode");staged|=checkout.equals(job.optString("checkoutId"))&&digest.equals(job.optString("sourceDigest"))&&"project_stage".equals(job.optString("kind"))&&"completed".equals(job.optString("status"))&&Boolean.TRUE.equals(job.opt("receiptReceived"))&&(exit instanceof Integer||exit instanceof Long)&&((Number)exit).longValue()==0;}
            if(intent.equals("prepare")||runtimeId.equals("termux")&&!staged){
                JSONObject preparation=ProjectSources.copy(base);boolean managedDownload=!runtimeId.equals("termux")&&inspection.getJSONObject("runtime").optBoolean("preparationAvailable");
                if(managedDownload)preparation.put("preparationCatalog",inspection.getJSONObject("runtime").getJSONObject("preparationEffects"));
                steps.put(step("prepare","termux.prepare",preparation,runtimeId.equals("termux")?"在Termux建立独立源码版本目录":"准备这台手机的开发工具",managedDownload));
            }
            if(!intent.equals("prepare")){
                JSONArray argv=new JSONArray();String executable="";boolean network=false;
                if(ecosystem.equals("node")){
                    JSONObject pkg=files.containsKey("package.json")?new JSONObject(files.get("package.json")):new JSONObject();String manager=manager(files,pkg,warnings);JSONObject scripts=pkg.optJSONObject("scripts");
                    if(manager.equals("unsupported_or_conflicting"))throw new IllegalArgumentException("请先处理包管理器声明/锁文件冲突");
                    if(devWatch){executable="node";argv.put("tools/dev.mjs");network=true;}
                    else if(intent.equals("dependencies")){
                        if(inspection.getJSONArray("dependencies").length()>0){executable=manager;network=true;argv.put(manager.equals("npm")&&(files.containsKey("package-lock.json")||files.containsKey("npm-shrinkwrap.json"))?"ci":"install");if(manager.equals("pnpm")&&files.containsKey("pnpm-lock.yaml"))argv.put("--frozen-lockfile");argv.put("--ignore-scripts");if(manager.equals("npm"))argv.put("--no-audit").put("--no-fund");warnings.put("依赖安装不运行生命周期脚本；若项目需要脚本，必须重新审阅单独的精确计划，不自动放开");}
                    }else if(scripts!=null&&scripts.opt(intent.equals("build")?"build":intent.equals("test")?"test":"start") instanceof String){executable=manager;argv.put("run").put(intent.equals("build")?"build":intent.equals("test")?"test":"start");network=true;}
                    else if(intent.equals("test")){executable="node";argv.put("--test");}
                    else{executable="node";String target=target(files,input.optString("target",""),List.of("main.js","index.js","main.mjs"),".js",".mjs",".cjs");if(intent.equals("build")){argv.put("--check");warnings.put("当前构建仅核对此入口JavaScript语法，不冒充bundler或完整依赖图编译");}argv.put("./"+target);}
                }else if(ecosystem.equals("python")){
                    executable="python3";network=true;
                    warnings.put("Python项目代码可访问所选账号的真实文件与网络；.venv用于隔离依赖，不是权限沙箱");
                    if(intent.equals("dependencies")){if(files.containsKey("requirements.txt")){argv.put("-m").put("pip").put("install").put("--target").put(".pocketworld/python-deps").put("-r").put("requirements.txt").put("--disable-pip-version-check");network=true;}else executable="";}
                    else if(intent.equals("test"))argv.put("-c").put("import sys,unittest;sys.path.insert(0,'.pocketworld/python-deps');r=unittest.TextTestRunner().run(unittest.defaultTestLoader.discover('.'));sys.exit(not r.wasSuccessful())");
                    else{String target=target(files,input.optString("target",""),List.of("main.py","app.py"),".py");if(intent.equals("build")){argv.put("-m").put("py_compile").put("./"+target);warnings.put("Python构建在此仅编译入口字节码；行为需运行test，运行报告需由run实际生成，不伪造构建产物");}else argv.put("-c").put("import runpy,sys;sys.path.insert(0,'.pocketworld/python-deps');runpy.run_path(sys.argv[1],run_name='__main__')").put("./"+target);}
                    if(!runtimeId.equals("termux")&&!executable.isEmpty()) {
                        if(intent.equals("dependencies")) {
                            executable="sh";argv=new JSONArray().put("-eu").put("-c").put("python3 -m venv .venv\nexport VIRTUAL_ENV=\"$PWD/.venv\"\nexport PATH=\"$VIRTUAL_ENV/bin:$PATH\"\n.venv/bin/python -m pip install -r requirements.txt --disable-pip-version-check");
                        } else {
                            if(intent.equals("test"))argv=new JSONArray().put("-m").put("unittest").put("discover").put(".");
                            else if(intent.equals("run"))argv=new JSONArray().put("./"+target(files,input.optString("target",""),List.of("main.py","app.py"),".py"));
                            StringBuilder script=new StringBuilder("if [ -x .venv/bin/python ]; then export VIRTUAL_ENV=\"$PWD/.venv\"; export PATH=\"$VIRTUAL_ENV/bin:$PATH\"; py=.venv/bin/python; else py=python3; fi\nexec \"$py\" ");
                            for(int i=0;i<argv.length();i++)script.append(ProjectSources.quote(argv.getString(i))).append(' ');
                            executable="sh";argv=new JSONArray().put("-eu").put("-c").put(script.toString());
                        }
                    }
                }else if(ecosystem.equals("java")){
                    if(intent.equals("build")){executable="javac";argv.put("-d").put("build/classes");for(String path:files.keySet())if(path.endsWith(".java"))argv.put("./"+path);if(argv.length()>38)throw new IllegalArgumentException("请将超过36个源文件的Java项目交给明确构建工具");}
                    else if(intent.equals("run")){executable="java";String target=target(files,input.optString("target",""),List.of("Main.java"),".java"),name=target.substring(target.lastIndexOf('/')+1,target.length()-5);java.util.regex.Matcher pkg=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w.]*)\\s*;").matcher(files.get(target));if(pkg.find())name=pkg.group(1)+"."+name;argv.put("-cp").put("build/classes").put(name);}else if(intent.equals("test"))throw new IllegalArgumentException("此独立Java项目尚未声明测试runner，请选择明确的Gradle测试项目");
                }else if(ecosystem.equals("gradle")){
                    if(intent.equals("run"))throw new IllegalArgumentException("Gradle运行task需在项目内明确声明；不猜测APK安装或启动");
                    executable="gradle";argv.put("--no-daemon").put(intent.equals("dependencies")?"dependencies":intent.equals("test")?"test":"build");network=true;
                }else throw new IllegalArgumentException("静态HTML无需编译；请使用明确的受限预览，不伪造构建成功");
                if(!executable.isEmpty()){
                    JSONObject payload=ProjectSources.copy(base).put("executable",executable).put("argv",argv);
                    if(runMode.equals("owned_service")){payload.put("runMode",runMode).put("timeoutMillis",serviceTimeout);
                        if(retainAfterTurn)payload.put("retainAfterTurn",true);
                        if(devWatch){ProjectLiveEditPolicy.putOnPayload(payload,livePolicy);warnings.put("本次明确批准原run内既有源码热改：所列文件的后续SHA-CAS修改会被正在运行的服务继续处理/执行；配置、锁、工具脚本、环境、新文件、第二执行不在该许可内。仍需真实HTTP/WS核对；仅明确选择保留时模型轮次结束可继续试用，期限、原任务停止或授权撤销仍停止并释放，不是持久或公网服务");}
                        else warnings.put("本次启动受控开发服务：仅在原执行轮次内监督，启动确认不代表服务就绪或业务完成；本次批准包含明确停止此原job。服务存活时只能读取/分析/生成方案，不能同时改写此项目；本轮结束必须停止并核对资源释放，不承诺持久服务或公网访问");}
                    steps.put(step(intent,"termux.run",payload,runMode.equals("owned_service")?(retainAfterTurn?"启动并按批准期限保留开发服务（可随时停止）":"启动受控开发服务（本轮内可明确停止）"):intent.equals("build")?"构建/检查此源码版本":intent.equals("test")?"运行实际项目测试":intent.equals("run")?"运行项目代码":"安装/解析项目依赖",network));
                }
            }
        }
        JSONObject termux=inspection.getJSONObject("runtime");boolean available=runtimeId.equals("termux")?termux.optBoolean("installed")&&termux.optBoolean("permissionGranted")&&termux.optBoolean("enabled"):termux.optBoolean("canExecute");
        JSONArray observed=inspection.getJSONArray("observedTools");Set<String> tools=new java.util.HashSet<>();for(int i=0;i<observed.length();i++)tools.add(observed.getString(i));
        for(int i=0;i<steps.length();i++){JSONObject request=steps.getJSONObject(i).getJSONObject("payload");if(request.has("executable")&&!tools.contains(request.getString("executable")))available=false;}
        if(intent.equals("edit"))available=true;
        if(intent.equals("prepare")&&!runtimeId.equals("termux"))available=termux.optBoolean("preparationAvailable");
        if(!runtimeId.equals("termux"))for(int i=0;i<steps.length();i++){JSONObject item=steps.getJSONObject(i);if(item.getString("localCommand").startsWith("termux."))item.put("localCommand",item.getString("localCommand").replace("termux.","project.runtime."));item.getJSONObject("effects").put("runtime",item.getString("localCommand").equals("writeFile")?"app_private_checkout":runtimeId);}
        if(!available)warnings.put("所选工具环境或实际工具探测尚未确认；本步骤显示真实缺口，不自动换到其他执行位置");
        JSONObject core=new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId",inspection.getString("projectId"))
                .put("sourceDigest",digest).put("sourceManifest",inspection.getJSONObject("source").getJSONArray("files")).put("runtimeId",runtimeId).put("runtimeIdentity",inspection.getJSONObject("runtime").optString("identity")).put("intent",intent).put("ecosystem",ecosystem).put("steps",steps).put("available",available).put("warnings",warnings).put("automaticChaining",false);
        if(runMode.equals("owned_service"))core.put("runMode",runMode);
        return seal(core);
    }
    /** The original package codec/catalog is the only producer; planning never publishes bytes. */
    private static JSONObject freezePackage(EngineModule local,JSONObject input)throws Exception {
        String checkout=identity(input.getString("checkoutId")),runtime=input.optString("runtimeId","termux"),mode=input.optString("packageMode","source_project");
        if(!Set.of("source_project","web_output").contains(mode)||input.has("includeGeneratedRoots")&&!(input.opt("includeGeneratedRoots") instanceof Boolean))throw new IllegalArgumentException("请选择源码项目包或固定网页成果及明确包含范围");
        for(String key:new String[]{"executable","argv","env","cwd","content","edits","runMode","terminal","devWatch","liveEditServiceExecutionId"})if(input.has(key))throw new IllegalArgumentException("项目包方案不能混合执行、修改或热改");
        JSONObject selectionInput=new JSONObject().put("checkoutId",checkout).put("mode",mode).put("entryPath",input.optString("entryPath","")).put("includeGeneratedRoots",mode.equals("web_output")||input.optBoolean("includeGeneratedRoots")).put("expectedSourceDigest",input.getString("expectedSourceDigest"));
        if(input.has("includePaths")){if(!"source_project".equals(mode))throw new IllegalArgumentException("includePaths仅用于选定源码子集，不混用固定网页资源根");selectionInput.put("includePaths",ProjectPackageArchive.normalizedSelection(input.getJSONArray("includePaths")));}
        JSONObject selection=local instanceof dev.pocketengine.features.local.LocalModule?((dev.pocketengine.features.local.LocalModule)local).projectPackageSelectionForHost(selectionInput):local.handle("project.package.plan",selectionInput);
        JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout)));
        if(!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest"))||!source.getString("sourceDigest").equals(selection.getString("sourceDigest")))throw new SecurityException("项目在准备成果方案时变化，请重新读取");
        JSONObject payload=new JSONObject(selectionInput.toString()).put("expectedManifestIdentity",selection.getString("manifestIdentity")).put("expectedArchiveSha256",selection.getString("archiveSha256")).put("expectedArchiveBytes",selection.getLong("archiveBytes"));
        JSONObject action=step("freeze_package","freezeProjectPackage",payload,input.has("includePaths")?"保存选定源码文件/目录子集（不是完整项目）":"保存这份完整项目包或固定网页成果",false);action.getJSONObject("effects").put("runtime","app_private_checkout").put("writesCheckout",false).put("publishesImmutableArtifact",true).put("automaticallyExported",false);
        JSONObject core=new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId",source.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("runtimeId",runtime).put("runtimeIdentity",local.handle("project.runtime.describe",new JSONObject().put("runtimeId",runtime)).optString("identity")).put("intent","freeze_package").put("ecosystem","immutable_project_package").put("steps",new JSONArray().put(action)).put("packageManifest",selection.getJSONObject("manifest")).put("available",true).put("automaticChaining",false).put("warnings",new JSONArray().put("保存前须停止并确认释放此副本的进程；包只包含实际选中字节，排除环境/依赖/已知凭据，不证明构建成功。不会自动导出、分享、运行或覆盖原项目。未知发布只查询原操作，不再次保存。"));
        JSONObject sealed=seal(core);sealed.getJSONArray("steps").getJSONObject(0).remove("hostAction");return sealed;
    }
    private static void verifyFreeze(JSONObject core,JSONArray steps)throws Exception {
        if(steps.length()!=1||!"immutable_project_package".equals(core.optString("ecosystem"))||!Boolean.FALSE.equals(core.opt("automaticChaining")))throw new SecurityException("固定成果需要单一原生文件保存方案");
        JSONObject step=steps.getJSONObject(0),payload=step.getJSONObject("payload"),effects=step.getJSONObject("effects"),manifest=core.getJSONObject("packageManifest"),source=manifest.getJSONObject("source");
        if(!"freeze_package".equals(step.optString("id"))||!"freeze_package".equals(step.optString("stepId"))||!"freezeProjectPackage".equals(step.optString("localCommand"))||!Boolean.TRUE.equals(step.opt("approvalRequired"))||!Boolean.FALSE.equals(step.opt("cancelReliable"))||!"app_private_checkout".equals(effects.optString("runtime"))||!Boolean.TRUE.equals(effects.opt("writes"))||!Boolean.FALSE.equals(effects.opt("networkMayBeUsed"))||!Boolean.FALSE.equals(effects.opt("writesCheckout"))||!Boolean.TRUE.equals(effects.opt("publishesImmutableArtifact"))||!Boolean.FALSE.equals(effects.opt("automaticallyExported")))throw new SecurityException("固定包不能混入进程、导出或副本写入效果");
        java.util.Set<String> allowedPayload=new java.util.TreeSet<>(Set.of("checkoutId","expectedSourceDigest","mode","entryPath","includeGeneratedRoots","expectedManifestIdentity","expectedArchiveSha256","expectedArchiveBytes"));if(payload.has("includePaths")){allowedPayload.add("includePaths");if(!"source_project".equals(payload.optString("mode"))||!ProjectSources.canonical(payload.getJSONArray("includePaths")).equals(ProjectSources.canonical(ProjectPackageArchive.normalizedSelection(payload.getJSONArray("includePaths")))))throw new SecurityException("所选源码子集路径不准确，不能静默变完整项目或网页根");}
        if(payload.has("includePaths"))ProjectPackageArchive.requireSelectionManifest(manifest,payload.getJSONArray("includePaths"));
        if(!jsonKeys(payload).equals(allowedPayload))throw new SecurityException("固定包含未审阅参数");
        for(String key:new String[]{"projectId","checkoutId","sourceDigest"})if(!core.getString(key).equals(source.getString(key)))throw new SecurityException("完整包清单不属于本方案项目版本");
        Object bytes=payload.opt("expectedArchiveBytes");if(!(bytes instanceof Integer||bytes instanceof Long)||((Number)bytes).longValue()<1||((Number)bytes).longValue()>ProjectPackageArchive.MAX_ARCHIVE_BYTES||!payload.optString("expectedArchiveSha256").matches("[a-f0-9]{64}")||!payload.getString("expectedManifestIdentity").equals(ProjectSources.digest(manifest))||!payload.getString("checkoutId").equals(core.getString("checkoutId"))||!payload.getString("expectedSourceDigest").equals(core.getString("sourceDigest")))throw new SecurityException("固定包字节或源码绑定不完整");
        if(!ProjectPackageArchive.FORMAT.equals(manifest.optString("format"))||!Set.of("source_project","web_output").contains(payload.getString("mode"))||!payload.getString("mode").equals(manifest.getString("mode"))||!payload.getString("entryPath").equals(manifest.getString("entryPath"))||!(payload.opt("includeGeneratedRoots") instanceof Boolean)||payload.getBoolean("includeGeneratedRoots")!=manifest.getJSONObject("policy").getBoolean("generatedRootsIncluded")||!manifest.getJSONObject("policy").getBoolean("privateFilesExcluded")||manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>ProjectPackageArchive.MAX_MANIFEST_BYTES)throw new SecurityException("固定包范围与完整原清单不同");
    }
    /** One exact command over the same active checkout, native tool map and immutable plan store. */
    private static JSONObject command(EngineModule local,JSONObject input)throws Exception {
        for(String field:new String[]{"ecosystem","target","path","content","expectedHash","edits","devWatch","liveEditPaths","liveEditServiceExecutionId","artifactSelection"})if(input.has(field))throw new IllegalArgumentException("精确命令方案不能混合构建入口、文件修改、热改或成果收集字段");
        String checkout=identity(input.getString("checkoutId")),runtime=input.optString("runtimeId","termux");
        if(!runtime.equals("managed_process_v1"))throw new IllegalArgumentException("通用命令只在本App已选的受控运行环境执行，不切换到Termux或其他宿主");
        JSONObject inspection=inspect(local,checkout,runtime),source=inspection.getJSONObject("source"),facts=inspection.getJSONObject("runtime");
        if(!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest")))throw new SecurityException("项目源码已变化，未生成旧版本命令方案");
        JSONObject payload=ProjectCommandSpec.normalize(input).put("checkoutId",checkout).put("expectedSourceDigest",source.getString("sourceDigest")).put("genericCommandVersion",1);
        boolean pty=payload.getString("terminalMode").equals("pty"),available=facts.optBoolean("canExecute"),found=false;JSONArray tools=inspection.getJSONArray("observedTools");
        for(int i=0;i<tools.length();i++)found|=payload.getString("executable").equals(tools.getString(i));available&=found;
        if(pty){available&=facts.optBoolean("ptyAvailable");payload.put("terminalNativeContract",dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);}
        JSONObject action=step("command","project.runtime.run",payload,pty?"启动本轮交互终端（含限定后续输入）":"执行这一精确项目命令",true);action.getJSONObject("effects").put("runtime",runtime).put("writes",true).put("networkMayBeUsed",true).put("argumentTransport","literal_argv").put("scopeEnforced",false).put("interactiveInput",pty);
        JSONArray warnings=new JSONArray().put("argv逐项原样传入，不进行Shell拆词；sh等解释器仅执行上面明确写出的参数。命令和项目代码仍可访问当前App账号可见的文件与网络，不是UID隔离沙箱。普通env不接受路径、加载器、解释器注入或凭据变量，不要在参数/输入中放密码和令牌。");
        if(pty)warnings.put("本次启动批准明确包括原execution在本run内有限文字输入、canonical模式EOF控制符和窗口调整，输入会继续触发该程序执行。stdout/stderr在PTY合流；EOF不是强行关闭任意raw输入。任务结束必须停止并取得原session/进程组/资源释放证据；不能凭旧PID恢复输入或信号。未观察到的自行脱离session后代不属于OS沙箱保证。");
        if(!available)warnings.put("所选原工具或PTY实际接口尚未准备，本步骤未派发，不会换用其他执行环境");
        return seal(new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId",source.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("runtimeId",runtime).put("runtimeIdentity",facts.optString("identity")).put("intent","command").put("ecosystem","native_command").put("runMode",payload.getString("runMode")).put("steps",new JSONArray().put(action)).put("available",available).put("warnings",warnings).put("automaticChaining",false));
    }
    /** Native caller supplies original immutable evidence for a conditional undo, never model JSON. */
    public static JSONObject revert(EngineModule local,JSONObject input,JSONObject originalPlan,JSONObject originalReceipt,String operation)throws Exception {
        return patch(local,input,ProjectBuildPlan.verified(originalPlan),originalReceipt,operation);
    }
    private static JSONObject patch(EngineModule local,JSONObject input,JSONObject originalPlan,JSONObject originalReceipt,String operation)throws Exception {
        String checkout=identity(input.getString("checkoutId")),runtime=input.optString("runtimeId","termux");JSONObject snapshot=local.handle("export",new JSONObject().put("checkoutId",checkout)),source=ProjectSources.describe(snapshot);
        if(!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest")))throw new SecurityException("补丁源码版本已变化，未生成旧版本方案");
        JSONArray files;
        if(originalPlan==null){JSONArray edits=input.getJSONArray("edits");boolean directory=false;for(int i=0;i<edits.length();i++)directory|="mkdir".equals(edits.getJSONObject(i).optString("action"));if(directory){if(edits.length()!=1||!(local instanceof dev.pocketengine.features.local.LocalModule))throw new IllegalArgumentException("mkdir需独占一次原native工作副本补丁，不混合文件/命令");files=((dev.pocketengine.features.local.LocalModule)local).prepareProjectDirectoryForHost(checkout,edits.getJSONObject(0));}else files=ProjectExactPatch.prepare(snapshot,edits);}
        else {if(!source.getString("projectId").equals(originalPlan.getString("projectId"))||!checkout.equals(originalPlan.getString("checkoutId")))throw new SecurityException("原修改不是这个项目副本");JSONObject originalStep=step(originalPlan,originalReceipt.getJSONObject("projectExecution").getString("stepId"));if(!"patchFiles".equals(originalStep.getString("localCommand")))throw new SecurityException("此撤销入口仅支持原精确文件补丁");files=ProjectExactPatch.reverse(snapshot,originalStep.getJSONObject("payload").getJSONArray("files"),originalReceipt,operation);}
        if(ProjectFileMove.any(files)&&local instanceof dev.pocketengine.features.local.LocalModule)((dev.pocketengine.features.local.LocalModule)local).verifyProjectMoveForHost(checkout,files);
        String intent=originalPlan==null?"patch":"revert_operation";JSONObject payload=new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",source.getString("sourceDigest")).put("files",files);
        if(operation!=null)payload.put("revertsOperationId",identity(operation)).put("reversalScope",ProjectExactPatch.reversalScope(originalReceipt));JSONObject action=step(intent,"patchFiles",payload,originalPlan==null?"按当前SHA应用精确文件补丁":"只撤回原确证applied文件；afterSHA仍相同才恢复原字节，失败/跳过文件不动",false);action.getJSONObject("effects").put("runtime","app_private_checkout").put("crossFileAtomic",false).put("fileCount",files.length());for(int f=0;f<files.length();f++)if(ProjectByteDelete.matches(files.getJSONObject(f))){JSONObject deleted=files.getJSONObject(f);action.put("label",("restore".equals(deleted.getString("deleteDirection"))?"仅恢复原确证删除的完整原字节：":"删除当前工作副本完整原字节：")+deleted.getString("path")+"（不动正式版本库）");}if(ProjectFileMove.any(files)){JSONObject move=ProjectFileMove.pair(files);action.put("label",(originalPlan==null?"重命名当前工作副本资源：":"按原两路径/afterSHA条件重命名回：")+move.getString("fromPath")+" → "+move.getString("toPath")+"（不动正式版本库）");}if(ProjectDirectoryCreate.any(files)){JSONObject directory=ProjectDirectoryCreate.descriptor(files);action.put("label","创建当前工作副本目录："+directory.getString("path")+(directory.getBoolean("createParents")?"（明确包括缺失父目录）":"（仅单层，父目录须已有）"));action.getJSONObject("effects").put("fileCount",0).put("resourceKind","directory").put("directoryCount",directory.getJSONArray("missingPaths").length());}
        return seal(new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId",source.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("runtimeId",runtime).put("runtimeIdentity",local.handle("project.runtime.describe",new JSONObject().put("runtimeId",runtime)).optString("identity")).put("intent",intent).put("ecosystem","exact_source_patch").put("steps",new JSONArray().put(action)).put("available",true).put("warnings",new JSONArray().put(ProjectDirectoryCreate.any(files)?"只创建此次列出的目录；createParents缺省false，只有明确true才逐个建缺失父目录。既有准确目录返回already_exists，不覆盖普通文件/链接。目录有独立native元数据pin，不把directory冒文件SHA；进入mkdir后异常保留原unknown及已观察createdPaths，不自动回滚、重放或rmdir。此入口不自动撤销目录、不写正式版本库。":ProjectFileMove.any(files)?"先核对源SHA、目标不存在及已有父目录，再一次不带REPLACE的Files.move；未宣称跨路径原子CAS。进入rename后异常保unknown不重放；两路径确证后，原afterSHA仍相同且旧源仍absent，新批准才可重命名回。":"全部文件先核对再按文件原子替换；这不是跨文件事务。每个文件有实际applied/failed/unknown/skipped回执，未知不重放。撤销也是新一次原操作与明确批准，只在afterSHA未被后续修改时恢复。")).put("automaticChaining",false));
    }
    private static JSONObject collection(EngineModule local,JSONObject input)throws Exception {
        if(ProjectManagedOutput.selection(input.optJSONObject("artifactSelection"))){
            JSONObject selection=ProjectManagedOutput.verified(input.getJSONObject("artifactSelection")),source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",input.getString("checkoutId"))));
            if(!input.getString("checkoutId").equals(selection.getString("checkoutId"))||!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest"))||!source.getString("sourceDigest").equals(selection.getString("sourceDigest")))throw new SecurityException("原managed生成文件选择已不属于当前源码");
            JSONObject runtime=local.handle("project.runtime.describe",new JSONObject().put("runtimeId","managed_process_v1"));if(!runtime.optBoolean("managedOutputCollectionAvailable")||!runtime.optString("identity").equals(selection.getString("runtimeIdentity")))throw new SecurityException("原managed构建环境已变化");
            JSONObject payload=new JSONObject().put("checkoutId",input.getString("checkoutId")).put("expectedSourceDigest",source.getString("sourceDigest")).put("artifactSelection",selection),action=step("collect",ProjectManagedOutput.COMMAND,payload,"保存原成功构建的选定生成文件",false);action.getJSONObject("effects").put("runtime","app_private_checkout").put("writesCheckout",false).put("publishesImmutableArtifact",true).put("automaticallyExported",false);
            return seal(new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",selection.getString("checkoutId")).put("projectId",selection.getString("projectId")).put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("runtimeId","managed_process_v1").put("runtimeIdentity",selection.getString("runtimeIdentity")).put("intent","collect").put("ecosystem","managed_selected_output").put("steps",new JSONArray().put(action)).put("available",true).put("warnings",selection.getJSONArray("warnings")).put("automaticChaining",false));
        }
        JSONObject selection=GeneratedArtifacts.verified(input.getJSONObject("artifactSelection")),source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",input.getString("checkoutId"))));
        if(!input.getString("checkoutId").equals(selection.getString("checkoutId"))||!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest"))||!source.getString("sourceDigest").equals(selection.getString("sourceDigest")))throw new SecurityException("回收选择与当前项目源码不同");
        JSONObject payload=new JSONObject().put("checkoutId",input.getString("checkoutId")).put("expectedSourceDigest",source.getString("sourceDigest")).put("artifactSelection",selection);
        JSONArray steps=new JSONArray().put(step("collect","termux.collect",payload,"回收这一构建的选定文本产物",false));
        return seal(new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",input.getString("checkoutId")).put("projectId",source.getString("projectId"))
                .put("sourceDigest",source.getString("sourceDigest")).put("sourceManifest",source.getJSONArray("files")).put("intent","collect").put("ecosystem","selected_text_artifact").put("steps",steps)
                .put("available",true).put("warnings",selection.getJSONArray("warnings")).put("automaticChaining",false));
    }
    static JSONObject seal(JSONObject core)throws Exception {
        String seal=ProjectSources.digest(core);core.put("planId",seal).put("planDigest",seal);JSONObject clean=ProjectSources.copy(core);JSONArray steps=core.getJSONArray("steps");
        for(int i=0;i<steps.length();i++){JSONObject item=steps.getJSONObject(i);JSONObject payload=ProjectSources.copy(item.getJSONObject("payload")).put("projectPlan",ProjectSources.copy(clean)).put("projectStepId",item.getString("id"));item.put("hostAction",new JSONObject().put("feature","local").put("command",item.getString("localCommand")).put("payload",payload));}
        return core;
    }
    private static JSONObject step(String id,String command,JSONObject payload,String label,boolean network)throws Exception {
        return new JSONObject().put("id",id).put("stepId",id).put("localCommand",command).put("payload",payload).put("label",label).put("approvalRequired",true)
                .put("effects",new JSONObject().put("writes",!command.equals("termux.probe")).put("networkMayBeUsed",network).put("scopeEnforced",false).put("runtime",command.equals("writeFile")?"app_private_checkout":"termux")).put("cancelReliable",false);
    }
    private static String target(Map<String,String> files,String requested,List<String> preferred,String... suffixes){
        if(!requested.isEmpty()){VersionedWorkspace.path(requested);if(!files.containsKey(requested))throw new IllegalArgumentException("入口不在此源码快照");for(String suffix:suffixes)if(requested.endsWith(suffix))return requested;throw new IllegalArgumentException("入口类型不匹配");}
        for(String path:preferred)if(files.containsKey(path))return path;
        List<String> candidates=new ArrayList<>();for(String path:files.keySet())for(String suffix:suffixes)if(path.endsWith(suffix))candidates.add(path);
        if(candidates.size()!=1)throw new IllegalArgumentException("请明确选择项目入口文件");return candidates.get(0);
    }
    public static JSONObject verified(JSONObject plan)throws Exception {
        JSONObject core=ProjectSources.copy(plan);core.remove("planId");core.remove("planDigest");JSONArray steps=core.getJSONArray("steps");
        for(int i=0;i<steps.length();i++)steps.getJSONObject(i).remove("hostAction");
        if(!"pocketworld.project-plan.v1".equals(core.getString("format"))||!ProjectSources.digest(core).equals(plan.getString("planDigest"))||!plan.getString("planId").equals(plan.getString("planDigest"))||!core.getString("sourceDigest").equals(ProjectSources.manifestDigest(core.getJSONArray("sourceManifest"))))throw new SecurityException("计划内容或源码清单已变化，请重新审阅");
        for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(!"patchFiles".equals(step.optString("localCommand")))continue;JSONArray files=step.getJSONObject("payload").getJSONArray("files");for(int f=0;f<files.length();f++)if(ProjectExactPatch.encodedBytes(files.getJSONObject(f))){JSONObject file=files.getJSONObject(f);ProjectExactPatch.payloadBytes(file,false);if(ProjectByteCopy.matches(file))ProjectByteCopy.requirePlanSource(core,steps.getJSONObject(i),file);if(ProjectByteDelete.matches(file))ProjectByteDelete.requirePlanSource(core,step,file);}if(ProjectFileMove.any(files))ProjectFileMove.requireManifest(core,step,files);if(ProjectDirectoryCreate.any(files))ProjectDirectoryCreate.requirePlan(core,step,files);for(int f=0;f<files.length();f++)if("mkdir".equals(files.getJSONObject(f).optString("action"))&&!ProjectDirectoryCreate.matches(files.getJSONObject(f)))throw new SecurityException("目录动作缺少准确typed原pin");}
        if("freeze_package".equals(core.optString("intent")))verifyFreeze(core,steps);
        if("command".equals(core.optString("intent")))verifyCommand(core,steps);
        if("managed_selected_output".equals(core.optString("ecosystem")))verifyManagedOutput(core,steps);
        if(ProjectGit.INTENT.equals(core.optString("intent")))ProjectGit.verify(core,steps);
        return core.put("planId",plan.getString("planId")).put("planDigest",plan.getString("planDigest"));
    }
    private static void verifyManagedOutput(JSONObject core,JSONArray steps)throws Exception {
        if(!"collect".equals(core.getString("intent"))||!"managed_process_v1".equals(core.getString("runtimeId"))||steps.length()!=1||!Boolean.FALSE.equals(core.opt("automaticChaining")))throw new SecurityException("原managed生成成果必须单一精确文件保存");JSONObject action=steps.getJSONObject(0),payload=action.getJSONObject("payload"),effects=action.getJSONObject("effects");
        if(!jsonKeys(payload).equals(new TreeSet<>(Set.of("checkoutId","expectedSourceDigest","artifactSelection")))||!ProjectManagedOutput.COMMAND.equals(action.getString("localCommand"))||!"collect".equals(action.getString("id"))||!"collect".equals(action.getString("stepId"))||!Boolean.TRUE.equals(action.opt("approvalRequired"))||!Boolean.FALSE.equals(action.opt("cancelReliable"))||!Boolean.FALSE.equals(effects.opt("networkMayBeUsed"))||!Boolean.FALSE.equals(effects.opt("writesCheckout"))||!Boolean.TRUE.equals(effects.opt("publishesImmutableArtifact"))||!Boolean.FALSE.equals(effects.opt("automaticallyExported"))||!"app_private_checkout".equals(effects.optString("runtime")))throw new SecurityException("生成文件动作不能混入执行、网络或副本写入");JSONObject selected=ProjectManagedOutput.verified(payload.getJSONObject("artifactSelection"));for(String field:new String[]{"projectId","checkoutId","sourceDigest","runtimeId","runtimeIdentity"})if(!core.getString(field).equals(selected.getString(field)))throw new SecurityException("生成文件与原项目或工具环境不同");if(!core.getString("checkoutId").equals(payload.getString("checkoutId"))||!core.getString("sourceDigest").equals(payload.getString("expectedSourceDigest")))throw new SecurityException("生成文件原源码绑定不同");
    }
    private static void verifyCommand(JSONObject core,JSONArray steps)throws Exception {
        if(!"managed_process_v1".equals(core.getString("runtimeId"))||!"native_command".equals(core.getString("ecosystem"))||steps.length()!=1||!Boolean.FALSE.equals(core.opt("automaticChaining")))throw new SecurityException("精确命令必须是本App原运行环境中的单步骤方案");
        JSONObject action=steps.getJSONObject(0),payload=action.getJSONObject("payload"),effects=action.getJSONObject("effects");
        JSONObject spec=ProjectCommandSpec.fromPayload(payload);boolean pty="pty".equals(spec.getString("terminalMode"));
        if(!"command".equals(action.getString("id"))||!"command".equals(action.getString("stepId"))||!"project.runtime.run".equals(action.getString("localCommand"))||!Boolean.TRUE.equals(action.opt("approvalRequired"))||!Boolean.FALSE.equals(action.opt("cancelReliable"))||!Integer.valueOf(1).equals(payload.opt("genericCommandVersion"))||!core.getString("checkoutId").equals(payload.getString("checkoutId"))||!core.getString("sourceDigest").equals(payload.getString("expectedSourceDigest"))||!core.getString("runMode").equals(spec.getString("runMode")))throw new SecurityException("精确命令步骤或原源码绑定不完整");
        if(!"managed_process_v1".equals(effects.getString("runtime"))||!Boolean.TRUE.equals(effects.opt("writes"))||!Boolean.TRUE.equals(effects.opt("networkMayBeUsed"))||!Boolean.FALSE.equals(effects.opt("scopeEnforced"))||!Boolean.valueOf(pty).equals(effects.opt("interactiveInput"))||!"literal_argv".equals(effects.optString("argumentTransport")))throw new SecurityException("精确命令不能自称只读、无网络或权限沙箱");
        if(pty&&!dev.pocketengine.features.local.runtime.NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(payload.getString("terminalNativeContract")))throw new SecurityException("交互终端接口版本已变化，请重新审阅");
        java.util.Set<String> expected=new java.util.HashSet<>();for(var keys=spec.keys();keys.hasNext();)expected.add(keys.next());expected.addAll(Set.of("checkoutId","expectedSourceDigest","genericCommandVersion"));if(pty)expected.add("terminalNativeContract");
        if(!expected.equals(jsonKeys(payload)))throw new SecurityException("精确命令含有未审阅的额外执行字段");
    }
    public static JSONObject step(JSONObject plan,String id)throws Exception {JSONArray steps=plan.getJSONArray("steps");for(int i=0;i<steps.length();i++)if(id.equals(steps.getJSONObject(i).getString("id")))return ProjectSources.copy(steps.getJSONObject(i));throw new IllegalArgumentException("计划没有此步骤");}
    /** Only the original tracked owner command can supply these native-injected task/run fields. */
    public static JSONObject ownerLink(JSONObject input,String command)throws Exception {
        if(!input.has("projectPlan"))return null;
        JSONObject plan=verified(input.getJSONObject("projectPlan")),step=step(plan,input.getString("projectStepId"));
        if(!command.equals(step.getString("localCommand")))throw new SecurityException("项目步骤与真实命令不匹配");
        JSONObject payload=step.getJSONObject("payload");for(String key:jsonKeys(payload))if(!ProjectSources.canonical(payload.get(key)).equals(ProjectSources.canonical(input.opt(key))))throw new SecurityException("审阅后的项目执行参数已变化");
        String task=identity(input.optString("_taskId")),run=identity(input.optString("_runId")),operation=identity(input.optString("operationId"));
        if(!run.equals(operation))throw new SecurityException("主人执行必须来自原生tracked run，不能自填操作编号");
        return new JSONObject().put("planDigest",plan.getString("planDigest")).put("stepId",step.getString("id")).put("sourceDigest",plan.getString("sourceDigest"))
                .put("planIntent",plan.getString("intent")).put("intent",step.getString("id")).put("hostTaskId",task).put("runId",run).put("operationId",operation).put("requestDigest",ProjectSources.digest(payload)).put("authority","owner_tracked_job");
    }
    public static String identity(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("原生任务/操作身份无效");return value;}
    private static java.util.SortedSet<String> jsonKeys(JSONObject value) {
        java.util.SortedSet<String> keys = new java.util.TreeSet<>();
        java.util.Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

}
