package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;

/** One exact native accessibility scroll, admitted by the original prepared phone operation. */
public interface NativeTreeScrollPort {
    String TOOL="phone_scroll_node";
    JSONObject scrollNode(JSONObject args,RunControl original,NativePhoneEntry.Admission admission)throws Exception;
    static void validate(JSONObject args)throws Exception {
        PhoneTreeExtract.keys(args,"observationId","nodeId","direction");PhoneTreeExtract.text(args,"observationId",100);PhoneTreeExtract.text(args,"nodeId",100);
        if(!java.util.Set.of("forward","backward").contains(PhoneTreeExtract.text(args,"direction",10)))throw new IllegalArgumentException("滚动方向只能forward或backward");
    }
}
