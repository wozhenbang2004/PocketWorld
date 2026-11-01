package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes one host-requested protocol step. There are no threads, timers, or automatic resubmissions. */
public final class MediaJobs {
    public interface ArtifactSink { JSONObject save(MediaTransport.Payload payload, JSONObject source, RunControl control) throws Exception; }
    public interface ReceiptSink { void emit(JSONObject receipt); }
    private final MediaJobJournal journal;
    private final ArtifactSink artifacts;
    private final ReceiptSink events;
    private final java.util.Set<String> observing = new java.util.HashSet<>();

    public MediaJobs(MediaJobJournal journal, ArtifactSink artifacts, ReceiptSink events) {
        this.journal = java.util.Objects.requireNonNull(journal);
        this.artifacts = java.util.Objects.requireNonNull(artifacts);
        this.events = java.util.Objects.requireNonNull(events);
    }

    public JSONArray list() throws Exception { return journal.list(); }
    public JSONObject get(String operation) throws Exception {
        JSONObject record = journal.get(operation);
        if (record == null) throw new IllegalArgumentException("媒体操作回执不存在");
        return MediaJobJournal.visible(record);
    }
    public static String configurationRevision(JSONObject config) throws Exception {
        return config.optString("revision").isEmpty() ? MediaJobJournal.hash(config) : config.getString("revision");
    }

    /** Native read control only. This neither writes a job receipt nor creates/cancels a remote task. */
    public JSONObject observeProgress(JSONObject input, JSONObject config, String key, RunControl control,
            ComfyUiProgressObserver.Listener listener) throws Exception {
        final String operation;
        final ComfyUiProgressObserver observer;
        synchronized (this) {
            control.check();
            JSONObject record = require(input);
            for (String field : new String[]{"operationId", "_taskId", "_runId", "_configurationRevision", "providerJobId"}) {
                if (!input.has(field) || input.getString(field).isEmpty()) throw new SecurityException("进度观察必须绑定原任务与配置");
            }
            if (input.has("clientId") || input.has("_clientId")) throw new SecurityException("进度观察不能替换原客户端身份");
            if (!control.hasHostRun() || !control.matchesHostRun(input.getString("_taskId"), input.getString("_runId"), input.optInt("_epoch"))) {
                throw new SecurityException("进度观察控制与原任务代次不一致");
            }
            requireConfiguration(config, record);
            if (!record.getString("configurationRevision").equals(input.getString("_configurationRevision"))) throw new SecurityException("进度观察配置已变化");
            if (!record.getString("provider").equals("comfyui") || !record.optBoolean("providerJobIdConfirmed")) throw new IllegalStateException("请先核对原ComfyUI任务身份，再观察进度");
            if (record.optString("status").matches("response_received|failed|cancelled")) {
                return new JSONObject().put("kind", "media_progress_observation").put("operationId", record.getString("operationId"))
                        .put("observationState", "unavailable").put("endReason", "provider_already_terminal").put("historyReconciliationRequired", true).put("verified", false);
            }
            operation = record.getString("operationId");
            if (observing.contains(operation) || observing.size() >= 2) throw new IllegalStateException("原媒体任务已有进度观察，或观察连接已达上限");
            observer = new ComfyUiProgressObserver(record, config, key, control, listener, input.optLong("maxDurationMs", 30000));
            observing.add(operation);
        }
        try { return observer.observe(); }
        finally { observer.close(); synchronized (this) { observing.remove(operation); } }
    }

    public synchronized JSONObject execute(String command, JSONObject input, JSONObject config, String key, RunControl control) throws Exception {
        control.check();
        if (command.equals("listJobs")) return new JSONObject().put("jobs", list());
        if (command.equals("getOperation")) return MediaJobJournal.visible(require(input));
        if (command.matches("generateImage|synthesizeSpeech|submitWorkflow")) return submit(command, input, config, key, control);
        JSONObject record = require(input);
        if (!command.matches("getJob|reconcileOperation|fetchResults|cancelJob|cancel")) throw new IllegalArgumentException("不支持的媒体操作");
        if (!record.getString("provider").equals("comfyui")) {
            return MediaJobJournal.visible(record).put("querySupported", false).put("remoteCancellationSupported", false)
                    .put("message", record.optJSONArray("attachments") != null && record.getJSONArray("attachments").length() > 0
                            ? "媒体回执已保留为附件" : "此同步媒体API不提供原请求查询或远程取消；未知效果未重试");
        }
        requireConfiguration(config, record);
        if (command.equals("cancelJob") || command.equals("cancel")) return cancel(record, config, key, control);
        if (!terminal(record)) record = query(record, config, key, control);
        if (command.equals("fetchResults")) return fetch(record, config, key, control);
        return MediaJobJournal.visible(record);
    }

