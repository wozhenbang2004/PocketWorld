package dev.pocketengine.media;

import android.util.AtomicFile;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.media.jobs.ComfyUiTransport;
import dev.pocketengine.media.jobs.MediaJobJournal;
import dev.pocketengine.media.jobs.MediaJobs;
import dev.pocketengine.media.jobs.ComfyUiProgressObserver;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-requested media protocol steps and receipts; TaskStore owns execution and scheduling. */
public final class MediaModule implements EngineModule {
    private final FeatureContext context;
    private final AtomicFile configuration;
    private MediaJobs jobs;
    private RunControl active;
    private String activeOperation;
    private boolean closed;
    private final java.util.Set<RunControl> progressControls = new java.util.HashSet<>();

    public MediaModule(FeatureContext context) {
        this.context = context;
        configuration = new AtomicFile(new File(context.android.getFilesDir(), "media-service.json"));
    }
    @Override public String id() { return "media"; }

    private synchronized MediaJobs jobs() throws Exception {
        if (jobs == null) jobs = new MediaJobs(new MediaJobJournal(new File(context.android.getFilesDir(), "media-receipts-v1.json")),
                this::importArtifact, receipt -> context.emit("media", receipt));
        return jobs;
    }

    @Override public synchronized JSONObject snapshot() throws Exception {
        JSONObject config = read(), publicConfig = new JSONObject(config.toString());
        String slot = publicConfig.optString("secretSlot");
        publicConfig.remove("secretSlot");
        JSONObject workflow = publicConfig.optJSONObject("workflow");
        publicConfig.remove("workflow");
        publicConfig.put("hasApiKey", !slot.isEmpty() && context.secrets.contains(slot)).put("hasWorkflow", workflow != null);
        if (workflow != null) publicConfig.put("workflowHash", MediaJobJournal.hash(workflow));
        boolean comfy = config.optString("provider").equals("comfyui");
        return new JSONObject().put("configuration", publicConfig).put("configurationRevision", MediaJobs.configurationRevision(config))
                .put("configured", !config.optString("baseUrl").isEmpty()).put("busy", active != null)
                .put("activeOperationId", activeOperation == null ? "" : activeOperation).put("jobs", jobs().list())
                .put("serviceVerification", "not_verified_for_current_configuration").put("asyncProviderJobs", comfy)
                .put("remoteCancellation", comfy ? config.optBoolean("comfyJobsApi") ? "jobs_api" : "queue_only" : "unavailable")
                .put("commands", new JSONArray("[\"configure\",\"generateImage\",\"synthesizeSpeech\",\"submitWorkflow\",\"getJob\",\"getOperation\",\"listJobs\",\"fetchResults\",\"reconcileOperation\",\"cancelJob\",\"cancel\"]"))
                .put("inputLimits",new JSONObject().put("imagePromptChars",MediaTransport.MAX_IMAGE_INPUT_CHARS).put("speechTextChars",MediaTransport.MAX_SPEECH_INPUT_CHARS))
                .put("outputMimeTypes",new JSONArray(new String[]{"image/png","image/jpeg","image/webp","audio/wav"})).put("maxOutputBytes",MediaTransport.MAX_MEDIA_BYTES)
                .put("videoSupported", false).put("imageEditingSupported",false).put("backgroundPolling", false)
                .put("nativeProgressObservationSupported", comfy).put("activeProgressObservers", progressControls.size());
    }

    /** Compatibility owner entry; only the host can establish persistent operation and run authority. */
    @Override public JSONObject handle(String command, JSONObject input) throws Exception {
        return handleInternal(command, input, new RunControl(), false);
    }

    /** Native host entry retains revocable authority across every HTTP dispatch and artifact import. */
    public JSONObject handleForHost(String command, JSONObject input, RunControl control) throws Exception {
        if (control == null) throw new SecurityException("媒体执行需要原任务实时控制");
        control.check();
        if (control.hasHostRun() && !control.matchesHostRun(input.optString("_taskId"), input.optString("_runId"), input.optInt("_epoch"))) {
            throw new SecurityException("媒体执行控制与原任务代次不一致");
        }
        return handleInternal(command, input, control, true);
    }

    /** Existing host worker + dedicated observer control; no generic feature/model command exposes this. */
    public JSONObject observeProgress(JSONObject originalBinding, RunControl control, ComfyUiProgressObserver.Listener listener) throws Exception {
        if (control == null || !control.hasHostRun()) throw new SecurityException("进度观察需要原任务实时控制");
        JSONObject input = new JSONObject(originalBinding.toString());
        if (!control.matchesHostRun(input.optString("_taskId"), input.optString("_runId"), input.optInt("_epoch"))) throw new SecurityException("进度观察代次已变化");
        control.check();
        final JSONObject config;
        final RunControl observerControl = new RunControl();
        observerControl.bindHostRun(input.getString("_taskId"), input.getString("_runId"), input.getInt("_epoch"));
        observerControl.setAuthorityCheck(control::check);
        synchronized (this) {
            if (closed || progressControls.size() >= 2) throw new IllegalStateException("媒体模块已关闭或观察连接已达上限");
            config = read();
            if (!MediaJobs.configurationRevision(config).equals(input.optString("_configurationRevision"))) throw new SecurityException("进度观察配置已变化");
            progressControls.add(observerControl);
        }
        Runnable cancel = observerControl::cancel;
        control.onCancel(cancel);
        try {
            observerControl.check();
            String slot = config.optString("secretSlot");
            String key = slot.isEmpty() ? "" : context.secrets.get(slot);
            return jobs().observeProgress(input, config, key, observerControl, listener);
        } finally {
            control.removeCancelAction(cancel); observerControl.cancel();
            synchronized (this) { progressControls.remove(observerControl); }
        }
    }

