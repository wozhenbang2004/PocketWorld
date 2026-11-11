import { useCallback, useEffect, useRef, useState } from 'react';
import { DataOperationHeading, DataOperationRow, useDataSettingsJourney } from './DataSettingsJourney';
import { Accordion, AccordionItem, Button } from './forms-library';
import { IconDevices, IconFileSettings, IconMcp, IconPhone, IconPlugin, IconSkill, IconTerminal } from './icons';
import { PermissionPolicySettings, type PermissionLeaveGuard } from './PermissionPolicySettings';
import { ToolCatalogBrowser } from './ToolCatalogBrowser';
import { type CatalogTool, type ToolCatalog } from './tool-catalog';
import type { SetupCapability } from './setup-contract';
import './capabilities-workspace.css';

export type CapabilitiesWorkspaceProps = {
  state: Record<string, any>; onNavigate: (route: string) => void; onPrepare?: (capability?: SetupCapability) => void;
  run?: (action: string, args?: Record<string, any>) => Promise<any>; supported?: (action: string) => boolean;
  registerBack?: (back: (() => boolean) | null) => void;
  registerBeforeLeave?: (guard: PermissionLeaveGuard | null) => void;
};
/** Affect's retained integration rows + list/detail journey over native definitions.
 * Catalog reads never start services, run plugins, connect devices or grant tools. */
