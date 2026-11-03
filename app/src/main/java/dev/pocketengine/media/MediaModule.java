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
                new MediaJobs.ArtifactSink(){public JSONObject save(MediaTransport.Payload payload,JSONObject source,RunControl control)throws Exception{return importArtifact(payload,source,control);}public JSONObject find(JSONObject source,RunControl control)throws Exception{return findImportedArtifact(source,control);}}, receipt -> context.emit("media", receipt));
        return jobs;
    }

    @Override public synchronized JSONObject snapshot() throws Exception {
        JSONObject config = read(), publicConfig = new JSONObject(config.toString());
        String slot = publicConfig.optString("secretSlot");
        publicConfig.remove("secretSlot");
        JSONObject workflow = publicConfig.optJSONObject("workflow");
        publicConfig.remove("workflow");JSONObject videoWorkflow=publicConfig.optJSONObject("videoWorkflow");publicConfig.remove("videoWorkflow");publicConfig.put("hasVideoWorkflow",videoWorkflow!=null);if(videoWorkflow!=null)publicConfig.put("videoWorkflowHash",MediaJobJournal.hash(videoWorkflow));
        publicConfig.put("hasApiKey", !slot.isEmpty() && context.secrets.contains(slot)).put("hasWorkflow", workflow != null);
        if (workflow != null) publicConfig.put("workflowHash", MediaJobJournal.hash(workflow));
        boolean comfy = config.optString("provider").equals("comfyui");
        return new JSONObject().put("configuration", publicConfig).put("configurationRevision", MediaJobs.configurationRevision(config))
                .put("configured", !config.optString("baseUrl").isEmpty()).put("busy", active != null)
                .put("activeOperationId", activeOperation == null ? "" : activeOperation).put("jobs", jobs().list())
                .put("serviceVerification", "not_verified_for_current_configuration").put("asyncProviderJobs", comfy)
                .put("remoteCancellation", comfy ? config.optBoolean("comfyJobsApi") ? "jobs_api" : "queue_only" : "unavailable")
                .put("commands", new JSONArray("[\"configure\",\"generateImage\",\"generateVideo\",\"editImage\",\"synthesizeSpeech\",\"submitWorkflow\",\"getJob\",\"getOperation\",\"listJobs\",\"fetchResults\",\"reconcileOperation\",\"cancelJob\",\"cancel\"]"))
                .put("inputLimits",new JSONObject().put("imagePromptChars",MediaTransport.MAX_IMAGE_INPUT_CHARS).put("speechTextChars",MediaTransport.MAX_SPEECH_INPUT_CHARS))
                .put("outputMimeTypes",new JSONArray(new String[]{"image/png","image/jpeg","image/webp","audio/wav","video/mp4"})).put("maxOutputBytes",MediaTransport.MAX_MEDIA_BYTES)
                .put("videoSupported",comfy).put("videoScope","actual_comfyui_mp4_avc_hevc_aac_only_20MiB").put("imageEditingSupported",!comfy&&!config.optString("imageModel").isEmpty()).put("imageEditingInputMaxBytes",4*1024*1024).put("imageEditingInputMaxPixels",4_000_000).put("backgroundPolling",false).put("resultUrlPolicy",new JSONObject().put("enabled",config.optBoolean("allowResultUrls")).put("httpsOnly",true).put("publicAddressAtActualConnection",true).put("crossOriginCredentials",false).put("redirects",false))
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

    /** Package-private owner bridge. Neither handle nor handleForHost accepts a JSON download grant. */
    JSONObject originalResultDownloadForOwner(String command,JSONObject input,RunControl control)throws Exception {
        if(control==null||!control.hasHostRun()||!control.matchesHostRun(input.optString("_taskId"),input.optString("_runId"),input.optInt("_epoch")))throw new SecurityException("一次原结果下载需要原主人任务实时控制");
        if(!command.matches("reviewResultDownload|confirmResultDownload"))throw new SecurityException("不是原结果下载审阅或确认");
        control.check();final JSONObject config;
        synchronized(this){
            if(closed||active!=null)throw new IllegalStateException("媒体模块已关闭或已有原请求正在执行");
            config=read();active=control;activeOperation=input.getString("operationId");
        }
        try{
            control.check();String slot=config.optString("secretSlot"),key=slot.isEmpty()?"":context.secrets.get(slot);
            JSONObject result=command.equals("reviewResultDownload")?jobs().reviewOriginalResultDownload(input,config,key,control):jobs().confirmOriginalResultDownload(input,config,key,control);
            return result.put("liveHostControl",true).put("hostRunBound",true);
        }finally{synchronized(this){if(active==control){active=null;activeOperation=null;}}}
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
        if (command.matches("reviewResultDownload|confirmResultDownload")) throw new SecurityException("一次原结果下载只能由原生主人审阅入口授权");
        if (command.equals("configure")) return configure(input);
        if (command.equals("listJobs") || command.equals("getOperation")) {
            synchronized (this) { if (closed) throw new IllegalStateException("媒体模块已关闭"); }
            return jobs().execute(command, input, new JSONObject(), "", control);
        }
        String operation = command.matches("generateImage|generateVideo|editImage|synthesizeSpeech|submitWorkflow")
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
        if(command.equals("editImage")&&(!nativeControl||!control.hasHostRun()))throw new SecurityException("图像编辑必须使用原生主人/模型原任务控制，不能自报_taskId上传原件");
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
            JSONObject result;
            if(command.equals("editImage"))try(var selected=readImageEditInputs(input,control)){result=jobs().execute(command,input,config,key,control,selected);}else result=jobs().execute(command,input,config,key,control);
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
                .put("voice", voice).put("legacyImageBase64", input.optBoolean("legacyImageBase64")).put("allowResultUrls",input.optBoolean("allowResultUrls"))
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
        if(provider.equals("comfyui")&&input.has("videoWorkflow")){JSONObject graph=ComfyUiTransport.workflow(input.getJSONObject("videoWorkflow"));String node=bounded(input.getString("videoPromptNodeId"),80),field=bounded(input.optString("videoPromptInput","text"),80);if(!(graph.getJSONObject(node).getJSONObject("inputs").opt(field) instanceof String))throw new IllegalArgumentException("视频提示词需要视频API图中已有的文字字段");next.put("videoWorkflow",graph).put("videoPromptNodeId",node).put("videoPromptInput",field);}
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

    /** Original provider receipt only; no new request and no caller-provided job descriptor. */
    public JSONObject originalReceiptForHost(String operation)throws Exception{return jobs().get(operation);}
    public boolean markOriginalReadStartedForHost(String operation,String task,String run,String revision,long until)throws Exception{return jobs().markOriginalReadStarted(operation,task,run,revision,until);}
    public void finishOriginalReadContinuationForHost(String operation,String reason,int reads)throws Exception{jobs().finishOriginalReadContinuation(operation,reason,reads);}

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

    public JSONArray imageEditInputReferencesForOwner(JSONObject args,RunControl control)throws Exception{
        try(var selected=readImageEditInputs(args,control)){return selected.references().getJSONArray("inputArtifacts");}
    }
    private MediaTransport.EditInput readImageEditInputs(JSONObject args,RunControl control)throws Exception{
        var repo=AttachmentRepository.get(context.android);var original=repo.privateOriginalForHost(args.getString("imageId"),args.getString("imageSha256"),control);byte[] imageBytes=null,maskBytes=null;
        try{
            if(original.sizeBytes>4*1024*1024)throw new IllegalArgumentException("编辑原图最多4MiB");imageBytes=original.bytes(control);var image=MediaTransport.imagePayload(imageBytes);int[] dims=imageDimensions(imageBytes,false);JSONArray refs=new JSONArray().put(inputReference(original.metadata()));
            dev.pocketengine.features.attachments.OwnerFilePreviewSource maskSource=null;MediaTransport.Payload mask=null;
            if(args.has("maskId")||args.has("maskSha256")){if(!args.has("maskId")||!args.has("maskSha256"))throw new IllegalArgumentException("mask需原附件ID与完整SHA");maskSource=repo.privateOriginalForHost(args.getString("maskId"),args.getString("maskSha256"),control);if(maskSource.sizeBytes>4*1024*1024)throw new IllegalArgumentException("mask最多4MiB");maskBytes=maskSource.bytes(control);mask=MediaTransport.imagePayload(maskBytes);if(!mask.mimeType.equals("image/png"))throw new IllegalArgumentException("编辑mask必须是含透明区域的PNG");int[] maskDims=imageDimensions(maskBytes,true);if(dims[0]!=maskDims[0]||dims[1]!=maskDims[1])throw new IllegalArgumentException("原图和mask实际像素尺寸不同，未缩放或猜测");refs.put(inputReference(maskSource.metadata()));}
            var checkedMask=maskSource;JSONObject pins=new JSONObject().put("inputArtifacts",refs).put("width",dims[0]).put("height",dims[1]).put("imageBytes",imageBytes.length).put("maskUsed",mask!=null).put("originalBytesUploaded",true);
            return MediaTransport.editInput(image,mask,pins,c->{original.requireCurrent(c);if(checkedMask!=null)checkedMask.requireCurrent(c);});
        }catch(Exception failed){if(imageBytes!=null)java.util.Arrays.fill(imageBytes,(byte)0);if(maskBytes!=null)java.util.Arrays.fill(maskBytes,(byte)0);throw failed;}
    }
    private static JSONObject inputReference(JSONObject meta)throws Exception{return new JSONObject().put("id",meta.getString("id")).put("sha256",meta.getString("sha256")).put("mimeType",meta.getString("mimeType")).put("size",meta.getLong("size")).put("name",meta.getString("name"));}
    private static int[] imageDimensions(byte[] bytes,boolean mask)throws Exception{
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>4_000_000)throw new IllegalArgumentException("编辑原图/mask最多400万像素且必须能真实解码");
        android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)throw new IllegalArgumentException("原图或mask实际字节不能解码");try{if(mask){if(!bitmap.hasAlpha())throw new IllegalArgumentException("mask没有透明通道");int[] row=new int[bitmap.getWidth()];boolean transparent=false;for(int y=0;y<bitmap.getHeight()&&!transparent;y++){bitmap.getPixels(row,0,row.length,0,y,row.length,1);for(int pixel:row)if((pixel>>>24)==0){transparent=true;break;}}if(!transparent)throw new IllegalArgumentException("mask没有完全透明的可编辑区域");}return new int[]{bitmap.getWidth(),bitmap.getHeight()};}finally{bitmap.recycle();}
    }
    private JSONObject findImportedArtifact(JSONObject source,RunControl control)throws Exception{
        AttachmentBook book=AttachmentRepository.get(context.android).book;
        if(nativePublication(source)){JSONObject found=book.findNativeResult(source.getString("operationId"),outputRole(source),source.getString("taskId"),source.getString("runId"),publicationDigest(source),control);if(found.optBoolean("found"))return found;}
        JSONArray all=book.list();for(int i=0;i<all.length();i++){JSONObject actual=all.getJSONObject(i),origin=actual.optJSONObject("source");if(origin==null||!source.getString("operationId").equals(origin.optString("operationId"))||!source.getString("artifactKey").equals(origin.optString("artifactKey")))continue;
            for(String key:new String[]{"taskId","runId","configurationRevision","requestIdentity","providerResponseSha256"})if(origin.has(key)&&!source.optString(key).equals(origin.getString(key)))throw new SecurityException("原媒体产物来源身份改变");book.readVerifiedMetadata(actual.getString("id"),actual.getString("sha256"),control);return actual;}return null;
    }
    private static String publicationDigest(JSONObject source)throws Exception{JSONObject semantic=new JSONObject(source.toString());semantic.remove("formatCheck");return MediaJobJournal.hash(semantic);}
    private static String outputRole(JSONObject source)throws Exception{return "provider_output_"+MediaJobJournal.hash(source.getString("artifactKey")).substring(0,16);}
    private static boolean nativePublication(JSONObject source){return source.optString("operationId").matches("[a-f0-9-]{36}")&&source.optString("taskId").matches("[a-f0-9-]{36}")&&source.optString("runId").matches("[a-f0-9-]{36}");}
    private JSONObject importArtifact(MediaTransport.Payload payload, JSONObject source, RunControl control) throws Exception {
        control.check();
        if (payload.mimeType.startsWith("image/")) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long) bounds.outWidth * bounds.outHeight > 33_554_432L) throw new IllegalStateException("媒体图像不能解码或尺寸超过上限");
        }
        if(payload.mimeType.equals("video/mp4"))source=new JSONObject(source.toString()).put("formatCheck",NativeMediaValidation.mp4(payload.bytes,context.android.getCacheDir(),control));
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
            JSONObject attachment=nativePublication(source)?book.importNativeResult(input,"generated-"+source.getString("operationId")+"."+payload.extension,payload.mimeType,source,source.getString("operationId"),outputRole(source),source.getString("taskId"),source.getString("runId"),publicationDigest(source),control):book.importBytes(input,"generated-"+source.getString("operationId")+"."+payload.extension,payload.mimeType,source,control);
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
