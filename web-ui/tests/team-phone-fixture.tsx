/** Test-only production component mount. No Android, model, pairing or task I/O. */
import { createRoot } from 'react-dom/client';
import { useEffect, useRef, useState } from 'react';
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
let review: any, phoneSelection: any, sequence = 0, confirmedStarts = 0;
const calls: { action: string; args: any }[] = [], closedHandles: string[] = [], consumedHandles: string[] = [];
const handle = (kind: string) => `fixture-${kind}-${++sequence}`;
const phoneView = (goal: string) => ({ teamId: team.id, definition: structuredClone(team), goal, rounds: 1, members: team.members.filter((member: any) => member.runner === 'phone_agent').map((member: any) => ({ memberId: member.id, name: member.name, assignment: member.assignment, device: member.device, options: [{ optionId: `scope-${member.id}`, label: member.id === 'reader' ? '资料应用 · 后台屏幕' : '浏览器 · 工作手机后台屏幕', resourceBinding: { screenMode: 'background', androidUserId: 0, packageNames: [member.id === 'reader' ? 'example.reader' : 'example.browser'] }, toolBudgetMax: 40, modelBudgetMax: 24, expiresAtMax: Date.now() + 30 * 60_000, available: true }] })) });
const fixture = {
  back: (): boolean => false,
  snapshot: () => structuredClone({ calls, closedHandles, consumedHandles, confirmedStarts,
    activePhoneHandle: phoneSelection?.phoneHandle || null, activeReviewHandle: review?.reviewHandle || null }),
};
declare global { interface Window { teamPhoneFixture: typeof fixture; } }
window.teamPhoneFixture = fixture;

function Fixture() {
  const back = useRef<(() => boolean) | null>(null), [last, setLast] = useState('尚未操作');
  useEffect(() => { fixture.back = () => back.current?.() ?? false; return () => { fixture.back = () => false; }; }, []);
  const run = async (action: string, args: any = {}) => {
    calls.push({ action, args: structuredClone(args) });
    setLast(action);
    if (action === 'teams.snapshot') return { teams: [structuredClone(team)], runs: [], phoneTargets: targets };
    if (action === 'teams.readDefinition') return { kind: 'definition', definition: structuredClone(team), viewHandle: handle('definition') };
    if (action === 'teams.closeReading') {
      const original = args.phoneHandle || args.reviewHandle || args.viewHandle;
      if (!original) throw new Error('Fixture close requires an exact handle');
      closedHandles.push(original);
      if (phoneSelection?.phoneHandle === original) phoneSelection = null;
      if (review?.reviewHandle === original) review = null;
      return { closed: true };
    }
    if (action === 'teams.reviewStart') {
      phoneSelection = { operation: 'phone_scope', phoneHandle: handle('scope'), view: phoneView(args.goal) };
      return structuredClone(phoneSelection);
    }
    if (action === 'teams.reviewPhoneStart') {
      if (!phoneSelection || args.phoneHandle !== phoneSelection.phoneHandle) throw new Error('Fixture phone selection is no longer current');
      const view = phoneSelection.view;
      consumedHandles.push(phoneSelection.phoneHandle); phoneSelection = null;
      review = { operation: 'start', reviewHandle: handle('start'), input: { goal: view.goal }, definition: view.definition,
        phoneScope: { members: args.members.map((choice: any) => {
          const member = view.members.find((value: any) => value.memberId === choice.memberId);
          const option = member?.options.find((value: any) => value.optionId === choice.optionId);
          if (!option) throw new Error('Fixture selection does not match the original member scope');
          return { ...member, ...choice, resourceBinding: option.resourceBinding };
        }) } };
      return structuredClone(review);
    }
    if (action === 'teams.reviewDefinition') { review = { operation: 'save', reviewHandle: handle('save'), input: structuredClone(args) }; return structuredClone(review); }
    if (action === 'teams.confirm') {
      if (!review || args.reviewHandle !== review.reviewHandle) throw new Error('Fixture review is no longer current');
      const original = review; consumedHandles.push(original.reviewHandle); review = null;
      if (original.operation === 'save') { team = { ...team, ...original.input }; delete team.viewHandle; return { confirmed: true, command: 'save', result: structuredClone(team) }; }
      if (original.operation === 'start') {
        confirmedStarts++;
        const value = { id: 'fixture-run', goal: original.input.goal, teamName: original.definition.name, leaderId: original.definition.leaderId, status: 'running', generation: 1, revision: 1, members: original.definition.members.map((member: any) => ({ ...member, status: member.dependsOn.length ? 'waiting_dependency' : 'running', generation: 1 })) };
        return { confirmed: true, command: 'start', result: value, reading: { kind: 'run', run: value, messages: [], viewHandle: handle('run') } };
      }
    }
    throw new Error(`Fixture command not implemented: ${action}`);
  };
  return <main className="pw-secondary-root" style={{ maxWidth: 760, margin: '0 auto', padding: '24px 20px' }}><header><h1>团队</h1><p className="pw-muted">界面回归样本 · 假宿主，不连接真实手机或模型</p><button onClick={() => fixture.back()}>返回上一层</button><output style={{ display: 'block', fontSize: 12 }} aria-label="最近界面命令">{last}</output></header><TeamsWorkspace state={{ modelSelectionBusy: false }} run={run} registerTransientBack={handler => { back.current = handler; }} /></main>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
