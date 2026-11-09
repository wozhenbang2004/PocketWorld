import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.mjs';

function fakeDB() {
  const events = [];
  return {
    prepare(sql) {
      let args = [];
      return {
        bind(...value) { args = value; return this; },
        async run() {
          if (!sql.startsWith('INSERT OR IGNORE INTO events')) throw new Error('Unexpected query');
          if (events.some(row => row.source_key === args[0])) return { meta: { changes: 0 } };
          const [source_key, kind, title, body, origin, url, priority, created_at] = args;
          events.push({ seq: events.length+1, source_key,kind,title,body,origin,url,priority,created_at });
          return { meta: { changes: 1 } };
        },
        async all() {
          if (!sql.includes('FROM events WHERE seq >')) throw new Error('Unexpected query');
          return { results: events.filter(e => e.seq > args[0]).slice(0,100) };
        }
      };
    }
  };
}
const inbox = 'r'.repeat(50), ingest = 'w'.repeat(50);
const env = { DB: fakeDB(), INBOX_TOKEN: inbox, INGEST_TOKEN: ingest };
function req(method, authorization, body) {
  return new Request('https://example.workers.dev/v1/events', {
    method,headers:{authorization:'Bearer '+authorization,'content-type':'application/json'},
    ...(body === undefined ? {} : { body:JSON.stringify(body) })
  });
}
test('read and ingest use different auth credentials', async () => {
  assert.equal((await worker.fetch(req('GET',ingest),env)).status,401);
  assert.equal((await worker.fetch(req('POST',inbox,{ title:'x' }),env)).status,401);
});
test('events are idempotent and ordered for replay', async () => {
  const entry={key:'issue:mybatis/mybatis-3#10:new',kind:'github_issue',title:'New bug',
    origin:'mybatis/mybatis-3',body:'Repro added',url:'https://github.com/mybatis/mybatis-3/issues/10'};
  assert.equal((await worker.fetch(req('POST',ingest,entry),env)).status,201);
  assert.equal((await worker.fetch(req('POST',ingest,entry),env)).status,200);
  const result=await (await worker.fetch(req('GET',inbox),env)).json();
  assert.equal(result.format,'pocketworld.remote-inbox.v1');
  assert.deepEqual(result.events.map(x=>x.source_key),[entry.key]);
  assert.equal(result.events[0].seq,1);
  const cursor=await (await worker.fetch(new Request('https://example.workers.dev/v1/events?after=1',{
    headers:{authorization:'Bearer '+inbox}}),env)).json();
  assert.deepEqual(cursor.events,[]);
  assert.equal(cursor.hasMore,false);
});
test('service rejects unsafe GitHub links even with valid ingest credentials', async () => {
  const result=await worker.fetch(req('POST',ingest,{
    key:'unsafe',kind:'github_issue',title:'Trick',origin:'test',url:'https://other.example/issue'
  }),env);
  assert.equal(result.status,400);
});
