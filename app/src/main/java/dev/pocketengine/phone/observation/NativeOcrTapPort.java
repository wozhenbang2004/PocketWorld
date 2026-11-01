package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import org.json.JSONObject;

/** A real driver that can verify its privately retained full frame before a native OCR tap. */
public interface NativeOcrTapPort {
    JSONObject tapOcrText(OcrTextTarget target,RunControl originalControl,NativePhoneEntry.Admission admission)throws Exception;
}
