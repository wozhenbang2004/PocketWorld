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


if __name__ == '__main__':
    unittest.main()
