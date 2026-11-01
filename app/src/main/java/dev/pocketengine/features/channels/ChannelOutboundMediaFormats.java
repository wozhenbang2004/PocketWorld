package dev.pocketengine.features.channels;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Byte-derived presentation/wire format, never an audience or upload grant. */
public final class ChannelOutboundMediaFormats {
    public static final int MAX_BYTES=8*1024*1024;
    private ChannelOutboundMediaFormats() { }
    public static JSONObject describe(byte[] bytes,String name)throws Exception {
        if(bytes==null||bytes.length>MAX_BYTES)throw new IllegalArgumentException("所选渠道附件最多8MiB，未压缩或部分发送");
        String kind="file",mime="application/octet-stream",evidence="opaque_exact_file_bytes";int codec=0;JSONObject dimensions=null;
        try{dimensions=WeixinOwnerImages.imageFacts(bytes);kind="image";mime=dimensions.getString("mimeType");evidence="signature_and_native_header_dimensions_not_content_understanding";}catch(IllegalArgumentException notNativeImage){/* Keep exact bytes as a file, not an invented image. */}
        if(kind.equals("file")&&bytes.length>=12&&new String(bytes,4,4,StandardCharsets.US_ASCII).equals("ftyp")){kind="video";mime="video/mp4";evidence="mp4_container_header_only_not_full_decoder";}
        if(kind.equals("file")&&(prefix(bytes,"#!SILK_V3",0)||prefix(bytes,"#!SILK_V3",1)&&bytes[0]==2)){kind="voice";mime="audio/silk";codec=6;evidence="silk_header_only_no_transcription";}
        else if(kind.equals("file")&&prefix(bytes,"#!AMR",0)){kind="voice";mime="audio/amr";codec=5;evidence="amr_header_only_no_transcription";}
        else if(kind.equals("file")&&prefix(bytes,"ID3",0)){kind="voice";mime="audio/mpeg";codec=7;evidence="id3_header_only_no_transcription";}
        if(kind.equals("file")&&prefix(bytes,"%PDF-",0)){mime="application/pdf";evidence="pdf_header_only";}
        JSONObject value=new JSONObject().put("kind",kind).put("mimeType",mime).put("name",dev.pocketengine.features.attachments.AttachmentBook.displayName(name))
                .put("bytes",bytes.length).put("formatEvidence",evidence).put("contentUnderstandingVerified",false)
                .put("qqFileType",kind.equals("image")?1:kind.equals("video")?2:kind.equals("voice")?3:4)
                .put("weixinItemType",kind.equals("image")?2:kind.equals("video")?5:kind.equals("voice")?3:4)
                .put("weixinUploadMediaType",kind.equals("image")?1:kind.equals("video")?2:kind.equals("voice")?4:3);
        if(codec!=0)value.put("weixinCodec",codec);if(dimensions!=null)for(String key:new String[]{"width","height"})value.put(key,dimensions.getInt(key));return value;
    }
    private static boolean prefix(byte[] value,String text,int offset){byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);if(offset<0||value.length<offset+bytes.length)return false;for(int n=0;n<bytes.length;n++)if(value[n+offset]!=bytes[n])return false;return true;}
}
