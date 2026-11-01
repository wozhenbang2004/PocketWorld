package dev.pocketengine.features.ssh;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SshTerminalScreenTest {
    @Test public void bytesStayExactAcrossSplitUtf8AndCurrentCursorIsRequired()throws Exception {
        SshTerminalScreen screen=new SshTerminalScreen();byte[] value="中文🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        screen.append(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(value,2)),0,2,false);screen.append(Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(value,2,value.length)),2,value.length,false);
        JSONObject view=screen.view(80,24);assertEquals(2,view.getJSONArray("packets").length());assertEquals(value.length,view.getInt("retainedBytes"));assertFalse(view.getBoolean("gap"));assertEquals(0,view.getInt("generation"));
        assertThrows(IllegalArgumentException.class,()->screen.append("YQ==",0,1,false));assertThrows(IllegalArgumentException.class,()->screen.append("YQ==",value.length,value.length+2,false));assertEquals(value.length,screen.cursor());
    }
    @Test public void coveredBytesAndLocalRetentionResetAreExplicitNotFakeScreenContinuity()throws Exception {
        SshTerminalScreen screen=new SshTerminalScreen();byte[] block=new byte[65536];String encoded=Base64.getEncoder().encodeToString(block);for(int i=0;i<5;i++)screen.append(encoded,i*65536L,(i+1)*65536L,false);
        JSONObject local=screen.view(80,24);assertTrue(local.getBoolean("gap"));assertEquals(65536,local.getInt("retainedBytes"));assertEquals(1,local.getInt("generation"));
        screen.append("Yg==",screen.cursor(),screen.cursor()+100,true);JSONObject wire=screen.view(100,30);assertEquals(1,wire.getInt("retainedBytes"));assertEquals(2,wire.getInt("generation"));assertEquals("reconstructed_from_retained_prefix_after_gap",wire.getString("screenState"));
    }
    @Test public void terminalDisplayCannotFetchNavigateOrReadHostAssets() {
        assertTrue(SshTerminalWebView.allowed(SshTerminalWebView.ORIGIN+"index.html"));assertTrue(SshTerminalWebView.allowed(SshTerminalWebView.ORIGIN+"xterm.js"));
        for(String bad:new String[]{"https://evil.example/xterm.js","file:///data/data/private","content://secret",SshTerminalWebView.ORIGIN+"../index.html",SshTerminalWebView.ORIGIN+"xterm.js?token=x",SshTerminalWebView.ORIGIN+"index.html#jump"})assertFalse(SshTerminalWebView.allowed(bad));
    }
}
