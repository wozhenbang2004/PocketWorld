/** Test-only entrypoint. Real browser media elements, deterministic synthetic
 * WAV/WebM/SVG files. No host bridge, native authorization or real user assets. */
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { OriginalMediaPlayer } from '../src/workspace-library/OriginalMediaPlayer';
import { OriginalImageComparison } from '../src/workspace-library/OriginalImageComparison';
import { AttachmentFileCollection } from '../src/workspace-library/AttachmentFileCollection';
import { FilesBrowser } from '../src/shell-library/FilesBrowser';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';

const image = (color: string, text: string) => 'data:image/svg+xml,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="${color}"/><circle cx="400" cy="300" r="140" fill="#ffffff50"/><text x="400" y="310" fill="white" font-family="sans-serif" font-size="40" text-anchor="middle">${text}</text></svg>`);
const sources = [{ id: 'fixture-parent-pin', kind: 'image', name: '测试父原图.svg', url: image('#535b69', 'FIXTURE BEFORE') }, { id: 'fixture-current-pin', kind: 'image', name: '测试当前图.svg', url: image('#8b7664', 'FIXTURE AFTER') }];
const files = Array.from({ length: 6 }, (_, index) => ({ id: 'fixture-file-' + index, sha256: String(index).repeat(64), name: `测试图片来源 ${index + 1}.png`, mimeType: 'image/png', size: 2048 + index }));
function Fixture() {
  const [selected, setSelected] = useState(''), [visible, setVisible] = useState(true), [kind, setKind] = useState<'audio' | 'video'>('audio');
  (window as any).workspaceMediaFixture = { selected, hide: () => setVisible(false), show: () => setVisible(true), video: () => setKind('video') };
  return <main className="pw-redesign" style={{ maxWidth: 900, margin: '0 auto', padding: 20, display: 'grid', gap: 32 }}><p>仅测试素材 · 不代表 Android 或真实文件授权验证</p>
    {visible && <OriginalMediaPlayer key={kind} name={kind === 'audio' ? '真实浏览器 WAV 测试' : '真实浏览器 WebM 测试'} kind={kind} url={kind === 'audio' ? '/fixture-tone.wav' : '/fixture-video.webm'} onError={() => { throw new Error('fixture media failed'); }} />}
    <OriginalImageComparison sources={sources} windowId="fixture-window"/>
    <AttachmentFileCollection label="测试来源" files={files} busy={false} choosing={false} onSelect={target => setSelected(target.fileId)}/>
    <FilesBrowser files={[{ path: 'src/example.ts', version: 3 }, { path: '设计/长期项目说明.md', version: 2 }]} selectedPath="src/example.ts" onOpen={file => setSelected(file.path)} />
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture/>);
