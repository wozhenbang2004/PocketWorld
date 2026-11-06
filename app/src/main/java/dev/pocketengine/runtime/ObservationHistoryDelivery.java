package dev.pocketengine.runtime;

import static dev.pocketengine.runtime.DurableHistoryCodec.OBSERVATION_PREFIX;
import static dev.pocketengine.runtime.DurableHistoryCodec.PROJECT_IMAGE_PREFIX;
import static dev.pocketengine.runtime.DurableHistoryCodec.markObservationWithoutImage;
import static dev.pocketengine.runtime.DurableHistoryCodec.safeJson;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.RunContextBudget;
import dev.pocketengine.runtime.history.RecentScreenHistory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Live observation envelopes and bounded image projection into Runner-owned history.
 * This is presentation, not capture, persistence or model dispatch. Runner retains
 * source checks, native image pins, screen retention and upload association. Image
 * shape checks here are transport filtering, never proof of currentness/permission.
 */
final class ObservationHistoryDelivery {
    private ObservationHistoryDelivery() { }

    /** Rewrite only the already selected observation. Source withdrawal is decided
     * by the original owner before this method; removing pixels never grants access. */
    static void removeImage(JSONObject message,String notice)throws Exception {
        markObservationWithoutImage(message);JSONArray parts=message.optJSONArray("content"),replacement=new JSONArray();
        for(int i=0;parts!=null&&i<parts.length();i++){JSONObject part=parts.optJSONObject(i);if(part!=null&&!"image_url".equals(part.optString("type")))replacement.put(part);}
        replacement.put(new JSONObject().put("type","text").put("text",notice));message.put("content",replacement);
    }
    /** Historical envelopes keep their distinct low-trust label. They cannot
     * become the latest live observation or provide new coordinate authority. */
    static void appendHistorical(JSONArray history,JSONObject observation,ModelConfig config,RunContextBudget budget,int maxImages,int maxImageChars)throws Exception {
        // The payload has no observationId, nodeId or coordinates; it cannot replace the current action surface.
        append(history,observation,config,budget,maxImages,maxImageChars);
        JSONObject first=history.getJSONObject(history.length()-1).getJSONArray("content").getJSONObject(0);
        String text=first.getString("text");if(!text.startsWith(OBSERVATION_PREFIX))throw new SecurityException("historical_image_envelope_missing");
        first.put("text",RecentScreenHistory.PREFIX+text.substring(OBSERVATION_PREFIX.length()));
    }
    static void append(JSONArray history, JSONObject observation, ModelConfig config, RunContextBudget contextBudget,int maxImages,int maxImageChars) throws Exception {
        if(observation.optBoolean("noDeviceObservation"))return;
        JSONObject nativeData=dev.pocketengine.features.memory.TaskMemoryHistory.observationData(observation);
        JSONObject text = safeJson(nativeData, config, false);
        String image = observation.optString("imageBase64");
        String mime = observation.optString("imageMimeType", "image/jpeg");
        boolean hasImage = config.sendScreenshots && !image.isEmpty() && image.length() <= maxImageChars
                && ("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime))
                && image.matches("[A-Za-z0-9+/=\\r\\n]+") && image.length() % 4 == 0;
        text.put("actualImageAttached", hasImage);
        if (!hasImage) text.put("visionNotice", config.sendScreenshots ? "没有可发送的截图；只依据当前控件树文字。" : "已关闭截图发送；只依据当前控件树文字。");
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text")
                .put("text", ("project_image_snapshot".equals(nativeData.optString("kind"))?PROJECT_IMAGE_PREFIX:OBSERVATION_PREFIX) + text));
        if (hasImage) content.put(new JSONObject().put("type", "image_url").put("image_url",
                new JSONObject().put("url", "data:" + mime + ";base64," + image.replace("\r", "").replace("\n", ""))));
        JSONObject nativeObservation = new JSONObject().put("role", "user").put("content", content).put("hostObservation",true);
        // Keep insertion before budget registration exactly as in the Runner.
        // The caller owns failure handling/checkpointing; no second history is kept.
        history.put(nativeObservation);
        contextBudget.observed(nativeObservation,nativeData);
        // Count only observations recognized by the original context budget.
        // A user/model row that merely resembles this envelope is not ours to prune.
        int remaining = maxImages;
        for (int i = history.length() - 1; i >= 0; i--) {
            JSONObject message = history.optJSONObject(i);
            if (contextBudget.observationFingerprint(message) == null) continue;
            JSONArray parts = message == null ? null : message.optJSONArray("content");
            if (parts == null) continue;
            boolean imageFound = false;
            for (int part = 0; part < parts.length(); part++) if ("image_url".equals(parts.optJSONObject(part).optString("type"))) imageFound = true;
            if (!imageFound) continue;
            if (remaining-- > 0) continue;
            markObservationWithoutImage(message);
            JSONArray replacement = new JSONArray();
            for (int part = 0; part < parts.length(); part++) {
                JSONObject value = parts.optJSONObject(part);
                if (!"image_url".equals(value.optString("type"))) {
                    JSONObject retained = new JSONObject(value.toString());
                    replacement.put(retained);
                }
            }
            replacement.put(new JSONObject().put("type", "text").put("text", "此处旧截图已移除，请以最新观察为准。"));
            message.put("content", replacement);
        }
    }

}
