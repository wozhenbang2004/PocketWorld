package dev.pocketengine.features.local;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
public final class NativeCommandOutputDecoderTest {
    @Test public void everyByteBoundaryPreservesOriginalUnicodeAndIndependentStreams()throws Exception {
        String value="实际输出😀\n第二行\r\n";byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        for(int split=0;split<=bytes.length;split++){StringBuilder out=new StringBuilder(),err=new StringBuilder();var stdout=new NativeCommandExecutor.OutputDecoder(out::append);var stderr=new NativeCommandExecutor.OutputDecoder(err::append);stdout.append(java.util.Arrays.copyOfRange(bytes,0,split));stderr.append("separate error".getBytes(StandardCharsets.UTF_8));stdout.append(java.util.Arrays.copyOfRange(bytes,split,bytes.length));stdout.finish();stderr.finish();assertEquals(value,out.toString());assertEquals("separate error",err.toString());}
    }
    @Test public void invalidOrIncompleteOutputUsesOriginalReaderReplacementAndCannotFlushTwice()throws Exception {
        byte[] bytes={(byte)'a',(byte)0xe4,(byte)0xbd};StringBuilder out=new StringBuilder();var decoder=new NativeCommandExecutor.OutputDecoder(out::append);for(byte b:bytes)decoder.append(new byte[]{b});assertEquals("a",out.toString());decoder.finish();assertEquals(new String(bytes,StandardCharsets.UTF_8),out.toString());assertThrows(IllegalStateException.class,decoder::finish);
    }
}
