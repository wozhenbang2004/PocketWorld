"""Unified editor wiring; store tests cover real transactions, not rendered UI."""
from pathlib import Path
import json
import os
import unittest

ROOT = Path(os.environ.get('POCKETWORLD_SOURCE_ROOT', Path(__file__).resolve().parents[2]))
UI = ROOT / 'app/src/main/kotlin/dev/pocketengine/ui'


class SettingsProfileTargetTest(unittest.TestCase):
    def test_settings_routes_to_the_existing_exact_connection_editor(self):
        source = (ROOT / 'web-ui/src/Secondary.tsx').read_text()
        self.assertIn("hostSection = isModelPage ? 'models'", source)
        self.assertIn('<ModelSettings key={modelDraftKey}', source)
        for preserved in ['<BackgroundSettings', '<DataManagement', "page === 'archives'", "page === 'storage'",
                          'PresentationSettings', "'learning'", "'memoryReview'"]:
            self.assertIn(preserved, source)
        self.assertFalse((UI / 'PocketWorldApp.kt').exists())

    def test_no_controller_callback_can_use_the_later_retained_profile(self):
        controller = (UI / 'PocketWorldController.kt').read_text()
        self.assertNotIn('fun saveModel(', controller)
        self.assertNotIn('fun testModel(', controller)
        panel = (ROOT / 'web-ui/src/Secondary.tsx').read_text()
        self.assertIn('useRef(!adding && connection ? { ...connection } : undefined)', panel)
        self.assertIn('modelConnectionCommand(adding, testing, original.current)', panel)
        contracts = (ROOT / 'web-ui/src/forms-library/host-contracts.ts').read_text()
        self.assertIn('id: entry.id, expectedRevision: entry.revision', contracts)
        native = (UI / 'web/PocketWebBridge.kt').read_text()
        self.assertIn('requiredInt(args, "expectedRevision")', native)
        self.assertIn('controller.saveApiConnection(adding, id, expected,', native)

    def test_original_per_connection_preferences_are_saved_with_the_exact_row(self):
        panel = (ROOT / 'web-ui/src/Secondary.tsx').read_text()
        for read in ['config.sendScreenshots !== false', 'Boolean(config.supportsImageInput)',
                     "text(config.maxOutputTokens, '4096')"]:
            self.assertIn(read, panel)
        self.assertIn('output < 128 || output > 32768', panel)
        self.assertIn('isDisabled={!adding && Boolean(original.current?.catalogEnabled)}', panel)
        self.assertIn('screenshots: form.screenshots', panel)
        self.assertIn('maxOutputTokens: output', panel)
        native = (UI / 'web/PocketWebBridge.kt').read_text()
        self.assertIn('.put("sendScreenshots", requiredBoolean(args, "screenshots"))', native)
        self.assertIn('.put("maxOutputTokens", requiredInt(args, "maxOutputTokens"))', native)

    def test_real_native_transaction_regression_is_registered(self):
        registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual('dev.pocketengine.store.SettingsProfileEditTargetTest',
                         registry['settings-profile-edit-target']['class'])
        self.assertTrue(registry['settings-profile-edit-target']['robolectric'])


if __name__ == '__main__':
    unittest.main()
