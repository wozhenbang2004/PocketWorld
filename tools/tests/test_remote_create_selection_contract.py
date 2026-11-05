"""Unified Web/native source wiring only, not browser or provider acceptance."""
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
        cls.composer = (ROOT / 'web-ui/src/ConnectionWorkspace.tsx').read_text()
        cls.connections = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebConnections.kt').read_text()
        cls.input_page = (ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/web/PocketWebRemoteInputs.kt').read_text()

    def test_all_three_create_entries_capture_the_current_codex_selection(self):
        # Three entry buttons share the one capture before the host freezes a review.
        capture = compact(self.composer.split('const prepare = async', 1)[1].split('const readEnvironments', 1)[0])
        self.assertIn("mode:'ordinary'|'baseline'|'inputs'='ordinary'", capture)
        self.assertIn('model.trim()?{model:model.trim()}:{}', capture)
        self.assertIn("worker==='codex_app_server'&&effort.trim()?{effort:effort.trim()}:{}", capture)
        self.assertIn("worker==='codex_app_server'&&model.trim()&&probe?.modelOptions?.digest?{modelOptionsDigest:probe.modelOptions.digest}:{}", capture)
        self.assertIn("constprepared=awaitrequest('remote.reviewTask',input)", capture)
        self.assertIn("if(mode==='baseline')setBaseline(prepared);elseif(mode==='inputs')setInputRequest(prepared);elsesetReview(prepared)", capture)
        native = compact(self.connections)
        self.assertIn('input.toString(),SystemClock.elapsedRealtime()+120_000,controller.webWindowTicket()', native)
        take = native.split('privatefuntakeCreateReview(', 1)[1].split('privatefunproject(', 1)[0]
        self.assertIn('reviews.remove(handle)', take)
        self.assertIn('valchecked=JSONObject(reviewed.input)', take)
        self.assertIn('valrequest=JSONObject(checked.toString())', take)
        self.assertIn('RemoteTaskRequest.first(request)', take)
        self.assertIn('inputPages?:error(', native)
        self.assertIn('.openPrepared(task,request,done)', native)
        self.assertIn('request?.let{input.put("request",JSONObject(it.toString()))}', compact(self.input_page))
        schema = (ROOT / 'app/src/main/java/dev/pocketengine/features/remote/RemoteTaskRequest.java').read_text()
        self.assertIn('digest.matches("[a-f0-9]{64}")', schema)

    def test_probe_freshness_and_existing_create_admission_remain_wired(self):
        source = compact(self.composer)
        self.assertIn('if(locked||!nativeActive()||!environmentFresh)return', source)
        self.assertIn("worker!=='command'&&(!goal.trim()||probe?.available!==true)", source)
        self.assertIn('setProbe(null);setReview(null);setWriteIntent(false)', source)
        native = compact(self.connections)
        self.assertIn('valchecked=RemoteTaskRequest.firstForPrivateConnection(args)', native)
        self.assertIn('check(observed.optString("profileBinding")==checked.getString("_profileBinding"))', native)
        self.assertIn('check(environment.optString("bindingHash")==checked.getString("environmentBinding"))', native)
        self.assertIn('listOf("profileId","_profileBinding","environmentId","environmentBinding","worker","model","modeId","effort"))check(proof.optString(key)==checked.optString(key))', native)
        self.assertIn('check(observedProbe.optBoolean("available"))', native)
        self.assertIn('observedProbe.optJSONObject("modelOptions")?.optString("digest")==checked.getString("modelOptionsDigest")', native)
        inputs = compact((ROOT / 'app/src/main/java/dev/pocketengine/features/remote/RemoteInputAttachments.java').read_text())
        self.assertIn('RemoteTaskRequest.requireInputCapability(env)', inputs)
        self.assertIn('!request.getString("environmentBinding").equals(env.optString("bindingHash"))', inputs)
        self.assertIn('JSONObjectrequest=newJSONObject(window.request.toString())', inputs)
        self.assertIn('.put("request",request).put("environmentBinding",request.getString("environmentBinding"))', inputs)

    def test_final_reviews_display_frozen_request_model_and_effort(self):
        project = (ROOT / 'web-ui/src/RemoteTaskArtifacts.tsx').read_text().split('export function RemoteProjectReview', 1)[1].split('export function RemoteTaskArtifacts', 1)[0]
        inputs = (ROOT / 'web-ui/src/RemoteTaskInputs.tsx').read_text()
        self.assertIn('request.model', project)
        self.assertIn('request.effort ?', project)
        self.assertIn('scope.request?.model', inputs)
        self.assertIn('scope.request?.effort ?', inputs)
        self.assertIn('scope.request.effort', inputs)
        self.assertIn('review?.model', self.composer)
        self.assertIn('review?.effort', self.composer)

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
