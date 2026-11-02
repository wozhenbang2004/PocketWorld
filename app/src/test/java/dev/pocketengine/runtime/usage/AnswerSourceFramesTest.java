package dev.pocketengine.runtime.usage;
import dev.pocketengine.core.RunControl;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class AnswerSourceFramesTest {
 private static final String TASK="10000000-0000-0000-0000-000000000001",RUN="20000000-0000-0000-0000-000000000002";
 private static final String PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5n8AAAAASUVORK5CYII=";
 private static JSONObject image()throws Exception{return new JSONObject().put("ok",true).put("observationId","original-observation").put("imageBase64",PNG).put("imageMimeType","image/png");}
 @Test public void onlySameObservedBytesCanBeReadAndEvictedIsNotCurrentScreen()throws Exception {Path dir=Files.createTempDirectory("pw-frame-test");AnswerSourceFrames store=new AnswerSourceFrames(dir);AnswerSourceFrames.install(store);try{RunControl control=new RunControl();control.bindHostRun(TASK,RUN,1);JSONObject observed=image();AnswerSourceFrames.retain(TASK,control,observed);JSONObject ref=observed.getJSONObject("retainedFrame");assertEquals("data:image/png;base64,"+PNG,store.read(ref).getString("dataUrl"));JSONObject foreign=new JSONObject(ref.toString()).put("observationId","new-observation");assertThrows(SecurityException.class,()->store.read(foreign));Files.delete(dir.resolve(ref.getString("file")));assertFalse(store.read(ref).getBoolean("available"));assertFalse(store.read(ref).has("dataUrl"));}finally{AnswerSourceFrames.install(null);try(var files=Files.list(dir)){files.forEach(p->{try{Files.deleteIfExists(p);}catch(Exception failure){throw new RuntimeException(failure);}});}Files.delete(dir);}}
 @Test public void cancelledOrMetadataOnlyObservationDoesNotRetainAFrame()throws Exception {Path dir=Files.createTempDirectory("pw-frame-stop-test");AnswerSourceFrames store=new AnswerSourceFrames(dir);AnswerSourceFrames.install(store);try{RunControl control=new RunControl();control.bindHostRun(TASK,RUN,1);JSONObject metadata=new JSONObject().put("observationId","metadata-only");AnswerSourceFrames.retain(TASK,control,metadata);assertFalse(metadata.has("retainedFrame"));control.cancel();JSONObject observed=image();AnswerSourceFrames.retain(TASK,control,observed);assertFalse(observed.has("retainedFrame"));try(var files=Files.list(dir)){assertEquals(0,files.count());}}finally{AnswerSourceFrames.install(null);Files.delete(dir);}}
}
