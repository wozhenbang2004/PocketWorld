import { Accordion, AccordionItem, Button, SettingsDialog } from './forms-library';
import { InlineDiff } from './shell-library';

export type HostComparedFile = { path: string; state: 'same' | 'changed' | 'missing' | 'limit' | 'unverified';
  originalBytes: number; originalSha256: string; phoneBytes?: number; phoneSha256?: string };
export type HostComparison = { files: HostComparedFile[]; observedAt: number; evidenceDigest: string; viewHandle: string };
const labels = { same: '与原件相同', changed: '本机已改', missing: '本机不存在', limit: '超过核对额度', unverified: '这次无法核对' };
const sha = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const size = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 256 * 1024;
export function checkedHostFiles(value: unknown, count: number): HostComparedFile[] {
  if (!Array.isArray(value) || value.length !== count || count < 1 || count > 8) throw new Error('本机核对没有覆盖原先选中的文件。');
  const paths = new Set<string>();
  for (const row of value) {
    if (!row || typeof row.path !== 'string' || !row.path || paths.has(row.path) || !Object.hasOwn(labels, row.state) ||
        !size(row.originalBytes) || !sha(row.originalSha256) ||
        (['same', 'changed'].includes(row.state) ? !size(row.phoneBytes) || !sha(row.phoneSha256) ||
          (row.state === 'same') !== (row.phoneBytes === row.originalBytes && row.phoneSha256 === row.originalSha256) :
          row.phoneSha256 !== undefined || row.phoneBytes !== undefined)) throw new Error('有文件的本机读取和原版本回执不相符。');
    paths.add(row.path);
  }
  return value as HostComparedFile[];
}
export type HostEditorFile = { path: string; originalSha256: string; phoneSha256: string; evidenceDigest: string };
export type HostDiff = HostEditorFile & { snapshotVersion: string; before: string; after: string; evidenceDigest: string; viewHandle: string };
export function ProjectHostDiff({ diff, close, edit }: { diff: HostDiff; close: () => void; edit?: () => void }) {
  return <SettingsDialog isOpen title="这一份文件改了什么" onClose={close} closeLabel="返回核对列表">
    <p><code>{diff.path}</code></p>
    <InlineDiff before={diff.before} after={diff.after} versionLabel={diff.snapshotVersion} beforeLabel="导入时的主机原件" afterLabel="刚核对的手机已保存版本" readOnly/>
    <p className="pw-field-hint">只读对照，显示这次重读的两份内容，不随之后的改动刷新。未改写文件，也未发送到主机。</p>
    <div className="pw-form-actions">{edit && <Button variant="secondary" onClick={edit}>继续编辑这份本机文件</Button>}
      <Button variant="ghost" onClick={close}>返回核对列表</Button></div>
  </SettingsDialog>;
}
export function checkedHostText(value: Record<string, any>, file: HostComparedFile): Pick<HostDiff, 'before' | 'after'> {
  if (value.networkUsed !== false || value.written !== false || value.path !== file.path || value.originalSha256 !== file.originalSha256 ||
      value.phoneSha256 !== file.phoneSha256 || value.originalBytes !== file.originalBytes || value.phoneBytes !== file.phoneBytes ||
      typeof value.before !== 'string' || typeof value.after !== 'string' || value.before.length > 65536 || value.after.length > 65536 ||
      new TextEncoder().encode(value.before).length !== file.originalBytes || new TextEncoder().encode(value.after).length !== file.phoneBytes)
    throw new Error('两侧真实内容或版本不符合原来选中的文件。');
  return { before: value.before, after: value.after };
}
export function ProjectHostComparedFiles({ value, onRead, busy }: { value: HostComparison; onRead?: (file: HostComparedFile) => void; busy: boolean }) {
  const counts = value.files.reduce((total, file) => { total[file.state]++; return total; }, { same: 0, changed: 0, missing: 0, limit: 0, unverified: 0 });
  const summary = [counts.changed && `${counts.changed} 份已改`, counts.missing && `${counts.missing} 份缺失`, counts.same && `${counts.same} 份相同`,
    counts.limit + counts.unverified > 0 && `${counts.limit + counts.unverified} 份未核实`].filter(Boolean).join(' · ');
  return <section aria-label="本机已保存文件与导入时的主机版本">
    <p role="status">{summary}</p>
    <ul className="pw-host-file-comparison">{value.files.map(file =>
      <li key={file.path}><code>{file.path}</code><span>{labels[file.state]}{typeof file.phoneBytes === 'number' && file.state === 'changed' ? ` · ${file.originalBytes} → ${file.phoneBytes} 字节` : ''}</span>
        {file.state === 'changed' && onRead && file.originalBytes <= 65536 && file.phoneBytes! <= 65536 &&
          <Button variant="ghost" size="small" disabled={busy} onClick={() => onRead(file)}>查看这份内容差异</Button>}</li>)}</ul>
    <Accordion><AccordionItem id="host-file-evidence" title="范围与文件摘要" description={new Date(value.observedAt).toLocaleTimeString()}>
      <p>只逐份核对当初选入的路径和手机已保存的字节，不包括未保存草稿、新增的其他路径或远端今天的文件。没有回传任何修改。</p>
      {value.files.map(file => <p key={file.path}><code>{file.path}</code><br/>导入时 SHA-256：{file.originalSha256}
        {file.phoneSha256 && <><br/>手机 SHA-256：{file.phoneSha256}</>}</p>)}
    </AccordionItem></Accordion>
  </section>;
}
