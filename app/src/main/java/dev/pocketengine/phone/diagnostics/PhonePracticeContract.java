package dev.pocketengine.phone.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned facts emitted only by the private target and compared with the original native observations. */
public final class PhonePracticeContract {
    public static final int VERSION = 2;
    public static final String MARKER = "PW_DIAG_TARGET_V1", ASCII = "PocketWorld ASCII 42", CHINESE = "口袋世界中文演练42";
    public static final String LAYOUT = "PW_DIAG_LAYOUT", ROTATE = "PW_DIAG_ROTATE";
    private PhonePracticeContract() {}

    public record Layout(int width, int height, int orientation, int displayRotation, int request, int requestedOrientation) {
        public boolean oriented(int expected) { return orientation == expected && (expected == 1 ? height > width : width > height); }
        public JSONObject json() throws Exception { return new JSONObject().put("windowWidth", width).put("windowHeight", height)
                .put("configurationOrientation", orientation).put("displayRotation", displayRotation)
                .put("requestCount", request).put("requestedOrientation", requestedOrientation); }
    }
    public static String layoutText(int width, int height, int orientation, int rotation, int request, int requested) {
        return LAYOUT + ":" + width + "," + height + "," + orientation + "," + rotation + "," + request + "," + requested;
    }
    public static Layout layout(JSONObject observation) {
        JSONArray nodes = observation == null ? null : observation.optJSONArray("nodes");
        Layout result = null;
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || node.optBoolean("password") || node.optBoolean("editable") || !node.optBoolean("visible", true)
                    || !LAYOUT.equals(node.optString("description"))) continue;
            String text = node.optString("text");
            if (!text.matches("PW_DIAG_LAYOUT:[0-9]{1,5},[0-9]{1,5},[12],[0-3],[01],[0-2]")) return null;
            String[] values = text.substring(LAYOUT.length() + 1).split(",");
            Layout current = new Layout(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]),
                    Integer.parseInt(values[3]), Integer.parseInt(values[4]), Integer.parseInt(values[5]));
            if (current.width() < 1 || current.height() < 1 || current.width() > 8192 || current.height() > 8192 || result != null) return null;
            result = current;
        }
        return result;
    }
    public static JSONObject expected(String id) throws Exception {
        String description = switch (id) {
            case "helper" -> "原后台助手通过原认证连接";
            case "display" -> "自有测试页出现在原独立显示与会话";
            case "tree" -> "原显示真实树含本次自有页标记与会话";
            case "frame" -> "原本地帧身份、解码尺寸和非空像素均可核对";
            case "click" -> "原节点接受点击后，新观察中的自有计数增加";
            case "ascii_input" -> "原输入节点接受指定 ASCII 后，新观察精确回读";
            case "chinese_input" -> "原输入节点接受指定中文后，新观察精确回读";
            case "scroll" -> "原列表接受滚动后，新观察中的可见行变化";
            case "back" -> "原返回被接受后，新观察确认离开本次测试页";
            case "live_updates" -> "原观察经过至少 3000ms，测试页计数至少增加 3";
            case "main_screen_isolation" -> "原生落点回执证实目标仍在自有独立屏";
            case "ime_isolation" -> "原生助手报告独立屏输入法隔离可用";
            case "rotation" -> "原旋转节点请求后，实际方向、窗口与帧尺寸一致，旋转后原节点点击有新回读";
            default -> throw new IllegalArgumentException("unknown_practice_check");
        };
        return new JSONObject().put("description", description).put("requestAcceptedIsSuccess", false);
    }
}
