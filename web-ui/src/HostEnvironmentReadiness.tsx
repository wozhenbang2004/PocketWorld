import { Accordion, AccordionItem, Button } from './forms-library';
import { hostWorkspaceAnchor } from './CloudWorkspaceChoice';

type Data=Record<string,any>;
/** Explain what the original local-to-Companion environment actually returned, not a catalog of install suggestions. */
export function HostEnvironmentReadiness({environment,fresh,disabled,onRefresh,onChoose}:{environment?:Data;fresh:boolean;disabled:boolean;onRefresh:()=>void;onChoose:()=>void}){
  const env=environment;
  if(!fresh||!env||env.kind!=='local'||!Array.isArray(env.workers)||!hostWorkspaceAnchor(env))return null;
  const workers=(env.workers as Data[]).filter(w=>w&&typeof w.id==='string'&&w.id!=='command');
  if(workers.some(w=>w.available===true))return null;
  const unknown=workers.some(w=>w.available!==false);
  const status=!workers.length?'这个目录还不能接管家目标':unknown?'执行器已登记，尚未核实能否接单':'这台主机没有观察到已登记的 Agent 程序';
  const note=!workers.length?(env.workers.some((w:Data)=>w?.id==='command')?'这里只有白名单命令，不会把目标文字当成 Shell。':'原目录没有返回 Agent 执行器。'):
    unknown?'请先在这个目录核对原执行器。登记不代表账号、协议或模型可用。':'程序可能不在运行 Companion 的账户环境中。没有自动安装或开启写权限。';
  const ownerCheck=env.ownerWorkspaceCheck==='pocketworld.owner-workspace-check.v1'&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(env.id);
  return <section className="pw-connection-attention" aria-label="这个目录的主机准备状态"><h3>{status}</h3><p>{note}</p>
    {ownerCheck&&<Accordion><AccordionItem id="original-host-check" title="在已配置的 Linux 主机上核对"><p>机主可从已有 Companion 源码目录运行。请换成现有配置路径，不要把连接令牌或配置内容贴到聊天。</p>
      <pre className="pw-source-copy">{'python3 -B companion/pocket_companion.py --check-workspace --config /absolute/path/to/companion.json --environment '+env.id}</pre>
      <p className="pw-field-hint">只看登记的目录、程序文件和连接准备条件，不读取令牌内容，不登录模型，不启动服务或任务。</p></AccordionItem></Accordion>}
    {unknown&&<Button variant="secondary" size="small" disabled={disabled} onClick={onChoose}>核对原执行器</Button>}
    <Button variant="ghost" size="small" disabled={disabled} onClick={onRefresh}>重新读取这台主机的环境</Button>
  </section>;
}
