package dev.pocketengine.ui;
import android.Manifest;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.channels.ChannelReceiveService;
import dev.pocketengine.features.channels.ChannelTaskHostTest;
import dev.pocketengine.features.channels.ChannelsModule;
import java.lang.reflect.Field;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Actual native Activity/Controller path; no service/provider is auto-executed by the Intent fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChannelReceivingControllerTest {
 @Before public void setup(){java.security.Security.addProvider(new java.security.Provider("ChannelOwnerKeyStore","1","Synthetic owner-controller keystore"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.FOREGROUND_SERVICE,Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,Manifest.permission.WAKE_LOCK);ServiceInfo service=new ServiceInfo();service.packageName=RuntimeEnvironment.getApplication().getPackageName();service.name=ChannelReceiveService.class.getName();service.applicationInfo=RuntimeEnvironment.getApplication().getApplicationInfo();service.exported=false;org.robolectric.util.ReflectionHelpers.setField(service,"mForegroundServiceType",ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addOrUpdateService(service);}
 private PocketWorldController controller(ComposeActivity activity)throws Exception{Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);return (PocketWorldController)field.get(activity);}
 private ActivityController<ComposeActivity> nativeActivity(){
  ActivityController<ComposeActivity> value=Robolectric.buildActivity(ComposeActivity.class).create().start().resume();
  // This is a native admission/lifecycle fixture, not a Material-resource or visual test.
  // Keep the actual Activity/Controller lifecycle; replace only unrendered product content before attach.
  value.get().setContentView(new android.view.View(value.get()));return value.visible().windowFocusChanged(false);
 }
 private ChannelsModule channels(){return (ChannelsModule)((EngineApplication)RuntimeEnvironment.getApplication()).features.module("channels");}
 private JSONObject profile()throws Exception{return channels().handle("profile.save",new JSONObject().put("name","fixture QQ").put("provider","qq").put("appId","fixture-app").put("secret","fixture-qq-secret").put("enabled",true));}
 private Intent channelIntent(){Intent value;while((value=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService())!=null)if(value.getComponent()!=null&&value.getComponent().getClassName().equals(ChannelReceiveService.class.getName()))return value;return null;}
 @Test public void actualNativeConfirmationCreatesOnlyStartingReceiptAndExactStopBeforeServiceClaimDoesNotCreateTask()throws Exception{
  try(ActivityController<ComposeActivity> activity=nativeActivity()){
   PocketWorldController owner=controller(activity.get());JSONObject row=profile();ChannelReceivingReview review=ChannelReceivingPanelKt.reviewChannelReceiving(channels().snapshot(),List.of(row.getString("id")));assertNull(channelIntent());owner.startChannelReceiving(review,false);assertTrue(owner.getState().getModuleErrors().containsKey("owner.channelReceiving.start"));assertEquals("stopped",channels().backgroundState().getString("status"));assertNull(channelIntent());String request=owner.startChannelReceiving(review,true);JSONObject receipt=owner.getState().getModuleResults().get("owner.channelReceiving.start");assertNotNull(receipt);assertFalse(receipt.getBoolean("foregroundEstablished"));assertEquals(request,owner.getState().getModuleResultInputs().get("owner.channelReceiving.start").getString("_requestId"));Intent started=channelIntent();assertNotNull(started);assertEquals(ChannelReceiveService.START,started.getAction());assertEquals(receipt.getString("sessionId"),started.getStringExtra("sessionId"));assertEquals("starting",channels().backgroundState().getString("status"));assertFalse(channels().backgroundState().getBoolean("foregroundServicePresent"));owner.stopChannelReceiving(receipt.getString("sessionId"));assertEquals("stopped",channels().backgroundState().getString("status"));owner.startChannelReceiving(review,true);String current=channels().backgroundState().getString("sessionId");assertNotEquals(receipt.getString("sessionId"),current);owner.stopChannelReceiving(receipt.getString("sessionId"));assertTrue(owner.getState().getModuleErrors().containsKey("owner.channelReceiving.stop"));assertEquals(current,channels().backgroundState().getString("sessionId"));assertEquals("starting",channels().backgroundState().getString("status"));owner.stopChannelReceiving(current);assertEquals(0,((EngineApplication)RuntimeEnvironment.getApplication()).tasks.list().length());
  }
 }
 @Test public void changedReviewAndStoppedActivityCannotReserveForegroundReceiving()throws Exception{
  try(ActivityController<ComposeActivity> activity=nativeActivity()){
   PocketWorldController owner=controller(activity.get());JSONObject row=profile();String id=row.getString("id");ChannelReceivingReview review=ChannelReceivingPanelKt.reviewChannelReceiving(channels().snapshot(),List.of(id));channels().handle("profile.save",new JSONObject().put("id",id).put("name","changed QQ").put("provider","qq").put("appId","fixture-app").put("enabled",true));owner.startChannelReceiving(review,true);assertTrue(owner.getState().getModuleErrors().get("owner.channelReceiving.start").contains("改变"));assertNull(channelIntent());ChannelReceivingReview current=ChannelReceivingPanelKt.reviewChannelReceiving(channels().snapshot(),List.of(id));activity.pause().stop();owner.startChannelReceiving(current,true);assertTrue(owner.getState().getModuleErrors().containsKey("owner.channelReceiving.start"));assertEquals("stopped",channels().backgroundState().getString("status"));assertNull(channelIntent());
  }
 }
}
