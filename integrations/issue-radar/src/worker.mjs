// PocketWorld remote-notice relay. No client credential is used by GitHub scanning.
// D1 is the delivery authority: a push hint never starts a task or substitutes a stored event.
const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };
const respond = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
const now = () => new Date().toISOString();

function credential(request) {
  const value = request.headers.get('authorization') || '';
  return value.startsWith('Bearer ') ? value.slice(7) : '';
}
async function sameSecret(candidate, expected) {
  if (!expected || expected.length < 32 || !candidate) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([crypto.subtle.digest('SHA-256', enc.encode(candidate)), crypto.subtle.digest('SHA-256', enc.encode(expected))]);
  const x = new Uint8Array(a), y = new Uint8Array(b);
  let difference = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) difference |= (x[i] || 0) ^ (y[i] || 0);
  return difference === 0;
}
const short = (input, max) => String(input ?? '').trim().slice(0, max);
export function safeNotice(payload) {
  if (!payload || typeof payload !== 'object') throw new Error('invalid_event');
  const key = short(payload.key, 180), kind = short(payload.kind, 40);
  const title = short(payload.title, 180), body = short(payload.body, 600);
  const origin = short(payload.origin, 120);
  if (!key || !/^[A-Za-z0-9:._/#-]+$/.test(key) || !['github_issue', 'github_pr', 'agent_task', 'service'].includes(kind) || !title || !origin) throw new Error('invalid_event');
  const url = String(payload.url || '');
  let parsed;
  try { parsed = new URL(url); } catch { throw new Error('invalid_link'); }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.hash || (kind.startsWith('github_') && parsed.hostname !== 'github.com')) throw new Error('invalid_link');
  const priority = payload.priority === 'attention' ? 'attention' : 'normal';
  return { key, kind, title, body, origin, url: parsed.href, priority };
}
async function record(env, event) {
  const clean = safeNotice(event);
  const write = await env.DB.prepare('INSERT OR IGNORE INTO events (source_key, kind, title, body, origin, url, priority, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
    .bind(clean.key, clean.kind, clean.title, clean.body, clean.origin, clean.url, clean.priority, now()).run();
  return write.meta?.changes === 1;
}
export function scoreIssue(issue) {
  const labels = (issue.labels || []).map(item => typeof item === 'string' ? item.toLowerCase() : String(item.name || '').toLowerCase());
  const welcoming = labels.some(label => ['help wanted', 'good first issue', 'prs welcome', 'contributions welcome'].includes(label));
  const title = String(issue.title || '').toLowerCase(), description = String(issue.body || '');
  const hasRepro = /reproduc|steps to|stack trace|traceback|expected behavior|actual behavior|复现|错误日志|最小示例/i.test(description);
  const type = /\bbug\b|regression|错误|崩溃|失败/i.test(title + ' ' + labels.join(' ')) ? 'bug' : 'other';
  const unassigned = !issue.assignees?.length && !issue.assignee;
  const score = Math.min(100, 40 + (welcoming ? 30 : 0) + (type === 'bug' ? 12 : 0) + (hasRepro ? 12 : 0) + (unassigned ? 6 : 0));
  return { score, welcoming, hasRepro, unassigned, type };
}
function repoList(raw) {
  return [...new Set(String(raw || '').split(/[\s,;]+/).filter(Boolean).filter(name => /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(name)))].slice(0, 70);
}
async function monitorRepository(env, name) {
  const [owner, repo] = name.split('/');
  const headers = { accept: 'application/vnd.github+json', 'user-agent': 'PocketWorld-Issue-Radar', 'x-github-api-version': '2022-11-28' };
  if (env.GITHUB_TOKEN) headers.authorization = 'Bearer ' + env.GITHUB_TOKEN;
  const response = await fetch('https://api.github.com/repos/' + encodeURIComponent(owner) + '/' + encodeURIComponent(repo) + '/issues?state=open&sort=created&direction=desc&per_page=100', { headers });
  if (!response.ok) throw new Error(name + ' upstream_status_' + response.status);
  const incoming = await response.json();
  if (!Array.isArray(incoming)) throw new Error('upstream_schema');
  const state = await env.DB.prepare('SELECT initialized FROM watched_repos WHERE name = ?').bind(name).first();
  const initialized = state?.initialized === 1;
  let published = 0;
  for (const issue of incoming.reverse()) {
    if (issue.pull_request || !Number.isSafeInteger(issue.number) || !issue.html_url) continue;
    const key = name + '#' + issue.number;
    const previous = await env.DB.prepare('SELECT welcoming FROM watched_issues WHERE issue_key = ?').bind(key).first();
    const quality = scoreIssue(issue);
    await env.DB.prepare('INSERT INTO watched_issues (issue_key, welcoming, seen_at) VALUES (?, ?, ?) ON CONFLICT(issue_key) DO UPDATE SET welcoming = excluded.welcoming, seen_at = excluded.seen_at')
      .bind(key, quality.welcoming ? 1 : 0, now()).run();
    if (!initialized || !quality.unassigned) continue;
    let phase = '';
    if (!previous) phase = 'new';
    else if (!previous.welcoming && quality.welcoming) phase = 'ready';
    if (!phase) continue;
    const event = {
      key: 'issue:' + key + ':' + phase, kind: 'github_issue',
      title: name + ' #' + issue.number + ' · ' + short(issue.title, 130),
      body: (quality.welcoming ? '含外部贡献标签 · ' : '') + (quality.hasRepro ? '包含复现线索 · ' : '') + '尚无 GitHub assignee；认领及关联 PR 需进一步核实',
      origin: name, url: issue.html_url, priority: quality.welcoming && quality.score >= 80 ? 'attention' : 'normal',
    };
    if (await record(env, event)) published++;
  }
  await env.DB.prepare('INSERT INTO watched_repos (name, initialized, checked_at) VALUES (?, 1, ?) ON CONFLICT(name) DO UPDATE SET initialized=1, checked_at=excluded.checked_at')
    .bind(name, now()).run();
  return { repository: name, published, scanned: incoming.length, possibleOverflow: incoming.length === 100 };
}
export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health' && request.method === 'GET') return respond({ ok: true });
      const incoming = credential(request);
      if (url.pathname === '/v1/events' && request.method === 'GET') {
        if (!await sameSecret(incoming, env.INBOX_TOKEN)) return respond({ error: 'unauthorized' }, 401);
        const cursor = Number(url.searchParams.get('after') || 0);
        if (!Number.isSafeInteger(cursor) || cursor < 0) return respond({ error: 'invalid_cursor' }, 400);
        const list = await env.DB.prepare('SELECT seq, source_key, kind, title, body, origin, url, priority, created_at FROM events WHERE seq > ? ORDER BY seq ASC LIMIT 100').bind(cursor).all();
        return respond({ format: 'pocketworld.remote-inbox.v1', events: list.results || [], hasMore: (list.results || []).length === 100 });
      }
      if (url.pathname === '/v1/events' && request.method === 'POST') {
        if (!await sameSecret(incoming, env.INGEST_TOKEN)) return respond({ error: 'unauthorized' }, 401);
        const type = request.headers.get('content-type') || '';
        if (!type.toLowerCase().startsWith('application/json')) return respond({ error: 'json_required' }, 415);
        const raw = await request.text();
        if (raw.length > 8000) return respond({ error: 'too_large' }, 413);
        const created = await record(env, JSON.parse(raw));
        return respond({ accepted: created }, created ? 201 : 200);
      }
      return respond({ error: 'not_found' }, 404);
    } catch (error) {
      if (/invalid_event|invalid_link/.test(String(error.message))) return respond({ error: String(error.message) }, 400);
      return respond({ error: 'temporarily_unavailable' }, 503);
    }
  },
  async scheduled(_controller, env) {
    for (const repository of repoList(env.WATCH_REPOS)) {
      try { await monitorRepository(env, repository); }
      catch (error) { console.error('Issue watch:', repository, String(error)); }
    }
  },
};
