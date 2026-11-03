package dev.pocketengine.displayhelper;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;
import android.provider.AlarmClock;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.semantic.AndroidSemanticPlatform;
import dev.pocketengine.features.semantic.SemanticPlatform;
import dev.pocketengine.features.semantic.SemanticRequest;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original native helper and PackageManager route; Android services are explicit SDK35 fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DisplayTypedIntentNativeTest {
    private static final String PACKAGE="typed.native.fixture";
    private static final int DISPLAY=7;
    private static Application app(){return RuntimeEnvironment.getApplication();}
    private static final class Gate implements DisplayTypedIntent.Gate {
        int checks,claims;Runnable atClaim=()->{};
        @Override public void check()throws Exception{checks++;}
        @Override public void requireAppFree(String pkg,int user)throws Exception{assertEquals(PACKAGE,pkg);assertEquals(android.os.Process.myUid()/100000,user);claims++;atClaim.run();}
    }
    public static JSONObject installRequest(JSONObject parameters)throws Exception {
        SemanticRequest request=SemanticRequest.parse(parameters);install(request,1,true);
        SemanticPlatform.Target target=new AndroidSemanticPlatform(app(),null,null).discover(request,new RunControl()).get(0);
        return new JSONObject().put("kind",request.kind).put("parameters",request.parameters()).put("target",target.identity())
                .put("userId",android.os.Process.myUid()/100000).put("route",target.route);
    }
    private static void install(SemanticRequest request,int version,boolean browser)throws Exception {
        ApplicationInfo application=new ApplicationInfo();application.packageName=PACKAGE;application.uid=android.os.Process.myUid()/100000*100000+10466;application.enabled=true;
        ActivityInfo activity=new ActivityInfo();activity.packageName=PACKAGE;activity.name=PACKAGE+".Main";activity.applicationInfo=application;activity.enabled=true;activity.exported=true;
        PackageInfo pkg=new PackageInfo();pkg.packageName=PACKAGE;pkg.applicationInfo=application;pkg.activities=new ActivityInfo[]{activity};pkg.firstInstallTime=100;pkg.lastUpdateTime=200;pkg.setLongVersionCode(version);
        pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{3,5,8})});Shadows.shadowOf(app().getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(request.action);filter.addCategory(Intent.CATEGORY_DEFAULT);
        if(request.kind.equals("web")||request.kind.equals("app_link")){filter.addCategory(Intent.CATEGORY_BROWSABLE);filter.addDataScheme("https");if(!browser)filter.addDataAuthority("example.org",null);}
        if(request.kind.equals("share_text"))filter.addDataType("text/plain");
        ResolveInfo match=new ResolveInfo();match.activityInfo=activity;match.filter=filter;match.isDefault=true;match.nonLocalizedLabel="原生入口fixture";
        Shadows.shadowOf(app().getPackageManager()).setResolveInfosForIntent(DisplayTypedIntent.build(request.kind,request.parameters()),List.of(match));
    }
    /** Bridge only for the original Runner/DisplayModule transport fixture; no production alternate entry. */
    public static JSONObject dispatchFixture(Context context,int display,JSONObject request,RunControl control)throws Exception {
        return DisplayTypedIntent.dispatch(context,display,request,new DisplayTypedIntent.Gate(){
            public void check()throws Exception{control.check();if(control.isPaused())throw new SecurityException("fixture_original_run_paused");}
            public void requireAppFree(String pkg,int user)throws Exception{assertEquals(request.getJSONObject("target").getString("packageName"),pkg);assertEquals(android.os.Process.myUid()/100000,user);}
        });
    }
    /** Android migrates ACTION_SEND's EXTRA_TEXT into ClipData during native launch. */
    public static void assertOnlyAuthorizedTextPayload(Intent intent,String original){
        assertEquals(Intent.ACTION_SEND,intent.getAction());assertEquals("text/plain",intent.getType());
        assertEquals(Set.of(Intent.EXTRA_TEXT),intent.getExtras().keySet());assertEquals(original,intent.getStringExtra(Intent.EXTRA_TEXT));
        android.content.ClipData clip=intent.getClipData();assertNotNull(clip);assertEquals(1,clip.getItemCount());
        assertEquals(1,clip.getDescription().getMimeTypeCount());assertEquals("text/plain",clip.getDescription().getMimeType(0));
        android.content.ClipData.Item item=clip.getItemAt(0);assertEquals(original,item.getText().toString());assertNull(item.getHtmlText());assertNull(item.getUri());assertNull(item.getIntent());
        assertNull(intent.getData());assertNull(intent.getSelector());
        assertEquals(0,intent.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
    }
    @Test public void showAlarmsEntersTheExactBackgroundComponentOnceWithoutClaimingAnAlarmExists()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));Gate gate=new Gate();JSONObject receipt=DisplayTypedIntent.dispatch(app(),DISPLAY,request,gate);
        var launched=Shadows.shadowOf(app()).getNextStartedActivityForResult();assertNotNull(launched);assertEquals(AlarmClock.ACTION_SHOW_ALARMS,launched.intent.getAction());
        assertEquals(PACKAGE+"/"+PACKAGE+".Main",launched.intent.getComponent().flattenToString());assertEquals(DISPLAY,launched.options.getInt("android.activity.launchDisplayId",-1));
        assertNull(launched.intent.getExtras());assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK,launched.intent.getFlags());assertEquals(1,gate.claims);assertEquals(3,gate.checks);
        assertTrue(receipt.getBoolean("accepted"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("messageSent"));assertTrue(receipt.getBoolean("requiresPostconditionCheck"));
        assertSame("Robolectric maintains a separate intent/options queue for the same single entry",launched.intent,Shadows.shadowOf(app()).getNextStartedActivity());
        assertNull(Shadows.shadowOf(app()).getNextStartedActivityForResult());assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void installChangeInsideFinalOwnershipGatePreventsNativeEntry()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));Gate gate=new Gate();gate.atClaim=()->{try{install(SemanticRequest.parse(request.getJSONObject("parameters")),2,true);}catch(Exception e){throw new AssertionError(e);}};
        assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,gate));assertEquals(1,gate.claims);assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void profileSerialAndUidCannotBeSubstitutedOrFallBackToTheCallingAccount()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));JSONObject target=request.getJSONObject("target");long serial=target.getLong("profileSerial");
        target.put("profileSerial",serial+1);assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,new Gate()));
        target.put("profileSerial",serial).put("uid",target.getInt("uid")+100000);assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,new Gate()));
        request.put("userId",1);target.put("userId",1);assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,new Gate()));assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void changedHttpsRouteCannotUseAnExplicitComponentToBypassOsAppLinks()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","app_link").put("packageName",PACKAGE).put("url","https://example.org/path"));
        install(SemanticRequest.parse(request.getJSONObject("parameters")),1,false);
        assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,new Gate()));assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
        assertThrows(SecurityException.class,()->DisplayTypedIntent.build("app_link",new JSONObject().put("kind","app_link").put("packageName",PACKAGE).put("url","intent://private")));
    }
    @Test public void cancellationAfterOwnershipCheckStillStopsTheNativeCall()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));RunControl control=new RunControl();
        DisplayTypedIntent.Gate gate=new DisplayTypedIntent.Gate(){public void check()throws Exception{control.check();}public void requireAppFree(String p,int user){control.cancel();}};
        assertThrows(InterruptedException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,gate));assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void staleNativeLeaseAfterFinalPackageReadCannotBecomeALateLaunch()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));int[] checks={0};
        DisplayTypedIntent.Gate gate=new DisplayTypedIntent.Gate(){public void check(){if(++checks[0]==3)throw new SecurityException("original display request expired during native metadata read");}public void requireAppFree(String p,int user){}};
        assertThrows(SecurityException.class,()->DisplayTypedIntent.dispatch(app(),DISPLAY,request,gate));assertEquals(3,checks[0]);assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void exceptionAfterNativeEntryPreservesUnknownEffectAndDisallowsReplay()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","show_alarms"));Context uncertain=new ContextWrapper(app()){
            @Override public void startActivity(Intent intent,Bundle options){super.startActivity(intent,options);throw new IllegalStateException("fixture lost reply after Android entry");}
        };
        JSONObject receipt=DisplayTypedIntent.dispatch(uncertain,DISPLAY,request,new Gate());assertTrue(receipt.isNull("accepted"));assertTrue(receipt.getBoolean("requiresReconciliation"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertNotNull(Shadows.shadowOf(app()).getNextStartedActivity());assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
    }
    @Test public void exactPackageShareHasOnlyTheApprovedTextAndNeverAChooserOrRecipient()throws Exception {
        JSONObject request=installRequest(new JSONObject().put("kind","share_text").put("packageName",PACKAGE).put("text","仅交付这段原文"));
        assertTrue(DisplayTypedIntent.dispatch(app(),DISPLAY,request,new Gate()).getBoolean("accepted"));Intent actual=Shadows.shadowOf(app()).getNextStartedActivity();assertEquals(PACKAGE,actual.getPackage());assertOnlyAuthorizedTextPayload(actual,"仅交付这段原文");
        assertNull(Shadows.shadowOf(app()).getNextStartedActivity());
        assertThrows(SecurityException.class,()->DisplayTypedIntent.build("show_alarms",new JSONObject().put("kind","show_alarms").put("rawIntent","intent://blocked")));
    }
}