    private JSONObject handleInternal(String command, JSONObject input, RunControl control, boolean nativeControl) throws Exception {
        if (command.equals("configure")) return configure(input);
        if (command.equals("listJobs") || command.equals("getOperation")) {
            synchronized (this) { if (closed) throw new IllegalStateException("媒体模块已关闭"); }
            return jobs().execute(command, input, new JSONObject(), "", control);
        }
        String operation = command.matches("generateImage|synthesizeSpeech|submitWorkflow")
                ? input.optString("_operationId") : input.optString("operationId", input.optString("_operationId"));
        synchronized (this) {
            if (closed) throw new IllegalStateException("媒体模块已关闭");
            if (command.equals("cancel") || command.equals("cancelJob")) {
                if (active != null) {
                    if (!activeOperation.equals(operation)) throw new SecurityException("媒体操作身份已变化，请检查原任务");
                    active.cancel();
                    return new JSONObject().put("id", operation).put("operationId", operation).put("status", "stopping")
                            .put("verified", false).put("message", "已停止本地等待；远端是否受理、完成或计费仍需核对原任务");
                }
                if (command.equals("cancel")) {
                    try { jobs().get(operation); } catch (IllegalArgumentException | SecurityException absent) { return new JSONObject().put("status", "idle"); }
                }
            }
        }
        MediaJobJournal.identity(operation);
        if (command.equals("submitWorkflow") && (input.optString("_taskId").isEmpty() || input.optString("_runId").isEmpty())) {
            throw new SecurityException("ComfyUI工作流必须先由宿主登记原任务及操作");
        }
        final JSONObject config;
        synchronized (this) {
            if (active != null) throw new IllegalStateException("已有媒体协议请求执行中，请等待返回或停止原操作");
            config = read();
            if (config.optString("baseUrl").isEmpty()) throw new IllegalStateException("请先配置真实媒体服务地址、模型和凭据");
            if (input.has("_configurationRevision") && !MediaJobs.configurationRevision(config).equals(input.getString("_configurationRevision"))) throw new SecurityException("媒体配置已变化，请重新确认");
            active = control;
            activeOperation = operation;
        }
        try {
            control.check();
            String slot = config.optString("secretSlot");
            String key = slot.isEmpty() ? "" : context.secrets.get(slot);
            JSONObject result = jobs().execute(command, input, config, key, control);
            return result.put("liveHostControl", nativeControl).put("hostRunBound", nativeControl && control.hasHostRun());
        } finally {
            synchronized (this) { if (active == control) { active = null; activeOperation = null; } }
        }
    }

