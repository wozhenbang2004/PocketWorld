package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.ProjectDevelopment;
import dev.pocketengine.features.local.project.ProjectExecutionExit;
import dev.pocketengine.features.local.project.ProjectFileBytes;
import dev.pocketengine.features.local.project.ProjectFileDraft;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner-reviewed Python source only. No command registry, project writeback, or sandbox. */
public final class OwnerPythonRun {
    static final String FIELD = "ownerPythonScript";
    public static final String FORMAT = "pocketworld.owner-python-script.v1";
    public static final String WORK = "ownerPythonScriptRun", EXIT = "ownerPythonScriptExit";
    public static final String COMMAND = "project.pythonScript.owner", MODE = "OWNER_PYTHON_SCRIPT";
    public static final String ROUTE = "ownerPythonScriptDigest";
    public static final int MAX_SOURCE = 256 * 1024;
    public static final int MAX_PROGRAM_FILES = 8;
    private static final String PROGRAM_FILES = "programFiles";
    static final String INTERPRETER = "/usr/bin/python3";
    // The owned guest mounts only the frozen stage at /workspace. In isolated -c mode
    // it is not importable at startup. Compile the exact selected leaf first, then allow
    // reviewed sibling imports after the managed standard library, never before entry.
    private static final String PROGRAM_BOOTSTRAP = """
            import sys as _pw_sys
            __file__ = '/workspace/' + _pw_sys.argv.pop(1)
            _pw_sys.argv[0] = __file__
            with open(__file__, 'rb') as _pw_source:
                _pw_code = compile(_pw_source.read(), __file__, 'exec', dont_inherit=True)
            _pw_sys.path.append('/workspace')
            del _pw_source, _pw_sys
            exec(globals().pop('_pw_code'), globals())
            """;
    static final long TIMEOUT_MILLIS = 60_000L;
    private static final int MAX_OUTPUT = 64 * 1024, MAX_STAGED = 16;
    private static final int MAX_COMPLETE_LOG_CHUNKS = 2048;
    private static final int MAX_PATH = 240, MAX_PARENT_DEPTH = 4;
    private static final Pattern SCRIPT_LEAF = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,119}\\.py");
    private static final Pattern MODULE_LEAF = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,119}\\.py");
    private static final Pattern SOURCE_FOLDER = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,63}");
    private static final long REVIEW_NANOS = 120_000_000_000L;
    private static final String INTENT = "owner_python_script";
    private static final String RESULT = "pocketworld.owner-python-script-result.v1";
    private OwnerPythonRun() {}

    record Tool(String generation, String sha256) {
        Tool {
            requireDigest(generation);
            requireDigest(sha256);
        }
    }

    private record InputFile(String path, String sha256, int size) {
        private InputFile {
            if (!supportsPath(path) || size < 0 || size > MAX_SOURCE)
                throw new SecurityException("原 Python 源码路径或大小不可核对");
            requireDigest(sha256);
        }
        JSONObject view() throws Exception {
            return new JSONObject().put("path", path).put("sha256", sha256).put("bytes", size);
        }
    }

    record Source(String id, String project, String checkout, String path, String sha256, int size, Tool tool,
                  List<InputFile> programFiles) {
        Source {
            requireUuid(id);
            requireUuid(project);
            requireUuid(checkout);
            if (!supportsPath(path) || size < 0 || size > MAX_SOURCE)
                throw new SecurityException("请选择所选副本中一个受支持路径的已保存 Python 文件");
            requireDigest(sha256);
            programFiles = List.copyOf(programFiles);
            if (!programFiles.isEmpty()) {
                if (programFiles.size() < 2 || programFiles.size() > MAX_PROGRAM_FILES)
                    throw new SecurityException("本机小程序只带入 2 至 8 个同目录 Python 源码文件");
                int total = 0;
                boolean entry = false;
                String previous = "";
                for (InputFile file : programFiles) {
                    if (!folder(path).equals(folder(file.path)) || previous.compareTo(file.path) >= 0)
                        throw new SecurityException("原 Python 源码不在同一个已选目录");
                    if (file.path.equals(path)) {
                        entry = sha256.equals(file.sha256) && size == file.size;
                    } else if (!programCompanion(path, file.path))
                        throw new SecurityException("这不是同目录可直接导入的 Python 模块");
                    total += file.size;
                    previous = file.path;
                }
                if (!entry || total > MAX_SOURCE)
                    throw new SecurityException("原入口或 256 KiB 同目录源码范围不符");
            }
        }
        // Job ordering is private to this runner, not the real checkout's build/output order.
        String executionCheckout() {
            String key = FORMAT + "\0execution-checkout\0" + project + "\0" + checkout;
            String value = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.US_ASCII)).toString();
            if (value.equals(checkout)) throw new SecurityException("原副本与脚本执行范围不能相同");
            return value;
        }
        String leaf() {
            return scriptLeaf(path);
        }
        boolean directory() { return !programFiles.isEmpty(); }
        String stageName(InputFile file) {
            return scriptLeaf(file.path);
        }
        List<InputFile> stagedSources() {
            return directory() ? programFiles : List.of(new InputFile(path, sha256, size));
        }
        JSONArray argv() {
            return new JSONArray(commandArguments());
        }
        List<String> commandArguments() {
            return directory() ? List.of("-I", "-S", "-B", "-u", "-c", PROGRAM_BOOTSTRAP, leaf())
                    : List.of("-I", "-S", "-B", "-u", "./" + leaf());
        }
        JSONArray manifest() throws Exception {
            var files = new TreeMap<String, InputFile>();
            for (InputFile file : stagedSources()) files.put(stageName(file), file);
            var result = new JSONArray();
            for (var file : files.entrySet()) result.put(file.getValue().view().put("path", file.getKey()));
            return result;
        }
        JSONObject pin() throws Exception {
            JSONObject value = new JSONObject().put("format", FORMAT).put("requestId", id).put("projectId", project)
                    .put("checkoutId", checkout).put("path", path).put("sha256", sha256).put("bytes", size)
                    .put("runtimeIdentity", tool.generation()).put("interpreterSha256", tool.sha256());
            if (directory()) {
                JSONArray files = new JSONArray();
                for (InputFile file : programFiles) files.put(file.view());
                value.put(PROGRAM_FILES, files);
            }
            return value;
        }
        JSONObject snapshot(Map<String, byte[]> bytes) throws Exception {
            JSONArray files = manifest();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                file.remove("bytes");
                file.put("binary", true).put("contentBase64", Base64.getEncoder().encodeToString(bytes.get(file.getString("path"))));
            }
            return new JSONObject().put("checkout", new JSONObject().put("id", executionCheckout()).put("projectId", project))
                    .put("files", files);
        }
    }

    public static boolean supportsPath(String path) {
        if (path == null || path.length() > MAX_PATH || !ProjectSources.sourcePath(path)) return false;
        String[] parts = path.split("/", -1);
        int leaf = parts.length - 1;
        if (leaf > MAX_PARENT_DEPTH || !SCRIPT_LEAF.matcher(parts[leaf]).matches()) return false;
        for (int i = 0; i < leaf; i++) if (!SOURCE_FOLDER.matcher(parts[i]).matches()) return false;
        return true;
    }

    static String scriptLeaf(String originalPath) {
        if (!supportsPath(originalPath)) throw new SecurityException("原脚本路径不可核对");
        return originalPath.substring(originalPath.lastIndexOf('/') + 1);
    }
    private static String folder(String path) { return path.substring(0, path.lastIndexOf('/') + 1); }
    public static boolean programCompanion(String entry, String path) {
        if (!supportsPath(entry) || !supportsPath(path) || entry.equals(path) || !folder(entry).equals(folder(path))) return false;
        return MODULE_LEAF.matcher(scriptLeaf(path)).matches();
    }

    public static boolean matchesRoute(JSONObject route) {
        return route != null && route.has(ROUTE);
    }

    /** The caller is the foreground ordinary native file page, not Web-supplied text or selectors. */
    static Review review(ManagedProjectRuntime runtime, ProjectFileDraft editor, byte[] actualSaved,
                         ProjectRuntime.LiveGate currentOrdinaryFile) throws Exception {
        return review(runtime, editor, actualSaved, Map.of(), currentOrdinaryFile);
    }

    static Review reviewDirectory(ManagedProjectRuntime runtime, ProjectFileDraft editor, byte[] actualSaved,
                                  Map<String, byte[]> siblings, ProjectRuntime.LiveGate currentOrdinaryFile) throws Exception {
        if (siblings.isEmpty() || siblings.size() >= MAX_PROGRAM_FILES)
            throw new SecurityException("请选择包含 2 至 8 个已保存 Python 源码的同一目录");
        return review(runtime, editor, actualSaved, siblings, currentOrdinaryFile);
    }

    private static Review review(ManagedProjectRuntime runtime, ProjectFileDraft editor, byte[] actualSaved,
                                 Map<String, byte[]> siblings, ProjectRuntime.LiveGate currentOrdinaryFile) throws Exception {
        currentOrdinaryFile.check();
        if (editor == null || editor.creating || editor.dirty() || editor.conflict()
                || actualSaved == null || actualSaved.length > MAX_SOURCE)
            throw new SecurityException("请先保存并核对原副本中的同一个 Python 文件");
        JSONObject latest = editor.latestReview();
        if (latest == null || !Boolean.FALSE.equals(latest.opt("deleted"))
                || !editor.projectId.equals(latest.opt("projectId")) || !editor.checkoutId.equals(latest.opt("checkoutId"))
                || !editor.path.equals(latest.opt("path")) || !editor.baseHash.equals(latest.opt("expectedHash"))
                || !editor.baseHash.equals(latest.opt("sha256")) || latest.optLong("bytes", -1L) != actualSaved.length)
            throw new SecurityException("没有核对到原编辑器已保存的同一个文件");
        Map<String, byte[]> bytes = new TreeMap<>();
        byte[] entry = actualSaved.clone();
        bytes.put(scriptLeaf(editor.path), entry);
        boolean retained = false;
        try {
            String text = sourceText(entry);
            if (!editor.baseContent.equals(text) || !editor.baseHash.equals(ProjectSources.sha256(entry)))
                throw new SecurityException("原脚本的实际已保存文字已变化，请重新核对");
            Map<String, InputFile> inputs = new TreeMap<>();
            if (!siblings.isEmpty()) inputs.put(editor.path, new InputFile(editor.path, editor.baseHash, entry.length));
            int size = entry.length;
            for (var sibling : siblings.entrySet()) {
                byte[] observed = sibling.getValue();
                if (!programCompanion(editor.path, sibling.getKey()) || observed == null || observed.length > MAX_SOURCE - size)
                    throw new SecurityException("同目录在核对期间变化或源码超过 256 KiB");
                byte[] frozen = observed.clone();
                bytes.put(scriptLeaf(sibling.getKey()), frozen);
                sourceText(frozen);
                inputs.put(sibling.getKey(), new InputFile(sibling.getKey(), ProjectSources.sha256(frozen), frozen.length));
                size += frozen.length;
            }
            Source source = new Source(UUID.randomUUID().toString(), editor.projectId, editor.checkoutId, editor.path,
                    editor.baseHash, actualSaved.length, runtime.ownerPythonTool(), List.copyOf(inputs.values()));
            currentOrdinaryFile.check();
            Review result = new Review(runtime, source, plan(source), bytes, currentOrdinaryFile);
            retained = true;
            return result;
        } finally {
            if (!retained) wipe(bytes);
        }
    }
    private static String sourceText(byte[] bytes) throws Exception {
        String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (value.indexOf('\0') >= 0) throw new SecurityException("Python 源码必须是实际保存的 UTF-8 文字");
        return value;
    }
    private static void wipe(Map<String, byte[]> bytes) {
        bytes.values().forEach(value -> Arrays.fill(value, (byte)0));
    }

    public static final class Review implements AutoCloseable {
        private final ManagedProjectRuntime runtime;
        private final Source source;
        private final JSONObject plan;
        private final ProjectRuntime.LiveGate currentOrdinaryFile;
        private final long expires = System.nanoTime() + REVIEW_NANOS;
        private Map<String, byte[]> bytes;
        private boolean agreed;
        private Review(ManagedProjectRuntime runtime, Source source, JSONObject plan, Map<String, byte[]> bytes,
                       ProjectRuntime.LiveGate currentOrdinaryFile) {
            this.runtime = runtime; this.source = source; this.plan = plan;
            this.bytes = bytes; this.currentOrdinaryFile = currentOrdinaryFile;
        }
        private void requireAvailable() {
            if (bytes == null || System.nanoTime() - expires >= 0) throw new SecurityException("原脚本核对已结束");
        }
        void requireCurrent() throws Exception {
            currentOrdinaryFile.check();
            synchronized (this) { requireAvailable(); }
        }
        public JSONObject view() throws Exception {
            requireCurrent();
            return source.pin().put("timeoutMillis", TIMEOUT_MILLIS).put("originalCheckoutMounted", false)
                    .put("dependenciesInstalledByAction", false).put("outputFilesReturned", false)
                    .put("scopeEnforced", false).put("networkMayBeUsed", true).put("executionAuthorized", false);
        }
        private JSONObject planForOwnerApproval() throws Exception {
            requireCurrent();
            return ProjectSources.copy(plan);
        }
        private JSONObject capabilityForOwnerApproval() throws Exception {
            requireCurrent();
            return new JSONObject().put("format", "pocketworld.owner-python-script-capability.v1")
                    .put("feature", "local").put("runtime", ManagedProjectRuntime.RUNTIME_ID)
                    .put("command", "project.runtime.run").put("requiresOwnerConsent", true)
                    .put("planDigest", plan.getString("planDigest")).put(FIELD, source.pin())
                    .put("scopeEnforced", false).put("modelDispatchAllowed", false);
        }
        private String routeDigestForOwnerApproval() throws Exception {
            requireCurrent();
            return ProjectSources.digest(source.pin());
        }
        /** Only the explicit native same-editor confirmation may convert this opaque review. */
        public Confirmed confirm() throws Exception {
            requireCurrent();
            synchronized (this) {
                requireAvailable();
                if (agreed) throw new SecurityException("原脚本核对已使用");
                agreed = true;
                return new Confirmed(this);
            }
        }
        /** Only called from the native consumed-approval path for this exact owner Work. */
        private RuntimeReceipt startApproved(TaskStore.OwnerPythonOriginal claim, RunControl original,
                                     ProjectRuntime.LiveGate approvedOriginalWork) throws Exception {
            requireCurrent();
            JSONObject nativeRoute = claim.route();
            Original run = original(plan, nativeRoute);
            run.ref.checkControl(original);
            approvedOriginalWork.check();
            // No owner-window or SQL checks while holding the buffer monitor.
            currentOrdinaryFile.check();
            Map<String, byte[]> owned;
            synchronized (this) { requireAvailable(); owned = bytes; bytes = null; }
            Stage stage = null;
            boolean passed = false;
            try {
                try {
                    stage = runtime.materializeOwnerPython(source, owned);
                } catch (Exception failed) {
                    if (failed instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw failed;
                    // This one-use buffer has already been taken. Materialization cannot call the provider.
                    // Do not convert any later exception, missing receipt, or cleanup state to this fact.
                    throw new StageNotStarted(claim);
                }
                RuntimeWorkspace physical = new RuntimeWorkspace(stage.directory(), source.project, source.executionCheckout());
                RuntimeInvocation invocation = new RuntimeInvocation(run.exact, nativeRoute, source.snapshot(owned),
                        original, approvedOriginalWork, physical);
                invocation.workspace().verify(invocation);
                currentOrdinaryFile.check();
                passed = true; // the original provider owns the stage from this point, even if its ACK is lost
                return runtime.startOwnerPython(invocation, source.tool, stage);
            } finally {
                wipe(owned);
                if (!passed && stage != null) stage.close();
            }
        }
        @Override public synchronized void close() {
            if (bytes != null) { wipe(bytes); bytes = null; }
        }
    }

    /** Only the synchronous native single-script staging branch can produce this proof. */
    public static final class StageNotStarted extends Exception {
        private final TaskStore.OwnerPythonOriginal approved;
        private StageNotStarted(TaskStore.OwnerPythonOriginal approved) { this.approved = approved; }
        public boolean matches(TaskStore.OwnerPythonOriginal candidate) { return approved == candidate; }
    }

    public static final class Confirmed implements AutoCloseable {
        private final Review review;
        private boolean entered;
        private Confirmed(Review review) { this.review = review; }
        public void requireHost(ManagedProjectRuntime runtime) throws Exception {
            if (runtime != review.runtime) throw new SecurityException("本机脚本宿主已变化");
            requireCurrent();
            if (!review.source.tool.equals(runtime.ownerPythonTool()))
                throw new SecurityException("原 Python 工具已变化，未启动脚本");
            requireCurrent();
        }
        public void requireCurrent() throws Exception { review.requireCurrent(); }
        public JSONObject plan() throws Exception { return review.planForOwnerApproval(); }
        public JSONObject capability() throws Exception { return review.capabilityForOwnerApproval(); }
        public String routeDigest() throws Exception { return review.routeDigestForOwnerApproval(); }
        public void enter() throws Exception {
            requireCurrent();
            synchronized (this) {
                if (entered) throw new SecurityException("原脚本批准已经使用");
                entered = true;
            }
        }
        public RuntimeReceipt startApproved(TaskStore.OwnerPythonOriginal claim, RunControl original,
                                            ProjectRuntime.LiveGate live) throws Exception {
            synchronized (this) {
                if (!entered || !review.source.id.equals(claim.id()))
                    throw new SecurityException("没有这次脚本的原准入");
            }
            return review.startApproved(claim, original, live);
        }
        @Override public void close() { review.close(); }
    }

    public static JSONObject scopeForPlan(JSONObject plan) throws Exception {
        Source source = sourceForPlan(plan);
        return source.pin().put("taskId", source.id).put("runId", source.id).put("operationId", source.id)
                .put("epoch", 1).put("planDigest", plan.getString("planDigest")).put("scriptInputDigest", plan.getString("sourceDigest"))
                .put("modelDispatchAllowed", false).put("replayAllowed", false);
    }

    public static JSONObject exactForClaim(JSONObject plan, JSONObject route) throws Exception {
        return original(plan, route).exact();
    }

    private static JSONObject plan(Source source) throws Exception {
        JSONArray manifest = source.manifest();
        String digest = ProjectSources.manifestDigest(manifest);
        JSONObject payload = new JSONObject().put("checkoutId", source.executionCheckout()).put("expectedSourceDigest", digest)
                .put("executable", "python3").put("argv", source.argv()).put("workingDirectory", ".")
                .put("environment", new JSONObject()).put("timeoutMillis", TIMEOUT_MILLIS).put("runMode", "foreground")
                .put("terminalMode", "pipes").put(FIELD, source.pin());
        JSONObject step = new JSONObject().put("id", "command").put("stepId", "command").put("localCommand", "project.runtime.run")
                .put("payload", payload).put("approvalRequired", true).put("label", source.directory() ? "运行这份已保存的同目录 Python 程序" : "运行这份已保存的 Python 脚本")
                .put("cancelReliable", false)
                .put("effects", new JSONObject().put("runtime", ManagedProjectRuntime.RUNTIME_ID).put("writes", true)
                        .put("networkMayBeUsed", true).put("scopeEnforced", false).put("interactiveInput", false)
                        .put("argumentTransport", "literal_argv"));
        JSONObject core = new JSONObject().put("format", "pocketworld.project-plan.v1").put("projectId", source.project)
                .put("checkoutId", source.executionCheckout()).put("sourceDigest", digest).put("sourceManifest", manifest)
                .put("intent", INTENT).put("ecosystem", INTENT).put("runtimeId", ManagedProjectRuntime.RUNTIME_ID)
                .put("runtimeIdentity", source.tool.generation()).put("runMode", "foreground").put("available", true)
                .put("warnings", new JSONArray().put((source.directory() ? "只复制已核对的同目录 Python 源码，不安装依赖，不带回运行生成的文件。"
                        : "只复制这一份已保存脚本，不安装依赖，不带回运行生成的文件。")
                        + "脚本可能访问文件或网络，这不是沙箱。只运行你信任的代码。"))
                .put("automaticChaining", false).put("steps", new JSONArray().put(step));
        String seal = ProjectSources.digest(core);
        return core.put("planId", seal).put("planDigest", seal);
    }

    static boolean claims(JSONObject exact) {
        JSONObject payload = exact.optJSONObject("payload");
        return payload != null && payload.has(FIELD);
    }

    private record Original(Source source, JSONObject exact, RuntimeExecutionRef ref) {}
    /** Rebuild only from persisted original approved native Work data, never from a handle, PID or model JSON. */
    private static Source sourceForPlan(JSONObject savedPlan) throws Exception {
        JSONObject checked = ProjectBuildPlan.verified(savedPlan);
        JSONObject pin = ProjectBuildPlan.step(checked, "command").getJSONObject("payload").getJSONObject(FIELD);
        var inputs = new java.util.ArrayList<InputFile>();
        if (pin.has(PROGRAM_FILES)) {
            JSONArray files = pin.getJSONArray(PROGRAM_FILES);
            if (files.length() < 2 || files.length() > MAX_PROGRAM_FILES)
                throw new SecurityException("原 Python 程序的源文件范围不完整");
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                inputs.add(new InputFile(file.getString("path"), file.getString("sha256"), integer(file, "bytes", 0, MAX_SOURCE)));
            }
        }
        Source source = new Source(pin.getString("requestId"), pin.getString("projectId"), pin.getString("checkoutId"),
                pin.getString("path"), pin.getString("sha256"), integer(pin, "bytes", 0, MAX_SOURCE),
                new Tool(pin.getString("runtimeIdentity"), pin.getString("interpreterSha256")), inputs);
        if (!ProjectSources.canonical(plan(source)).equals(ProjectSources.canonical(checked)))
            throw new SecurityException("原脚本计划或已核对字节范围已变化");
        return source;
    }
    private static Original original(JSONObject savedPlan, JSONObject route) throws Exception {
        Source source = sourceForPlan(savedPlan);
        JSONObject exact = ProjectDevelopment.exact(savedPlan, ProjectBuildPlan.step(savedPlan, "command"));
        if (!"native_host_operation".equals(route.opt("authority")) || integer(route, "coordinatorEpoch", 1, 1) != 1
                || !ProjectSources.digest(source.pin()).equals(route.opt(ROUTE)))
            throw new SecurityException("没有这次本机脚本的原审批绑定");
        requireUuid(route.getString("approvalId"));
        for (String key : Set.of("taskId", "runId", "operationId", "jobId"))
            if (!source.id.equals(route.opt(key))) throw new SecurityException("原脚本运行身份不一致");
        for (String key : Set.of("projectId", "checkoutId", "sourceDigest", "planDigest", "requestDigest",
                "runtimeId", "runtimeIdentity", "command", "stepId", "intent"))
            if (!exact.get(key).equals(route.opt(key))) throw new SecurityException("原脚本运行范围不一致");
        return new Original(source, exact, RuntimeExecutionRef.fromHost(exact, route));
    }

    /** The existing recorder gets the actual provider receipt; Web receives only the typed scalar view. */
    public record Retained(RuntimeReceipt nativeReceipt, String viewJson) {
        public JSONObject view() throws Exception { return new JSONObject(viewJson); }
    }
    static Retained retainedExit(ManagedProjectRuntime runtime, JSONObject plan, JSONObject route,
                                 RunControl read, BooleanSupplier sameWindow) throws Exception {
        Original run = original(plan, route);
        RuntimeReceipt receipt = terminal(runtime, run, read, sameWindow);
        JSONObject actual = receipt == null ? null : exit(run, route, receipt.json());
        return actual == null ? null : new Retained(receipt, actual.toString());
    }

    /** A locator, not authority or a body hash. Recheck the canonical terminal receipt before a full read. */
    public record CompleteText(int bytes,String receiptSha256) {
        public CompleteText {
            if(bytes<=MAX_OUTPUT||bytes>OwnerPythonOutputText.MAX_RAW)
                throw new IllegalArgumentException("完整原输出的大小不可核对");
            requireDigest(receiptSha256);
        }
        public JSONObject view()throws Exception{
            return new JSONObject().put("bytes",bytes).put("receiptSha256",receiptSha256);
        }
    }
    private static CompleteText completeTextFrom(JSONObject receipt)throws Exception{
        Object size=receipt.opt("storedOutputBytes"), cursor=receipt.opt("logCursor");
        if(!(size instanceof Integer||size instanceof Long)||!(cursor instanceof Integer||cursor instanceof Long))return null;
        long bytes=((Number)size).longValue(), chunks=((Number)cursor).longValue();
        if(bytes<=MAX_OUTPUT||bytes>OwnerPythonOutputText.MAX_RAW||chunks<=0||chunks>MAX_COMPLETE_LOG_CHUNKS||
                !Boolean.TRUE.equals(receipt.opt("outputComplete"))||!Boolean.FALSE.equals(receipt.opt("outputTruncated"))||
                !exactOutputBytes(receipt,"outputBytes",(int)bytes))return null;
        return new CompleteText((int)bytes,ProjectSources.digest(receipt));
    }

    /** Explicit second read of the same settled native job; the two actual pipe channels are never merged. */
    static JSONObject openOutput(ManagedProjectRuntime runtime, JSONObject plan, JSONObject route,
                                 RunControl read, BooleanSupplier sameWindow, CompleteText requested,
                                 java.util.function.Consumer<OwnerPythonOutputText> completeText) throws Exception {
        Original run = original(plan, route);
        RuntimeReceipt receipt = terminal(runtime, run, read, sameWindow);
        JSONObject before = receipt == null ? null : receipt.json();
        JSONObject observed = before == null ? null : exit(run, route, before);
        if (observed == null) return null;
        CompleteText eligible = completeText!=null&&"exit_code".equals(observed.opt("exitKind"))&&
                Boolean.FALSE.equals(observed.opt("timedOut"))&&Boolean.FALSE.equals(observed.opt("cancelRequested"))
                ? completeTextFrom(before) : null;
        if(requested!=null&&!requested.equals(eligible))
            throw new SecurityException("原输出范围或结束回执已变化，没有读取其他输出");
        int limit=requested==null?MAX_OUTPUT:requested.bytes();
        JSONObject page = runtime.ownerPythonLog(run.ref, limit, sameWindow);
        if (!run.source.id.equals(page.opt("operationId"))) throw new SecurityException("原脚本日志不匹配");
        JSONArray rows = page.getJSONArray("chunks");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int cursor = 0;
        for (int i = 0; i < rows.length(); i++) {
            if (read.isCancelled() || !sameWindow.getAsBoolean()) throw new SecurityException("原脚本工作已关闭");
            JSONObject row = rows.getJSONObject(i);
            if (integer(row, "sequence", 1, Integer.MAX_VALUE) != ++cursor)
                throw new SecurityException("原脚本日志缺页");
            int stream = integer(row, "stream", 1, 2), count = integer(row, "bytes", 1, 8192);
            String encoded = row.getString("dataBase64");
            if (encoded.length() > 10924 || count > limit - stdout.size() - stderr.size())
                throw new SecurityException("原脚本日志超出读取预算");
            byte[] chunk = Base64.getDecoder().decode(encoded);
            if (chunk.length != count) throw new SecurityException("原脚本日志字节数不一致");
            (stream == 1 ? stdout : stderr).write(chunk);
        }
        int originalCursor = integer(before, "logCursor", 0, Integer.MAX_VALUE);
        boolean more = Boolean.TRUE.equals(page.opt("hasMore"));
        boolean incomplete = Boolean.TRUE.equals(page.opt("logIncompleteTail"));
        if (cursor != integer(page, "nextCursor", 0, Integer.MAX_VALUE) || cursor > originalCursor
                || !more && !incomplete && cursor != originalCursor || more && cursor == originalCursor)
            throw new SecurityException("原脚本日志范围未核对，不把缺失的日志显示为空");
        RuntimeReceipt after = terminal(runtime, run, read, sameWindow);
        if (after == null || !ProjectSources.canonical(before).equals(ProjectSources.canonical(after.json())))
            throw new SecurityException("原脚本退出回执或日志已变化");
        boolean truncated=more||incomplete||Boolean.TRUE.equals(page.opt("outputTruncated"))||!Boolean.TRUE.equals(before.opt("outputComplete"));
        int count=stdout.size()+stderr.size();
        boolean verifiedComplete=!truncated&&count>0&&cursor==originalCursor&&
                Boolean.FALSE.equals(before.opt("outputTruncated"))&&exactOutputBytes(before,"storedOutputBytes",count)&&
                exactOutputBytes(before,"outputBytes",count)&&!"no_child".equals(observed.opt("exitKind"));
        if(requested!=null&&(!verifiedComplete||count!=requested.bytes()||
                !Boolean.FALSE.equals(page.opt("hasMore"))||!Boolean.FALSE.equals(page.opt("logIncompleteTail"))||
                !Boolean.FALSE.equals(page.opt("outputTruncated"))))
            throw new SecurityException("没有核对到完整的原输出，没有保存片段");
        if(completeText!=null&&verifiedComplete){
            byte[] out=stdout.toByteArray(),err=stderr.toByteArray();
            try{
                OwnerPythonOutputText captured=OwnerPythonOutputText.capture(scopeForPlan(plan),observed,out,err);
                if(captured!=null)completeText.accept(captured);
                else if(requested!=null)throw new SecurityException("完整原输出不是可保存的 UTF-8 文字");
            }finally{Arrays.fill(out,(byte)0);Arrays.fill(err,(byte)0);}
        }
        if(requested!=null)return observed.put("completeText",requested.view());
        if(eligible!=null&&more&&!incomplete&&Boolean.FALSE.equals(page.opt("outputTruncated")))
            observed.put("completeText",eligible.view());
        return observed.put("stdout", stdout.toString(StandardCharsets.UTF_8.name()))
                .put("stderr", stderr.toString(StandardCharsets.UTF_8.name())).put("stdoutBytesShown", stdout.size())
                .put("stderrBytesShown", stderr.size()).put("truncated", truncated)
                .put("encoding", "utf-8-with-replacement");
    }

    private static boolean exactOutputBytes(JSONObject receipt,String key,int expected){
        Object count=receipt.opt(key);
        return (count instanceof Long||count instanceof Integer)&&((Number)count).longValue()==expected;
    }

    private static RuntimeReceipt terminal(ManagedProjectRuntime runtime, Original run, RunControl read,
                                           BooleanSupplier sameWindow) throws Exception {
        // Work authority may read the ledger, outside the Jobs monitor. Only the
        // inexpensive presentation fence can run inside the retained-cache read.
        run.ref.checkControl(read);
        RuntimeReceipt receipt = runtime.ownerPythonTerminal(run.ref, sameWindow);
        run.ref.checkControl(read);
        if (!sameWindow.getAsBoolean()) throw new SecurityException("原脚本工作已关闭");
        return receipt;
    }

    /** Used by the exact native callback and journal reader; never accepts terminal text as evidence. */
    public static JSONObject verifiedNativeExit(JSONObject plan, JSONObject route, JSONObject receipt) throws Exception {
        return exit(original(plan, route), route, receipt);
    }

    private static JSONObject exit(Original original, JSONObject route, JSONObject receipt) throws Exception {
        if (receipt.has("receiptPersistence") || !receipt.optString("status").matches("completed|failed")
                || !"managed_process".equals(receipt.opt("provider")) || !"command".equals(receipt.opt("kind"))
                || !(receipt.opt("timedOut") instanceof Boolean) || !(receipt.opt("cancelRequested") instanceof Boolean)
                || !Boolean.FALSE.equals(receipt.opt("ownedWorkerLive")) || receipt.optLong("finishedAt", 0L) <= 0
                || !Boolean.TRUE.equals(receipt.opt("processGroupAbsent")) || !Boolean.TRUE.equals(receipt.opt("resourcesReleased"))
                || !NativeProcessSupervisor.knownOrdinaryExitContract(receipt.optString("nativeResultContract"))
                || !"pre_exec_manifest_verified".equals(receipt.opt("sourceCheckStatus"))
                || !original.source.tool.generation().equals(receipt.opt("environmentIdentity"))
                || !"python3".equals(receipt.opt("requestedExecutable")) || !"foreground".equals(receipt.opt("runMode"))
                || !"pipes".equals(receipt.opt("terminalMode")) || !Boolean.FALSE.equals(receipt.opt("argumentValuesIncluded"))
                || !ProjectSources.digest(new JSONObject().put("argv", original.source.argv())).equals(receipt.opt("argvDigest")))
            return null;
        JSONObject actual = ProjectExecutionExit.from(original.exact, route, receipt);
        if (actual == null) return null;
        Source source = original.source;
        // The source checkout is real, but the digest is only this run's staged source inputs.
        return new JSONObject().put("format", RESULT).put("taskId", source.id).put("runId", source.id)
                .put("operationId", source.id).put("epoch", 1).put("projectId", source.project).put("checkoutId", source.checkout)
                .put("path", source.path).put("scriptSha256", source.sha256).put("scriptInputDigest", original.ref.sourceDigest())
                .put("exitKind", actual.getString("kind")).put("value", actual.getInt("value"))
                .put("nativeStatus", actual.getString("nativeStatus")).put("resourcesReleased", true)
                .put("timedOut", actual.get("timedOut")).put("cancelRequested", actual.get("cancelRequested"))
                .put("outputFilesReturned", false).put("replayAllowed", false);
    }

    static final class Stage implements AutoCloseable {
        private final Path root, directory;
        private final Object rootKey, directoryKey;
        private final List<String> arguments;
        private final Map<Path, Object> files = new HashMap<>();
        private Stage(Path root, Object rootKey, Path directory, Object directoryKey, List<String> arguments) {
            this.root = root; this.directory = directory; this.rootKey = rootKey;
            this.directoryKey = directoryKey; this.arguments = arguments;
        }
        File directory() { return directory.toFile(); }
        List<String> command() {
            List<String> command = new java.util.ArrayList<>();
            command.add(INTERPRETER);
            command.addAll(arguments);
            return command;
        }
        void requireReady() throws Exception {
            current();
            int contained = 0;
            try (var entries = Files.newDirectoryStream(directory)) {
                for (Path file : entries) {
                    Object expected = files.get(file);
                    if (expected == null || !expected.equals(regular(file).fileKey()))
                        throw new SecurityException("Python 暂存不是这次已核对的源码");
                    contained++;
                }
            }
            if (contained != files.size() || contained < 1) throw new SecurityException("原 Python 暂存缺少已核对的源码");
            current();
        }
        private void current() throws Exception {
            if (!rootKey.equals(directoryKey(root)) || !directoryKey.equals(directoryKey(directory)))
                throw new SecurityException("脚本暂存目录已变化");
        }
        @Override public void close() {
            // The provider calls this only after known release (or known no entry). Do not
            // recurse into a changed directory or remove files created by an unknown program.
            try {
                current();
                for (var file : files.entrySet()) {
                    current();
                    Path path = file.getKey();
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && file.getValue().equals(regular(path).fileKey()))
                        Files.delete(path);
                }
                current();
                Files.delete(directory); // only when still this same empty directory
            } catch (Exception unverified) { /* Preserve an unexpected or changed slot; never borrow it. */ }
        }
    }

    static synchronized Stage materialize(File privateRuntime, Source source, Map<String, byte[]> bytes) throws Exception {
        requireUuid(source.id);
        List<InputFile> inputs = source.stagedSources();
        if (bytes.size() != inputs.size()) throw new SecurityException("脚本暂存输入范围不匹配");
        for (InputFile file : inputs) {
            byte[] body = bytes.get(source.stageName(file));
            if (body == null || body.length != file.size || !file.sha256.equals(ProjectSources.sha256(body)))
                throw new SecurityException("脚本暂存输入不匹配");
        }
        Path root = RuntimeFiles.directory(privateRuntime, "owner-python-scripts").toPath();
        Object rootKey = directoryKey(root);
        try (var entries = Files.newDirectoryStream(root)) {
            int occupied = 0;
            for (Path ignored : entries) if (++occupied >= MAX_STAGED)
                throw new IOException("本机脚本暂存还有未核对的目录，没有清理或覆盖");
        }
        Path directory = root.resolve(source.id);
        if (!rootKey.equals(directoryKey(root))) throw new SecurityException("脚本暂存根已变化");
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Stage stage = new Stage(root, rootKey, directory, directoryKey(directory), source.commandArguments());
        boolean retained = false;
        try {
            for (InputFile input : inputs) {
                stage.current();
                String name = source.stageName(input);
                Path path = directory.resolve(name);
                byte[] expected = bytes.get(name);
                try (FileChannel out = FileChannel.open(path,
                        Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------")))) {
                    stage.files.put(path, regular(path).fileKey());
                    ByteBuffer data = ByteBuffer.wrap(expected);
                    while (data.hasRemaining()) if (out.write(data) <= 0) throw new IOException("脚本暂存写入未完成");
                    out.force(true);
                }
                BasicFileAttributes selected = regular(path);
                byte[] actual = ProjectFileBytes.read(path, selected, MAX_SOURCE);
                try {
                    stage.current();
                    if (!stage.files.get(path).equals(regular(path).fileKey()) || !Arrays.equals(expected, actual))
                        throw new SecurityException("脚本暂存回读与原保存字节不一致");
                } finally { Arrays.fill(actual, (byte) 0); }
            }
            stage.requireReady();
            retained = true;
            return stage;
        } finally {
            if (!retained) stage.close();
        }
    }

    private static Object directoryKey(Path path) throws Exception {
        var info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!path.equals(path.toRealPath()) || !info.isDirectory() || info.isSymbolicLink() || info.fileKey() == null)
            throw new SecurityException("脚本暂存不是可核对的私人目录");
        return info.fileKey();
    }
    private static BasicFileAttributes regular(Path path) throws Exception {
        var info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!info.isRegularFile() || info.isSymbolicLink() || info.fileKey() == null
                || !(links instanceof Number count) || count.longValue() != 1)
            throw new SecurityException("脚本暂存不是独立的普通文件");
        return info;
    }

    private static int integer(JSONObject value, String key, int min, int max) {
        Object raw = value.opt(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw new SecurityException("原脚本记录的数字不可核对");
        long n = ((Number) raw).longValue();
        if (n < min || n > max) throw new SecurityException("原脚本记录的数字越界");
        return (int) n;
    }
    private static void requireUuid(String text) {
        if (!UUID.fromString(text).toString().equals(text)) throw new SecurityException("原脚本或副本身份无效");
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new SecurityException("没有准确字节摘要");
    }
}
