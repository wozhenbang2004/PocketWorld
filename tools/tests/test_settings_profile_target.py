"""Original native entry wiring only; store tests cover real transactions, not mounted UI."""
from pathlib import Path
import json
import os
import unittest

ROOT = Path(os.environ.get('POCKETWORLD_SOURCE_ROOT', Path(__file__).resolve().parents[2]))
UI = ROOT / 'app/src/main/kotlin/dev/pocketengine/ui'


class SettingsProfileTargetTest(unittest.TestCase):
    def test_settings_routes_to_the_existing_exact_connection_editor(self):
        source = (UI / 'PocketWorldApp.kt').read_text()
        sheet = source.split('private fun SettingsSheet(', 1)[1].split('// Layout adaptation', 1)[0]
        self.assertIn('SettingEntry("API 连接与模型目录"', sheet)
        self.assertIn('controller.openConnection("models")', sheet)
        for old_entry in ['controller.saveModel(', 'controller.testModel(', 'var url by remember(state.baseUrl)',
                          'var key by remember', 'editApi', 'settingsFieldColors()']:
            self.assertNotIn(old_entry, sheet)
        for preserved in ['AgentPreferencesControls(', 'AutomaticUserLearningControls(',
                          'controller.saveLocalObservation(', 'BackgroundConnectionSummary(',
                          'PocketAppearanceControls()', 'NativeOwnerDataArchives(', 'NativeOwnerProjectStorage(']:
            self.assertIn(preserved, sheet)

    def test_no_controller_callback_can_use_the_later_retained_profile(self):
        controller = (UI / 'PocketWorldController.kt').read_text()
        self.assertNotIn('fun saveModel(', controller)
        self.assertNotIn('fun testModel(', controller)
        page = (UI / 'ModelConnectionsPage.kt').read_text()
        self.assertIn('ApiConnectionProfilesPanel(', page)
        self.assertIn('service.saveConnection(id, revision, name, values)', page)
        panel = (UI / 'ApiConnectionProfilesPanel.kt').read_text()
        self.assertIn('editing = JSONObject(row.toString())', panel)
        self.assertIn('original?.optString("id").orEmpty(), original?.optLong("revision")', panel)
        self.assertNotIn('PocketUiState', panel)

    def test_original_per_connection_preferences_are_saved_with_the_exact_row(self):
        panel = (UI / 'ApiConnectionProfilesPanel.kt').read_text()
        for read in ['config.optBoolean("sendScreenshots", true)', 'config.optBoolean("supportsImageInput")',
                     'config.optInt("maxOutputTokens", 4096)']:
            self.assertIn(read, panel)
        self.assertIn('configuredOutput in 128..32768', panel)
        self.assertIn('provider != "openai_chat"', panel)
        self.assertIn('!catalogEnabled', panel)
        self.assertIn('"sendScreenshots", screenshots', panel)
        self.assertIn('"maxOutputTokens", configuredOutput', panel)

    def test_real_native_transaction_regression_is_registered(self):
        registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual('dev.pocketengine.store.SettingsProfileEditTargetTest',
                         registry['settings-profile-edit-target']['class'])
        self.assertTrue(registry['settings-profile-edit-target']['robolectric'])


if __name__ == '__main__':
    unittest.main()
