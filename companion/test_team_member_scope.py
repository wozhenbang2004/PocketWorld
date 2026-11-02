"""Actual original Companion + stdio protocol fixtures, no accounts or inference."""
from test_companion import CompanionTests


class TeamMemberScopeTests(CompanionTests):
    def test_team_claude_restricts_writable_environment_and_resumes_same_session(self):
        self.manager.environments['computer']['writeAllowed'] = True
        with self.claude_fixture():
            first, _ = self.submit_claude('team-first', teamReadOnlyTools=True)
            first = self.finish(first['id'])
            self.assertEqual('completed', first['status'])
            self.assertTrue(first['teamReadOnlyTools'])
            self.assertTrue(first['readOnly'])
            self.assertFalse(first['osReadIsolationVerified'])
            self.assertTrue(first['protocolVerified'])
            second, _ = self.submit_claude('team-second', teamReadOnlyTools=True,
                threadId=first['threadId'], resumeJobId=first['id'])
            second = self.finish(second['id'])
        self.assertEqual('completed', second['status'])
        self.assertEqual(first['sessionId'], second['sessionId'])
        self.assertNotEqual(first['id'], second['id'])
        self.assertEqual(first['id'], second['resumeJobId'])
        self.assertTrue(self.manager.environments['computer']['writeAllowed'])
        self.assertEqual('11', (self.workspace/'claude-prompt-count').read_text())

    def test_team_claude_write_permission_is_denied_even_on_writable_environment(self):
        self.manager.environments['computer']['writeAllowed'] = True
        with self.claude_fixture('approval'):
            first, body = self.submit_claude('team-no-write', teamReadOnlyTools=True)
            result = self.finish(first['id'])
            duplicate = self.manager.submit(body)
        self.assertEqual('completed', result['status'])
        self.assertEqual(first['id'], duplicate['id'])
        self.assertFalse((self.workspace/'result.txt').exists())
        self.assertEqual([], result['pendingApprovals'])
        self.assertEqual('1', (self.workspace/'claude-prompt-count').read_text())

    def test_team_scope_cannot_be_used_as_generic_command_permission(self):
        status, _ = self.request('/v1/jobs', {'environmentId':'computer','worker':'command',
            'operationId':'bad-team','argv':['python','-c','print(1)'],'teamReadOnlyTools':True})
        self.assertEqual(409,status)
        self.assertEqual({}, self.manager.jobs)
