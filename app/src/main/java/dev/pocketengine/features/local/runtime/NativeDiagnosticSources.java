package dev.pocketengine.features.local.runtime;

import android.content.Context;
import android.os.Build;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.diagnostics.AndroidPhoneDiagnostics;
import java.util.*;
import org.json.*;

/** Fixed local metadata sources. No model/provider, memory body, configuration,
 * task text, log body, screenshot, network call, probe command or file content. */
public final class NativeDiagnosticSources {
    private NativeDiagnosticSources(){}
    public static Map<NativeDiagnosticCollector.Domain,NativeDiagnosticCollector.Probe> create(Context context,ManagedProjectRuntime runtime){
        EnumMap<NativeDiagnosticCollector.Domain,NativeDiagnosticCollector.Probe> sources=new EnumMap<>(NativeDiagnosticCollector.Domain.class);
        sources.put(NativeDiagnosticCollector.Domain.PLATFORM,observation->{observation.check();var info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);observation.check();return new JSONObject().put("deviceSdk",Build.VERSION.SDK_INT).put("targetSdk",context.getApplicationInfo().targetSdkVersion).put("appVersionCode",info.getLongVersionCode()).put("arm64Advertised",Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));});
        sources.put(NativeDiagnosticCollector.Domain.RUNTIME,observation->{if(runtime==null)throw new IllegalStateException("diagnostic_original_runtime_unavailable");return runtime.diagnosticMetadataForHost(observation);});
        sources.put(NativeDiagnosticCollector.Domain.PHONE,observation->{
            observation.check();var report=AndroidPhoneDiagnostics.preview(context,null);JSONObject alreadySafe=report.readiness();observation.check();JSONObject safe=new JSONObject();
            for(String key:List.of("mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"))safe.put(key,alreadySafe.getString(key));
            // No bound background module was sampled. Never export the null
            // adapter's default false as a real background-connection fact.
            return safe.put("backgroundSampled",report.snapshot().getBoolean("backgroundSampled"));
        });
        sources.put(NativeDiagnosticCollector.Domain.STORAGE,observation->{if(runtime==null)throw new IllegalStateException("diagnostic_original_runtime_unavailable");return storage(runtime.ownerStorageForHost().inventory(observation),observation);});
        return Collections.unmodifiableMap(sources);
    }
    private static JSONObject storage(JSONArray rows,RunControl observation)throws Exception {
        JSONObject safe=new JSONObject();for(String key:List.of("homeLogicalBytes","temporaryLogicalBytes","downloadLogicalBytes","originalLogLogicalBytes"))safe.put(key,JSONObject.NULL);
        int incomplete=0,generations=0,links=0;
        for(int i=0;i<rows.length();i++){
            observation.check();JSONObject row=rows.getJSONObject(i),usage=row.optJSONObject("usage");String id=row.optString("id");
            if(id.startsWith("runtime:generation:"))generations++;if(id.startsWith("runtime:links:"))links++;
            boolean complete=usage!=null&&Boolean.TRUE.equals(usage.opt("complete"));if(!complete)incomplete++;
            String key=id.equals("runtime:home")?"homeLogicalBytes":id.equals("runtime:temporary")?"temporaryLogicalBytes":id.equals("runtime:downloads")?"downloadLogicalBytes":id.equals("runtime:original-receipts")?"originalLogLogicalBytes":null;
            if(key!=null&&complete)safe.put(key,usage.opt("logicalBytes")==null?JSONObject.NULL:usage.opt("logicalBytes"));
        }
        return safe.put("incompleteScopes",incomplete).put("generationScopeCount",generations).put("linkScopeCount",links).put("logicalCountsNotAdditive",true).put("fileContentsRead",false);
    }
}
