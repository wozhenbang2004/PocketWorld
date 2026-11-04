package dev.pocketengine.media;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.util.*;
import org.json.*;

/** Real Android container parser before a provider MP4 is published as a playable original.
 * Header sniffing alone is not validation, and this is not a transcode/quality claim. */
final class NativeMediaValidation {
    private NativeMediaValidation(){}
    static JSONObject mp4(byte[] bytes,File privateCache,RunControl control)throws Exception{
        if(bytes.length<12||bytes.length>MediaTransport.MAX_MEDIA_BYTES||bytes[4]!='f'||bytes[5]!='t'||bytes[6]!='y'||bytes[7]!='p')throw new IllegalArgumentException("原视频不是有界MP4");
        try(var copy=NativeMediaScratch.providerValidation(privateCache)){
        File file=copy.file();MediaExtractor extractor=new MediaExtractor();Runnable cancel=extractor::release;control.onCancel(cancel);
        try{
            try(OutputStream out=new FileOutputStream(file)){for(int offset=0;offset<bytes.length;offset+=32768){control.check();out.write(bytes,offset,Math.min(32768,bytes.length-offset));}}
            control.check();extractor.setDataSource(file.getAbsolutePath());if(extractor.getDrmInitData()!=null||extractor.getPsshInfo()!=null&&!extractor.getPsshInfo().isEmpty())throw new SecurityException("加密视频不在原件预览支持范围");
            int count=extractor.getTrackCount();if(count<1||count>4)throw new IllegalArgumentException("MP4需1至4个支持的原轨道");JSONArray tracks=new JSONArray();int videos=0;
            for(int i=0;i<count;i++){control.check();MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);if(!Set.of("video/avc","video/hevc","audio/mp4a-latm").contains(mime)||!format.containsKey(MediaFormat.KEY_DURATION)||format.getLong(MediaFormat.KEY_DURATION)<1||format.getLong(MediaFormat.KEY_DURATION)>3_600_000_000L)throw new IllegalArgumentException("原视频仅支持H.264/H.265、AAC与一小时内有时长轨道");JSONObject track=new JSONObject().put("mimeType",mime).put("durationUs",format.getLong(MediaFormat.KEY_DURATION));if(mime.startsWith("video/")){videos++;int width=format.getInteger(MediaFormat.KEY_WIDTH),height=format.getInteger(MediaFormat.KEY_HEIGHT);if(width<1||height<1||width>4096||height>4096||(long)width*height>16_777_216)throw new IllegalArgumentException("原视频分辨率超出支持范围");track.put("width",width).put("height",height);}
                extractor.selectTrack(i);if(extractor.getSampleSize()<1||(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IllegalArgumentException("原MP4轨道无可读样本或已加密");extractor.unselectTrack(i);tracks.put(track);}
            if(videos!=1)throw new IllegalArgumentException("当前原视频预览只支持一个视频轨道");control.check();return new JSONObject().put("container","mp4").put("parser","android.media.MediaExtractor").put("actualTracks",tracks).put("containerReopened",true).put("fullPlaybackVerified",false);
        }finally{control.removeCancelAction(cancel);extractor.release();}
        }
    }
}
