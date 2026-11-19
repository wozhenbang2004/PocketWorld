import { IconFolder, IconMonitor, IconPhone, IconServer, IconTeam } from './icons';
import { homeWorkIdentity } from './butler-work-entry';
import type { WorkCollectionTask } from './work-collection';
import './work-execution-place.css';

function label(value: unknown): string {
  return typeof value === 'string' ? value.trim().slice(0, 90) : '';
}

/** The place comes from the native work binding, not the conversation, the
 * currently selected project, the model's answer, or the screen viewing it. */
export function WorkExecutionPlace({ task, projects }: { task: WorkCollectionTask & {executionMode?:string}; projects: {id:string;name:string}[] }) {
  const binding = homeWorkIdentity(task);
  if (!binding || binding.domain === 'butler') return null;
  const r = task.raw!;
  let title = '', detail = '';
  let Icon = IconMonitor;
  switch (binding.domain) {
    case 'project': {
      const d = r.developmentContext;
      if (d?.format !== 'pocketworld.development-context.v1' || !label(d.runtime)) return null;
      Icon = IconFolder;
      title = '这台手机 · 本机项目';
      const name = projects.find(project => project.id === d.projectId)?.name;
      detail = label(name) ? label(name) + ' · 原私人副本' : '原私人副本';
      break;
    }
    case 'phone':
      Icon = IconPhone;
      title = '这台手机';
      detail = (task.executionMode || r.executionMode) === 'background' ? '原后台独立屏' : '原手机主屏';
      break;
    case 'remote':
      Icon = IconServer;
      title = '远端 Companion';
      detail = label(r.evidence?.hostWorkspaceAnchor) &&
        r.evidence?.operationId === r.operationId && r.evidence?.profileId === r.externalRefs?.profileId
        ? '原主机工作区。手机是交办入口，不在手机上改文件。'
        : '任务绑定原远程连接；运行和成果以原回执为准。';
      break;
    case 'ssh':
      Icon = IconServer;
      title = '原 SSH 主机';
      detail = '主机上的本次操作，不是手机本机开发。';
      break;
    case 'devices':
      Icon = IconMonitor;
      title = '配对设备';
      detail = '原目标与是否接单分别见下方设备回执。';
      break;
    case 'teams':
      Icon = IconTeam;
      title = '按成员分工执行';
      detail = '每个成员的原位置和结果在这次协作内。';
      break;
  }
  return <section className="pw-work-execution-place" aria-label="原执行位置">
    <Icon size={16} aria-hidden />
    <div><span className="pw-work-execution-meta">执行位置</span><strong dir="auto">{title}</strong>
      {detail && <small dir="auto">{detail}</small>}</div>
  </section>;
}
