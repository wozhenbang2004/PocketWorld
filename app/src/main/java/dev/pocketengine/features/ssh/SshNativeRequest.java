package dev.pocketengine.features.ssh;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONObject;

/** Immutable native request description, not an authorization or a deserializable execution grant.
 * Original TaskStore row + installed native authority must independently accept every dispatch. */
public final class SshNativeRequest {
    private final String command,hash;
    private final JSONObject scope;
    private SshNativeRequest(String command,JSONObject scope)throws Exception{this.command=command;this.scope=new JSONObject(scope.toString());hash=scope.getString("requestHash");}
    static SshNativeRequest capture(String command,JSONObject original)throws Exception{return new SshNativeRequest(command,describe(command,original));}
    public String command(){return command;}
    public String requestHash(){return hash;}
    public JSONObject view()throws Exception{return new JSONObject(scope.toString());}
    public static boolean guarded(String command){return command!=null&&command.matches("exec|writeFile|inspectFile|downloadFile|uploadFile|openPreviewForward");}
    /** Pure data for the original native queued-row journal. Public JSON cannot restore the type. */
    public static JSONObject describe(String command,JSONObject input)throws Exception {
        if(!guarded(command))throw new SecurityException("不是已支持的原生SSH受控请求");
        JSONObject scope=new JSONObject().put("format","pocketworld.ssh.native-request.v1").put("command",command).put("profileId",SshProfile.text(input,"profileId",80)).put("profileBinding",SshProfile.text(input,"_profileBinding",64));
        if(!scope.getString("profileBinding").matches("[a-f0-9]{64}"))throw new SecurityException("原SSH配置版本无效");
        if(command.equals("openPreviewForward")){
            if(!Boolean.TRUE.equals(input.opt("acceptPreviewRisk")))throw new SecurityException("请明确核对所选开发端口、源任务及网页/WS风险");
            scope.put("remoteHost","127.0.0.1").put("remotePort",integer(input,"remotePort",0,1024,65535)).put("timeoutSeconds",integer(input,"timeoutSeconds",300,10,600)).put("acceptPreviewRisk",true);
            JSONObject original=input.getJSONObject("previewSource"),source=new JSONObject();for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})source.put(key,original.get(key));if(original.has("workspaceIdentity"))source.put("workspaceIdentity",original.get("workspaceIdentity"));scope.put("previewSource",source);
        }
        else if(command.equals("exec")){String body=SshProfile.text(input,"command",12000);scope.put("commandSha256",SshProfile.hash(body.getBytes(StandardCharsets.UTF_8))).put("commandChars",body.length()).put("timeoutSeconds",integer(input,"timeoutSeconds",60,1,300));}
        else {
            scope.put("path",SshProfile.relative(SshProfile.text(input,"path",1024),false));
            if(command.equals("writeFile")){Object raw=input.opt("content");if(!(raw instanceof String))throw new IllegalArgumentException("原文本写入内容无效");byte[] bytes=((String)raw).getBytes(StandardCharsets.UTF_8);if(bytes.length>262144)throw new IllegalArgumentException("原文本写入仍最多256KiB");scope.put("contentSha256",SshProfile.hash(bytes)).put("contentBytes",bytes.length);}
            else scope.put("timeoutSeconds",integer(input,"timeoutSeconds",180,10,600)).put("maxTransferBytes",DirectSshClient.MAX_TRANSFER);
            if(!command.equals("inspectFile")){String version=SshProfile.text(input,"expectedVersion",80);if(!version.matches(command.equals("downloadFile")?"sha256:[a-f0-9]{64}":"absent|sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("需要准确远端基线版本");scope.put("expectedVersion",version);}
            if(command.equals("uploadFile")){String id=SshProfile.text(input,"attachmentId",80),sha=SshProfile.text(input,"expectedSha256",64);if(!id.matches("[a-f0-9-]{36}")||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("需要原私人附件准确ID和SHA256");scope.put("attachmentId",id).put("expectedSha256",sha);}
            if(command.matches("writeFile|uploadFile")){if(!(input.opt("acceptCooperativeWrite") instanceof Boolean)||!input.getBoolean("acceptCooperativeWrite"))throw new SecurityException("请明确确认本次协作写入风险");scope.put("acceptCooperativeWrite",true);}
        }
        ArrayList<String> keys=new ArrayList<>();for(var it=scope.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder identity=new StringBuilder("pocketworld.ssh.native-request-hash.v1\n");
        for(String key:keys){Object value=scope.get(key);if(value instanceof JSONObject)value=canonical((JSONObject)value);String text=(value instanceof String?"string:":value instanceof Boolean?"boolean:":"integer:")+value;identity.append(key.length()).append(':').append(key).append(text.length()).append(':').append(text).append('\n');}
        return scope.put("requestHash",SshProfile.hash(identity.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static String canonical(JSONObject value)throws Exception {ArrayList<String> keys=new ArrayList<>();for(var it=value.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder result=new StringBuilder();for(String key:keys){Object field=value.get(key);result.append(JSONObject.quote(key)).append(':').append(field instanceof String?JSONObject.quote((String)field):String.valueOf(field)).append('\n');}return result.toString();}
    private static int integer(JSONObject input,String field,int fallback,int minimum,int maximum)throws Exception{Object value=input.opt(field);int actual=input.optInt(field,fallback);if(value!=null&&(!(value instanceof Number)||((Number)value).doubleValue()!=actual)||actual<minimum||actual>maximum)throw new IllegalArgumentException("原SSH时限参数无效");return actual;}
}
