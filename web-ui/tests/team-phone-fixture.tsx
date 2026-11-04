/** Test-only production component mount. No Android, model, pairing or task I/O. */
import { createRoot } from 'react-dom/client';
import { useRef, useState } from 'react';
import { TeamsWorkspace } from '../src/TeamsWorkspace';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const targets = [{ kind: 'local_phone', screenMode: 'background', label: '本机 · 独立后台屏幕', available: true }, { kind: 'paired_android', targetDeviceId: 'fixture-peer', label: '测试工作手机', available: true }];
let team: any = { id: 'fixture-team', name: '手机资料协作', mode: 'collaborate', leaderId: 'reader', members: [
  { id: 'reader', name: '资料查询', role: '查阅本机应用中的资料', model: '', runner: 'phone_agent', device: { kind: 'local_phone', screenMode: 'background' }, assignment: '打开资料应用，找到今天新增的三篇文章，逐条记录标题与来源。', dependsOn: [] },
  { id: 'checker', name: '来源核对', role: '使用工作手机核对原始来源', model: '', runner: 'phone_agent', device: { kind: 'paired_android', targetDeviceId: 'fixture-peer' }, assignment: '等待查询成员完成，核对文章来源并指出重复内容。', dependsOn: ['reader'] },
] };
let review: any;
const phoneView = (goal: string) => ({ teamId: team.id, definition: structuredClone(team), goal, rounds: 1, members: team.members.filter((member: any) => member.runner === 'phone_agent').map((member: any) => ({ memberId: member.id, name: member.name, assignment: member.assignment, device: member.device, options: [{ optionId: `scope-${member.id}`, label: member.id === 'reader' ? '资料应用 · 后台屏幕' : '浏览器 · 工作手机后台屏幕', resourceBinding: { screenMode: 'background', packageNames: [member.id === 'reader' ? 'example.reader' : 'example.browser'] }, toolBudgetMax: 40, modelBudgetMax: 24, expiresAtMax: Date.now() + 30 * 60_000, available: true }] })) });

function Fixture() {
  const back = useRef<(() => boolean) | null>(null), [last, setLast] = useState('尚未操作');
  const run = async (action: string, args: any = {}) => {
    setLast(action);
    if (action === 'teams.snapshot') return { teams: [structuredClone(team)], runs: [], phoneTargets: targets };
    if (action === 'teams.readDefinition') return { kind: 'definition', definition: structuredClone(team), viewHandle: 'fixture-definition' };
    if (action === 'teams.closeReading') return { closed: true };
    if (action === 'teams.reviewStart') return { operation: 'phone_scope', phoneHandle: 'fixture-scope', view: phoneView(args.goal) };
    if (action === 'teams.reviewPhoneStart') {
      const view = phoneView('整理并核对今天的资料');
      review = { operation: 'start', reviewHandle: 'fixture-start', input: { goal: view.goal }, definition: structuredClone(team), phoneScope: { members: args.members.map((choice: any) => ({ ...view.members.find((member: any) => member.memberId === choice.memberId), ...choice, resourceBinding: view.members.find((member: any) => member.memberId === choice.memberId)?.options[0].resourceBinding })) } };
      return review;
    }
    if (action === 'teams.reviewDefinition') { review = { operation: 'save', reviewHandle: 'fixture-save', input: args }; return review; }
    if (action === 'teams.confirm' && review?.operation === 'save') { team = { ...team, ...review.input }; delete team.viewHandle; return { confirmed: true, command: 'save', result: structuredClone(team) }; }
    if (action === 'teams.confirm' && review?.operation === 'start') {
      const value = { id: 'fixture-run', goal: review.input.goal, teamName: team.name, leaderId: team.leaderId, status: 'running', generation: 1, revision: 1, members: team.members.map((member: any) => ({ ...member, status: member.dependsOn.length ? 'waiting_dependency' : 'running', generation: 1 })) };
      return { confirmed: true, command: 'start', result: value, reading: { kind: 'run', run: value, messages: [], viewHandle: 'fixture-run-handle' } };
    }
    throw new Error(`Fixture command not implemented: ${action}`);
  };
  return <main className="pw-secondary-root" style={{ maxWidth: 760, margin: '0 auto', padding: '24px 20px' }}><header><h1>团队</h1><p className="pw-muted">界面回归样本 · 不连接真实手机</p><button onClick={() => back.current?.()}>返回上一层</button><output style={{ display: 'block', fontSize: 12 }} aria-label="最近界面命令">{last}</output></header><TeamsWorkspace state={{ modelSelectionBusy: false }} run={run} registerTransientBack={handler => { back.current = handler; }} /></main>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
