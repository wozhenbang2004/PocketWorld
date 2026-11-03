package dev.pocketengine.features.local;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import org.json.*;
/** Forked host-JVM fixture with an explicitly empty native-library path. */
public final class NativeCommandUnavailableProbe {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);try(var commands=new NativeCommandExecutor(root.resolve("receipts").toFile(),List.of(root.resolve("tools").toFile()),event->{throw new AssertionError("unavailable native dispatched");})){
            JSONArray tools=commands.tools();boolean seen=false;for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(tool.getString("name").equals("pwd")){seen=true;if(!tool.getBoolean("available")||tool.getBoolean("executionAllowed")||tool.getBoolean("nativeSupervisorAvailable"))throw new AssertionError("false native capability");}}
            if(!seen)throw new AssertionError("missing original readonly tool");try{commands.submit("own-checkout",root.toFile(),new JSONArray().put("pwd"),"never-native",2);throw new AssertionError("unavailable native accepted");}catch(IllegalStateException expected){if(!expected.getMessage().contains("监督器不可用"))throw expected;}
            if(commands.list().length()!=0||Files.exists(root.resolve("fallback-ran"))||Files.exists(root.resolve("receipts/never-native.json")))throw new AssertionError("weak fallback or false receipt");System.out.println("native_unavailable_not_dispatched");
        }
    }
}
