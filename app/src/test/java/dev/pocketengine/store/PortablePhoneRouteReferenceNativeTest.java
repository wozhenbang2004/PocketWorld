package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.runtime.routes.NativePhoneRouteReferenceHost;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQLite/owner-result/forget fixture; only the device-effect receipt is synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PortablePhoneRouteReferenceNativeTest {
    @Test public void exactConfirmedSourceExportsOnlyReferenceAndDoesNotRegisterAnotherRoute()throws Exception {
        try(var f=new PhoneRouteSourceNativeTest.Fixture()){
            JSONObject selected=f.source(true),task=f.tasks.get(selected.getString("id"),false);int before=f.tasks.phoneRouteTaskIds().length();
            var host=new NativePhoneRouteReferenceHost(f.tasks,f.bridge);try(var review=host.inspect(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl())){
                ByteArrayOutputStream output=new ByteArrayOutputStream();JSONObject receipt=review.export(output,new RunControl());String body=output.toString(StandardCharsets.UTF_8);
                assertEquals(review.view().getString("text"),body);assertFalse(body.contains(task.getString("id")));assertFalse(body.contains(task.getString("runId")));assertFalse(body.contains(task.getString("goal")));assertFalse(body.contains("hostUserIdentity"));
                assertFalse(receipt.getBoolean("executionAuthorized"));assertFalse(receipt.getBoolean("replayAllowed"));assertEquals(before,f.tasks.phoneRouteTaskIds().length());assertNotNull(f.tasks.phoneRouteSourceMetadata(task.getString("id")));
            }
        }
    }
    @Test public void missingConfirmationWrongRunOrEpochCannotAcquireAnExportReview()throws Exception {
        try(var f=new PhoneRouteSourceNativeTest.Fixture()){
            JSONObject pending=f.source(false),task=f.tasks.get(pending.getString("id"),false);var host=new NativePhoneRouteReferenceHost(f.tasks,f.bridge);
            assertThrows(IllegalStateException.class,()->host.inspect(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl()));
            JSONObject selected=f.source(true),confirmed=f.tasks.get(selected.getString("id"),false);
            assertThrows(SecurityException.class,()->host.inspect(confirmed.getString("id"),"later-run",confirmed.getInt("coordinatorEpoch"),new RunControl()));
            assertThrows(SecurityException.class,()->host.inspect(confirmed.getString("id"),confirmed.getString("runId"),confirmed.getInt("coordinatorEpoch")+1,new RunControl()));
        }
    }
    @Test public void sourceRetirementWithdrawalAndClosedReviewRejectBeforeWritingBytes()throws Exception {
        for(String change:new String[]{"retired","withdrawn","closed"})try(var f=new PhoneRouteSourceNativeTest.Fixture()){
            JSONObject selected=f.source(true),task=f.tasks.get(selected.getString("id"),false);var host=new NativePhoneRouteReferenceHost(f.tasks,f.bridge);var review=host.inspect(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl());
            if(change.equals("retired"))f.tasks.forgetMemorySourceSnapshot(SourceRef.fromJson(f.tasks.phoneRouteSourceMetadata(task.getString("id")).getJSONObject("sourceRef")));
            else if(change.equals("closed"))review.close();
            else f.tasks.event(task.getString("id"),new JSONObject().put("type","phone_route_withdrawn").put("routeId",selected.getJSONObject("candidate").getString("routeId")).put("digest",selected.getJSONObject("candidate").getString("digest")));
            ByteArrayOutputStream output=new ByteArrayOutputStream();Class<? extends Exception> denial=change.equals("retired")?IllegalStateException.class:SecurityException.class;assertThrows(denial,()->review.export(output,new RunControl()));assertEquals(0,output.size());review.close();
        }
    }
}
