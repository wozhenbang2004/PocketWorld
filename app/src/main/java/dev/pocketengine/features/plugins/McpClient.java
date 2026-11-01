package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independently authored Streamable HTTP subset, explicit 2026-07-28 or 2025-11-25; no scripts or model authority. */
public final class McpClient implements AutoCloseable, dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.Transport {
    private static final int MAX_BYTES=262144,MAX_MESSAGES=128;
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .callTimeout(20,TimeUnit.SECONDS).build();
    public interface AuthorizationCheck {void check(String exactEndpoint)throws Exception;}
    public interface NativeFactory {McpClient create(String endpoint,String token,String version,AuthorizationCheck check)throws Exception;}
    private final String endpoint,token,version;private final AuthorizationCheck authorization;private final OkHttpClient http;
    private final Set<Call> calls=ConcurrentHashMap.newKeySet();
    private volatile String session="";
    private volatile boolean initialized,valid=true,closed;
    private JSONObject serverInfo;
    private volatile boolean taskSupport;

    public McpClient(String endpoint,String token)throws Exception {this(endpoint,token,McpData.VERSION);}
    public McpClient(String endpoint,String token,String version)throws Exception {this(endpoint,token,version,ignored->{},HTTP);}
    public McpClient(String endpoint,String token,String version,AuthorizationCheck authorization)throws Exception{this(endpoint,token,version,authorization,HTTP);}
    /** Native transport seam for an actual TLS fixture; never selected by tool/config JSON. */
    McpClient(String endpoint,String token,String version,AuthorizationCheck authorization,OkHttpClient http)throws Exception {
        PluginRules.validateEndpoint(endpoint);validateToken(token);if(!McpData.supportedVersion(version))throw new IllegalArgumentException("不支持此MCP协议版本");
        this.endpoint=endpoint;this.token=token;this.version=version;this.authorization=java.util.Objects.requireNonNull(authorization);this.http=java.util.Objects.requireNonNull(http);
    }
    private boolean modern(){return McpData.VERSION.equals(version);}
    String version(){return version;}
    String serverDigest()throws Exception{return McpData.digest(serverInfo);}
    static void validateToken(String token){
        if(token.length()>4096)throw new IllegalArgumentException("MCP凭据过长");
        for(int i=0;i<token.length();i++)if(token.charAt(i)<0x21||token.charAt(i)>0x7e)throw new IllegalArgumentException("MCP凭据需为不含空白的ASCII Bearer token");
    }
    boolean usable(){return initialized&&valid&&!closed;}
    boolean needsDiscovery(){return !initialized&&valid&&!closed;}
    public boolean supportsTasks(){return usable()&&modern()&&taskSupport;}
    String endpoint(){return endpoint;}
    JSONObject serverInfo()throws Exception{return McpData.copy(serverInfo);}

