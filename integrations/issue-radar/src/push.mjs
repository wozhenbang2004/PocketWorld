// Business glue only: RFC 8291/8292 encryption is supplied by the pinned library.
// This module is imported only by the explicitly selected push-enabled Worker entry.
import { buildPushPayload } from '@block65/webcrypto-web-push';

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };
const MAX_DEVICES = 16;
const MAX_BATCH = 8;
const LEASE_MS = 30 * 24 * 60 * 60 * 1000;
const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const tokenPattern = /^[A-Za-z0-9_-]{43,128}$/;
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
const hash = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))))
  .map(byte => byte.toString(16).padStart(2, '0')).join('');
async function equal(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length < 32 || b.length < 32) return false;
  const x = await hash(a), y = await hash(b);
  let different = 0;
  for (let index = 0; index < x.length; index++) different |= x.charCodeAt(index) ^ y.charCodeAt(index);
  return different === 0;
}
function enabled(env) {
  return env.PUSH_ENABLED === 'true' && typeof env.PUSH_REGISTER_TOKEN === 'string' && env.PUSH_REGISTER_TOKEN.length >= 32 && env.PUSH_REGISTER_TOKEN !== env.INBOX_TOKEN && env.PUSH_REGISTER_TOKEN !== env.INGEST_TOKEN &&
    /^[A-Za-z0-9_-]{87}$/.test(env.PUSH_VAPID_PUBLIC_KEY || '') && /^[A-Za-z0-9_-]{43}$/.test(env.PUSH_VAPID_PRIVATE_KEY || '') &&
    /^(mailto:|https:\/\/)/.test(env.PUSH_VAPID_SUBJECT || '') && hosts(env).length > 0;
}
function hosts(env) {
  // Exact operator allowlist, not a client-provided hostname or wildcard. Redirects are forbidden.
  return String(env.PUSH_ALLOWED_HOSTS || '').split(',').map(value => value.trim().toLowerCase())
    .slice(0, 16).filter(value => /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$/.test(value) &&
      !value.endsWith('.localhost') && !value.endsWith('.local') && !value.endsWith('.internal'));
}
function subscription(raw, env) {
  if (!raw || typeof raw.endpoint !== 'string' || raw.endpoint.length > 1000) throw new Error('invalid');
  const url = new URL(raw.endpoint);
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || (url.port && url.port !== '443') || !hosts(env).includes(url.hostname)) throw new Error('invalid');
  if (!/^[A-Za-z0-9_-]{87}$/.test(raw.keys?.p256dh || '') || !/^[A-Za-z0-9_-]{22}$/.test(raw.keys?.auth || '')) throw new Error('invalid');
  return { endpoint: url.href, expirationTime: null, keys: { p256dh: raw.keys.p256dh, auth: raw.keys.auth } };
}
async function input(request) {
  if (!(request.headers.get('content-type') || '').startsWith('application/json')) throw new Error('invalid');
  // Consume incrementally; Content-Length alone is not a bound on streamed bodies.
  const reader = request.body?.getReader();
  if (!reader) throw new Error('invalid');
  let size = 0; const chunks = [];
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 4096) { await reader.cancel(); throw new Error('invalid'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder().decode(bytes));
}
async function send(env, row, payload) {
  // Revalidate retained URLs against the CURRENT operator allowlist on every send.
  const target = subscription(JSON.parse(row.subscription), env);
  const init = await buildPushPayload({ data: payload, options: { ttl: 3600, urgency: 'normal', topic: 'pocketworld-inbox' } }, target, {
    subject: env.PUSH_VAPID_SUBJECT, publicKey: env.PUSH_VAPID_PUBLIC_KEY, privateKey: env.PUSH_VAPID_PRIVATE_KEY,
  });
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(target.endpoint, { ...init, redirect: 'error', signal: controller.signal });
    await response.body?.cancel();
    return response.status;
  } finally { clearTimeout(timeout); }
}
export async function pushRoute(request, env) {
  const path = new URL(request.url).pathname;
  if (!path.startsWith('/v1/push/')) return null;
  if (!enabled(env)) return reply({ error: 'push_not_enabled' }, 503);
  const authorization = request.headers.get('authorization') || '';
  const bearer = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
  if (request.method === 'GET' && path === '/v1/push/config') {
    if (!await equal(bearer, env.PUSH_REGISTER_TOKEN)) return reply({ error: 'unauthorized' }, 401);
    try { await env.DB.prepare('SELECT COUNT(*) AS total FROM push_devices').first(); }
    catch { return reply({ error: 'push_storage_not_ready' }, 503); }
    return reply({ format: 'pocketworld.push.v1', vapid: env.PUSH_VAPID_PUBLIC_KEY, allowedHosts: hosts(env) });
  }
  try {
    const body = await input(request);
    if (!idPattern.test(body.deviceId || '')) return reply({ error: 'invalid_device' }, 400);
    if (request.method === 'POST' && path === '/v1/push/register') {
      if (!await equal(bearer, env.PUSH_REGISTER_TOKEN)) return reply({ error: 'unauthorized' }, 401);
      if (!tokenPattern.test(body.secret || '')) return reply({ error: 'invalid_device' }, 400);
      const target = subscription(body.subscription, env);
      const now = Date.now();
      await env.DB.prepare('DELETE FROM push_devices WHERE expires_at <= ?').bind(now).run();
      const existing = await env.DB.prepare('SELECT * FROM push_devices WHERE device_id = ?').bind(body.deviceId).first();
      const secretHash = await hash(body.secret);
      if (existing && !await equal(existing.secret_hash, secretHash)) return reply({ error: 'unauthorized' }, 401);
      const encoded = JSON.stringify(target);
      if (existing && existing.subscription === encoded) {
        await env.DB.prepare('UPDATE push_devices SET expires_at = ? WHERE device_id = ? AND secret_hash = ?').bind(now + LEASE_MS, body.deviceId, secretHash).run();
      } else {
        const challenge = crypto.randomUUID();
        if (existing) {
          await env.DB.prepare('UPDATE push_devices SET subscription = ?, challenge = ?, verified = 0, expires_at = ?, next_attempt_at = 0, attempts = 0 WHERE device_id = ? AND secret_hash = ?')
            .bind(encoded, challenge, now + LEASE_MS, body.deviceId, secretHash).run();
        } else {
          // A single SQL statement keeps the fleet bound when registrations race.
          const result = await env.DB.prepare('INSERT OR IGNORE INTO push_devices (device_id, secret_hash, subscription, challenge, expires_at) SELECT ?, ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM push_devices) < ?')
            .bind(body.deviceId, secretHash, encoded, challenge, now + LEASE_MS, MAX_DEVICES).run();
          if (!result.meta?.changes) return reply({ error: 'device_limit' }, 409);
        }
      }
      // The secret challenge is NEVER returned over registration HTTPS. It must arrive via push.
      return reply({ status: 'registered', verified: !!existing && existing.subscription === encoded && existing.verified === 1, expiresAt: now + LEASE_MS });
    }
    if (!tokenPattern.test(bearer)) return reply({ error: 'unauthorized' }, 401);
    const row = await env.DB.prepare('SELECT * FROM push_devices WHERE device_id = ?').bind(body.deviceId).first();
    if (!row || !await equal(row.secret_hash, await hash(bearer))) return reply({ error: 'unauthorized' }, 401);
    if (request.method === 'DELETE' && path === '/v1/push/device') {
      await env.DB.prepare('DELETE FROM push_devices WHERE device_id = ? AND secret_hash = ?').bind(body.deviceId, row.secret_hash).run();
      return reply({ status: 'removed' });
    }
    if (request.method === 'POST' && path === '/v1/push/ack') {
      if (row.expires_at <= Date.now()) return reply({ error: 'registration_expired' }, 410);
      const verified = row.verified === 1 || (typeof body.challenge === 'string' && body.challenge === row.challenge);
      if (!verified) return reply({ verified: false });
      const top = await env.DB.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM events').first();
      const cursor = Number.isSafeInteger(body.cursor) && body.cursor >= 0 ? Math.min(body.cursor, top.seq) : 0;
      const acknowledged = await env.DB.prepare('UPDATE push_devices SET verified = 1, last_ack_seq = MAX(last_ack_seq, ?), next_attempt_at = 0, attempts = 0 WHERE device_id = ? AND challenge = ?')
        .bind(cursor, row.device_id, row.challenge).run();
      return reply({ verified: acknowledged.meta?.changes === 1 });
    }
    return reply({ error: 'not_found' }, 404);
  } catch { return reply({ error: 'invalid_push_request' }, 400); }
}
export async function flushPush(env) {
  if (!enabled(env)) return;
  const now = Date.now();
  await env.DB.prepare('DELETE FROM push_devices WHERE expires_at <= ?').bind(now).run();
  const top = await env.DB.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM events').first();
  const page = await env.DB.prepare('SELECT * FROM push_devices WHERE next_attempt_at <= ? AND (verified = 0 OR last_ack_seq < ?) ORDER BY next_attempt_at, device_id LIMIT ?').bind(now, top.seq, MAX_BATCH).all();
  // Four concurrent requests at most; a bounded batch stays within Worker waitUntil lifetime.
  for (let start = 0; start < (page.results || []).length; start += 4) {
    await Promise.all(page.results.slice(start, start + 4).map(async row => {
      const delay = Math.min(3_600_000, 60_000 * 2 ** Math.min(row.attempts, 6));
      const claim = await env.DB.prepare('UPDATE push_devices SET next_attempt_at = ?, attempts = attempts + 1 WHERE device_id = ? AND challenge = ? AND next_attempt_at = ?')
        .bind(now + delay, row.device_id, row.challenge, row.next_attempt_at).run();
      if (!claim.meta?.changes) return;
      try {
        const data = row.verified ? { kind: 'inbox_wake', instance: row.device_id } : { kind: 'push_challenge', instance: row.device_id, challenge: row.challenge };
        const status = await send(env, row, data);
        if (status === 404 || status === 410) await env.DB.prepare('DELETE FROM push_devices WHERE device_id = ? AND challenge = ?').bind(row.device_id, row.challenge).run();
        // A 2xx is provider acceptance only. Retries stop only after the device's catch-up ack.
      } catch { /* No endpoint, auth secret or payload in logs; periodic cron retries with backoff. */ }
    }));
  }
}
