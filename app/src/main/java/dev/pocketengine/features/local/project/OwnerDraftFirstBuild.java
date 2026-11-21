package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One owner-reviewed private checkout version. This witness is not an approval. */
public final class OwnerDraftFirstBuild {
    public static final String FIELD="ownerDraftFirstBuild";
    public static final String PENDING_FIELD="originalManagedNodePending";
    public static final Set<String> TOOLS=Set.of("project_inspect","project_plan","project_execute","project_status","project_log","project_collect_plan","project_generated_artifact");
    public static final String GOAL="验证我打开的这份独立改稿副本。先按同一版 package.json 准备 Node 构建（project_plan，intent=build、ecosystem=node），让我另行确认具体命令。不要安装依赖、启动服务、改写源码或发布。失败时保留原退出和日志；若原构建成功，再查看 build 或 dist，将我另行确认的文件交回这个任务。";
    private static final String LOCKED_GOAL="在我选定的独立改稿副本中验证同版 Node 构建。已有原生核对的关键包就直接申请构建，缺依赖才按这一把锁申请准备。两项分别等待我的批准；准备失败停在本任务。只选实际构建之后观察到的 build 或 dist 文件，保留另行确认。不改原件、不开服务、不发布或切到云端。";
    public static String goal(JSONObject pin){
        if(existingCheckout(pin))return "核对所选私人工作副本的本机 Node 构建。"+
            (dependencies(pin)==null?"只申请这一版 package.json 的构建，不安装依赖。":"已有核对的关键包则直接申请构建；缺包只按这份锁申请，依赖准备和构建分别批准。")+
            "命令等待本人确认，不改写源码、不启动服务、不发布、不切云端。保留这次实际退出和日志；只把另行确认的原构建后文件交回这项任务。";
        return dependencies(pin)==null?GOAL:LOCKED_GOAL;
    }
    private OwnerDraftFirstBuild(){}
    public static String client(String identity){
        if(identity==null||!identity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("没有同一份改稿副本");
        return UUID.nameUUIDFromBytes(("private-draft-first-build-v1:"+identity).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String lockedClient(String identity){
        if(identity==null||!identity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("没有同一份改稿副本");
        return UUID.nameUUIDFromBytes(("private-draft-locked-node-v1:"+identity).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static final String EDITED_BASE="copiedSourceDigest", EXISTING_CHECKOUT="existingCheckoutBuild";
    public static boolean edited(JSONObject pin){return pin.has(EDITED_BASE);}
    public static boolean existingCheckout(JSONObject pin){return Boolean.TRUE.equals(pin.opt(EXISTING_CHECKOUT));}
    static boolean ordinaryCheckout(JSONObject value){
        return !value.has("teamProjectCopy")&&!value.has("skillExecutionCopy")&&!value.has("isolatedGit")&&!value.has("nativeGitImportPending");
    }
    public static String existingClient(String project,String checkout,String digest){
        if(!hash(digest))throw new IllegalArgumentException("无法核对这版私人副本");
        return UUID.nameUUIDFromBytes(("private-existing-node-build-v1:"+ProjectBuildPlan.identity(project)+":"+
            ProjectBuildPlan.identity(checkout)+":"+digest).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String editedClient(String identity,String digest){
        if(!hash(identity)||!hash(digest))throw new IllegalArgumentException("无法核对这版工作副本");
        return UUID.nameUUIDFromBytes(("private-draft-edited-node-v1:"+identity+":"+digest).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    // Output provenance only. The existing TaskStore route and approval remain the execution authority.
    public static String editedWebVersion(JSONObject pin)throws Exception{
        return !edited(pin)?"":editedWebVersion(pin.getString("workIdentity"),pin.getString(EDITED_BASE),pin.getString("sourceDigest"));
    }
    static String editedWebVersion(String work,String copied,String source)throws Exception{
        if(!hash(copied)||copied.equals(source))throw new SecurityException("没有独立副本的当前编辑版");
        return ProjectSources.digest(new JSONObject().put("clientMessageId",editedClient(work,source)).put(EDITED_BASE,copied));
    }
    public static String existingWebVersion(JSONObject pin)throws Exception{
        if(!existingCheckout(pin))return "";
        return ordinaryWebVersion(pin);
    }
    static String ordinaryWebVersion(JSONObject exact)throws Exception{
        String client=existingClient(exact.getString("projectId"),exact.getString("checkoutId"),exact.getString("sourceDigest"));
        return ProjectSources.digest(new JSONObject().put("format","pocketworld.ordinary-node-web-version.v1")
            .put("clientMessageId",client).put("planDigest",exact.getString("planDigest")).put("runtimeIdentity",exact.getString("runtimeIdentity")));
    }
    public static String reviewedClient(JSONObject pin)throws Exception{
        if(existingCheckout(pin))return existingClient(pin.getString("projectId"),pin.getString("checkoutId"),pin.getString("sourceDigest"));
        String identity=pin.getString("workIdentity");
        return edited(pin)?editedClient(identity,pin.getString("sourceDigest")):dependencies(pin)==null?client(identity):lockedClient(identity);
    }
    public static JSONObject pin(OwnerDraftWorkCopy.Seed seed,JSONObject copy,JSONObject issued)throws Exception{
        if(!Boolean.TRUE.equals(copy.opt("found"))||!"ready".equals(copy.opt("status"))||!Boolean.FALSE.equals(copy.opt("recyclable"))||
            !seed.identity.equals(copy.opt("workIdentity"))||!seed.projectId.equals(copy.opt("projectId"))||
            !seed.checkoutId.equals(copy.opt("checkoutId")))throw new SecurityException("请先打开这份原稿的实际独立副本。");
        JSONObject pin=buildPin(seed.projectId,seed.checkoutId,issued).put("workIdentity",seed.identity);
        String copied=copy.getString("initialCopyDigest"),source=pin.getString("sourceDigest");
        if(!hash(copied))throw new SecurityException("无法核对独立副本的原稿，未审阅构建。");
        if(copied.equals(source)&&!"selected_version".equals(copy.opt("currentFileState")))
            throw new SecurityException("当前文件未核对为最初复制的版本，请重新读取副本。");
        return copied.equals(source)?pin:pin.put(EDITED_BASE,copied);
    }
    static JSONObject existingPin(String project,String checkout,JSONObject issued)throws Exception{
        return buildPin(project,checkout,issued).put(EXISTING_CHECKOUT,true);
    }
    private static JSONObject buildPin(String project,String checkout,JSONObject issued)throws Exception{
        JSONObject plan=ProjectBuildPlan.verified(issued);JSONArray steps=plan.getJSONArray("steps");
        String source=plan.getString("sourceDigest");
        if(!project.equals(plan.opt("projectId"))||!checkout.equals(plan.opt("checkoutId"))||!hash(source))
            throw new SecurityException("无法核对当前源码版本和原工作副本，未审阅构建。");
        if(!"managed_process_v1".equals(plan.opt("runtimeId"))||plan.optString("runtimeIdentity").isBlank()||
            !"build".equals(plan.opt("intent"))||!"node".equals(plan.opt("ecosystem"))||steps.length()!=1||plan.opt("automaticChaining")!=Boolean.FALSE)
            throw new SecurityException("没有这份工程单独的本机构建步骤。");
        JSONObject step=steps.getJSONObject(0),p=step.getJSONObject("payload");String manager=p.optString("executable");JSONArray argv=p.optJSONArray("argv");
        if(!Set.of("npm","pnpm").contains(manager)||!"project.runtime.run".equals(step.opt("localCommand"))||!"build".equals(step.opt("id"))||
            !Boolean.TRUE.equals(step.opt("approvalRequired"))||argv==null||argv.length()!=2||!"run".equals(argv.opt(0))||!"build".equals(argv.opt(1)))
            throw new IllegalStateException("所选工程没有声明可用的 Node build 脚本；语法检查不等于构建。");
        if(!plan.optBoolean("available"))throw new IllegalStateException("这台手机尚未确认可用的 "+manager+" 运行环境；没有启动、下载或改用云端。");
        return new JSONObject().put("projectId",project).put("checkoutId",checkout).put("sourceDigest",source)
            .put("runtimeIdentity",plan.getString("runtimeIdentity")).put("planDigest",plan.getString("planDigest")).put("manager",manager);
    }
    public static final String DEPENDENCIES="lockedDependencies";
    public static JSONObject chooseDependencies(JSONObject pin,JSONObject snapshot,JSONObject runtime)throws Exception{
        JSONObject review=OwnerDraftNodeDependencies.review(snapshot,pin.getString("manager"),runtime);
        if(review!=null)pin.put(DEPENDENCIES,new JSONObject().put("review",review).put("planDigest",
            ProjectBuildPlan.lockedNodeDependencies(snapshot,pin.getString("runtimeIdentity"),review).getString("planDigest")));
        return pin;
    }
    public static JSONObject dependencies(JSONObject pin){JSONObject lock=pin.optJSONObject(DEPENDENCIES);return lock==null?null:lock.optJSONObject("review");}
    /** Prepared receipts are evidence in their own scope; current files must always be rechecked. */
    public static boolean allowsBuildWithCurrentPackages(JSONObject observation){
        return observation==null||"dependency_exit".equals(observation.opt("kind"))&&Integer.valueOf(0).equals(observation.opt("value"));
    }

    public static JSONArray catalog(JSONArray visible)throws Exception{
        JSONArray only=new JSONArray();for(int i=0;i<visible.length();i++){JSONObject f=visible.getJSONObject(i);if(TOOLS.contains(f.getJSONObject("function").getString("name")))only.put(f);}return only;
    }
    /** Native-only review fields, before collection, and the same two identities in its durable result.
     * An earlier run's verified build can be an input. The new save does not become a new build. */
    public static JSONObject reviewOperation(JSONObject pin,JSONObject plan,JSONObject operation)throws Exception{
        requirePlan(pin,plan,operation.getString("taskId"));
        JSONObject view=new JSONObject().put("action","build").put("manager",pin.getString("manager")).put("projectId",pin.getString("projectId"))
            .put("checkoutId",pin.getString("checkoutId")).put("sourceDigest",pin.getString("sourceDigest")).put("taskId",operation.getString("taskId"))
            .put("actionRunId",operation.getString("runId")).put("actionEpoch",operation.getInt("coordinatorEpoch")).put("actionOperationId",operation.getString("operationId"));
        if("dependencies".equals(plan.opt("intent"))){
            JSONObject step=ProjectBuildPlan.step(plan,OwnerDraftNodeDependencies.STEP);
            return view.put("action","dependencies").put("lock",dependencies(pin)).put("argv",step.getJSONObject("payload").getJSONArray("argv"));
        }
        if("collect".equals(plan.opt("intent"))){
            JSONObject choice=ProjectManagedOutput.verified(plan.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").getJSONObject("artifactSelection")),origin=choice.getJSONObject("buildBinding");
            JSONObject proved=choice.getJSONObject("buildReceipt").getJSONObject("binding");
            requireSourceBinding(origin,proved);String buildId=origin.getString("operationId");
            String receiptId=ProjectSources.digest(new JSONObject().put("planDigest",pin.getString("planDigest")).put("stepId","build").put("jobId",buildId).put("binding",proved));
            if(!"native_host_operation".equals(proved.opt("authority"))||!receiptId.equals(choice.getString("buildReceiptIdentity"))||
                !buildId.equals(choice.getString("buildJobId"))||operation.getString("operationId").equals(buildId)||
                origin.getInt("coordinatorEpoch")<1||operation.getInt("coordinatorEpoch")<origin.getInt("coordinatorEpoch"))throw new SecurityException("收件和源构建不是可核对的两个操作");
            view.put("action","collect").put("path",choice.getString("path")).put("bytes",choice.getLong("bytes")).put("sha256",choice.getString("sha256"))
                .put("buildRunId",origin.getString("runId")).put("buildEpoch",origin.getInt("coordinatorEpoch")).put("buildOperationId",origin.getString("operationId"))
                .put("buildReceiptIdentity",choice.getString("buildReceiptIdentity"));
        }return view;
    }
    public static String outputPath(String relative){
        String path=ProjectManagedOutput.path(relative);ProjectBuildOutputs.requireDirectory(path);
        if(path.indexOf('/')<0)throw new SecurityException("请选择 build 或 dist 下的具体可见文件，不采纳其他目录");
        return path;
    }
    public static void requireSourceBinding(JSONObject selected,JSONObject actual)throws Exception{
        for(String k:new String[]{"taskId","runId","coordinatorEpoch","operationId"})
            if(!ProjectSources.canonical(selected.get(k)).equals(ProjectSources.canonical(actual.get(k))))throw new SecurityException("源构建不属于同一实际项目操作与轮次");
    }
    /** Original approval rows are nested under the task. They do NOT include a taskId of their own. */
    public static boolean reviewMatches(JSONObject approval,String task,String run,int epoch){
        try{
            JSONObject cap=approval.getJSONObject("capability"),view=approval.getJSONObject("firstBuildReview");
            if(!"project_execute".equals(approval.opt("tool"))||!run.equals(approval.opt("runId"))||!task.equals(view.opt("taskId"))||
                !run.equals(view.opt("actionRunId"))||epoch<1||epoch!=view.getInt("actionEpoch")||
                !cap.getString("checkoutId").equals(view.getString("checkoutId"))||!cap.getString("sourceDigest").equals(view.getString("sourceDigest"))||
                !view.getString("sourceDigest").matches("[a-f0-9]{64}"))return false;
            String operation=ProjectBuildPlan.identity(view.getString("actionOperationId"));
            if("build".equals(view.opt("action")))return "project.runtime.run".equals(cap.opt("command"))&&Set.of("npm","pnpm").contains(view.getString("manager"));
            if("dependencies".equals(view.opt("action"))){
                JSONObject lock=view.getJSONObject("lock");String manager=lock.getString("manager");
                return "project.runtime.run".equals(cap.opt("command"))&&manager.equals(view.opt("manager"))&&
                    OwnerDraftNodeDependencies.REGISTRY.equals(lock.opt("registry"))&&Set.of("npm","pnpm").contains(manager)&&
                    lock.getString("lockSha256").matches("[a-f0-9]{64}")&&lock.getInt("packages")>0&&
                    ProjectSources.canonical(OwnerDraftNodeDependencies.argv(manager)).equals(ProjectSources.canonical(view.getJSONArray("argv")));
            }
            return "collect".equals(view.opt("action"))&&ProjectManagedOutput.COMMAND.equals(cap.opt("command"))&&
                !operation.equals(ProjectBuildPlan.identity(view.getString("buildOperationId")))&&!ProjectBuildPlan.identity(view.getString("buildRunId")).isBlank()&&
                view.getInt("buildEpoch")>0&&view.getInt("buildEpoch")<=epoch&&view.getLong("bytes")>=0&&view.getString("sha256").matches("[a-f0-9]{64}")&&
                view.getString("buildReceiptIdentity").matches("[a-f0-9]{64}")&&outputPath(view.getString("path")).equals(view.getString("path"));
        }catch(Exception invalid){return false;}
    }
    /** The stable capability and native plan remain unchanged. A new operation may consume the
     * old decision ONLY for the explicit, proven-not-dispatched native proposal continuation. */
    public static boolean sameApprovedProposal(JSONObject approval,JSONObject live)throws Exception{
        JSONObject previous=approval.optJSONObject("firstBuildReview");if(previous==null)return false;
        if(ProjectSources.canonical(previous).equals(ProjectSources.canonical(live)))return true;
        if(!"approved".equals(approval.opt("state"))||!Boolean.TRUE.equals(approval.opt("nativeCapabilityRevalidationRequired"))||
            !previous.getString("actionRunId").equals(approval.opt("continuedFromRun"))||
            !previous.getString("actionOperationId").equals(approval.opt("notDispatchedOperationId"))||
            !live.getString("actionRunId").equals(approval.opt("runId"))||live.getInt("actionEpoch")!=approval.optInt("continuedToEpoch",-1)||
            live.getInt("actionEpoch")<=previous.getInt("actionEpoch"))return false;
        JSONObject a=ProjectSources.copy(previous),b=ProjectSources.copy(live);
        for(String k:new String[]{"actionRunId","actionEpoch","actionOperationId"}){a.remove(k);b.remove(k);}
        return ProjectSources.canonical(a).equals(ProjectSources.canonical(b));
    }
    public static void requireTooling(JSONObject pin,JSONObject actual)throws Exception{
        if(!pin.getString("runtimeIdentity").equals(actual.opt("identity"))||!Boolean.TRUE.equals(actual.opt("canExecute")))throw new IllegalStateException("本机运行环境在核对后发生变化；没有运行。");
        boolean node=false,manager=false;JSONArray tools=actual.optJSONArray("tools");
        for(int i=0;tools!=null&&i<tools.length();i++){JSONObject t=tools.getJSONObject(i);if(t.optBoolean("hashVerified")||t.optBoolean("probeVerified")){
            node|="node".equals(t.opt("name"));manager|=pin.getString("manager").equals(t.opt("name"));
        }}
        if(!node||!manager)throw new IllegalStateException("这台手机尚未同时核实 Node 和项目的包管理器；没有安装依赖或切换运行位置。");
        if(!actual.optBoolean("managedOutputCollectionAvailable"))throw new IllegalStateException("本机尚无原构建结果的收件能力，不能开始这条验证与交付流程。");
    }
    record ExistingBuildSource(String state,String missingLock){
        ExistingBuildSource(String state){this(state,"");}
    }
    /** Read-only eligibility from the saved source, not an approval or a runtime check. */
    static ExistingBuildSource existingBuildState(JSONObject snapshot){
        try{
            var files=ProjectSources.files(snapshot);JSONObject pkg=ordinaryPackage(files);
            Set<String> paths=sourcePaths(snapshot);
            Object declared=pkg.opt("packageManager");
            if(declared!=null&&!(declared instanceof String))return new ExistingBuildSource("");
            String manager=ProjectCheckoutTooling.manager(paths,pkg.optString("packageManager"));
            if(!Set.of("npm","pnpm").contains(manager))return new ExistingBuildSource("");
            scriptFields(pkg);
            try{OwnerDraftNodeDependencies.requirePlainProject(pkg,paths);}
            catch(SecurityException config){
                return new ExistingBuildSource(paths.contains("pnpm-workspace.yaml")?"workspaces":paths.contains(".npmrc")?"npmrc":"manager_configuration");
            }
            catch(IllegalArgumentException config){return new ExistingBuildSource(pkg.has("workspaces")?"workspaces":"manifest_configuration");}
            String missing="";
            try{missing=OwnerDraftNodeDependencies.missingRequiredLock(pkg,paths,manager);}
            catch(Exception unknown){/* Leave unsupported declarations to the existing review. */}
            return new ExistingBuildSource("available",missing);
        }catch(Exception unavailable){return new ExistingBuildSource("");}
    }
    static void requireExistingConfiguration(JSONObject snapshot)throws Exception{
        var files=ProjectSources.files(snapshot);
        OwnerDraftNodeDependencies.requirePlainProject(ordinaryPackage(files),sourcePaths(snapshot));
    }
    /** ProjectSources.files already validated the snapshot; binary members count as present, too. */
    private static Set<String> sourcePaths(JSONObject snapshot)throws Exception{
        Set<String> paths=new java.util.HashSet<>();JSONArray rows=snapshot.getJSONArray("files");
        for(int i=0;i<rows.length();i++)paths.add(rows.getJSONObject(i).getString("path"));
        return paths;
    }
    private static JSONObject ordinaryPackage(java.util.Map<String,String> files)throws Exception{
        String raw=files.get("package.json");
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("需要可核对的根 package.json（不超过 64 KiB）");
        return dev.pocketengine.features.plugins.mcp.McpJson.parse(raw);
    }
    /** Only these inert manifest declarations reach the private review, never as authority or instructions. */
    public static JSONObject scripts(JSONObject snapshot,JSONObject pin)throws Exception{
        JSONObject version=ProjectSources.describe(snapshot);
        for(String k:new String[]{"projectId","checkoutId","sourceDigest"})if(!pin.getString(k).equals(version.opt(k)))throw new SecurityException("读取期间副本已变化，没有展示另一版脚本");
        var files=ProjectSources.files(snapshot);String raw=files.get("package.json");
        return scriptFields(existingCheckout(pin)?ordinaryPackage(files):raw==null?null:new JSONObject(raw));
    }
    private static JSONObject scriptFields(JSONObject pkg)throws Exception{
        JSONObject scripts=pkg==null?null:pkg.optJSONObject("scripts"),view=new JSONObject();
        if(scripts==null||!(scripts.opt("build") instanceof String build)||build.isBlank())throw new IllegalStateException("package.json 没有非空的 build 脚本；没有当成可用构建。");
        for(String k:new String[]{"prebuild","build","postbuild"})if(scripts.has(k)){
            Object value=scripts.opt(k);
            if(!(value instanceof String text)||text.length()>4096||text.codePoints().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'||c>=0x7f&&c<=0x9f||c>=0x202a&&c<=0x202e||c>=0x2066&&c<=0x2069))
                throw new IllegalStateException("构建脚本不能完整、安全地显示。请先在这个副本中核对 package.json，未开始。");
            view.put(k,text);
        }
        return view;
    }
    public static void requireContext(JSONObject pin,JSONObject context)throws Exception{
        JSONObject lock=pin.optJSONObject(DEPENDENCIES);
        boolean existing=existingCheckout(pin);
        if(pin.length()!=(lock==null?7:8)+(edited(pin)?1:0)||
            (pin.has(EXISTING_CHECKOUT)&&!existing)||(existing?(pin.has("workIdentity")||edited(pin)):!hash(pin.optString("workIdentity")))||
            edited(pin)&&(!hash(pin.optString(EDITED_BASE))||pin.optString(EDITED_BASE).equals(pin.opt("sourceDigest")))||
            pin.has(DEPENDENCIES)&&(lock==null||lock.length()!=2||!lock.getString("planDigest").matches("[a-f0-9]{64}")||
            !pin.getString("manager").equals(lock.getJSONObject("review").opt("manager")))||!"managed_process_v1".equals(context.opt("runtime"))||context.has("draftOnly")||context.has(OwnerRevisionSource.FIELD)||
            !pin.getString("projectId").equals(context.opt("projectId"))||!pin.getString("checkoutId").equals(context.opt("checkoutId"))||
            !pin.getString("sourceDigest").equals(context.opt("initialSourceDigest"))||!reviewedClient(pin).equals(context.opt("clientMessageId"))||
            !Set.of("npm","pnpm").contains(pin.getString("manager"))||!pin.getString("planDigest").matches("[a-f0-9]{64}")||pin.getString("runtimeIdentity").isBlank())
            throw new SecurityException("不是机主审阅的这版私人副本构建交办");
    }
    /** The only two native plan references whose uncertain receipts require process evidence. */
    public static String pendingManagedNodePhase(JSONObject task,JSONObject args)throws Exception{
        JSONObject context=task.optJSONObject("developmentContext"),pin=context==null?null:context.optJSONObject(FIELD);
        if(pin==null||args==null||args.length()!=3||!args.optString("planRef").matches("[a-f0-9-]{36}")||
            !"pocketworld.development-context.v1".equals(context.opt("format"))||
            !"assistant".equals(task.opt("kind"))||!"capabilities".equals(task.opt("executionMode"))||
            !"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||task.has("origin")||
            !task.isNull("teamAssignment")||!task.isNull("parentTaskId"))return null;
        String step=args.optString("stepId"),digest=args.optString("planDigest");
        JSONObject lock=pin.optJSONObject(DEPENDENCIES);
        if(!hash(digest)||!("build".equals(step)&&digest.equals(pin.opt("planDigest"))||
            OwnerDraftNodeDependencies.STEP.equals(step)&&lock!=null&&digest.equals(lock.opt("planDigest"))))return null;
        requireContext(pin,context);
        return step;
    }

    /** Read-only classification of an already claimed operation, not a receipt or a grant. */
    public static JSONObject pendingManagedNode(JSONObject task,JSONObject row)throws Exception{
        if(!"unknown".equals(row.opt("state"))||!"project_execute".equals(row.opt("tool")))return null;
        String phase=pendingManagedNodePhase(task,row.optJSONObject("arguments"));
        JSONObject envelope=row.optJSONObject("receipt"),route=envelope==null?null:envelope.optJSONObject("projectExecution");
        if(phase==null||route==null||!"unknown".equals(envelope.opt("status"))||envelope.opt("accepted")!=JSONObject.NULL||
            !Set.of("project_pending","project_cancel_pending_unknown").contains(envelope.optString("code"))||
            !"project.runtime.run".equals(route.opt("command"))||!"managed_process_v1".equals(route.opt("runtimeId"))||
            !phase.equals(route.opt("intent"))||!row.optString("operationId").equals(route.opt("jobId"))||
            route.optString("approvalId").isEmpty()||!hash(route.optString("capabilityFingerprint"))||!hash(route.optString("requestDigest")))return null;
        JSONObject pin=task.getJSONObject("developmentContext").getJSONObject(FIELD);
        if(!pin.getString("sourceDigest").equals(route.opt("sourceDigest"))||!pin.getString("runtimeIdentity").equals(route.opt("runtimeIdentity")))return null;
        JSONObject binding=ProjectDevelopment.originalPollRequest(task,row).getJSONObject("binding");
        int epoch=binding.getInt("coordinatorEpoch");
        if(row.has("runEpoch")&&row.getInt("runEpoch")!=epoch)return null;
        return new JSONObject().put("format","pocketworld.original-managed-node-pending.v1")
            .put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId")).put("epoch",epoch)
            .put("operationId",binding.getString("operationId")).put("projectId",pin.getString("projectId"))
            .put("checkoutId",pin.getString("checkoutId")).put("phase",phase)
            .put("canObserve",boundManagedNodeBackend(route,envelope.optJSONObject("executionReceipt"),pin.getString("manager"),phase,epoch))
            .put("canCheck",canCheckBareManagedNode(task,row.optJSONObject("arguments"),pin,envelope,route,binding));
    }
    public static boolean ownerObservationSourceVisible(JSONObject task){
        return (!task.has("contextAvailable")||Boolean.TRUE.equals(task.opt("contextAvailable")))&&
            (!task.has("contextWithdrawn")||Boolean.FALSE.equals(task.opt("contextWithdrawn")))&&
            (!task.has("sourceCurrentReadRequired")||Boolean.FALSE.equals(task.opt("sourceCurrentReadRequired")))&&
            !"source_unavailable".equals(task.opt("taskProjection"));
    }
    private static boolean canCheckBareManagedNode(JSONObject task,JSONObject args,JSONObject pin,JSONObject envelope,JSONObject route,JSONObject binding){
        if(!ownerObservationSourceVisible(task)||!existingCheckout(pin)||envelope.has("executionReceipt")||!"project_pending".equals(envelope.opt("code"))||
            !"dispatching".equals(envelope.opt("executionStatus"))||!Boolean.FALSE.equals(task.opt("cancelRequested"))||
            envelope.optBoolean("cancelRequested")||route.optBoolean("cancelRequested")||
            !binding.opt("runId").equals(task.opt("runId"))||binding.optInt("coordinatorEpoch")!=task.optInt("coordinatorEpoch",-1))return false;
        try{return consumedCommandApproval(task,args,route)!=null;}
        catch(Exception unverified){return false;}
    }
    /** Already used in the original native CAS, never available to approve/execute anything again. */
    public static JSONObject consumedCommandApproval(JSONObject task,JSONObject args,JSONObject route)throws Exception{
        JSONArray approvals=task.optJSONArray("approvals");JSONObject matched=null;
        String id=route.getString("approvalId");
        for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject row=approvals.getJSONObject(i);
            if(!id.equals(row.opt("id")))continue;
            if(matched!=null)return null;matched=row;
        }
        return matched!=null&&"consumed".equals(matched.opt("state"))&&"project_execute".equals(matched.opt("tool"))&&
            route.getString("runId").equals(matched.opt("runId"))&&matched.isNull("policyDecision")&&
            ProjectSources.digest(args).equals(matched.opt("argumentsHash"))&&
            ProjectSources.digest(matched.getJSONObject("capability")).equals(route.opt("capabilityFingerprint"))?matched:null;
    }

    /** Only the existing provider's persisted settled ordinary exit can close this first read. */
    public static boolean verifiedFirstTerminalObservation(JSONObject exact,JSONObject route,JSONObject backend)throws Exception{
        String status=backend.optString("status"),phase=exact.optString("intent");
        if(!status.matches("completed|failed")||!Boolean.FALSE.equals(backend.opt("ownedWorkerLive"))||
            "owned_worker_live".equals(backend.opt("observation"))||!backend.isNull("receiptPersistence")||
            !Boolean.TRUE.equals(backend.opt("resourcesReleased"))||!Boolean.TRUE.equals(backend.opt("processGroupAbsent"))||
            !Boolean.FALSE.equals(backend.opt("cancelRequested"))||!Boolean.FALSE.equals(backend.opt("timedOut"))||
            !"pre_exec_manifest_verified".equals(backend.opt("sourceCheckStatus"))||backend.optLong("finishedAt")<=0||
            !dev.pocketengine.features.local.runtime.NativeProcessSupervisor.knownOrdinaryExitContract(backend.optString("nativeResultContract"))||
            !boundManagedNodeBackend(route,backend,exact.getJSONObject("payload").getString("executable"),phase,route.getInt("coordinatorEpoch")))return false;
        JSONObject exit=ProjectExecutionExit.from(exact,route,backend);
        return exit!=null&&"exit_code".equals(exit.opt("kind"))&&"completed".equals(status)==(exit.getInt("value")==0);
    }

    // Same native binding and reviewed command as the explicit terminal observer. A route alone is not a log.
    private static boolean boundManagedNodeBackend(JSONObject route,JSONObject backend,String manager,String phase,int epoch)throws Exception{
        if(backend==null||!"managed_process".equals(backend.opt("provider"))||!"command".equals(backend.opt("kind"))||
            !"foreground".equals(backend.opt("runMode"))||!route.getString("operationId").equals(backend.opt("id"))||
            !manager.equals(backend.opt("requestedExecutable")))return false;
        for(String key:new String[]{"operationId","checkoutId","sourceDigest"})if(!route.getString(key).equals(backend.opt(key)))return false;
        JSONObject link=backend.optJSONObject("projectExecution");if(link==null)return false;
        Object generation=link.opt("coordinatorEpoch");
        if(!(generation instanceof Integer||generation instanceof Long)||((Number)generation).longValue()!=epoch)return false;
        for(String key:new String[]{"authority","command","projectId","checkoutId","taskId","runId","operationId",
            "planDigest","stepId","intent","sourceDigest","requestDigest","runtimeId","runtimeIdentity"})
            if(!route.getString(key).equals(link.opt(key)))return false;
        JSONArray expected="build".equals(phase)?new JSONArray().put("run").put("build"):OwnerDraftNodeDependencies.argv(manager);
        JSONArray actual=backend.optJSONArray("requestedArgv");if(actual==null||actual.length()!=expected.length())return false;
        for(int i=0;i<expected.length();i++)if(!expected.getString(i).equals(actual.opt(i)))return false;
        return true;
    }

    /** Only the original host-enveloped operation may produce a public scalar fact; no output bodies here. */
    public static JSONObject outcomeFact(JSONObject pin,JSONObject task,JSONObject row)throws Exception{
        return outcomeFact(pin,task,row,false);
    }
    public static JSONObject outcomeFact(JSONObject pin,JSONObject task,JSONObject row,boolean ownerFailureDisplay)throws Exception{
        JSONObject request=ProjectDevelopment.originalPollRequest(task,row),plan=request.getJSONObject("plan"),binding=request.getJSONObject("binding");
        if(!task.getString("id").equals(binding.opt("taskId"))||!task.getString("runId").equals(binding.opt("runId"))||
            task.getInt("coordinatorEpoch")!=binding.optInt("coordinatorEpoch",-1)||!row.getString("operationId").equals(binding.opt("operationId")))return null;
        requirePlan(pin,plan,task.getString("id"));
        JSONObject fact=readFact(pin,task,row,request);
        if(ownerFailureDisplay&&fact!=null&&fact.optInt("value",-1)>0){
            JSONObject step=ProjectBuildPlan.step(plan,request.getString("stepId")),payload=step.getJSONObject("payload");
            String manager=pin.getString("manager");
            JSONArray approved=switch(fact.optString("kind")){
                case "build_exit" -> new JSONArray().put("run").put("build");
                case "dependency_exit" -> OwnerDraftNodeDependencies.argv(manager);
                default -> null;
            };
            // Only the closed Node command vocabulary. Never widen the generic
            // journal projection to script bodies, arbitrary argv or env.
            if(approved!=null&&Set.of("npm","pnpm").contains(manager)&&manager.equals(payload.opt("executable"))&&
                "project.runtime.run".equals(step.opt("localCommand"))&&ProjectSources.canonical(approved).equals(ProjectSources.canonical(payload.opt("argv")))){
                JSONArray command=new JSONArray().put(manager);
                for(int i=0;i<approved.length();i++)command.put(approved.getString(i));
                fact.put("approvedCommand",command).put("planDigest",plan.getString("planDigest"));
            }
        }
        return fact;
    }
    /** Carries only actual same-task receipts; an earlier dependency receipt is evidence, never approval. */
    public static JSONObject priorDependencyFact(JSONObject pin,JSONObject task,JSONObject row,JSONObject binding)throws Exception{
        JSONObject request=ProjectDevelopment.originalPollRequest(task,row),plan=request.getJSONObject("plan");
        requireSourceBinding(request.getJSONObject("binding"),binding);
        if(!"dependencies".equals(plan.opt("intent")))return null;
        requirePlan(pin,plan,task.getString("id"));
        return readFact(pin,task,row,request);
    }
    private static JSONObject readFact(JSONObject pin,JSONObject task,JSONObject row,JSONObject request)throws Exception{
        JSONObject plan=request.getJSONObject("plan"),binding=request.getJSONObject("binding");
        if(ProjectDevelopment.recordedReceipt(task,row)==null)return null;
        JSONObject raw=row.getJSONObject("receipt").getJSONObject("executionReceipt");
        JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,request.getString("stepId")));
        if("collect".equals(plan.opt("intent"))){
            if(!"completed".equals(raw.opt("status"))||!ProjectManagedOutput.terminalReceipt(exact,raw))return null;
            return reviewOperation(pin,plan,binding).put("kind","saved_file");
        }
        JSONObject exit=ProjectExecutionExit.from(exact,binding,raw);
        if(exit==null||!"exit_code".equals(exit.opt("kind"))||!"pre_exec_manifest_verified".equals(raw.opt("sourceCheckStatus"))||
            !Boolean.TRUE.equals(raw.opt("resourcesReleased"))||!Boolean.TRUE.equals(raw.opt("processGroupAbsent"))||
            !Boolean.FALSE.equals(raw.opt("timedOut"))||!Boolean.FALSE.equals(raw.opt("cancelRequested")))return null;
        int code=exit.getInt("value");if(code==0&&!"completed".equals(raw.opt("status"))||code!=0&&!"failed".equals(raw.opt("status")))return null;
        if("dependencies".equals(plan.opt("intent")))return new JSONObject().put("kind","dependency_exit").put("value",code)
            .put("actionRunId",binding.getString("runId")).put("actionEpoch",binding.getInt("coordinatorEpoch")).put("actionOperationId",binding.getString("operationId"));
        return reviewOperation(pin,plan,binding).put("kind","build_exit").put("value",code);
    }
    public static dev.pocketengine.core.RunOutcome settle(dev.pocketengine.core.RunOutcome original,JSONObject fact)throws Exception{
        if(!"completed".equals(original.status))return original;
        boolean file=fact!=null&&"saved_file".equals(fact.opt("kind")),exit=fact!=null&&"build_exit".equals(fact.opt("kind"));
        boolean ok=file||exit&&fact.getInt("value")==0;
        boolean dependency=fact!=null&&"dependency_exit".equals(fact.opt("kind"));
        String text=dependency?fact.getInt("value")==0?"本机依赖准备命令已退出 0。还要核对实际包并另行批准构建；当前没有构建结果。":
            "本机依赖准备失败，退出码 "+fact.getInt("value")+"。请查看本任务的原日志；需要再试也须核对并确认同一锁。没有构建。":file?"本任务第 "+fact.getInt("actionEpoch")+" 轮已保留 "+fact.getString("path")+"（"+fact.getLong("bytes")+" 字节）。它是在第 "+fact.getInt("buildEpoch")+" 轮原构建后观察并选取的文件，不证明由本轮或该次构建新生成。可以在任务文件里查看。":
            exit?"独立副本的本机构建命令退出码为 "+fact.getInt("value")+(ok?"。退出成功不等于已经保留生成文件；可在本任务继续核对输出。":"。请查看这次执行的日志，在原任务决定下一步；没有自动安装或重跑。"):
            "这次没有核对到原构建的完整退出或已保留文件，不能凭文字答复算作完成。请回本任务查看待批准的命令或已发生的执行，不要重复提交。";
        JSONObject evidence=new JSONObject(original.evidence.toString()).put("modelSummary",text);
        if(fact!=null)evidence.put("firstBuildObservation",fact);return new dev.pocketengine.core.RunOutcome(ok?"completed":dependency&&fact.getInt("value")==0?"awaiting_user":"failed",text,evidence);
    }
    /** Check again before native I/O. A generated-file save must trace to this task's actual build, not merely the same filename. */
    public static void requirePlan(JSONObject pin,JSONObject issued,String task)throws Exception{
        JSONObject p=ProjectBuildPlan.verified(issued);
        if(!pin.getString("projectId").equals(p.opt("projectId"))||!pin.getString("checkoutId").equals(p.opt("checkoutId"))||
            !pin.getString("sourceDigest").equals(p.opt("sourceDigest"))||!pin.getString("runtimeIdentity").equals(p.opt("runtimeIdentity"))||
            !"managed_process_v1".equals(p.opt("runtimeId")))throw new SecurityException("原构建不能更换工程、版本或运行环境");
        if("build".equals(p.opt("intent"))&&pin.getString("planDigest").equals(p.opt("planDigest")))return;
        JSONObject lock=pin.optJSONObject(DEPENDENCIES);
        if(lock!=null&&"dependencies".equals(p.opt("intent"))&&"locked_node_registry".equals(p.opt("ecosystem"))&&lock.getString("planDigest").equals(p.opt("planDigest")))return;
        if("collect".equals(p.opt("intent"))&&"managed_selected_output".equals(p.opt("ecosystem"))){
            JSONObject choice=ProjectManagedOutput.verified(p.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").getJSONObject("artifactSelection"));
            outputPath(choice.getString("path"));
            if(pin.getString("planDigest").equals(choice.getJSONObject("buildPlan").opt("planDigest"))&&task.equals(choice.getJSONObject("buildBinding").opt("taskId")))return;
        }
        throw new SecurityException("这次只审阅原版的构建或原构建后观察并选定的文件；没有执行其他操作。");
    }
}
