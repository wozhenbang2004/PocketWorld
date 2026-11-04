package dev.pocketengine.phone.display;

import static org.junit.Assert.*;

import android.app.Application;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Real client/framing/control with an explicit Android socket fixture, not a device race test. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=ShellBridgePauseDispatchTest.Socket.class)
public final class ShellBridgePauseDispatchTest {
    static Runnable beforeOutput;
    static CountDownLatch readingResponse,releaseResponse;
    static boolean loseResponse;
    static int uid=2000,bytesWritten,requests;
    static JSONObject lastRequest;
    final ExecutorService workers=Executors.newFixedThreadPool(2);

    @Implements(LocalSocket.class)
    public static class Socket {
        private boolean closed;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private byte[] response;
        @Implementation public void connect(LocalSocketAddress address) { }
        @Implementation public void setSoTimeout(int timeout) { }
        @Implementation public Credentials getPeerCredentials(){return new Credentials(1,uid,2000);}
        @Implementation public OutputStream getOutputStream(){
            if(beforeOutput!=null)beforeOutput.run();
            return new OutputStream(){
                @Override public void write(int value)throws IOException {
                    if(closed)throw new IOException("socket closed");
                    bytes.write(value);bytesWritten++;
                }
                @Override public void flush()throws IOException {
                    try {
                        lastRequest=new JSONObject(DisplayWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),32768));
                        requests++;
                        JSONObject receipt=new JSONObject().put("ok",true).put("accepted",true)
                                .put("requestId",lastRequest.getString("requestId"));
                        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                        DisplayWire.write(new DataOutputStream(encoded),receipt.toString(),8*1024*1024);
                        response=encoded.toByteArray();
                    }catch(Exception failure){throw new IOException(failure);}
                }
            };
        }
        @Implementation public InputStream getInputStream()throws IOException {
            if(closed||loseResponse)throw new IOException("response unavailable after write");
            ByteArrayInputStream reply=new ByteArrayInputStream(response);
            return new InputStream(){
                @Override public int read()throws IOException {
                    if(readingResponse!=null){
                        readingResponse.countDown();
                        try {if(!releaseResponse.await(3,TimeUnit.SECONDS))throw new IOException("fixture response not released");}
                        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}
                    }
                    return reply.read();
                }
            };
        }
        @Implementation public void close(){closed=true;}
    }

    private static ShellBridgeClient client(){return new ShellBridgeClient("fixture-socket","fixture-token");}
    private static JSONObject input()throws Exception{return new JSONObject().put("sessionId","original-session").put("displayId",5).put("frameGeneration",4);}
    private static void resetSocket(){beforeOutput=null;readingResponse=null;releaseResponse=null;loseResponse=false;uid=2000;bytesWritten=0;requests=0;lastRequest=null;}
    @After public void close()throws Exception {
        if(releaseResponse!=null)releaseResponse.countDown();
        workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));resetSocket();
    }
    private void pauseBeforeOutput(RunControl control,boolean resume){
        beforeOutput=()->{
            try {workers.submit(()->{control.pause();if(resume)control.resume();}).get(1,TimeUnit.SECONDS);}
            catch(Exception failure){throw new AssertionError("fixture must finish owner pause before returning the output stream",failure);}
        };
    }

    @Test public void pauseWinsAfterFinalFreshCheckAndNoOrdinaryCommandWrites()throws Exception {
        for(String command:new String[]{"launch","tap","swipe","key"}){
            resetSocket();RunControl control=new RunControl();pauseBeforeOutput(control,false);
            Exception refused=null;try{client().call(command,input(),control,0);}catch(Exception failure){refused=failure;}
            assertTrue(control.isPaused());assertEquals(1,control.pauseRevision());
            assertEquals(command,0,bytesWritten);assertEquals(command,0,requests);
            assertTrue("pause rejection must stay definite",refused instanceof ShellBridgeClient.StaleRequestException);
        }
    }
    @Test public void pauseThenResumeAfterFinalFreshCheckStillRejectsTheOldRevision()throws Exception {
        RunControl control=new RunControl();pauseBeforeOutput(control,true);
        Exception refused=null;try{client().call("tap",input(),control,0);}catch(Exception failure){refused=failure;}
        assertFalse(control.isPaused());assertEquals(1,control.pauseRevision());assertEquals(0,bytesWritten);assertEquals(0,requests);
        assertTrue("old revision rejection must stay definite",refused instanceof ShellBridgeClient.StaleRequestException);
    }
    @Test public void ordinaryCommandsStillWriteExactlyOneOriginalAuthenticatedFrame()throws Exception {
        for(String command:new String[]{"launch","tap","swipe","key"}){
            resetSocket();RunControl control=new RunControl();JSONObject receipt=client().call(command,input(),control);
            assertTrue(receipt.getBoolean("accepted"));assertEquals(1,requests);assertTrue(bytesWritten>0);
            assertEquals(command,lastRequest.getString("command"));assertEquals("fixture-token",lastRequest.getString("token"));
            assertEquals("original-session",lastRequest.getString("sessionId"));assertEquals(5,lastRequest.getInt("displayId"));
            assertEquals(receipt.getString("requestId"),lastRequest.getString("requestId"));
        }
    }
    @Test public void responseWaitDoesNotHoldThePauseMonitor()throws Exception {
        RunControl control=new RunControl();readingResponse=new CountDownLatch(1);releaseResponse=new CountDownLatch(1);
        Future<JSONObject> call=workers.submit(()->client().call("tap",input(),control,0));
        try {
            assertTrue("actual client must reach response read",readingResponse.await(2,TimeUnit.SECONDS));assertEquals(1,requests);
            workers.submit(control::pause).get(1,TimeUnit.SECONDS);
            assertTrue(control.isPaused());assertFalse("receipt is still withheld",call.isDone());
        }finally {releaseResponse.countDown();}
        assertTrue(call.get(2,TimeUnit.SECONDS).getBoolean("accepted"));assertEquals(1,requests);
    }
    @Test public void lostResponseAfterEntryRemainsUncertainAndNeverRetries()throws Exception {
        loseResponse=true;
        Exception uncertain=assertThrows(IOException.class,()->client().call("tap",input(),new RunControl(),0));
        assertFalse(uncertain instanceof ShellBridgeClient.StaleRequestException);
        assertFalse(uncertain instanceof NativePhoneEntry.Refused);assertFalse(uncertain instanceof ShellBridgeClient.NotDispatchedException);
        assertEquals(1,requests);assertTrue(bytesWritten>0);
    }
    @Test public void cancellationAtTheNewEntryGateDoesNotWrite()throws Exception {
        RunControl control=new RunControl();beforeOutput=control::cancel;
        assertThrows(Exception.class,()->client().call("tap",input(),control,0));
        assertTrue(control.isCancelled());assertEquals(0,bytesWritten);assertEquals(0,requests);
    }
    @Test public void typedOcrAdmissionStillRefusesAPauseBeforeWriting()throws Exception {
        RunControl control=new RunControl();AtomicInteger checked=new AtomicInteger();
        assertThrows(NativePhoneEntry.Refused.class,()->client().call("tap",input(),control,0,false,()->{checked.incrementAndGet();control.pause();}));
        assertEquals(1,checked.get());assertEquals(0,bytesWritten);assertEquals(0,requests);
    }
    @Test public void pausedOwnerHandoffAndExistingManualInputKeepTheirSeparateEntry()throws Exception {
        RunControl owner=new RunControl();owner.pause();
        assertTrue(client().call("owner_handoff_main",input(),owner,owner.pauseRevision(),true).getBoolean("accepted"));
        assertEquals(1,requests);resetSocket();
        assertTrue(client().call("tap",input(),owner,owner.pauseRevision(),true).getBoolean("accepted"));assertEquals(1,requests);
    }
    @Test public void rejectedPeerReceivesNoOrdinaryRequest()throws Exception {
        uid=10000;assertThrows(SecurityException.class,()->client().call("tap",input(),new RunControl(),0));
        assertEquals(0,bytesWritten);assertEquals(0,requests);
    }
}
