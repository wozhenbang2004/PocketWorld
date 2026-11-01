"""Real original ledger/raw HTTP/Git/files, loopback peer and explicitly synthetic CLI only.
These envelopes are protocol fixtures, not evidence of native Android owner admission.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import time
import unittest
import uuid
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import assignment_projects as projects
import assignment_workspaces as selected
from pocket_companion import JobManager, RequestError
import test_companion as original
from test_assignment_workspaces import CODING_PIPE


def uid():
    return str(uuid.uuid4())


class ProjectAssignmentTests(unittest.TestCase):
    def setUp(self):
        self.f = original.CompanionTests()
        self.f.setUp()
        self.manager = self.f.manager
        self.manager.environments['computer'].update(workers=['command', 'codex_app_server'], writeAllowed=True)
        self.raw = {'proof.txt': b'phone base\n', 'remove.bin': bytes(range(256)), 'assets/keep.bin': bytes(range(256)) * 1200}
        files = [{'path': path, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'executable': False, 'kind': 'file', 'mimeType': 'application/octet-stream'} for path, raw in sorted(self.raw.items())]
        self.manifest = {'format': 'pocketworld.project-package.v1', 'mode': 'source_project', 'project': {'id': uid(), 'name': 'fixture'}, 'source': {'projectId': uid(), 'checkoutId': uid(), 'sourceDigest': 'a' * 64}, 'entryPath': '', 'previewRoot': '', 'directories': ['assets', 'empty'], 'files': files, 'bytes': sum(len(raw) for raw in self.raw.values()), 'omitted': [], 'includesHistory': False, 'includesToolEnvironment': False, 'atomicSourceFence': False, 'policy': {'version': 1, 'privateFilesExcluded': True, 'generatedRootsIncluded': False}}
        native = {key: uid() for key in ['taskId', 'runId', 'parentTaskId', 'parentRunId', 'teamRunId', 'assignmentId', 'attemptId', 'workItemId']}
        native.update(coordinatorEpoch=1, parentEpoch=1, teamGeneration=1, assignmentRevision=1, memberId='native-author', memberGeneration=1, itemRevision=1, ownerGeneration=1)
        self.request = {'operationId': uid(), 'nativeAssignment': native, 'environmentId': 'computer', 'environmentBinding': self.manager.environment_binding('computer'), 'packageIdentityEncoding': projects.MANIFEST_ENCODING, 'packageIdentity': projects.manifest_identity(self.manifest), 'packageManifest': self.manifest, 'packageReference': {'id': uid(), 'sha256': 'b' * 64, 'manifestIdentity': projects.manifest_identity(self.manifest)}, 'files': [{'path': p, 'baseHash': hashlib.sha256(self.raw[p]).hexdigest(), 'baseVersion': 3} for p in ['proof.txt', 'remove.bin']] + [{'path': 'new.bin', 'baseHash': 'absent', 'baseVersion': 5}], 'limits': {'maxWallTimeSeconds': 10, 'maxOutputBytes': 8192, 'maxContributionBytes': 4 * 1024 * 1024}, 'commandScope': {key: True for key in ['allowCommands', 'sameUserExecutionRiskAcknowledged', 'privateSourceTransferAcknowledged', 'internalInferenceBillingUnboundedAcknowledged']}, 'approvalRevision': 1, 'approvalExpiresAt': self.manager.now() + 300000}

    def test_explicit_android_jsonstringer_identity_covers_controls_unicode_utf16_keys_and_exact_integers(self):
        controls = "".join(chr(value) for value in range(32))
        sample = {"z": 9223372036854775807, "a": [-9223372036854775808, 0, True, False, None], "😀": controls + '雪é😀/\\"\u2028\u2029', "\uffff": "UTF16 property ordering"}
        encoded = projects.manifest_bytes(sample)
        self.assertTrue(encoded.startswith(b'{"a":[-9223372036854775808,0,true,false,null],"z":9223372036854775807,'))
        self.assertLess(encoded.index('😀'.encode()), encoded.index('\uffff'.encode()))
        self.assertIn(b'\\/\\\\\\"', encoded)
        self.assertIn(b'\\u0000', encoded)
        self.assertIn('雪é😀'.encode(), encoded)
        self.assertIn('\u2028\u2029'.encode(), encoded)
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), projects.manifest_identity(sample))
        self.assertNotEqual(selected.digest(self.manifest), self.request['packageIdentity'])
        with self.assertRaises(ValueError):
            projects.manifest_bytes({'nonManifestFloat': 1.0})
        changed = json.loads(json.dumps(self.request));changed['packageIdentityEncoding'] = 'unknown_or_old_default'
        with self.assertRaises(RequestError):
            projects.prepare(self.manager, changed, RequestError)
        self.assertEqual([], self.manager.ledger.execute('SELECT operation_id FROM operations').fetchall())

    def tearDown(self):
        self.f.tearDown()

    def http(self, path, raw=None, method=None, headers=None):
        request = Request(self.f.base + path, data=raw, method=method, headers={'Authorization': 'Bearer ' + original.TOKEN, **(headers or {})})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, response.read(), dict(response.headers)
        except HTTPError as error:
            return error.code, error.read(), dict(error.headers)

    def prepared(self):
        status, result = self.f.request('/v1/assignment-projects', self.request)
        self.assertEqual(200, status, result)
        prepared = result['result']
        self.assertEqual('awaiting_upload', prepared['status'])
        self.assertFalse(self.manager.jobs)
        for file in prepared['inputFiles']:
            raw = self.raw[file['path']]
            status, result, _ = self.http('/v1/assignment-projects/' + self.request['operationId'] + '/files/' + str(file['index']), raw, 'PUT', {'Content-Type': 'application/octet-stream', 'X-Pocket-Prepare-Operation': self.request['operationId']})
            self.assertEqual(200, status, result)
            self.assertEqual('verified', json.loads(result)['result']['inputFiles'][file['index']]['state'])
        final = {'finalizeOperationId': uid(), 'packageIdentity': self.request['packageIdentity']}
        status, result = self.f.request('/v1/assignment-projects/' + self.request['operationId'] + '/finalize', final)
        self.assertEqual(200, status, result)
        return result['result'], final

    def test_raw_binary_package_real_git_job_and_exact_tombstone_contribution(self):
        prepared, finalize = self.prepared()
        self.assertEqual('ready', prepared['status'])
        self.assertTrue(prepared['fullProject'])
        self.assertFalse(prepared['originalGitHistoryIncluded'])
        self.assertRegex(prepared['gitBaseCommit'], r'^[a-f0-9]{40}$')
        root = Path(prepared['workspace'])
        self.assertTrue((root / 'empty').is_dir())
        for path, raw in self.raw.items():
            self.assertEqual(raw, (root / path).read_bytes())
        again = projects.finalize(self.manager, self.request['operationId'], finalize, RequestError)
        self.assertEqual(prepared['gitBaseCommit'], again['gitBaseCommit'])
        pipe = CODING_PIPE.replace("Path('turn-started').write_text('one original turn')", "pass # Native turn receipts, not an extra unapproved project file")
        pipe = pipe.replace("Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n')", "Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\\n');Path('new.bin').write_bytes(bytes([255,0,128,7]));Path('remove.bin').unlink()")
        script = self.f.root / 'explicit-project-pipe.py'
        script.write_text(pipe)
        reference = {key: prepared[key] for key in ['mode', 'prepareOperationId', 'checkoutId', 'checkoutVersion', 'nativeAssignment']}
        reference.update(preparationNativeAssignment=reference['nativeAssignment'], reservationId=uid(), approvalRevision=2, approvalExpiresAt=self.manager.now() + 300000)
        body = {'operationId': uid(), 'environmentId': 'computer', 'environmentBinding': self.manager.environment_binding('computer'), 'worker': 'codex_app_server', 'readOnly': False, 'prompt': 'Explicitly synthetic project command fixture', 'timeoutSeconds': 10, 'assignmentWorkspace': reference}
        with patch.object(self.manager, '_launch', return_value=([sys.executable, str(script), 'command-approval'], str(root))):
            status, result = self.f.request('/v1/jobs', body)
        self.assertEqual(200, status, result)
        job = result['result']
        until = time.monotonic() + 5
        while time.monotonic() < until:
            actual = self.manager.get(job['id'])
            if actual.get('pendingApprovals'):
                break
            time.sleep(.01)
        self.assertEqual('awaiting_approval', actual['status'], actual)
        self.assertTrue(actual['pendingApprovals'][0]['canAccept'], actual)
        self.assertTrue(actual['pendingApprovals'][0]['projectCommandRisk'])
        status, result = self.f.request('/v1/jobs/' + job['id'] + '/approval', {'requestId': actual['pendingApprovals'][0]['requestId'], 'decision': 'accept'})
        self.assertEqual(200, status, result)
        until = time.monotonic() + 5
        while time.monotonic() < until:
            actual = self.manager.get(job['id'])
            if actual['status'] in {'completed', 'failed', 'unknown', 'blocked', 'cancelled'} and actual.get('dispatchFenceFinal'):
                break
            time.sleep(.01)
        self.assertEqual('completed', actual['status'], actual)
        freeze = {'operationId': uid(), 'jobOperationId': body['operationId'], 'prepareOperationId': prepared['prepareOperationId'], 'checkoutVersion': prepared['checkoutVersion']}
        status, result = self.f.request('/v1/jobs/' + job['id'] + '/project-contributions', freeze)
        self.assertEqual(200, status, result)
        snapshot = result['result']
        self.assertEqual({'proof.txt': 'modify', 'remove.bin': 'delete', 'new.bin': 'add'}, {v['path']: v['change'] for v in snapshot['assignmentContribution']['files']})
        removed = next(v for v in snapshot['files'] if v['path'] == 'remove.bin')
        self.assertTrue(removed['tombstone']);self.assertEqual('absent', removed['sha256'])
        from urllib.parse import urlencode
        query = urlencode({'version': snapshot['snapshotVersion'], 'path': 'new.bin', 'sha256': hashlib.sha256(bytes([255,0,128,7])).hexdigest(), 'bytes': 4, 'jobOperationId': body['operationId']})
        status, raw, headers = self.http('/v1/jobs/' + job['id'] + '/project-contributions/' + snapshot['snapshotId'] + '/file?' + query)
        self.assertEqual(200, status, raw);self.assertEqual(bytes([255,0,128,7]), raw)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), headers['X-Pocket-Content-SHA256'])
        self.assertEqual(1, len(self.manager.jobs))
        self.assertEqual(self.raw['assets/keep.bin'], (root / 'assets/keep.bin').read_bytes())
        self.assertEqual('unknown', actual['inferenceUsage']['status'])
        created = next(v for v in snapshot['assignmentContribution']['files'] if v['path'] == 'new.bin')
        self.assertEqual(5, created['appBaseVersion'])  # Real prior tombstone version is not reset to zero.
        with self.assertRaises(RequestError):projects.cancel(self.manager, prepared['prepareOperationId'], RequestError)
        self.manager.jobs.pop(job['id'])  # Normal old-job cache pruning cannot release original checkout mapping.
        with self.assertRaises(RequestError):projects.bound_environment(self.manager, body, RequestError)


    def test_interrupted_body_is_query_only_and_restart_does_not_replay_git_or_body(self):
        prepared = projects.prepare(self.manager, self.request, RequestError)
        slot = prepared['inputFiles'][0]
        with self.assertRaises(RequestError):
            projects.upload(self.manager, self.request['operationId'], 0, slot['bytes'], io.BytesIO(b''), RequestError)
        class NeverRead:
            def read(self, *_):
                raise AssertionError('A reserved body must never be read twice')
        with self.assertRaises(RequestError):
            projects.upload(self.manager, self.request['operationId'], 0, slot['bytes'], NeverRead(), RequestError)
        self.assertEqual('unknown', projects.operation(self.manager, self.request['operationId'], RequestError)['status'])
        # Original SQLite row, not a second map, survives real manager close/reopen.
        self.manager.close()
        self.f.manager = JobManager(self.f.config, original.TOKEN)
        self.manager = self.f.manager
        self.f.server.manager = self.manager
        again = projects.operation(self.manager, self.request['operationId'], RequestError)
        self.assertEqual('unknown', again['status']);self.assertFalse(self.manager.jobs)
        self.assertFalse((Path(prepared['workspace']) / '.git').exists())

    def test_cancel_expiry_and_changed_environment_prevent_first_body(self):
        prepared = projects.prepare(self.manager, self.request, RequestError)
        projects.cancel(self.manager, self.request['operationId'], RequestError)
        with self.assertRaises(RequestError):
            projects.upload(self.manager, self.request['operationId'], 0, prepared['inputFiles'][0]['bytes'], io.BytesIO(b'bad'), RequestError)
        self.assertEqual('cancelled', projects.operation(self.manager, self.request['operationId'], RequestError)['status'])
        self.assertFalse(self.manager.jobs)
        changed = json.loads(json.dumps(self.request));changed['operationId'] = uid();changed['nativeAssignment']['attemptId'] = uid();changed['approvalExpiresAt'] = self.manager.now() - 1
        with self.assertRaises(RequestError):projects.prepare(self.manager, changed, RequestError)
        changed['approvalExpiresAt'] = self.manager.now() + 300000;changed['environmentBinding'] = '0' * 64
        with self.assertRaises(RequestError):projects.prepare(self.manager, changed, RequestError)
        self.assertEqual('not_found', self.manager.operation(changed['operationId'])['status'])

    def test_exact_prepare_identity_and_drift_cannot_relabel_or_replace_original(self):
        prepared, _ = self.prepared()
        self.assertEqual(prepared['checkoutId'], projects.prepare(self.manager, self.request, RequestError)['checkoutId'])
        changed = json.loads(json.dumps(self.request));changed['files'][0]['baseVersion'] += 1
        with self.assertRaises(RequestError):projects.prepare(self.manager, changed, RequestError)
        reference = {key: prepared[key] for key in ['mode', 'prepareOperationId', 'checkoutId', 'checkoutVersion', 'nativeAssignment']}
        reference.update(preparationNativeAssignment=reference['nativeAssignment'], reservationId=uid(), approvalRevision=2, approvalExpiresAt=self.manager.now() + 300000)
        body = {'environmentId': 'computer', 'environmentBinding': self.manager.environment_binding('computer'), 'worker': 'codex_app_server', 'readOnly': False, 'timeoutSeconds': 10, 'assignmentWorkspace': reference}
        (Path(prepared['workspace']) / 'proof.txt').write_bytes(b'DRIFT')
        with self.assertRaises(RequestError):projects.bound_environment(self.manager, body, RequestError)
        self.assertFalse(self.manager.jobs)

    def test_sensitive_manifest_and_symlink_are_refused_without_following(self):
        changed = json.loads(json.dumps(self.request));changed['packageManifest']['files'][0]['path'] = '.ssh/id_rsa';changed['packageIdentity'] = projects.manifest_identity(changed['packageManifest']);changed['packageReference']['manifestIdentity'] = changed['packageIdentity']
        with self.assertRaises(RequestError):projects.prepare(self.manager, changed, RequestError)
        self.assertEqual('not_found', self.manager.operation(changed['operationId'])['status'])
        prepared = projects.prepare(self.manager, self.request, RequestError)
        first = prepared['inputFiles'][0]
        root = Path(prepared['workspace'])
        (root / 'assets').rmdir()
        target = self.f.root / 'never-write-here';target.mkdir()
        (root / 'assets').symlink_to(target, target_is_directory=True)
        with self.assertRaises((RequestError, OSError)):
            projects.upload(self.manager, self.request['operationId'], first['index'], first['bytes'], io.BytesIO(self.raw[first['path']]), RequestError)
        self.assertFalse(list(target.iterdir()))
        self.assertEqual('unknown', projects.operation(self.manager, self.request['operationId'], RequestError)['status'])

    def test_failed_git_finalize_is_unknown_and_never_removes_lock_or_retries(self):
        prepared = projects.prepare(self.manager, self.request, RequestError)
        for file in prepared['inputFiles']:
            projects.upload(self.manager, self.request['operationId'], file['index'], file['bytes'], io.BytesIO(self.raw[file['path']]), RequestError)
        root = Path(prepared['workspace'])
        original_git = projects._git
        def fail_after_init(manager, directory, *args, **kwargs):
            if args[0] == 'config':
                (root / '.git/index.lock').write_bytes(b'actual interrupted Git lock')
                raise OSError('controlled Git failure')
            return original_git(manager, directory, *args, **kwargs)
        final = {'finalizeOperationId': uid(), 'packageIdentity': self.request['packageIdentity']}
        with patch.object(projects, '_git', side_effect=fail_after_init):
            with self.assertRaises(OSError):projects.finalize(self.manager, self.request['operationId'], final, RequestError)
        self.assertEqual('unknown', projects.finalize(self.manager, self.request['operationId'], final, RequestError)['status'])
        self.assertEqual(b'actual interrupted Git lock', (root / '.git/index.lock').read_bytes())
        self.assertFalse(self.manager.jobs)


if __name__ == '__main__':
    unittest.main()
