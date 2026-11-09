import test from 'node:test';
import assert from 'node:assert/strict';
import { safeNotice, scoreIssue } from '../src/worker.mjs';

test('external notice demands https and rejects javascript and mismatched GitHub hosts', () => {
  const base = { key: 'issue:mybatis/mybatis-3#99:new', kind: 'github_issue', title: 'Issue', origin: 'mybatis/mybatis-3', url: 'https://github.com/mybatis/mybatis-3/issues/99' };
  assert.equal(safeNotice(base).url, base.url);
  assert.throws(() => safeNotice({ ...base, url: 'javascript:alert(1)' }), /invalid_link/);
  assert.throws(() => safeNotice({ ...base, url: 'https://evil.com/a' }), /invalid_link/);
  assert.throws(() => safeNotice({ ...base, key: '../secret' }), /invalid_event/);
  assert.equal(safeNotice({ ...base, priority: 'SUDO' }).priority, 'normal');
});
test('unassigned is a hint, not a verified claim or automatic action', () => {
  const quality = scoreIssue({ title: 'Bug: mapper crashes', body: 'Steps to reproduce', assignees: [], labels: [{ name: 'help wanted' }] });
  assert.equal(quality.unassigned, true);
  assert.equal(quality.welcoming, true);
  assert.ok(quality.score >= 80);
  assert.equal(scoreIssue({ title: 'Bug', labels: [], assignee: { login: 'maintainer' } }).unassigned, false);
});
