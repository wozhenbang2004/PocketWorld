package dev.pocketengine.ui;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native preference contract, including invalid bridge inputs and a failed disk receipt. */
public final class UiTypographyTest {
    @Test public void completeSelectionCommitsTogetherAndReopensWithoutChangingOtherPreferences() throws Exception {
        Preferences preferences = new Preferences();
        preferences.values.put("appearance", "dark");
        JSONObject chosen = new JSONObject().put("chinese", "lxgwwenkai").put("latin", "geist").put("code", "ibmplexmono");
        UiTypography.save(preferences, chosen);
        assertEquals(1, preferences.commits);
        assertEquals(Set.of(UiTypography.KEY), preferences.lastKeys);
        assertEquals("dark", preferences.getString("appearance", null));
        Preferences reopened = new Preferences(preferences.values);
        JSONObject actual = UiTypography.snapshot(reopened);
        assertEquals("lxgwwenkai", actual.getString("chinese"));
        assertEquals("geist", actual.getString("latin"));
        assertEquals("ibmplexmono", actual.getString("code"));
        UiTypography.save(reopened, UiTypography.defaults());
        assertEquals("notosanssc", UiTypography.snapshot(reopened).getString("chinese"));
    }

    @Test public void rejectedBridgeArgumentsLeaveOriginalSelectionUntouched() throws Exception {
        Preferences preferences = new Preferences();
        UiTypography.save(preferences, UiTypography.defaults());
        String before = preferences.getString(UiTypography.KEY, null);
        JSONObject[] rejected = {
                new JSONObject().put("chinese", "notosanssc").put("latin", "inter"),
                new JSONObject(UiTypography.defaults().toString()).put("url", "https://invalid.example/font.ttf"),
                new JSONObject(UiTypography.defaults().toString()).put("code", "mashanzheng"),
                new JSONObject(UiTypography.defaults().toString()).put("chinese", "inter"),
                new JSONObject(UiTypography.defaults().toString()).put("latin", 1),
                new JSONObject(UiTypography.defaults().toString()).put("latin", "url(javascript:invalid)"),
                new JSONObject(UiTypography.defaults().toString()).put("code", JSONObject.NULL)
        };
        for (JSONObject selection : rejected) {
            assertThrows(IllegalArgumentException.class, () -> UiTypography.save(preferences, selection));
            assertEquals(before, preferences.getString(UiTypography.KEY, null));
        }
        assertEquals("Invalid requests must not open a preference editor", 1, preferences.edits);
    }

    @Test public void corruptedOrObsoleteStorageFallsBackAsAWhole() throws Exception {
        Preferences preferences = new Preferences();
        Object[] invalid = { 42, "{invalid", "{}", "{\"chinese\":\"lxgwwenkai\",\"latin\":\"old-font\",\"code\":\"ibmplexmono\"}" };
        for (Object value : invalid) {
            preferences.values.put(UiTypography.KEY, value);
            JSONObject actual = UiTypography.snapshot(preferences);
            assertEquals("notosanssc", actual.getString("chinese"));
            assertEquals("inter", actual.getString("latin"));
            assertEquals("jetbrainsmono", actual.getString("code"));
        }
        assertEquals(0, preferences.edits);
    }

    @Test public void failedDiskReceiptDoesNotBecomeASuccessfulSave() throws Exception {
        Preferences preferences = new Preferences();
        preferences.commitResult = false;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> UiTypography.save(preferences, UiTypography.defaults()));
        assertTrue(failure.getMessage().contains("未确认"));
        assertEquals(1, preferences.commits);
        assertEquals(Set.of(UiTypography.KEY), preferences.lastKeys);
    }

    @Test public void validationCopiesTheSelectionBeforeTheAsyncBoundary() throws Exception {
        JSONObject source = UiTypography.defaults();
        JSONObject captured = UiTypography.validate(source);
        source.put("chinese", "notoserifsc");
        assertEquals("notosanssc", captured.getString("chinese"));
    }

    private static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int edits, commits;
        Set<String> lastKeys = Set.of();
        boolean commitResult = true;
        Preferences() {}
        Preferences(Map<String, Object> saved) { values.putAll(saved); }
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) { return values.containsKey(key) ? (Set<String>) values.get(key) : fallback; }
        public int getInt(String key, int fallback) { return values.containsKey(key) ? (Integer) values.get(key) : fallback; }
        public long getLong(String key, long fallback) { return values.containsKey(key) ? (Long) values.get(key) : fallback; }
        public float getFloat(String key, float fallback) { return values.containsKey(key) ? (Float) values.get(key) : fallback; }
        public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public Editor edit() {
            edits++;
            return new Editor() {
                final Map<String, Object> staged = new HashMap<>();
                final Set<String> removed = new HashSet<>();
                boolean clear;
                public Editor putString(String key, String value) { staged.put(key, value); return this; }
                public Editor putStringSet(String key, Set<String> value) { staged.put(key, value); return this; }
                public Editor putInt(String key, int value) { staged.put(key, value); return this; }
                public Editor putLong(String key, long value) { staged.put(key, value); return this; }
                public Editor putFloat(String key, float value) { staged.put(key, value); return this; }
                public Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
                public Editor remove(String key) { removed.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    commits++;
                    lastKeys = Set.copyOf(staged.keySet());
                    if (!commitResult) return false;
                    if (clear) values.clear();
                    removed.forEach(values::remove);
                    values.putAll(staged);
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
}
