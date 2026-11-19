import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button, SettingsDialog } from './forms-library';
import { registerOverlayBack } from './overlay-back';

type Data = Record<string, any>;
type Props = {
  profile: Data; path: string; bytes: number; enabled: boolean; canReadText: boolean; busy: boolean;
  request: (action: string, args: Data) => Promise<Data>; onClose: () => void; onText: () => void;
  onReceipt: (value: Data) => void; openTask: (id: string) => void;
};
const limit = 20 * 1024 * 1024;
const binaryName = /\.(apk|aab|aar|jar|class|dex|so|o|a|exe|dll|zip|gz|tgz|xz|tar|7z|pdf|png|webp|jpe?g|gif|mp[34]|wasm)$/i;
const keyName = /(?:\.(?:jks|keystore|p12|pfx|pem|key)|(?:^|\/)id_(?:rsa|ecdsa|ed25519|dsa))$/i;
const privatePath = (path: string) => /(?:^|\/)\./.test(path) || keyName.test(path);
const windowKey = () => { const view = bridge.getState().view as Data | undefined; return [bridge.getDocumentEpoch(), view?.resumeRevision, view?.section, view?.taskId].join('|'); };
const retire = (handle: string) => { if (handle) void bridge.command('ssh.dismissDownload', { reviewHandle: handle }).catch(() => {}); };

