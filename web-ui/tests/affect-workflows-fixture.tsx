import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import SkillsWorkspace from '../src/SkillsWorkspace';
import { ModelDiagnosticsWorkspace } from '../src/ModelDiagnosticsWorkspace';
import { bridge } from '../src/bridge';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const calls: Array<{ action: string; args: any }> = [];
const skills = [{ id: 'skill-one', name: '文档整理', description: '把真实资料整理成清晰的文档', source: '已登记仓库 A', revision: 4, enabled: false, resources: ['SKILL.md'], requiredCapabilities: ['read_file'] }, { id: 'skill-two', name: '故障排查', description: '按原日志逐步检查错误与环境', source: '已登记仓库 B', revision: 7, enabled: true, resources: ['SKILL.md'], requiredCapabilities: ['read_log'], gitSource: { source: { repository: 'fixture-repository' } } }];
const events = [{ eventId: 'e1', sessionId: 's1', operationId: 'o1', stage: 'model_request', outcome: 'started', code: 'STARTED', time: 10, durationMs: 0 }, { eventId: 'e2', sessionId: 's1', operationId: 'o1', stage: 'model_request', outcome: 'failed', code: 'TIMEOUT', time: 20, durationMs: 312 }, { eventId: 'e3', sessionId: 's1', operationId: 'o2', stage: 'model_prepare', outcome: 'succeeded', code: 'READY', time: 30, durationMs: 5 }];
function Fixture() {
  const [state, setState] = useState<any>({ modelDiagnostics: {}, skillsWorkspace: {} });
  const controls = (window as any).affectFixture ||= { calls, failStart: false };
  controls.changeSelection = () => setState((old: any) => ({ ...old, modelDiagnostics: { ...old.modelDiagnostics, probeSelectionRevision: 8 } }));
  bridge.command = async (action: string, args: any = {}) => {
    calls.push({ action, args });
    if (action === 'modelDiagnostics.open') { const modelDiagnostics = { windowId: 'diagnostic-window', ready: true, canProbe: true, model: 'fixture-model', provider: 'fixture-provider', requestTimeoutSeconds: 60, probeSelectionRevision: 7 }; setState((old: any) => ({ ...old, modelDiagnostics })); return modelDiagnostics; }
    if (action === 'modelDiagnostics.logs') return { events, retainedEvents: events.length, maxEvents: 256 };
    if (action === 'modelDiagnostics.review') return { reviewHandle: 'review-1', model: 'fixture-model', maxRequests: args.includeImage ? 3 : 2, includeImage: args.includeImage, requestTimeoutSeconds: 60 };
    if (action === 'modelDiagnostics.start') {
      if (args.reviewHandle !== 'review-1' || args.acknowledge !== true) throw new Error('fixture requires reviewed explicit approval');
      if (controls.failStart) throw new Error('原连接已变化，请重新核对');
      setState((old: any) => ({ ...old, modelDiagnostics: { ...old.modelDiagnostics, probe: { model: 'fixture-model', at: 100, text: 'passed', tools: 'unconfirmed', image: 'not_requested', diagnostics: [{ stage: 'tools', reason: 'SYNTHETIC_REPLY_UNCONFIRMED', message: '本次未返回单个合成函数；没有执行真实工具。' }] } } }));
      return { started: true, verified: false };
    }
    return {};
  };
  async function run(action: string, args: any = {}) {
    calls.push({ action, args });
    if (action === 'skills.open' || action === 'skills.snapshot') return { skills };
    if (action === 'skills.get') return { skillHandle: `handle:${args.id}`, skill: skills.find(row => row.id === args.id) };
    return {};
  }
  return <div className="pw-app pw-redesign" style={{ height: 'auto', minHeight: '100dvh', overflow: 'visible' }}><main style={{ maxWidth: 900, width: '100%', margin: '0 auto', padding: 16 }}><h2>技能</h2><div className="pw-secondary-scroll"><SkillsWorkspace state={state} run={run} registerTransientBack={() => {}}/></div><h2>模型诊断</h2><ModelDiagnosticsWorkspace state={state} supported={() => true}/></main></div>;
}
createRoot(document.getElementById('root')!).render(<Fixture/>);
