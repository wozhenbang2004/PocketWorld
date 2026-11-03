package dev.pocketengine.features.remote;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** First-create contract only. Session continuation has a different native owner review. */
public final class RemoteTaskRequest {
    private RemoteTaskRequest() { }
    public static final String INPUT_PROTOCOL="private_inputs_v1";
    public static JSONArray inputManifest(JSONArray supplied)throws Exception {
        if(supplied==null||supplied.length()<1||supplied.length()>8)throw new SecurityException("请明确选择1至8个私人附件");
        JSONArray result=new JSONArray();Set<String> ids=new java.util.HashSet<>();long total=0;
        for(int i=0;i<supplied.length();i++){
            JSONObject value=supplied.getJSONObject(i);String id=value.getString("attachmentId"),name=value.getString("name"),mime=value.getString("mimeType"),sha=value.getString("sha256");
            Object raw=value.opt("bytes");long bytes=value.getLong("bytes");
            if(value.length()!=5||!id.matches("[a-f0-9-]{36}")||!ids.add(id)||name.isEmpty()||name.length()>160||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>253||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.matches(".*[\\p{Cntrl}].*"))throw new SecurityException("私人附件名称或原件身份无效");
            for(int n=0;n<name.length();n++)if(Character.isSurrogate(name.charAt(n))&&(Character.isLowSurrogate(name.charAt(n))||n+1>=name.length()||!Character.isLowSurrogate(name.charAt(++n))))throw new SecurityException("附件名称含无效Unicode");
            if(!(raw instanceof Integer||raw instanceof Long)||bytes<0||bytes>262144||!sha.matches("[a-f0-9]{64}")||mime.length()>200||!mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))throw new SecurityException("附件声明的类型、大小或hash无效");
            total=Math.addExact(total,bytes);result.put(new JSONObject(value.toString()));
        }
        if(total>524288)throw new SecurityException("本次附件总计不能超过512KiB");return result;
    }
    public static String inputHash(JSONArray files)throws Exception {
        JSONArray checked=inputManifest(files);StringBuilder value=new StringBuilder();
        for(int i=0;i<checked.length();i++){JSONObject file=checked.getJSONObject(i);for(String key:new String[]{"attachmentId","sha256","bytes","name","mimeType"})value.append(file.get(key)).append('\n');}
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    public static void requireInputCapability(JSONObject environment)throws Exception {
        JSONObject capability=environment.optJSONObject("privateInputCapabilities");
        if(capability==null||!INPUT_PROTOCOL.equals(capability.optString("protocol"))||capability.opt("supported")!=Boolean.TRUE||!capability.optBoolean("binaryStreaming")||!capability.optBoolean("workerStartsOnlyAfterReady")||!capability.optBoolean("monotonicStateRevision")
                ||capability.optInt("maxFiles")<8||capability.optLong("maxFileBytes")<262144||capability.optLong("maxTotalBytes")<524288)throw new SecurityException("此环境未支持已核二进制附件暂存；不降级为文字写入，请保留无附件路径或更新执行端");
    }
    public static final String INPUT_CONTINUATION_PROTOCOL="private_input_continuation_v1";
    public static void requireInputContinuationCapability(JSONObject environment,String worker)throws Exception {
        requireInputCapability(environment);JSONObject cap=environment.getJSONObject("privateInputCapabilities");boolean found=false;JSONArray workers=cap.optJSONArray("continuationWorkers");for(int i=0;workers!=null&&i<workers.length();i++)if(worker.equals(workers.getString(i)))found=true;
        if(!INPUT_CONTINUATION_PROTOCOL.equals(cap.optString("continuationProtocol"))||!cap.optBoolean("originalSessionPrelaunchCheck")||!found)throw new SecurityException("此执行端未声明原会话附件续接；保留文字续接或更新执行端，不降级到新会话");
    }
    public static JSONObject inputContinuationSource(JSONObject source)throws Exception {
        JSONObject value=new JSONObject();for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","threadId","workspaceIdentity"})value.put(key,source.get(key));for(String key:new String[]{"turnId","sessionId","promptResultIdentity"})if(source.has(key)&&source.opt(key)!=JSONObject.NULL)value.put(key,source.get(key));return value;
    }
    public static void requireGroupApiMode(JSONObject proof,JSONObject request)throws Exception {
        if(!"codex_app_server".equals(request.optString("worker"))||!request.optBoolean("readOnly")||!"apiKey".equals(proof.optString("authMode"))||proof.opt("accountReadVerified")!=Boolean.TRUE||proof.opt("configuredCredentialSourceVerified")!=Boolean.TRUE||proof.opt("billingBackendVerified")!=Boolean.FALSE||proof.opt("internalTokensKnown")!=Boolean.FALSE||proof.opt("internalCostKnown")!=Boolean.FALSE||!request.getString("environmentId").equals(proof.optString("environmentId"))||!request.getString("environmentBinding").equals(proof.optString("environmentBinding"))||!proof.optString("credentialGeneration").matches("[a-f0-9]{64}")||!proof.optString("proofToken").matches("[a-f0-9]{64}")||proof.optLong("observedAt")>System.currentTimeMillis()+5000||proof.optLong("expiresAt")<=System.currentTimeMillis()||proof.optLong("expiresAt")>proof.optLong("observedAt")+120000)throw new SecurityException("缺少当前原环境/只读API-key模式代次证明；未知不外传群资料");
    }
    public static JSONObject inputReceipt(JSONObject actual,JSONObject scope)throws Exception {
        JSONObject request=scope.getJSONObject("request");
        for(String key:new String[]{"profileId","serviceBase"})if(!scope.getString(key).equals(actual.optString(key)))throw new SecurityException("附件回执改变了原目标");
        for(String key:new String[]{"environmentId","worker"})if(!request.getString(key).equals(actual.optString(key)))throw new SecurityException("附件回执不属于原环境/执行器");
        if(!scope.getString("jobOperationId").equals(actual.optString("operationId"))||!actual.optString("id").matches("[A-Za-z0-9_-]{1,80}")||!actual.optString("workspaceIdentity").matches("[a-f0-9]{64}"))throw new SecurityException("缺少原job或物理工作区回执");
        if(scope.has("jobId")&&!scope.getString("jobId").equals(actual.optString("id")))throw new SecurityException("附件回执属于另一job");
        JSONObject state=actual.getJSONObject("inputState");
        if(scope.has("groupInputSource")){if(!"EXTERNAL_GROUP".equals(state.optString("inferenceAudience"))||state.optJSONObject("groupInputSource")==null||state.optJSONObject("externalGroupApiMode")==null||!dev.pocketengine.store.TaskStore.capabilityFingerprint(scope.getJSONObject("groupInputSource")).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(state.getJSONObject("groupInputSource")))||!dev.pocketengine.store.TaskStore.capabilityFingerprint(scope.getJSONObject("externalGroupApiMode")).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(state.getJSONObject("externalGroupApiMode"))))throw new SecurityException("回执把原群来源/API-mode范围换成私人或另一群");}
        else if(state.has("inferenceAudience")||state.has("groupInputSource")||state.has("externalGroupApiMode"))throw new SecurityException("私人输入不能被回执改成另一个外部受众");
        JSONArray expected=inputManifest(scope.getJSONArray("files")),files=state.getJSONArray("files");
        if(scope.has("continuationSource")){
            JSONObject expectedSource=inputContinuationSource(scope.getJSONObject("continuationSource"));
            if(!INPUT_CONTINUATION_PROTOCOL.equals(state.optString("continuationProtocol"))||(state.optJSONObject("continuationSource")==null||!dev.pocketengine.store.TaskStore.capabilityFingerprint(expectedSource).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(state.getJSONObject("continuationSource"))))||!expectedSource.getString("jobId").equals(actual.optString("resumeJobId"))||!expectedSource.getString("threadId").equals(actual.optString("threadId")))throw new SecurityException("输入续接回执改变了原job、会话或上一轮基线");
        }else if(state.has("continuationProtocol")||state.has("continuationSource"))throw new SecurityException("首创输入不能被回执改成其它会话续接");
        if(!(state.opt("revision") instanceof Integer||state.opt("revision") instanceof Long)||state.getLong("revision")<1)throw new SecurityException("缺少原job单调输入版本，不能用时间戳猜附件进度");
        if(!INPUT_PROTOCOL.equals(state.optString("protocol"))||!scope.getString("manifestHash").equals(state.optString("manifestHash"))||!scope.getString("inputDirectory").equals(state.optString("inputDirectory"))||state.opt("cleanupConfirmed")!=Boolean.FALSE||files.length()!=expected.length())throw new SecurityException("暂存回执与明确所选附件不一致");
        if(!state.optString("phase").matches("awaiting_upload|uploading|ready|starting|running|completed|failed|cancelled|unknown|blocked|interrupted"))throw new SecurityException("未知附件阶段，未授权后继派发");
        long total=0,verified=0;boolean all=true;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i),original=expected.getJSONObject(i);
            for(String key:new String[]{"attachmentId","name","mimeType","sha256"})if(!original.getString(key).equals(file.optString(key)))throw new SecurityException("暂存文件身份变化");
            long bytes=original.getLong("bytes"),received=file.getLong("receivedBytes");
            if(file.getLong("bytes")!=bytes||i!=file.getInt("index")||received<0||received>bytes||!(scope.getString("inputDirectory")+"/"+i+"-"+original.getString("name")).equals(file.optString("path"))||!file.optString("state").matches("pending|uploading|verified|unknown"))throw new SecurityException("暂存文件范围、进度或字节变化");
            total+=bytes;boolean accepted=file.optString("state").equals("verified");all&=accepted;
            if(accepted){if(received!=bytes||!original.getString("sha256").equals(file.optString("actualSha256")))throw new SecurityException("远端尚未验证实际完整字节");verified+=bytes;}
        }
        if(total!=state.getLong("bytes")||verified!=state.getLong("verifiedBytes")||state.optString("phase").matches("ready|starting|running|completed")&&!all||state.optBoolean("workerStarted")&&!all)throw new SecurityException("ready/worker回执没有全部实际hash证据");
        return new JSONObject(actual.toString());
    }
    public static JSONObject first(JSONObject input)throws Exception {
        Set<String> allowed=Set.of("profileId","bindingHash","environmentId","environmentBinding","worker","prompt","argv","model","readOnly","timeoutSeconds");
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("首创任务不接受会话、原任务、凭据或任意路由参数");
        String profile=id(input,"profileId"),environment=id(input,"environmentId"),binding=text(input,"bindingHash",64),environmentBinding=text(input,"environmentBinding",64),worker=text(input,"worker",40);
        if(!binding.matches("[a-f0-9]{64}")||!environmentBinding.matches("[a-f0-9]{64}")||!worker.matches("command|codex_app_server|claude|pi"))throw new IllegalArgumentException("请选择当前已配置的电脑和受支持执行器");
        Object timeout=input.opt("timeoutSeconds");int seconds=input.optInt("timeoutSeconds",300);
        if((timeout!=null&&(!(timeout instanceof Number)||((Number)timeout).doubleValue()!=seconds))||seconds<10||seconds>3600)throw new IllegalArgumentException("任务时限需为10至3600秒整数");
        JSONObject result=new JSONObject().put("profileId",profile).put("_profileBinding",binding).put("environmentId",environment).put("environmentBinding",environmentBinding).put("worker",worker).put("timeoutSeconds",seconds);
        if(worker.equals("command")){
            if(input.has("prompt")||input.has("model")||input.has("readOnly"))throw new IllegalArgumentException("命令任务只接受明确argv，不将自然语言当Shell执行");
            JSONArray argv=input.optJSONArray("argv");if(argv==null||argv.length()<1||argv.length()>80)throw new IllegalArgumentException("命令需为1至80个明确参数的JSON数组");
            int bytes=0;for(int i=0;i<argv.length();i++){Object value=argv.get(i);if(!(value instanceof String)||((String)value).length()>12000||((String)value).indexOf('\0')>=0||i==0&&blank((String)value))throw new IllegalArgumentException("命令参数无效");bytes+=((String)value).length();}
            if(bytes>16000)throw new IllegalArgumentException("命令参数总长度超过16000字符");result.put("argv",new JSONArray(argv.toString()));
        }else{
            if(input.has("argv"))throw new IllegalArgumentException("编码执行器接收目标，不接收任意启动命令");
            result.put("prompt",text(input,"prompt",12000));if(input.has("model"))result.put("model",text(input,"model",200));
            if(input.has("readOnly")&&!(input.opt("readOnly") instanceof Boolean))throw new IllegalArgumentException("readOnly需为明确布尔值");
            boolean readOnly=input.optBoolean("readOnly",worker.equals("codex_app_server"));
            if((worker.equals("claude")||worker.equals("pi"))&&readOnly)throw new SecurityException("CLI工具限制不是操作系统只读沙箱，不能声明只读");
            result.put("readOnly",readOnly);
        }
        return result;
    }
    /** The actual advertised environment is evidence, not an arbitrary model-supplied host. */
    public static JSONObject environment(JSONObject response,String environmentId,String worker)throws Exception {
        JSONArray environments=response.optJSONArray("environments");JSONObject found=null;
        for(int i=0;environments!=null&&i<environments.length();i++){JSONObject item=environments.getJSONObject(i);if(environmentId.equals(item.optString("id"))){if(found!=null)throw new SecurityException("环境身份重复，无法安全选择");found=item;}}
        if(found==null)throw new SecurityException("远端环境已移除，请重新读取实际环境");
        JSONArray workers=found.optJSONArray("workers");JSONObject selected=null;
        for(int i=0;workers!=null&&i<workers.length();i++){JSONObject item=workers.getJSONObject(i);if(worker.equals(item.optString("id"))){if(selected!=null)throw new SecurityException("执行器身份重复");selected=item;}}
        if(selected==null||Boolean.FALSE.equals(selected.opt("available")))throw new SecurityException("此环境未启用或缺少所选执行器");
        return new JSONObject(found.toString());
    }
    private static boolean blank(String value){for(int i=0;i<value.length();i++)if(!Character.isWhitespace(value.charAt(i)))return false;return true;}
    private static String id(JSONObject input,String key)throws Exception {String value=text(input,key,80);if(!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("电脑或环境身份无效");return value;}
    private static String text(JSONObject input,String key,int max)throws Exception {Object value=input.opt(key);if(!(value instanceof String)||blank((String)value)||((String)value).length()>max||((String)value).indexOf('\0')>=0)throw new IllegalArgumentException("请填写有效的"+key);return ((String)value).trim();}
}