    private JSONObject submit(String command, JSONObject input, JSONObject config, String key, RunControl control) throws Exception {
        String operation = input.optString("_operationId");
        MediaJobJournal.identity(operation);
        String task = input.optString("_taskId"), run = input.optString("_runId", operation);
        if (!task.isEmpty()) MediaJobJournal.identity(task);
        MediaJobJournal.identity(run);
        if (config.optString("baseUrl").isEmpty()) throw new IllegalStateException("请先配置真实媒体服务");
        MediaTransport.base(config.getString("baseUrl"));
        String revision = configurationRevision(config);
        if (input.has("_configurationRevision") && !revision.equals(input.getString("_configurationRevision"))) throw new SecurityException("媒体配置已变化，请重新确认");
        String provider = config.optString("provider", "openai_compatible");
        if (!provider.matches("openai_compatible|comfyui")) throw new IllegalArgumentException("媒体服务类型无效");
        String kind = command.equals("synthesizeSpeech") ? "speech" : command.equals("submitWorkflow") ? "workflow" : "image";
        JSONObject request;
        if (provider.equals("comfyui")) {
            if (kind.equals("speech")) throw new IllegalArgumentException("ComfyUI语音需使用显式工作流，或配置兼容TTS服务");
            request = kind.equals("workflow") ? ComfyUiTransport.workflow(input.getJSONObject("workflow")) : imageWorkflow(config, input);
        } else {
            if (kind.equals("workflow")) throw new IllegalArgumentException("此媒体服务不是ComfyUI工作流服务");
            request = MediaTransport.compatibleBody(kind, config.optString(kind.equals("image") ? "imageModel" : "ttsModel"),
                    config.optString("voice"), input.getString(kind.equals("image") ? "prompt" : "text"), config.optBoolean("legacyImageBase64"));
        }
        String configurationHash = MediaJobJournal.hash(config);
        String hash = MediaJobJournal.hash(new JSONObject().put("command", command).put("configurationHash", configurationHash).put("request", request));
        JSONObject existing = journal.get(operation);
        if (existing != null) {
            if (!hash.equals(existing.optString("_requestHash")) || !task.equals(existing.optString("taskId")) || !run.equals(existing.optString("runId"))) {
                throw new SecurityException("原媒体操作的输入、配置或任务身份已变化；不能复用操作标识提交");
            }
            return MediaJobJournal.visible(existing).put("knownOperation", true);
        }
        JSONObject record = new JSONObject().put("id", operation).put("operationId", operation).put("taskId", task).put("runId", run)
                .put("provider", provider).put("kind", kind).put("configurationRevision", revision).put("_requestHash", hash)
                .put("_configurationHash", configurationHash)
                .put("_clientId", UUID.randomUUID().toString()).put("providerJobId", provider.equals("comfyui") ? UUID.randomUUID().toString() : "")
                .put("providerJobIdConfirmed", false).put("status", "prepared").put("dispatched", false).put("verified", false)
                .put("createdAt", System.currentTimeMillis()).put("expirySupported", false).put("expiresAt", JSONObject.NULL)
                .put("outputs", new JSONArray()).put("attachments", new JSONArray())
                .put("remoteCancellation", provider.equals("comfyui") ? config.optBoolean("comfyJobsApi") ? "jobs_api" : "queue_only" : "unavailable");
        journal.put(record); // Must succeed before any network side effect.
        control.check();
        record.put("status", "submitting").put("dispatched", true);
        journal.put(record); // A crash after this point is unknown; the operation is never submitted again.
        try {
            if (provider.equals("comfyui")) {
                String id = ComfyUiTransport.submit(config.getString("baseUrl"), key, request, record, control);
                record.put("providerJobId", id).put("providerJobIdConfirmed", true).put("status", "queued").put("providerState", "accepted")
                        .put("message", "ComfyUI已受理原任务；请查询进度，不要重新生成");
            } else {
                MediaTransport.Payload payload = MediaTransport.request(config.getString("baseUrl"), key, kind,
                        config.getString(kind.equals("image") ? "imageModel" : "ttsModel"), config.optString("voice"),
                        input.getString(kind.equals("image") ? "prompt" : "text"), config.optBoolean("legacyImageBase64"), control);
                record.put("providerState", "response_received");
                control.check();
                JSONObject attachment = artifacts.save(payload, source(record, config, "response"), control);
                record.put("attachments", new JSONArray().put(attachment)).put("attachment", attachment).put("resultStatus", "saved")
                        .put("status", "response_received").put("message", "媒体服务返回已保存为附件，请检查生成内容");
            }
        } catch (Exception failure) {
            boolean rejected = failure instanceof MediaTransport.Rejected && ((MediaTransport.Rejected) failure).definitive;
            record.put("status", rejected ? "failed" : "unknown").put("message", rejected ? failure.getMessage()
                    : "媒体提交或产物保存回执不完整；服务可能已受理或计费，未自动重试");
            if (failure instanceof MediaTransport.Rejected) record.put("httpStatus", ((MediaTransport.Rejected) failure).status);
            if (control.isCancelled()) record.put("localWaitCancelled", true);
        }
        return save(record);
    }

