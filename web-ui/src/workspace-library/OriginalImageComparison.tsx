import { useState } from 'react';
import { Button } from '../forms-library';
import { CompareSlider } from '../shell-library/adapted/ovio/components/ovio/compare-slider/compare-slider';
import { MediaCarousel } from './MediaCarousel';
import { AttachmentOriginalReader } from '../AttachmentOriginalReader';
import type { FileJson } from '../attachment-workspace';
import './workspace-media.css';

/** Only receives the two original image pins created by compareParent. It
 * cannot choose a file, create a pin or dispatch an editing command. */
export function OriginalImageComparison({ sources, windowId }: { sources: FileJson[]; windowId: string }) {
  const [mode, setMode] = useState<'compare' | 'browse'>('compare'), [error, setError] = useState(false), [details, setDetails] = useState(false);
  if (sources.length !== 2 || sources.some(source => source.kind !== 'image' || typeof source.url !== 'string' || !source.url)) return <p className="pw-muted">两份原图尚未就绪，请返回后重新打开对照。</p>;
  const image = (source: FileJson, index: number) => <img src={source.url} alt={`${index ? '当前版本' : '父原图'}：${source.name || '原图'}`} draggable={false} onError={() => setError(true)} />;
  return <section className="pw-original-comparison" aria-label="两份固定原图对照">
    <div className="pw-comparison-toolbar"><div><h3>原图对照</h3><p className="pw-muted">{sources[0].name} → {sources[1].name}</p></div><div role="group" aria-label="对照显示方式"><Button variant={mode === 'compare' ? 'secondary' : 'ghost'} aria-pressed={mode === 'compare'} onClick={() => setMode('compare')}>滑动对照</Button><Button variant={mode === 'browse' ? 'secondary' : 'ghost'} aria-pressed={mode === 'browse'} onClick={() => setMode('browse')}>逐张查看</Button></div></div>
    {error ? <p className="pw-error" role="alert">原图读取已关闭或未完成，请返回后重新打开。</p> : mode === 'compare' ? <CompareSlider key={sources.map(source => source.id).join(':')} variant="minimal" label="移动分隔线比较父原图和当前版本" beforeLabel="父原图" afterLabel="当前版本" aspectRatio="4 / 3" className="pw-image-compare-slider" before={image(sources[0], 0)} after={image(sources[1], 1)} /> : <MediaCarousel label="固定原图" items={sources.map((source, index) => ({ id: String(source.id), content: <figure className="pw-original-image-card">{image(source, index)}<figcaption><strong>{index ? '当前版本' : '父原图'}</strong><small>{source.name}</small></figcaption></figure> }))} />}
    {mode === 'compare' && !error && <p className="pw-muted pw-compare-help">拖动分隔线，或聚焦后使用方向键；Home / End 可查看完整单侧。</p>}
    <details className="pw-comparison-originals" onToggle={event => setDetails(event.currentTarget.open)}><summary>分别缩放与查看原图信息</summary>{details && <div className="pw-image-comparison">{sources.map(source => <AttachmentOriginalReader key={source.id} source={source} windowId={windowId}/>)}</div>}</details>
  </section>;
}
