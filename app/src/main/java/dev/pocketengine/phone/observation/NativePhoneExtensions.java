package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;

/** Exact selected physical port; each write still needs the original prepared-operation admission. */
public interface NativePhoneExtensions {
    JSONObject extension(String tool,JSONObject arguments,RunControl original,NativePhoneEntry.Admission admission)throws Exception;
}
