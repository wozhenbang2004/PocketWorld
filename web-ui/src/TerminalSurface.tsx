import { useEffect, useRef, useState } from 'react';

type Frame = { revision: number; generation: number; cols: number; rows: number; packets: string[]; gap?: boolean };
const loaded = new Map<string, Promise<void>>();
function script(name: string) {
  if (!loaded.has(name)) loaded.set(name, new Promise<void>((resolve, reject) => {
    const element = document.createElement('script'); element.src = new URL('ssh-terminal/' + name, document.baseURI).href;
    element.onload = () => resolve(); element.onerror = () => { loaded.delete(name); reject(new Error('终端显示组件未加载，原连接仍保留。')); }; document.head.append(element);
  }));
  return loaded.get(name)!;
}

/** React adaptation of the repository's ssh-terminal/terminal.js. The same xterm
 * source and OSC policy are bundled unchanged. Output cannot send PTY input. */
export default function TerminalSurface({ frame, dimensionMode = 'ssh', onFrameApplied }: { frame: Frame | null; dimensionMode?: 'ssh' | 'project'; onFrameApplied?: (revision: number, generation: number) => void }) {
  const element = useRef<HTMLDivElement>(null), pending = useRef<Frame | null>(frame), terminal = useRef<any>(null);
  const rendering = useRef(false), consumed = useRef(0), revision = useRef(-1), generation = useRef(-1), alive = useRef(false);
  const [error, setError] = useState('');
  const applied = useRef(onFrameApplied); applied.current = onFrameApplied;
  const drain = async () => {
    if (rendering.current || !terminal.current) return;
    rendering.current = true;
    try {
      while (alive.current && pending.current && terminal.current) {
        const value = pending.current; pending.current = null;
        if (!Number.isSafeInteger(value.revision) || value.revision < revision.current || !Number.isSafeInteger(value.generation) || value.generation < generation.current ||
          !Number.isInteger(value.cols) || value.cols < (dimensionMode === 'project' ? 4 : 20) || value.cols > (dimensionMode === 'project' ? 400 : 300) || !Number.isInteger(value.rows) || value.rows < (dimensionMode === 'project' ? 2 : 5) || value.rows > (dimensionMode === 'project' ? 200 : 120) ||
          !Array.isArray(value.packets) || value.packets.length > 128 || value.packets.some(packet => typeof packet !== 'string' || packet.length > 87384 || !/^[A-Za-z0-9+/]*={0,2}$/.test(packet))) throw new Error('原终端输出范围无法核对，请重新打开原记录。');
        terminal.current.resize(value.cols, value.rows);
        if (value.generation !== generation.current) { terminal.current.reset(); generation.current = value.generation; consumed.current = 0; }
        for (let index = consumed.current; index < value.packets.length && alive.current; index++) {
          const binary = atob(value.packets[index]), bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
          await new Promise<void>(resolve => terminal.current?.write(bytes, resolve)); consumed.current = index + 1;
        }
        revision.current = value.revision;
        if (alive.current) applied.current?.(value.revision, value.generation);
      }
    } catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : '原终端显示未完成'); }
    finally { rendering.current = false; }
  };
  useEffect(() => {
    alive.current = true;
    if (!document.getElementById('pocket-terminal-styles')) { const css = document.createElement('link'); css.id = 'pocket-terminal-styles'; css.rel = 'stylesheet'; css.href = new URL('ssh-terminal/xterm.css', document.baseURI).href; document.head.append(css); }
    void script('xterm.js').then(() => script('terminal-policy.js')).then(() => {
      if (!alive.current || !element.current) return;
      const host = window as any; terminal.current = host.PocketTerminalPolicy.create(host.Terminal); terminal.current.open(element.current); void drain();
    }).catch(failure => { if (alive.current) setError(failure.message); });
    return () => { alive.current = false; pending.current = null; terminal.current?.dispose(); terminal.current = null; };
  }, []);
  useEffect(() => { pending.current = frame; void drain(); }, [frame]);
  return <><div ref={element} role="region" aria-label="原任务终端输出" style={{ overflow: 'auto', minHeight: 260, maxHeight: '60vh' }} />
    {frame?.gap && <p className="pw-field-hint">较早输出已被覆盖，当前屏幕由保留的实际字节重建。</p>}{error && <p role="alert" className="pw-secondary-error">{error}</p>}</>;
}
