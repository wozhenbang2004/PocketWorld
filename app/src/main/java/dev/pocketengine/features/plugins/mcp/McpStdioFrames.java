package dev.pocketengine.features.plugins.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** MCP's UTF-8/LF wire framing only: no process, transport grant, retries or protocol negotiation.
 * Feed stdout exclusively. The original process owner must retain stderr as diagnostics, apply
 * its original control/current gate, and enforce each RPC's time/message budget separately.
 * A malformed or interrupted delivery poisons this reader; it cannot skip bytes and resynchronize. */
public final class McpStdioFrames {
    public static final int MAX_OUTBOUND_BYTES=131072,MAX_FRAME_BYTES=262144,MAX_FRAMES_PER_DELIVERY=128;
    private McpStdioFrames() {}
    @FunctionalInterface public interface Sink {void accept(JSONObject envelope)throws Exception;}
    public static final class FrameException extends Exception {
        private FrameException(String message){super(message);}
    }
    /** One complete JSON-RPC object plus LF is one original pipe-write ticket. Never split/replay. */
    public static byte[] encode(JSONObject message)throws Exception {
        if(message==null)throw new FrameException("MCP stdio message is missing");
        validate(message);String serialized=message.toString();
        if(serialized==null||serialized.indexOf('\n')>=0||serialized.indexOf('\r')>=0)throw new FrameException("MCP stdio message contains a wire newline");
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(serialized));
        if(encoded.remaining()>=MAX_OUTBOUND_BYTES)throw new FrameException("MCP stdio outbound message exceeds 128KiB including LF");
        byte[] bytes=new byte[encoded.remaining()+1];encoded.get(bytes,0,bytes.length-1);bytes[bytes.length-1]='\n';return bytes;
    }
    public static final class Decoder {
        private final ByteArrayOutputStream pending=new ByteArrayOutputStream(4096);
        private boolean failed,closed,delivering;
        /** Each output callback may contain partial UTF-8, several frames, or a partial final frame.
         * The sink receives a frame once only; a sink failure never makes that frame replayable. */
        public synchronized int acceptStdout(byte[] bytes,int offset,int length,Sink sink)throws Exception {
            if(failed||closed||delivering)throw new FrameException("MCP stdio reader is not available");
            if(bytes==null||sink==null||offset<0||length<0||offset>bytes.length-length)throw new IllegalArgumentException("Invalid MCP stdout slice");
            delivering=true;boolean completed=false;int delivered=0;
            try {
                for(int i=offset;i<offset+length;i++){
                    int value=bytes[i]&255;
                    if(value=='\n'){
                        if(++delivered>MAX_FRAMES_PER_DELIVERY)throw new FrameException("MCP stdio delivery exceeds 128 frames");
                        byte[] frame=pending.toByteArray();pending.reset();int size=frame.length;
                        // Accept CRLF as a delimiter, never an unescaped CR inside protocol JSON.
                        if(size>0&&frame[size-1]=='\r')size--;
                        if(size==0)throw new FrameException("MCP stdio stdout contains an empty non-protocol line");
                        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame,0,size)).toString();
                        if(text.startsWith("\ufeff")||text.indexOf('\r')>=0)throw new FrameException("MCP stdio stdout contains a BOM or embedded newline");
                        JSONObject message=McpJson.parse(text);validate(message);sink.accept(message);
                    }else{
                        if(pending.size()>=MAX_FRAME_BYTES)throw new FrameException("MCP stdio stdout frame exceeds 256KiB");
                        pending.write(value);
                    }
                }
                completed=true;return delivered;
            }finally{delivering=false;if(!completed){failed=true;pending.reset();}}
        }
        /** EOF without LF never manufactures a successful final reply. */
        public synchronized void endOfStream()throws Exception {
            if(failed||closed||delivering)throw new FrameException("MCP stdio reader is not available");
            closed=true;if(pending.size()!=0){failed=true;pending.reset();throw new FrameException("MCP stdio stdout ended during an incomplete frame");}
        }
    }
    private static void validate(JSONObject message)throws Exception {
        unicode(message,0);
        if(!"2.0".equals(message.opt("jsonrpc")))throw new FrameException("MCP stdio JSON-RPC version is invalid");
        if(message.has("method")){
            Object method=message.get("method");if(!(method instanceof String)||((String)method).isEmpty()||((String)method).length()>256||((String)method).chars().anyMatch(Character::isISOControl))throw new FrameException("MCP stdio method is invalid");
            if(message.has("result")||message.has("error")||message.has("params")&&!(message.opt("params") instanceof JSONObject))throw new FrameException("MCP stdio request shape is invalid");
            if(message.has("id"))id(message.get("id"));
        }else{
            if(!message.has("id")||message.has("result")==message.has("error")||message.has("params"))throw new FrameException("MCP stdio response shape is invalid");id(message.get("id"));
            if(message.has("result")){if(!(message.opt("result") instanceof JSONObject))throw new FrameException("MCP stdio result must be an object");}
            else{JSONObject error=message.getJSONObject("error");Object code=error.get("code");if(!(code instanceof Number)||!(error.opt("message") instanceof String))throw new FrameException("MCP stdio error shape is invalid");try{new java.math.BigDecimal(code.toString()).intValueExact();}catch(NumberFormatException|ArithmeticException invalid){throw new FrameException("MCP stdio error code is invalid");}}
        }
    }
    private static void id(Object value)throws Exception {
        if(value instanceof String){if(((String)value).length()>256)throw new FrameException("MCP stdio request ID is too long");return;}
        if(value instanceof Number){try{java.math.BigDecimal number=new java.math.BigDecimal(value.toString());if(number.precision()<=256&&number.scale()>=-256&&number.stripTrailingZeros().scale()<=0)return;}catch(NumberFormatException|ArithmeticException invalid){/* not an integer */}}
        throw new FrameException("MCP stdio request ID is invalid");
    }
    private static void unicode(Object value,int depth)throws Exception {
        if(depth>32)throw new FrameException("MCP stdio JSON nesting exceeds 32");
        if(value instanceof String)StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap((String)value));
        else if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();unicode(key,depth+1);unicode(object.get(key),depth+1);}}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)unicode(array.get(i),depth+1);}
    }
}
