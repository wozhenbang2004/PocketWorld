package dev.pocketengine.phone.display.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production endpoint/SYNC code, in-process streams; not an Android ADB or pairing test. */
public final class AdbBootstrapPolicyTest {
    private LocalAdbEndpoints endpoints()throws Exception {
        return new LocalAdbEndpoints(Arrays.asList(InetAddress.getByName("127.0.0.1"),InetAddress.getByName("::1"),InetAddress.getByName("192.0.2.10")));
    }
    @Test public void onlyNumericCurrentDeviceAddressesAreAccepted()throws Exception {
        LocalAdbEndpoints local=endpoints();assertEquals("127.0.0.1",local.check("localhost",37111));
        assertEquals("192.0.2.10",local.check("192.0.2.10",37111));assertNotNull(local.check("[::1]",37111));
        for(String host:new String[]{"example.com","192.0.2.11","0.0.0.0","::","127.0.0.1.evil.test","127.0.0.1/anything","127.0.0.1:37111"})assertThrows(host,Exception.class,()->local.check(host,37111));
    }
    @Test public void tlsServiceKindsPortsAndTransientCodeAreSeparate()throws Exception {
        assertEquals(LocalAdbEndpoints.PAIRING,LocalAdbEndpoints.serviceType("_adb-tls-pairing._tcp"));
        assertEquals(LocalAdbEndpoints.CONNECT,LocalAdbEndpoints.serviceType("_adb-tls-connect._tcp."));
        assertThrows(SecurityException.class,()->LocalAdbEndpoints.serviceType("_adb._tcp."));
        assertThrows(IllegalArgumentException.class,()->LocalAdbEndpoints.distinctPorts(37111,37111));
        LocalAdbEndpoints.distinctPorts(37111,37112);assertEquals("012345",LocalAdbEndpoints.pairingCode("012345"));
        for(String code:new String[]{"12345","1234567","12 345","１２３４５６","abcdef"})assertThrows(IllegalArgumentException.class,()->LocalAdbEndpoints.pairingCode(code));
        assertThrows(IllegalArgumentException.class,()->endpoints().check("127.0.0.1",0));
        assertThrows(IllegalArgumentException.class,()->endpoints().check("127.0.0.1",65536));
    }
    @Test public void syncSendsFixedPathBoundedChunksAndWaitsForOkay()throws Exception {
        byte[] helper=helper(70001);ByteArrayOutputStream wire=new ByteArrayOutputStream();
        AdbSyncUpload.send(new ByteArrayInputStream(reply("OKAY",0)),wire,helper,1730000000);
        ByteBuffer frames=ByteBuffer.wrap(wire.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("SEND",tag(frames));int length=frames.getInt();byte[] path=new byte[length];frames.get(path);
        assertEquals(AdbSyncUpload.HELPER_PATH+",33152",new String(path,StandardCharsets.UTF_8));
        ByteArrayOutputStream actual=new ByteArrayOutputStream();int chunks=0;
        while(true){String tag=tag(frames);int count=frames.getInt();if(tag.equals("DONE")){assertEquals(1730000000,count);break;}
            assertEquals("DATA",tag);assertTrue(count>0&&count<=65536);byte[] chunk=new byte[count];frames.get(chunk);actual.write(chunk);chunks++;
        }
        assertEquals(2,chunks);assertArrayEquals(helper,actual.toByteArray());assertEquals("QUIT",tag(frames));assertEquals(0,frames.getInt());assertFalse(frames.hasRemaining());
        assertEquals("shell:CLASSPATH=/data/local/tmp/pocketengine-display-helper.jar app_process /system/bin dev.pocketengine.displayhelper.Main",AdbSyncUpload.HELPER_COMMAND);
    }
    @Test public void invalidAssetAndTimestampNeverWriteAPartialSend()throws Exception {
        for(byte[] asset:new byte[][]{null,new byte[3],new byte[524289],new byte[]{'n','o','p','e'}}){
            ByteArrayOutputStream wire=new ByteArrayOutputStream();assertThrows(IOException.class,()->AdbSyncUpload.send(new ByteArrayInputStream(new byte[0]),wire,asset,1));assertEquals(0,wire.size());
        }
        ByteArrayOutputStream wire=new ByteArrayOutputStream();assertThrows(IOException.class,()->AdbSyncUpload.send(new ByteArrayInputStream(new byte[0]),wire,helper(4),-1));assertEquals(0,wire.size());
    }
    @Test public void rejectionAndMalformedRepliesNeverBecomeUploadSuccess()throws Exception {
        ByteArrayOutputStream response=new ByteArrayOutputStream();response.write(reply("FAIL",6));response.write("secret".getBytes(StandardCharsets.US_ASCII));
        IOException rejected=assertThrows(IOException.class,()->AdbSyncUpload.send(new ByteArrayInputStream(response.toByteArray()),new ByteArrayOutputStream(),helper(4),1));assertFalse(rejected.getMessage().contains("secret"));
        for(byte[] responseBytes:new byte[][]{reply("OKAY",1),reply("FAIL",0xffffffffL),reply("????",0)})assertThrows(IOException.class,()->AdbSyncUpload.send(new ByteArrayInputStream(responseBytes),new ByteArrayOutputStream(),helper(4),1));
        assertThrows(EOFException.class,()->AdbSyncUpload.send(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),helper(4),1));
    }
    private static byte[] helper(int length){byte[] value=new byte[length];value[0]='P';value[1]='K';for(int i=2;i<length;i++)value[i]=(byte)i;return value;}
    private static byte[] reply(String tag,long length){return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(tag.getBytes(StandardCharsets.US_ASCII)).putInt((int)length).array();}
    private static String tag(ByteBuffer buffer){byte[] value=new byte[4];buffer.get(value);return new String(value,StandardCharsets.US_ASCII);}
}
