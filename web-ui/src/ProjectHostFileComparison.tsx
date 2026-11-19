import { Accordion, AccordionItem } from './forms-library';

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
export function ProjectHostComparedFiles({ value }: { value: HostComparison }) {
  const counts = value.files.reduce((total, file) => { total[file.state]++; return total; }, { same: 0, changed: 0, missing: 0, limit: 0, unverified: 0 });
  const summary = [counts.changed && `${counts.changed} 份已改`, counts.missing && `${counts.missing} 份缺失`, counts.same && `${counts.same} 份相同`,
    counts.limit + counts.unverified > 0 && `${counts.limit + counts.unverified} 份未核实`].filter(Boolean).join(' · ');
  return <section aria-label="本机已保存文件与导入时的主机版本">
    <p role="status">{summary}</p>
    <ul className="pw-host-file-comparison">{value.files.map(file =>
      <li key={file.path}><code>{file.path}</code><span>{labels[file.state]}{typeof file.phoneBytes === 'number' && file.state === 'changed' ? ` · ${file.originalBytes} → ${file.phoneBytes} 字节` : ''}</span></li>)}</ul>
    <Accordion><AccordionItem id="host-file-evidence" title="范围与文件摘要" description={new Date(value.observedAt).toLocaleTimeString()}>
      <p>只逐份核对当初选入的路径和手机已保存的字节，不包括未保存草稿、新增的其他路径或远端今天的文件。没有回传任何修改。</p>
      {value.files.map(file => <p key={file.path}><code>{file.path}</code><br/>导入时 SHA-256：{file.originalSha256}
        {file.phoneSha256 && <><br/>手机 SHA-256：{file.phoneSha256}</>}</p>)}
    </AccordionItem></Accordion>
  </section>;
}
