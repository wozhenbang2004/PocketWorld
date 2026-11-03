package dev.pocketengine.features.plugins;

import java.util.Set;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;

/** A view over the original installed records, never another registry or permission store.
 * One optional plugin publishes all of its checked routes or none. Global store reads stay
 * outside this boundary; cancellation and VM failures are not converted to availability. */
public final class PluginRegistryAssembly {
    @FunctionalInterface public interface Entry { JSONArray assemble(JSONObject originalPlugin) throws Exception; }
    public static final class Unavailable extends Exception {
        private final String code;
        public Unavailable(String code) {
            super("Optional plugin unavailable");
            if(!Set.of("authorization_required","discovery_required","source_invalidated").contains(code))throw new IllegalArgumentException("Unknown registry availability code");
            this.code=code;
        }
    }
    public static final class Result {
        private final String tools,unavailable;
        private Result(JSONArray tools,JSONArray unavailable){this.tools=tools.toString();this.unavailable=unavailable.toString();}
        public JSONArray tools()throws Exception{return new JSONArray(tools);}
        public JSONArray unavailable()throws Exception{return new JSONArray(unavailable);}
    }
    private PluginRegistryAssembly(){}
    public static Result assemble(JSONArray original,Entry entry)throws Exception {
        java.util.Objects.requireNonNull(original);java.util.Objects.requireNonNull(entry);
        JSONArray tools=new JSONArray(),unavailable=new JSONArray();
        for(int i=0;i<original.length();i++) {
            JSONObject plugin=original.optJSONObject(i);
            if(plugin!=null&&!plugin.optBoolean("enabled"))continue;
            try {
                if(plugin==null)throw new IllegalArgumentException("Invalid original plugin record");
                JSONArray staged=java.util.Objects.requireNonNull(entry.assemble(plugin));
                // Materialize the entire plugin before publishing any of its entries.
                JSONArray checked=new JSONArray();for(int at=0;at<staged.length();at++)checked.put(new JSONObject(staged.getJSONObject(at).toString()));
                for(int at=0;at<checked.length();at++)tools.put(checked.getJSONObject(at));
            } catch(InterruptedException cancelled) {Thread.currentThread().interrupt();throw cancelled;}
            catch(CancellationException cancelled){throw cancelled;}
            catch(Exception failed) {
                Object supplied=plugin==null?null:plugin.opt("id");
                String id=supplied instanceof String value&&value.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")?value:"invalid_stored_plugin";
                unavailable.put(new JSONObject().put("pluginId",id).put("code",failed instanceof Unavailable known?known.code:"declaration_or_binding_unavailable")
                        .put("toolsExposed",false).put("automaticRetry",false));
            }
        }
        return new Result(tools,unavailable);
    }
}
