import { Accordion, AccordionItem } from './forms-library';
import type { DeviceData } from './devices-workspace';
import { windowsJobRead } from './windows-receipt-binding';

const labels: Record<string, string> = {
  queued: '这台 Windows 已接单并排队', running: '这台 Windows 已启动命令',
  not_started: '原执行器确认没有启动进程', exited_zero: '原命令以 0 退出',
  exited_nonzero: '原命令以非零码退出', stopped: '原命令已停止，之前的影响未撤回',
  accepted_only: '已收到接单编号，原工作文件未能核对', unknown: '原命令的执行结果仍待核对',
};

// Only the existing native, authenticated operation.get issues `read`. This
// card is not a second accept/dispatch or a browser-created TaskStore run.
function checked(original: DeviceData, read: DeviceData) {
  const result = windowsJobRead(original, read, 'windowsCommandReceipt', 'windows-local-command-receipt-v1');
  if (!result) return null;
  const { r, w } = result;
  if (w.businessVerified !== false || typeof w.phase !== 'string' || !Object.hasOwn(labels, w.phase)) return null;
  if (w.phase === 'accepted_only') return r.status === 'unknown' && w.output === undefined && w.exitCode === undefined ? w : null;
  if (!Number.isSafeInteger(w.revision) || w.revision <= 0 || typeof w.cleanupVerified !== 'boolean') return null;
  if (w.phase === 'exited_zero' && (r.status !== 'succeeded' || r.payload.exitCode !== 0 || w.exitCode !== 0 || !w.cleanupVerified)) return null;
  if (w.phase === 'exited_nonzero' && (r.status !== 'failed' || r.payload.exitCode !== w.exitCode || !Number.isSafeInteger(w.exitCode) || w.exitCode === 0 || !w.cleanupVerified)) return null;
  if ((w.phase === 'stopped' && (r.status !== 'cancelled' || !w.cleanupVerified)) || (w.phase === 'not_started' && !w.cleanupVerified)) return null;
  if ((w.phase === 'running' && r.status !== 'running') || (w.phase === 'queued' && r.status !== 'accepted')) return null;
  if (w.output !== undefined && (!['exited_zero', 'exited_nonzero', 'stopped'].includes(w.phase)
    || typeof w.output !== 'string' || [...w.output].length > 1024 || typeof w.outputPartial !== 'boolean')) return null;
  return w;
}

export function WindowsCommandReceipt({ original, read }: { original: DeviceData; read: DeviceData | null }) {
  if (original.directWorkspaceCommand !== true) return null;
  const alias = original.originalRequestedCommandName;
  const mine = typeof alias === 'string' && alias.length <= 48 && /^[A-Za-z]/.test(alias) && !/[^A-Za-z0-9_-]/.test(alias)
    ? <Accordion><AccordionItem id="original-request-command-name" title="看本手机当时指定的命令名">
        <p><bdi>{alias}</bdi> · 来自这笔私人原派单，不是 Windows 的接单或执行证明。不显示参数或程序路径。</p>
      </AccordionItem></Accordion> : null;
  if (read && (read.readOnly !== true || read.managementOnly === true)) return null;
  if (!read) return mine;
  const w = checked(original, read);
  if (!w) return <>{mine}<p>对端没有返回可核对的 Windows 本机命令记录。原操作状态不等于进程结果。</p></>;
  return <section aria-label="Windows 原命令结果">
    <h3>{labels[w.phase]}</h3>
    {mine}
    <p>本手机交办 · Windows 本机工作区 {w.environmentId} · {new Date(w.updatedAt).toLocaleString()}</p>
    <p>Windows 工作 <bdi>{w.jobId}</bdi>{w.exitCode != null ? <> · 退出码 {w.exitCode}</> : null}{w.cleanupVerified ? ' · 原进程组清理已核实' : ''}</p>
    <p>这是这条命令的回执，不证明仓库已修改、文件已交付或整个目标已完成。</p>
    {w.output !== undefined && <Accordion><AccordionItem id="original-windows-output" title="查看这条命令的留存输出">
      <pre className="pw-source-copy" dir="auto">{w.output || '没有留存可显示的字符。'}</pre>
      {w.outputPartial && <p>仅显示留存输出的一部分。</p>}
    </AccordionItem></Accordion>}
  </section>;
}
