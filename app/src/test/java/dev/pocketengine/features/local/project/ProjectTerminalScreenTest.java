package dev.pocketengine.features.local.project;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectTerminalScreenTest {
    private JSONObject chunk(long sequence,byte[] bytes)throws Exception{return new JSONObject().put("sequence",sequence).put("stream",1).put("bytes",bytes.length).put("dataBase64",Base64.getEncoder().encodeToString(bytes));}
    private JSONObject page(long next,JSONObject...chunks)throws Exception{JSONArray values=new JSONArray();for(JSONObject chunk:chunks)values.put(chunk);return new JSONObject().put("operationId","original").put("chunks",values).put("nextCursor",next).put("hasMore",false).put("outputTruncated",false).put("logIncompleteTail",false);}
    private byte[] retained(JSONObject view)throws Exception{ByteArrayOutputStream all=new ByteArrayOutputStream();JSONArray packets=view.getJSONObject("screen").getJSONArray("packets");for(int i=0;i<packets.length();i++)all.write(Base64.getDecoder().decode(packets.getString(i)));return all.toByteArray();}
    @Test public void originalSequencePagesKeepAnsiAndSplitUtf8BytesExact()throws Exception {
        ProjectTerminalScreen screen=new ProjectTerminalScreen("original",80,24);byte[] bytes="\u001b[2J中文🙂\r\n".getBytes(StandardCharsets.UTF_8);screen.append(page(1,chunk(1,java.util.Arrays.copyOf(bytes,6))));JSONObject view=screen.append(page(2,chunk(2,java.util.Arrays.copyOfRange(bytes,6,bytes.length))));assertArrayEquals(bytes,retained(view));assertEquals(2,screen.cursor());assertFalse(view.getJSONObject("screen").getBoolean("gap"));assertEquals("parsed_actual_pty_bytes",view.getJSONObject("screen").getString("screenState"));
        assertEquals(2,screen.append(page(2)).getLong("outputCursor"));screen.resize(96,30);JSONObject sized=screen.append(page(2));assertEquals(96,sized.getJSONObject("screen").getInt("cols"));assertEquals(30,sized.getJSONObject("screen").getInt("rows"));
    }
    @Test public void wrongIdentityReplayOrMalformedLengthDoesNotAdvanceOriginalCursor()throws Exception {
        ProjectTerminalScreen screen=new ProjectTerminalScreen("original",80,24);JSONObject first=page(1,chunk(1,new byte[]{1}));screen.append(first);assertThrows(SecurityException.class,()->screen.append(first));assertThrows(SecurityException.class,()->screen.append(page(2,chunk(2,new byte[]{2})).put("operationId","other")));assertThrows(SecurityException.class,()->screen.append(page(3,chunk(2,new byte[]{2}))));assertThrows(SecurityException.class,()->screen.append(page(2,chunk(2,new byte[]{2}).put("bytes",2))));assertThrows(SecurityException.class,()->screen.append(page(2,chunk(2,new byte[]{2}).put("stream",3))));assertEquals(1,screen.cursor());assertArrayEquals(new byte[]{1,2},retained(screen.append(page(2,chunk(2,new byte[]{2})))));
    }
    @Test public void sequenceGapAndNativeTruncationStayVisibleAndDoNotPretendScreenContinuity()throws Exception {
        ProjectTerminalScreen screen=new ProjectTerminalScreen("original",80,24);screen.append(page(1,chunk(1,new byte[]{1})));JSONObject result=screen.append(page(3,chunk(3,new byte[]{3})).put("outputTruncated",true).put("logIncompleteTail",true));assertTrue(result.getJSONObject("screen").getBoolean("gap"));assertEquals(1,result.getJSONObject("screen").getInt("generation"));assertArrayEquals(new byte[]{3},retained(result));assertTrue(result.getBoolean("outputTruncated"));assertTrue(result.getBoolean("logIncompleteTail"));assertTrue(screen.append(page(3)).getBoolean("outputTruncated"));
    }
    @Test public void legitimateManySmallNativeChunksAreAcceptedWithinTheRealPageByteLimit()throws Exception {
        ProjectTerminalScreen screen=new ProjectTerminalScreen("original",80,24);JSONObject many=page(256);JSONArray chunks=many.getJSONArray("chunks");for(int i=1;i<=256;i++)chunks.put(chunk(i,new byte[]{(byte)i}));JSONObject result=screen.append(many);assertEquals(256,retained(result).length);assertFalse(result.getJSONObject("screen").getBoolean("gap"));
        assertThrows(IllegalArgumentException.class,()->screen.resize(301,24));assertThrows(IllegalArgumentException.class,()->screen.resize(80,4));assertEquals(256,screen.cursor());
    }
}
