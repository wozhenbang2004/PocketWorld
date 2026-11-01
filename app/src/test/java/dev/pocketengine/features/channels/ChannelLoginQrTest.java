package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.Test;

/** Local encode/decode proof only. Not a phone camera or real WeChat scan. */
public final class ChannelLoginQrTest {
    @Test public void exactOfficialChallengeRoundTripsLocallyWithNoNetworkEncoder()throws Exception {
        String content="https://weixin.qq.com/fixture-native-challenge?value=中文🙂&opaque=not-a-real-login";int[] pixels=ChannelLoginQr.pixels(content,512);assertEquals(512*512,pixels.length);
        String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(512,512,pixels)))).getText();assertEquals(content,decoded);assertEquals(0xffffffff,pixels[0]);
    }
    @Test public void invalidAndOversizedContentCannotProduceAnUnrelatedPlaceholderQr()throws Exception {
        assertThrows(IllegalArgumentException.class,()->ChannelLoginQr.pixels("",512));assertThrows(IllegalArgumentException.class,()->ChannelLoginQr.pixels("a".repeat(1025),512));assertThrows(IllegalArgumentException.class,()->ChannelLoginQr.pixels("normal",4096));
    }
}
