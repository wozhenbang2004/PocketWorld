import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { packagePreviewChoices, type PackagePreviewChoices, type PackagePreviewTarget } from './project-preview';

type Options = {
  route: string; navigationRevision: number; projectId: string; blocked: boolean;
  navigation: () => number;
  supported: (command: string) => boolean;
  leaveTask: (stillCurrent: () => boolean) => Promise<boolean>;
  show: (choices:PackagePreviewChoices, originalChat?:()=>boolean) => boolean;
  error: (message: string) => void;
};
type Handoff = {
  target: PackagePreviewTarget; epoch: number; resume: number; navigation: number; projectId: string;
  phase: 'leaving' | 'waiting' | 'inspecting' | 'selecting' | 'presenting';
  choices?: PackagePreviewChoices; timer?: number; shown: () => void; originalChat?:()=>boolean;
};
const nativeView = () => bridge.getState().view as { taskId?: string | null; section?: string | null; resumeRevision?: number } | undefined;

/** Receipt and presentation are separate. Close the real task, wait for both
 * the committed destination and native window, then inspect the original SQL
 * operation once. Choosing the owning project never initiates package execution. */
export function useProjectPackageHandoff(options: Options) {
  const latest = useRef(options); latest.current = options;
  const active = useRef<Handoff | null>(null);
  const [opening, setOpening] = useState(false), [, wake] = useState(0);
  const sameDocument = (r: Handoff) => active.current === r && !document.hidden &&
    bridge.getDocumentEpoch() === r.epoch && Number(nativeView()?.resumeRevision || 0) === r.resume && r.originalChat?.() !== false;
  const finish = (r: Handoff, message?: string) => {
    if (active.current !== r) return;
    if (r.timer !== undefined) window.clearTimeout(r.timer);
    active.current = null; setOpening(false);
    if (message) latest.current.error(message);
  };
  const timely = (r: Handoff, message: string) => {
    if (r.timer !== undefined) window.clearTimeout(r.timer);
    r.timer = window.setTimeout(() => finish(r, sameDocument(r) && latest.current.navigation() === r.navigation + 1 ? message : undefined), 5000);
  };
  const onDestination = (r: Handoff) => {
    const o = latest.current, state = bridge.getState().state;
    const matchesProject = (id: string) => id === r.projectId || id === r.choices?.projectId;
    return sameDocument(r) && o.navigation() === r.navigation + 1 && o.navigationRevision === r.navigation + 1 &&
      o.route === 'projects' && !o.blocked &&
      matchesProject(o.projectId) && matchesProject(String(state.projectId || '')) &&
      (!nativeView()?.taskId && nativeView()?.section == null);
  };
  const open = (target: PackagePreviewTarget, shown: () => void, originalChat?:()=>boolean) => {
    const o = latest.current, view = nativeView();
    if (active.current || document.hidden || !view || view.section != null ||
        (originalChat ? o.route!=='chat' || !!view.taskId || !originalChat() : view.taskId !== target.taskId) ||
        !['tasks.close', 'projects.package.preview.inspect', 'projects.package.preview.open'].every(o.supported)) return false;
    const r: Handoff = { target, epoch: bridge.getDocumentEpoch(), resume: Number(view.resumeRevision || 0),
      navigation: o.navigation(), projectId: o.projectId, phase: 'leaving', shown, originalChat };
    active.current = r; setOpening(true);
    void o.leaveTask(() => sameDocument(r) && latest.current.navigation() === r.navigation && (!nativeView()?.taskId || nativeView()?.taskId === r.target.taskId)).then(closed => {
      if (!sameDocument(r)) { finish(r); return; }
      if (!closed || latest.current.navigation() !== r.navigation + 1) { finish(r); return; }
      r.phase = 'waiting'; timely(r, '尚未确认原工作窗口已关闭，未读取另一份成果。'); wake(n => n + 1);
    }).catch(error => finish(r, sameDocument(r) ? String(error instanceof Error ? error.message : error) : undefined));
    return true;
  };

  // Snapshot publication also wakes this hook. A receipt is not a guarantee
  // that React has committed the associated route or the project list yet.
  useEffect(() => {
    const refresh = () => { const r = active.current; if (r) { if (!sameDocument(r)) finish(r); else wake(n => n + 1); } };
    const unsub = bridge.subscribe(refresh); document.addEventListener('visibilitychange', refresh);
    return () => { unsub(); document.removeEventListener('visibilitychange', refresh); if (active.current?.timer !== undefined) window.clearTimeout(active.current.timer); active.current = null; };
  }, []);
  useEffect(() => {
    const r = active.current, o = latest.current;
    if (!r || r.phase === 'leaving') return;
    if (!sameDocument(r) || o.navigation() !== r.navigation + 1) { finish(r); return; }
    // The local destination has not committed yet. It is not an interruption.
    if (o.navigationRevision < r.navigation + 1) return;
    if (o.route !== 'projects' || o.blocked || (o.projectId !== r.projectId && o.projectId !== r.choices?.projectId)) { finish(r); return; }
    if (nativeView()?.taskId === r.target.taskId && r.phase === 'waiting') return;
    if (!onDestination(r)) { finish(r); return; }
    if (r.phase === 'waiting') {
      r.phase = 'inspecting'; window.clearTimeout(r.timer);
      void (async () => {
        const result = await bridge.command('projects.package.preview.inspect', { taskId: r.target.taskId, operationId: r.target.operationId });
        if (!onDestination(r)) { finish(r); return; }
        const choices = packagePreviewChoices(result, r.target);
        if (!choices || r.originalChat&&choices.mode!=='web_output') throw new Error('固定包与所选原工作操作不符，没有打开其他版本。');
        r.choices = choices;
        const state = bridge.getState().state;
        if (state.projectId !== choices.projectId || state.filePath) {
          if (!o.supported('projects.select')) throw new Error('无法打开这份成果所属的项目。');
          r.phase = 'selecting'; await bridge.command('projects.select', { id: choices.projectId });
          if (!onDestination(r)) { finish(r); return; }
        }
        r.phase = 'presenting'; timely(r, '未读到成果所属的项目，没有打开其他版本。'); wake(n => n + 1);
      })().catch(error => finish(r, onDestination(r) ? String(error instanceof Error ? error.message : error) : undefined));
    } else if (r.phase === 'presenting' && r.choices) {
      const state = bridge.getState().state, id = r.choices.projectId;
      if (o.projectId !== id || state.projectId !== id || state.filePath || !Array.isArray(state.projects) || !state.projects.some(p => p?.id === id)) return;
      if (o.show(r.choices,r.originalChat)) { r.shown(); finish(r); }
    }
  });
  return { opening, open };
}
