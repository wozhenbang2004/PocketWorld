import { Accordion, AccordionItem } from './forms-library';
import type { DeviceData } from './devices-workspace';

const labels: Record<string, string> = {
  receiving: 'Windows 正在等候或接收原文件',
  not_dispatched: 'Windows 确认未派发目标替换',
  target_unconfirmed: 'Windows 的原文件写入结果未知',
  write_verified_only: '原落盘已核实，传输收尾未确认',
  delivered: 'Windows 已核实当时写入了这份文件',
};

export function WindowsFileDeliveryReceipt({ original, read }: { original: DeviceData; read: DeviceData | null }) {
  if (!read || read.readOnly !== true || read.newRun !== false || read.retrySubmitted !== false || read.managementOnly === true) return null;
  // Native code authenticated this peer and joined its CAS receipt to our stored original wire.
  // Do not render raw transmitted paths, backups, caches or transfer descriptors from the peer.
  const w = read.originalWindowsFileDelivery, r = read.operationReceipt;
  if (!w || !r || r.accepted !== true || w.operationId !== original.operationId || r.operationId !== w.operationId
    || !r.jobId || r.jobId !== w.jobId || r.executorDeviceId !== original.targetDeviceId || r.instanceId !== original.instanceId
    || w.phase === 'receipt_only' || !Object.hasOwn(labels, w.phase))
    return <p>尚无可核对的 Windows 目标落盘证明。接单不等于文件已写入，不会补传。</p>;
  const receiving = w.phase === 'receiving';
  return <section aria-label="Windows 原文件落盘回执">
    <h3>{labels[w.phase]}</h3>
    <p><bdi>{w.path}</bdi> · {w.bytes} 字节</p>
    <p>{receiving ? `原传输已核实 ${w.verifiedOffset}/${w.bytes} 字节，这不是已写入目标。`
      : w.phase === 'not_dispatched' ? (w.targetConditionFailed ? '目标不再符合原先指定的版本或“不存在”条件。没有覆盖，没有补传。' : '原文件事务在替换前停止；先前可能收到过字节，没有补传。')
      : w.phase === 'target_unconfirmed' ? '原写入意图存在，但没有最终字节证明。不能据此断言写入失败，也不会补发。'
      : `原生回读核实了当时${w.newFile ? '新建' : '按原版本替换'}的文件。这不是现在的文件检查，也不代表整个任务完成。`}</p>
    <Accordion><AccordionItem id="original-windows-file" title="核对原文件摘要和工作编号">
      <p>工作区 <bdi>{w.environmentId}</bdi> · Windows 工作 <bdi>{w.jobId}</bdi></p>
      <p>原选文件 SHA-256：<bdi>{w.sha256}</bdi></p>
    </AccordionItem></Accordion>
  </section>;
}
