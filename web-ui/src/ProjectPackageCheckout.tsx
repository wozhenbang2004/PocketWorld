import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { registerOverlayBack } from './overlay-back';
import { ProjectCheckoutFiles } from './ProjectCheckoutFiles';
import type { PackagePreviewChoices, SourceMember } from './project-preview';

type Target = PackagePreviewChoices & { sourceMember: SourceMember };
const ownerWindow = (project: string) => {
  const { state, view } = bridge.getState(), window = view as { taskId?: string; section?: string; resumeRevision?: number } | undefined;
  return !document.hidden && window && !window.taskId && window.section == null && state.projectId === project && !state.filePath
    ? JSON.stringify([bridge.getDocumentEpoch(), project, window.resumeRevision]) : '';
};
const release = (handle: string) => { if (handle) void bridge.command('projects.development.close', { viewHandle: handle }).catch(() => {}); };

/** Ordinary owner editor reached through a pinned source file, never the new-job form's remembered checkout. */
export function ProjectPackageCheckout({ target, onReturn, onDismiss }: { target: Target; onReturn: () => void; onDismiss: () => void }) {
  const { projectId, checkoutId, sourceMember, taskId, operationId } = target;
  const [view, setView] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const page = useRef(''), generation = useRef(0), attempt = useRef(false), callbacks = useRef({ onReturn, onDismiss });
  callbacks.current = { onReturn, onDismiss };
  const removeBack = useRef<(() => void) | null>(null);
  const registerBack = useCallback((handler: (() => boolean) | null) => {
    removeBack.current?.(); removeBack.current = handler ? registerOverlayBack(() => handler()) : null;
  }, []);
  const open = async () => {
    const origin = ownerWindow(projectId), serial = generation.current;
    if (!origin || attempt.current || page.current) return;
    attempt.current = true; setBusy(true); setError('');
    const current = () => serial === generation.current && origin === ownerWindow(projectId);
    try {
      const result = await bridge.command('projects.development.read', { projectId });
      if (!current() || result.projectId !== projectId || !result.viewHandle || result.commandsAuthorized !== false) {
        release(typeof result.viewHandle === 'string' ? result.viewHandle : ''); throw new Error('未能核对原项目，未打开其他副本。');
      }
      page.current = result.viewHandle; setView(page.current);
    } catch (e) { if (current()) setError(e instanceof Error ? e.message : '原副本暂时不可读取。'); }
    finally { if (current()) { attempt.current = false; setBusy(false); } }
  };
  useEffect(() => {
    const origin = ownerWindow(projectId);
    const close = () => { if (!origin || origin !== ownerWindow(projectId)) callbacks.current.onDismiss(); };
    const unsub = bridge.subscribe(close); document.addEventListener('visibilitychange', close);
    if (origin) void open(); else close();
    return () => { ++generation.current; attempt.current = false; release(page.current); page.current = ''; unsub(); removeBack.current?.(); document.removeEventListener('visibilitychange', close); };
  }, [projectId, checkoutId, taskId, operationId, sourceMember.path]);
  return view ? <ProjectCheckoutFiles projectId={projectId} checkoutId={checkoutId} viewHandle={view} run={bridge.command} registerBack={registerBack}
    sourceMember={{ ...sourceMember, taskId, operationId }} onClose={() => { if (ownerWindow(projectId)) callbacks.current.onReturn(); else callbacks.current.onDismiss(); }}/> :
    <section className="pw-preview-pane" aria-busy={busy} aria-label="打开原工作副本"><h3 dir="auto">{sourceMember.path}</h3>
      {busy && <p className="pw-muted" role="status">正在核对这个原工作的项目与私人副本…</p>}
      {error && <><p role="alert">{error}</p><Button variant="secondary" onClick={() => void open()}>重读原项目</Button></>}
      <Button variant="ghost" onClick={onReturn}>返回原工作</Button>
    </section>;
}
