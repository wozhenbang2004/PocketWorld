package dev.pocketengine.features.local.runtime.android;

import android.content.Context;
import android.os.Build;
import dev.pocketengine.features.local.runtime.AndroidRuntimeCatalog;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** The only default Android runtime composition point; no desktop paths or extra app required. */
public final class AndroidManagedRuntimeFactory {
    private AndroidManagedRuntimeFactory() {}
    public static ManagedProjectRuntime create(Context context) throws Exception {
        File directory=new File(context.getFilesDir(),"development-runtime").getCanonicalFile();
        if(!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))return observe(context,ManagedProjectRuntime.unavailableAndroid(directory,"android_runtime_unsupported_abi"));
        try(InputStream input=context.getAssets().open("runtime/catalog.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(bytes.size()+n>131072)throw new IllegalArgumentException("android_runtime_catalog_too_large");bytes.write(buffer,0,n);}
            AndroidRuntimeCatalog catalog=new AndroidRuntimeCatalog(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
            return observe(context,ManagedProjectRuntime.createAndroid(directory,new File(context.getApplicationInfo().nativeLibraryDir),catalog));
        } catch(java.io.FileNotFoundException missing) {
            return observe(context,ManagedProjectRuntime.unavailableAndroid(directory,"android_runtime_release_catalog_missing"));
        } catch(org.json.JSONException|IllegalArgumentException|SecurityException invalid) {
            return observe(context,ManagedProjectRuntime.unavailableAndroid(directory,"android_runtime_release_catalog_invalid:"+invalid.getMessage()));
        }
    }
    private static ManagedProjectRuntime observe(Context context,ManagedProjectRuntime original){
        AndroidRuntimeThermalObservation observer=null;
        try{observer=AndroidRuntimeThermalObservation.open(context);original.bindLogObservationForHost(observer);}
        catch(RuntimeException unavailable){if(observer!=null)try{observer.close();}catch(RuntimeException cleanupUnconfirmed){/* No false observer-removal claim. */}/* Optional facts never disable the valid original runtime; native read defaults remain conservative. */}
        return original;
    }
}
