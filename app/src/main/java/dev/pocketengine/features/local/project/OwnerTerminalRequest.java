package dev.pocketengine.features.local.project;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner form over the existing command/PTY contract, without a model turn. */
public final class OwnerTerminalRequest {
    private OwnerTerminalRequest(){}
    public static JSONObject command(JSONObject supplied)throws Exception {
        if(supplied==null||supplied.toString().length()>12000)throw new IllegalArgumentException("手动终端表单过大");
        Set<String> allowed=Set.of("projectId","checkoutId","expectedSourceDigest","runtimeId","cwd","env","timeoutMillis","columns","rows","inputByteLimit");
        for(var keys=supplied.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("手动终端表单不能替换任务、命令或执行身份");
        for(String key:new String[]{"projectId","checkoutId"})if(!(supplied.opt(key) instanceof String)||!supplied.getString(key).matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("请选择原项目及工作副本");
        if(!"managed_process_v1".equals(supplied.optString("runtimeId"))||!supplied.optString("expectedSourceDigest").matches("[a-f0-9]{64}"))throw new SecurityException("手动PTY仅使用已核对的本机环境及原工作副本");
        // These dimensions are shared with the existing bounded xterm view contract.
        ProjectCommandSpec.integer(supplied,"columns",80,20,300);ProjectCommandSpec.integer(supplied,"rows",24,5,120);
        JSONObject input=new JSONObject(supplied.toString()).put("intent","command").put("executable","sh").put("argv",new JSONArray().put("-i")).put("runMode","owned_service").put("terminal","pty");
        ProjectCommandSpec.normalize(input);return input;
    }
}
