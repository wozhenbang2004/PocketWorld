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
        return first(input,false);
    }
    /** Only the original private connection editor uses this explicit native entry point. */
    public static JSONObject firstForPrivateConnection(JSONObject input)throws Exception {
        return first(input,true);
    }
    private static JSONObject first(JSONObject input,boolean privateConnection)throws Exception {
        Set<String> allowed=Set.of("profileId","bindingHash","environmentId","environmentBinding","worker","prompt","argv","model","readOnly","timeoutSeconds","effort","modelOptionsDigest","backendId","capabilityRevision","modeId","harnessSelection","externalExecutionRiskAcknowledged");
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("首创任务不接受会话、原任务、凭据或任意路由参数");
        String profile=id(input,"profileId"),environment=id(input,"environmentId"),binding=text(input,"bindingHash",64),environmentBinding=text(input,"environmentBinding",64),worker=text(input,"worker",80);
        boolean generic=RemoteHarnessSelection.isGenericWorker(worker);
        if(!binding.matches("[a-f0-9]{64}")||!environmentBinding.matches("[a-f0-9]{64}")||!worker.matches("command|codex_app_server|claude|pi|gemini_acp")&&!generic)throw new IllegalArgumentException("请选择当前已配置的电脑和受支持执行器");
        boolean gemini=worker.equals("gemini_acp");
        if(gemini&&!privateConnection)throw new SecurityException("Gemini后端只在原私人连接页接入，不向模型任务入口开放");
        if(!gemini&&(input.has("backendId")||input.has("capabilityRevision")||input.has("modeId")&&!generic))throw new SecurityException("此执行器不接收另一后端的探测或模式选项");
        if(!generic&&input.has("harnessSelection"))throw new SecurityException("不能将通用 Harness 选择附加给另一协议");
        Object timeout=input.opt("timeoutSeconds");int seconds=input.optInt("timeoutSeconds",300);
        if((timeout!=null&&(!(timeout instanceof Number)||((Number)timeout).doubleValue()!=seconds))||seconds<10||seconds>3600)throw new IllegalArgumentException("任务时限需为10至3600秒整数");
        JSONObject result=new JSONObject().put("profileId",profile).put("_profileBinding",binding).put("environmentId",environment).put("environmentBinding",environmentBinding).put("worker",worker).put("timeoutSeconds",seconds);
        if(worker.equals("command")){
            if(input.has("prompt")||input.has("model")||input.has("readOnly")||input.has("effort")||input.has("modelOptionsDigest"))throw new IllegalArgumentException("命令任务只接受明确argv，不将自然语言当Shell执行");
            JSONArray argv=input.optJSONArray("argv");if(argv==null||argv.length()<1||argv.length()>80)throw new IllegalArgumentException("命令需为1至80个明确参数的JSON数组");
            int bytes=0;for(int i=0;i<argv.length();i++){Object value=argv.get(i);if(!(value instanceof String)||((String)value).length()>12000||((String)value).indexOf('\0')>=0||i==0&&blank((String)value))throw new IllegalArgumentException("命令参数无效");bytes+=((String)value).length();}
            if(bytes>16000)throw new IllegalArgumentException("命令参数总长度超过16000字符");result.put("argv",new JSONArray(argv.toString()));
        }else{
            if(input.has("argv"))throw new IllegalArgumentException("编码执行器接收目标，不接收任意启动命令");
            result.put("prompt",text(input,"prompt",12000));if(input.has("model"))result.put("model",text(input,"model",200));
            if(input.has("readOnly")&&!(input.opt("readOnly") instanceof Boolean))throw new IllegalArgumentException("readOnly需为明确布尔值");
            if(gemini){
                if(input.opt("readOnly")!=Boolean.FALSE||!"gemini_cli_acp_v1".equals(input.opt("backendId"))||!"default".equals(input.opt("modeId")))throw new SecurityException("此Gemini后端仅支持明确允许写入的default模式，不能声明只读或省略确认");
                Object revision=input.opt("capabilityRevision");
                if(!(revision instanceof String)||!((String)revision).matches("[a-f0-9]{64}"))throw new SecurityException("请先核对当前原生后端探测版本");
                result.put("backendId","gemini_cli_acp_v1").put("capabilityRevision",revision).put("modeId","default");
            }
            boolean readOnly=input.optBoolean("readOnly",worker.equals("codex_app_server"));
            if(generic){
                if(input.opt("readOnly")!=Boolean.FALSE||input.opt("externalExecutionRiskAcknowledged")!=Boolean.TRUE)throw new SecurityException("主人配置 Harness 需明确允许实际工具执行，不能宣称只读沙箱");
                result.put("harnessSelection",RemoteHarnessSelection.checked(input.optJSONObject("harnessSelection"),worker)).put("externalExecutionRiskAcknowledged",true);
                if(input.has("modeId"))result.put("modeId",text(input,"modeId",200));
            }
            if((worker.equals("claude")||worker.equals("pi"))&&readOnly)throw new SecurityException("CLI工具限制不是操作系统只读沙箱，不能声明只读");
            if(input.has("effort")||input.has("modelOptionsDigest")){
                if(!worker.equals("codex_app_server")||!input.has("model"))throw new SecurityException("首创档位只接受当前Codex目录；Claude须先在已批准原job取得native目录再由主人下一轮选择");
                if(input.has("effort"))result.put("effort",text(input,"effort",80));
                if(input.has("modelOptionsDigest")){String digest=text(input,"modelOptionsDigest",64);if(!digest.matches("[a-f0-9]{64}"))throw new SecurityException("原目录digest无效");result.put("modelOptionsDigest",digest);}
            }
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
