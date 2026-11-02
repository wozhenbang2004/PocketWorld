package dev.pocketengine.store;

/** Same package-private original constructor as native model tests; only synthetic in-memory secret values. */
public final class ResearchModelFixtureSettings {
    private ResearchModelFixtureSettings(){}
    public static SettingsStore create(android.content.SharedPreferences preferences,SettingsStore.ApiSecrets secrets){return new SettingsStore(preferences,secrets);}
}
