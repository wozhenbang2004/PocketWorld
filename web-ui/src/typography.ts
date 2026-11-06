/** Approved font references, applied only through the native presentation preference. */
export const CHINESE_FONT_IDS = ['notosanssc', 'notoserifsc', 'lxgwwenkai', 'zcoolxiaowei', 'zcoolqingkehuangyou', 'mashanzheng'] as const;
export const LATIN_FONT_IDS = ['inter', 'geist', 'dmsans', 'manrope', 'plusjakartasans', 'spacegrotesk', 'sora', 'outfit', 'fraunces', 'newsreader', 'jetbrainsmono', 'ibmplexmono'] as const;
export const CODE_FONT_IDS = ['jetbrainsmono', 'ibmplexmono'] as const;

export interface TypographyPreferences {
  chinese: typeof CHINESE_FONT_IDS[number];
  latin: typeof LATIN_FONT_IDS[number];
  code: typeof CODE_FONT_IDS[number];
}
export interface TypefaceChoice {
  id: string;
  label: string;
  family: string;
  description: string;
  emphasisWeight: 400 | 600;
}

export const DEFAULT_TYPOGRAPHY: Readonly<TypographyPreferences> = Object.freeze({ chinese: 'notosanssc', latin: 'inter', code: 'jetbrainsmono' });
export const CHINESE_FONTS: readonly TypefaceChoice[] = [
  { id: 'notosanssc', label: 'Noto Sans SC · 中文黑体', family: 'Noto Sans SC', description: '中性清晰，适合界面与长文', emphasisWeight: 600 },
  { id: 'notoserifsc', label: 'Noto Serif SC · 中文宋体', family: 'Noto Serif SC', description: '衬线笔画，适合阅读', emphasisWeight: 600 },
  { id: 'lxgwwenkai', label: '霞鹜文楷', family: 'LXGW WenKai', description: '自然书写，适合笔记与阅读', emphasisWeight: 400 },
  { id: 'zcoolxiaowei', label: '站酷小薇体', family: 'ZCOOL XiaoWei', description: '有笔意的短标题', emphasisWeight: 400 },
  { id: 'zcoolqingkehuangyou', label: '站酷庆科黄油体', family: 'ZCOOL QingKe HuangYou', description: '紧凑圆润的展示字形', emphasisWeight: 400 },
  { id: 'mashanzheng', label: '马善政楷书', family: 'Ma Shan Zheng', description: '鲜明的毛笔书写感', emphasisWeight: 400 },
];
export const LATIN_FONTS: readonly TypefaceChoice[] = [
  { id: 'inter', label: 'Inter', family: 'Inter', description: '中性的专业界面', emphasisWeight: 600 },
  { id: 'geist', label: 'Geist', family: 'Geist', description: '安静利落的技术工具', emphasisWeight: 600 },
  { id: 'dmsans', label: 'DM Sans', family: 'DM Sans', description: '柔和的正文与标签', emphasisWeight: 600 },
  { id: 'manrope', label: 'Manrope', family: 'Manrope', description: '舒展的几何字形', emphasisWeight: 600 },
  { id: 'plusjakartasans', label: 'Plus Jakarta Sans', family: 'Plus Jakarta Sans', description: '饱满的产品界面', emphasisWeight: 600 },
  { id: 'spacegrotesk', label: 'Space Grotesk', family: 'Space Grotesk', description: '有辨识度的技术标题', emphasisWeight: 600 },
  { id: 'sora', label: 'Sora', family: 'Sora', description: '醒目清楚的标题与数字', emphasisWeight: 600 },
  { id: 'outfit', label: 'Outfit', family: 'Outfit', description: '圆润年轻的展示字形', emphasisWeight: 600 },
  { id: 'fraunces', label: 'Fraunces', family: 'Fraunces', description: '柔软的编辑型衬线', emphasisWeight: 600 },
  { id: 'newsreader', label: 'Newsreader', family: 'Newsreader', description: '连续屏幕阅读的衬线', emphasisWeight: 600 },
  { id: 'jetbrainsmono', label: 'JetBrains Mono', family: 'JetBrains Mono', description: '清晰的等宽字符', emphasisWeight: 600 },
  { id: 'ibmplexmono', label: 'IBM Plex Mono', family: 'IBM Plex Mono', description: '温和的工程等宽字形', emphasisWeight: 600 },
];
export const CODE_FONTS = LATIN_FONTS.filter(font => (CODE_FONT_IDS as readonly string[]).includes(font.id));

const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const member = <T extends string>(value: unknown, allowed: readonly T[], fallback: T): T => typeof value === 'string' && allowed.includes(value as T) ? value as T : fallback;

/** Missing or corrupt stored values fall back independently; no received value becomes CSS. */
export function normalizeTypography(value: unknown): TypographyPreferences {
  const data = object(value);
  return {
    chinese: member(data.chinese, CHINESE_FONT_IDS, DEFAULT_TYPOGRAPHY.chinese),
    latin: member(data.latin, LATIN_FONT_IDS, DEFAULT_TYPOGRAPHY.latin),
    code: member(data.code, CODE_FONT_IDS, DEFAULT_TYPOGRAPHY.code),
  };
}
export function typographyKey(value: TypographyPreferences): string { return `${value.chinese}:${value.latin}:${value.code}`; }
export function typographyArgs(value: TypographyPreferences): TypographyPreferences {
  const preferences = normalizeTypography(value);
  return { chinese: preferences.chinese, latin: preferences.latin, code: preferences.code };
}
export function typeface(id: string): TypefaceChoice {
  return [...CHINESE_FONTS, ...LATIN_FONTS].find(font => font.id === id) ?? LATIN_FONTS[0];
}
export function typographyStacks(value: unknown): { ui: string; code: string; chinese: string } {
  const preferences = normalizeTypography(value);
  const chinese = `"${typeface(preferences.chinese).family}"`;
  return {
    ui: `"${typeface(preferences.latin).family}", ${chinese}, system-ui, sans-serif`,
    code: `"${typeface(preferences.code).family}", ${chinese}, ui-monospace, monospace`,
    chinese: `${chinese}, system-ui, sans-serif`,
  };
}
/** A bridge acceptance alone does not establish native preference persistence. */
export function typographySaved(value: unknown, submitted: TypographyPreferences): boolean {
  const result = object(value), saved = object(result.typography);
  return result.saved === true && Object.keys(saved).length === 3 &&
    saved.chinese === submitted.chinese && saved.latin === submitted.latin && saved.code === submitted.code;
}
