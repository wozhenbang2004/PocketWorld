package dev.pocketengine.phone.reading;

import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A closed first-use request for one literal, not an app selector or a change to general owner intent. */
public final class CurrentMainInspection {
    private static final Pattern GOAL = Pattern.compile(
            "(?:请)?仅读取眼前(?:计算器的|(?:页面|屏幕)上(?:的|显示的))(?:数字|读数)[，,]不要点击[。.]?");
    private static final Pattern NUMBER = Pattern.compile("[-−]?[0-9]{1,8}(?:[.,][0-9]{1,6})?");

    private CurrentMainInspection() {}

    public static boolean matchesGoal(String goal) {
        return goal != null && goal.length() <= 64 && GOAL.matcher(goal.trim()).matches();
    }

    /** No interpretation, screenshots, model, or further reader. Consume only this already captured tree. */
    public static String observedNumber(JSONObject captured) throws Exception {
        JSONArray windows = captured.getJSONArray("windows"), nodes = captured.getJSONArray("nodes");
        if (windows.length() != 1 || nodes.length() == 0 || nodes.length() > 4096 ||
                !Boolean.FALSE.equals(captured.opt("truncated"))) return null;
        JSONObject ownWindow=windows.getJSONObject(0);
        if(!Boolean.TRUE.equals(ownWindow.opt("structuralComplete")))return null;
        int window = ownWindow.getInt("windowId");
        Map<String,JSONObject> byId=new HashMap<>();
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.getJSONObject(i);
            if(node.getInt("windowId")!=window||byId.put(node.getString("id"),node)!=null)return null;
        }
        String reading = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (!Boolean.FALSE.equals(node.opt("textTruncated"))) continue;
            JSONArray children = node.optJSONArray("children");
            Object text = node.opt("text");
            if (children == null || children.length() != 0 || !(text instanceof String raw)) continue;
            String value = raw.trim();
            if (!NUMBER.matcher(value).matches() || !plainVisibleText(node,byId)) continue;
            if (reading != null && !reading.equals(value)) return null;
            reading = value;
        }
        return reading;
    }

    /** A non-clickable child label inside a keypad button is not a numeric readout. */
    private static boolean plainVisibleText(JSONObject node,Map<String,JSONObject> byId){
        for(int depth=0;node!=null&&depth<=128;depth++){
            if(!Boolean.TRUE.equals(node.opt("visible"))||!Boolean.FALSE.equals(node.opt("password"))||
                    !Boolean.FALSE.equals(node.opt("clickable"))||!Boolean.FALSE.equals(node.opt("editable")))return false;
            Object parent=node.opt("parentId");
            if(parent==JSONObject.NULL)return true;
            node=parent instanceof String id?byId.get(id):null;
        }
        return false;
    }
}
