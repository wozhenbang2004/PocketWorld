import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ProjectFileEditor } from '../src/ProjectFileEditor';
import type { FileLayer } from '../src/project-file';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';
const calls: string[] = [];
let back: (() => boolean) | null = null;
function Fixture() {
  const [session, setSession] = useState({ before: '开头保持不变\n状态：旧内容\n结尾保持不变\n', after: '开头保持不变\n状态：新内容\n结尾保持不变\n', saving: false });
  const [mode, setMode] = useState<FileLayer>('diff');
  Object.assign(window, { fileDiffFixture: { calls, set: (before: string, after: string, saving = false) => { setSession({ before, after, saving }); setMode('diff'); }, back: () => back?.() } });
  return <div className="pw-app pw-redesign"><main className="pw-page pw-projects-page"><div className="pw-project-work"><ProjectFileEditor path="笔记/比较验收.txt" version={7} draft={{ text: session.after, baseline: session.before, version: 7, dirty: session.before !== session.after }} state={{ fileReady: true, fileSaving: session.saving }} conflict={false} mode={mode} setMode={setMode} can={action => action === 'projects.save'} edit={after => setSession(value => ({ ...value, after }))} save={() => calls.push('save')} readHistory={() => calls.push('history')} readRevision={() => calls.push('revision')} retry={() => calls.push('retry')} copy={() => calls.push('copy')} adoption={{ pending: false, unknown: false, message: '', adopt: () => calls.push('adopt') }} registerBack={handler => { back = handler; }} /></div></main></div>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
