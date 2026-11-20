import { Accordion, AccordionItem } from './forms-library';
import { profileDependencyLabel, profileRows, type ProfileData as Data } from './profile-contract';
import { toolTitle } from './tool-catalog';
import './work-profile-skill-preparation.css';

export const isProfileSkill = (item: Data) => item.kind === 'skill' || item.kind === 'instruction';
const layerLabel = (layer: unknown) => layer === 'PROJECT' ? '项目范围' : layer === 'USER' ? '个人范围' : layer === 'BUNDLED' ? '随应用提供' : '';
const capabilityName = (need: Data) => {
  if (typeof need.tool === 'string' && typeof need.pluginId === 'string') return `${need.pluginId} · ${need.tool}`;
  const title = toolTitle({name: need.capability, category: '', description: '', source: '', configurable: false});
  return typeof title === 'string' ? title : need.capability;
};

/** One view of the same native preflight. It does not read the current catalogue or confer authority. */
export function WorkProfileSkillPreparation({ preflight }: { preflight?: Data }) {
  const selections = profileRows(preflight?.items).filter(isProfileSkill);
  if (!selections.length) return null;
  const proof = preflight?.skillPreparation, items = profileRows(proof?.items);
  const expected = new Map(selections.map(item => [`${item.kind}:${item.id}`, item]));
  const seen = new Set<string>();
  if (preflight?.sourceChecksOnly !== true || preflight.executionAuthorized !== false ||
      proof?.format !== 'pocketworld.profile-skill-preparation.v1' || proof.configurationOnly !== true || proof.executionAuthorized !== false ||
      proof.profileId !== preflight.profileId || proof.profileRevision !== preflight.profileRevision || proof.resolutionDigest !== preflight.resolutionDigest ||
      items.length !== selections.length || items.some(item => {
        const key = `${item.kind}:${item.id}`, prior = expected.get(key), duplicate = seen.has(key); seen.add(key);
        return duplicate || !prior || !Number.isSafeInteger(item.revision) || item.revision < 1 ||
          prior.included !== item.included || prior.optional !== item.optional || prior.status !== item.status ||
          item.included && prior.binding?.revision !== item.revision ||
          item.dependencies != null && (!Array.isArray(item.dependencies) || profileRows(item.dependencies).length !== item.dependencies.length ||
            item.dependencies.some((need: Data) => typeof need.capability !== 'string' || typeof need.status !== 'string'));
      })) return <p className="pw-profile-caption">尚未核对本次选择的具体技能要求；不把已安装当成已就绪。</p>;
  const included = items.filter(item => item.included === true).length;
  const omitted = items.filter(item => item.included === false && item.optional === true).length;
  const notReady = items.length - included - omitted;
  const summary = [included && `${included} 项已选入`, omitted && `${omitted} 项可选项省略`, notReady && `${notReady} 项未就绪`].filter(Boolean).join(' · ');
  return <Accordion className="pw-profile-skill-preparation" defaultExpandedKeys={items.length <= 3 ? ['profile-skill-preparation'] : []}>
    <AccordionItem id="profile-skill-preparation" title="这次的技能与工具" description={summary}>
      <p>这是所选范围的原预检结果；开始前还会核对原来源。没有注册本轮工具，也没有授予新权限。</p>
      <ul>{items.map(item => <li key={`${item.kind}:${item.id}`}>
        <strong dir="auto">{item.name || (item.kind === 'instruction' ? '原指令资料' : '原技能')} · 修订 {item.revision}</strong>
        <p>{[layerLabel(item.layer), item.included ? '原生预检已选入' : `${item.optional ? '本次省略' : '本次未就绪'}：${profileDependencyLabel(item.status)}`].filter(Boolean).join(' · ')}</p>
        {Array.isArray(item.dependencies) && (item.dependencies.length ? <ul>{profileRows(item.dependencies).map((need, index) =>
          <li key={`${need.capability}:${index}`}>{capabilityName(need)}：{need.status === 'ready' && need.available === true && need.selected === true ? '准备已核对' : profileDependencyLabel(need.status)}</li>
        )}</ul> : <p>这份资料未声明额外工具要求。</p>)}
      </li>)}</ul>
    </AccordionItem>
  </Accordion>;
}
