package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;

/** Optional local pixel evidence under the original capture permission and display/run lease. */
public interface NativePhoneVisualSignals {
    /** No input, image delivery, target reference, implicit preparation or wait for a throttled capture. */
    JSONObject readVisualEvidence(RunControl originalControl)throws Exception;
}
