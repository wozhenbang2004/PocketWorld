import { Accordion, AccordionItem } from './forms-library';
import { harnessCapabilityItems, harnessReason } from './harness-capabilities';

type Data = Record<string, any>;
export function HarnessEvidence({ probe, id = 'harness-evidence' }: { probe: Data; id?: string }) {
  const capabilities = harnessCapabilityItems(probe), agent = probe.agentInfo || {};
  return <div className="pw-harness-evidence" aria-label="本次执行器核对">
    <p role="status">{probe.available === true ? probe.protocolVerified === true ? '执行器协议已核对，尚未发送工作任务。' : '执行器已返回可用声明；实际会话以运行回执为准。' : harnessReason(probe) || '当前未确认可用，请在电脑端核对安装与配置。'}</p>
    {probe.available === true && harnessReason(probe) && <p className="pw-field-hint">{harnessReason(probe)}</p>}
    <Accordion variant="divided" className="pw-compact-disclosures"><AccordionItem id={id} title="这次核对了哪些能力">
      <dl className="pw-facts">
        {(agent.name || agent.version) && <div><dt>执行端</dt><dd>{[agent.name, agent.version].filter(Boolean).join(' · ')}</dd></div>}
        <div><dt>会话访问</dt><dd>{probe.sessionAccessVerified === true ? '已建立测试会话；尚未进行模型推理' : '未核实登录与实际模型调用'}</dd></div>
        {probe.reportedModel && <div><dt>执行端报告模型</dt><dd>{probe.reportedModel}</dd></div>}
        {probe.modeId && <div><dt>执行端报告模式</dt><dd>{probe.modeId}</dd></div>}
        {capabilities.map(item => <div key={item.label}><dt>{item.label}</dt><dd>{item.state}</dd></div>)}
      </dl>
      {!capabilities.length && <p className="pw-field-hint">本次没有返回逐项能力；不据此推定支持恢复、运行中补充或工具调用。</p>}
      {probe.capabilities?.teamToolsOnly === true && probe.capabilities?.codingToolsSupported === false && <p className="pw-field-hint">仅开放团队协作工具，未开放代码、命令与文件工具。</p>}
      {probe.noTools === true && <p className="pw-field-hint">这次探测未挂载工具；团队运行使用哪些工具，以原任务的实际能力回执为准。</p>}
      {probe.executionPolicy === 'owner_managed' && <p className="pw-field-hint">使用电脑端已有的工具与权限配置。工作目录不提供操作系统隔离。</p>}
    </AccordionItem></Accordion>
  </div>;
}
