import { Accordion, AccordionItem } from './forms-library';
import type { DeviceData } from './devices-workspace';
import { windowsJobRead } from './windows-receipt-binding';

const kinds: Record<string, string> = { directory: '目录', file: '文件', reparse_point: '重解析点，未跟随', unsupported: '其他' };
function checked(original: DeviceData, read: DeviceData) {
  const result = windowsJobRead(original, read, 'windowsReadReceipt', 'windows-workspace-read-receipt-v1');
  if (!result) return null;
  const { r, w } = result;
  if (w.tool !== original.directWorkspaceRead || !['workspace.list', 'workspace.read'].includes(w.tool) || w.readAgain !== false) return null;
  if (w.phase === 'receipt_only') return w.part == null && w.entries == null && w.path == null ? w : null;
  if (r.status !== 'succeeded' || r.payload.status !== 'completed' || !Number.isSafeInteger(w.revision) || w.revision < 1
    || typeof w.path !== 'string' || [...w.path].length > 2048 || !/^[a-f0-9]{64}$/.test(original.directWorkspaceReadPathDigest || '')
    || w.pathDigest !== original.directWorkspaceReadPathDigest) return null;
  if (w.phase === 'text_snapshot' && w.tool === 'workspace.read' && w.path && Number.isSafeInteger(w.bytes)
    && w.bytes >= 0 && w.bytes <= 24576 && typeof w.version === 'string' && /^sha256:[a-f0-9]{64}$/.test(w.version)
    && w.version === r.payload.nativeToolResult?.version && w.bytes === r.payload.nativeToolResult?.bytes && typeof w.part === 'string'
    && [...w.part].length <= 1024 && typeof w.partial === 'boolean') return w;
  if (w.phase === 'directory_snapshot' && w.tool === 'workspace.list' && Number.isSafeInteger(w.returned)
    && w.returned >= 0 && w.returned <= 200 && w.returned === r.payload.nativeToolResult?.files?.length
    && typeof w.sourceTruncated === 'boolean' && w.sourceTruncated === r.payload.nativeToolResult?.truncated && Array.isArray(w.entries)
    && w.entries.length === Math.min(8, w.returned) && w.entries.every((e: any) => e && typeof e.name === 'string'
      && [...e.name].length >= 1 && [...e.name].length <= 255 && !/[/\\]/.test(e.name) && typeof e.kind === 'string' && Object.hasOwn(kinds, e.kind))) return w;
  return null;
}

export function WindowsWorkspaceReadReceipt({ original, read }: { original: DeviceData; read: DeviceData | null }) {
  if (!read || read.readOnly !== true || read.managementOnly === true) return null;
  const w = checked(original, read);
  if (!w || w.phase === 'receipt_only') return <p>没有可独立核对的 Windows 原读取结果；不会重新读取目录或文件。</p>;
  const file = w.phase === 'text_snapshot', path = w.path || '工作区根目录';
  return <section aria-label="Windows 留存的读取结果">
    <h3>{file ? '当时读到的文本' : '当时读到的目录'}</h3>
    <p>Windows 本机工作区 {w.environmentId} · {new Date(w.updatedAt).toLocaleString()}</p>
    <p className="pw-source-copy">原相对位置：<bdi>{[...path].length > 120 ? '…' + [...path].slice(-119).join('') : path}</bdi></p>
    <Accordion><AccordionItem id="windows-original-workspace-read" title={file ? `查看原文本片段 · ${w.bytes} 字节` : `查看原目录项 · 已返回 ${w.returned} 项`}>
      {file ? <><pre className="pw-source-copy" dir="auto">{w.part || '当时的文本为空。'}</pre>
        {w.partial && <p>仅显示原文件开头的一部分。</p>}<p className="pw-source-copy">当时的版本：<bdi>{w.version}</bdi></p></>
        : <>{w.entries.length ? <ul>{w.entries.map((e: DeviceData, index: number) => <li key={index}><bdi>{e.name}</bdi> · {kinds[e.kind]}</li>)}</ul> : <p>这份原回执没有目录项。</p>}
          {(w.sourceTruncated || w.entries.length < w.returned) && <p>{w.entries.length < w.returned ? '此处最多显示原回执的前 8 项。' : ''}{w.sourceTruncated ? '执行端当时也只返回了部分目录。' : ''}</p>}</>}
    </AccordionItem></Accordion>
    <p>这是原工作 {w.jobId} 的留存结果，不是当前文件或目录；项目没有复制到本手机。</p>
  </section>;
}
