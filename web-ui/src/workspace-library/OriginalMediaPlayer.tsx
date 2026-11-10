import { useEffect, useRef, useState } from 'react';
import { Slider, SliderThumb, SliderTrack } from 'react-aria-components';
import { IconMusic, IconPause, IconPlay, IconSeekBack, IconSeekForward, IconSpeaker, IconSpeakerOff } from '../icons';
import { IconButton } from '../forms-library';
import './workspace-media.css';

const clock = (seconds: number) => {
  const value = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
};

/** Stageshow hero-grid Player's original content/names/rail/times/control
 * composition. Its illustration and scripted time are replaced by the single
 * host-authorized original media element. React Aria owns the seek interaction. */
export function OriginalMediaPlayer({ url, kind, name, onError }: { url: string; kind: 'audio' | 'video'; name: string; onError: () => void }) {
  const media = useRef<HTMLMediaElement>(null);
  const [playing, setPlaying] = useState(false), [muted, setMuted] = useState(false);
  const [duration, setDuration] = useState(0), [time, setTime] = useState(0), [waiting, setWaiting] = useState(false), [problem, setProblem] = useState('');
  useEffect(() => {
    const node = media.current;
    setPlaying(false); setDuration(0); setTime(0); setWaiting(false); setProblem('');
    const pause = () => node?.pause();
    const visibility = () => { if (document.hidden) pause(); };
    document.addEventListener('visibilitychange', visibility); window.addEventListener('pagehide', pause);
    return () => { pause(); document.removeEventListener('visibilitychange', visibility); window.removeEventListener('pagehide', pause); };
  }, [url]);
  const sync = () => {
    const node = media.current; if (!node) return;
    setTime(node.currentTime); setDuration(Number.isFinite(node.duration) ? node.duration : 0); setMuted(node.muted);
  };
  const seek = (next: number) => {
    const node = media.current; if (!node || !duration || !node.seekable.length) return;
    try { node.currentTime = Math.max(0, Math.min(duration, next)); sync(); }
    catch { setProblem('当前读取范围尚不能跳转，请稍后重试。'); }
  };
  const toggle = async () => {
    const node = media.current; if (!node || document.hidden) return;
    if (!node.paused) { node.pause(); return; }
    try { setProblem(''); await node.play(); }
    catch { setProblem('播放没有开始，请重试或返回后用其他应用打开。'); }
  };
  const events = { onLoadedMetadata: sync, onDurationChange: sync, onTimeUpdate: sync, onVolumeChange: sync,
    onPlay: () => setPlaying(true), onPause: () => { setPlaying(false); setWaiting(false); }, onEnded: () => setPlaying(false),
    onWaiting: () => setWaiting(true), onPlaying: () => setWaiting(false), onCanPlay: () => setWaiting(false),
    onError: () => { setPlaying(false); setWaiting(false); onError(); } };
  return <section className="pw-original-player hg-player" aria-label={`${kind === 'audio' ? '音频' : '视频'}播放器：${name}`}>
    {kind === 'video' ? <video ref={node => { media.current = node; }} playsInline preload="metadata" src={url} {...events} /> : <audio ref={node => { media.current = node; }} preload="metadata" src={url} {...events} />}
    <div className="hg-player-content" data-stack="true">
      {kind === 'audio' && <span className="hg-cover" aria-hidden="true"><IconMusic size={28} /></span>}
      <div className="hg-player-side">
        <div className="hg-player-names"><strong>{name}</strong><span>{waiting ? '正在缓冲…' : playing ? '正在播放原件' : '固定原件'}</span></div>
        <div className="hg-player-progress">
          <Slider className="pw-media-seek" aria-label="播放进度" minValue={0} maxValue={Math.max(duration, 1)} step={.1} value={Math.min(time, duration)} onChange={seek} isDisabled={!duration} formatOptions={{ style: 'unit', unit: 'second' }}>
            <SliderTrack className="hg-player-rail">{({ state }) => <><i style={{ width: `${state.getThumbPercent(0) * 100}%` }} /><SliderThumb className="pw-media-seek-thumb" /></>}</SliderTrack>
          </Slider>
          <div className="hg-player-times"><span>{clock(time)}</span><span>{duration ? clock(duration) : '时长待读取'}</span></div>
        </div>
        <div className="hg-player-controls">
          <IconButton icon={IconSeekBack} aria-label="后退 10 秒" disabled={!duration} onClick={() => seek(time - 10)} />
          <IconButton icon={playing ? IconPause : IconPlay} className="hg-player-play" aria-label={playing ? '暂停原件' : '播放原件'} aria-pressed={playing} onClick={() => void toggle()} />
          <IconButton icon={IconSeekForward} aria-label="前进 10 秒" disabled={!duration} onClick={() => seek(time + 10)} />
          <IconButton icon={muted ? IconSpeakerOff : IconSpeaker} aria-label={muted ? '取消静音' : '静音'} aria-pressed={muted} onClick={() => { if (media.current) media.current.muted = !media.current.muted; }} />
        </div>
        {problem && <p className="pw-player-problem" role="alert">{problem}</p>}
      </div>
    </div>
  </section>;
}
