"""Source wiring contracts only, not mounted Compose or provider acceptance."""
from pathlib import Path
import importlib.util
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def compact(source):
    return re.sub(r'\s+', '', source)


class RemoteCreateSelectionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.composer = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/RemoteTaskComposer.kt').read_text()

    def test_all_three_create_entries_capture_the_current_codex_selection(self):
        entries = [
            ('val request = JSONObject()', 'review = RemoteTaskRequest.first(request)', 'request'),
            ('val request=JSONObject()', 'RemoteTaskRequest.first(request);projectRequest=request', 'request'),
            ('val target = JSONObject()', 'RemoteTaskRequest.first(target)', 'target'),
        ]
        for start, end, target in entries:
            with self.subTest(entry=end):
                capture = compact(self.composer.split(start, 1)[1].split(end, 1)[0])
                self.assertIn(f'if(model.isNotBlank()){target}.put("model",model.trim())', capture)
                self.assertIn(f'if(worker=="codex_app_server"&&effort.isNotBlank()){target}.put("effort",effort)', capture)
                self.assertIn('if(worker=="codex_app_server"&&model.isNotBlank())probe?.optJSONObject("modelOptions")?.optString("digest")', capture)
                self.assertIn(f'it.matches(Regex("[a-f0-9]{{64}}"))', capture)
                self.assertIn(f'{target}.put("modelOptionsDigest",it)', capture)

    def test_probe_freshness_and_existing_create_admission_remain_wired(self):
        source = compact(self.composer)
        self.assertIn('rememberSaveable(profileId,binding,environmentId,worker,model,effort)', source)
        self.assertIn('probeRequest!=null&&state.moduleResultInputs["remote.probeWorker"]?.optString("_requestId")==probeRequest', source)
        self.assertIn('(worker=="command"||probe?.optBoolean("available")==true)', source)
        self.assertIn('enabled=canCreate,', source)
        self.assertIn('enabled=canCreate&&goal.isNotBlank()', source)
        self.assertIn('RemoteTaskRequest.requireInputCapability(selected)', source)

    def test_final_reviews_display_frozen_request_model_and_effort(self):
        project = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/RemoteProjectStartDialog.kt').read_text().split('internal fun RemoteProjectActionReview', 1)[1]
        inputs = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/RemoteInputDialog.kt').read_text().split('if (confirm && review != null && scope != null)', 1)[1]
        for source, target in [(project, 'request'), (inputs, 'captured')]:
            with self.subTest(target=target):
                self.assertIn(f'{target}.optString("model")', source)
                self.assertIn(f'{target}.optString("effort")', source)
                self.assertIn(f'{target}.has("effort")', source)
        self.assertIn('val captured = scope.optJSONObject("request")', inputs)

    def test_pure_serializers_are_used_after_existing_owner_claim_checks(self):
        source = (ROOT / 'app/src/main/java/dev/pocketengine/features/remote/RemoteModule.java').read_text()
        project = source.split('public JSONObject dispatchProjectAction(', 1)[1].split('static JSONObject projectCreateBody(', 1)[0]
        self.assertLess(project.index('approval.checkCurrent()'), project.index('projectCreateBody(request,operation)'))
        self.assertIn('before.beforeDispatch();approval.checkCurrent()', project)
        inputs = source.split('public JSONObject submitInputJob(', 1)[1].split('static JSONObject inputCreateBody(', 1)[0]
        self.assertLess(inputs.index('if(!claim.canDispatch())'), inputs.index('inputCreateBody(scope)'))
        self.assertIn('return inputSend(claim,scope,"/v1/jobs",body', inputs)

    def test_final_body_tests_are_registered_without_native_or_http_prerequisites(self):
        spec = importlib.util.spec_from_file_location('host_tests', ROOT / 'tools/host-tests.py')
        host = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(host)
        registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
        name = 'dev.pocketengine.features.remote.RemoteAlternativeCreateBodyTest'
        self.assertEqual(['remote-create-model-options'], host.select(registry, [], [name]))
        entry = registry['remote-create-model-options']
        for flag in ['native', 'python', 'robolectric']:
            self.assertFalse(entry.get(flag, False))


if __name__ == '__main__':
    unittest.main()