    private static JSONObject imageWorkflow(JSONObject config, JSONObject input) throws Exception {
        String prompt = input.getString("prompt");
        if (prompt.isBlank() || prompt.length() > 8000) throw new IllegalArgumentException("媒体输入为空或超过长度限制");
        JSONObject graph = ComfyUiTransport.workflow(config.optJSONObject("workflow"));
        String node = config.getString("promptNodeId"), field = config.optString("promptInput", "text");
        JSONObject inputs = graph.getJSONObject(node).getJSONObject("inputs");
        if (!(inputs.opt(field) instanceof String)) throw new IllegalArgumentException("ComfyUI提示词目标必须是工作流中已有的文字输入");
        inputs.put(field, prompt);
        return ComfyUiTransport.workflow(graph);
    }

    private JSONObject require(JSONObject input) throws Exception {
        String operation = input.optString("operationId", input.optString("_operationId"));
        if (input.has("operationId") && input.has("_operationId") && !input.getString("operationId").equals(input.getString("_operationId"))) throw new SecurityException("媒体操作标识不一致");
        JSONObject record = journal.get(operation);
        if (record == null) throw new IllegalArgumentException("媒体操作回执不存在");
        for (String key : new String[]{"taskId", "runId", "providerJobId"}) {
            if (input.has(key) && !record.optString(key).equals(input.getString(key))) throw new SecurityException("媒体原任务身份已变化");
            if (input.has("_" + key) && !record.optString(key).equals(input.getString("_" + key))) throw new SecurityException("媒体原任务身份已变化");
        }
        return record;
    }

    private static void requireConfiguration(JSONObject config, JSONObject record) throws Exception {
        if (!configurationRevision(config).equals(record.getString("configurationRevision"))
                || !MediaJobJournal.hash(config).equals(record.getString("_configurationHash"))) throw new SecurityException("媒体服务配置已变化，不能把原任务或凭据送往新配置");
    }

    private JSONObject query(JSONObject record, JSONObject config, String key, RunControl control) throws Exception {
        try {
            ComfyUiTransport.Observation observed = ComfyUiTransport.query(config.getString("baseUrl"), key, record, control);
            if (!observed.state.equals("unknown")) {
                record.put("providerJobId", observed.id).put("providerJobIdConfirmed", true).put("providerState", observed.state);
                if (observed.outputs.length() > 0) {
                    String outputHash = MediaJobJournal.hash(observed.outputs);
                    if (record.has("_outputsHash") && !outputHash.equals(record.getString("_outputsHash"))) throw new SecurityException("ComfyUI原任务产物描述已变化");
                    record.put("outputs", observed.outputs).put("_outputsHash", outputHash);
                }
            }
            record.put("status", observed.state.equals("completed") ? "response_received" : observed.state)
                    .put("queryStatus", "received").put("message", observed.state.equals("unknown")
                            ? "原任务不在可见队列或历史中，可能尚未受理、已完成或已清理；未重新提交"
                            : observed.state.equals("completed") ? "ComfyUI已返回完成记录，请获取并核对产物" : "已查询原ComfyUI任务");
        } catch (Exception failure) {
            record.put("status", "unknown").put("queryStatus", "unconfirmed").put("message", "原媒体任务查询未取得可用回执；未重新提交或计费");
            if (failure instanceof MediaTransport.Rejected) record.put("queryHttpStatus", ((MediaTransport.Rejected) failure).status);
        }
        save(record);
        return record;
    }

