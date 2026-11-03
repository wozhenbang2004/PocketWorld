package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact model-requested command data. Not a tool resolver, shell parser, secret channel or grant. */
public final class ProjectCommandSpec {
    public static final int MAX_ARGS=96,MAX_ARGUMENT_CHARS=4096,MAX_ARGUMENT_BYTES=20000;
    public static final int MAX_ENV=16,MAX_ENV_BYTES=8192,MAX_INPUT_BYTES=262144,MAX_INPUT_CHUNK=4096;
    public static final long MAX_TIMEOUT_MILLIS=300000;
    public static final int MAX_FOREGROUND_WAIT_MILLIS=30000;
    private static final Set<String> ORDINARY_ENV=Set.of("PORT","HOST","NODE_ENV","DEBUG","LOG_LEVEL","CI","NO_COLOR","FORCE_COLOR","TZ","PYTHONUNBUFFERED");
    private ProjectCommandSpec() {}
    public static JSONObject normalize(JSONObject input)throws Exception {
        String executable=input.getString("executable");
        if(!executable.matches("[a-z][a-z0-9_-]{0,39}"))throw new IllegalArgumentException("请选择此环境已登记的工具名；不接受任意宿主路径或拼接命令");
        JSONArray requested=input.getJSONArray("argv"),argv=new JSONArray();if(requested.length()>MAX_ARGS)throw new IllegalArgumentException("命令参数最多96项");int argumentBytes=0;
        for(int i=0;i<requested.length();i++){
            Object raw=requested.get(i);if(!(raw instanceof String))throw new IllegalArgumentException("每项argv必须是原样文字，不进行Shell拆词");String value=(String)raw;
            byte[] bytes=encode(value);if(value.length()>MAX_ARGUMENT_CHARS||(argumentBytes+=bytes.length)>MAX_ARGUMENT_BYTES)throw new IllegalArgumentException("命令参数超过单项4096字符或合计20000字节范围");argv.put(value);
        }
        String cwd=input.optString("cwd",".");if(!cwd.equals("."))cwd=VersionedWorkspace.path(cwd);
        if(cwd.length()>240)throw new IllegalArgumentException("命令工作目录过长");
        String mode=input.optString("runMode","foreground"),terminal=input.optString("terminal","pipes");
        if(!Set.of("foreground","owned_service").contains(mode)||!Set.of("pipes","pty").contains(terminal))throw new IllegalArgumentException("命令运行模式或终端类型无效");
        if(terminal.equals("pty")&&!mode.equals("owned_service"))throw new IllegalArgumentException("交互PTY必须明确选择本轮受控会话，才能继续读写同一个执行");
        long timeout=integer(input,"timeoutMillis",300000,1,MAX_TIMEOUT_MILLIS);
        JSONObject environment=new JSONObject();Object envValue=input.opt("env");if(envValue!=null&&!(envValue instanceof JSONObject))throw new IllegalArgumentException("env必须是普通非秘密环境参数对象");JSONObject requestedEnv=(JSONObject)envValue;
        if(requestedEnv!=null){if(requestedEnv.length()>MAX_ENV)throw new IllegalArgumentException("普通环境参数最多16项");int total=0;
            for(java.util.Iterator<String> keys=requestedEnv.keys();keys.hasNext();){String key=keys.next();if(!allowedEnvironmentKey(key))throw new IllegalArgumentException("此环境参数名称不在允许范围，加载器/路径/解释器注入及凭据变量不能覆盖");Object raw=requestedEnv.get(key);if(!(raw instanceof String))throw new IllegalArgumentException("环境参数值必须是普通文字");String value=(String)raw;byte[] bytes=encode(value);if(value.length()>2048||(total+=key.length()+bytes.length)>MAX_ENV_BYTES)throw new IllegalArgumentException("普通环境参数超出2048字符/合计8192字节范围");environment.put(key,value);}
        }
        JSONObject result=new JSONObject().put("executable",executable).put("argv",argv).put("workingDirectory",cwd).put("environment",environment).put("timeoutMillis",timeout).put("runMode",mode).put("terminalMode",terminal);
        if(input.has("foregroundWaitMillis")){
            long wait=integer(input,"foregroundWaitMillis",-1,1,MAX_FOREGROUND_WAIT_MILLIS);
            if(!mode.equals("owned_service")||wait>timeout)throw new IllegalArgumentException("前台短等后继续观察需明确选择原受控会话，等待不能超过原批准总期限");
            result.put("foregroundWaitMillis",wait).put("backgroundObservationExplicitlyIncluded",true);
        }
        if(terminal.equals("pty"))result.put("terminalColumns",integer(input,"columns",80,4,400)).put("terminalRows",integer(input,"rows",24,2,200)).put("inputByteLimit",integer(input,"inputByteLimit",65536,1,MAX_INPUT_BYTES)).put("interactiveInputExplicitlyIncluded",true).put("terminalOutputMerged",true);
        else for(String name:new String[]{"columns","rows","inputByteLimit"})if(input.has(name))throw new IllegalArgumentException("普通管道命令不能声明交互终端参数");
        return result;
    }
    /** Recheck the exact native payload at execution. No environment value is copied to receipts. */
    public static JSONObject fromPayload(JSONObject payload)throws Exception {
        JSONObject input=new JSONObject().put("executable",payload.getString("executable")).put("argv",payload.getJSONArray("argv")).put("cwd",payload.getString("workingDirectory")).put("env",payload.getJSONObject("environment")).put("timeoutMillis",payload.get("timeoutMillis")).put("runMode",payload.getString("runMode")).put("terminal",payload.getString("terminalMode"));
        if(payload.has("foregroundWaitMillis")){
            if(!Boolean.TRUE.equals(payload.opt("backgroundObservationExplicitlyIncluded")))throw new SecurityException("原启动批准没有明确包括短等后的同执行观察");
            input.put("foregroundWaitMillis",payload.get("foregroundWaitMillis"));
        }else if(payload.has("backgroundObservationExplicitlyIncluded"))throw new SecurityException("没有原批准的前台观察时窗");
        if(payload.getString("terminalMode").equals("pty")){
            if(!Boolean.TRUE.equals(payload.opt("interactiveInputExplicitlyIncluded"))||!Boolean.TRUE.equals(payload.opt("terminalOutputMerged")))throw new SecurityException("原交互输入批准范围不完整");
            input.put("columns",payload.get("terminalColumns")).put("rows",payload.get("terminalRows")).put("inputByteLimit",payload.get("inputByteLimit"));
        }
        JSONObject normalized=normalize(input);for(java.util.Iterator<String> keys=normalized.keys();keys.hasNext();){String key=keys.next();if(!ProjectSources.canonical(normalized.get(key)).equals(ProjectSources.canonical(payload.get(key))))throw new SecurityException("原命令规范化范围已变化");}return normalized;
    }
    /** One ordinary, non-secret input action. Target/operation identity remains native host data. */
    public static JSONObject inputRequest(JSONObject args)throws Exception {
        for(java.util.Iterator<String> keys=args.keys();keys.hasNext();)if(!Set.of("executionId","action","text","rows","columns").contains(keys.next()))throw new IllegalArgumentException("终端输入包含不支持的字段");
        String action=args.getString("action");JSONObject request=new JSONObject().put("action",action);
        switch(action){
            case "text":if(!(args.opt("text") instanceof String)||args.has("rows")||args.has("columns"))throw new IllegalArgumentException("文字输入需明确text，不能混合窗口操作");String text=args.getString("text");int size=encode(text).length;if(size<1||size>MAX_INPUT_CHUNK)throw new IllegalArgumentException("每次普通终端输入限1至4096字节，不支持秘密输入");request.put("text",text);break;
            case "eof":if(args.has("text")||args.has("rows")||args.has("columns"))throw new IllegalArgumentException("EOF不能混合文字或窗口操作");break;
            case "resize":if(args.has("text"))throw new IllegalArgumentException("窗口调整不能混合文字输入");long rows=integer(args,"rows",-1,2,200),columns=integer(args,"columns",-1,4,400);if(rows<2||columns<4)throw new IllegalArgumentException("窗口调整必须明确行列");request.put("rows",(int)rows).put("columns",(int)columns);break;
            default:throw new IllegalArgumentException("终端输入动作只支持text/eof/resize");
        }
        return request;
    }
    public static String inputScopeDigest(JSONObject payload)throws Exception{return ProjectSources.digest(new JSONObject().put("command",fromPayload(payload)).put("terminalNativeContract",payload.getString("terminalNativeContract")));}
    public static boolean allowedEnvironmentKey(String key){
        if(key==null||key.length()>64||!key.matches("[A-Z][A-Z0-9_]*"))return false;
        if(key.matches(".*(?:TOKEN|PASSWORD|PASSWD|SECRET|CREDENTIAL|PRIVATE_KEY|API_KEY|ACCESS_KEY).*")||key.startsWith("PROOT")||key.startsWith("PROROOT")||key.startsWith("POCKETWORLD")||key.startsWith("LD_")||key.startsWith("DYLD_"))return false;
        return ORDINARY_ENV.contains(key)||key.startsWith("APP_")||key.startsWith("PW_USER_");
    }
    public static byte[] encode(String value)throws Exception{if(value==null||value.indexOf('\0')>=0)throw new IllegalArgumentException("命令文字不能含空字节");var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}
    public static long integer(JSONObject input,String field,long fallback,long minimum,long maximum)throws Exception{
        if(!input.has(field))return fallback;Object raw=input.get(field);if(!(raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException("命令数值必须是整数："+field);long value=((Number)raw).longValue();if(value<minimum||value>maximum)throw new IllegalArgumentException("命令数值超出允许范围："+field);return value;
    }
}
