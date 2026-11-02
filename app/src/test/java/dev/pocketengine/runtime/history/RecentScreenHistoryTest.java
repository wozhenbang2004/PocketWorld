package dev.pocketengine.runtime.history;

import dev.pocketengine.core.RunControl;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RecentScreenHistoryTest {
    private static final String PIXELS="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private static JSONObject frame()throws Exception{return new JSONObject().put("imageBase64",PIXELS).put("imageMimeType","image/png").put("observationId","action-reference-must-not-return").put("nodes",new JSONArray());}
    @Test public void recallReturnsExactPixelsWithoutAnActionReference()throws Exception {
        RunControl control=new RunControl();try(RecentScreenHistory history=new RecentScreenHistory(control,()->{})){
            JSONObject original=frame();history.retain(original,control);String id=original.getString("historicalScreenId");JSONObject recalled=history.recall(id,control);assertArrayEquals(Base64.getDecoder().decode(PIXELS),Base64.getDecoder().decode(recalled.getString("imageBase64")));assertFalse(recalled.has("observationId"));assertFalse(recalled.getBoolean("coordinateAuthority"));assertEquals(original.getString("historicalScreenHash"),recalled.getString("sha256"));
            assertThrows(IllegalArgumentException.class,()->RecentScreenHistory.validate(RecentScreenHistory.VIEW,new JSONObject().put("screenId",id).put("observationId","o")));
            assertTrue(history.forget(id,control));assertNull(history.recall(id,control));assertThrows(SecurityException.class,()->history.index(new RunControl()));
        }
    }
    @Test public void expiredPrivateAndPausedFramesNeverFallbackToAnotherPicture()throws Exception {
        RunControl control=new RunControl();AtomicLong clock=new AtomicLong(100);try(RecentScreenHistory history=new RecentScreenHistory(control,()->{},clock::get)){
            JSONObject privateFrame=frame().put("secure",true);history.retain(privateFrame,control);assertEquals(0,history.index(control).length());
            JSONObject first=frame();history.retain(first,control);String id=first.getString("historicalScreenId");clock.set(300101);assertNull(history.recall(id,control));
            JSONObject second=frame();history.retain(second,control);control.pause();assertThrows(SecurityException.class,()->history.recall(second.getString("historicalScreenId"),control));control.resume();assertEquals(0,history.index(control).length());
        }
    }
    @Test public void ringIsBoundedAndSourceRevocationStopsReads()throws Exception {
        RunControl control=new RunControl();boolean[] live={true};try(RecentScreenHistory history=new RecentScreenHistory(control,()->{if(!live[0])throw new SecurityException("revoked");})){
            String first="";for(int i=0;i<6;i++){JSONObject f=frame();history.retain(f,control);if(i==0)first=f.getString("historicalScreenId");}assertEquals(4,history.index(control).length());assertNull(history.recall(first,control));live[0]=false;assertThrows(SecurityException.class,()->history.index(control));live[0]=true;assertEquals("A later renewed source cannot revive withdrawn old pixels",0,history.index(control).length());
        }
    }
}