/** Selecting a directory entry does not fetch it. Only a native streamed inspection can unlock saving. */
export function SshFilePickup({ profile, path, bytes, enabled, canReadText, busy, request, onClose, onText, onReceipt, openTask }: Props) {
  const [checked, setChecked] = useState<Data | null>(null), [result, setResult] = useState<Data | null>(null);
  const [message, setMessage] = useState(''), [work, setWork] = useState<'inspect' | 'save' | null>(null);
  const active = useRef(true), pending = useRef(false), handle = useRef(''), owner = useRef(windowKey()), close = useRef(onClose); close.current = onClose;
  const available = enabled && Number.isSafeInteger(bytes) && bytes >= 0 && bytes <= limit && !privatePath(path);
  const current = () => active.current && document.visibilityState === 'visible' && windowKey() === owner.current;
  const dismiss = () => { active.current = false; retire(handle.current); handle.current = ''; close.current(); };
  useEffect(() => {
    active.current = true;
    const leave = () => { if (active.current && !current()) dismiss(); };
    const pagehide = () => { if (active.current) dismiss(); };
    const unsubscribe = bridge.subscribe(leave), removeBack = registerOverlayBack(dismiss);
    document.addEventListener('visibilitychange', leave); window.addEventListener('pagehide', pagehide);
    return () => { active.current = false; retire(handle.current); handle.current = ''; unsubscribe(); removeBack(); document.removeEventListener('visibilitychange', leave); window.removeEventListener('pagehide', pagehide); };
  }, []);
  useEffect(() => {
    if (!checked) return;
    const timer = window.setTimeout(() => {
      if (current() && handle.current === checked.downloadReviewHandle) {
        retire(handle.current); handle.current = ''; setChecked(null); setMessage('这次版本核对已过期，没有自动保存。请返回目录重新读取。');
      }
    }, Math.max(0, checked.downloadReviewExpiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [checked]);
  const inspect = async () => {
    if (!current() || pending.current || busy || !available) return;
    pending.current = true; setWork('inspect'); setMessage('');
    try {
      const value = await request('ssh.inspectDownload', { profileId: profile.id, _profileBinding: profile.bindingHash, path });
      if (!current()) { retire(value.downloadReviewHandle); return; }
      if (value.status !== 'completed' || value.exists !== true || value.streamedHash !== true || value.profileId !== profile.id || value.path !== path
        || value.bytes !== bytes || !Number.isSafeInteger(value.downloadReviewExpiresAt) || value.downloadReviewExpiresAt <= Date.now() || !/^sha256:[a-f0-9]{64}$/.test(value.version) || typeof value.downloadReviewHandle !== 'string' || !value.downloadReviewHandle) {
        retire(value.downloadReviewHandle); setMessage(value.exists === false ? '这个文件已不存在。请返回目录重新读取。' : '没有核对到这个文件的实际版本。请返回目录重新读取，没有保存。'); return;
      }
      handle.current = value.downloadReviewHandle; setChecked(value);
    } catch (error) { if (current()) setMessage(error instanceof Error ? error.message : '文件版本未核对，没有保存。'); }
    finally { pending.current = false; if (current()) setWork(null); }
  };
  const save = async () => {
    const original = handle.current;
    if (!current() || pending.current || busy || !available || !checked || !original || original !== checked.downloadReviewHandle || Date.now() >= checked.downloadReviewExpiresAt) return;
    pending.current = true; handle.current = ''; setWork('save'); setMessage('');
    try {
      const value = await request('ssh.confirmDownload', { reviewHandle: original });
      if (current()) { setResult(value); onReceipt(value); }
    } catch (error) {
      if (current()) { setResult({}); setMessage(error instanceof Error ? error.message : '没有取得完整保存回执。'); }
    } finally { pending.current = false; if (current()) setWork(null); }
  };
  const saved = result?.privateDownloadVerified === true && result.attachment?.size === bytes && checked && result.version === checked.version;
  return <SettingsDialog isOpen title={saved ? '文件已保存到手机' : '服务器文件'} onClose={dismiss} closeLabel="返回文件目录"><div className="pw-remote-section pw-collaboration-dialog">
    <h3>{path.split('/').at(-1)}</h3><dl className="pw-facts">
      <div><dt>来源</dt><dd>{profile.name} · {profile.username}@{profile.host}:{profile.port}</dd></div>
      <div><dt>工作区</dt><dd>{profile.workspace}</dd></div><div><dt>文件路径</dt><dd>{path}</dd></div>
      <div><dt>{checked ? '核对大小' : '目录所列'}</dt><dd>{bytes.toLocaleString()} 字节</dd></div>
      <div><dt>保存到</dt><dd>这台手机的 PocketWorld 私人附件</dd></div>
    </dl>
    {checked && <div><p>实际文件 SHA-256</p><pre className="pw-connection-code">{checked.version.slice(7)}</pre></div>}
    {!checked && !result && <><p className="pw-field-hint">{!enabled ? '当前主机或本版未开放保存。' : privatePath(path) ? '不接收隐藏路径或密钥文件。' : !available ? '单个文件上限为 20 MiB，不会保存截断副本。' : '先只读核对这一个文件，不保存、不修改服务器。'}</p>
      <div className="pw-form-actions">{available && !message && <Button disabled={busy || !!work} onClick={() => void inspect()}>核对文件版本</Button>}
      {!message && canReadText && bytes <= 262144 && !binaryName.test(path) && !keyName.test(path) && <Button variant="secondary" disabled={busy || !!work} onClick={() => { if (current() && !pending.current) { dismiss(); onText(); } }}>读取为文本</Button>}</div></>}
    {work && <p role="status">{work === 'inspect' ? '正在读取原件并计算摘要…' : '正在核对并保存所选版本。返回目录不会撤回已确认的传输。'}</p>}
    {checked && !result && !work && <><p className="pw-field-hint">只保存上面这一版；文件变更就停止。不安装、不分享，也不据此认定由某次构建生成。</p><Button disabled={!available || busy} onClick={() => void save()}>保存这个版本到手机</Button></>}
    {result && <><p role="status">{saved ? '已核对手机副本与服务器所选版本一致。' : result.code === 'version_conflict' || result.code === 'source_changed' ? '原件已变更，没有保存所选版本。请重新读取目录。' : '本次未取得可确认的保存回执。请先查看这台主机的原任务，不要重复提交。'}</p>
      {result.hostTaskId && <Button variant="secondary" onClick={() => { if (current()) { dismiss(); openTask(result.hostTaskId); } }}>查看这次传输的原任务</Button>}</>}
    {message && <><p role="alert" className="pw-field-error">{message}</p>{!result && <Button variant="secondary" onClick={dismiss}>返回目录重新读取</Button>}</>}
  </div></SettingsDialog>;
}
