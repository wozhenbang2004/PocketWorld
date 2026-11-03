package dev.pocketengine.features.attachments;

import static org.junit.Assert.*;
import dev.pocketengine.features.attachments.processing.NativeMediaTrim;
import dev.pocketengine.features.local.project.ProjectSources;
import java.lang.reflect.Constructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Receipt construction with a real generated AAC-only fixture, not extractor/muxer execution.
 * Fixture: ffmpeg lavfi sine=frequency=440:sample_rate=16000:duration=1, AAC 16kbit/s MP4.
 * ffprobe confirms one AAC track, one second, and a sample at 192000us before 200000us.
 * The supplied track descriptions/times test the receipt contract, not Android media parsing. */
public final class NativeMediaTrimReceiptTest {
    private static JSONArray tracks(String... mimes)throws Exception{
        JSONArray tracks=new JSONArray();for(int i=0;i<mimes.length;i++)tracks.put(new JSONObject().put("index",i).put("mimeType",mimes[i]).put("durationUs",1_000_000L));return tracks;
    }
    private static OwnerFilePreviewSource source()throws Exception{
        byte[] bytes;try(var fixture=NativeMediaTrimReceiptTest.class.getResourceAsStream("/media-trim/aac-only.mp4")){assertNotNull("Real generated AAC fixture is required",fixture);bytes=fixture.readAllBytes();}
        JSONObject original=new JSONObject().put("id","fixed-original").put("name","audio-in-mp4.mp4").put("mimeType","video/mp4").put("sizeBytes",bytes.length).put("sha256",ProjectSources.sha256(bytes));
        return new OwnerFilePreviewSource("attachments",original,c->{throw new AssertionError("Receipt construction must not read or process bytes");},c->{throw new AssertionError("Receipt construction must not acquire source authority");});
    }
    private static NativeMediaTrim.Review metadataOnlyReview(OwnerFilePreviewSource source,JSONArray tracks)throws Exception{
        Constructor<NativeMediaTrim.Review> constructor=NativeMediaTrim.Review.class.getDeclaredConstructor(OwnerFilePreviewSource.class,long.class,long.class,long.class,long.class,JSONArray.class);
        constructor.setAccessible(true);return constructor.newInstance(source,200_000L,400_000L,192_000L,1_000_000L,tracks);
    }
    @Test public void aacOnlyReceiptDoesNotInventVideoFromTheContainerMime()throws Exception{
        var review=metadataOnlyReview(source(),tracks("audio/mp4a-latm"));assertEquals("previous_audio_sample",review.metadata().getString("startAlignment"));
    }
    @Test public void severalAudioTracksStillDescribeAnAudioSample()throws Exception{
        var review=metadataOnlyReview(source(),tracks("audio/mp4a-latm","audio/mp4a-latm"));assertEquals("previous_audio_sample",review.metadata().getString("startAlignment"));
    }
    @Test public void videoAndAudioUseTheExistingVideoSyncAlignmentInEitherOrder()throws Exception{
        for(JSONArray tracks:new JSONArray[]{tracks("video/avc","audio/mp4a-latm"),tracks("audio/mp4a-latm","video/hevc")})assertEquals("previous_video_sync_sample",metadataOnlyReview(source(),tracks).metadata().getString("startAlignment"));
    }
    @Test public void correctedReceiptStillBindsTheSameSourceAndRequestedActualTimes()throws Exception{
        var source=source();var review=metadataOnlyReview(source,tracks("audio/mp4a-latm"));var metadata=review.metadata();
        assertSame(source,review.source());assertEquals(source.identity,metadata.getString("sourceIdentity"));assertEquals(source.sha256,metadata.getString("sourceSha256"));
        assertEquals(200_000L,metadata.getLong("requestedStartUs"));assertEquals(400_000L,metadata.getLong("requestedEndUs"));assertEquals(192_000L,metadata.getLong("actualStartUs"));assertFalse(metadata.getBoolean("transcoding"));assertTrue(metadata.getBoolean("allOriginalBytesRetained"));
        assertEquals(review.identity,metadata.remove("planDigest"));assertEquals(review.identity,ProjectSources.digest(metadata));
    }
    @Test public void returnedMetadataCannotChangeTheFrozenReviewOrPlanDigest()throws Exception{
        var review=metadataOnlyReview(source(),tracks("audio/mp4a-latm"));var before=review.metadata();var altered=review.metadata();altered.put("startAlignment","previous_video_sync_sample");altered.getJSONArray("tracks").getJSONObject(0).put("mimeType","video/avc");
        assertEquals(ProjectSources.canonical(before),ProjectSources.canonical(review.metadata()));assertEquals(review.identity,review.metadata().getString("planDigest"));
    }
    @Test public void videoReceiptKeepsItsPreviousCanonicalIdentity()throws Exception{
        var review=metadataOnlyReview(source(),tracks("video/avc"));var previous=review.metadata();previous.remove("planDigest");previous.put("startAlignment","previous_video_sync_sample");assertEquals(review.identity,ProjectSources.digest(previous));
    }
    @Test public void audioReviewPresentationDescribesTheReceiptedAudioSample()throws Exception{
        assertEquals("音频样本",NativeMediaTrim.startAlignmentLabel(metadataOnlyReview(source(),tracks("audio/mp4a-latm")).metadata()));
    }
    @Test public void videoReviewPresentationStillDescribesItsVideoSyncFrame()throws Exception{
        assertEquals("视频同步帧",NativeMediaTrim.startAlignmentLabel(metadataOnlyReview(source(),tracks("audio/mp4a-latm","video/avc")).metadata()));
    }
    @Test public void missingOrUnknownAlignmentDoesNotInventAVideoFrame()throws Exception{
        assertEquals("媒体样本",NativeMediaTrim.startAlignmentLabel(new JSONObject()));assertEquals("媒体样本",NativeMediaTrim.startAlignmentLabel(new JSONObject().put("startAlignment","unrecognized")));
    }
}
