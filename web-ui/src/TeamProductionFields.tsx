import { CheckboxField, SelectField, TextAreaField, TextField } from './forms-library';
import './collaboration-depth.css';
import { resetTeamProductionAcknowledgments, teamProductionDraft, teamProductionPackageKey, teamProductionSnapshotKey,
  teamProductionSnapshots, teamProductionSourceLabel, type TeamProductionDraft } from './team-production';

type Data = Record<string, any>;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];

export function TeamProductionFields({ member, sources, disabled, onChange }: {
  member: Data; sources: Data; disabled: boolean; onChange: (changes: Data) => void;
}) {
  const draft: TeamProductionDraft = member.production || teamProductionDraft(), full = draft.sourceMode === 'project';
  const update = (changes: Partial<TeamProductionDraft>) => onChange({ production: { ...draft, ...changes } });
  const snapshots = teamProductionSnapshots(member, sources), packages = rows(sources.packages);
  const snapshotKey = teamProductionSnapshotKey(draft), packageKey = teamProductionPackageKey({ id: draft.packageId, sha256: draft.packageSha, manifestIdentity: draft.packageManifest });
  const knownSnapshot = snapshots.some(value => teamProductionSnapshotKey(value) === snapshotKey);
  const knownPackage = packages.some(value => teamProductionPackageKey(value) === packageKey);
  return <section className="pw-team-production-fields"><h4>负责的文件与源码</h4>
    <TextAreaField label="负责的项目文件，每行一个" value={member.writeFiles || ''} onChange={writeFiles => onChange({ writeFiles })}
      isDisabled={disabled} placeholder={'src/example.txt\nREADME.md'} hint="每名成员负责 1 至 8 个项目相对路径；不同成员不能负责同一个文件。" />
    {member.runner !== 'model' && <>
      <SelectField label="电脑成员读取的源码范围" value={draft.sourceMode} options={[{ id: 'snapshot', label: '所选文字文件副本' }, { id: 'project', label: '完整源项目与独立 Git 基线' }]}
        isDisabled={disabled} onChange={sourceMode => onChange({ production: { ...resetTeamProductionAcknowledgments(draft), sourceMode } })} />
      {full ? <>
        <p className="pw-field-hint">完整固定源包会传到所选电脑，包含未列为输出的代码；只导回上方负责文件。独立 Git 基线不包含原 Git 历史，工作目录不提供系统级隔离。</p>
        <SelectField label="已冻结的完整源项目包" value={knownPackage ? packageKey : ''}
          options={[{ id: '', label: '选择准确版本的完整源项目包' }, ...packages.map(source => ({ id: teamProductionPackageKey(source), label: teamProductionSourceLabel(source, true) }))]}
          isDisabled={disabled} onChange={key => { const source = packages.find(value => teamProductionPackageKey(value) === key); onChange({ production: { ...resetTeamProductionAcknowledgments(draft), packageId: source?.id || '', packageSha: source?.sha256 || '', packageManifest: source?.manifestIdentity || '' } }); }} />
        {!packages.length && <p role="status">还没有可用的完整源项目包。请先在原项目保存源包，再刷新团队页。</p>}
        {draft.packageId && !knownPackage && <p role="status">原源包版本已不在当前目录，请重新选择后确认。</p>}
        <CheckboxField isSelected={draft.sourceTransferAcknowledged} isDisabled={disabled} onChange={sourceTransferAcknowledged => update({ sourceTransferAcknowledged })}>我同意将这个准确完整源包传到所选电脑，包含负责文件以外的代码。</CheckboxField>
        <CheckboxField isSelected={draft.commandsAcknowledged} isDisabled={disabled} onChange={commandsAcknowledged => update({ commandsAcknowledged })}>我允许远端作者提出命令，每次原请求仍单独审批；同用户命令可能访问该用户可访问的文件与网络。</CheckboxField>
      </> : <>
        <p className="pw-field-hint">只使用同一电脑、同一环境中原任务已冻结的文字文件副本。启动前仍会逐个核对文件内容，不复制整项目。</p>
        <SelectField label="原任务的文件快照" value={knownSnapshot ? snapshotKey : ''}
          options={[{ id: '', label: '选择这台电脑已冻结的文件快照' }, ...snapshots.map(source => ({ id: teamProductionSnapshotKey(source), label: teamProductionSourceLabel(source) }))]}
          isDisabled={disabled} onChange={key => { const source = snapshots.find(value => teamProductionSnapshotKey(value) === key); onChange({ production: { ...resetTeamProductionAcknowledgments(draft), sourceTaskId: source?.sourceTaskId || '', snapshotId: source?.snapshotId || '' } }); }} />
        {!snapshots.length && <p role="status">当前已加载任务没有匹配快照。请先在原电脑任务冻结所需文件，再刷新团队页。</p>}
        {draft.snapshotId && !knownSnapshot && <p role="status">原文件快照不属于当前电脑与环境，请重新选择。</p>}
      </>}
      <section className="pw-team-production-limits"><h4>执行与交付上限</h4>
      <TextField label="该任务最长运行秒数" value={draft.wallSeconds} onChange={wallSeconds => update({ wallSeconds })} isDisabled={disabled} hint="10–900 秒" />
      <TextField label="该任务输出字节上限" value={draft.outputBytes} onChange={outputBytes => update({ outputBytes })} isDisabled={disabled} hint="1024–196608 字节" />
      <TextField label="冻结贡献字节上限" value={draft.contributionBytes} onChange={contributionBytes => update({ contributionBytes })} isDisabled={disabled} hint={`1024–${full ? 16777216 : 524288} 字节`} />
      <CheckboxField isSelected={draft.billingAcknowledged} isDisabled={disabled} onChange={billingAcknowledged => update({ billingAcknowledged })}>我理解执行器内部模型次数、token 和费用不能硬限；启动时仍须批准本次独立远端任务范围。</CheckboxField></section>
    </>}
  </section>;
}

export function TeamProductionReview({ member, sources }: { member: Data; sources: Data }) {
  const source = member.remoteProduction, pin = source?.packageReference, limits = source?.limits;
  const selected = pin ? rows(sources.packages).find(value => teamProductionPackageKey(value) === teamProductionPackageKey(pin))
    : source ? rows(sources.snapshots).find(value => teamProductionSnapshotKey(value) === teamProductionSnapshotKey(source)) : null;
  return <>
    <p>负责文件：{(Array.isArray(member.writeFiles) ? member.writeFiles : []).join('、')}</p>
    {source && <>
      <p>源码：{selected ? teamProductionSourceLabel(selected, !!pin) : pin ? `完整源项目包 ${pin.id}` : `原任务 ${source.sourceTaskId} · 快照 ${source.snapshotId}`}</p>
      {pin && <><p className="pw-field-hint">包版本：{pin.sha256}<br />清单版本：{pin.manifestIdentity}</p><p>完整源码将传到所选电脑；命令按每次请求独立审批。</p></>}
      <p>最长 {limits?.maxWallTimeSeconds} 秒 · 输出最多 {limits?.maxOutputBytes} 字节 · 冻结贡献最多 {limits?.maxContributionBytes} 字节</p>
      <p className="pw-field-hint">内部模型次数、token 和费用不能硬限。保存分工后，开始原项目任务时还需独立审阅实际执行范围。</p>
    </>}
  </>;
}
