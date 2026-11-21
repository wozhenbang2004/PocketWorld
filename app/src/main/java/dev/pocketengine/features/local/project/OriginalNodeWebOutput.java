package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.preview.ViteOutputMount;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One owner-selected static deliverable. Not a model tool, build, device operation or preview grant. */
public final class OriginalNodeWebOutput {
    public static final String TOOL="owner_keep_first_node_web_output", DIRECT_TOOL="owner_keep_direct_node_web_output", ENTRY="dist/index.html";
    public static final String DIRECT_ROUTE="directOwnerNodeWebVersion", DIRECT_ARTIFACT_SOURCE="owner_direct_vite_work";
    public static boolean directArchiveMetadata(JSONObject artifact){return DIRECT_ARTIFACT_SOURCE.equals(artifact.opt("source"));}
    private static final String DIRECT_FORMAT="pocketworld.owner-native-node-web-version.v1";
    public static final String PENDING="owner_web_output_pending", NOT_PUBLISHED="owner_web_output_not_published";
    public static final String WITNESS="firstNodeWebOutput", EDITED_ROUTE="editedNodeWebVersion", EXISTING_ROUTE="existingNodeWebVersion";
    public static final String OUTPUT_LIMITATION="firstNodeWebOutputLimitation", NESTED_VITE_OUTPUT="nested_vite_output";
    private static final String EDITED_TREES="editedOutputTrees", EXISTING_TREES="existingOutputTrees";
    private static final Set<String> BINDING=Set.of("taskId","runId","epoch","buildOperationId","buildReceiptIdentity",
        "checkoutSequence","projectId","checkoutId","sourceDigest","planDigest","runtimeIdentity","operationId");
    private static final Set<String> WITNESS_KEYS=Set.of("taskId","runId","epoch","buildOperationId","checkoutSequence",
        "projectId","checkoutId","sourceDigest","planDigest","runtimeIdentity","entryPath","manifestIdentity","archiveBytes","archiveSha256");
    private static final Pattern SCRIPT=Pattern.compile("app-[A-Z0-9]{8}\\.js"), STYLE=Pattern.compile("app-[A-Z0-9]{8}\\.css"),
        ICON=Pattern.compile("mark-[A-Z0-9]{8}\\.svg");
    private static final String VITE="vite_fresh_dist_v1", STOCK="stock_web_v1";
    static final int VITE_FILES=64, VITE_BYTES=8*1024*1024, VITE_ENTRIES=96;
    private static final Pattern VITE_LEAF=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,159}");
    private static final Pattern VITE_ENTRY=Pattern.compile(
        "<script\\s+type=\"module\"(?:\\s+crossorigin(?:=\"\")?)?\\s+src=\"((?:/|\\./)assets/[A-Za-z0-9._-]+\\.m?js)\"\\s*>\\s*</script>");
    private static final Pattern VITE_ASSET=Pattern.compile("\\b(?:src|href)\\s*=\\s*\"((?:/|\\./)(?:assets/)?[A-Za-z0-9._-]+)\"");
    private static final String REACT_CONFIG="vite.config.js", REACT_ENTRY="src/main.jsx";
    private static final Set<String> REACT_PACKAGES=Set.of("vite","@vitejs/plugin-react","react","react-dom");
    private static final Set<String> REACT_ENTRY_URLS=Set.of("/"+REACT_ENTRY,"./"+REACT_ENTRY,REACT_ENTRY);
    // Official create-vite template-react config, blob 9982072c14949379a05b48f656856691a6f0ab86.
    // Only cosmetic whitespace/quotes/semicolon layout and its known comment may vary.
    private static final String IMPORT_END="(?:\\s*;\\s*|[ \\t]*\\r?\\n\\s*)";
    private static final Pattern DEFAULT_REACT_CONFIG=Pattern.compile(
        "\\s*import\\s+react\\s+from\\s+(?:'@vitejs/plugin-react'|\"@vitejs/plugin-react\")"+IMPORT_END+
        "import\\s*\\{\\s*defineConfig\\s*\\}\\s*from\\s+(?:'vite'|\"vite\")"+IMPORT_END+
        "(?://[ \\t]+https://vite\\.dev/config/[ \\t]*\\r?\\n\\s*)?"+
        "export\\s+default\\s+defineConfig\\s*\\(\\s*\\{\\s*plugins\\s*:\\s*\\[\\s*react\\s*\\(\\s*\\)\\s*\\]\\s*,?\\s*\\}\\s*\\)\\s*;?\\s*");
    private static final Set<String> PUBLIC_MEDIA=Set.of("image/svg+xml","image/png","image/jpeg","image/webp","image/gif","image/x-icon",
        "font/woff","font/woff2","font/ttf");
    private OriginalNodeWebOutput(){}

    public static final class OutputSource {
        private final String stockEntry;
        private final boolean nestedPublic;
        private OutputSource(String stockEntry,boolean nestedPublic){this.stockEntry=stockEntry;this.nestedPublic=nestedPublic;}
        private boolean vite(){return stockEntry==null;}
        private String profile(){return vite()?VITE:STOCK;}
    }

    public static final class Build {
        private final JSONObject plan,binding,facts,witness;
        private final boolean direct;
        private Build(JSONObject plan, JSONObject binding, JSONObject facts,JSONObject witness,boolean direct)throws Exception{
            this.plan=ProjectSources.copy(plan);this.binding=ProjectSources.copy(binding);
            this.facts=ProjectSources.copy(facts);this.witness=ProjectSources.copy(witness);this.direct=direct;
        }
        public JSONObject plan()throws Exception{return ProjectSources.copy(plan);}
        public JSONObject binding()throws Exception{return ProjectSources.copy(binding);}
        public JSONObject facts()throws Exception{return ProjectSources.copy(facts);}
        public String checkoutId(){return facts.optString("checkoutId");}
        public String operationId(){return facts.optString("operationId");}
        public boolean directOwnerVite(){return direct;}
        public String saveTool(){return direct?DIRECT_TOOL:TOOL;}
    }
    public static String directVersion(JSONObject exact,JSONObject binding)throws Exception{
        if(binding.has(OriginalNodeCompilerOutput.DIRECT_ROUTE))throw new SecurityException("原网页构建不借编译文件来源");
        return OriginalNodeCompilerOutput.directBuildVersion(exact,binding,DIRECT_FORMAT);
    }
    public static boolean matchesDirectRoute(JSONObject exact,JSONObject route)throws Exception{
        return !route.has(OriginalNodeCompilerOutput.DIRECT_ROUTE)&&OriginalNodeCompilerOutput.matchesDirectBuildRoute(exact,route,DIRECT_ROUTE,DIRECT_FORMAT);
    }
    /** Internal original SQL row; a tool success string or an arbitrary artifact id is not a build. */
    public static Build fromOriginalRow(JSONObject task,JSONObject pin,JSONObject row,boolean current)throws Exception{
        JSONObject dc=task.getJSONObject("developmentContext");OwnerDraftFirstBuild.requireContext(pin,dc);
        if(!"receipt".equals(row.opt("state"))||!"project_execute".equals(row.opt("tool")))throw new SecurityException("原构建还未到可确认回执");
        JSONObject request=ProjectDevelopment.originalPollRequest(task,row),plan=request.getJSONObject("plan"),binding=request.getJSONObject("binding");
        if(!"build".equals(request.opt("stepId"))||!"build".equals(plan.opt("intent")))throw new SecurityException("不是原 Node 构建；依赖或保存回执不能代替");
        OwnerDraftFirstBuild.requirePlan(pin,plan,task.getString("id"));
        if(current){
            JSONObject outcome=OwnerDraftFirstBuild.outcomeFact(pin,task,row);
            if(outcome==null||!"build_exit".equals(outcome.opt("kind"))||outcome.getInt("value")!=0)throw new SecurityException("不是这一轮实际成功的 Node 构建");
        }
        if(ProjectDevelopment.recordedReceipt(task,row)==null)throw new SecurityException("原构建没有原生结果");
        String edited=OwnerDraftFirstBuild.editedWebVersion(pin),existing=OwnerDraftFirstBuild.existingWebVersion(pin);
        JSONObject route=row.getJSONObject("receipt").getJSONObject("projectExecution");
        requireVersionRoute(route,EDITED_ROUTE,edited);
        requireVersionRoute(route,EXISTING_ROUTE,existing);
        if(route.has(DIRECT_ROUTE))throw new SecurityException("原网页构建用途不同");
        return verifiedBuild(task,row,plan,binding,edited,existing,false);
    }
    /** The store supplies the already verified native Tool Work and its actual consumed SQL operation. */
    public static Build fromDirectRow(JSONObject task,JSONObject row,JSONObject plan,JSONObject exact,JSONObject route)throws Exception{
        var ref=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,route);
        JSONObject actual=row.getJSONObject("receipt").getJSONObject("executionReceipt");
        ref.requireReceipt(actual);
        if(!matchesDirectRoute(exact,route)||!actual.isNull("receiptPersistence"))
            throw new SecurityException("原本机网页构建缺少已保留的退出证据");
        return verifiedBuild(task,row,plan,ref.binding(),"",route.getString(DIRECT_ROUTE),true);
    }
    private static Build verifiedBuild(JSONObject task,JSONObject row,JSONObject plan,JSONObject binding,
            String editedVersion,String existingVersion,boolean direct)throws Exception{
        JSONObject actual=row.getJSONObject("receipt").getJSONObject("executionReceipt");
        JSONObject proof=ProjectManagedOutput.build(plan,binding,actual,actual.getLong("checkoutSequence"));
        if(!Boolean.FALSE.equals(actual.opt("cancelRequested"))||!Boolean.FALSE.equals(actual.opt("timedOut")))
            throw new SecurityException("原构建并非正常成功退出");
        String taskId=task.getString("id"),run=binding.getString("runId"),op=binding.getString("operationId");
        int epoch=binding.getInt("coordinatorEpoch");
        if(!taskId.equals(binding.getString("taskId"))||!op.equals(row.getString("operationId"))||epoch<1)
            throw new SecurityException("原构建的任务或轮次身份不同");
        String ownerOp=UUID.nameUUIDFromBytes(ProjectSources.canonical(new JSONArray().put(direct?DIRECT_TOOL:TOOL).put(taskId).put(run).put(epoch).put(op))
                .getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject facts=new JSONObject().put("taskId",taskId).put("runId",run).put("epoch",epoch).put("buildOperationId",op)
            .put("buildReceiptIdentity",proof.getString("receiptIdentity")).put("checkoutSequence",proof.getLong("checkoutSequence"))
            .put("operationId",ownerOp);
        for(String key:ListKeys.SOURCE)facts.put(key,plan.get(key));
        JSONObject witness=actual.optJSONObject(WITNESS);
        requireWitness(facts,witness,editedVersion,existingVersion);
        return new Build(plan,binding,facts,witness,direct);
    }
    private static void requireVersionRoute(JSONObject route,String key,String expected){
        if(expected.isEmpty()?route.has(key):!expected.equals(route.opt(key)))
            throw new SecurityException("网页没有这份源码版本的原生派发依据");
    }
    private static final class ListKeys {
        static final String[] SOURCE={"projectId","checkoutId","sourceDigest","planDigest","runtimeIdentity"};
    }
    public static void requireSameBuild(Build original,JSONObject proof)throws Exception{
        JSONObject source=original.facts;
        if(!source.getString("buildReceiptIdentity").equals(proof.opt("receiptIdentity"))||
           source.getLong("checkoutSequence")!=proof.optLong("checkoutSequence",-1))throw new SecurityException("原构建已不是这个副本的最新已核对操作");
    }

    public static void requireSameWitness(Build original,JSONObject actual)throws Exception {
        JSONObject seen=actual.optJSONObject(WITNESS);
        if(seen==null||original.direct&&!actual.isNull("receiptPersistence")||
            !ProjectSources.canonical(original.witness).equals(ProjectSources.canonical(seen)))
            throw new SecurityException("原运行宿主的退出资源证据与原任务回执不同");
    }
    private static Map<String,String> template()throws Exception{
        Map<String,String> result=new TreeMap<>();JSONArray files=ProjectTemplates.files("web");
        for(int i=0;i<files.length();i++){JSONObject item=files.getJSONObject(i);result.put(item.getString("path"),item.getString("content"));}
        return result;
    }
    /** The stock build and lock cannot be substituted. A user can change the two plain heading slots. */
    public static OutputSource requireSupportedSource(JSONObject snapshot)throws Exception{
        Map<String,String> expected=template(),actual=ProjectSources.files(snapshot);
        for(String path:Set.of("package.json","package-lock.json","tools/build.mjs"))
            if(!expected.get(path).equals(actual.get(path))){
                JSONObject checkout=snapshot.optJSONObject("checkout");
                return requireViteSource(actual,checkout!=null&&OwnerDraftFirstBuild.ordinaryCheckout(checkout)&&!checkout.has(OwnerDraftWorkCopy.FIELD));
            }
        String stock=expected.get("index.html"),html=actual.get("index.html");
        if(html==null||html.length()>stock.length()+320)throw new SecurityException("内置网页只支持更改标题文字，不更改页面入口或资源结构");
        String title=entryText(html,"title"),heading=entryText(html,"h1");
        if(!stock.replace("<title>我的小计划</title>","<title>"+title+"</title>")
                 .replace("<h1>我的小计划</h1>","<h1>"+heading+"</h1>").equals(html))
            throw new SecurityException("内置网页入口或资源结构已变；本次不标成固定整站");
        return new OutputSource(html,false);
    }

    /** Ordinary model-free Vite: configless or the official default React-JS preset only.
     * Check the original validated source rows too: a binary config cannot opt out of this rule. */
    public static OutputSource directViteSource(JSONObject exact,JSONObject snapshot)throws Exception{
        JSONObject checkout=snapshot.getJSONObject("checkout");
        if(!exact.getString("checkoutId").equals(checkout.opt("id"))||!exact.getString("projectId").equals(checkout.opt("projectId"))||
            !isNodeBuild(exact)||!"npm".equals(exact.getJSONObject("payload").opt("executable")))return null;
        return directViteSource(snapshot);
    }
    /** Same source-shape inspection for the project entry, without suggesting that lock/tools are ready. */
    static OutputSource directViteSource(JSONObject snapshot)throws Exception{
        JSONObject checkout=snapshot.getJSONObject("checkout");
        if(!OwnerDraftFirstBuild.ordinaryCheckout(checkout)||checkout.has(OwnerDraftWorkCopy.FIELD)||
            checkout.has("collectedFromCheckoutId")||checkout.has("collectionOperationId"))return null;
        var files=ProjectSources.files(snapshot);
        String manifest=files.get("package.json");
        if(manifest==null||manifest.getBytes(StandardCharsets.UTF_8).length>65536||!files.containsKey("package-lock.json")||
            !files.containsKey("index.html")||files.get("index.html").isBlank())return null;
        JSONObject pkg=dev.pocketengine.features.plugins.mcp.McpJson.parse(manifest),scripts=pkg.optJSONObject("scripts");
        if(scripts==null||!"vite build".equals(scripts.opt("build"))||scripts.has("prebuild")||scripts.has("postbuild")||pkg.has("postcss"))return null;
        boolean vite=false;
        for(String field:Set.of("dependencies","devDependencies")){
            JSONObject declared=pkg.optJSONObject(field);
            vite|=declared!=null&&declared.opt("vite") instanceof String version&&!version.isBlank();
        }
        if(!vite)return null;
        JSONArray rows=snapshot.getJSONArray("files");Set<String> paths=new java.util.HashSet<>();
        boolean react=false,customReactTransform=pkg.has("babel");
        for(int i=0;i<rows.length();i++){
            String path=rows.getJSONObject(i).getString("path");
            paths.add(path);
            String leaf=path.substring(path.lastIndexOf('/')+1).toLowerCase(java.util.Locale.ROOT);
            if(leaf.startsWith(".env")||leaf.startsWith(".postcssrc")||leaf.startsWith("postcss.config."))return null;
            customReactTransform|=leaf.equals(".babelrc")||leaf.startsWith(".babelrc.")||leaf.startsWith("babel.config.");
            if(leaf.startsWith("vite.config.")){
                if(!REACT_CONFIG.equals(path))return null;
                react=true;
            }
        }
        if(pkg.has("packageManager")&&!(pkg.opt("packageManager") instanceof String)||
                !"npm".equals(ProjectCheckoutTooling.manager(paths,pkg.optString("packageManager"))))return null;
        OwnerDraftNodeDependencies.requirePlainProject(pkg,paths);
        if(react&&(customReactTransform||!defaultReactVite(pkg,files)))return null;
        return requireViteSource(files,true);
    }
    private static boolean defaultReactVite(JSONObject pkg,Map<String,String> files)throws Exception{
        String config=files.get(REACT_CONFIG),jsx=files.get(REACT_ENTRY),html=files.get("index.html");
        if(!"module".equals(pkg.opt("type"))||config==null||config.length()>1024||!DEFAULT_REACT_CONFIG.matcher(config).matches()||
            jsx==null||jsx.isBlank()||html.getBytes(StandardCharsets.UTF_8).length>ViteOutputMount.MAX_ENTRY_BYTES)return false;
        var document=org.jsoup.Jsoup.parse(html);
        var scripts=document.select("script");
        if(scripts.size()!=1||!document.select("base").isEmpty())return false;
        var entry=scripts.get(0);
        return entry.parent()==document.body()&&entry.attributes().size()==2&&"module".equals(entry.attr("type"))&&
            REACT_ENTRY_URLS.contains(entry.attr("src"))&&entry.data().isBlank()&&
            OwnerDraftNodeDependencies.matchesOriginalNpmProfile(pkg,files.get("package-lock.json"),REACT_PACKAGES);
    }
    private static OutputSource requireViteSource(Map<String,String> files,boolean nestedPublic)throws Exception {
        String manifest=files.get("package.json");
        if(manifest==null||manifest.getBytes(StandardCharsets.UTF_8).length>65536||!files.containsKey("index.html"))
            throw new SecurityException("没有可核对的单入口 Vite 项目");
        JSONObject pkg=dev.pocketengine.features.plugins.mcp.McpJson.parse(manifest);
        JSONObject scripts=pkg.optJSONObject("scripts");
        boolean vite=false;
        for(String field:Set.of("dependencies","devDependencies")){
            JSONObject declared=pkg.optJSONObject(field);
            vite|=declared!=null&&declared.opt("vite") instanceof String;
        }
        if(!vite||scripts==null||scripts.has("prebuild")||scripts.has("postbuild")||
           !Set.of("vite build","tsc -b && vite build").contains(scripts.optString("build")))
            throw new SecurityException("固定网页包只支持内置模板或声明了标准单入口 Vite build 的项目；其余仍可在原任务另存单文件");
        return new OutputSource(null,nestedPublic);
    }
    private static String entryText(String html,String tag){
        String begin="<"+tag+">",end="</"+tag+">";
        int from=html.indexOf(begin),to=from<0?-1:html.indexOf(end,from+begin.length());
        if(to<0)throw new SecurityException("无法核对内置网页入口的标题");
        String text=html.substring(from+begin.length(),to);
        if(text.trim().isEmpty()||text.codePointCount(0,text.length())>80)
            throw new SecurityException("两个标题都只支持 1–80 字的纯文字");
        for(int at=0;at<text.length();){
            int c=text.codePointAt(at);at+=Character.charCount(c);
            if(!Character.isLetterOrDigit(c)&&Character.getType(c)!=Character.NON_SPACING_MARK&&
               " -_.·、！!？?：:；;，,。()（）「」『』".indexOf(c)<0)
                throw new SecurityException("标题不解释额外标记，只能使用普通文字");
        }
        return text;
    }
    public static void requireStockPaths(java.io.File root)throws Exception{
        Path dist=root.toPath().resolve("dist"), assets=dist.resolve("assets");
        if(!Files.isDirectory(dist,LinkOption.NOFOLLOW_LINKS)||!Files.isDirectory(assets,LinkOption.NOFOLLOW_LINKS)||
            !Files.isRegularFile(dist.resolve("index.html"),LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("原静态输出必须是此副本内的普通目录和文件");
        try(var paths=Files.newDirectoryStream(dist);var leaves=Files.newDirectoryStream(assets)){
            int count=0;
            for(Path path:paths){
                if(++count>2||!Set.of("index.html","assets").contains(path.getFileName().toString()))
                    throw new SecurityException("原退出时未观测到内置静态 Web 的固定输出根");
            }
            if(count!=2)throw new SecurityException("缺少内置静态 Web 的原入口或资源目录");
            count=0;
            for(Path path:leaves){
                String name=path.getFileName().toString();
                if(++count>3||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||
                   !(SCRIPT.matcher(name).matches()||STYLE.matcher(name).matches()||ICON.matcher(name).matches()))
                    throw new SecurityException("原输出资源含不受支持的类型或目录，不能作为固定整站");
            }
            if(count!=3)throw new SecurityException("原退出时未观测到内置静态 Web 的完整四项");
        }
    }
    /** A positive observation in this ordinary Vite checkout, not a general archive/IO error. */
    public static final class UnsupportedNestedViteDirectory extends SecurityException {
        UnsupportedNestedViteDirectory(){super("原 Vite 输出含不能固定的子目录，未保存部分成果");}
    }
    public static void requireOutputPaths(java.io.File root,OutputSource source)throws Exception{
        requireOutputPaths(root,source,false);
    }
    private static void requireOutputPaths(java.io.File root,OutputSource source,boolean observeOrdinaryViteExit)throws Exception{
        if(!source.vite()){requireStockPaths(root);return;}
        WebDistObservation.requireOutputPaths(root.toPath(),source.nestedPublic,observeOrdinaryViteExit);
    }
    /** Relative to dist. Generated modules/styles keep the original flat assets scope. */
    static boolean viteFilePath(String member,boolean nestedPublic){
        int slash=member.lastIndexOf('/');
        if(slash<0)return vitePath(member,true);
        if(slash=="assets".length()&&member.startsWith("assets/"))return vitePath(member.substring(slash+1),false);
        return nestedPublic&&ViteOutputMount.publicStaticMember(member);
    }
    private static boolean vitePath(String leaf,boolean top){
        if(!VITE_LEAF.matcher(leaf).matches())return false;
        String mime=dev.pocketengine.features.local.preview.ProjectPreviewBundle.mime(leaf);
        return PUBLIC_MEDIA.contains(mime)||(top?leaf.equals("index.html")||leaf.equals("robots.txt"):
            Set.of("text/javascript","text/css").contains(mime));
    }
    private static boolean isNodeBuild(JSONObject exact)throws Exception {
        JSONObject payload=exact.getJSONObject("payload");
        return "build".equals(exact.opt("intent"))&&"project.runtime.run".equals(exact.opt("command"))&&
            Set.of("npm","pnpm").contains(payload.optString("executable"))&&
            "[\"run\",\"build\"]".equals(ProjectSources.canonical(payload.optJSONArray("argv")));
    }
    private static boolean isOwnerCopy(JSONObject exact,JSONObject checkout)throws Exception{
        JSONObject ownerCopy=checkout.optJSONObject(OwnerDraftWorkCopy.FIELD);
        return ownerCopy!=null&&exact.getString("sourceDigest").equals(ownerCopy.opt("initialCopyDigest"))&&
            exact.getString("checkoutId").equals(ownerCopy.opt("checkoutId"))&&exact.getString("projectId").equals(ownerCopy.opt("projectId"));
    }

    private static boolean observeCleanDistAtLaunch(RuntimeInvocation invocation)throws Exception{
        JSONObject exact=invocation.exact();
        if(!isNodeBuild(exact)||!isOwnerCopy(exact,invocation.sourceSnapshot().getJSONObject("checkout")))return false;
        invocation.checkCurrent();
        Path dist=invocation.workspace().resolve("dist",false).toPath();
        try{Files.readAttributes(dist,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false;}
        catch(NoSuchFileException absent){invocation.checkCurrent();return true;}
    }

    private static boolean isEditedCopy(String version,JSONObject exact,JSONObject checkout)throws Exception{
        JSONObject copy=checkout.optJSONObject(OwnerDraftWorkCopy.FIELD);
        if(copy==null||!"build".equals(exact.opt("stepId"))||!"managed_process_v1".equals(exact.opt("runtimeId"))||
            !".".equals(exact.getJSONObject("payload").optString("workingDirectory","."))||
            !exact.getString("checkoutId").equals(copy.opt("checkoutId"))||!exact.getString("projectId").equals(copy.opt("projectId")))return false;
        return version.equals(OwnerDraftFirstBuild.editedWebVersion(copy.getString("workIdentity"),copy.getString("initialCopyDigest"),exact.getString("sourceDigest")));
    }

    private static boolean isExistingCheckout(String version,JSONObject exact,JSONObject checkout)throws Exception{
        JSONObject payload=exact.getJSONObject("payload");
        return !checkout.has(OwnerDraftWorkCopy.FIELD)&&OwnerDraftFirstBuild.ordinaryCheckout(checkout)&&
            "build".equals(exact.opt("stepId"))&&"managed_process_v1".equals(exact.opt("runtimeId"))&&
            ".".equals(payload.optString("workingDirectory","."))&&"foreground".equals(payload.optString("runMode","foreground"))&&
            exact.getString("checkoutId").equals(checkout.opt("id"))&&exact.getString("projectId").equals(checkout.opt("projectId"))&&
            version.equals(OwnerDraftFirstBuild.ordinaryWebVersion(exact));
    }
    private static OutputSource versionedSource(RuntimeInvocation invocation,JSONObject exact,JSONObject snapshot)throws Exception{
        String edited=invocation.editedWebVersion(),existing=invocation.existingWebVersion();
        JSONObject checkout=snapshot.getJSONObject("checkout");
        if(invocation.directWebOutput())return directViteSource(exact,snapshot);
        if(!existing.isEmpty()){
            if(!edited.isEmpty()||!isExistingCheckout(existing,exact,checkout))return null;
            return requireViteSource(ProjectSources.files(snapshot),true);
        }
        return isEditedCopy(edited,exact,checkout)?requireSupportedSource(snapshot):null;
    }
    public static final class LaunchObservation {
        private final RuntimeInvocation original;
        private final boolean cleanDist;
        private final WebDistObservation before;
        private LaunchObservation(RuntimeInvocation original,boolean cleanDist,WebDistObservation before){
            this.original=original;this.cleanDist=cleanDist;this.before=before;
        }
    }
    /** Optional observation in the live worker, before dispatch to the OS. Nothing is cleared or written. */
    public static LaunchObservation observeBeforeLaunch(RuntimeInvocation invocation)throws Exception{
        if(!invocation.versionedWebOutput())return new LaunchObservation(invocation,observeCleanDistAtLaunch(invocation),null);
        JSONObject exact=invocation.exact(),snapshot=invocation.sourceSnapshot();
        if(!isNodeBuild(exact))return null;
        OutputSource source=versionedSource(invocation,exact,snapshot);
        if(source==null)return null;
        var root=invocation.workspace();root.verify(invocation);
        WebDistObservation before=WebDistObservation.read(root.directory().toPath(),source.nestedPublic);invocation.checkCurrent();
        return new LaunchObservation(invocation,false,before);
    }

    /** A read-only witness before this proven-success original process releases its lease. No late observation. */
    public static JSONObject observeAtOriginalExit(RuntimeInvocation invocation,long sequence,LaunchObservation launch)throws Exception{
        JSONObject exact=invocation.exact();boolean versioned=invocation.versionedWebOutput();
        if(!isNodeBuild(exact)||sequence<1||launch!=null&&launch.original!=invocation)return null;
        invocation.checkCurrent();JSONObject snapshot=invocation.sourceSnapshot(),checkout=snapshot.getJSONObject("checkout");
        if(versioned?launch==null||launch.before==null:!isOwnerCopy(exact,checkout))return null;
        OutputSource source=versioned?versionedSource(invocation,exact,snapshot):requireSupportedSource(snapshot);
        if(source==null||!versioned&&source.vite()&&(launch==null||!launch.cleanDist))return null; // V1 Vite still needs an empty output location.
        var root=invocation.workspace();
        try{requireOutputPaths(root.directory(),source,versioned&&!invocation.existingWebVersion().isEmpty());}
        catch(UnsupportedNestedViteDirectory unsupported){root.verify(invocation);throw unsupported;}
        root.verify(invocation);
        var exit=versioned?WebDistObservation.read(root.directory().toPath(),source.nestedPublic):null;
        JSONObject info=new JSONObject().put("projectId",exact.getString("projectId")).put("checkoutId",exact.getString("checkoutId"))
            .put("sourceDigest",exact.getString("sourceDigest")).put("name",checkout.getString("name"));
        var frozen=ProjectPackageArchive.capture(root.directory(),info,"web_output",ENTRY,true);
        requireOutputContents(frozen,source,true);invocation.checkCurrent();
        JSONObject changedTrees=null;
        boolean existing=!invocation.existingWebVersion().isEmpty();
        if(versioned){
            exit.requireSameRead(WebDistObservation.read(root.directory().toPath(),source.nestedPublic));
            changedTrees=launch.before.changedTo(exit,frozen).put("version",existing?invocation.existingWebVersion():invocation.editedWebVersion());
        }else{
            requireOutputPaths(root.directory(),source);
            var again=ProjectPackageArchive.capture(root.directory(),info,"web_output",ENTRY,true);
            if(!frozen.identity.equals(again.identity))throw new SecurityException("原退出的整站资源在观察中变化");
        }
        root.verify(invocation);invocation.checkCurrent();
        JSONObject binding=invocation.binding(),stamp=ProjectPackageDelivery.archiveIdentity(frozen)
            .put("manifestIdentity",frozen.identity).put("entryPath",ENTRY).put("checkoutSequence",sequence)
            .put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId"))
            .put("epoch",binding.getInt("coordinatorEpoch")).put("buildOperationId",binding.getString("operationId"));
        for(String k:ListKeys.SOURCE)stamp.put(k,exact.get(k));
        if(source.vite())stamp.put("outputProfile",source.profile());
        return stamp.putOpt(existing?EXISTING_TREES:EDITED_TREES,changedTrees);
    }
    private static void requireWitness(JSONObject facts,JSONObject witness,String editedVersion,String existingVersion)throws Exception{
        boolean edited=!editedVersion.isEmpty(),existing=!existingVersion.isEmpty();
        if(witness==null||edited&&existing||!ENTRY.equals(witness.opt("entryPath"))||
           witness.length()!=WITNESS_KEYS.size()+(witness.has("outputProfile")?1:0)+(edited||existing?1:0)||
           ((existing||witness.has("outputProfile"))&&!VITE.equals(witness.opt("outputProfile"))))
            throw new SecurityException("这笔原构建没有在成功退出时留下可核对的网页原证据；不补读当前 dist");
        for(String key:WITNESS_KEYS)if(!witness.has(key))throw new SecurityException("原整站退出证据缺少字段");
        requireTrees(witness,EDITED_TREES,editedVersion);
        requireTrees(witness,EXISTING_TREES,existingVersion);
        for(String key:Set.of("taskId","runId","epoch","buildOperationId","checkoutSequence",
            "projectId","checkoutId","sourceDigest","planDigest","runtimeIdentity"))
            if(!ProjectSources.canonical(facts.get(key)).equals(ProjectSources.canonical(witness.opt(key))))
                throw new SecurityException("网页的退出时证据属于另一轮、另一副本或原操作");
        for(String k:Set.of("manifestIdentity","archiveSha256"))if(!witness.optString(k).matches("[a-f0-9]{64}"))
            throw new SecurityException("原整站退出摘要不可核对");
        if(witness.optLong("archiveBytes")<=0||witness.optLong("archiveBytes")>ProjectPackageArchive.MAX_ARCHIVE_BYTES)
            throw new SecurityException("原整站退出大小不可核对");
    }
    private static void requireTrees(JSONObject witness,String key,String version){
        JSONObject trees=witness.optJSONObject(key);
        if(version.isEmpty()?witness.has(key):trees==null||trees.length()!=3||!version.equals(trees.opt("version"))||
            !trees.optString("before").matches("[a-f0-9]{64}")||!trees.optString("after").matches("[a-f0-9]{64}")||trees.opt("before").equals(trees.opt("after")))
            throw new SecurityException("这次构建缺少已改变的原网页字节证据");
    }
    public static void requireExitBytes(JSONObject witness,ProjectPackageArchive.Frozen frozen)throws Exception{
        JSONObject bytes=ProjectPackageDelivery.archiveIdentity(frozen);
        if(!frozen.identity.equals(witness.opt("manifestIdentity"))||!bytes.getString("archiveSha256").equals(witness.opt("archiveSha256"))||
            bytes.getLong("archiveBytes")!=witness.optLong("archiveBytes",-1))
            throw new SecurityException("完整网页资源已不是原构建退出时观察的字节；未用当前同名文件替代");
    }

    public static void requireOutputContents(ProjectPackageArchive.Frozen frozen,OutputSource source)throws Exception{
        requireOutputContents(frozen,source,false);
    }
    private static void requireOutputContents(ProjectPackageArchive.Frozen frozen,OutputSource source,boolean atOriginalExit)throws Exception{
        if(!source.vite()){requireCompleteStockSite(frozen,source.stockEntry);return;}
        JSONObject manifest=frozen.manifest();JSONArray dirs=manifest.getJSONArray("directories"),files=manifest.getJSONArray("files");
        if(!"web_output".equals(manifest.opt("mode"))||!ENTRY.equals(manifest.opt("entryPath"))||!"dist".equals(manifest.opt("previewRoot"))||
            manifest.getJSONArray("omitted").length()!=0||files.length()<2||files.length()>VITE_FILES||
            files.length()+dirs.length()>VITE_ENTRIES||manifest.getLong("bytes")>VITE_BYTES)
            throw new SecurityException("原固定包不是这次核对的单入口 Vite 输出，未保存部分资源");
        Set<String> paths=new java.util.HashSet<>(),expectedDirectories=new java.util.HashSet<>(Set.of("dist","dist/assets"));
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);String path=file.getString("path");
            long bytes=file.getLong("bytes");
            if(!path.startsWith("dist/")||!viteFilePath(path.substring(5),source.nestedPublic)||
               ProjectPackageArchive.excluded(path,false,true)!=null||bytes<=0||bytes>ProjectPackageArchive.MAX_FILE_BYTES||!paths.add(path))
                throw new SecurityException("原固定包文件与已观察 Vite 目录不同");
            for(int slash=path.lastIndexOf('/');slash>4;slash=path.lastIndexOf('/',slash-1))
                expectedDirectories.add(path.substring(0,slash));
        }
        // No omitted or orphan directories. Both observation and capture cover the
        // same resource tree, not a subset of a build that happened to contain the entry.
        for(int i=0;i<dirs.length();i++)if(!expectedDirectories.remove(dirs.get(i)))
            throw new SecurityException("原固定包目录与已观察 Vite 目录不同");
        if(!expectedDirectories.isEmpty())throw new SecurityException("原固定包缺少实际 Vite 资源目录");
        byte[] entry=frozen.file(ENTRY);
        if(entry.length>ViteOutputMount.MAX_ENTRY_BYTES)throw new SecurityException("原网页入口过大，未保存");
        String html=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(entry)).toString();
        if(!ViteOutputMount.rootedBase(html,path->paths.contains("dist/"+path)).isEmpty())return;
        var module=VITE_ENTRY.matcher(html);
        if(!module.find()||!paths.contains(viteRelative(module.group(1)))||module.find())
            throw new SecurityException("原网页未引用一个实际同时固定的 Vite 模块入口");
        var references=VITE_ASSET.matcher(html);
        while(references.find()){
            String path=viteRelative(references.group(1));
            if((path.startsWith("dist/assets/")||PUBLIC_MEDIA.contains(dev.pocketengine.features.local.preview.ProjectPreviewBundle.mime(path)))&&
               !paths.contains(path))throw new SecurityException("原网页引用的本地资源不在固定包中");
        }
        if(source.nestedPublic&&(atOriginalExit||dirs.length()>2)){
            // A new exit always checks literal public references, even if no new
            // directory was emitted. Late review honors older witnessed flat trees.
            for(var tag:org.jsoup.Jsoup.parse(html).select("[src], [href]")){
                requireNestedPublicReference(tag.attr("src"),paths);
                requireNestedPublicReference(tag.attr("href"),paths);
            }
        }
    }
    private static void requireNestedPublicReference(String literal,Set<String> paths){
        String member=literal.startsWith("./")?literal.substring(2):literal.startsWith("/")?literal.substring(1):literal;
        if(ViteOutputMount.publicStaticMember(member)&&!paths.contains("dist/"+member))
            throw new SecurityException("原网页引用的本地子目录资源不在同一固定包中");
    }
    private static String viteRelative(String url){return "dist/"+url.substring(url.startsWith("./")?2:1);}

    /** The stock builder always emits one bundled script, stylesheet and icon, wired into the
     * source entry. Do not guess at general HTML/JS graphs or run a detector. */
    public static void requireCompleteStockSite(ProjectPackageArchive.Frozen frozen,String sourceEntry)throws Exception{
        JSONObject manifest=frozen.manifest();
        if(!"web_output".equals(manifest.get("mode"))||!ENTRY.equals(manifest.get("entryPath"))||
           !"dist".equals(manifest.get("previewRoot"))||manifest.getJSONArray("omitted").length()!=0)throw new SecurityException("请选择同一原副本的内置 Web 输出根");
        var files=frozen.files();
        if(files.size()!=4||!files.containsKey(ENTRY))throw new SecurityException("未找到该站点的完整 HTML、JS、CSS 与 SVG，未保存或标为可预览");
        String js=null,css=null,svg=null;
        for(String path:files.keySet()){
            if(path.equals(ENTRY))continue;
            if(!path.startsWith("dist/assets/"))throw new SecurityException("原网页资源不在预期目录，未保存");
            if(SCRIPT.matcher(path.substring("dist/assets/".length())).matches()&&js==null)js=path;
            else if(STYLE.matcher(path.substring("dist/assets/".length())).matches()&&css==null)css=path;
            else if(ICON.matcher(path.substring("dist/assets/".length())).matches()&&svg==null)svg=path;
            else throw new SecurityException("生成目录含其他资源或同种文件；无法证明这是内置单入口的完整版本，未保存");
            if(files.get(path).length==0)throw new SecurityException("原网页资源为空，未保存");
        }
        if(js==null||css==null||svg==null)throw new SecurityException("原网页少了脚本、样式或图，未保存");
        String html=sourceEntry.replace("./src/main.js","./"+js.substring(5))
            .replace("./src/style.css","./"+css.substring(5)).replace("./assets/mark.svg","./"+svg.substring(5));
        if(!java.util.Arrays.equals(html.getBytes(StandardCharsets.UTF_8),files.get(ENTRY)))
            throw new SecurityException("HTML 的实际引用不是这份固定包里的三组字节，未保存");
        // The bound preview does not provide network, dynamic resource loading or SSR.
        String stylesheet=new String(files.get(css),StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        if(stylesheet.contains("url")||stylesheet.contains("@import")||stylesheet.contains("image-set")||stylesheet.contains("\\"))
            throw new SecurityException("这份样式另引用了无法在本次核对的资源，先不标为完整网页");
    }

    public static final class Review {
        public final Build build;private final JSONObject pin,files;
        private Review(Build build, JSONObject pin, JSONArray files)throws Exception{
            this.build=build;this.pin=ProjectSources.copy(pin);
            this.files=new JSONObject().put("files",new JSONArray(files.toString()));
        }
        /** Only metadata may cross the owner review window; no HTML/JS or runtime handle. */
        public JSONObject view()throws Exception{
            return ProjectSources.copy(pin).put("state","review").put("files",new JSONArray(files.getJSONArray("files").toString()))
                .put("entryPath",ENTRY).put("outputProfile",build.witness.optString("outputProfile",STOCK))
                .put("bytes",pin.getLong("archiveBytes")).put("authorityAdded",false);
        }
        public JSONObject pin()throws Exception{return ProjectSources.copy(pin);}
    }
    public static Review review(Build build,ProjectPackageArchive.Frozen frozen,OutputSource sourceShape)throws Exception{
        if(!sourceShape.profile().equals(build.witness.optString("outputProfile",STOCK))||
           sourceShape.nestedPublic&&!build.witness.has(EXISTING_TREES))
            throw new SecurityException("原构建见证与本次审阅的网页类型不同");
        requireOutputContents(frozen,sourceShape);requireExitBytes(build.witness,frozen);
        JSONObject trees=build.witness.optJSONObject(build.witness.has(EXISTING_TREES)?EXISTING_TREES:EDITED_TREES);
        if(trees!=null&&!trees.getString("after").equals(WebDistObservation.archiveTreeDigest(frozen)))
            throw new SecurityException("当前网页包与这次构建的退出字节不一致");
        JSONObject manifest=frozen.manifest(),selected=build.facts();
        JSONObject source=manifest.getJSONObject("source");
        for(String key:Set.of("projectId","checkoutId","sourceDigest"))if(!selected.getString(key).equals(source.opt(key)))
            throw new SecurityException("原构建和网页包不在同一副本版本");
        JSONObject bytes=ProjectPackageDelivery.archiveIdentity(frozen);
        selected.put("manifestIdentity",frozen.identity).put("archiveSha256",bytes.getString("archiveSha256")).put("archiveBytes",bytes.getLong("archiveBytes"));
        return new Review(build,selected,manifest.getJSONArray("files"));
    }
    public static void requirePin(Build build,JSONObject pin)throws Exception{
        JSONObject exact=build.facts();
        if(pin.length()!=BINDING.size()+3)throw new SecurityException("原固定网页审阅信息无效");
        for(String k:BINDING)if(!ProjectSources.canonical(exact.get(k)).equals(ProjectSources.canonical(pin.opt(k))))
            throw new SecurityException("原构建/轮次/清单引用已经变化");
        for(String k:Set.of("manifestIdentity","archiveSha256","archiveBytes"))
            if(!ProjectSources.canonical(build.witness.get(k)).equals(ProjectSources.canonical(pin.opt(k))))
                throw new SecurityException("待存网页不等于原构建退出时观察的固定字节");
        for(String k:Set.of("manifestIdentity","archiveSha256"))if(!pin.getString(k).matches("[a-f0-9]{64}"))
            throw new SecurityException("原固定网页摘要无法核对");
        long n=pin.getLong("archiveBytes");if(n<=0||n>ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new SecurityException("原固定包字节数无效");
    }

    /** Mint only the existing package evidence format after the native original action is settled. */
    public static JSONObject savedEvidence(Build build,JSONObject pin,JSONObject artifact)throws Exception{
        requirePin(build,pin);String op=build.operationId(),action=ProjectPackageDelivery.actionId(op);
        String id=UUID.nameUUIDFromBytes(("project-package:"+action).getBytes(StandardCharsets.UTF_8)).toString();
        if(!id.equals(artifact.opt("id"))||!id.equals(artifact.opt("versionId"))||!action.equals(artifact.opt("ownerActionId"))||
            !"project_bundle".equals(artifact.opt("kind"))||!"web_output".equals(artifact.opt("mode"))||
            build.direct!=directArchiveMetadata(artifact)||build.direct&&!ENTRY.equals(artifact.opt("entryPath"))||
            !Boolean.TRUE.equals(artifact.opt("persisted"))||"retired".equals(artifact.optString("availability"))||
            pin.getLong("archiveBytes")!=artifact.getLong("sizeBytes")||!pin.getString("archiveSha256").equals(artifact.opt("sha256")))
            throw new SecurityException("原固定网页成果没有同一份完整字节回执");
        for(String k:Set.of("projectId","checkoutId","sourceDigest","manifestIdentity"))if(!pin.getString(k).equals(artifact.opt(k)))
            throw new SecurityException("原固定网页成果不属于这次审阅");
        JSONObject metadata=ProjectPackageDelivery.metadata(artifact);
        if(build.direct)metadata.put("source",DIRECT_ARTIFACT_SOURCE);
        return build.facts().put("format",ProjectPackageDelivery.EVIDENCE_FORMAT).put("coordinatorEpoch",pin.getInt("epoch"))
            .put("manifestIdentity",pin.getString("manifestIdentity")).put("archiveSha256",pin.getString("archiveSha256"))
            .put("archiveBytes",pin.getLong("archiveBytes")).put("packageArtifact",metadata)
            .put("authorityAdded",false).put("buildOutputAttribution","selected_output_observed_after_build");
    }
}
