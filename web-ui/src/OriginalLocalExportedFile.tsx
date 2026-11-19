import { useState } from 'react';
import { Button } from './forms-library';
import type { DeviceData } from './devices-workspace';

const format = 'pocketworld.owner-local-received-file.v1';
export function OriginalLocalExportedFile({ handle, original, disabled, check }: {
  handle: string; original: DeviceData; disabled: boolean; check: (accept: (reply: DeviceData) => void) => void;
}) {
  const [result, setResult] = useState<DeviceData | null>(null);
  function recheck() {
    setResult(null);
    check(reply => {
      const value = reply?.result;
      if (reply?.kind !== 'originalLocalExportedFile' || reply.viewHandle !== handle || value?.format !== format ||
          ['taskId', 'operationId', 'targetDeviceId', 'instanceId', 'argumentsDigest'].some(key => typeof original[key] !== 'string' || original[key] !== value[key]) ||
          value.requestRunId !== original.exportRequestRunId || value.requestExecutorEpoch !== original.exportRequestEpoch ||
          !['no_verified_descriptor', 'no_current_publication', 'not_verified', 'verified_here'].includes(value.state)) throw new Error('不是本窗口的原文件核验。');
      if (value.state === 'verified_here' && (typeof value.name !== 'string' || !value.name || !Number.isSafeInteger(value.bytes) || value.bytes < 0 ||
          !/^[a-f0-9]{64}$/.test(value.sha256) || typeof value.attachmentId !== 'string' || !Number.isSafeInteger(value.checkedAt))) throw new Error('没有取得完整的本机原件证据。');
      setResult(value);
    });
  }
  return <section className="pw-device-result" aria-label="检查这台手机收到的文件">
    <h3>这台手机收到原文件了吗？</h3>
    <p>对端完成导出不等于文件已到手机。只核对原任务登记的这一件附件和本机字节，不联网或重新下载。</p>
    <Button variant="secondary" disabled={disabled} onClick={recheck}>{result ? '再核对本机原件' : '只查这台手机的原附件'}</Button>
    {result?.state === 'verified_here' ? <div role="status"><p>本次已在手机核实：{result.name}（{result.bytes.toLocaleString()} 字节）。</p>
      <details><summary>核对固定版本</summary><p className="pw-source-copy">SHA-256：{result.sha256}</p>
        <p>本机附件 {result.attachmentId}</p><p>源文件 {result.sourceArtifactId} · 原传输 {result.sourceTransferId}</p></details>
    </div> : result && <p role="status">{result.state === 'no_verified_descriptor' ? '原任务没有可核实的固定导出回执，不能据此说已收妥。' :
      result.state === 'no_current_publication' ? '本次未找到这个固定版本的本机附件，不能判断过去是否收过或后来撤下。' :
      '本机原附件的登记、权限或完整字节不能同时核实。没有找同名文件代替。'}</p>}
  </section>;
}
