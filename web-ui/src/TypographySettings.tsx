import { useEffect, useRef, useState } from 'react';
import { Button, SelectField } from './forms-library';
import {
  CHINESE_FONTS, CODE_FONTS, DEFAULT_TYPOGRAPHY, LATIN_FONTS,
  normalizeTypography, typeface, typographyArgs, typographyKey, typographySaved, typographyStacks,
  type TypographyPreferences,
} from './typography';
import './typography.css';

type Data = Record<string, unknown>;
type Props = { state: Data; supported: (action: string) => boolean; run: (action: string, args?: Record<string, unknown>) => Promise<unknown> };
const object = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const options = (fonts: typeof CHINESE_FONTS) => fonts.map(font => ({ id: font.id, label: font.label, description: font.description }));

/** BoardUI selectors and native persistence; draft preview never changes the saved UI preference. */
export function TypographySettings({ state, supported, run }: Props) {
  const appearance = object(state.appearance), persisted = normalizeTypography(appearance.typography);
  const persistedKey = typographyKey(persisted);
  const [draft, setDraft] = useState<TypographyPreferences>(persisted);
  const [pending, setPending] = useState(false), [error, setError] = useState('');
  const [saved, setSaved] = useState<TypographyPreferences | null>(null);
  const [preview, setPreview] = useState<'loading' | 'ready' | 'error'>('loading');
  const [previewAttempt, setPreviewAttempt] = useState(0);
  const mounted = useRef(true), saving = useRef(false), previous = useRef(persistedKey), draftRef = useRef(draft);
  draftRef.current = draft;
  const draftKey = typographyKey(draft), fonts = typographyStacks(draft);
  const canSave = supported('settings.typography');
  const dirty = draftKey !== persistedKey;
  // A native disk failure can still publish the new in-memory preference.
  // Keep an explicit retry available even when that snapshot now matches the draft.
  const visibleError = error || (typeof appearance.error === 'string' ? appearance.error : '');
  const needsSave = dirty || Boolean(visibleError);
  const locked = pending || appearance.saving === true;

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    // Adopt an update from another native window only when this editor was clean.
    const prior = previous.current;
    previous.current = persistedKey;
    if (typographyKey(draftRef.current) === prior) setDraft(persisted);
  }, [persistedKey]);
  useEffect(() => {
    let cancelled = false;
    setPreview('loading');
    if (!document.fonts) { setPreview('error'); return; }
    // Only the three selected families are requested. Menu labels use the current UI font.
    void Promise.all([
      document.fonts.load(`400 18px "${typeface(draft.chinese).family}"`, '今天，专注一件重要的事。'),
      document.fonts.load(`400 18px "${typeface(draft.latin).family}"`, 'Make room for what matters.'),
      document.fonts.load(`400 16px "${typeface(draft.code).family}"`, 'const goal = 1;'),
    ]).then(loaded => {
      if (!cancelled) setPreview(loaded.every(faces => faces.length > 0 && faces.every(face => face.status === 'loaded')) ? 'ready' : 'error');
    }).catch(() => { if (!cancelled) setPreview('error'); });
    return () => { cancelled = true; };
  }, [draftKey, previewAttempt]);

  const change = (role: keyof TypographyPreferences, value: string) => {
    if (locked) return;
    setDraft(current => normalizeTypography({ ...current, [role]: value }));
    setSaved(null); setError('');
  };
  const save = async () => {
    if (saving.current || locked || !canSave || !needsSave || preview !== 'ready') return;
    const submitted = typographyArgs(draft);
    saving.current = true; setPending(true); setSaved(null); setError('');
    try {
      const result = await run('settings.typography', { ...submitted });
      if (!mounted.current) return;
      if (!typographySaved(result, submitted)) throw new Error('字体保存尚未确认，当前选择已保留，请重试。');
      setSaved(submitted);
    } catch (failure) {
      if (mounted.current) setError(failure instanceof Error ? failure.message : '字体未能保存，请重试。');
    } finally {
      saving.current = false;
      if (mounted.current) setPending(false);
    }
  };

  return <section className="pw-typography-settings" aria-label="字体设置">
    <div className="pw-typography-heading"><h3>字体</h3><span className="pw-typography-draft-state">{locked ? '正在保存字体…' : visibleError ? '保存待确认' : dirty ? '尚未保存的选择' : '当前字体组合'}</span></div>
    <div className="pw-typography-workbench">
      <div className="pw-typography-preview" aria-label="所选字体预览" aria-busy={preview === 'loading'}>
        {preview === 'loading' && <p className="pw-field-hint" role="status">正在准备所选字体…</p>}
        {preview === 'error' && <div className="pw-typography-preview-error"><p className="pw-field-hint" role="status">字体预览暂未载入，当前选择已保留。</p><Button variant="ghost" size="small" onClick={() => setPreviewAttempt(value => value + 1)}>重试预览</Button></div>}
        <div className="pw-typography-specimens" hidden={preview !== 'ready'}>
          <p lang="zh-CN" className="pw-typography-sample-cjk" style={{ fontFamily: fonts.chinese, fontWeight: 400 }}>中文界面与阅读</p>
          <p lang="en" className="pw-typography-sample-latin" style={{ fontFamily: fonts.ui, fontWeight: 400 }}>Interface · 0123456789</p>
          <code className="pw-typography-sample-code" style={{ fontFamily: fonts.code, fontWeight: 400 }}>const goal = "你好";  // 0O 1Il</code>
        </div>
      </div>
      <div className="pw-typography-fields">
        <SelectField compactValue label="中文" options={options(CHINESE_FONTS)} value={draft.chinese} onChange={value => change('chinese', value)} isDisabled={locked || !canSave} />
        <SelectField compactValue label="英文与数字" options={options(LATIN_FONTS)} value={draft.latin} onChange={value => change('latin', value)} isDisabled={locked || !canSave} />
        <SelectField compactValue label="代码与终端" options={options(CODE_FONTS)} value={draft.code} onChange={value => change('code', value)} isDisabled={locked || !canSave} />
      </div>
    </div>
    <p className="pw-field-hint">选择后先预览，保存才会应用到整个界面。</p>
    <div className="pw-form-actions pw-typography-actions">
      <Button disabled={locked || !canSave || !needsSave || preview !== 'ready'} onClick={() => { void save(); }}>{pending ? '正在保存…' : visibleError ? '重试保存' : '保存字体'}</Button>
      {dirty && <Button variant="ghost" disabled={locked} onClick={() => { setDraft({ ...persisted }); setSaved(null); setError(''); }}>撤销本次选择</Button>}
      <Button variant="ghost" disabled={locked || !canSave || draftKey === typographyKey(DEFAULT_TYPOGRAPHY)} onClick={() => { setDraft({ ...DEFAULT_TYPOGRAPHY }); setSaved(null); setError(''); }}>恢复默认组合</Button>
    </div>
    {!canSave && <p className="pw-field-hint">当前窗口暂不支持保存字体。</p>}
    {visibleError && <p className="pw-error" role="alert">{visibleError}</p>}
    {saved && !visibleError && <p className="pw-field-hint" role="status">{typographyKey(saved) === persistedKey ? '字体已保存并应用。' : '字体已保存，正在应用。'}</p>}
  </section>;
}
