package dev.pocketengine.phone;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.access.*;
import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport;
import dev.pocketengine.phone.observation.PhoneExtensionTools;
import dev.pocketengine.phone.setup.ClipboardRestoration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneBoundaryTest {
    private final AppCopyKey main=new AppCopyKey("example.notes",0),work=new AppCopyKey("example.notes",10);
    @Test public void sharingCannotAuthorizeAutomationAndUnknownOwnerIsDenied(){
        PhoneAccessPolicy.State policy=new PhoneAccessPolicy.State(3,true,Map.of(main,new PhoneAccessPolicy.Rule(false,true)));
        policy.require(Set.of(main),true,PhoneAccessPolicy.Use.OWNER_SHARE);
        assertThrows(PhoneAccessPolicy.Denied.class,()->policy.require(Set.of(main),true,PhoneAccessPolicy.Use.AUTOMATION));
        assertThrows(PhoneAccessPolicy.Denied.class,()->policy.require(Set.of(),false,PhoneAccessPolicy.Use.AUTOMATION));
        policy.require(Set.of(work),true,PhoneAccessPolicy.Use.AUTOMATION);
    }
    @Test public void missingUserNeverDefaultsToMainProfile()throws Exception {
        JSONObject ownership=new JSONObject().put("status","observed").put("owned",new JSONArray().put(new JSONObject().put("packageName","example.notes")));
        assertFalse(PhoneOwnerPackages.fromOwnership(ownership,"owned").known());
        ownership.getJSONArray("owned").getJSONObject(0).put("userId",10);PhoneOwnerPackages actual=PhoneOwnerPackages.fromOwnership(ownership,"owned");assertTrue(actual.known());assertEquals(Set.of(work),actual.owners());
    }
    @Test public void sameCopyCannotBeClaimedByTwoTasksAndStaleReleaseCannotFreeReplacement()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();assertEquals(PhoneAppResources.Result.ACQUIRED,resources.reserve("a",main,(k,o)->true));assertEquals(PhoneAppResources.Result.BUSY,resources.reserve("b",main,(k,o)->true));assertEquals(PhoneAppResources.Result.ACQUIRED,resources.reserve("b",work,(k,o)->true));resources.releaseOwner("a");assertEquals(PhoneAppResources.Result.ACQUIRED,resources.reserve("c",main,(k,o)->true));resources.releaseOwner("a");assertEquals("c",resources.holder(main));
    }
    @Test public void actualScreenConflictCannotBeOverriddenByEmptyResourceMap()throws Exception {PhoneAppResources resources=new PhoneAppResources();assertEquals(PhoneAppResources.Result.BUSY,resources.reserve("a",main,(k,o)->false));assertNull(resources.holder(main));}
    @Test public void releaseWakesTheOriginalWaitingRunWithoutChangingItsIdentity()throws Exception {PhoneAppResources resources=new PhoneAppResources();resources.reserve("first",main,(k,o)->true);RunControl waiting=new RunControl();waiting.bindHostRun("phone-task","native-run",1);CountDownLatch probed=new CountDownLatch(1);AtomicReference<Object> outcome=new AtomicReference<>();Thread thread=new Thread(()->{try{outcome.set(resources.await("second",main,2000,waiting,(k,o)->{probed.countDown();return true;}));}catch(Exception failure){outcome.set(failure);}});thread.start();assertTrue(probed.await(1,TimeUnit.SECONDS));resources.releaseOwner("first");thread.join(1500);assertFalse(thread.isAlive());assertEquals(PhoneAppResources.Result.ACQUIRED,outcome.get());assertEquals("second",resources.holder(main));assertTrue(waiting.matchesHostRun("phone-task","native-run",1));}
    @Test public void actualUseTimeoutDoesNotClaimAResource()throws Exception {PhoneAppResources resources=new PhoneAppResources();assertEquals(PhoneAppResources.Result.TIMED_OUT,resources.await("first",main,20,new RunControl(),(k,o)->false));assertNull(resources.holder(main));}
    @Test public void waitCycleExitsAndCancellationCleansWaitingRecord()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();resources.reserve("a",main,(k,o)->true);resources.reserve("b",work,(k,o)->true);RunControl a=new RunControl(),b=new RunControl();CountDownLatch probed=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread first=new Thread(()->{try{resources.await("a",work,3000,a,(k,o)->{probed.countDown();return true;});}catch(Throwable stopped){failure.set(stopped);}});first.start();assertTrue(probed.await(1,TimeUnit.SECONDS));
        assertEquals(PhoneAppResources.Result.DEADLOCK,resources.await("b",main,200,b,(k,o)->true));a.cancel();first.join(1500);assertFalse(first.isAlive());assertTrue(failure.get() instanceof InterruptedException);resources.releaseOwner("a");assertEquals(PhoneAppResources.Result.ACQUIRED,resources.await("b",main,200,b,(k,o)->true));
    }
    private static final class Fixture implements ClipboardRestoration.Storage,ClipboardRestoration.Clipboard {
        String durable,current="owner-clip";boolean denyRestore,denySave;
        public String read(){return durable;}public void write(String value){if(denySave)throw new IllegalStateException();durable=value;}public void clear(){durable=null;}
        public String snapshot(){return current;}public String replacement(String text){return "our:"+text;}public void install(String value){if(denyRestore&&value.equals("owner-clip"))throw new IllegalStateException();current=value;}
    }
    @Test public void clipboardOriginalIsRestoredOnCancellation()throws Exception {Fixture io=new Fixture();ClipboardRestoration restore=new ClipboardRestoration(io,io);RunControl control=new RunControl();assertThrows(InterruptedException.class,()->restore.paste("中文",control,()->{control.cancel();control.check();return true;}));assertEquals("owner-clip",io.current);assertNull(io.durable);}
    @Test public void clipboardNeverChangesBeforeDurableOriginalIsSaved()throws Exception {Fixture io=new Fixture();io.denySave=true;JSONObject result=new ClipboardRestoration(io,io).paste("x",new RunControl(),()->{fail();return true;});assertFalse(result.getBoolean("accepted"));assertEquals("owner-clip",io.current);}
    @Test public void laterOwnerClipboardIsNeverOverwritten()throws Exception {Fixture io=new Fixture();ClipboardRestoration restore=new ClipboardRestoration(io,io);restore.paste("x",new RunControl(),()->{io.current="new-owner-clip";return true;});assertEquals("new-owner-clip",io.current);assertNull(io.durable);}
    @Test public void failedRestoreKeepsDurableRecoveryAndNeverPastesAgain()throws Exception {Fixture io=new Fixture();io.denyRestore=true;ClipboardRestoration restore=new ClipboardRestoration(io,io);JSONObject receipt=restore.paste("x",new RunControl(),()->true);assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("clipboardRestored"));assertNotNull(io.durable);JSONObject blocked=restore.paste("again",new RunControl(),()->{fail();return true;});assertEquals("clipboard_recovery_pending",blocked.getString("error"));io.denyRestore=false;assertTrue(restore.recover());assertEquals("owner-clip",io.current);assertNull(io.durable);}
    @Test public void defaultDiagnosticCannotCarryRawTextTokensPairCodeOrUrl()throws Exception {String secret="SECRET_PRIVATE_482911";JSONObject display=new JSONObject().put("message",secret).put("token",secret).put("setupCommands",secret).put("sessionId",secret).put("adb",new JSONObject().put("phase",secret).put("pairingCode","482911"));JSONObject report=PhoneDiagnosticReport.create(new JSONObject().put("commit","a".repeat(40)),new JSONObject().put("sdkInt",35).put("abi","arm64-v8a"),display,new JSONObject().put("nodes",secret),new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("id","tree").put("state","passed").put("detail",secret))));String text=report.toString();assertFalse(text.contains(secret));assertFalse(text.contains("482911"));assertFalse(text.contains("setupCommands"));assertFalse(text.contains("sessionId"));assertEquals("a".repeat(40),report.getString("sourceCommit"));}
    @Test public void diagnosticCheckNamesAndReasonsAreClosedEnumsRatherThanTextChannels()throws Exception {JSONObject report=PhoneDiagnosticReport.create(new JSONObject(),new JSONObject(),new JSONObject(),new JSONObject(),new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("id","private_secret_482911").put("state","passed")).put(new JSONObject().put("id","frame").put("state","unknown").put("reasonCode","private_secret_482911")).put(new JSONObject().put("id","frame").put("state","passed"))));assertEquals(1,report.getJSONArray("selfCheck").length());assertTrue(report.getJSONArray("selfCheck").getJSONObject(0).isNull("reasonCode"));assertFalse(report.toString().contains("private_secret_482911"));}
    @Test public void compatibilityInputRequiresExplicitPermissionAndNoExtraKeys()throws Exception {JSONObject p=new JSONObject().put("observationId","fresh").put("nodeId","n1").put("text","中文");assertThrows(IllegalArgumentException.class,()->PhoneExtensionTools.validate("phone_paste_text",p));p.put("allowClipboardFallback",true);PhoneExtensionTools.validate("phone_paste_text",p);p.put("shell","am start");assertThrows(IllegalArgumentException.class,()->PhoneExtensionTools.validate("phone_paste_text",p));}
    @Test public void gesturesRejectFractionalCoordinatesExtraFingersAndOverflow()throws Exception {JSONObject p=new JSONObject().put("observationId","fresh").put("durationMs",500).put("paths",new JSONArray("[[[0,0],[1000,1000]],[[1000,0],[0,1000]]]"));PhoneExtensionTools.validate("phone_gesture",p);JSONArray point=p.getJSONArray("paths").getJSONArray(0).getJSONArray(0);for(Object invalid:new Object[]{0.5,4294967296L,-4294967296L,"0"}){point.put(0,invalid);assertThrows(IllegalArgumentException.class,()->PhoneExtensionTools.validate("phone_gesture",p));}point.put(0,0);p.getJSONArray("paths").put(new JSONArray("[[0,0],[100,100]]"));assertThrows(IllegalArgumentException.class,()->PhoneExtensionTools.validate("phone_gesture",p));}
}
