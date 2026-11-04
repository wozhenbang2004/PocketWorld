import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { adoptedRevisionSnapshot, revisionSourceSnapshot, revisionWindowMatches, type RevisionAdoption } from './project-file';

type Options = { key: string; active: boolean; allowed: boolean; saving: boolean; localCurrent: () => { editRevision: number; navigationRevision: number }; current: RevisionAdoption | null; apply: (original: RevisionAdoption) => void };
/** Adopt only after both the original host receipt and its exact state arrive. Never replay. */
export function useProjectRevision(options: Options) {
  const current = useRef(options); current.current = options;
  const operation = useRef<RevisionAdoption | null>(null), serial = useRef(0), quarantined = useRef(false);
  const [pending, setPending] = useState(false), [message, setMessage] = useState(''), [unknown, setUnknown] = useState(false);
  useEffect(() => { serial.current++; operation.current = null; quarantined.current = false; setPending(false); setMessage(''); setUnknown(false); }, [options.key]);
  useEffect(() => {
    const invalidate = () => {
      const original = operation.current;
      if (original && (document.hidden || bridge.getDocumentEpoch() !== original.documentEpoch || !current.current.active || current.current.current?.navigationRevision !== original.navigationRevision || Number((bridge.getState().view as { resumeRevision?: number })?.resumeRevision || 0) !== original.resumeRevision)) {
        quarantined.current = true; serial.current++; operation.current = null; setPending(false); setUnknown(true); setMessage('采用操作的回执尚未核对，原草稿仍保留，没有重发。');
      }
    };
    const unsubscribe = bridge.subscribe(invalidate); document.addEventListener('visibilitychange', invalidate); invalidate();
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', invalidate); };
  }, [options.active, options.current?.navigationRevision, options.current?.resumeRevision]);
  useEffect(() => () => { serial.current++; operation.current = null; }, []);
  const adopt = () => {
    const latest = current.current, original = latest.current;
    if (!original || !latest.active || !latest.allowed || latest.saving || operation.current || quarantined.current || unknown || document.hidden) return;
    const snapshot = bridge.getState();
    if (bridge.getDocumentEpoch() !== original.documentEpoch || Number((snapshot.view as { resumeRevision?: number })?.resumeRevision || 0) !== original.resumeRevision || snapshot.state.fileSaving || !revisionSourceSnapshot(original, snapshot.state) || latest.localCurrent().editRevision !== original.editRevision || latest.localCurrent().navigationRevision !== original.navigationRevision) return;
    const request = ++serial.current; let confirmed = false; operation.current = original; quarantined.current = true; setPending(true); setMessage('');
    const valid = () => request === serial.current && operation.current === original && !document.hidden && current.current.active &&
      bridge.getDocumentEpoch() === original.documentEpoch && Number((bridge.getState().view as { resumeRevision?: number })?.resumeRevision || 0) === original.resumeRevision &&
      current.current.localCurrent().editRevision === original.editRevision && current.current.localCurrent().navigationRevision === original.navigationRevision && !!current.current.current && revisionWindowMatches(original, current.current.current);
    void bridge.command('projects.useRevision', { projectId: original.projectId, path: original.path, version: original.version, revision: original.revision }).then(async receipt => {
      if (!valid()) return;
      if (receipt?.accepted !== true) throw new Error('原主机没有确认采用这份历史内容，草稿仍保留。');
      await new Promise<void>((resolve, reject) => {
        let unsubscribe = () => {};
        const timeout = window.setTimeout(() => { unsubscribe(); reject(new Error('采用回执已收到，但未读到同一文件和版本的内容；原草稿保留，没有重发。')); }, 5000);
        const check = () => {
          if (!valid()) { clearTimeout(timeout); unsubscribe(); resolve(); return; }
          if (adoptedRevisionSnapshot(original, bridge.getState().state)) { clearTimeout(timeout); unsubscribe(); resolve(); }
        };
        unsubscribe = bridge.subscribe(check); check();
      });
      if (!valid() || !adoptedRevisionSnapshot(original, bridge.getState().state)) return;
      confirmed = true; current.current.apply(original); quarantined.current = false; setMessage('历史内容已放入草稿。保存后才会生成新版本。');
    }).catch(failure => {
      if (request !== serial.current) return;
      setUnknown(true); setMessage(failure instanceof Error ? failure.message : '采用结果尚未核对，原草稿仍保留。');
    }).finally(() => {
      if (request !== serial.current) return;
      if (!confirmed && !valid()) { setUnknown(true); setMessage('原文件窗口或编辑草稿已变化，采用结果未覆盖当前草稿；没有重发。'); }
      operation.current = null; setPending(false);
    });
  };
  return { pending, message, unknown, adopt, isPending: () => !!operation.current, holdsDraft: () => quarantined.current || !!operation.current || unknown };
}
