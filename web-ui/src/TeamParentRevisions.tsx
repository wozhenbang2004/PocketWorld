import {Button} from './forms-library';

type Data=Record<string,any>;

export function TeamParentRevisions({rows,disabled,inspect,returnToWork}:{rows:Data[];disabled:boolean;inspect:(row:Data)=>void;returnToWork?:()=>void}) {
  if(!rows.length)return returnToWork?<><p role="status">原提案不再等待这次确认，没有换成其他分工。</p><Button variant="ghost" size="small" onClick={returnToWork}>返回原对话</Button></>:null;
  return <section className="pw-team-action-list" aria-label="管家留在原分工的修订提案">
    <header className="pw-collaboration-group-heading"><h3>管家建议修改</h3><span>{rows.length} 项</span></header>
    {returnToWork&&<Button variant="ghost" size="small" onClick={returnToWork}>返回原对话</Button>}
    {rows.map(row=><details open={returnToWork?true:undefined} className="pw-team-expanded-copy" key={row.operationId}><summary>{row.title} · {row.author}</summary>
      <p dir="auto" style={{whiteSpace:'pre-wrap'}}>{row.text}</p>
      {row.canReview?<Button variant="secondary" disabled={disabled} onClick={()=>inspect(row)}>核对这次修订范围</Button>
        :<p className="pw-field-hint">这是原提案，没有发给成员执行。这里没有与它对应的已写回版本可供修订确认。</p>}
    </details>)}
  </section>;
}

export function TeamParentRevisionReview({facts}:{facts:Data}) {
  return <><h3>{facts.title}</h3><p>管家提出的原文：</p><p dir="auto" style={{whiteSpace:'pre-wrap'}}>{facts.text}</p>
    <p>确认后，以下原作者会按这条要求修订自己的隔离草稿。原项目 <code>{facts.projectId}</code> 不会被直接改写。新内容仍需独立审阅和新写回许可。</p>
    <ul aria-label="本次实际修订的原分工和文件">{(facts.affected as Data[]).map(item=><li key={item.workItemId}><strong>{item.title}</strong> · {item.author}
      {item.workItemId!==facts.workItemId?'（依赖此项）':''}<ul>{(item.files as Data[]).map(file=><li key={file.path}><code>{file.path}</code> · 基线版本 {file.version}</li>)}</ul></li>)}</ul>
    <p>{facts.retainedItems? `另有 ${facts.retainedItems} 项原成果保留，不重跑。`:'本次涉及上述所有工作项。'}原版本和已有回执保留。作者仍只能在所列路径新增、修改或删除草稿。</p>
    <p className="pw-field-hint">团队总额度：模型 {facts.modelLimit} → {facts.nextModelLimit} 次（已用或预留 {facts.usedModels}），工具 {facts.toolLimit} → {facts.nextToolLimit} 次（已用或预留 {facts.usedTools}）。这是原父任务授权内的本轮额度；不会增加父授权、启动远端命令或批准项目写入。</p></>;
}
