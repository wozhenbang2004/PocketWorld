import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('host_selection', ROOT / 'tools/host-selection.py')
selection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selection)

class MikaIssueBatchSelectionTest(unittest.TestCase):
    def test_exact_private_branch_and_bounded_selected_groups(self):
        workflow = (ROOT / '.github/workflows/build-apk.yml').read_text()
        exact = "github.head_ref == 'integrate/mika-issues-batch-20261103'"
        self.assertEqual(3, workflow.count(exact))
        self.assertIn('github.event.pull_request.head.repo.full_name == github.repository', workflow)
        self.assertIn('github.event.repository.private', workflow)
        self.assertIn("'mika-issue-integration,runner-budget-stream,mika-models,model-capability-probe,mika-ecosystem,magpie-work-profile,mika-phone-evidence,phone-access-history,mika-skill-lifecycle,aion-mcp-skills'", workflow)
        registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual('mika-issue-integration,runner-budget-stream,mika-models,model-capability-probe,mika-ecosystem,magpie-work-profile,mika-phone-evidence,phone-access-history,mika-skill-lifecycle,aion-mcp-skills'.split(','), selection.selected(registry, 'mika-issue-integration', 'mika-issue-integration,runner-budget-stream,mika-models,model-capability-probe,mika-ecosystem,magpie-work-profile,mika-phone-evidence,phone-access-history,mika-skill-lifecycle,aion-mcp-skills'))
        group = registry['mika-issue-integration']
        self.assertEqual(10, len([group['class']] + group['additionalClasses']))
        for name in [group['class']] + group['additionalClasses']:
            relative = Path(*name.split('.')).with_suffix('.java')
            self.assertTrue(any((ROOT / root / relative).is_file() for root in ('app/src/test/java', 'app/src/hostTest/java')), name)
        runner = registry['runner-budget-stream']
        self.assertIn('dev.pocketengine.runtime.policy.OwnerQuestionChoicesTest', runner['additionalClasses'])
        self.assertIn('dev.pocketengine.runtime.StructuredRunOutcomeRunnerTest', runner['additionalClasses'])

if __name__ == '__main__': unittest.main()