    private synchronized JSONObject configure(JSONObject input) throws Exception {
        if (closed) throw new IllegalStateException("媒体模块已关闭");
        if (active != null || !progressControls.isEmpty()) throw new IllegalStateException("媒体请求或进度观察期间不能更改服务配置，请先停止原观察");
        String base = input.getString("baseUrl").trim();
        MediaTransport.base(base);
        String provider = input.optString("provider", "openai_compatible");
        if (!provider.matches("openai_compatible|comfyui")) throw new IllegalArgumentException("媒体服务类型无效");
        String image = bounded(input.optString("imageModel"), 120), tts = bounded(input.optString("ttsModel"), 120), voice = bounded(input.optString("voice"), 120);
        JSONObject next = new JSONObject().put("provider", provider).put("baseUrl", base).put("imageModel", image).put("ttsModel", tts)
                .put("voice", voice).put("legacyImageBase64", input.optBoolean("legacyImageBase64"))
                .put("comfyJobsApi", provider.equals("comfyui") && input.optBoolean("comfyJobsApi")).put("revision", UUID.randomUUID().toString());
        if (provider.equals("openai_compatible")) {
            if (image.isEmpty() && tts.isEmpty()) throw new IllegalArgumentException("至少配置一个真实媒体模型标识");
            if (!tts.isEmpty() && voice.isEmpty()) throw new IllegalArgumentException("语音模型需要voice标识");
        } else if (input.has("workflow")) {
            JSONObject graph = ComfyUiTransport.workflow(input.getJSONObject("workflow"));
            String node = bounded(input.getString("promptNodeId"), 80), field = bounded(input.optString("promptInput", "text"), 80);
            if (!(graph.getJSONObject(node).getJSONObject("inputs").opt(field) instanceof String)) throw new IllegalArgumentException("ComfyUI提示词目标必须是工作流中已有的文字输入");
            next.put("workflow", graph).put("promptNodeId", node).put("promptInput", field);
        }
        JSONObject old = read();
        String previous = old.optString("secretSlot"), slot = "", newSlot = null, key = input.optString("apiKey");
        if (key.length() > 8192 || key.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("媒体服务密钥格式无效");
        if (!key.isEmpty()) { newSlot = "media." + UUID.randomUUID(); context.secrets.put(newSlot, key); slot = newSlot; }
        else if (!input.optBoolean("clearKey") && old.optString("provider", "openai_compatible").equals(provider)
                && old.optString("baseUrl").equals(base)) slot = previous;
        next.put("secretSlot", slot);
        FileOutputStream output = null;
        try { output = configuration.startWrite(); output.write(next.toString().getBytes(StandardCharsets.UTF_8)); configuration.finishWrite(output); }
        catch (Exception failure) { if (output != null) configuration.failWrite(output); if (newSlot != null) context.secrets.remove(newSlot); throw failure; }
        if (!previous.isEmpty() && !previous.equals(slot)) context.secrets.remove(previous);
        return snapshot();
    }

    /** Native owner draft bytes use the existing attachment store, never provider receipts or task logs. */
    public JSONObject retainOwnerSubmissionInput(String taskId,String command,JSONObject arguments,String requestHash)throws Exception{
        JSONObject document=new JSONObject().put("submissionCommand",command).put("arguments",arguments);
        byte[] bytes=document.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1_100_000)throw new IllegalArgumentException("媒体准备输入超过保存上限");
        JSONObject source=new JSONObject().put("kind","media_owner_input").put("taskId",taskId).put("requestHash",requestHash);
        return AttachmentRepository.get(context.android).book.importBytes(new ByteArrayInputStream(bytes),"media-input-"+taskId+".json","application/json",source);
    }
    public JSONObject readOwnerSubmissionInput(String taskId,String requestHash,JSONObject reference)throws Exception{
        AttachmentBook book=AttachmentRepository.get(context.android).book;JSONObject meta=book.get(reference.getString("id")),source=meta.optJSONObject("source");
        if(!reference.getString("sha256").equals(meta.optString("sha256"))||source==null||!"media_owner_input".equals(source.optString("kind"))||!taskId.equals(source.optString("taskId"))||!requestHash.equals(source.optString("requestHash")))throw new SecurityException("原媒体输入附件身份已变化");
        File file=book.file(meta.getString("id"));if(file.length()>1_100_000)throw new IllegalArgumentException("原媒体输入附件过大");
        byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());if(!meta.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new SecurityException("原媒体输入字节已变化，未恢复或提交");
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }

    private JSONObject importArtifact(MediaTransport.Payload payload, JSONObject source, RunControl control) throws Exception {
        control.check();
        if (payload.mimeType.startsWith("image/")) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 33_554_432L) throw new IllegalStateException("媒体图像不能解码或尺寸超过上限");
        }
        AttachmentBook book = AttachmentRepository.get(context.android).book;
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(payload.bytes));
        JSONArray existing = book.list();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject attachment = existing.getJSONObject(i), origin = attachment.optJSONObject("source");
            if (origin == null || !source.getString("operationId").equals(origin.optString("operationId"))
                    || !source.getString("artifactKey").equals(origin.optString("artifactKey"))) continue;
            if (!digest.equals(attachment.optString("sha256"))) throw new SecurityException("原媒体产物字节已变化，请先核对现有附件");
            return attachment;
        }
        try (FilterInputStream input = new FilterInputStream(new ByteArrayInputStream(payload.bytes)) {
            private void check() throws IOException { try { control.check(); } catch (InterruptedException stopped) { throw new IOException("媒体附件导入已停止", stopped); } }
            @Override public int read() throws IOException { check(); return super.read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException { check(); return super.read(bytes, offset, length); }
        }) {
            JSONObject attachment = book.importBytes(input, "generated-" + source.getString("operationId") + "." + payload.extension, payload.mimeType, source);
            context.emit("attachments", new JSONObject().put("status", "imported").put("attachments", new JSONArray().put(attachment)));
            return attachment;
        }
    }
    private JSONObject read() throws Exception {
        try { return new JSONObject(new String(configuration.readFully(), StandardCharsets.UTF_8)); }
        catch (java.io.FileNotFoundException absent) { return new JSONObject(); }
    }
    private static String bounded(String value, int limit) {
        value = value.trim();
        if (value.length() > limit || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("媒体模型或voice标识无效");
        return value;
    }
    private static String hex(byte[] digest) { StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
    @Override public void close() {
        final RunControl request;
        final java.util.List<RunControl> observers;
        synchronized (this) { closed = true; request = active; observers = new java.util.ArrayList<>(progressControls); }
        if (request != null) request.cancel();
        for (RunControl observer : observers) observer.cancel();
    }
}
