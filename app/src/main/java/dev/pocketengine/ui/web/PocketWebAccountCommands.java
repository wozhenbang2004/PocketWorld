package dev.pocketengine.ui.web;

import java.util.Set;
import org.json.JSONObject;

/** Exact Web account command fields; the existing account workflow owns every operation. */
public final class PocketWebAccountCommands {
    public static final Set<String> COMMANDS=Set.of("accounts.beginLogin","accounts.cancelLogin","accounts.select","accounts.refresh","accounts.retryPlanUsage","accounts.setEnabled","accounts.refreshAuthorization","accounts.reauthorize","accounts.acknowledgePlanWelcome");
    public record Request(String command,JSONObject payload) { }
    private PocketWebAccountCommands(){ }
    public static Request prepare(String action,JSONObject args)throws Exception{
        if(!COMMANDS.contains(action))throw new IllegalArgumentException("不是账号操作");
        Set<String> fields=switch(action){
            case "accounts.beginLogin" -> Set.of("label");
            case "accounts.refresh" -> Set.of();
            case "accounts.cancelLogin" -> Set.of("sessionId");
            case "accounts.setEnabled" -> Set.of("accountId","expectedVersion","enabled");
            default -> Set.of("accountId","expectedVersion");
        };
        var keys=args.keys();while(keys.hasNext())if(!fields.contains(keys.next()))throw new IllegalArgumentException("账号请求含不适用的字段");
        JSONObject payload=new JSONObject(args.toString());
        if(action.equals("accounts.beginLogin")){
            Object raw=payload.opt("label");if(raw!=null&&!(raw instanceof String))throw new IllegalArgumentException("请填写账号名称");
            String label=raw==null?"":((String)raw).trim();if(label.isEmpty())label="我的 ChatGPT 账号";
            if(label.length()>80||label.indexOf('\u0000')>=0)throw new IllegalArgumentException("请填写 1–80 个字符的账号名称");payload.put("label",label);
        }else if(action.equals("accounts.cancelLogin"))string(payload,"sessionId");
        else if(!action.equals("accounts.refresh")){
            string(payload,"accountId");Object raw=payload.opt("expectedVersion");
            if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw new IllegalArgumentException("请重新读取原账号版本");
        }
        if(action.equals("accounts.setEnabled")&&!(payload.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("请选择此账号的启用状态");
        String command=switch(action){case "accounts.refresh" -> "snapshot";case "accounts.refreshAuthorization" -> "refresh";case "accounts.reauthorize" -> "beginLogin";default -> action.substring("accounts.".length());};
        return new Request(command,payload);
    }
    private static void string(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof String text)||text.isBlank()||text.length()>200||text.indexOf('\u0000')>=0)throw new IllegalArgumentException("请重新读取原账号或授权会话");}
}
