package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes one host-requested protocol step. There are no threads, timers, or automatic resubmissions. */
public final class MediaJobs {
    private static final long MAX_IMPORT_BYTES = 40L * 1024 * 1024;
    public interface ArtifactSink {
        JSONObject save(MediaTransport.Payload payload,JSONObject source,RunControl control)throws Exception;
        default JSONObject find(JSONObject source,RunControl control)throws Exception{return null;}
    }
    public interface ReceiptSink { void emit(JSONObject receipt); }
    private final MediaJobJournal journal;
    private final ArtifactSink artifacts;
    private final ReceiptSink events;
    private final java.util.Set<String> observing = new java.util.HashSet<>();
    private final java.util.Map<String,String> ownerDownloadReviews = new java.util.HashMap<>();

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
    /** Native host observation marker in this ORIGINAL provider receipt; never a new grant,
     * scheduling authority or caller JSON command. Once written, cold/duplicate receipts cannot
     * reset the application's bounded automatic read window. Explicit original GET stays usable. */
    public synchronized boolean markOriginalReadStarted(String operation,String task,String run,String revision,long until)throws Exception{
        JSONObject record=journal.get(operation);if(record==null||!task.equals(record.optString("taskId"))||!run.equals(record.optString("runId"))||!revision.equals(record.optString("configurationRevision"))||!record.optString("provider").equals("comfyui")||!record.optBoolean("providerJobIdConfirmed")||!record.optString("status").matches("queued|running|unknown"))throw new SecurityException("续查不是原确认的媒体job");
        if(record.has("_readContinuationStartedAt"))return false;long now=System.currentTimeMillis();if(until<=now||until>now+300000)throw new SecurityException("原只读续查时限无效");record.put("_readContinuationStartedAt",now).put("_readContinuationUntil",until).put("originalReadContinuation","application_alive");journal.put(record);return true;
    }
    public synchronized void finishOriginalReadContinuation(String operation,String reason,int reads)throws Exception{
        JSONObject record=journal.get(operation);if(record==null||!record.has("_readContinuationStartedAt"))return;record.put("originalReadContinuation","ended").put("readContinuationEnd",reason).put("readContinuationReads",Math.max(0,Math.min(60,reads)));save(record);
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

    public synchronized JSONObject execute(String command,JSONObject input,JSONObject config,String key,RunControl control)throws Exception{return execute(command,input,config,key,control,null);}
    public synchronized JSONObject execute(String command,JSONObject input,JSONObject config,String key,RunControl control,MediaTransport.EditInput edit)throws Exception {
        control.check();
        if (command.equals("listJobs")) return new JSONObject().put("jobs", list());
        if (command.equals("getOperation")) return MediaJobJournal.visible(require(input));
        if (command.matches("generateImage|generateVideo|editImage|synthesizeSpeech|submitWorkflow")) return submit(command,input,config,key,control,edit);
        JSONObject record = require(input);
        if (!command.matches("getJob|reconcileOperation|fetchResults|cancelJob|cancel")) throw new IllegalArgumentException("不支持的媒体操作");
        if (!record.getString("provider").equals("comfyui")) {
            requireConfiguration(config,record);
            if(command.equals("fetchResults")&&record.has("_resultUrl"))return fetchConfirmedUrl(record,config,key,control);
            if(command.equals("fetchResults")&&record.has("_responseDigest")){JSONObject found=artifacts.find(source(record,config,"response"),control);if(found!=null)return save(record.put("attachments",new JSONArray().put(found)).put("attachment",found).put("resultStatus","saved").put("message","已找到原响应对应的真实固定附件；没有重发生成"));}
            return MediaJobJournal.visible(record).put("resultDownloadRetryAvailable",record.has("_resultUrl")&&!record.optString("resultStatus").equals("saved")).put("querySupported", false).put("remoteCancellationSupported", false)
                    .put("message", record.optJSONArray("attachments") != null && record.getJSONArray("attachments").length() > 0
                            ? "媒体回执已保留为附件" : "此同步媒体API不提供原请求查询或远程取消；未知效果未重试");
        }
        requireConfiguration(config, record);
        if (command.equals("cancelJob") || command.equals("cancel")) return cancel(record, config, key, control);
        if (!terminal(record)) record = query(record, config, key, control);
        if (command.equals("fetchResults")) return fetch(record, config, key, control);
        return MediaJobJournal.visible(record);
    }

    private JSONObject submit(String command,JSONObject input,JSONObject config,String key,RunControl control,MediaTransport.EditInput edit)throws Exception {
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
        String kind=command.equals("synthesizeSpeech")?"speech":command.equals("submitWorkflow")?"workflow":command.equals("generateVideo")?"video":"image";
        JSONObject request;
        if(command.equals("editImage")&&(edit==null||!provider.equals("openai_compatible")))throw new SecurityException("编辑需原私人附件字节与兼容图像编辑服务，不能拿Comfy图或JSON伪造原图");
        if (provider.equals("comfyui")) {
            if (kind.equals("speech")) throw new IllegalArgumentException("ComfyUI语音需使用显式工作流，或配置兼容TTS服务");
            request=kind.equals("workflow")?ComfyUiTransport.workflow(input.getJSONObject("workflow")):kind.equals("video")?videoWorkflow(config,input):imageWorkflow(config,input);
        } else {
            if (kind.equals("workflow")||kind.equals("video")) throw new IllegalArgumentException("此媒体服务不是ComfyUI工作流服务");
            request = MediaTransport.compatibleBody(kind, config.optString(kind.equals("image") ? "imageModel" : "ttsModel"),
                    config.optString("voice"), input.getString(kind.equals("image") ? "prompt" : "text"), config.optBoolean("legacyImageBase64"));
        }
        if(command.equals("editImage"))request.put("nativeImageInputs",edit.references());
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
                .put("_configurationHash", configurationHash).put("_providerCredentialHash", credentialHash(hash,key))
                .put("_clientId", UUID.randomUUID().toString()).put("providerJobId", provider.equals("comfyui") ? UUID.randomUUID().toString() : "")
                .put("providerJobIdConfirmed", false).put("status", "prepared").put("dispatched", false).put("verified", false)
                .put("createdAt", System.currentTimeMillis()).put("expirySupported", false).put("expiresAt", JSONObject.NULL)
                .put("outputs", new JSONArray()).put("attachments", new JSONArray())
                .put("remoteCancellation", provider.equals("comfyui") ? config.optBoolean("comfyJobsApi") ? "jobs_api" : "queue_only" : "unavailable");
        if(edit!=null)record.put("_inputArtifacts",edit.references().getJSONArray("inputArtifacts"));
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
                MediaTransport.ProviderResponse response=command.equals("editImage")
                    ?MediaTransport.editResponse(config.getString("baseUrl"),key,config.getString("imageModel"),input.getString("prompt"),config.optBoolean("legacyImageBase64"),edit,control)
                    :MediaTransport.requestResponse(config.getString("baseUrl"),key,kind,config.getString(kind.equals("image")?"imageModel":"ttsModel"),config.optString("voice"),input.getString(kind.equals("image")?"prompt":"text"),config.optBoolean("legacyImageBase64"),control);
                record.put("providerState","response_received").put("_responseDigest",response.responseDigest).put("status","response_received");
                if(!response.resultUrl.isEmpty())record.put("_resultUrl",response.resultUrl).put("resultDescriptorConfirmed",true).put("resultDownloadRetryAvailable",true);
                // Persist the exact returned URL BEFORE any result GET/import. Recovery never
                // repeats the paid image/edit POST and never exposes signed URLs in tool views.
                journal.put(record);control.check();
                if(response.payload==null)return fetchConfirmedUrl(record,config,key,control);
                if(edit!=null)edit.requireCurrent(control);
                JSONObject attachment=artifacts.save(response.payload,source(record,config,"response"),control);
                record.put("attachments",new JSONArray().put(attachment)).put("attachment",attachment).put("resultStatus","saved").put("message","原媒体响应已保存为独立附件，请检查内容；输入原件保留");
            }
        } catch (Exception failure) {
            boolean rejected = failure instanceof MediaTransport.Rejected && ((MediaTransport.Rejected) failure).definitive;
            if(record.optString("providerState").equals("response_received"))record.put("status","response_received").put("resultStatus","unavailable").put("message","服务原响应已取得，但附件尚未完整保存；只核对原响应，不重新生成或编辑");
            else record.put("status", rejected ? "failed" : "unknown").put("message", rejected ? failure.getMessage()
                    : "媒体提交或产物保存回执不完整；服务可能已受理或计费，未自动重试");
            if (failure instanceof MediaTransport.Rejected) record.put("httpStatus", ((MediaTransport.Rejected) failure).status);
            if (control.isCancelled()) record.put("localWaitCancelled", true);
        }
        return save(record);
    }

    private JSONObject fetchConfirmedUrl(JSONObject record,JSONObject config,String key,RunControl control)throws Exception {
        return fetchConfirmedUrl(record,config,key,control,false);
    }
    private JSONObject fetchConfirmedUrl(JSONObject record,JSONObject config,String key,RunControl control,boolean ownerConfirmed)throws Exception {
        if(!record.optBoolean("resultDescriptorConfirmed")||!record.optString("providerState").equals("response_received")||!record.optString("_responseDigest").matches("[a-f0-9]{64}"))throw new SecurityException("缺少原服务实际返回并保存的结果描述");
        requireConfiguration(config,record);JSONObject origin=source(record,config,"response");
        try{
            control.check();JSONObject saved=artifacts.find(origin,control);
            if(saved==null){if(!ownerConfirmed&&!config.optBoolean("allowResultUrls"))return save(record.put("resultStatus","owner_download_policy_required").put("ownerResultDownloadReviewAvailable",true).put("message","服务已返回并保存原结果地址，但本次配置未允许下载。可在原操作中审阅并一次允许取回这个原结果；不会重新生成或开启其他地址下载。"));
                byte[] bytes=dev.pocketengine.media.MediaResultDownload.getConfirmedResult(record.getString("_resultUrl"),config.getString("baseUrl"),key,control);try{saved=artifacts.save(MediaTransport.imagePayload(bytes),origin,control);}finally{java.util.Arrays.fill(bytes,(byte)0);}}
            record.put("attachments",new JSONArray().put(saved)).put("attachment",saved).put("resultStatus","saved").put("resultDownloadRetryAvailable",false).put("message","已获取原服务返回的固定图像并保存；未重新提交生成");
        }catch(Exception failure){record.put("resultStatus","unavailable").put("resultDownloadRetryAvailable",true).put("message","原结果GET或附件保存未确认；原响应描述保留，不重发生成。HTTPS/公有地址、取消或容量条件未满足时不会下载。");if(failure instanceof MediaTransport.Rejected)record.put("resultHttpStatus",((MediaTransport.Rejected)failure).status);}
        return save(record);
    }
    /** Explicit native owner review only. The signed URL never leaves the private journal. */
    public synchronized JSONObject reviewOriginalResultDownload(JSONObject input,JSONObject config,String key,RunControl control)throws Exception {
        if(input.has("downloadReview"))throw new SecurityException("原结果审阅不能由参数预先指定");
        JSONObject record=requireOriginalResultDownload(input,config,key,control);
        if("saved".equals(record.optString("resultStatus")))throw new IllegalStateException("原结果已保存，请查看现有附件");
        ownerDownloadReviews.remove(record.getString("operationId"));
        record.put("_ownerResultDownloadReviewId",UUID.randomUUID().toString()).put("_ownerResultDownloadReviewUntil",System.currentTimeMillis()+300000).put("_ownerResultDownloadReviewEpoch",input.getInt("_epoch"));
        JSONObject visible=save(record);
        ownerDownloadReviews.put(record.getString("operationId"),record.getString("_ownerResultDownloadReviewId"));
        return visible.put("resultDownloadReview",downloadReview(record,config));
    }

    /** Consume one exact owner confirmation BEFORE any GET. No persistent allow, URL input or POST. */
    public synchronized JSONObject confirmOriginalResultDownload(JSONObject input,JSONObject config,String key,RunControl control)throws Exception {
        JSONObject record=requireOriginalResultDownload(input,config,key,control);
        if(!record.has("_ownerResultDownloadReviewId")||record.optInt("_ownerResultDownloadReviewEpoch",-1)!=input.getInt("_epoch")||record.optLong("_ownerResultDownloadReviewUntil")<=System.currentTimeMillis())throw new SecurityException("原结果的一次下载审阅已失效，请重新审阅");
        JSONObject expected=downloadReview(record,config),supplied=input.getJSONObject("downloadReview");
        if(!MediaJobJournal.hash(expected).equals(MediaJobJournal.hash(supplied)))throw new SecurityException("原操作、原响应或原结果地址的下载确认已变化");
        control.check();
        // A failed/uncertain consumption write cannot reuse the token in this process;
        // a cold journal never restores live confirmations. The owner must review again.
        if(!ownerDownloadReviews.remove(record.getString("operationId"),record.getString("_ownerResultDownloadReviewId")))throw new SecurityException("原结果的一次下载审阅已使用或已冷启动失效，请重新审阅");
        record.put("_ownerResultDownloadConsumedId",record.getString("_ownerResultDownloadReviewId")).put("_ownerResultDownloadConsumedAt",System.currentTimeMillis()).put("ownerResultDownloadAttempted",true);
        record.remove("_ownerResultDownloadReviewId");record.remove("_ownerResultDownloadReviewUntil");record.remove("_ownerResultDownloadReviewEpoch");
        journal.put(record); // A crash/failure cannot reuse this owner's one-GET confirmation.
        control.check();
        return fetchConfirmedUrl(record,originalDownloadConfiguration(config,record),key,control,true);
    }

    private JSONObject requireOriginalResultDownload(JSONObject input,JSONObject config,String key,RunControl control)throws Exception {
        control.check();for(var fields=input.keys();fields.hasNext();){String field=fields.next();if(!java.util.Set.of("operationId","_operationId","taskId","_taskId","runId","_runId","epoch","_epoch","configurationRevision","_configurationRevision","providerJobId","downloadReview").contains(field))throw new SecurityException("原结果下载含未授权字段："+field);}
        JSONObject record=require(input);
        if(!control.hasHostRun()||!control.matchesHostRun(record.optString("taskId"),record.optString("runId"),input.optInt("_epoch"))||!record.getString("configurationRevision").equals(input.optString("_configurationRevision")))throw new SecurityException("原结果下载需要原主人任务的实时控制与原配置身份");
        if(!"openai_compatible".equals(record.optString("provider"))||!"image".equals(record.optString("kind"))||!record.optBoolean("resultDescriptorConfirmed")||!"response_received".equals(record.optString("providerState"))||!record.optString("_responseDigest").matches("[a-f0-9]{64}")||record.optString("_resultUrl").isEmpty())throw new SecurityException("缺少原服务实际返回并持久化的图像结果描述");
        originalDownloadConfiguration(config,record);
        // New receipts also pin the actual credential. Legacy configure receipts retain their
        // immutable vault slot through the exact original configuration hash below.
        if(record.has("_providerCredentialHash")&&!record.getString("_providerCredentialHash").equals(credentialHash(record.getString("_requestHash"),key)))throw new SecurityException("原媒体凭据已变化，不能授权下载");
        if(!record.has("_providerCredentialHash")&&!config.has("secretSlot"))throw new SecurityException("原媒体回执没有可核对的原凭据槽位");
        dev.pocketengine.media.MediaResultDownload.validate(record.getString("_resultUrl"));
        return record;
    }
    private static JSONObject originalDownloadConfiguration(JSONObject config,JSONObject record)throws Exception {
        // Only the original false policy can receive this one-off override. Reconstruct its
        // exact configured revision and false flag, then require the ORIGINAL FULL hash.
        // Provider/base URL/secret slot/models and every other setting still have to match.
        JSONObject original=new JSONObject(config.toString()).put("revision",record.getString("configurationRevision")).put("allowResultUrls",false);
        requireConfiguration(original,record);
        return original;
    }
    private static JSONObject downloadReview(JSONObject record,JSONObject config)throws Exception {
        java.net.URI target=dev.pocketengine.media.MediaResultDownload.validate(record.getString("_resultUrl")),provider=MediaTransport.base(config.getString("baseUrl"));
        return new JSONObject().put("reviewId",record.getString("_ownerResultDownloadReviewId")).put("expiresAt",record.getLong("_ownerResultDownloadReviewUntil")).put("epoch",record.getInt("_ownerResultDownloadReviewEpoch"))
                .put("operationId",record.getString("operationId")).put("taskId",record.getString("taskId")).put("runId",record.getString("runId"))
                .put("configurationRevision",record.getString("configurationRevision")).put("currentConfigurationRevision",configurationRevision(config))
                .put("provider",record.getString("provider")).put("providerOrigin",origin(provider)).put("resultOrigin",origin(target))
                .put("providerResponseSha256",record.getString("_responseDigest")).put("resultUrlSha256",urlHash(record.getString("_resultUrl")))
                .put("method","GET").put("maxAttempts",1).put("redirects",false).put("newGeneration",false).put("policyScope","original_false_policy_only");
    }
    private static String origin(java.net.URI uri)throws Exception{return new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toASCIIString();}
    private static String urlHash(String raw)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static String credentialHash(String requestHash,String key)throws Exception{return MediaJobJournal.hash(new JSONObject().put("requestIdentity",requestHash).put("credential",key==null?"":key));}

    private static JSONObject imageWorkflow(JSONObject config, JSONObject input) throws Exception {
        String prompt = input.getString("prompt");
        if (prompt.codePoints().allMatch(Character::isWhitespace) || prompt.length() > 8000) throw new IllegalArgumentException("媒体输入为空或超过长度限制");
        JSONObject graph = ComfyUiTransport.workflow(config.optJSONObject("workflow"));
        String node = config.getString("promptNodeId"), field = config.optString("promptInput", "text");
        JSONObject inputs = graph.getJSONObject(node).getJSONObject("inputs");
        if (!(inputs.opt(field) instanceof String)) throw new IllegalArgumentException("ComfyUI提示词目标必须是工作流中已有的文字输入");
        inputs.put(field, prompt);
        return ComfyUiTransport.workflow(graph);
    }

    private static JSONObject videoWorkflow(JSONObject config,JSONObject input)throws Exception{
        String prompt=input.getString("prompt");if(prompt.codePoints().allMatch(Character::isWhitespace)||prompt.length()>MediaTransport.MAX_IMAGE_INPUT_CHARS)throw new IllegalArgumentException("视频输入需1至8000字");JSONObject graph=ComfyUiTransport.workflow(config.optJSONObject("videoWorkflow"));String node=config.getString("videoPromptNodeId"),field=config.optString("videoPromptInput","text");JSONObject inputs=graph.getJSONObject(node).getJSONObject("inputs");if(!(inputs.opt(field) instanceof String))throw new SecurityException("原视频模板的文字字段改变");inputs.put(field,prompt);return ComfyUiTransport.workflow(graph);
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
            if (total > MAX_IMPORT_BYTES) throw new IllegalStateException("本任务产物超过40MiB导入上限");
            for (int i = saved.length(); i < outputs.length(); i++) {
                control.check();
                JSONObject output = outputs.getJSONObject(i);
                JSONObject found=artifacts.find(source(record,config,MediaJobJournal.hash(output)),control);
                if(found!=null){
                    total += found.optLong("size");
                    saved.put(found);
                    record.put("attachments",saved).put("resultStatus",total<=MAX_IMPORT_BYTES&&saved.length()==outputs.length()?"saved":"partial");
                    if(saved.length()==1)record.put("attachment",found);
                    save(record); // The original already exists; retain its receipt even when over budget.
                    if(total>MAX_IMPORT_BYTES)throw new IllegalStateException("本任务产物超过40MiB导入上限");
                    continue;
                }
                if (total >= MAX_IMPORT_BYTES) throw new IllegalStateException("本任务产物超过40MiB导入上限");
                byte[] bytes = MediaTransport.exchange(ComfyUiTransport.outputUri(config.getString("baseUrl"), output), key, "GET", null,
                        (int) Math.min(MediaTransport.MAX_MEDIA_BYTES, MAX_IMPORT_BYTES - total), control);
                total += bytes.length;
                if (total > MAX_IMPORT_BYTES) throw new IllegalStateException("本任务产物超过40MiB导入上限");
                MediaTransport.Payload payload = MediaTransport.outputPayload(bytes);
                control.check();
                JSONObject attachment = artifacts.save(payload, source(record, config, MediaJobJournal.hash(output)), control);
                saved.put(attachment);
                record.put("attachments", saved).put("resultStatus", saved.length() == outputs.length() ? "saved" : "partial");
                if (saved.length() == 1) record.put("attachment", attachment);
                save(record); // Preserve each import before proceeding; recovery finds the same artifact identity.
            }
            boolean video=false;for(int i=0;i<saved.length();i++)video|="video/mp4".equals(saved.getJSONObject(i).optString("mimeType"));
            if(record.optString("kind").equals("video")&&!video)record.put("resultStatus","no_video_output").put("message","原job返回的文件已保留，但没有支持的真实MP4视频；未将预览图标成视频生成成功");else record.put("resultStatus", "saved").put("message", "原媒体任务产物已保存为附件，请检查内容");
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
        result.put("configurationRevision",record.getString("configurationRevision")).put("requestIdentity",record.getString("_requestHash"));
        if(record.has("_responseDigest"))result.put("providerResponseSha256",record.getString("_responseDigest"));
        if(record.has("_inputArtifacts")){JSONArray inputs=record.getJSONArray("_inputArtifacts");result.put("inputArtifacts",inputs);if(inputs.length()>0)result.put("parentArtifact",inputs.getJSONObject(0));}
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
