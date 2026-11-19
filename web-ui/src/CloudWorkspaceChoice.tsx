import { Button } from './forms-library';
import { harnessWorkerLabel } from './harness-capabilities';

type Data = Record<string, any>;
export type SavedCloudWorkspace = { profileId: string; profileBinding: string; environmentId: string; worker: string; workspaceAnchor: string };
const digest = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const id = (v: unknown): v is string => typeof v === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(v);
export function cloudChoice(value: Data | undefined): { revision: number; ownerDeclaredByos: boolean; target?: SavedCloudWorkspace } | null {
  if (value?.selectionOnly !== true || value.liveState !== 'not_checked' || !Number.isSafeInteger(value.revision) || value.revision < 0) return null;
  const t = value.target;
  if (t == null) return { revision: value.revision, ownerDeclaredByos:false };
  return id(t.profileId) && digest(t.profileBinding) && id(t.environmentId) && id(t.worker) && t.worker !== 'command' && digest(t.workspaceAnchor)
    ? { revision: value.revision, target: t, ownerDeclaredByos:value.ownerDeclaredByos === true } : null;
}
export function hostWorkspaceAnchor(environment?: Data): string | null {
  const value = environment?.hostWorkspace;
  return environment?.kind === 'local' && value?.format === 'companion_host_workspace_v1' && digest(value.anchor) &&
    value.jobRegistry === 'persistent_operations' && value.runningAfterSupervisorRestart === 'not_guaranteed' && value.processSandbox === false
    ? value.anchor : null;
}
export function originalCloudEnvironment(catalog: Data[], t: SavedCloudWorkspace): Data | undefined {
  const rows = catalog.filter(e => e.id === t.environmentId);
  if (rows.length !== 1 || hostWorkspaceAnchor(rows[0]) !== t.workspaceAnchor || !digest(rows[0].bindingHash)) return;
  const workers = Array.isArray(rows[0].workers) ? rows[0].workers.filter((w: Data) => w?.id === t.worker) : [];
  return workers.length === 1 && workers[0].available !== false ? rows[0] : undefined;
}

/** Saved selection is a convenience, not evidence that a host or process is online. */
export function CloudWorkspaceChoice({ profile, value, environment, worker, fresh, locked, useSaved, save, forget, label }:
  { profile: Data; value: ReturnType<typeof cloudChoice>; environment?: Data; worker?: Data; fresh: boolean; locked: boolean;
    useSaved: () => void; save: () => void; forget: () => void; label?: (byos: boolean) => void }) {
  if (!value) return null;
  const byos = value.ownerDeclaredByos, saved = value.target, here = saved && saved.profileId === profile.id && saved.profileBinding === profile.bindingHash ? saved : undefined;
  const anchor = hostWorkspaceAnchor(environment), selectable = fresh && !!anchor && !!worker && worker.id !== 'command' && worker.available !== false;
  const same = here && selectable && here.environmentId === environment?.id && here.worker === worker?.id && here.workspaceAnchor === anchor;
  if (!here && !selectable) return null;
  return <div className="pw-connection-subsection">
    {here && <><h4>{byos ? '我的自带云工作区' : '常用主机工作区'}</h4><p>{profile.name} · <bdi>{here.environmentId}</bdi> · {harnessWorkerLabel(undefined, here.worker)}</p>
      <p className="pw-field-hint">{same ? '刚核对的是同一台服务的原工作目录。' : '这是手机记住的位置，不代表在线。核对后才选用，不带入旧权限。'}</p></>}
    <div className="pw-form-actions">{here && !same && <Button size="small" variant="secondary" disabled={locked} onClick={useSaved}>核对并选用原工作区</Button>}
      {selectable && !same && <Button size="small" variant="secondary" disabled={locked} onClick={save}>{saved ? '记住当前工作区，替换原选择' : '记住这个主机工作区'}</Button>}
      {here && label && <Button size="small" variant="ghost" disabled={locked} onClick={() => label(!byos)}>{byos ? '移除自带云标记' : '标为我的自带云'}</Button>}
      {here && <Button size="small" variant="ghost" disabled={locked} onClick={forget}>不再记住</Button>}</div>
    {here && label && <p className="pw-field-hint">{byos ? '“自带云”是你对这处工作区的标记。' : '同一处 Linux 工作区也可以在局域网，无需标为自带云。'}标记只留在手机，不会因此上传对话、仓库或其他设备资料。</p>}
    {selectable && <p className="pw-field-hint">工作在这台 Linux 主机上，不在手机或 SSH 第二跳。主机保留原作业记录；服务重启不保证进程续跑，工作目录也不是系统沙箱。</p>}
  </div>;
}
