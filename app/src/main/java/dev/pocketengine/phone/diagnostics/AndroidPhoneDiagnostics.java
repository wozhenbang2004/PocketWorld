package dev.pocketengine.phone.diagnostics;

import android.content.Context;
import android.os.Build;
import dev.pocketengine.phone.AndroidPhoneReadiness;
import dev.pocketengine.phone.display.DisplayModule;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Native read-only facts. Never connects a helper, requests permission, starts a task or runs a self-test. */
public final class AndroidPhoneDiagnostics {
    private AndroidPhoneDiagnostics() { }
    /** A null display deliberately samples only the existing main readiness groups. */
    public static PhoneDiagnosticReport.Report preview(Context android,DisplayModule display)throws Exception {
        JSONObject version=packagedVersion(android);
        JSONObject platform=new JSONObject().put("sdkInt",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS==null||Build.SUPPORTED_ABIS.length==0?"":Build.SUPPORTED_ABIS[0]);
        JSONObject background=null;
        if(display!=null)try{background=display.snapshot();}catch(Exception unavailable){/* Keep main readiness; never include the raw helper failure or invent a successful sample. */}
        return PhoneDiagnosticReport.create(version,platform,background,AndroidPhoneReadiness.snapshot(android,display));
    }
    private static JSONObject packagedVersion(Context android)throws Exception {
        JSONObject version=new JSONObject();
        // Only the original packaged asset. Never infer a current build SHA from runtime files or a working Git tree.
        try(InputStream input=android.getAssets().open("phone-build-identity.json")){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;
            while((n=input.read(chunk))!=-1){if(buffer.size()+n>4096)throw new IllegalArgumentException("packaged source identity too large");buffer.write(chunk,0,n);}
            JSONObject identity=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));version.put("commit",identity.optString("commit")).put("tree",identity.optString("tree"));
        }catch(Exception unavailable){/* Missing/invalid packaged identity is explicitly unknown, not a host guess. */}
        try{version.put("versionCode",android.getPackageManager().getPackageInfo(android.getPackageName(),0).getLongVersionCode());}
        catch(Exception unavailable){/* Native package version remains null. No raw exception enters the report. */}
        return version;
    }
}
