package dev.pocketengine.features.attachments.processing;

import android.media.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.OwnerFilePreviewSource;
import dev.pocketengine.features.local.project.ProjectSources;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Platform extractor/muxer remux, never an arbitrary ffmpeg/shell command. Original bytes are
 * copied to a dedicated immutable input before codecs see them. Sample-boundary approximation (video sync sample or audio sample) is
 * displayed in the review and receipt; no sample/frame-accurate transcode claim. */
public final class NativeMediaTrim {
    public static final class Review {
        private final OwnerFilePreviewSource source;private final JSONObject metadata;
        public final long requestedStartUs,requestedEndUs,actualStartUs,durationUs;public final String identity;
        private Review(OwnerFilePreviewSource source,long start,long end,long actual,long duration,JSONArray tracks)throws Exception{
            this.source=source;requestedStartUs=start;requestedEndUs=end;actualStartUs=actual;durationUs=duration;metadata=new JSONObject().put("format","pocketworld.media-trim.v1").put("sourceIdentity",source.identity).put("sourceSha256",source.sha256).put("requestedStartUs",start).put("requestedEndUs",end).put("actualStartUs",actual).put("sourceDurationUs",duration).put("tracks",tracks).put("outputContainer","mp4").put("transcoding",false).put("startAlignment",startAlignment(tracks)).put("endAlignment","last_sample_before_requested_end").put("allOriginalBytesRetained",true);identity=ProjectSources.digest(metadata);
        }
        public JSONObject metadata()throws Exception{return ProjectSources.copy(metadata).put("planDigest",identity);}
        public OwnerFilePreviewSource source(){return source;}
    }
    private NativeMediaTrim(){}
    public static JSONObject inspect(OwnerFilePreviewSource source,File privateCache,RunControl control)throws Exception{try(var budget=OriginalFileProcessingBudget.acquire(control);var original=new Original(source,privateCache,control)){MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(original.file.getAbsolutePath());JSONArray tracks=tracks(extractor);long duration=0;for(int i=0;i<tracks.length();i++)duration=Math.max(duration,tracks.getJSONObject(i).getLong("durationUs"));source.requireCurrent(control);return new JSONObject().put("sourceIdentity",source.identity).put("sha256",source.sha256).put("durationMillis",duration/1000).put("tracks",tracks).put("startAlignment",startAlignment(tracks)).put("editable",duration<=600_000_000L).put("transcoding",false);}finally{extractor.release();}}}
    public static Review review(OwnerFilePreviewSource source,long startMillis,long endMillis,File privateCache,RunControl control)throws Exception{
        if(startMillis<0||endMillis<=startMillis||endMillis-startMillis>120000||endMillis>600000)throw new IllegalArgumentException("本机无损重封装截取最多2分钟，来源最多10分钟；结束需晚于开始，未重编码或修改原件");
        try(var budget=OriginalFileProcessingBudget.acquire(control);var original=new Original(source,privateCache,control)){
            MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(original.file.getAbsolutePath());JSONArray tracks=tracks(extractor);long duration=0;int primary=-1;for(int i=0;i<tracks.length();i++){var track=tracks.getJSONObject(i);duration=Math.max(duration,track.getLong("durationUs"));if(primary<0||track.getString("mimeType").startsWith("video/"))primary=i;}
                long start=Math.multiplyExact(startMillis,1000),end=Math.multiplyExact(endMillis,1000);if(end>duration||duration>600_000_000L)throw new IllegalArgumentException("所选时间超出原文件实际时长或10分钟来源上限");extractor.selectTrack(primary);extractor.seekTo(start,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);long actual=extractor.getSampleTime();if(actual<0||actual>start)throw new SecurityException("未取得原文件不晚于开始时间的同步样本，未猜时间");source.requireCurrent(control);return new Review(source,start,end,actual,duration,tracks);
            }finally{extractor.release();}
        }
    }
    public static ProcessedOriginalFile execute(Review review,File privateCache,RunControl control)throws Exception{
        if(review==null)throw new SecurityException("请先核对原文件与实际样本起点");
        try(var budget=OriginalFileProcessingBudget.acquire(control);var original=new Original(review.source,privateCache,control)){
            File output=File.createTempFile("trim-output-",".mp4",original.directory);MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean started=false,stopped=false;int samples=0;long first=Long.MAX_VALUE,last=-1;int videos=0;Set<Integer> videoSeen=new HashSet<>();
            try{
                extractor.setDataSource(original.file.getAbsolutePath());JSONArray tracks=tracks(extractor);if(!ProjectSources.canonical(tracks).equals(ProjectSources.canonical(review.metadata.getJSONArray("tracks"))))throw new SecurityException("原媒体实际轨道已变化");muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int[] indexes=new int[tracks.length()];long[] written=new long[tracks.length()];Arrays.fill(written,-1);
                for(int i=0;i<tracks.length();i++){MediaFormat format=extractor.getTrackFormat(i);indexes[i]=muxer.addTrack(format);extractor.selectTrack(i);if(tracks.getJSONObject(i).getString("mimeType").startsWith("video/")){videos++;if(format.containsKey(MediaFormat.KEY_ROTATION))muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION));}}
                if(videos>1)throw new IllegalArgumentException("当前只支持一个视频轨道和不超过三个音频轨道，未丢弃其他视频");muxer.start();started=true;extractor.seekTo(review.actualStartUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);ByteBuffer buffer=ByteBuffer.allocateDirect(2*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                for(;;){control.check();if(System.nanoTime()>deadline)throw new IOException("媒体加工超时，未发布部分文件");int track=extractor.getSampleTrackIndex();long time=extractor.getSampleTime();if(track<0||time<0)break;if(time>=review.requestedEndUs)break;if(time<review.actualStartUs){if(!extractor.advance())break;continue;}
                    int flags=extractor.getSampleFlags();if((flags&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new SecurityException("加密媒体不在本机剪辑范围");if((flags&MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)!=0)throw new SecurityException("分片样本不在此原件重封装范围，未丢失帧片段");if(tracks.getJSONObject(track).getString("mimeType").startsWith("video/")&&videoSeen.add(track)&&(flags&MediaExtractor.SAMPLE_FLAG_SYNC)==0)throw new SecurityException("实际首视频样本不是同步帧，未生成不可独立播放片段");long count=extractor.getSampleSize();if(count<0||count>buffer.capacity())throw new IllegalArgumentException("单个原媒体样本超过2MiB，未截断复制");buffer.clear();int size=extractor.readSampleData(buffer,0);if(size!=count||size<0)throw new IOException("原媒体样本未完整读取");info.set(0,size,time-review.actualStartUs,(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);muxer.writeSampleData(indexes[track],buffer,info);written[track]++;samples++;first=Math.min(first,time);last=Math.max(last,time);if(samples>100000||output.length()>20*1024*1024)throw new IllegalArgumentException("剪辑输出样本或20MiB范围超限，未发布部分结果");if(!extractor.advance())break;
                }
                for(long count:written)if(count<0)throw new IllegalStateException("所选范围没有包含每个原轨道的实际样本，未静默丢轨");if(samples<1)throw new IllegalArgumentException("所选范围没有可复制媒体样本");muxer.stop();stopped=true;control.check();if(output.length()<8||output.length()>20*1024*1024)throw new IOException("实际MP4输出为空或超限");
                int actualTracks;MediaExtractor reopened=new MediaExtractor();try{reopened.setDataSource(output.getAbsolutePath());actualTracks=reopened.getTrackCount();if(actualTracks!=tracks.length())throw new SecurityException("重开输出后的真实轨道数量不同");for(int i=0;i<actualTracks;i++){reopened.selectTrack(i);if(reopened.getSampleSize()<1)throw new SecurityException("重开输出没有实际样本");reopened.unselectTrack(i);}}finally{reopened.release();}
                review.source.requireCurrent(control);byte[] bytes=Files.readAllBytes(output.toPath());return new ProcessedOriginalFile(bytes,videos>0?"video/mp4":"audio/mp4","mp4",review.source.identity,review.source.sha256,review.identity,review.metadata().put("outputSamples",samples).put("firstCopiedSampleUs",first).put("lastCopiedSampleUs",last).put("outputTracks",actualTracks).put("reopened",true).put("actualEndIsSampleBoundary",true));
            }finally{extractor.release();if(muxer!=null){if(started&&!stopped)try{muxer.stop();}catch(Exception ignored){}muxer.release();}Files.deleteIfExists(output.toPath());}
        }
    }
    private static JSONArray tracks(MediaExtractor actual)throws Exception{
        if(actual.getDrmInitData()!=null||actual.getPsshInfo()!=null&&!actual.getPsshInfo().isEmpty())throw new SecurityException("受保护媒体不能重封装");int count=actual.getTrackCount();if(count<1||count>4)throw new IllegalArgumentException("当前支持1至4个真实媒体轨道");JSONArray result=new JSONArray();for(int i=0;i<count;i++){MediaFormat f=actual.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(!Set.of("video/avc","video/hevc","audio/mp4a-latm").contains(mime)||!f.containsKey(MediaFormat.KEY_DURATION)||f.getLong(MediaFormat.KEY_DURATION)<=0)throw new IllegalArgumentException("当前支持MP4中的H.264/H.265与AAC；不静默丢弃字幕、额外数据、未知或无时长轨道");JSONObject track=new JSONObject().put("index",i).put("mimeType",mime).put("durationUs",f.getLong(MediaFormat.KEY_DURATION));for(String key:new String[]{MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT,MediaFormat.KEY_ROTATION})if(f.containsKey(key))track.put(key,f.getInteger(key));result.put(track);}return result;
    }
    private static String startAlignment(JSONArray tracks)throws Exception{
        for(int i=0;i<tracks.length();i++)if(tracks.getJSONObject(i).getString("mimeType").startsWith("video/"))return "previous_video_sync_sample";
        return "previous_audio_sample";
    }
    /** Present only the inspected/reviewed alignment; unknown receipts do not imply video. */
    public static String startAlignmentLabel(JSONObject metadata){
        return switch(metadata.optString("startAlignment")){case "previous_video_sync_sample"->"视频同步帧";case "previous_audio_sample"->"音频样本";default->"媒体样本";};
    }
    private static final class Original implements AutoCloseable {
        final File file,directory;
        Original(OwnerFilePreviewSource source,File privateCache,RunControl control)throws Exception{
            byte[] bytes=source.bytes(control);try{if(bytes.length<12||bytes[4]!='f'||bytes[5]!='t'||bytes[6]!='y'||bytes[7]!='p')throw new IllegalArgumentException("此入口只接受实际MP4容器，未从URL或播放列表加载其他资源");directory=new File(privateCache,"original-media-processing");if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法准备原媒体处理缓存");File[] old=directory.listFiles();if(old==null||old.length>8)throw new IOException("原媒体处理缓存未释放，请明确核对清理后重试");file=File.createTempFile("trim-source-",".mp4",directory);try{Files.write(file.toPath(),bytes);source.requireCurrent(control);}catch(Exception failure){Files.deleteIfExists(file.toPath());throw failure;}}finally{Arrays.fill(bytes,(byte)0);}
        }
        public void close()throws Exception{Files.deleteIfExists(file.toPath());}
    }
}
