"""Controlled local server/files/fake Codex protocol only; not a live CLI/account/device."""
import base64
import os
import sys
import time
import unittest
from unittest.mock import patch
from pocket_companion import JobManager, RequestError
import test_companion as original_fixture


class ResultSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.f = original_fixture.CompanionTests()
        self.f.setUp()

    def tearDown(self):
        self.f.tearDown()

    def source(self):
        f = self.f
        job = f.submit("from pathlib import Path; Path('proof.txt').write_text('ORIGINAL RESULT BYTES')")
        result = f.finish(job['id'])
        deadline = time.monotonic() + 2
        while f.manager.resources and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertEqual('completed', result['status'])
        return result

    def capture(self, job, paths=None, operation='snapshot-one'):
        body = {'operationId': operation, 'jobOperationId': job['operationId'], 'environmentId': job['environmentId'], 'worker': job['worker'], 'paths': paths or ['proof.txt']}
        for key in ('threadId', 'turnId'):
            if job.get(key):
                body[key] = job[key]
        return self.f.request('/v1/jobs/' + job['id'] + '/snapshots', body)

    def codex(self):
        from test_codex_driver import FIXTURE
        f = self.f
        script = f.root / 'snapshot-protocol-fixture.py'
        script.write_text(FIXTURE)
        f.manager.environments['computer']['workers'] = ['codex_app_server']
        (f.workspace / 'proof.txt').write_text('EXPLICIT EXISTING SOURCE, AUTHOR NOT PROVEN')
        launch = ([sys.executable, str(script), 'final-without-start-ack'], str(f.workspace))
        with patch.object(f.manager, '_launch', return_value=launch):
            status, response = f.request('/v1/jobs', {'operationId': 'source-codex', 'environmentId': 'computer', 'worker': 'codex_app_server', 'prompt': 'Read only fixture', 'readOnly': True})
        self.assertEqual(200, status, response)
        job = f.finish(response['result']['id'])
        self.assertEqual('completed', job['status'])
        deadline = time.monotonic() + 2
        while f.manager.resources and time.monotonic() < deadline:
            time.sleep(.005)
        return job, launch

    def continuation(self, job, snapshot):
        return {'operationId': 'snapshot-explicit-continuation', 'environmentId': 'computer', 'worker': 'codex_app_server', 'prompt': 'Continue this exact reviewed version',
                'threadId': job['threadId'], 'resumeJobId': job['id'], 'resultSnapshotId': snapshot['snapshotId'], 'resultSnapshotVersion': snapshot['snapshotVersion']}

    def test_original_selected_bytes_survive_edits_and_are_not_broadcast_in_job_lists(self):
        job = self.source()
        status, response = self.capture(job)
        self.assertEqual(200, status, response)
        snapshot = response['result']
        (self.f.workspace / 'proof.txt').write_text('LATER WORKSPACE CHANGE')
        status, result = self.f.request('/v1/jobs/' + job['id'] + '/snapshots/' + snapshot['snapshotId'] + '?version=' + snapshot['snapshotVersion'] + '&path=proof.txt')
        self.assertEqual(200, status, result)
        self.assertEqual(b'ORIGINAL RESULT BYTES', base64.b64decode(result['result']['base64']))
        self.assertTrue(result['result']['immutableArtifact'])
        self.assertFalse(result['result']['authorshipVerified'])
        self.assertNotIn('base64', str(self.f.manager.list_jobs()))
        self.assertEqual(job['operationId'], result['result']['jobOperationId'])

    def test_lost_ack_query_and_duplicate_never_recapture_changed_files(self):
        job = self.source()
        _, first = self.capture(job)
        (self.f.workspace / 'proof.txt').write_text('CHANGED AFTER ORIGINAL COMMIT')
        _, repeated = self.capture(job)
        self.assertEqual(first['result']['snapshotVersion'], repeated['result']['snapshotVersion'])
        _, query = self.f.request('/v1/jobs/' + job['id'] + '/snapshot-operations/snapshot-one')
        self.assertEqual(first['result']['snapshotId'], query['result']['snapshotId'])
        self.assertEqual(1, len(self.f.manager.jobs[job['id']]['resultSnapshots']))
        self.assertEqual(409, self.capture(job, ['other.txt'])[0])

    def test_file_changes_during_capture_are_refused_before_publication(self):
        job = self.source()
        original = self.f.manager._artifact_bytes
        calls = []
        def changing(env, path):
            calls.append(path)
            if len(calls) == 2:
                (self.f.workspace / path).write_text('RACE CHANGED ACTUAL FILE')
            return original(env, path)
        with patch.object(self.f.manager, '_artifact_bytes', side_effect=changing):
            status, response = self.capture(job)
        self.assertEqual(409, status, response)
        self.assertEqual([], self.f.manager.jobs[job['id']].get('resultSnapshots', []))

    def test_explicit_scope_secret_symlink_and_total_limits_are_enforced(self):
        job = self.source()
        (self.f.workspace / '.env').write_text('NEVER CAPTURE THIS SECRET')
        with patch.object(self.f.manager, '_artifact_bytes', wraps=self.f.manager._artifact_bytes) as reads:
            self.assertEqual(403, self.capture(job, ['.env'])[0])
            self.assertEqual(403, self.capture(job, ['../outside'])[0])
            self.assertEqual(0, reads.call_count)
        (self.f.workspace / 'linked.txt').symlink_to(self.f.workspace / 'proof.txt')
        self.assertEqual(403, self.capture(job, ['linked.txt'])[0])
        outside = self.f.root / 'outside.txt'
        outside.write_text('PRIVATE OUTSIDE SOURCE')
        os.link(outside, self.f.workspace / 'hardlinked.txt')
        self.assertEqual(403, self.capture(job, ['hardlinked.txt'])[0])
        for name in ['a.bin', 'b.bin', 'c.bin']:
            (self.f.workspace / name).write_bytes(b'a' * 200_000)
        self.assertEqual(413, self.capture(job, ['a.bin', 'b.bin', 'c.bin'])[0])
        self.assertEqual([], self.f.manager.jobs[job['id']].get('resultSnapshots', []))

    def test_original_environment_and_active_resource_fences_remain_required(self):
        job = self.source()
        resource = self.f.manager.jobs[job['id']]['resourceId']
        self.f.manager.resources.add(resource)
        self.assertEqual(409, self.capture(job)[0])
        self.f.manager.resources.remove(resource)
        self.f.manager.environments['computer']['root'] = str(self.f.root)
        self.assertEqual(409, self.capture(job)[0])
        self.assertEqual([], self.f.manager.jobs[job['id']].get('resultSnapshots', []))

    def test_snapshot_retained_in_original_atomic_job_document_after_restart(self):
        job = self.source()
        _, response = self.capture(job)
        self.f.manager.close()
        self.f.manager = JobManager(self.f.config, original_fixture.TOKEN)
        self.f.server.manager = self.f.manager
        snapshot = response['result']
        actual = self.f.manager.result_snapshot(job['id'], snapshot['snapshotId'], snapshot['snapshotVersion'], 'proof.txt')
        self.assertEqual(b'ORIGINAL RESULT BYTES', base64.b64decode(actual['base64']))
        self.assertEqual(snapshot['snapshotVersion'], self.f.manager.result_snapshot_operation(job['id'], 'snapshot-one')['snapshotVersion'])

    def test_replaced_physical_workspace_same_configured_path_never_borrows_original_job_identity(self):
        job, _launch = self.codex()
        old = self.f.root / 'old-workspace'
        self.f.workspace.rename(old)
        self.f.workspace.mkdir()
        (self.f.workspace / 'proof.txt').write_bytes((old / 'proof.txt').read_bytes())
        self.assertFalse(self.f.manager.get(job['id'])['controls']['threadResume'])
        self.assertEqual(409, self.capture(job)[0])
        self.assertEqual([], self.f.manager.jobs[job['id']].get('resultSnapshots', []))

    def test_same_version_continuation_keeps_original_job_and_proves_prelaunch_version(self):
        job, launch = self.codex()
        status, captured = self.capture(job)
        self.assertEqual(200, status, captured)
        snapshot = captured['result']
        checked = self.f.manager.verify_result_snapshot_current(job['id'], snapshot['snapshotId'], snapshot['snapshotVersion'])
        self.assertTrue(checked['matchesCurrent'])
        self.assertFalse(checked['executionAuthorized'])
        with patch.object(self.f.manager, '_launch', return_value=launch):
            status, response = self.f.request('/v1/jobs', self.continuation(job, snapshot))
        self.assertEqual(200, status, response)
        actual = self.f.finish(response['result']['id'])
        self.assertEqual('completed', actual['status'])
        self.assertTrue(actual['snapshotPrelaunchVerified'])
        self.assertEqual(job['id'], actual['resumeJobId'])
        self.assertEqual(snapshot['snapshotVersion'], actual['resultSnapshotVersion'])
        self.assertEqual('11', (self.f.workspace / 'codex-turn-count').read_text())

    def test_drift_before_post_and_between_admission_and_launch_never_starts_old_version(self):
        job, launch = self.codex()
        _, response = self.capture(job)
        snapshot = response['result']
        body = self.continuation(job, snapshot)
        with patch.object(self.f.manager.pool, 'submit') as queued, patch.object(self.f.manager, '_launch', return_value=launch):
            status, admitted = self.f.request('/v1/jobs', body)
        self.assertEqual(200, status, admitted)
        (self.f.workspace / 'proof.txt').write_text('CHANGED WHILE ORIGINAL ATTEMPT QUEUED')
        args = queued.call_args.args
        with patch('pocket_companion.subprocess.Popen') as process:
            args[0](*args[1:])
        self.assertEqual(0, process.call_count)
        self.assertEqual('blocked', self.f.manager.get(admitted['result']['id'])['status'])
        self.assertFalse(self.f.manager.get(admitted['result']['id'])['snapshotPrelaunchVerified'])
        self.assertEqual('1', (self.f.workspace / 'codex-turn-count').read_text())
        with self.assertRaises(RequestError):
            self.f.manager.verify_result_snapshot_current(job['id'], snapshot['snapshotId'], snapshot['snapshotVersion'])
