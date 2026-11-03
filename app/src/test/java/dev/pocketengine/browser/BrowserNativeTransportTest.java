package dev.pocketengine.browser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production wire parser and native nonce predicates. Android LocalSocket/provider is not emulated. */
public final class BrowserNativeTransportTest {
    private static byte[] frame(int first,String text)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] body=text.getBytes(StandardCharsets.UTF_8);out.write(first);out.write(body.length);out.write(body);return out.toByteArray();}
    @Test public void fragmentedUtf8MessagesAndInterleavedPingUseActualWireParser()throws Exception {
        ByteArrayOutputStream wire=new ByteArrayOutputStream();wire.write(frame(1,"{\"text\":\""));wire.write(frame(137,"ping"));wire.write(frame(128,"中文\"}"));List<String> controls=new ArrayList<>();
        assertEquals("{\"text\":\"中文\"}",BrowserDevToolsConnection.readMessage(new ByteArrayInputStream(wire.toByteArray()),(op,b)->controls.add(op+":"+new String(b,StandardCharsets.UTF_8))));assertEquals(List.of("10:ping"),controls);
    }
    @Test public void maskedServerFramesBinaryUnexpectedContinuationAndUnboundedLengthFailClosed()throws Exception {
        for(byte[] wire:new byte[][]{new byte[]{(byte)129,(byte)128,0,0,0,0},new byte[]{(byte)130,0},new byte[]{(byte)128,0},new byte[]{(byte)193,0},new byte[]{(byte)137,126,0,126},new byte[]{(byte)129,127,0,0,0,0,0,4,0,1},new byte[]{(byte)129,2,(byte)0xc0,(byte)0xaf}})assertThrows(IOException.class,()->BrowserDevToolsConnection.readMessage(new ByteArrayInputStream(wire),(op,b)->{}));
    }
    @Test public void outgoingClientFramesMaskEveryPayloadAndRespectLengths()throws Exception {
        for(int size:new int[]{0,1,125,126,70000}){byte[] value=new byte[size];for(int i=0;i<size;i++)value[i]=(byte)(i%255);ByteArrayOutputStream wire=new ByteArrayOutputStream();BrowserDevToolsConnection.writeFrame(wire,1,value);ByteArrayInputStream in=new ByteArrayInputStream(wire.toByteArray());assertEquals(129,in.read());int n=in.read();assertTrue((n&128)!=0);long count=n&127;if(count==126)count=in.read()*256L+in.read();else if(count==127){count=0;for(int i=0;i<8;i++)count=count*256+in.read();}assertEquals(size,count);byte[] mask=in.readNBytes(4);for(int i=0;i<size;i++)assertEquals(value[i]&255,(in.read()^(mask[i&3]&255))&255);assertEquals(-1,in.read());}
    }
    @Test public void ignoredProtocolEventsAlsoConsumeWireMessageAndByteBudgets()throws Exception {
        BrowserDevToolsConnection.WireBudget messages=new BrowserDevToolsConnection.WireBudget();for(int i=0;i<8192;i++)messages.accept("{}");assertThrows(IOException.class,()->messages.accept("{}"));
        BrowserDevToolsConnection.WireBudget bytes=new BrowserDevToolsConnection.WireBudget();String packet="x".repeat(BrowserDevToolsConnection.MAX_MESSAGE);for(int i=0;i<64;i++)bytes.accept(packet);assertThrows(IOException.class,()->bytes.accept("x"));
    }
    @Test public void targetListCanSelectOnlyLocalPagePathsNeverAnArbitraryRemoteEndpoint()throws Exception {
        assertEquals("/devtools/page/ABC-123",BrowserDevToolsConnection.targetPath("ws://localhost:9222/devtools/page/ABC-123"));
        for(String url:List.of("ws://remote.example/devtools/page/abc","wss://localhost/devtools/page/abc","ws://u:p@localhost/devtools/page/abc","ws://localhost/devtools/browser/abc","ws://localhost/devtools/page/abc?x=1","ws://localhost/devtools/page/%2Fetc","ws://localhost/devtools/page/abc#x"))assertThrows(Exception.class,()->BrowserDevToolsConnection.targetPath(url));
    }
    @Test public void matchingUrlOrDefaultPageWorldCannotReplaceNativeDocumentNonce()throws Exception {
        BrowserNativeNetwork.DocumentProof proof=new BrowserNativeNetwork.DocumentProof("__pw_document_"+"a".repeat(32),"b".repeat(32),"native-doc");
        JSONObject context=new JSONObject("{\"id\":7,\"uniqueId\":\"provider-context-7\",\"origin\":\"https://same.example\",\"auxData\":{\"isDefault\":false,\"type\":\"isolated\",\"frameId\":\"main-a\"}}");JSONObject params=BrowserNativeNetwork.nonceProbe(proof,context);
        assertEquals("provider-context-7",params.getString("uniqueContextId"));assertFalse(params.has("contextId"));assertFalse(params.getString("expression").contains(proof.token));assertFalse(proof.toString().contains(proof.token));assertTrue(params.getBoolean("throwOnSideEffect"));assertFalse(params.getBoolean("allowUnsafeEvalBlockedByCSP"));
        assertFalse(BrowserNativeNetwork.nonceMatches(proof,new JSONObject("{\"result\":{\"type\":\"string\",\"value\":\"wrong-document\"}}")));
        JSONObject correct=new JSONObject().put("result",new JSONObject().put("type","string").put("value",proof.token));assertTrue(BrowserNativeNetwork.nonceMatches(proof,correct));correct.put("exceptionDetails",new JSONObject());assertFalse(BrowserNativeNetwork.nonceMatches(proof,correct));
        assertFalse(BrowserNativeNetwork.frameMatches(context,new JSONObject("{\"id\":\"child\",\"loaderId\":\"loader\"}")));assertFalse(BrowserNativeNetwork.frameMatches(context,new JSONObject("{\"id\":\"main-a\"}")));assertTrue(BrowserNativeNetwork.frameMatches(context,new JSONObject("{\"id\":\"main-a\",\"loaderId\":\"loader\"}")));
        context.getJSONObject("auxData").put("isDefault",true);assertNull(BrowserNativeNetwork.nonceProbe(proof,context));context.getJSONObject("auxData").put("isDefault",false);context.remove("uniqueId");assertNull(BrowserNativeNetwork.nonceProbe(proof,context));
    }
}
