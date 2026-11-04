/** Production team controls with explicit protocol fixtures. No agent, account or Android I/O. */
import { createRoot } from 'react-dom/client';
import { useState } from 'react';
import { TeamsWorkspace } from '../src/TeamsWorkspace';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const profile = { id: 'fixture-computer', name: '工作电脑', bindingHash: 'a'.repeat(64), hasToken: true };
let revision = '1'.repeat(64), available = false, sequence = 0, team: any = null, review: any = null;
const calls: { action: string; args: any }[] = [];
const worker = () => ({ id: 'acp_fixture_agent', label: '资料执行器', protocol: 'acp', available: true, configurationRevision: revision, executionPolicy: 'owner_managed', capabilities: { supportsResume: false, supportsSteer: false } });
const environment = () => ({ id: 'fixture-workspace', label: '资料项目', workspace: '/work/research', bindingHash: 'b'.repeat(64), writeAllowed: true,
  workers: [worker(), { ...worker(), id: 'muse_msp', label: '协作执行器', protocol: 'msp' }] });
const fixture = {
  available(value: boolean) { available = value; },
  changeConfiguration() { revision = '3'.repeat(64); },
  snapshot() { return structuredClone({ calls, team, revision }); },
};
declare global { interface Window { teamHarnessFixture: typeof fixture; } }
window.teamHarnessFixture = fixture;
function Fixture() {
  const [, redraw] = useState(0);
  const run = async (action: string, args: any = {}) => {
    calls.push({ action, args: structuredClone(args) });
    if (action === 'teams.snapshot') return { teams: team ? [structuredClone(team)] : [], runs: [], phoneTargets: [{ kind: 'local_phone', screenMode: 'background', label: '本机 · 独立后台屏幕', available: true }] };
    if (action === 'remote.environments') return { environments: [environment()] };
    if (action === 'remote.probeWorker' || action === 'teams.probeMember') {
      const value = { available, worker: args.worker || team?.members[0].runner, protocolVerified: available, configurationRevision: revision, capabilityRevision: '2'.repeat(64),
        sessionAccessVerified: available, executionPolicy: 'owner_managed', reportedModel: args.model || 'default-model', modeId: args.modeId || 'default',
        reason: available ? undefined : { code: 'install_missing', message: '未找到配置的执行器，请在工作电脑核对安装位置。' },
        agentInfo: { name: 'Fixture protocol agent', version: '1.0' }, capabilities: { supportsResume: false, supportsSteer: false, supportsCancel: true, peerMessaging: true },
        models: { availableModels: [{ modelId: 'analysis-v2', name: '分析模型' }, { modelId: 'review-v1', name: '复核模型' }] }, modes: { availableModes: [{ id: 'default', name: '常规模式' }] } };
      return action === 'teams.probeMember' ? { result: value } : value;
    }
    if (action === 'teams.reviewDefinition') { review = { operation: 'save', reviewHandle: `review-${++sequence}`, input: structuredClone(args) }; return structuredClone(review); }
    if (action === 'teams.confirm') {
      if (!review || args.reviewHandle !== review.reviewHandle) throw new Error('Stale fixture review');
      team = { ...review.input, id: 'fixture-team' }; delete team.viewHandle; review = null; redraw(value => value + 1);
      return { confirmed: true, command: 'save', result: structuredClone(team) };
    }
    if (action === 'teams.readDefinition') return { kind: 'definition', definition: structuredClone(team), viewHandle: `definition-${++sequence}` };
    if (action === 'teams.closeReading') return { closed: true };
    throw new Error(`Unexpected fixture command: ${action}`);
  };
  return <main className="pw-secondary-root" style={{ maxWidth: 760, margin: '0 auto', padding: '24px 20px' }}>
    <header><h1>团队</h1><p className="pw-muted">界面回归样本 · 协议回执为测试数据，不连接模型</p></header>
    <TeamsWorkspace state={{ features: { remote: { profiles: [profile] } }, modelSelectionBusy: false }} run={run} registerTransientBack={() => {}} />
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
