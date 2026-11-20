import { IconFolder, IconMonitor, IconPhone, IconServer, IconTeam } from './icons';
import { homeWorkIdentity } from './butler-work-entry';
import type { WorkCollectionTask } from './work-collection';
import { phoneResourceHistory } from './phone-resource-history';
import { originalHostLocation } from './original-seen-host-end';
import './work-execution-place.css';

function label(value: unknown): string {
  return typeof value === 'string' ? value.trim().slice(0, 90) : '';
}

/** The place comes from the native work binding, not the conversation, the
 * currently selected project, the model's answer, or the screen viewing it. */
export function WorkExecutionPlace({ task, projects, canReadPhoneResources, remoteProfiles }: {
  task: WorkCollectionTask & {executionMode?:string;hostReceipt?:Record<string,unknown>}; projects: {id:string;name:string}[];
  canReadPhoneResources:boolean;remoteProfiles?:unknown;
}) {
  const binding = homeWorkIdentity(task);
  if (!binding || binding.domain === 'butler') return null;
  const r = task.raw!;
  const phoneRecords = binding.domain === 'phone' && canReadPhoneResources ? phoneResourceHistory(task,'embedded') : undefined;
  let title = '', detail = '', heading='执行位置';
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
    case 'phone': {
      Icon = IconPhone;
      title = '这台手机';
      const mode = task.executionMode && task.executionMode !== r.executionMode ? undefined : r.executionMode;
      detail = mode === 'background' ? '计划使用后台独立屏' : mode === 'main_screen' ? '计划使用手机主屏' : '原记录未核实执行屏幕';
      break;
    }
    case 'remote': {
      Icon = IconServer;
      const original=originalHostLocation(task.id,r,task.hostReceipt,remoteProfiles);
      title=original?(original.name||original.hostname):'远端 Companion';
      if(original)heading='原选主机';
      detail=original?[original.name?original.hostname:'',`工作区 ${original.workspace}`,
        !original.current?'手机未找到匹配的原连接配置':''].filter(Boolean).join(' · '):'任务绑定原远程连接；运行和成果以原回执为准。';
      break;
    }
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
    <div><span className="pw-work-execution-meta">{heading}</span><strong dir="auto">{title}</strong>
      {detail && <small dir="auto">{detail}</small>}
      {phoneRecords?.stored && <small>任务当时记录：{phoneRecords.stored.summary}</small>}
      {phoneRecords?.event && <small>同轮一笔清理：{phoneRecords.event.summary}</small>}</div>
  </section>;
}
