type Data = Record<string, any>;
export const emptyTeamEvidence = () => ({ records: [] as string[], sources: [] as Data[], skills: [] as Data[], resources: [] as Data[], webOrigins: '', projectFiles: [] as string[] });
export type TeamEvidenceDraft = ReturnType<typeof emptyTeamEvidence>;
const handles = (values: string[], max: number) => {
  if (!Array.isArray(values) || values.length > max || new Set(values).size !== values.length || values.some(value => typeof value !== 'string' || !value)) throw new Error(`请从原目录选择至多 ${max} 项不同资料。`);
  return [...values];
};
const integer = (value: unknown, min: number, max: number) => {
  if (!/^\d+$/.test(String(value))) throw new Error('来源范围必须填写准确整数。');
  const number = Number(value); if (!Number.isSafeInteger(number) || number < min || number > max) throw new Error(`来源范围须在 ${min}–${max} 之间。`); return number;
};
export function teamEvidenceOrigins(text: string) {
  if (text.length > 8192) throw new Error('站点列表过长。');
  const sites = text.split('\n').map(value => value.trim().replace(/\/$/, '')).filter(Boolean);
  if (sites.length > 4 || new Set(sites).size !== sites.length) throw new Error('最多选择四个不重复的公共 HTTPS 站点。');
  for (const site of sites) {
    let url: URL; try { url = new URL(site); } catch { throw new Error('请填写完整公共 HTTPS 站点。'); }
    if (url.protocol !== 'https:' || site !== url.origin || url.username || url.password || url.search || url.hash ||
      url.hostname === 'localhost' || url.hostname.endsWith('.localhost') || url.hostname.includes(':') || /^[\d.]+$/.test(url.hostname)) throw new Error('只填写明确的公共 HTTPS 站点，不含页面路径、凭据或本机地址。');
  }
  return sites;
}
export function teamEvidenceSelection(draft: TeamEvidenceDraft) {
  const spans = (values: Data[], max: number) => {
    handles(values.map(value => value.choiceHandle), max);
    return values.map(value => ({ choiceHandle: value.choiceHandle, offset: integer(value.offset, 0, 2147483647), chars: integer(value.chars, 1, 24000) }));
  };
  handles(draft.skills.map(value => value.choiceHandle), 16);
  const skills = draft.skills.map(value => {
    const paths = String(value.paths).split('\n').map(path => path.trim()).filter(Boolean);
    if (!paths.length || paths.length > 16 || new Set(paths).size !== paths.length || paths.some(path => path.length > 500 || path.startsWith('/') || /[\\\u0000]/.test(path) || path.split('/').some(part => !part || part === '.' || part === '..'))) throw new Error('请选择 1–16 个技能内的准确相对路径。');
    return { choiceHandle: value.choiceHandle, paths };
  });
  return { records: handles(draft.records, 64), sources: spans(draft.sources, 64), skills, resources: spans(draft.resources, 32), webOrigins: teamEvidenceOrigins(draft.webOrigins), projectFiles: handles(draft.projectFiles, 32) };
}

/** The current local consent prevents old React callbacks from confirming a later native review. */
export class TeamEvidenceConsent {
  private handle = ''; private revision = 0; private accepted = false; private attempted = false;
  show(handle: string) { this.handle = handle; this.revision++; this.accepted = false; this.attempted = false; }
  change(accepted: boolean) { this.revision++; this.accepted = accepted; }
  clear() { this.show(''); }
  capture() { return { handle: this.handle, revision: this.revision }; }
  current(value: { handle: string; revision: number }) { return !!value.handle && value.handle === this.handle && value.revision === this.revision; }
  confirm(value: { handle: string; revision: number }) { if (!this.current(value) || !this.accepted || this.attempted) return false; this.attempted = true; return true; }
}
