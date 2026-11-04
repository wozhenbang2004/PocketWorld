package dev.pocketengine.bootstrap;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class BootstrapDiagnosticsTest {
 @Test public void messagesPathsAndForeignFramesNeverLeaveCapture() throws Exception {
  Exception inner=new IllegalArgumentException("secret api-token /data/user/private");
  inner.setStackTrace(new StackTraceElement[]{new StackTraceElement("external.Secret","key","/private/token",2),new StackTraceElement("dev.pocketengine.SomeFeature","initialize","/private/location",42)});
  Exception outer=new IllegalStateException("sensitive content",inner);outer.addSuppressed(new Exception("suppressed-secret"));
  String diagnostic=BootstrapDiagnostics.capture(outer).toString();
  for(String secret:new String[]{"api-token","/data/","/private/","external.Secret","sensitive content","suppressed-secret"})assertFalse(diagnostic,diagnostic.contains(secret));
  assertTrue(diagnostic.contains("SomeFeature"));assertTrue(diagnostic.contains("IllegalArgumentException"));
 }
 @Test public void failureKeepsExactActivityAndGateClosedAndRetryClearsDiagnostic() throws Exception {
  BootstrapStatus status=new BootstrapStatus(10);long first=status.beginInitial();status.stage(first,"migrated",10);status.activity(first,"features");status.module(first,"assistant");
  status.diagnose(first,new IllegalStateException("must not show"));status.failed(first,"initialization_failed","failed","retry",true);
  JSONObject view=status.snapshot();assertEquals("features",view.getString("activity"));assertEquals("assistant",view.getString("module"));assertFalse(status.ready());assertFalse(status.complete(first));
  assertEquals(1,view.getJSONObject("diagnostics").getJSONArray("causes").length());long next=status.retry(first,"retry-diagnostics-1");
  status.diagnose(first,new RuntimeException());assertEquals(0,status.snapshot().getJSONObject("diagnostics").length());assertFalse(status.ready());assertEquals(next,status.generation());
 }
 @Test public void snapshotCannotMutateStoredDiagnostics() throws Exception {
  BootstrapStatus status=new BootstrapStatus(10);long first=status.beginInitial();status.diagnose(first,new RuntimeException());status.snapshot().getJSONObject("diagnostics").put("secret","injected");assertFalse(status.snapshot().toString().contains("injected"));
 }
 @Test public void completedModuleCannotMislabelLaterBindingFailure() throws Exception {
  BootstrapStatus status=new BootstrapStatus(10);long generation=status.beginInitial();status.activity(generation,"features");status.module(generation,"memory");status.module(generation,"");status.diagnose(generation,new IllegalStateException());status.failed(generation,"initialization_failed","failed","retry",false);assertEquals("",status.snapshot().getString("module"));assertFalse(status.ready());
 }

}
