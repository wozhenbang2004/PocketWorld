package dev.pocketengine.store;
import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import java.nio.file.*;
import java.util.UUID;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HarnessGatewayClaimsNativeTest {
    private TaskStore tasks;private RunControl original;private JSONObject task,descriptor;
    private SQLiteDatabase db()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    @Before public void open()throws Exception {tasks=new TaskStore(RuntimeEnvironment.getApplication());task=tasks.startAttempt(tasks.createCapabilityTask("Original gateway claims task","").getString("id"),"capabilities",4);original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));descriptor=new JSONObject().put("leaseId",UUID.randomUUID().toString()).put("clientBindingId",UUID.randomUUID().toString()).put("generation",UUID.randomUUID().toString()).put("environmentId","fixture").put("environmentBinding","a".repeat(64)).put("bindingId","controlled-codex").put("profileDigest","b".repeat(64)).put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch")).put("audience","OWNER_PRIVATE").put("purpose","EXTERNAL_RELAY").put("expiresAt",System.currentTimeMillis()+30000);}
    @After public void close()throws Exception {if(tasks!=null&&db().isOpen())db().close();}
    @Test public void newDatabaseAndOriginalAtomicClaimNeverRedispatchAfterAckLossOrDigestChange()throws Exception {
        assertEquals(7,db().getVersion());var authority=tasks.createHarnessGatewayClaims(original,descriptor);String lease=descriptor.getString("leaseId"),request=UUID.randomUUID().toString(),digest="c".repeat(64);
        var first=authority.claim(lease,request,digest);assertTrue(first.dispatchAllowed());var same=authority.claim(lease,request,digest);assertFalse(same.dispatchAllowed());assertEquals(first.claimId(),same.claimId());assertThrows(SecurityException.class,()->authority.claim(lease,request,"d".repeat(64)));
        authority.outcome(lease,request,first.claimId(),false);assertThrows(SecurityException.class,()->authority.claim(lease,UUID.randomUUID().toString(),digest));
        JSONObject replacement=new JSONObject(descriptor.toString()).put("leaseId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->tasks.createHarnessGatewayClaims(original,replacement));
        try(Cursor rows=db().rawQuery("SELECT state FROM harness_gateway_leases WHERE lease_id=?",new String[]{lease})){assertTrue(rows.moveToFirst());assertEquals("REQUIRES_RECONCILE",rows.getString(0));}
        assertEquals(0,tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void cancellationRevocationAndExpiredOriginalTaskCannotIssueOrRebindClaims()throws Exception {
        var authority=tasks.createHarnessGatewayClaims(original,descriptor);authority.close();assertThrows(SecurityException.class,()->authority.claim(descriptor.getString("leaseId"),UUID.randomUUID().toString(),"e".repeat(64)));
        original.cancel();JSONObject next=new JSONObject(descriptor.toString()).put("leaseId",UUID.randomUUID().toString());assertThrows(InterruptedException.class,()->tasks.createHarnessGatewayClaims(original,next));
        assertEquals(0,tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void restartKeepsOriginalMayHaveDispatchedAsUnknownAndNeverReissuesAuthority()throws Exception {
        var authority=tasks.createHarnessGatewayClaims(original,descriptor);var first=authority.claim(descriptor.getString("leaseId"),"controlled-original-request","f".repeat(64));assertTrue(first.dispatchAllowed());
        TaskStore restarted=new TaskStore(RuntimeEnvironment.getApplication());
        try(Cursor rows=db().rawQuery("SELECT state FROM harness_gateway_claims WHERE lease_id=?",new String[]{descriptor.getString("leaseId")})){assertTrue(rows.moveToFirst());assertEquals("UNKNOWN",rows.getString(0));}
        assertThrows(SecurityException.class,()->authority.claim(descriptor.getString("leaseId"),"controlled-original-request","f".repeat(64)));
        assertNotNull(restarted.get(task.getString("id"),false));
    }
    @Test public void upgradeRealOriginalVersion6DatabasePreservesTaskAndPhysicalReceipt()throws Exception {
        var context=RuntimeEnvironment.getApplication();db().close();context.deleteDatabase("pocket-world.db");Path source=Path.of(System.getProperty("pocket.gateway.evidence"),"original-v6.db");assertTrue("must be generated by the actual frozen v6 TaskStore",Files.exists(source));Files.copy(source,context.getDatabasePath("pocket-world.db").toPath(),StandardCopyOption.REPLACE_EXISTING);
        JSONObject old=new JSONObject(Files.readString(source.resolveSibling("original-v6.json")));tasks=new TaskStore(context);assertEquals(7,db().getVersion());assertNotNull(tasks.get(old.getString("taskId"),false));JSONObject usage=tasks.inferenceUsage(old.getString("taskId"));assertEquals(old.getString("callId"),usage.getJSONArray("receipts").getJSONObject(0).getString("callId"));assertEquals(old.getJSONObject("receipt").toString(),usage.getJSONArray("receipts").getJSONObject(0).toString());
        try(Cursor row=db().rawQuery("SELECT count(*) FROM harness_gateway_claims",null)){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
    }
}