export function CapabilitiesWorkspace({ state, onNavigate, onPrepare, run, supported, registerBack, registerBeforeLeave }: CapabilitiesWorkspaceProps) {
  const journey = useDataSettingsJourney();
  const [catalog, setCatalog] = useState<ToolCatalog | null>(null), [loading, setLoading] = useState(false), [error, setError] = useState(''), [selectedTool, setSelectedTool] = useState('');
  const beforeLeave = useRef<PermissionLeaveGuard | null>(null), catalogBack = useRef<(() => boolean) | null>(null), generation = useRef(0);
  const runRef = useRef(run); runRef.current = run;
  const canRead = !!run && supported?.('permissions.catalog') === true;
  const canEdit = !!run && supported?.('permissions.snapshot') === true && supported?.('permissions.save') === true;
  const registerPolicyLeave = useCallback((guard: PermissionLeaveGuard | null) => { beforeLeave.current = guard; }, []);
  const registerCatalogBack = useCallback((back: (() => boolean) | null) => { catalogBack.current = back; }, []);
  const leave = (next: () => void) => { if (!beforeLeave.current?.(next)) next(); };
  const reload = async () => {
    if (!canRead || !runRef.current) return;
    const epoch = ++generation.current; setLoading(true); setError('');
    try { const value: ToolCatalog = await runRef.current('permissions.catalog', {}); if (epoch === generation.current) setCatalog(value); }
    catch (e) { if (epoch === generation.current) setError(`目录暂不可读取：${e instanceof Error ? e.message : String(e)}`); }
    finally { if (epoch === generation.current) setLoading(false); }
  };
  useEffect(() => { void reload(); return () => { ++generation.current; }; }, [canRead, state.conversationId]);
  const back = () => {
    if (journey.view === 'policy') { leave(() => journey.open('list')); return true; }
    if (journey.view === 'list' && catalogBack.current?.()) return true;
    return journey.back();
  };
  useEffect(() => { registerBack?.(back); return () => registerBack?.(null); }, [registerBack, journey.view]);
  useEffect(() => { registerBeforeLeave?.(next => beforeLeave.current?.(next) || false); return () => registerBeforeLeave?.(null); }, [registerBeforeLeave]);
  const openPolicy = (tool = '') => { setSelectedTool(tool); journey.open('policy'); };
  const sourceRoute = (tool: CatalogTool) => tool.source === 'installed_plugin' ? 'plugins' : ({ skills: 'skills', project: 'local', remote: 'devices', devices: 'devices', teams: 'teams', ssh: 'ssh', media: 'media', automation: 'automation' } as Record<string, string>)[tool.category];
  return <div className="pw-capabilities-workspace" ref={journey.root} tabIndex={-1}>
    {journey.view === 'policy' && run ? <>
      <DataOperationHeading title="审批规则" parent="工具目录" description="先选择范围，修改草稿，再明确保存。" onBack={() => leave(() => journey.open('list'))} />
      <PermissionPolicySettings key={`${String(state.conversationId || '')}:${selectedTool}`} conversationId={String(state.conversationId || '')} act={run} initialScope={state.conversationId ? 'conversation' : 'global'} initialTool={selectedTool} registerBeforeLeave={registerPolicyLeave} />
    </> : journey.view === 'sources' ? <>
      <DataOperationHeading title="管理工具来源" parent="工具目录" description="安装与连接决定能力来源；任务仍单独选择范围并审批。" onBack={() => journey.open('list')} />
      {onPrepare && <section className="pw-capabilities-preparation" aria-label="手机能力准备"><DataOperationRow id="setup" title="准备这台手机" detail="按需准备手机操作、开发、语音与后台运行" icon={IconPhone} onOpen={() => onPrepare()} /></section>}
      <section className="pw-capabilities-group" aria-label="方法与任务选择"><header><h2>方法与任务选择</h2></header><div className="pw-capabilities-list">
        <DataOperationRow id="skills" title="技能与方法" detail="安装和阅读做事步骤，脚本执行单独审批" icon={IconSkill} onOpen={onNavigate} />
        <DataOperationRow id="workProfiles" title="工作配置" detail="选择模型、技能与工具，不授予执行权限" icon={IconFileSettings} onOpen={onNavigate} />
      </div></section>
      <section className="pw-capabilities-group" aria-label="扩展与服务"><header><h2>扩展与服务</h2></header><div className="pw-capabilities-list">
        <DataOperationRow id="plugins" title="插件" detail="查找、安装、查看定义与逐项工具授权" icon={IconPlugin} onOpen={onNavigate} />
        <DataOperationRow id="mcp" title="工具服务 · MCP" detail="管理连接、认证和发现的工具，状态以原服务核对为准" icon={IconMcp} onOpen={onNavigate} />
      </div></section>
      <section className="pw-capabilities-group" aria-label="执行位置"><header><h2>执行位置</h2></header><div className="pw-capabilities-list">
        <DataOperationRow id="local" title="这台手机的开发环境" detail="准备运行环境、项目与依赖，构建仍需原范围" icon={IconTerminal} onOpen={onNavigate} />
        <DataOperationRow id="devices" title="其他设备" detail="核对连接与设备能力，每次使用原授权范围" icon={IconDevices} onOpen={onNavigate} />
      </div></section>
    </> : null}
    <div className="pw-capabilities-catalog-view" hidden={journey.view !== 'list'}>
      <div className="pw-capabilities-introduction"><p className="pw-capabilities-intro">找到能做这件事的工具。</p><p>先看用途和来源，再决定如何审批。这里不会替任务启用能力。</p></div>
      <div className="pw-capabilities-controls"><Button variant="secondary" onClick={() => journey.open('sources')}>管理来源</Button>{canEdit && <Button variant="ghost" onClick={() => openPolicy()}>审批规则</Button>}</div>
      {canRead ? <ToolCatalogBrowser tools={catalog?.tools || []} loading={loading} error={error} onReload={() => void reload()} registerBack={registerCatalogBack} detail={tool => <div className="pw-capabilities-tool-actions">{canEdit && tool.configurable && <Button onClick={() => openPolicy(tool.name)}>设置这个工具的审批</Button>}{sourceRoute(tool) && <Button variant="secondary" onClick={() => onNavigate(sourceRoute(tool))}>管理相关功能</Button>}{onPrepare && (tool.category === 'phone' || tool.category === 'semantic') && <Button variant="secondary" onClick={() => onPrepare()}>准备这台手机</Button>}<Button variant="ghost" onClick={() => onNavigate('workProfiles')}>管理工作配置</Button></div>} /> : <p className="pw-tool-status">当前版本未提供工具目录读取。仍可管理技能、插件、服务与执行位置。</p>}
      {!!catalog?.notices.length && <Accordion variant="divided" defaultExpandedKeys={[]}><AccordionItem id="catalog-notices" title="目录范围与读取说明">{catalog.notices.map((notice, index) => <p className="pw-tool-boundary" key={index}>{notice}</p>)}</AccordionItem></Accordion>}
    </div>
  </div>;
}
