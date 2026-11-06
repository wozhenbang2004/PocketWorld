import { open, readFile, rename, stat, writeFile } from 'node:fs/promises';

export async function downloadFont(entry, source, destination, verify, { idleTimeoutMs = 180000, log = console.log } = {}) {
  const temporary = `${destination}.download`;
  let received = await stat(temporary).then(file => file.size, error => {
    if (error.code === 'ENOENT') return 0;
    throw error;
  });
  if (received === entry.bytes) {
    const bytes = await readFile(temporary);
    try {
      verify(entry, bytes);
    } catch {
      received = 0;
    }
    if (received) {
      await rename(temporary, destination);
      return bytes;
    }
  }
  if (received > entry.bytes) received = 0;

  const controller = new AbortController();
  let timer;
  const resetIdle = () => {
    clearTimeout(timer);
    timer = setTimeout(() => controller.abort(new Error(`Font download idle: ${entry.file} (${received}/${entry.bytes} bytes)`)), idleTimeoutMs);
  };
  resetIdle();
  let handle;
  try {
    const headers = { 'Accept-Encoding': 'identity' };
    if (received) headers.Range = `bytes=${received}-`;
    // Large complete CJK files may take over three minutes on a slow connection.
    // Bound inactivity separately, with a 30 minute ceiling for the whole request.
    const response = await fetch(source, { headers, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(1800000)]) });
    if (response.status === 206) {
      const expected = `bytes ${received}-${entry.bytes - 1}/${entry.bytes}`;
      if (response.headers.get('content-range') !== expected) throw new Error(`Invalid font Content-Range: ${entry.file}`);
    } else if (response.status === 200) {
      // A server may ignore Range; replace only the temporary file from byte zero.
      received = 0;
    } else {
      throw new Error(`HTTP ${response.status}`);
    }
    handle = await open(temporary, received ? 'a' : 'w');
    let lastProgress = Date.now();
    log(`[fonts] receiving ${entry.file}: ${received}/${entry.bytes} bytes`);
    resetIdle();
    for await (const chunk of response.body) {
      resetIdle();
      if (received + chunk.length > entry.bytes) throw new Error(`Font exceeds pinned size: ${entry.file}`);
      await handle.writeFile(chunk);
      received += chunk.length;
      if (Date.now() - lastProgress >= 15000) {
        log(`[fonts] receiving ${entry.file}: ${received}/${entry.bytes} bytes (${Math.floor(received * 100 / entry.bytes)}%)`);
        lastProgress = Date.now();
      }
    }
    await handle.close();
    handle = undefined;
    if (received !== entry.bytes) throw new Error(`Incomplete font: ${entry.file} (${received}/${entry.bytes} bytes); partial download retained`);
    const bytes = await readFile(temporary);
    try {
      verify(entry, bytes);
    } catch (error) {
      // A full but invalid temporary file must never be resumed or promoted.
      await writeFile(temporary, Buffer.alloc(0));
      throw error;
    }
    await rename(temporary, destination);
    return bytes;
  } finally {
    clearTimeout(timer);
    controller.abort();
    await handle?.close();
  }
}
