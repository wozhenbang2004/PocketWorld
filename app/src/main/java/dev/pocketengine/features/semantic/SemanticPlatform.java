package dev.pocketengine.features.semantic;

import dev.pocketengine.core.RunControl;
import java.util.List;
import org.json.JSONObject;

/** The production implementation uses PackageManager and the captured phone display and user identity. */
public interface SemanticPlatform {
    interface Admission { void check() throws Exception; }
    List<Target> discover(SemanticRequest request,RunControl control) throws Exception;
    JSONObject resourceIdentity() throws Exception;
    default JSONObject resourceIdentity(SemanticRequest request)throws Exception{return resourceIdentity();}
    JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission) throws Exception;

    final class Target {
        public final String component,fingerprint,route;
        private final JSONObject identity,description;
        public Target(JSONObject identity,String label,String route) throws Exception {
            this.identity=SemanticJson.copy(identity);this.component=identity.getString("component");this.route=route;
            fingerprint=SemanticJson.hash(new JSONObject().put("identity",this.identity).put("route",route));
            String safe=label==null?"":label.replaceAll("[\\p{Cntrl}]"," ");if(safe.length()>100)safe=safe.substring(0,100);
            description=new JSONObject().put("component",component).put("packageName",identity.getString("packageName"))
                    .put("label",safe).put("route",route).put("fingerprint",fingerprint).put("labelIsUntrusted",true);
            for(String key:new String[]{"userId","profileSerial","uid"})if(identity.has(key))description.put(key,identity.get(key));
            if(identity.has("calendar"))description.put("calendar",SemanticJson.copy(identity.getJSONObject("calendar"))).put("scope","current_host_android_profile");
        }
        public JSONObject identity() throws Exception { return SemanticJson.copy(identity); }
        public JSONObject description() throws Exception { return SemanticJson.copy(description); }
    }
}
