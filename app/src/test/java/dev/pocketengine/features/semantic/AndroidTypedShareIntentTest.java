package dev.pocketengine.features.semantic;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import dev.pocketengine.core.RunControl;
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

/** SDK35 Intent/PackageManager contracts only; no physical app, chooser or delivery claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AndroidTypedShareIntentTest {
    static void install(SemanticRequest request,String packageName,boolean exported,boolean signed,boolean browser,int version)throws Exception{
        Application app=RuntimeEnvironment.getApplication();ApplicationInfo application=new ApplicationInfo();application.packageName=packageName;application.uid=10460;application.enabled=true;ActivityInfo activity=new ActivityInfo();activity.packageName=packageName;activity.name=packageName+".Main";activity.enabled=true;activity.exported=exported;activity.applicationInfo=application;PackageInfo pkg=new PackageInfo();pkg.packageName=packageName;pkg.applicationInfo=application;pkg.activities=new ActivityInfo[]{activity};pkg.setLongVersionCode(version);pkg.lastUpdateTime=version*1000L;
        if(signed){pkg.signingInfo=new SigningInfo();Shadows.shadowOf(pkg.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{3,5,8})});}Shadows.shadowOf(app.getPackageManager()).installPackage(pkg);
        IntentFilter filter=new IntentFilter(request.action);filter.addCategory(Intent.CATEGORY_DEFAULT);if(request.kind.equals("app_link")){filter.addCategory(Intent.CATEGORY_BROWSABLE);filter.addDataScheme("https");if(!browser)filter.addDataAuthority("example.org",null);}else filter.addDataType("text/plain");ResolveInfo target=new ResolveInfo();target.activityInfo=activity;target.filter=filter;target.isDefault=true;target.nonLocalizedLabel="明确原生接收fixture";Shadows.shadowOf(app.getPackageManager()).setResolveInfosForIntent(AndroidSemanticPlatform.intent(request),List.of(target));
    }
    @Test public void shareIntentContainsOnlyExactPackagePlainTextAndNoRecipientsStreamsChooserOrGrants()throws Exception{
        JSONObject request=SemanticTypedShareRequestTest.share();Intent intent=AndroidSemanticPlatform.intent(SemanticRequest.parse(request));assertEquals(Intent.ACTION_SEND,intent.getAction());assertEquals("text/plain",intent.getType());assertEquals(request.getString("packageName"),intent.getPackage());assertEquals(request.getString("text"),intent.getStringExtra(Intent.EXTRA_TEXT));assertEquals(Set.of(Intent.EXTRA_TEXT),intent.getExtras().keySet());assertNull(intent.getData());assertNull(intent.getComponent());assertNull(intent.getClipData());assertEquals(0,intent.getFlags());
    }
    @Test public void packageLinkUsesTheOriginalHttpsBrowsableContractAndNoArbitraryExtras()throws Exception{
        JSONObject request=SemanticTypedShareRequestTest.link();Intent intent=AndroidSemanticPlatform.intent(SemanticRequest.parse(request));assertEquals(Intent.ACTION_VIEW,intent.getAction());assertEquals(request.getString("url"),intent.getDataString());assertEquals(request.getString("packageName"),intent.getPackage());assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));assertNull(intent.getExtras());assertNull(intent.getClipData());assertEquals(0,intent.getFlags());
    }
    @Test public void packageManagerCannotSubstituteAnotherPackageEvenWhenItReturnsAMatchingHandler()throws Exception{
        SemanticRequest request=SemanticRequest.parse(SemanticTypedShareRequestTest.share());install(request,"other.native.fixture",true,true,false,1);AndroidSemanticPlatform platform=new AndroidSemanticPlatform(RuntimeEnvironment.getApplication(),null,null);assertTrue(platform.discover(request,new RunControl()).isEmpty());install(request,"share.native.fixture",true,true,false,1);assertEquals(1,platform.discover(request,new RunControl()).size());assertNull(Shadows.shadowOf((Application)RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void httpsPackageLinkStillRequiresOriginalOsAppLinksOrBrowserEligibility()throws Exception{
        SemanticRequest request=SemanticRequest.parse(SemanticTypedShareRequestTest.link());AndroidSemanticPlatform platform=new AndroidSemanticPlatform(RuntimeEnvironment.getApplication(),null,null);install(request,"share.native.fixture",true,true,false,1);assertTrue(platform.discover(request,new RunControl()).isEmpty());install(request,"share.native.fixture",true,true,true,1);assertEquals("browser",platform.discover(request,new RunControl()).get(0).route);
    }
    @Test public void targetVersionAndSignatureRemainPartOfTheNativeFingerprintAndUnexportedHandlersAreUnavailable()throws Exception{
        SemanticRequest request=SemanticRequest.parse(SemanticTypedShareRequestTest.share());AndroidSemanticPlatform platform=new AndroidSemanticPlatform(RuntimeEnvironment.getApplication(),null,null);install(request,"share.native.fixture",true,true,false,1);String first=platform.discover(request,new RunControl()).get(0).fingerprint;install(request,"share.native.fixture",true,true,false,2);assertNotEquals(first,platform.discover(request,new RunControl()).get(0).fingerprint);install(request,"share.native.fixture",false,true,false,2);assertTrue(platform.discover(request,new RunControl()).isEmpty());install(request,"share.native.fixture",true,false,false,2);assertTrue(platform.discover(request,new RunControl()).isEmpty());
    }
}
