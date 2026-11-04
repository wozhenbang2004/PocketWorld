"""Exact current Aion inventory; original snapshots remain in Git, not product test results."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = json.loads((ROOT / 'tools/host-tests.json').read_text())
FIXTURE = json.loads((ROOT / 'tools/tests/fixtures/aion-absorption-tests.json').read_text())


class AionHostGroups(unittest.TestCase):
    def source(self, name):
        # Match the explicit Java/Kotlin roots used by tools/host-tests.gradle.
        relative = name.replace('.', '/')
        candidates = [ROOT / root / (relative + suffix) for root, suffix in (
            ('app/src/hostTest/java', '.java'),
            ('app/src/test/java', '.java'),
            ('app/src/test/kotlin', '.kt'),
        )]
        sources = [path for path in candidates if path.is_file()]
        self.assertEqual(1, len(sources), name)
        return sources[0]

    def methods(self, name):
        source = self.source(name)
        declaration = r'public\s+void' if source.suffix == '.java' else r'(?:public\s+)?fun'
        return re.findall(r'@Test(?:\s*\([^)]*\))?\s+' + declaration + r'\s+(\w+)\s*\(', source.read_text())

    def test_exact_original_classes_counts_and_compile_only_helpers(self):
        self.assertEqual({'aion-core': 52, 'aion-mcp-skills': 140, 'aion-office': 18},
                         {key: value['testCount'] for key, value in FIXTURE.items()})
        selected = set()
        for key, expected in FIXTURE.items():
            with self.subTest(group=key):
                actual = REGISTRY[key]
                classes = [actual['class']] + actual.get('additionalClasses', [])
                self.assertEqual([item['class'] for item in expected['classes']], classes)
                self.assertEqual(expected['helpers'], actual['helpers'])
                self.assertEqual(expected['robolectric'], actual.get('robolectric', False))
                self.assertFalse(selected.intersection(classes))
                selected.update(classes)
                self.assertEqual(expected['testCount'], sum(len(item['methods']) for item in expected['classes']))
                for item in expected['classes']:
                    self.assertEqual(item['methods'], self.methods(item['class']), item['class'])
                for name in actual['helpers']:
                    self.assertTrue(self.source(name).is_file())
        self.assertEqual(26, len(selected))
        for key, expected in FIXTURE.items():
            self.assertFalse(selected.intersection(expected['helpers']), key)


    def test_conversations_selects_original_data_and_native_migration_bodies(self):
        expected = {
            'dev.pocketengine.features.assistant.ConversationWorkbenchNativeJourneyTest': 5,
            'dev.pocketengine.features.assistant.ConversationOrganizationTest': 4,
            'dev.pocketengine.features.assistant.ConversationTransferTest': 7,
            'dev.pocketengine.store.ConversationV9MigrationNativeTest': 5,
            'dev.pocketengine.store.DeviceV8MigrationNativeTest': 2,
            'dev.pocketengine.features.assistant.ConversationOwnerTitleNativeTest': 4,
            'dev.pocketengine.features.conversation.ConversationPinnedHistoryTest': 3,
            'dev.pocketengine.ui.ConversationOrganizationUiNativeTest': 3,
            'dev.pocketengine.features.conversation.InterruptedAttemptsTest': 3,
            'dev.pocketengine.features.assistant.notifications.OwnerQuestionReferenceTest': 1,
            'dev.pocketengine.features.assistant.OwnerQuestionInputsNativeTest': 5,
            'dev.pocketengine.features.assistant.OwnerPhoneQuestionsNativeTest': 14,
            'dev.pocketengine.features.assistant.OwnerPhoneQuestionContinuationJourneyTest': 1,
            'dev.pocketengine.features.assistant.OwnerQuestionRunnerNativeTest': 5,
            'dev.pocketengine.features.assistant.ConversationInterruptedNativeJourneyTest': 2,
        }
        entry = REGISTRY['aion-conversations']
        self.assertEqual(list(expected), [entry['class']] + entry['additionalClasses'])
        self.assertEqual([
            'dev.pocketengine.testutil.HttpServer',
            'dev.pocketengine.features.channels.ChannelTaskHostTest',
            'dev.pocketengine.features.channels.ChannelSecurityTest',
        ], entry['helpers'])
        self.assertTrue(entry['robolectric'])
        for name, count in expected.items():
            self.assertEqual(count, len(self.methods(name)), name)
        for name in entry['helpers']:
            self.assertTrue(self.source(name).is_file())
            self.assertNotIn(name, expected)


    def test_team_catalog_new_module_journey_stays_separate_from_absorption_inventory(self):
        entry = REGISTRY['aion-team-catalog']
        name = 'dev.pocketengine.features.teams.TeamRoleModuleNativeTest'
        self.assertEqual(name, entry['class'])
        self.assertEqual([], entry['helpers'])
        self.assertEqual([], entry.get('additionalClasses', []))
        self.assertTrue(entry['robolectric'])
        source = ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')
        self.assertEqual(3, len(re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source.read_text())))
        self.assertNotIn(name, [item['class'] for value in FIXTURE.values() for item in value['classes']])


    def test_work_profile_targets_are_two_native_modules_with_one_http_helper(self):
        expected = {
            'dev.pocketengine.features.remote.WorkProfileRemoteTargetFactsNativeTest': 15,
            'dev.pocketengine.features.ssh.WorkProfileSshTargetFactsNativeTest': 3,
            'dev.pocketengine.features.remote.WorkProfileHarnessCompositionNativeTest': 2,
        }
        entry = REGISTRY['aion-work-profile-targets']
        self.assertEqual(list(expected), [entry['class']] + entry['additionalClasses'])
        self.assertEqual(['dev.pocketengine.testutil.HttpServer'], entry['helpers'])
        self.assertTrue(entry['robolectric'])
        original = [item['class'] for value in FIXTURE.values() for item in value['classes']]
        for name, count in expected.items():
            source = ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')
            self.assertEqual(count, len(re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source.read_text())), name)
            self.assertNotIn(name, original)


    def test_worker_entrypoint_selects_one_pure_junit_bridge_to_exact_seven_python_tests(self):
        entry = REGISTRY['aion-worker-entrypoint']
        name = 'dev.pocketengine.features.remote.WorkerEntrypointCompanionContractTest'
        self.assertEqual(name, entry['class'])
        self.assertEqual([], entry['helpers'])
        self.assertEqual([], entry.get('additionalClasses', []))
        self.assertTrue(entry['python'])
        self.assertFalse(entry.get('robolectric', False))
        source = (ROOT / 'app/src/hostTest/java' / (name.replace('.', '/') + '.java')).read_text()
        self.assertEqual(1, len(re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source)))
        self.assertIn('"test_worker_executable_identity"', source)
        self.assertNotIn('"discover"', source)


    def test_original_successor_observation_and_config_groups_are_exact(self):
        groups = {
            'aion-capability-observations': ({
                'dev.pocketengine.features.remote.HarnessCapabilityObservationTest': 18,
                'dev.pocketengine.runtime.usage.ConnectionInferenceObservationsTest': 12,
            }, [], False),
            'aion-team-templates': ({
                'dev.pocketengine.features.teams.TeamRoleCatalogReadTest': 6,
                'dev.pocketengine.features.teams.TeamTemplateApplicationTest': 5,
            }, [], False),
            'aion-mcp-config': ({
                'dev.pocketengine.features.plugins.McpConfigImportAdapterTest': 15,
                'dev.pocketengine.features.plugins.McpConfigImportNativeJourneyTest': 11,
            }, ['dev.pocketengine.features.plugins.McpFixture', 'dev.pocketengine.testutil.HttpServer'], True),
        }
        for group, (classes, helpers, robolectric) in groups.items():
            entry = REGISTRY[group]
            self.assertEqual(list(classes), [entry['class']] + entry['additionalClasses'])
            self.assertEqual(helpers, entry['helpers'])
            self.assertEqual(robolectric, entry.get('robolectric', False))
            for name, count in classes.items():
                source = ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')
                self.assertEqual(count, len(re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source.read_text())), name)


if __name__ == '__main__':
    unittest.main()
