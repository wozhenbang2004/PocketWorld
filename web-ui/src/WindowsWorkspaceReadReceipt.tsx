import { Accordion, AccordionItem, Button } from './forms-library';
import type { DeviceData } from './devices-workspace';
import { windowsJobRead } from './windows-receipt-binding';

const emptyVersion = 'sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
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
    && [...w.part].length <= 1024 && w.partial === (w.bytes > 1024)
    && (w.bytes === 0 ? w.part === '' && w.version === emptyVersion && r.payload.nativeToolResult.content === '' : w.part.length > 0)) return w;
  if (w.phase === 'directory_snapshot' && w.tool === 'workspace.list' && Number.isSafeInteger(w.returned)
    && w.returned >= 0 && w.returned <= 200 && w.returned === r.payload.nativeToolResult?.files?.length
    && typeof w.sourceTruncated === 'boolean' && w.sourceTruncated === r.payload.nativeToolResult?.truncated && Array.isArray(w.entries)
    && w.entries.length === Math.min(8, w.returned) && w.entries.every((e: any) => e && typeof e.name === 'string'
      && [...e.name].length >= 1 && [...e.name].length <= 255 && !/[/\\]/.test(e.name) && typeof e.kind === 'string' && Object.hasOwn(kinds, e.kind))) return w;
  return null;
}

export function WindowsWorkspaceReadReceipt({ original, read, busy, failed, onRead }: { original: DeviceData; read: DeviceData | null; busy: boolean; failed: boolean; onRead?: () => void }) {
  const file = original.directWorkspaceRead === 'workspace.read';
  const w = read && read.managementOnly !== true ? checked(original, read) : null;
  const show = w && w.phase !== 'receipt_only';
  const path = w?.path || '工作区根目录';
  const noEvidence = failed || (!!read && read.managementOnly !== true);
  return <section aria-label={file ? 'Windows 原文本' : 'Windows 原目录'}>
    {show ? <>
      <h3>{file ? '当时读到的文本' : '当时读到的目录'}</h3>
      <p className="pw-source-copy">原相对位置：<bdi>{[...path].length > 120 ? '…' + [...path].slice(-119).join('') : path}</bdi></p>
      {file ? <>
        {w.bytes === 0 ? <p>已核对：当时的文件是 0 字节。</p> : <>
          {!w.part.trim() && <p>显示的原文本只有空白字符。</p>}
          <pre className="pw-source-copy" dir="auto">{w.part}</pre>
          {w.partial && <p>仅显示原文件开头最多 1 KiB。</p>}
        </>}
        <Accordion><AccordionItem id="windows-original-workspace-version" title="核对原文本版本与大小">
          <p className="pw-source-copy">原文件 {w.bytes} 字节 · 版本 <bdi>{w.version}</bdi></p>
        </AccordionItem></Accordion>
      </> : <Accordion><AccordionItem id="windows-original-workspace-read" title={`查看原目录项 · 当时返回 ${w.returned} 项`}>
        {w.entries.length ? <ul>{w.entries.map((e: DeviceData, index: number) => <li key={index}><bdi>{e.name}</bdi> · {kinds[e.kind]}</li>)}</ul> : <p>这份原回执没有返回目录项。</p>}
        {(w.sourceTruncated || w.entries.length < w.returned) && <p>{w.entries.length < w.returned ? '此处最多显示原回执的前 8 项。' : ''}{w.sourceTruncated ? '执行端当时也只返回了部分目录。' : ''}</p>}
      </AccordionItem></Accordion>}
      <p>原工作 {w.jobId} · 工作区 {w.environmentId} · {new Date(w.updatedAt).toLocaleString()}。这是当时的结果，不是当前{file ? '文件' : '目录'}；项目没有复制到本手机。</p>
    </> : <p role="status">{busy ? '等待读取回执…' : noEvidence
      ? `本次未取得可核对的原${file ? '文本' : '目录'}，不能据此判断${file ? '文件' : '目录'}是否为空。`
      : `尚未在这里核对当时的${file ? '文本' : '目录'}。`}</p>}
    {onRead && <Button variant={show ? 'ghost' : undefined} disabled={busy} onClick={onRead}>{show ? '重新核对原读取结果' : `向原电脑查看当时的${file ? '文本' : '目录'}`}</Button>}
    {!show && <p>{!onRead ? '当前版本不能在这里核对原读取结果。' : '只向原电脑查询当时留存的结果，不会重新读文件、列目录或启动任务。'}</p>}
  </section>;
}
