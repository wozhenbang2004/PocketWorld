package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneActionEffectsTest {
    static JSONObject j(Object... values)throws Exception {JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}
    static JSONObject node(int window,int path,String text)throws Exception{return j("id","n"+window+"_"+path,"windowId",window,"path",new JSONArray().put(path),"role","text","resourceId","app:id/value","text",text,"identityFingerprint",text,"visible",true,"enabled",true,"focused",false,"password",false,"bounds",new JSONArray().put(0).put(0).put(100).put(100));}
    static JSONObject observation(String id,String text)throws Exception {return j("ok",true,"observationId",id,"packageName","app","nodes",new JSONArray().put(node(9,0,text)),"windows",new JSONArray().put(j("windowId",9,"layer",0,"type",1,"active",true)),"observationSources",j("treeAvailable",true,"treeAdequate",true),"truncated",false);}
    static final class Clock implements PhoneActionEffects.Timing {
        long now;Runnable onPause;
        public long now(){return now;}
        public void pause(long ms,RunControl c,long revision,PhoneUiSignals feed,PhoneUiSignals.Stamp stamp,Set<Integer> windows)throws Exception {assertTrue(ms>=0&&ms<=250);c.check();now+=ms;if(onPause!=null){Runnable callback=onPause;onPause=null;callback.run();}}
    }
    @Test public void exactReadbackDoesNotAcceptSubstringOrWhitespaceAndMasksPrivateInput()throws Exception {
        assertTrue(PhoneActionEffects.inputReadback("中文🙂","中文🙂",false).getBoolean("exactMatch"));assertFalse(PhoneActionEffects.inputReadback("prefix123suffix","123",false).getBoolean("exactMatch"));assertFalse(PhoneActionEffects.inputReadback("123 456","123456",false).getBoolean("exactMatch"));
        JSONObject secret=PhoneActionEffects.inputReadback("secret","secret",true);assertTrue(secret.isNull("text"));assertTrue(secret.isNull("exactMatch"));assertFalse(secret.toString().contains("secret"));assertFalse(secret.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void fullValueCanMatchBeyondPresentationLimitWithoutSplittingUnicode()throws Exception {
        String value="x".repeat(511)+"🙂"+"rest".repeat(1000);JSONObject read=PhoneActionEffects.inputReadback(value,value,false);assertTrue(read.getBoolean("exactMatch"));assertTrue(read.getBoolean("textTruncated"));assertEquals(511,read.getString("text").length());assertEquals(value.length(),read.getInt("textChars"));assertFalse(read.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void delayedTreeChangeBecomesStableWithoutAnyInputOrImageCall()throws Exception {
        RunControl c=new RunControl();Clock clock=new Clock();AtomicInteger reads=new AtomicInteger();JSONObject before=observation("before","old");PhoneActionEffects.Result out=PhoneActionEffects.await(before,9,()->observation("after"+reads.incrementAndGet(),clock.now<400?"old":"new"),c,c.pauseRevision(),null,clock);
        assertEquals("stable_observed_scope",out.evidence.getString("stability"));assertTrue(out.evidence.getBoolean("metadataOnly"));assertFalse(out.evidence.getBoolean("verifiedBusinessSuccess"));assertTrue(reads.get()>=3);assertEquals("change_observed",out.evidence.getJSONObject("observed").getString("status"));
    }
    @Test public void unrelatedWindowAnimationDoesNotDestabilizeTheActualTargetWindow()throws Exception {
        RunControl c=new RunControl();Clock clock=new Clock();JSONObject before=observation("before","same");before.getJSONArray("nodes").put(node(10,0,"clock0"));AtomicInteger n=new AtomicInteger();PhoneActionEffects.Result out=PhoneActionEffects.await(before,9,()->{JSONObject o=observation("after","same");o.getJSONArray("nodes").put(node(10,0,"clock"+n.incrementAndGet()));return o;},c,c.pauseRevision(),null,clock);
        assertEquals("stable_observed_scope",out.evidence.getString("stability"));assertTrue(out.evidence.getLong("elapsedMs")<PhoneActionEffects.WAIT_MS);assertEquals("no_change_in_readable_tree_not_no_effect",out.evidence.getJSONObject("observed").getString("status"));
    }
    @Test public void unchangedOrPartialTreeNeverBecomesNoEffectOrBusinessSuccess()throws Exception {
        JSONObject before=observation("before","same"),after=observation("after","same");JSONObject noChange=PhoneActionEffects.diff(before,after,9);assertEquals("no_change_in_readable_tree_not_no_effect",noChange.getString("status"));assertFalse(noChange.getBoolean("verifiedBusinessSuccess"));after.put("truncated",true);assertEquals("unknown_partial_tree",PhoneActionEffects.diff(before,after,9).getString("status"));
        before.getJSONObject("observationSources").put("treeAvailable",false);AtomicInteger reads=new AtomicInteger();PhoneActionEffects.Result out=PhoneActionEffects.await(before,9,()->{reads.incrementAndGet();return after;},new RunControl(),0,null,new Clock());assertEquals(0,reads.get());assertEquals("before_tree_unavailable",out.evidence.getString("stability"));
        JSONObject empty=observation("empty","");empty.put("nodes",new JSONArray());PhoneActionEffects.Result noBaseline=PhoneActionEffects.await(empty,null,()->{reads.incrementAndGet();return after;},new RunControl(),0,null,new Clock());assertEquals(0,reads.get());assertEquals("before_tree_unavailable",noBaseline.evidence.getString("stability"));
    }
    @Test public void fullNativeIdentityChangesPastClippedTextRemainObservable()throws Exception {
        JSONObject before=observation("before","same clipped prefix"),after=observation("after","same clipped prefix");before.getJSONArray("nodes").getJSONObject(0).put("identityFingerprint","native-full-a");after.getJSONArray("nodes").getJSONObject(0).put("identityFingerprint","native-full-b");after.put("treeCoverage",j("bodyTruncated",true));JSONObject diff=PhoneActionEffects.diff(before,after,9);assertEquals("change_observed",diff.getString("status"));assertEquals(1,diff.getInt("nativeIdentityFingerprintChanged"));assertTrue(diff.getBoolean("textMayBeClipped"));
    }
    @Test public void continuousChangesStopAtBoundAndPauseNeverWaitsForAResumeOrReadsAgain()throws Exception {
        RunControl c=new RunControl();Clock clock=new Clock();AtomicInteger reads=new AtomicInteger();PhoneActionEffects.Result noisy=PhoneActionEffects.await(observation("before","0"),9,()->observation("after",String.valueOf(reads.incrementAndGet())),c,0,null,clock);assertTrue(noisy.evidence.getInt("samples")<=PhoneActionEffects.MAX_SAMPLES);assertTrue(noisy.evidence.getLong("elapsedMs")<=PhoneActionEffects.WAIT_MS);assertFalse(noisy.evidence.getBoolean("verifiedBusinessSuccess"));
        Clock paused=new Clock();paused.onPause=c::pause;AtomicInteger actual=new AtomicInteger();PhoneActionEffects.Result stopped=PhoneActionEffects.await(observation("b","x"),9,()->{actual.incrementAndGet();return observation("a","x");},c,0,null,paused);assertEquals("paused_or_changed",stopped.evidence.getString("stability"));assertEquals(1,actual.get());c.resume();assertEquals(1,actual.get());
    }
    @Test public void malformedWindowPathCannotMasqueradeAsStableInputEvidence()throws Exception {
        JSONObject bad=observation("old","x");bad.getJSONArray("nodes").getJSONObject(0).remove("path");AtomicInteger reads=new AtomicInteger();PhoneActionEffects.Result out=PhoneActionEffects.await(bad,9,()->{reads.incrementAndGet();return observation("new","x");},new RunControl(),0,null,new Clock());assertEquals(0,reads.get());assertEquals("before_window_path_unavailable",out.evidence.getString("stability"));
    }
}
