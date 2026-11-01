package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Android framework/shadow evidence only, distinct from device App Links or actual app launch acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class AndroidSemanticPlatformTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final RunControl control=new RunControl();
    private final AndroidSemanticPlatform platform=new AndroidSemanticPlatform(context,null,null);
    private SemanticRequest web() throws Exception {return SemanticRequest.parse(new JSONObject("{kind:'web',url:'https://example.org/a'}"));}
    private void install(SemanticRequest request,boolean exported,boolean browser,boolean signed,int version) throws Exception {
        ApplicationInfo app=new ApplicationInfo();app.packageName="example.browser";app.enabled=true;app.uid=10456;
        ActivityInfo activity=new ActivityInfo();activity.packageName=app.packageName;activity.name="example.browser.Main";activity.enabled=true;activity.exported=exported;activity.applicationInfo=app;
        PackageInfo pkg=new PackageInfo();pkg.packageName=app.packageName;pkg.applicationInfo=app;pkg.activities=new ActivityInfo[]{activity};pkg.setLongVersionCode(version);pkg.lastUpdateTime=version*1000L;
        if(signed) {pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{1,2,3})});}
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(request.action);filter.addCategory(Intent.CATEGORY_DEFAULT);filter.addCategory(Intent.CATEGORY_BROWSABLE);filter.addDataScheme("https");
        if(!browser)filter.addDataAuthority("example.org",null);
        ResolveInfo resolve=new ResolveInfo();resolve.activityInfo=activity;resolve.filter=filter;resolve.nonLocalizedLabel="真实包查询fixture";resolve.isDefault=true;
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(AndroidSemanticPlatform.intent(request),resolve);
    }
    @Test public void frameworkIntentContainsOnlyTypedPublicEntry() throws Exception {
        Intent web=AndroidSemanticPlatform.intent(web());assertEquals(Intent.ACTION_VIEW,web.getAction());assertEquals("https://example.org/a",web.getDataString());assertTrue(web.hasCategory(Intent.CATEGORY_BROWSABLE));assertNull(web.getComponent());assertNull(web.getExtras());
        Intent dial=AndroidSemanticPlatform.intent(SemanticRequest.parse(new JSONObject("{kind:'dial',number:'+86123456'}")));assertEquals(Intent.ACTION_DIAL,dial.getAction());assertEquals("tel:+86123456",dial.getDataString());
        Intent mail=AndroidSemanticPlatform.intent(SemanticRequest.parse(new JSONObject("{kind:'email_draft',to:'a@example.org',subject:'主题',body:'正文'}")));
        assertEquals(Intent.ACTION_SENDTO,mail.getAction());assertEquals("主题",mail.getStringExtra(Intent.EXTRA_SUBJECT));assertArrayEquals(new String[]{"a@example.org"},mail.getStringArrayExtra(Intent.EXTRA_EMAIL));assertEquals(0,mail.getFlags());
    }
    @Test public void androidPackageQueryProducesFingerprintForActualEligibleActivity() throws Exception {
        SemanticRequest request=web();install(request,true,true,true,1);List<SemanticPlatform.Target> result=platform.discover(request,control);
        assertEquals(1,result.size());assertEquals("browser",result.get(0).route);assertEquals("example.browser/example.browser.Main",result.get(0).component);assertTrue(result.get(0).fingerprint.matches("[a-f0-9]{64}"));
        assertEquals(1,result.get(0).identity().getJSONArray("certificates").length());
    }
    @Test public void packageUpdateChangesNativeCandidateFingerprint() throws Exception {
        SemanticRequest request=web();install(request,true,true,true,1);String first=platform.discover(request,control).get(0).fingerprint;
        install(request,true,true,true,2);assertNotEquals(first,platform.discover(request,control).get(0).fingerprint);
    }
    @Test public void unexportedOrUnverifiablePackageIsNeverEligible() throws Exception {
        SemanticRequest request=web();install(request,false,true,true,1);assertTrue(platform.discover(request,control).isEmpty());
        install(request,true,true,false,1);assertTrue(platform.discover(request,control).isEmpty());
    }
    @Test public void nonBrowserLinkWithoutDomainApprovalCannotBypassAppLinks() throws Exception {
        SemanticRequest request=web();install(request,true,false,true,1);assertTrue(platform.discover(request,control).isEmpty());
    }
    @Test public void noCapturedServiceCannotBecomeAnApplicationContextLaunch() throws Exception {
        assertThrows(SecurityException.class,platform::resourceIdentity);
        SemanticRequest request=web();install(request,true,true,true,1);SemanticPlatform.Target target=platform.discover(request,control).get(0);
        JSONObject result=platform.dispatch(request,target,control,()->{});assertFalse(result.getBoolean("accepted"));assertEquals("worker_required",result.getString("code"));
        assertNull(Shadows.shadowOf((Application)context).getNextStartedActivity());
    }
    @Test public void statusReportsExperimentalClientsUnavailableAndDirectFeatureDispatchRefuses() throws Exception {
        SemanticModule module=new SemanticModule(new FeatureContext(context,null,null,event->{}));JSONObject status=module.snapshot();
        assertFalse(status.getJSONObject("appFunctions").getBoolean("available"));assertFalse(status.getJSONObject("appFunctions").getBoolean("clientImplemented"));
        assertFalse(status.getJSONObject("webMcp").getBoolean("available"));assertFalse(status.getJSONObject("webMcp").getBoolean("featureProbePerformed"));
        assertThrows(SecurityException.class,()->module.handle("dispatch",new JSONObject()));
    }
}
