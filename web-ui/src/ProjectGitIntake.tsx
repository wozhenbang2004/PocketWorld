import { useEffect, useRef, useState, type RefObject } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog, TextField } from './forms-library';
import './project-git-intake.css';

type IntakeState = {
  open?: boolean; busy?: boolean; notice?: string; error?: string;
  sourceStatus?: string; sourcePending?: boolean; sourceAvailable?: boolean;
  creationPending?: boolean; creationFailed?: boolean; projectReady?: boolean; reviewKind?: string; reviewId?: string;
  reviewUrl?: string; draftBranch?: string; projectName?: string; branch?: string; commit?: string; sourceBytes?: number;
};

export type PublicGitDraft = { url: string; branch: string; name: string };
export function ProjectGitIntake({ state, toolReturn, onPrepareTools }: {
  state?: IntakeState; toolReturn: RefObject<PublicGitDraft | null>; onPrepareTools?: () => void;
}) {
  const [url, setUrl] = useState(toolReturn.current?.url || '');
  const [branch, setBranch] = useState(toolReturn.current?.branch || '');
  const [name, setName] = useState(toolReturn.current?.name || '');
  useEffect(() => { toolReturn.current = null; }, [toolReturn]);
  const [pending, setPending] = useState(false), [error, setError] = useState('');
  const pendingRef = useRef(false), wasOpen = useRef(false);
  wasOpen.current = !!state?.open;
  useEffect(() => () => {
    if (wasOpen.current) void bridge.command('projects.gitImport.close').catch(() => {});
  }, []);
  useEffect(() => { if (!state?.open) setError(''); }, [state?.open]);
  const busy = pending || !!state?.busy;
  const doAction = async (action: string, args: Record<string, string> = {}) => {
    if (pendingRef.current) return false;
    pendingRef.current = true; setPending(true); setError('');
    try { await bridge.command('projects.gitImport.' + action, args); return true; }
    catch (failure) { setError(failure instanceof Error ? failure.message : '原导入操作尚未确认'); return false; }
    finally { pendingRef.current = false; setPending(false); }
  };
  const review = state?.reviewId && state?.reviewKind;
  const readySource = state?.sourceStatus === 'completed' && state?.sourceAvailable !== false && !!state.commit;
  const sourceEnded = state?.sourceStatus === 'completed' || state?.sourceStatus === 'failed';
  const canReview = !busy && !!url.trim() && !!branch.trim() && !!name.trim();
  const details = [
    ['项目', state?.projectName],
    ['分支', state?.draftBranch || state?.branch],
    ['固定提交', state?.commit],
    ...(typeof state?.sourceBytes === 'number' && state.sourceBytes >= 0
      ? [['源码大小', new Intl.NumberFormat('zh-CN').format(state.sourceBytes) + ' 字节']] : []),
  ].filter(([, value]) => !!value);
  return <SettingsDialog isOpen={!!state?.open} title="从公开 Git 导入" closeLabel="返回项目目录"
    onClose={() => { void doAction('close'); }} className="pw-project-git-intake">
    <div className="pw-project-git-body" aria-busy={busy}>
      {(error || state?.error) && <p className="pw-error" role="alert">{error || state?.error}</p>}
      {state?.notice && !error && <p role="status">{state.notice}</p>}
      {review ? <>
        <h3>{state.reviewKind === 'fetch' ? '核对联网读取' : '核对本机项目'}</h3>
        {state.reviewUrl && <p className="pw-project-git-address" dir="auto">{state.reviewUrl}</p>}
        {details.length > 0 && <dl>{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd dir="auto">{value}</dd></div>)}</dl>}
        <p>{state.reviewKind === 'fetch' ? '只读取此 HTTPS 来源所选分支。不执行仓库代码，也不读取子模块或 LFS。读取后须再确认才会创建项目。' : '把已经核对的固定版本作为新的私人项目保存在这台手机，不覆盖现有项目，不上传、运行或发布代码。'}</p>
        <div className="pw-action-row"><Button variant="primary" disabled={busy} onClick={() => void doAction('confirm', { reviewId: state.reviewId! })}>{state.reviewKind === 'fetch' ? '确认读取此分支' : '确认创建本机项目'}</Button>
          <Button variant="ghost" disabled={busy} onClick={() => void doAction('dismissReview')}>返回核对前</Button></div>
      </> : state?.projectReady ? <>
        <h3>找到已创建的原项目</h3>
        <div className="pw-action-row"><Button variant="primary" disabled={busy} onClick={() => void doAction('openProject')}>打开这个项目</Button>
          <Button variant="ghost" disabled={busy} onClick={() => void doAction('clearPosition')}>导入另一个项目</Button></div>
      </> : state?.creationPending ? <>
        <h3>{state.creationFailed ? '本机项目未创建' : '核对原项目创建结果'}</h3><p>不会重复创建同一项目。</p>
        <div className="pw-action-row"><Button variant="secondary" disabled={busy} onClick={() => void doAction('queryCreate')}>查看原创建结果</Button>
          {state.creationFailed && <Button variant="ghost" disabled={busy} onClick={() => void doAction('clearPosition')}>返回填写新来源</Button>}</div>
      </> : state?.sourcePending ? <>
        <h3>{readySource ? '已读取固定版本' : sourceEnded ? '原读取已结束' : '等待原读取结果'}</h3>
        {!sourceEnded && <p>关闭页面不代表停止读取。再次打开，可以核对同一操作。</p>}
        {details.length > 0 && <dl>{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd dir="auto">{value}</dd></div>)}</dl>}
        <div className="pw-action-row">
          {readySource && <Button variant="primary" disabled={busy} onClick={() => void doAction('reviewCreate')}>核对并创建项目</Button>}
          <Button variant="secondary" disabled={busy} onClick={() => void doAction('querySource')}>查看原读取结果</Button>
          {!sourceEnded && <Button variant="ghost" disabled={busy} onClick={() => void doAction('stopSource')}>停止这次读取</Button>}
          {(state.sourceStatus === 'failed' || state.sourceAvailable === false) && sourceEnded && <Button variant="ghost" disabled={busy} onClick={() => void doAction('clearPosition')}>返回填写新来源</Button>}
        </div>
      </> : <>
        <p>填写公开仓库和准确分支。不会登录、保存凭据或更改已有项目。</p>
        <TextField label="公开 HTTPS 仓库地址" value={url} onChange={setUrl} maxLength={2048} isDisabled={busy} placeholder="https://github.com/owner/project.git"/>
        <TextField label="要读取的分支" value={branch} onChange={setBranch} maxLength={120} isDisabled={busy} placeholder="例如 main"/>
        <TextField label="本机项目名称" value={name} onChange={setName} maxLength={100} isDisabled={busy}/>
        <div className="pw-action-row">
          <Button variant="primary" disabled={!canReview} onClick={() => void doAction('review', { url, branch, name })}>核对来源与分支</Button>
          {onPrepareTools && <Button variant="ghost" disabled={busy} onClick={() => { void doAction('close').then(closed => {
            if (!closed) return;
            toolReturn.current = { url, branch, name };
            onPrepareTools();
          }); }}>准备本机开发工具</Button>}
        </div>
      </>}
    </div>
  </SettingsDialog>;
}
