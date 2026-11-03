package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.mcp.McpJson;
import dev.pocketengine.features.plugins.mcp.McpStdioFrames;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stateful MCP stdio protocol over an already admitted original native pipe, never a launcher.
 * HTTP's stateless server/discover is deliberately not reused as a stdio handshake. */
public final class McpStdioClient implements AutoCloseable {
    public static final String VERSION="2025-11-25";
    public static final int MAX_RPC_MILLIS=20000,MAX_RPC_MESSAGES=128;
    /** Native glue must use the retained opaque original pipe/control, not reconstruct an FD/job
     * from JSON. read returns only strict stdout frames; stderr is original diagnostic data. */
    public interface Channel {
        JSONObject write(String inputOperationId,byte[] completeFrame,RunControl original,long waitMillis)throws Exception;
        JSONObject read(RunControl original,long waitMillis)throws Exception;
        void invalidate();
    }
    private final Channel channel;private final RunControl original;private final LongSupplier clock;private final long rpcMillis,initializationMillis;
    private final AtomicBoolean channelInvalidated=new AtomicBoolean();private final Runnable cancellation=this::close;
    private volatile boolean valid=true,initialized,closed,initializationAttempted;private JSONObject serverIdentity;
    public McpStdioClient(Channel channel,RunControl original){this(channel,original,System::nanoTime,MAX_RPC_MILLIS);}
    McpStdioClient(Channel channel,RunControl original,LongSupplier clock,long rpcMillis){this(channel,original,clock,rpcMillis,rpcMillis);}
    private McpStdioClient(Channel channel,RunControl original,LongSupplier clock,long rpcMillis,long initializationMillis){this.channel=Objects.requireNonNull(channel);this.original=Objects.requireNonNull(original);this.clock=Objects.requireNonNull(clock);if(rpcMillis<1||rpcMillis>MAX_RPC_MILLIS||initializationMillis<1||initializationMillis>McpLocalPackage.INITIALIZATION_MILLIS)throw new IllegalArgumentException("Invalid MCP RPC budget");this.rpcMillis=rpcMillis;this.initializationMillis=initializationMillis;original.onCancel(cancellation);}
    static McpStdioClient forOriginalProgram(Channel channel,RunControl original,JSONObject originalScope)throws Exception{boolean preparation=originalScope.has(McpLocalPackage.PLAN);return new McpStdioClient(channel,original,System::nanoTime,MAX_RPC_MILLIS,preparation?Math.min(McpLocalPackage.INITIALIZATION_MILLIS,originalScope.getLong("timeoutMillis")):MAX_RPC_MILLIS);}
    public boolean usable(){return initialized&&valid&&!closed;}
    public synchronized JSONObject serverIdentity()throws Exception{if(!usable())throw new IllegalStateException("MCP stdio session is not initialized");return McpData.copy(serverIdentity);}
    public synchronized String serverDigest()throws Exception{return McpJson.digest(serverIdentity());}
    public synchronized JSONArray discover()throws Exception {
        if(initializationAttempted)throw new IllegalStateException("MCP stdio initialization cannot be replayed");initializationAttempted=true;
        try{JSONObject result=rpc("initialize",new JSONObject().put("protocolVersion",VERSION).put("capabilities",new JSONObject()).put("clientInfo",new JSONObject().put("name","PocketWorld").put("version","1")));
        if(!(result.opt("protocolVersion") instanceof String)||!VERSION.equals(result.getString("protocolVersion")))throw invalidate("MCP stdio negotiated an unsupported protocol version; no automatic downgrade");
        JSONObject capabilities=result.getJSONObject("capabilities"),info=result.getJSONObject("serverInfo");
        if(!(capabilities.opt("tools") instanceof JSONObject))throw invalidate("MCP stdio server does not declare tools");
        String name=identityText(info,"name"),version=identityText(info,"version");
        serverIdentity=new JSONObject().put("transport","stdio").put("protocolVersion",result.getString("protocolVersion")).put("serverInfo",new JSONObject().put("name",name).put("version",version)).put("capabilities",McpData.copy(capabilities));
        // Server instructions are never injected into the conversation or into this client's capabilities.
        notifyInitialized();initialized=true;return tools();
        }catch(Exception failure){valid=false;invalidateChannel();throw failure;}
    }
    public synchronized JSONArray tools()throws Exception {
        if(!usable())throw new IllegalStateException("MCP stdio session is not initialized");
        try{JSONArray found=new JSONArray();Set<String> names=new HashSet<>(),cursors=new HashSet<>();String cursor=null;int bytes=0;
        for(int page=0;page<8;page++){
            JSONObject params=new JSONObject();if(cursor!=null)params.put("cursor",cursor);JSONObject result=rpc("tools/list",params);JSONArray tools=result.getJSONArray("tools");
            bytes+=result.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes>262144)throw invalidate("MCP stdio tool catalogue exceeds 256KiB");
            for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);String name=McpPromptData.name(tool,"name",256);if(!names.add(name)||found.length()>=McpData.MAX_TOOLS)throw invalidate("MCP stdio tool catalogue is duplicated or exceeds 96 tools");if(!(tool.opt("inputSchema") instanceof JSONObject))throw invalidate("MCP stdio tool schema is missing");found.put(McpData.copy(tool));}
            if(!result.has("nextCursor"))return found;Object next=result.get("nextCursor");if(!(next instanceof String)||((String)next).length()>4096||!cursors.add((String)next))throw invalidate("MCP stdio tool cursor is invalid");cursor=(String)next;
        }
        throw invalidate("MCP stdio tool catalogue exceeds 8 pages");
        }catch(Exception failure){valid=false;invalidateChannel();throw failure;}
    }
    public synchronized JSONObject call(JSONObject reviewedTool,JSONObject supplied)throws Exception{return call(reviewedTool,supplied,null);}
    synchronized JSONObject call(JSONObject reviewedTool,JSONObject supplied,McpClient.ResultCapture capture)throws Exception {
        boolean resultReceived=false;try {
            if(!usable()||!serverDigest().equals(reviewedTool.getString("serverDigest")))throw new IllegalStateException("MCP stdio server identity changed");
            JSONObject arguments=PluginArguments.snapshot(supplied),actual=McpData.find(tools(),reviewedTool.getString("name"));
            if(actual==null||!McpJson.digest(actual).equals(reviewedTool.getString("descriptorDigest")))throw new IllegalStateException("MCP stdio tool declaration changed; a new review is required");
            JSONObject diagnostics=PluginRules.diagnoseMcpArguments(arguments,actual.getJSONObject("inputSchema"));if(!diagnostics.getBoolean("valid"))throw new IllegalArgumentException("MCP stdio arguments do not match the current reviewed schema");
            JSONObject result=rpc("tools/call",new JSONObject().put("name",actual.getString("name")).put("arguments",arguments));resultReceived=true;return capture==null?McpData.result(result,""):capture.result(result,"");
        }catch(RpcFailure failure){throw new McpClient.CallFailure(failure.toolMayHaveDispatched,!valid,failure.getMessage());}
        catch(dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable blocked){throw blocked;}
        catch(Exception failure){throw new McpClient.CallFailure(resultReceived,!valid,resultReceived?"MCP stdio returned a tool result but native projection/capture failed; never replay":"MCP stdio tool was not sent; check the original installation, declaration and arguments");}
    }
    private static String identityText(JSONObject object,String field)throws Exception{Object value=object.get(field);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>200)throw new IllegalArgumentException("MCP stdio server identity is invalid");StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap((String)value));return (String)value;}
    private void current()throws Exception{if(!valid||closed)throw new IllegalStateException("MCP stdio session is invalid; it will not reconnect or replay");original.check();if(original.isPaused())throw new SecurityException("MCP original run is paused");}
    private long remaining(long until)throws Exception{current();long left=until-clock.getAsLong();if(left<=0)throw new IllegalStateException("MCP stdio RPC exceeded its original time budget");return Math.max(1,Math.min(McpLocalPackage.INITIALIZATION_MILLIS,(left+999999)/1000000));}
    private void notifyInitialized()throws Exception{
        long until=clock.getAsLong()+rpcMillis*1000000;write(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),until);
    }
    private void write(JSONObject envelope,long until)throws Exception {
        // Package initialization may await its reply longer; the original stdin ACK ticket still has a 20-second ceiling.
        byte[] frame=McpStdioFrames.encode(envelope);current();JSONObject receipt=channel.write(UUID.randomUUID().toString(),frame,original,Math.min(MAX_RPC_MILLIS,remaining(until)));
        if(receipt==null)throw new InputFailure(true);int written=receiptCount(receipt,"writtenBytes"),requested=receiptCount(receipt,"requestedBytes");if(requested!=frame.length||written<0||written>requested)throw new InputFailure(true);
        boolean noEffects=Boolean.FALSE.equals(receipt.opt("effectMayHaveOccurred"))&&written==0;
        if("not_dispatched".equals(receipt.optString("status"))&&noEffects)throw new InputFailure(false);
        if(!"delivered".equals(receipt.optString("status"))||!Boolean.TRUE.equals(receipt.opt("nativeAcknowledged"))||!Boolean.TRUE.equals(receipt.opt("effectMayHaveOccurred"))||written!=frame.length)throw new InputFailure(true);
        current();
    }
    private static int receiptCount(JSONObject receipt,String field)throws Exception{Object value=receipt.opt(field);if(!(value instanceof Number))throw new InputFailure(true);try{return new java.math.BigDecimal(value.toString()).intValueExact();}catch(NumberFormatException|ArithmeticException invalid){throw new InputFailure(true);}}
    private JSONObject rpc(String method,JSONObject params)throws Exception {
        boolean toolMethod="tools/call".equals(method),entered=false,mainAcknowledged=false;long until=clock.getAsLong()+("initialize".equals(method)?initializationMillis:rpcMillis)*1000000;String id=UUID.randomUUID().toString();
        try {
            current();JSONObject request=new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params);
            // Encoding is a definite pre-write check; after entering write, missing ACK/reply is unknown.
            McpStdioFrames.encode(request);remaining(until);entered=true;write(request,until);mainAcknowledged=true;
            for(int count=0;count<MAX_RPC_MESSAGES;count++){
                JSONObject envelope=channel.read(original,remaining(until));current();if(envelope==null)throw new IllegalStateException("MCP stdio ended without the matching reply");
                if(!"2.0".equals(envelope.opt("jsonrpc")))throw new IllegalArgumentException("MCP stdio response version is invalid");
                if(envelope.has("method")){
                    String peerMethod=envelope.getString("method");if(envelope.has("result")||envelope.has("error"))throw new IllegalArgumentException("MCP stdio server request is invalid");
                    if(envelope.has("id")){JSONObject reply=new JSONObject().put("jsonrpc","2.0").put("id",envelope.get("id"));if("ping".equals(peerMethod))reply.put("result",new JSONObject());else reply.put("error",new JSONObject().put("code",-32601).put("message","Client capability not supported"));write(reply,until);}
                    else if("notifications/tools/list_changed".equals(peerMethod))throw new IllegalStateException("MCP stdio tool catalogue changed; a new owner review is required");
                    // Unknown logging/progress/notification payloads grant no client authority.
                    continue;
                }
                if(!(envelope.opt("id") instanceof String)||!id.equals(envelope.getString("id"))||envelope.has("result")==envelope.has("error"))throw new IllegalArgumentException("MCP stdio reply does not match the original request");
                if(envelope.has("error"))throw new IllegalStateException("MCP stdio server returned a protocol error; request was not replayed");
                JSONObject result=envelope.getJSONObject("result");remaining(until);return result;
            }
            throw new IllegalStateException("MCP stdio RPC exceeded 128 messages");
        }catch(Exception failure){if(!mainAcknowledged&&failure instanceof InputFailure&&!((InputFailure)failure).mayHaveDispatched)entered=false;valid=false;invalidateChannel();throw new RpcFailure(toolMethod&&entered,"MCP stdio protocol/write/reply failed; no reconnect or automatic replay",failure);}
    }
    private static final class InputFailure extends Exception {final boolean mayHaveDispatched;InputFailure(boolean value){mayHaveDispatched=value;}}
    private static final class RpcFailure extends Exception {final boolean toolMayHaveDispatched;RpcFailure(boolean value,String message,Throwable cause){super(message,cause);toolMayHaveDispatched=value;}}
    private void invalidateChannel(){original.removeCancelAction(cancellation);if(channelInvalidated.compareAndSet(false,true))channel.invalidate();}
    private IllegalStateException invalidate(String message){valid=false;invalidateChannel();return new IllegalStateException(message);}
    @Override public void close(){closed=true;valid=false;invalidateChannel();}
}
