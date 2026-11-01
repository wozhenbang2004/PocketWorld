package dev.pocketengine.features.channels;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;

/** Local-only standard QR encoding. No URL fetch, analytics, persistence or challenge upload. */
public final class ChannelLoginQr {
    private ChannelLoginQr() { }
    public static int[] pixels(String originalContent,int side)throws Exception {
        if(originalContent==null||originalContent.isEmpty()||originalContent.getBytes(StandardCharsets.UTF_8).length>1024)throw new IllegalArgumentException("本次官方登录内容超出本机二维码显示范围，请取消后重新核对");
        if(side<192||side>768)throw new IllegalArgumentException("二维码显示尺寸无效");
        EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.MARGIN,4);
        BitMatrix matrix=new QRCodeWriter().encode(originalContent,BarcodeFormat.QR_CODE,side,side,hints);
        if(matrix.getWidth()!=side||matrix.getHeight()!=side)throw new IllegalArgumentException("二维码内容过密，未生成不可核对的替代图片");
        int[] pixels=new int[side*side];for(int y=0;y<side;y++)for(int x=0;x<side;x++)pixels[y*side+x]=matrix.get(x,y)?0xff000000:0xffffffff;return pixels;
    }
}
