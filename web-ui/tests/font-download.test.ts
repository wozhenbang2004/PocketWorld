import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { downloadFont } from '../scripts/font-download.mjs';

const payload = Buffer.from('complete pinned font fixture');
const entry = { file: 'fixture.ttf', bytes: payload.length, sha256: createHash('sha256').update(payload).digest('hex') };
function verify(pin: typeof entry, bytes: Buffer) {
  assert.equal(bytes.length, pin.bytes);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), pin.sha256);
}

async function fixture(t: any, handler: Parameters<typeof createServer>[0]) {
  const directory = await mkdtemp(path.join(process.env.TEMP || process.env.TMPDIR || process.cwd(), 'font-download-'));
  const server = createServer(handler);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  });
  const address = server.address() as { port: number };
  return { destination: path.join(directory, entry.file), url: `http://127.0.0.1:${address.port}/font` };
}

test('a slow font keeps downloading while body chunks arrive within the idle timeout', async t => {
  const f = await fixture(t, (_request, response) => {
    response.writeHead(200, { 'Content-Length': payload.length });
    response.write(payload.subarray(0, 5));
    const second = setTimeout(() => response.write(payload.subarray(5, 10)), 70);
    const third = setTimeout(() => response.end(payload.subarray(10)), 140);
    response.on('close', () => { clearTimeout(second); clearTimeout(third); });
  });
  await downloadFont(entry, f.url, f.destination, verify, { idleTimeoutMs: 110 });
  assert.deepEqual(await readFile(f.destination), payload);
});

test('a retained partial font requests its exact remaining range before verification', async t => {
  const ranges: string[] = [];
  const f = await fixture(t, (request, response) => {
    ranges.push(request.headers.range || '');
    response.writeHead(206, { 'Content-Range': `bytes 5-${payload.length - 1}/${payload.length}` });
    response.end(payload.subarray(5));
  });
  await writeFile(`${f.destination}.download`, payload.subarray(0, 5));
  await downloadFont(entry, f.url, f.destination, verify);
  assert.deepEqual(ranges, ['bytes=5-']);
  assert.deepEqual(await readFile(f.destination), payload);
});

test('a server ignoring Range replaces the temporary prefix instead of appending', async t => {
  const f = await fixture(t, (request, response) => {
    assert.equal(request.headers.range, 'bytes=5-');
    response.end(payload);
  });
  await writeFile(`${f.destination}.download`, payload.subarray(0, 5));
  await downloadFont(entry, f.url, f.destination, verify);
  assert.deepEqual(await readFile(f.destination), payload);
});

test('an incorrect Content-Range cannot alter an existing font or partial file', async t => {
  const original = Buffer.from('previous font');
  const prefix = payload.subarray(0, 5);
  const f = await fixture(t, (_request, response) => {
    response.writeHead(206, { 'Content-Range': `bytes 6-${payload.length - 1}/${payload.length}` });
    response.end(payload.subarray(6));
  });
  await writeFile(f.destination, original);
  await writeFile(`${f.destination}.download`, prefix);
  await assert.rejects(downloadFont(entry, f.url, f.destination, verify), /Invalid font Content-Range/);
  assert.deepEqual(await readFile(f.destination), original);
  assert.deepEqual(await readFile(`${f.destination}.download`), prefix);
});

test('a stalled body retains its prefix for the next run and never replaces the current font', async t => {
  const original = Buffer.from('previous font');
  let requests = 0;
  const f = await fixture(t, (request, response) => {
    if (++requests === 1) {
      response.writeHead(200, { 'Content-Length': payload.length });
      response.write(payload.subarray(0, 5));
    } else {
      assert.equal(request.headers.range, 'bytes=5-');
      response.writeHead(206, { 'Content-Range': `bytes 5-${payload.length - 1}/${payload.length}` });
      response.end(payload.subarray(5));
    }
  });
  await writeFile(f.destination, original);
  await assert.rejects(downloadFont(entry, f.url, f.destination, verify, { idleTimeoutMs: 150 }), /Font download idle/);
  assert.deepEqual(await readFile(f.destination), original);
  assert.deepEqual(await readFile(`${f.destination}.download`), payload.subarray(0, 5));
  await downloadFont(entry, f.url, f.destination, verify);
  assert.deepEqual(await readFile(f.destination), payload);
});

test('truncation, oversize and same-size hash failures never promote an unverified font', async t => {
  const original = Buffer.from('previous font');
  const bodies = [payload.subarray(0, 5), Buffer.concat([payload, Buffer.from('!')]), Buffer.alloc(payload.length)];
  for (const body of bodies) {
    const f = await fixture(t, (_request, response) => response.end(body));
    await writeFile(f.destination, original);
    await assert.rejects(downloadFont(entry, f.url, f.destination, verify));
    assert.deepEqual(await readFile(f.destination), original);
    assert.ok((await readFile(`${f.destination}.download`)).length <= entry.bytes);
  }
});

test('a complete retained temporary font is verified and promoted without another request', async t => {
  let requests = 0;
  const f = await fixture(t, (_request, response) => { requests++; response.end(payload); });
  await writeFile(`${f.destination}.download`, payload);
  await downloadFont(entry, f.url, f.destination, verify);
  assert.equal(requests, 0);
  assert.deepEqual(await readFile(f.destination), payload);
});
