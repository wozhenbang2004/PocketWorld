package dev.pocketengine.store;
public final class ModelDiagnosticsFixtureSettings {
 private ModelDiagnosticsFixtureSettings(){}
 public static SettingsStore create(android.content.SharedPreferences prefs,SettingsStore.ApiSecrets secrets){return new SettingsStore(prefs,secrets);}
}
