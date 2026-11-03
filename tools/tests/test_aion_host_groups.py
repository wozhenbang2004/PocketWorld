"""Exact original Aion slices, not an implicit all-tests task or product test substitute."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = json.loads((ROOT / 'tools/host-tests.json').read_text())
FIXTURE = json.loads((ROOT / 'tools/tests/fixtures/aion-absorption-tests.json').read_text())


class AionHostGroups(unittest.TestCase):
    def test_exact_original_classes_counts_and_compile_only_helpers(self):
        self.assertEqual({'aion-core': 49, 'aion-mcp-skills': 60, 'aion-office': 15},
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
                for item in expected['classes']:
                    source = ROOT / 'app/src/test/java' / (item['class'].replace('.', '/') + '.java')
                    methods = re.findall(r'@Test(?:\s*\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(', source.read_text())
                    self.assertEqual(item['methods'], methods)
                for name in actual['helpers']:
                    self.assertTrue((ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')).is_file())
        self.assertEqual(18, len(selected))
        for key, expected in FIXTURE.items():
            self.assertFalse(selected.intersection(expected['helpers']), key)


    def test_conversations_selects_original_data_and_native_migration_bodies(self):
        expected = {
            'dev.pocketengine.features.assistant.ConversationWorkbenchNativeJourneyTest': 5,
            'dev.pocketengine.features.assistant.ConversationOrganizationTest': 4,
            'dev.pocketengine.features.assistant.ConversationTransferTest': 7,
            'dev.pocketengine.store.ConversationV9MigrationNativeTest': 5,
            'dev.pocketengine.store.DeviceV8MigrationNativeTest': 2,
        }
        entry = REGISTRY['aion-conversations']
        self.assertEqual(list(expected), [entry['class']] + entry['additionalClasses'])
        self.assertEqual([], entry['helpers'])
        self.assertTrue(entry['robolectric'])
        for name, count in expected.items():
            roots = ('app/src/test/java', 'app/src/hostTest/java')
            sources = [ROOT / root / (name.replace('.', '/') + '.java') for root in roots]
            source, = [item for item in sources if item.is_file()]
            self.assertEqual(count, len(re.findall(r'@Test(?:\s*\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(', source.read_text())), name)


    def test_team_catalog_new_module_journey_does_not_broaden_original_124(self):
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
            'dev.pocketengine.features.remote.WorkProfileRemoteTargetFactsNativeTest': 10,
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
                'dev.pocketengine.features.remote.HarnessCapabilityObservationTest': 10,
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
