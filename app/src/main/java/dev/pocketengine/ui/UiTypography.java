package dev.pocketengine.ui;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Local presentation only. Font identifiers never become URLs, CSS or task authority. */
public final class UiTypography {
    public static final String KEY = "typography";
    private static final Set<String> CHINESE = Set.of(
            "notosanssc", "notoserifsc", "lxgwwenkai", "zcoolxiaowei", "zcoolqingkehuangyou", "mashanzheng");
    private static final Set<String> LATIN = Set.of(
            "inter", "geist", "dmsans", "manrope", "plusjakartasans", "spacegrotesk", "sora", "outfit",
            "fraunces", "newsreader", "jetbrainsmono", "ibmplexmono");
    private static final Set<String> CODE = Set.of("jetbrainsmono", "ibmplexmono");
    private static final Set<String> FIELDS = Set.of("chinese", "latin", "code");

    private UiTypography() {}

    public static JSONObject defaults() {
        return new JSONObject(Map.of("chinese", "notosanssc", "latin", "inter", "code", "jetbrainsmono"));
    }

    /** Copy a complete selection before the asynchronous save starts. */
    public static JSONObject validate(JSONObject selection) {
        if (selection == null || selection.length() != FIELDS.size()) throw invalid();
        var fields = selection.keys();
        while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw invalid();
        Object chinese = selection.opt("chinese"), latin = selection.opt("latin"), code = selection.opt("code");
        if (!(chinese instanceof String) || !CHINESE.contains(chinese)
                || !(latin instanceof String) || !LATIN.contains(latin)
                || !(code instanceof String) || !CODE.contains(code)) throw invalid();
        return new JSONObject(Map.of("chinese", chinese, "latin", latin, "code", code));
    }

    /** Corrupt or obsolete local choices fall back together, rather than mixing partial settings. */
    public static JSONObject snapshot(SharedPreferences preferences) {
        try {
            String stored = preferences.getString(KEY, null);
            return stored == null ? defaults() : validate(new JSONObject(stored));
        } catch (Exception invalidStoredValue) {
            return defaults();
        }
    }

    /** One preference value commits all roles together. A false disk receipt is never success. */
    public static void save(SharedPreferences preferences, JSONObject selection) {
        String canonical = validate(selection).toString();
        if (!preferences.edit().putString(KEY, canonical).commit()) {
            throw new IllegalStateException("字体设置的本机保存未确认，请重试");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("请选择完整的中文、英文与代码字体");
    }
}
