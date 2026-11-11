// Optional deployment entry. The standard worker.mjs remains push-free.
import inbox from './worker.mjs';
import { flushPush, pushRoute } from './push.mjs';
export default {
  async fetch(request, env, ctx) {
    const response = await pushRoute(request, env) || await inbox.fetch(request, env, ctx);
    if (response.ok && request.method === 'POST') ctx.waitUntil(flushPush(env));
    return response;
  },
  async scheduled(event, env, ctx) {
    await flushPush(env);
    await inbox.scheduled(event, env, ctx);
    await flushPush(env);
  },
};
