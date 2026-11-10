import { DataOperationRow } from './DataSettingsJourney';
import { IconDevices, IconFileSettings, IconMcp, IconPhone, IconPlugin, IconSkill, IconTerminal } from './icons';
import type { SetupCapability } from './setup-contract';
import './capabilities-workspace.css';

export type CapabilitiesWorkspaceProps = {
  state: Record<string, any>;
  onNavigate: (route: string) => void;
  onPrepare?: (capability?: SetupCapability) => void;
};

/** Purpose-based entry points over the original workspaces. The approved
 * Affect Integrations v3 groups compact object rows by use; retained BoardUI
 * DataOperationRow owns the controls. Readiness belongs to each real workspace,
 * so this directory neither reads every service nor infers it from cached data. */
export function CapabilitiesWorkspace({ onNavigate, onPrepare }: CapabilitiesWorkspaceProps) {
  return <div className="pw-capabilities-workspace">
    <p className="pw-capabilities-intro">管理助手做事的方法、可调用的工具和执行环境。</p>

    {onPrepare && <section className="pw-capabilities-preparation" aria-label="手机能力准备">
      <DataOperationRow id="setup" title="启用这台手机的能力" detail="按需准备手机操作、开发工具、语音与后台运行" icon={IconPhone} onOpen={() => onPrepare()} />
    </section>}

    <section className="pw-capabilities-group" aria-labelledby="pw-capabilities-methods">
      <header><h2 id="pw-capabilities-methods">方法与工作方式</h2></header>
      <div className="pw-capabilities-list">
        <DataOperationRow id="skills" title="技能与方法" detail="安装、阅读和管理可复用的做事步骤" icon={IconSkill} onOpen={onNavigate} />
        <DataOperationRow id="workProfiles" title="工作配置" detail="保存一套模型、技能与工具选择，供任务使用" icon={IconFileSettings} onOpen={onNavigate} />
      </div>
    </section>

    <section className="pw-capabilities-group" aria-labelledby="pw-capabilities-tools">
      <header><h2 id="pw-capabilities-tools">工具与服务</h2></header>
      <div className="pw-capabilities-list">
        <DataOperationRow id="plugins" title="扩展工具" detail="管理已安装插件，或从插件目录添加新工具" icon={IconPlugin} onOpen={onNavigate} />
        <DataOperationRow id="mcp" title="连接工具服务" detail="添加服务地址，查看提供的工具并决定授权" icon={IconMcp} onOpen={onNavigate} />
      </div>
    </section>

    <section className="pw-capabilities-group" aria-labelledby="pw-capabilities-locations">
      <header><h2 id="pw-capabilities-locations">执行位置</h2></header>
      <div className="pw-capabilities-list">
        <DataOperationRow id="local" title="在这台手机上开发" detail="管理项目、终端、依赖与运行环境" icon={IconTerminal} onOpen={onNavigate} />
        <DataOperationRow id="devices" title="使用其他设备" detail="管理手机配对、电脑与远程连接" icon={IconDevices} onOpen={onNavigate} />
      </div>
    </section>
  </div>;
}
