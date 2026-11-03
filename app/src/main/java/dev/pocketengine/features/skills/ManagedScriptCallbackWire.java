package dev.pocketengine.features.skills;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.runtime.providers.ProtocolJson;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** One bounded private-output request, not a general RPC/tool API or an execution grant. */
final class ManagedScriptCallbackWire {
    static final String REQUEST="pocketworld.skill-private-output-request.v1",REPLY="pocketworld.skill-private-output-reply.v1";
    static final int MAX_REQUEST=4096,MAX_REPLY=16384,MAX_REQUESTS=128;
    private ManagedScriptCallbackWire(){}
    static final class Request {
        final String id,path;
        private Request(String id,String path){this.id=id;this.path=path;}
    }
    static Request request(byte[] bytes,String nonce,String operation)throws Exception {
        if(bytes==null||bytes.length==0||bytes.length>=MAX_REQUEST)throw new SecurityException("script_callback_frame_invalid");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\ufeff")||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new SecurityException("script_callback_frame_invalid");
        JSONObject value=ProtocolJson.parse(text);Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("format","nonce","requestId","operationId","path"))||!REQUEST.equals(value.opt("format"))||!operation.equals(value.opt("operationId")))throw new SecurityException("script_callback_scope_invalid");
        if(!(value.opt("nonce") instanceof String)||!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),value.getString("nonce").getBytes(StandardCharsets.UTF_8)))throw new SecurityException("script_callback_scope_invalid");
        Object id=value.opt("requestId"),path=value.opt("path");if(!(id instanceof String)||!id.equals(UUID.fromString((String)id).toString())||!(path instanceof String)||((String)path).length()>512)throw new SecurityException("script_callback_request_invalid");
        VersionedWorkspace.path((String)path);return new Request((String)id,(String)path);
    }
    static byte[] reply(String id,String operation,JSONObject result)throws Exception {
        JSONObject value=new JSONObject().put("format",REPLY).put("requestId",id).put("operationId",operation).put("result",result);
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value.toString()));
        if(encoded.remaining()+1>MAX_REPLY)throw new SecurityException("script_callback_reply_too_large");byte[] bytes=new byte[encoded.remaining()+1];encoded.get(bytes,0,bytes.length-1);bytes[bytes.length-1]='\n';return bytes;
    }
    static JSONObject rejected()throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("status","rejected").put("code","script_private_output_unavailable").put("replayAllowed",false).put("externalTransmission",false).put("executionAuthorized",false);}
}
