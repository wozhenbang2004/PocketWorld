// Optional deployment entry. The standard worker.mjs remains push-free.
import inbox from './worker.mjs';
import { flushPush, pushRoute } from './push.mjs';

async function flushAvailable(env) {
  try { await flushPush(env); }
  catch {
    // Missing optional storage or a sender failure must not stop the event log/radar.
    // Do not print exceptions: provider URLs and subscription capabilities are private.
    console.error('Optional inbox push deferred; event collection remains independent');
  }
}
export default {
  async fetch(request, env, ctx) {
    const response = await pushRoute(request, env) || await inbox.fetch(request, env, ctx);
    if (response.ok && request.method === 'POST') ctx.waitUntil(flushAvailable(env));
    return response;
  },
  async scheduled(event, env, ctx) {
    // Start old backlog recovery without placing it in front of event collection.
    // Each sender claims its rows in D1, so overlapping triggers do not own the same retry.
    ctx.waitUntil(flushAvailable(env));
    try { await inbox.scheduled(event, env, ctx); }
    finally { ctx.waitUntil(flushAvailable(env)); }
  },
};
