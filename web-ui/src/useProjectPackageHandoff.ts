import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { packagePreviewChoices, type PackagePreviewChoices, type PackagePreviewTarget } from './project-preview';

type Options = {
  route: string; navigationRevision: number; projectId: string; blocked: boolean;
  navigation: () => number;
  supported: (command: string) => boolean;
  leaveTask: (stillCurrent: () => boolean) => Promise<boolean>;
  show: (choices:PackagePreviewChoices, originalChat?:()=>boolean, explicitWebPick?:()=>boolean) => boolean;
  error: (message: string) => void;
};
type Origin = { originalChat?:()=>boolean; currentWork?:()=>boolean; departed?:()=>void; sourceError?:(message:string)=>void };
type Handoff = Origin & {
  target: PackagePreviewTarget; epoch: number; resume: number; navigation: number; projectId: string;
  sourceRoute: string; phase: 'inspecting' | 'leaving' | 'waiting' | 'selecting' | 'presenting';
  choices?: PackagePreviewChoices; timer?: number; shown: (choices:PackagePreviewChoices) => void;
};
const nativeView = () => bridge.getState().view as { taskId?: string | null; section?: string | null; resumeRevision?: number } | undefined;

/** Inspect the original operation from its current private source. The result
 * is metadata only; the existing renderer still validates the original bytes
 * and selected project after the confirmed navigation. */
export function useProjectPackageHandoff(options: Options) {
  const latest = useRef(options); latest.current = options;
  const active = useRef<Handoff | null>(null);
  const [opening, setOpening] = useState(false), [, wake] = useState(0);
  const sameDocument = (r: Handoff) => active.current === r && !document.hidden &&
    bridge.getDocumentEpoch() === r.epoch && Number(nativeView()?.resumeRevision || 0) === r.resume && r.originalChat?.() !== false;
  const onSource = (r: Handoff) => sameDocument(r) && latest.current.navigation() === r.navigation &&
    latest.current.route === r.sourceRoute && nativeView()?.section == null &&
    (r.originalChat ? !nativeView()?.taskId : nativeView()?.taskId === r.target.taskId && r.currentWork?.() === true);
  const finish = (r: Handoff, message?: string) => {
    if (active.current !== r) return;
    if (r.timer !== undefined) window.clearTimeout(r.timer);
    active.current = null; setOpening(false);
    if (message) (r.phase==='inspecting'&&r.sourceError||latest.current.error)(message);
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
  const open = (target: PackagePreviewTarget, shown: (choices:PackagePreviewChoices) => void, origin:Origin) => {
    const o = latest.current, view = nativeView();
    if (active.current || document.hidden || !view || view.section != null ||
        (origin.originalChat ? o.route!=='chat' || !!view.taskId || !origin.originalChat() : view.taskId !== target.taskId || origin.currentWork?.() !== true) ||
        !['tasks.close', 'projects.package.preview.inspect', 'projects.package.preview.open'].every(o.supported)) return false;
    const r: Handoff = { ...origin,target, epoch: bridge.getDocumentEpoch(), resume: Number(view.resumeRevision || 0),
      navigation: o.navigation(), projectId: o.projectId, sourceRoute:o.route, phase: 'inspecting', shown };
    active.current = r; setOpening(true);
    void (async()=>{
      const result=await bridge.command('projects.package.preview.inspect',{taskId:target.taskId,operationId:target.operationId});
      if(!onSource(r)){finish(r);return;}
      const choices=packagePreviewChoices(result,target);
      if(!choices||r.originalChat&&choices.mode!=='web_output')throw new Error('固定包与所选原工作操作不符，没有打开其他版本。');
      const state=bridge.getState().state;
      if((state.projectId!==choices.projectId||state.filePath)&&!latest.current.supported('projects.select'))
        throw new Error('无法打开这份成果所属的项目。');
      r.choices=choices;
      r.phase='leaving';
      const closed=await latest.current.leaveTask(() => sameDocument(r) && latest.current.navigation() === r.navigation &&
        (!nativeView()?.taskId || nativeView()?.taskId === r.target.taskId));
      if (!sameDocument(r)) { finish(r); return; }
      if (!closed || latest.current.navigation() !== r.navigation + 1) { finish(r); return; }
      r.departed?.();
      r.phase = 'waiting'; timely(r, '尚未确认原工作窗口已关闭，未读取另一份成果。'); wake(n => n + 1);
    })().catch(error => finish(r, (r.phase==='inspecting'?onSource(r):sameDocument(r)) ? String(error instanceof Error ? error.message : error) : undefined));
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
    if(r.phase==='inspecting'){
      if(!onSource(r))finish(r);
      return;
    }
    if (!sameDocument(r) || o.navigation() !== r.navigation + 1) { finish(r); return; }
    // The local destination has not committed yet. It is not an interruption.
    if (o.navigationRevision < r.navigation + 1) return;
    if (o.route !== 'projects' || o.blocked || (o.projectId !== r.projectId && o.projectId !== r.choices?.projectId)) { finish(r); return; }
    if (nativeView()?.taskId === r.target.taskId && r.phase === 'waiting') return;
    if (!onDestination(r)) { finish(r); return; }
    if (r.phase === 'waiting' && r.choices) {
      const choices=r.choices;
      r.phase = 'selecting'; window.clearTimeout(r.timer);
      void (async () => {
        const state = bridge.getState().state;
        if (state.projectId !== choices.projectId || state.filePath) {
          if (!o.supported('projects.select')) throw new Error('无法打开这份成果所属的项目。');
          await bridge.command('projects.select', { id: choices.projectId });
          if (!onDestination(r)) { finish(r); return; }
        }
        r.phase = 'presenting'; timely(r, '未读到成果所属的项目，没有打开其他版本。'); wake(n => n + 1);
      })().catch(error => finish(r, onDestination(r) ? String(error instanceof Error ? error.message : error) : undefined));
    } else if (r.phase === 'presenting' && r.choices) {
      const state = bridge.getState().state, id = r.choices.projectId;
      if (o.projectId !== id || state.projectId !== id || state.filePath || !Array.isArray(state.projects) || !state.projects.some(p => p?.id === id)) return;
      // An explicit click on an exact saved result can open its single entry.
      // The guard outlives this handoff while the native renderer is opening.
      // If original build proof is unavailable, retain the regular archive review.
      const exactWebWindow = r.target.savedBuildWeb && r.choices.originalBuild ? () => {
        const now = latest.current, native = bridge.getState();
        return !document.hidden && bridge.getDocumentEpoch() === r.epoch && Number(nativeView()?.resumeRevision || 0) === r.resume &&
          now.navigation() === r.navigation + 1 && now.navigationRevision === r.navigation + 1 && now.route === 'projects' && !now.blocked &&
          now.projectId === id && native.state.projectId === id && !native.state.filePath && !nativeView()?.taskId && nativeView()?.section == null;
      } : undefined;
      if (o.show(r.choices,r.originalChat,exactWebWindow)) { r.shown(r.choices); finish(r); }
    }
  });
  return { opening, open };
}
