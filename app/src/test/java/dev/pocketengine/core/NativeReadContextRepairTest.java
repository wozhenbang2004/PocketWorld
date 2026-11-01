package dev.pocketengine.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeReadContextRepairTest {
 private static final String TASK=UUID.randomUUID().toString(),RUN=UUID.randomUUID().toString();
 private static RunControl control(){RunControl c=new RunControl();c.bindHostRun(TASK,RUN,1);return c;}
 private static NativeReadContextRepair.Identity id(){return new NativeReadContextRepair.Identity(TASK,RUN,1);}
 private static String sha(String s)throws Exception{byte[] value=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
 private static JSONArray messages(String tool,String content)throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","unchanged system")).put(new JSONObject().put("role","user").put("content","unchanged owner")).put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","actual-call").put("type","function").put("function",new JSONObject().put("name",tool).put("arguments","{}"))))).put(new JSONObject().put("role","tool").put("tool_call_id","actual-call").put("content",content).put("actualMarker","original metadata"));}
 private static NativeReadContextRepair repair(RunControl c,String old,String replacement,RunControl.AuthorityCheck current,NativeReadContextRepair.FinalCheck check)throws Exception{return NativeReadContextRepair.capture(c,id(),List.of(new NativeReadContextRepair.Replacement("actual-call",sha(old),replacement)),current,check);}
 @Test public void oneNativeCandidateChangesOnlyThePairedToolContentAndNeverTheOriginalHistory()throws Exception{
  RunControl original=control();JSONArray raw=messages("browser_task_observe","safe delta");AtomicInteger checked=new AtomicInteger();try(var repair=repair(original,"safe delta","safe full",()->{},(finalMessages,c)->{assertSame(original,c);assertEquals("safe full",finalMessages.getJSONObject(3).getString("content"));checked.incrementAndGet();})){
   JSONArray next=repair.consume(raw,original);assertEquals("safe delta",raw.getJSONObject(3).getString("content"));assertEquals("safe full",next.getJSONObject(3).getString("content"));assertEquals("original metadata",next.getJSONObject(3).getString("actualMarker"));assertEquals(raw.getJSONObject(0).toString(),next.getJSONObject(0).toString());assertEquals(raw.getJSONObject(1).toString(),next.getJSONObject(1).toString());assertEquals(raw.getJSONObject(2).toString(),next.getJSONObject(2).toString());repair.validateFinal(next,original);assertEquals(1,checked.get());assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.consume(raw,original));assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.validateFinal(next,original));
  }
 }
 @Test public void wrongContentForeignToolsUnpairedIdsAndDuplicateRowsCannotBeRewritten()throws Exception{
  for(String kind:List.of("wrong-hash","foreign-tool","unpaired","duplicate-row")){RunControl original=control();JSONArray raw=messages(kind.equals("foreign-tool")?"another_tool":"browser_task_observe","safe delta");if(kind.equals("wrong-hash"))raw.getJSONObject(3).put("content","different actual bytes");if(kind.equals("unpaired"))raw.getJSONObject(3).put("tool_call_id","not-the-native-call");if(kind.equals("duplicate-row"))raw.put(new JSONObject(raw.getJSONObject(3).toString()));try(var repair=repair(original,"safe delta","safe full",()->{},(m,c)->{})){assertThrows(kind,NativeReadContextRepair.Unavailable.class,()->repair.consume(raw,original));assertNotEquals("safe full",raw.getJSONObject(3).getString("content"));}}
 }
 @Test public void sameStringsOnReplacementControlAndPauseResumeCannotRefreshNativeRepair()throws Exception{
  RunControl original=control(),foreign=control();JSONArray raw=messages("browser_task_observe","delta");try(var repair=repair(original,"delta","full",()->{},(m,c)->{})){assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.consume(raw,foreign));original.pause();original.resume();assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.consume(raw,original));}
 }
 @Test public void nativeGuardAndFinalCheckNeverRunUnderCarrierMonitorOrBlockOwnerClose()throws Exception{
  RunControl original=control();AtomicReference<NativeReadContextRepair> ref=new AtomicReference<>();AtomicBoolean stop=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
  try{NativeReadContextRepair r=repair(original,"delta","full",()->{NativeReadContextRepair current=ref.get();if(current!=null){assertFalse(Thread.holdsLock(current));if(stop.get()){entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("native guard blocked owner close");}}},(m,c)->{assertFalse(Thread.holdsLock(ref.get()));});ref.set(r);stop.set(true);Future<?> result=worker.submit(()->{assertThrows(NativeReadContextRepair.Unavailable.class,()->r.consume(messages("browser_task_observe","delta"),original));});assertTrue(entered.await(2,TimeUnit.SECONDS));r.close();release.countDown();result.get(2,TimeUnit.SECONDS);}
  finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
 }
 @Test public void aSecondBudgetThatStillLacksAValidBaseFailsInsteadOfRepairingOrSendingAgain()throws Exception{
  RunControl original=control();AtomicInteger checks=new AtomicInteger();JSONArray raw=messages("browser_task_observe","delta");try(var repair=repair(original,"delta","full",()->{},(m,c)->{checks.incrementAndGet();throw new NativeReadContextRepair.Unavailable();})){
   JSONArray replaced=repair.consume(raw,original);assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.validateFinal(replaced,original));assertEquals(1,checks.get());assertThrows(NativeReadContextRepair.Unavailable.class,()->repair.consume(raw,original));assertEquals("delta",raw.getJSONObject(3).getString("content"));
  }
 }
 @Test public void boundsAndDuplicateReplacementIdsFailBeforeTheyCanTouchAnyRequest()throws Exception{
  RunControl original=control();assertThrows(IllegalArgumentException.class,()->new NativeReadContextRepair.Replacement("call","not-a-hash","full"));assertThrows(IllegalArgumentException.class,()->new NativeReadContextRepair.Replacement("call","a".repeat(64),"x".repeat(NativeReadContextRepair.MAX_BYTES+1)));
  var item=new NativeReadContextRepair.Replacement("same",sha("delta"),"full");assertThrows(IllegalArgumentException.class,()->NativeReadContextRepair.capture(original,id(),List.of(item,item),()->{},(m,c)->{}));assertThrows(IllegalArgumentException.class,()->NativeReadContextRepair.capture(original,id(),Collections.emptyList(),()->{},(m,c)->{}));
 }
}