    public JSONArray discover(RunControl control)throws Exception {
        if(initialized||closed)throw new IllegalStateException("请使用新的MCP发现会话");
        try {
            JSONObject result;
            if(modern())result=requiredResult(rpc("server/discover",new JSONObject(),control));
            else {
                JSONObject params=new JSONObject().put("protocolVersion",version).put("capabilities",new JSONObject())
                        .put("clientInfo",new JSONObject().put("name","PocketWorld").put("version","1"));
                result=requiredResult(rpc("initialize",params,control));
                if(!version.equals(result.optString("protocolVersion")))throw refresh("MCP握手版本不兼容，请更换明确的协议模式重新发现");
            }
            serverInfo=identity(result);
            if(!modern())send(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),null,control,false,null);
            initialized=true;return tools(control);
        }catch(Exception failure){valid=false;throw failure;}
    }

    JSONArray tools(RunControl control)throws Exception {
        checkUsable();JSONArray all=new JSONArray();Set<String> names=new HashSet<>(),cursors=new HashSet<>();String cursor="";int totalBytes=0;
        for(int page=0;page<8;page++){
            JSONObject params=new JSONObject();if(!cursor.isEmpty())params.put("cursor",cursor);
            JSONObject result=requiredResult(rpc("tools/list",params,control));JSONArray tools=result.getJSONArray("tools");
            if(tools.length()+all.length()>McpData.MAX_TOOLS)throw refresh("MCP工具清单超过96项；请在服务端缩小范围");
            for(int i=0;i<tools.length();i++){
                JSONObject tool=tools.getJSONObject(i);String name=tool.getString("name");
                if(name.isEmpty()||name.length()>256||!names.add(name))throw refresh("MCP工具名称为空、重复或过长");
                // A server must not reflect the owner's credential into persistent declarations or UI.
                if(McpData.containsSecret(tool,token,0))throw refresh("MCP工具定义包含凭据，已拒绝导入");
                totalBytes+=tool.toString().getBytes(StandardCharsets.UTF_8).length;
                if(totalBytes>MAX_BYTES)throw refresh("MCP工具清单总量超过256KiB，请缩小服务端范围");
                all.put(McpData.copy(tool));
            }
            if(!result.has("nextCursor"))return all;
            cursor=result.getString("nextCursor");if(cursor.isEmpty()||cursor.length()>4096||!cursors.add(cursor))throw refresh("MCP分页游标无效或循环");
        }
        throw refresh("MCP工具清单超过8页；请在服务端缩小范围");
    }

    JSONObject call(JSONObject declaration,JSONObject arguments,RunControl control)throws CallFailure {
        return call(declaration,arguments,control,false);
    }
    JSONObject call(JSONObject declaration,JSONObject arguments,RunControl control,McpResultCapture capture)throws CallFailure {
        return call(declaration,arguments,control,false,capture);
    }
    @Override public JSONObject start(JSONObject declaration,JSONObject arguments,RunControl control)throws CallFailure {
        return call(declaration,arguments,control,true);
    }
    private JSONObject call(JSONObject declaration,JSONObject arguments,RunControl control,boolean tasks)throws CallFailure {
        return call(declaration,arguments,control,tasks,null);
    }
    private JSONObject call(JSONObject declaration,JSONObject arguments,RunControl control,boolean tasks,McpResultCapture capture)throws CallFailure {
        boolean dispatched=false;
        try {
            validateDeclaration(declaration,control);
            if(!PluginRules.diagnoseMcpArguments(arguments,declaration.getJSONObject("schema")).getBoolean("valid"))throw new McpException("MCP工具参数未通过当前声明校验");
            if(tasks&&(!modern()||!taskSupport))throw new McpException("所选协议或服务未声明2026-07-28 Tasks扩展；不会静默改用同步调用");
            // A lost response after this boundary is unknown effect; never replay tools/call.
            JSONObject parameters=new JSONObject().put("name",declaration.getString("name")).put("arguments",arguments);
            if(modern())McpHeaders.apply(declaration.getJSONObject("schema"),arguments,new Request.Builder());
            dispatched=true;
            JSONObject response=rpc("tools/call",parameters,control,declaration.getJSONObject("schema"),tasks);
            if(response.has("error"))return new JSONObject().put("isError",true).put("protocolError",safeError(response.getJSONObject("error")))
                    .put("content",new JSONArray()).put("untrusted",true).put("instructionsAreData",true);
            JSONObject result=response.getJSONObject("result");
            if(modern()&&"input_required".equals(result.optString("resultType")))return new JSONObject().put("isError",true).put("resultType","input_required")
                    .put("unsupportedInputRequired",true).put("untrusted",true).put("instructionsAreData",true).put("content",new JSONArray())
                    .put("message","此工具要求未授权或不支持的客户端输入；未执行sampling、elicitation、roots或重发调用");
            if(tasks&&"task".equals(result.optString("resultType")))return taskResult(result,null,true);
            requireComplete(result);return capture==null?McpData.result(result,token):capture.result(result,token);
        }catch(Exception failure){
            if(dispatched)valid=false;
            throw new CallFailure(dispatched,!usable(),failure instanceof McpException?failure.getMessage():
                    (dispatched?"MCP调用未取得有效回执，请核对服务端结果，勿重复提交":"MCP调用前校验失败，工具未发送；请重新发现或检查服务端"));
        }
    }
    private void validateDeclaration(JSONObject declaration,RunControl control)throws Exception {
            checkUsable();control.check();
            if(!endpoint.equals(declaration.getString("endpoint"))||!version.equals(declaration.getString("protocolVersion")))
                throw refresh("MCP安装地址或版本不匹配，请重新发现并安装");
            if(modern()){
                JSONObject fresh=identity(requiredResult(rpc("server/discover",new JSONObject(),control)));
                if(!McpData.digest(fresh).equals(declaration.getString("serverDigest")))throw refresh("MCP服务身份或版本已变化，请重新发现安装");
            }
            if(!serverDigest().equals(declaration.getString("serverDigest")))throw refresh("MCP服务身份已变化，请重新发现安装");
            JSONObject live=McpData.find(tools(control),declaration.getString("name"));
            if(live==null||!McpData.digest(live).equals(declaration.getString("descriptorDigest")))
                throw refresh("MCP工具声明已变化，请重新发现、安装并授权");
            checkUsable();control.check();
    }
    @Override public JSONObject query(JSONObject declaration,String remoteTaskId,RunControl control)throws Exception {
        validateDeclaration(declaration,control);
        if(!modern()||!taskSupport)throw new McpException("Tasks扩展不可用；不会调用旧版tasks/result");
        validateTaskId(remoteTaskId);
        return taskResult(requiredResult(rpc("tasks/get",new JSONObject().put("taskId",remoteTaskId),control,null,true)),remoteTaskId,false);
    }
    @Override public void cancel(JSONObject declaration,String remoteTaskId,RunControl control)throws Exception {
        validateDeclaration(declaration,control);
        if(!modern()||!taskSupport)throw new McpException("Tasks扩展不可用");
        validateTaskId(remoteTaskId);
        requiredResult(rpc("tasks/cancel",new JSONObject().put("taskId",remoteTaskId),control,null,true));
    }
    private JSONObject taskResult(JSONObject source,String expected,boolean creation)throws Exception {
        if(!(creation?"task":"complete").equals(source.optString("resultType")))throw refresh("MCP Tasks结果类型不匹配");
        String id=source.getString("taskId"),status=source.getString("status");
        if(!(source.get("taskId") instanceof String)||!(source.get("status") instanceof String)||!(source.get("createdAt") instanceof String)||!(source.get("lastUpdatedAt") instanceof String))throw refresh("MCP Tasks字段类型无效");
        validateTaskId(id);
        if(McpData.containsSecret(id,token,0)||expected!=null&&!expected.equals(id))
            throw refresh("MCP Tasks身份不匹配");
        if(!java.util.Arrays.asList("working","input_required","completed","failed","cancelled").contains(status))throw refresh("MCP Tasks状态无效");
        String created=source.getString("createdAt"),updated=source.getString("lastUpdatedAt");
        if(java.time.Instant.parse(updated).isBefore(java.time.Instant.parse(created)))throw refresh("MCP Tasks时间无效");
        if(!source.has("ttlMs"))throw refresh("MCP Tasks缺少ttlMs");
        dev.pocketengine.features.plugins.mcp.McpJson.interval(source.get("ttlMs"),0);
        if(source.has("pollIntervalMs")){
            if(!(source.get("pollIntervalMs") instanceof Number))throw refresh("MCP Tasks轮询间隔无效");
            dev.pocketengine.features.plugins.mcp.McpJson.interval(source.get("pollIntervalMs"),0);
        }
        JSONObject result=new JSONObject().put("resultType",creation?"task":"complete").put("taskId",id).put("status",status)
                .put("createdAt",created).put("lastUpdatedAt",updated).put("ttlMs",source.get("ttlMs")).put("untrusted",true).put("instructionsAreData",true);
        if(source.has("pollIntervalMs"))result.put("pollIntervalMs",source.get("pollIntervalMs"));
        if(!creation&&status.equals("completed")){
            JSONObject completed=source.getJSONObject("result");requireComplete(completed);result.put("result",McpData.result(completed,token));
        }
        if(!creation&&status.equals("failed"))result.put("error",safeError(source.getJSONObject("error")));
        if(status.equals("input_required"))result.put("unsupportedInputRequired",true); // Never return or execute inputRequests.
        return result;
    }
    private static void validateTaskId(String id){
        if(id==null||id.isEmpty()||id.length()>4096||id.chars().anyMatch(c->c<32||c==127)||McpHeaders.encode(id).length()>8192)throw new IllegalArgumentException("MCP Tasks身份无效");
    }
    public static final class CallFailure extends Exception implements dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.DispatchFailure {
        public final boolean dispatched,refreshRequired;
        CallFailure(boolean dispatched,boolean refreshRequired,String message){super(message);this.dispatched=dispatched;this.refreshRequired=refreshRequired;}
        @Override public boolean mayHaveDispatched(){return dispatched;}
    }
    private static final class McpException extends IOException {McpException(String message){super(message);}}
    private McpException refresh(String message){valid=false;return new McpException(message);}
    private void checkUsable()throws Exception {if(!usable())throw refresh("MCP会话已失效，请重新发现、安装并授权；不会沿用旧工具描述");}
    private JSONObject requiredResult(JSONObject envelope)throws Exception {
        if(envelope.has("error"))throw new McpException("MCP服务返回JSON-RPC错误（"+envelope.getJSONObject("error").getInt("code")+"）");
        JSONObject result=envelope.getJSONObject("result");requireComplete(result);return result;
    }
    private JSONObject safeError(JSONObject error)throws Exception {
        java.math.BigDecimal code=PluginRules.decimal(error.opt("code"));
        if(code==null||!PluginRules.integer(code)||code.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE))<0||code.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE))>0)throw refresh("MCP错误代码无效");
        String message=McpData.redact(error.getString("message"),token);if(message.length()>4096)message=message.substring(0,4096);
        return new JSONObject().put("code",error.getInt("code")).put("message",message).put("dataOmitted",error.has("data"));
    }
    private void requireComplete(JSONObject result)throws Exception {
        if(modern()&&!"complete".equals(result.optString("resultType")))throw refresh("MCP未返回complete结果；当前不支持多轮输入或扩展任务");
    }
    private JSONObject identity(JSONObject result)throws Exception {
        JSONObject extensions=result.getJSONObject("capabilities").optJSONObject("extensions");
        taskSupport=modern()&&extensions!=null&&extensions.optJSONObject(dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.EXTENSION)!=null;
        if(result.getJSONObject("capabilities").optJSONObject("tools")==null)throw refresh("MCP服务未声明tools能力");
        if(modern()){
            boolean supported=false;JSONArray versions=result.getJSONArray("supportedVersions");
            for(int i=0;i<versions.length();i++)if(version.equals(versions.getString(i)))supported=true;
            if(!supported)throw refresh("MCP服务不支持所选协议版本，请重新发现");
        }
        JSONObject info=modern()?result.getJSONObject("_meta").getJSONObject("io.modelcontextprotocol/serverInfo"):result.getJSONObject("serverInfo");
        String name=info.getString("name"),serverVersion=info.getString("version");
        if(name.length()>200||serverVersion.length()>200)throw refresh("MCP服务身份过长");
        return new JSONObject().put("name",McpData.redact(name,token)).put("version",McpData.redact(serverVersion,token));
    }
    private JSONObject rpc(String method,JSONObject params,RunControl control)throws Exception {return rpc(method,params,control,null);}
    private JSONObject rpc(String method,JSONObject params,RunControl control,JSONObject schema)throws Exception {return rpc(method,params,control,schema,false);}
    private JSONObject rpc(String method,JSONObject params,RunControl control,JSONObject schema,boolean tasks)throws Exception {
        JSONObject capabilities=new JSONObject();
        if(tasks)capabilities.put("extensions",new JSONObject().put(dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.EXTENSION,new JSONObject()));
        String id=UUID.randomUUID().toString();
        if(modern())params.put("_meta",new JSONObject().put("io.modelcontextprotocol/protocolVersion",version)
                .put("io.modelcontextprotocol/clientInfo",new JSONObject().put("name","PocketWorld").put("version","1"))
                .put("io.modelcontextprotocol/clientCapabilities",capabilities));
        return send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params),id,control,method.equals("initialize"),schema);
    }
    private Request.Builder request()throws Exception{
        authorization.check(endpoint);
        Request.Builder builder=new Request.Builder().url(endpoint).header("Accept","application/json, text/event-stream")
                .header("MCP-Protocol-Version",version).header("Cache-Control","no-cache");
        if(!token.isEmpty())builder.header("Authorization","Bearer "+token);
        if(!session.isEmpty())builder.header("MCP-Session-Id",session);return builder;
    }
    private JSONObject send(JSONObject message,String id,RunControl control,boolean initializing,JSONObject schema)throws Exception {
        control.check();if(closed||!valid)throw new McpException("MCP会话不可用，请重新发现");
        Request.Builder builder=request();
        if(modern()){
            builder.header("Mcp-Method",message.getString("method"));
            if("tools/call".equals(message.getString("method"))){
                JSONObject params=message.getJSONObject("params");builder.header("Mcp-Name",McpHeaders.encode(params.getString("name")));
                McpHeaders.apply(schema,params.getJSONObject("arguments"),builder);
            }else if(message.getString("method").startsWith("tasks/"))builder.header("Mcp-Name",McpHeaders.encode(message.getJSONObject("params").getString("taskId")));
        }
        Call call=http.newCall(builder.post(oneShot(message.toString())).build());
        AtomicBoolean started=new AtomicBoolean(),finished=new AtomicBoolean();
        Runnable cancel=()->{
            // Snapshot before cancelling: execute() can wake and mark finished immediately.
            boolean notify=!modern()&&id!=null&&!initializing&&started.get()&&!finished.get();
            call.cancel();
            if(notify)cancelNotification(id);
        };
        calls.add(call);control.onCancel(cancel);
        try {
            control.check();started.set(true);
            try(Response response=call.execute()){
                control.check();
                if(!modern()&&(response.code()==404||response.code()==400)&&!session.isEmpty())throw refresh("MCP会话已过期，请重新发现；本次工具不会自动重放");
                if(response.code()==401||response.code()==403)throw refresh("MCP凭据或访问权限已失效，请更新配置并重新发现");
                if(response.code()<200||response.code()>=300){
                    if(modern()&&id!=null&&(response.code()==400||response.code()==404)&&response.body()!=null&&
                            response.header("Content-Type","").split(";",2)[0].trim().equalsIgnoreCase("application/json")){
                        JSONObject error=accept(parse(read(response.body().byteStream(),MAX_BYTES,control)),id,control);
                        if(error!=null&&error.has("error")){
                            int code=error.getJSONObject("error").getInt("code");
                            if(code==-32022||code==-32020)valid=false;
                            return error;
                        }
                    }
                    throw new McpException("MCP HTTP状态"+response.code()+"；未重试，未跟随重定向");
                }
                headers(response,initializing);
                if(id==null){if(response.code()!=202)throw new McpException("MCP通知或响应应返回202");return null;}
                if(response.body()==null)throw new McpException("MCP回复为空");
                String type=response.header("Content-Type","").split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                JSONObject result;
                if(type.equals("application/json")){
                    result=accept(parse(read(response.body().byteStream(),MAX_BYTES,control)),id,control);
                    if(result==null)throw new McpException("MCP JSON回复缺少匹配的请求结果");
                }else if(type.equals("text/event-stream"))result=sse(response.body().byteStream(),id,control);
                else throw new McpException("MCP回复类型不支持");
                control.check();return result;
            }
        }finally{finished.set(true);control.removeCancelAction(cancel);calls.remove(call);}
    }
    private void headers(Response response,boolean initializing)throws Exception {
        if(response.headers("MCP-Protocol-Version").size()>1||response.headers("MCP-Session-Id").size()>1)throw refresh("MCP重复会话或协议标头");
        String version=response.header("MCP-Protocol-Version");
        if(version!=null&&!this.version.equals(version))throw refresh("MCP协议版本发生变化，请重新发现");
        String next=response.header("MCP-Session-Id");
        if(next!=null){
            if(modern())throw refresh("当前MCP无会话模式收到旧会话标头，请检查协议配置");
            if(next.isEmpty()||next.length()>4096)throw refresh("MCP会话标识无效");
            for(int i=0;i<next.length();i++)if(next.charAt(i)<0x21||next.charAt(i)>0x7e)throw refresh("MCP会话标识无效");
            if(initializing)session=next;else if(!next.equals(session))throw refresh("MCP会话标识发生变化，请重新发现");
        }
    }
    private JSONObject accept(JSONObject envelope,String expected,RunControl control)throws Exception {
        if(!"2.0".equals(envelope.optString("jsonrpc")))throw refresh("MCP JSON-RPC版本无效");
        if(envelope.has("method")){
            if(envelope.has("result")||envelope.has("error")||!(envelope.get("method") instanceof String))throw refresh("MCP请求格式无效");
            String method=envelope.getString("method");
            if(envelope.has("id")){
                if(modern())throw refresh("当前MCP协议禁止独立服务端请求；客户端能力未授权");
                Object requestId=envelope.get("id");if(!(requestId instanceof String)&&!(requestId instanceof Number))throw refresh("MCP服务请求ID无效");
                JSONObject reply=new JSONObject().put("jsonrpc","2.0").put("id",requestId);
                if(method.equals("ping"))reply.put("result",new JSONObject());
                else reply.put("error",new JSONObject().put("code",-32601).put("message","Client capability not supported"));
                send(reply,null,control,false,null);
            }else if(method.equals("notifications/tools/list_changed"))throw refresh("MCP工具清单已更新，请重新发现并授权");
            // Logging, progress and unknown notifications carry no client authority and are not injected into prompts.
            return null;
        }
        if(!(envelope.opt("id") instanceof String)||!expected.equals(envelope.getString("id")))throw refresh("MCP回复ID与请求不符");
        if(envelope.has("result")==envelope.has("error"))throw refresh("MCP回复需且仅能包含result或error");
        if(envelope.has("error")){
            JSONObject error=envelope.getJSONObject("error");java.math.BigDecimal code=PluginRules.decimal(error.opt("code"));
            if(code==null||!PluginRules.integer(code)||code.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE))<0||code.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE))>0||!(error.opt("message") instanceof String))throw refresh("MCP错误格式无效");
        }else envelope.getJSONObject("result");
        return envelope;
    }
    private JSONObject sse(InputStream input,String expected,RunControl control)throws Exception {
        PushbackInputStream in=new PushbackInputStream(input,1);StringBuilder data=new StringBuilder();String event="";
        int[] budget={0};int messages=0;boolean first=true;
        while(true){
            String line=line(in,budget,control);if(line==null)throw new McpException("MCP SSE在匹配结果前断开；未自动恢复或重发");
            if(first){first=false;if(line.startsWith("\ufeff"))line=line.substring(1);}
            if(line.isEmpty()){
                if(data.length()>0){
                    if(++messages>MAX_MESSAGES)throw new McpException("MCP SSE事件过多");
                    if(event.isEmpty()||event.equals("message")){
                        JSONObject value=accept(parse(data.toString()),expected,control);if(value!=null)return value;
                    }
                }
                data.setLength(0);event="";continue;
            }
            if(line.startsWith(":"))continue;
            int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon),value=colon<0?"":line.substring(colon+1);
            if(value.startsWith(" "))value=value.substring(1);
            if(field.equals("data")){if(data.length()>0)data.append('\n');data.append(value);}
            else if(field.equals("event"))event=value;
            // id/retry are not used: this bounded adapter deliberately does not resume streams.
        }
    }
    private static String line(PushbackInputStream in,int[] budget,RunControl control)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(true){
            control.check();int b=in.read();if(b<0)return out.size()==0?null:decode(out.toByteArray());
            if(++budget[0]>MAX_BYTES)throw new McpException("MCP SSE回复超过256KiB");
            if(b=='\n')return decode(out.toByteArray());
            if(b=='\r'){int next=in.read();if(next>=0&&next!='\n')in.unread(next);else if(next=='\n'&&++budget[0]>MAX_BYTES)throw new McpException("MCP SSE回复超过256KiB");return decode(out.toByteArray());}
            if(out.size()>=65536)throw new McpException("MCP SSE单行超过64KiB");out.write(b);
        }
    }
    private static String read(InputStream input,int limit,RunControl control)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1){control.check();if(n>limit-out.size())throw new McpException("MCP回复超过256KiB");out.write(buffer,0,n);}return decode(out.toByteArray());
    }
    private static String decode(byte[] bytes)throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static JSONObject parse(String text)throws Exception {
        return dev.pocketengine.features.plugins.mcp.McpJson.parse(text);
    }
    static String diagnostic(Exception failure){
        return failure instanceof McpException?failure.getMessage():"网络或服务数据无效，请检查地址、协议模式和凭据；未导入";
    }
    private static RequestBody oneShot(String text){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        return new RequestBody(){
            @Override public MediaType contentType(){return JSON;}
            @Override public long contentLength(){return bytes.length;}
            @Override public boolean isOneShot(){return true;}
            @Override public void writeTo(okio.BufferedSink sink)throws IOException{sink.write(bytes);}
        };
    }
    private void cancelNotification(String id){
        try{JSONObject notification=new JSONObject().put("jsonrpc","2.0").put("method","notifications/cancelled")
                    .put("params",new JSONObject().put("requestId",id).put("reason","Owner cancelled"));
            auxiliary(request().post(oneShot(notification.toString())).build());
        }catch(Exception ignored){/* Best effort; stopping local I/O does not imply remote rollback. */}
    }
    private void auxiliary(Request request){
        Call call=http.newCall(request);call.timeout().timeout(2,TimeUnit.SECONDS);
        call.enqueue(new Callback(){@Override public void onFailure(Call call,IOException error){}@Override public void onResponse(Call call,Response response){response.close();}});
    }
    @Override public void close(){
        if(closed)return;closed=true;valid=false;for(Call call:calls)call.cancel();
        if(!session.isEmpty())try{auxiliary(request().delete().build());}catch(Exception revoked){/* Auth logout closes local I/O; it cannot revive token authority even for session cleanup. */}
    }
}