    private JSONObject fetch(JSONObject record, JSONObject config, String key, RunControl control) throws Exception {
        JSONArray outputs = record.getJSONArray("outputs"), saved = record.getJSONArray("attachments");
        if (!terminal(record) || outputs.length() == 0) return MediaJobJournal.visible(record).put("resultStatus", outputs.length() == 0 ? "no_saved_outputs" : "not_ready");
        long total = 0;
        for (int i = 0; i < saved.length(); i++) total += saved.getJSONObject(i).optLong("size");
        try {
            for (int i = saved.length(); i < outputs.length(); i++) {
                control.check();
                JSONObject output = outputs.getJSONObject(i);
                byte[] bytes = MediaTransport.exchange(ComfyUiTransport.outputUri(config.getString("baseUrl"), output), key, "GET", null,
                        MediaTransport.MAX_MEDIA_BYTES, control);
                total += bytes.length;
                if (total > 40L * 1024 * 1024) throw new IllegalStateException("本任务产物超过40MiB导入上限");
                MediaTransport.Payload payload = MediaTransport.outputPayload(bytes);
                control.check();
                JSONObject attachment = artifacts.save(payload, source(record, config, MediaJobJournal.hash(output)), control);
                saved.put(attachment);
                record.put("attachments", saved).put("resultStatus", saved.length() == outputs.length() ? "saved" : "partial");
                if (saved.length() == 1) record.put("attachment", attachment);
                save(record); // Preserve each import before proceeding; recovery finds the same artifact identity.
            }
            record.put("resultStatus", "saved").put("message", "原媒体任务产物已保存为附件，请检查内容");
        } catch (Exception failure) {
            record.put("resultStatus", saved.length() == 0 ? "unavailable" : "partial")
                    .put("message", "生成回执已保留，产物下载或导入未全部完成；可核对原产物，不会重新生成");
            if (failure instanceof MediaTransport.Rejected) record.put("resultHttpStatus", ((MediaTransport.Rejected) failure).status);
        }
        return save(record);
    }

    private JSONObject cancel(JSONObject record, JSONObject config, String key, RunControl control) throws Exception {
        if (terminal(record)) return MediaJobJournal.visible(record).put("cancelStatus", "already_terminal");
        record = query(record, config, key, control);
        if (terminal(record)) return MediaJobJournal.visible(record).put("cancelStatus", "already_terminal");
        if (record.optBoolean("_cancelDispatched")) return MediaJobJournal.visible(record).put("cancelStatus", "query_only_after_request");
        if (!record.optBoolean("providerJobIdConfirmed") || record.getString("status").equals("unknown")) return MediaJobJournal.visible(record).put("cancelStatus", "original_job_unconfirmed");
        boolean jobsApi = record.getString("remoteCancellation").equals("jobs_api");
        if (!jobsApi && !record.getString("status").equals("queued")) return MediaJobJournal.visible(record).put("cancelStatus", "unsupported_running")
                .put("message", "此服务未配置原子逐任务取消，未调用全局interrupt；原任务可能继续运行");
        control.check();
        record.put("_cancelDispatched", true).put("cancelStatus", "request_unknown");
        journal.put(record);
        try {
            boolean accepted = ComfyUiTransport.cancel(config.getString("baseUrl"), key, record.getString("providerJobId"), jobsApi, control);
            record.put("cancelStatus", accepted ? "requested" : "no_op").put("status", accepted ? "stopping" : record.getString("status"))
                    .put("message", "已取得原任务取消请求回执；仍需查询终止或产物状态");
        } catch (Exception failure) {
            record.put("status", "unknown").put("cancelStatus", "unconfirmed").put("message", "原任务取消回执未知，只能查询对账，未调用全局interrupt");
            if (failure instanceof MediaTransport.Rejected) record.put("cancelHttpStatus", ((MediaTransport.Rejected) failure).status);
        }
        return save(record);
    }

    private static boolean terminal(JSONObject record) { return record.optString("status").matches("response_received|failed|cancelled"); }
    private static JSONObject source(JSONObject record, JSONObject config, String artifactKey) throws Exception {
        JSONObject result = new JSONObject().put("kind", "generated_media").put("operationId", record.getString("operationId"))
                .put("taskId", record.getString("taskId")).put("runId", record.getString("runId")).put("provider", record.getString("provider"))
                .put("providerJobId", record.getString("providerJobId")).put("artifactKey", artifactKey)
                .put("serviceOrigin", MediaTransport.base(config.getString("baseUrl")).getHost());
        String model = config.optString(record.getString("kind").equals("speech") ? "ttsModel" : "imageModel");
        if (!model.isEmpty()) result.put("model", model);
        return result;
    }
    private JSONObject save(JSONObject record) throws Exception {
        record.put("updatedAt", System.currentTimeMillis());
        journal.put(record);
        JSONObject visible = MediaJobJournal.visible(record);
        try { events.emit(new JSONObject(visible.toString())); } catch (RuntimeException ignored) { /* Receipt persistence is authoritative for this adapter. */ }
        return visible;
    }
}
