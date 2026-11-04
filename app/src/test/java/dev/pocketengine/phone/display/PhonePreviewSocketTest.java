package dev.pocketengine.phone.display;

import android.app.Application;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Actual preview client and length-prefixed wire. The authenticated socket peer is a fixture;
 * no action driver, accessibility tree, physical device or actual helper process is simulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows=PhonePreviewSocketTest.Peer.class)
public final class PhonePreviewSocketTest {
    private static int uid=2000,requests;
    private static JSONObject last;
    private static RunControl cancelAfterWrite;

    @Implements(LocalSocket.class)
    public static final class Peer {
        private boolean closed;
        private byte[] response;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        @Implementation public void connect(LocalSocketAddress address) { }
        @Implementation public void setSoTimeout(int timeout) { }
        @Implementation public Credentials getPeerCredentials(){return new Credentials(1,uid,2000);}
        @Implementation public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int value)throws java.io.IOException {if(closed)throw new java.io.IOException("closed");bytes.write(value);}
            @Override public void flush()throws java.io.IOException {
                try{
                    JSONObject request=new JSONObject(DisplayWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),32*1024));
                    if(!"preview_frame".equals(request.getString("command")))throw new AssertionError("viewer must not dispatch actions");
                    requests++;last=request;
                    JSONObject reply=new JSONObject().put("ok",true).put("requestId",request.getString("requestId"))
                            .put("sessionId",request.getString("sessionId")).put("displayId",request.getInt("displayId"));
                    ByteArrayOutputStream encoded=new ByteArrayOutputStream();DisplayWire.write(new DataOutputStream(encoded),reply.toString(),2*1024*1024);response=encoded.toByteArray();
                    if(cancelAfterWrite!=null)cancelAfterWrite.cancel();
                }catch(Exception failure){throw new java.io.IOException(failure);}
            }
        };}
        @Implementation public InputStream getInputStream()throws java.io.IOException {if(closed)throw new java.io.IOException("closed after write");return new ByteArrayInputStream(response);}
        @Implementation public void close(){closed=true;}
    }
    private static RunControl control(){RunControl original=new RunControl();original.bindHostRun("task","run",1);return original;}
    private static JSONObject payload()throws Exception {return new JSONObject().put("ownerTaskId","task").put("ownerRunId","run").put("ownerEpoch",1)
            .put("sessionId","original-session").put("displayId",5).put("afterFrameGeneration",-1);}
    @After public void reset(){uid=2000;requests=0;last=null;cancelAfterWrite=null;}

    @Test public void actualPreviewTransportNeverRunsActionAuthorityAndKeepsPausedRunPaused()throws Exception {
        RunControl original=control();original.setAuthorityCheck(()->{throw new AssertionError("viewer entered action authority");});original.pause();long revision=original.pauseRevision();
        JSONObject result=new ShellBridgeClient("fixture","fixture-token").previewFrame(payload(),original,()->{});
        assertTrue(result.getBoolean("ok"));assertEquals(1,requests);
        assertEquals("original-session",last.getString("sessionId"));assertEquals(5,last.getInt("displayId"));assertEquals("run",last.getString("ownerRunId"));
        assertTrue(original.isPaused());assertEquals(revision,original.pauseRevision());assertFalse(original.isCancelled());
    }
    @Test public void ownerWindowRevokedAtWireEntryPreventsPreviewAndNeverCancelsExecution()throws Exception {
        RunControl original=control();java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(SecurityException.class,()->new ShellBridgeClient("fixture","fixture-token").previewFrame(payload(),original,()->{
            if(checks.incrementAndGet()==2)throw new SecurityException("original owner window closed");
        }));
        assertEquals(0,requests);assertFalse(original.isCancelled());assertFalse(original.isPaused());
    }
    @Test public void cancelledPreviewCannotReturnPixelsAndUnprivilegedPeerCannotBeRead()throws Exception {
        RunControl original=control();cancelAfterWrite=original;
        assertThrows(Exception.class,()->new ShellBridgeClient("fixture","fixture-token").previewFrame(payload(),original,()->{}));
        assertEquals(1,requests);assertTrue(original.isCancelled());cancelAfterWrite=null;uid=10000;
        assertThrows(SecurityException.class,()->new ShellBridgeClient("fixture","fixture-token").previewFrame(payload(),control(),()->{}));
        assertEquals(1,requests);
    }
}
