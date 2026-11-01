package dev.pocketengine.features.semantic;

import dev.pocketengine.core.RunControl;
import java.util.List;
import org.json.JSONObject;

/** The production implementation uses PackageManager and the captured main-screen accessibility service. */
public interface SemanticPlatform {
    interface Admission { void check() throws Exception; }
    List<Target> discover(SemanticRequest request,RunControl control) throws Exception;
    JSONObject resourceIdentity() throws Exception;
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
        }
        public JSONObject identity() throws Exception { return SemanticJson.copy(identity); }
        public JSONObject description() throws Exception { return SemanticJson.copy(description); }
    }
}
