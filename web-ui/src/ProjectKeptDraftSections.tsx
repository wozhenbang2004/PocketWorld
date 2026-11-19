import {useEffect,useRef,useState} from 'react';
import {bridge} from './bridge';
import {Button,CheckboxField} from './forms-library';
import {InlineDiff} from './shell-library';

type Data=Record<string,any>;
type File={keepId:string;sha256:string;baseVersion:number;baseRevisionId:string;baseSha256:string};
type Change={id:number;status:'ready'|'conflict'|'ambiguous'|'present';baseLine:number;oldLines:number;newLines:number;currentLine:number|null;before:string;kept:string};
type Plan={baseVersion:number;baseRevision:string;baseSha:string;headVersion:number;headRevision:string;headSha:string;draftSha:string;changes:Change[]};
type Props={file:File;handle:string;version:number;disabled:boolean;read:(mode:string,args:Data)=>Promise<Data|undefined>;
  baseline:()=>string|null;stage:(value:string,expected:string)=>boolean;};
const hash=(s:string)=>crypto.subtle.digest('SHA-256',new TextEncoder().encode(s)).then(x=>Array.from(new Uint8Array(x),n=>n.toString(16).padStart(2,'0')).join(''));
const currentResume=()=>(bridge.getState().view as {resumeRevision?:number}|undefined)?.resumeRevision;
const sha=(s:unknown)=>typeof s==='string'&&/^[a-f0-9]{64}$/.test(s);
const revision=(s:unknown)=>typeof s==='string'&&/^[a-f0-9-]{36}$/.test(s);
const labels={ready:'可选',present:'当前版本已有',conflict:'同处已改，不自动采用',ambiguous:'位置不唯一，不自动采用'};

export function ProjectKeptDraftSections({file,handle,version,disabled,read,baseline,stage}:Props){
  const [plan,setPlan]=useState<Plan|null>(null),[token,setToken]=useState(''),[chosen,pick]=useState<number[]>([]),
    [opened,viewed]=useState<number[]>([]),[error,tell]=useState(''),[pending,spin]=useState(false),life=useRef(0),flight=useRef(false),last=useRef({baseline,stage,disabled});
  last.current={baseline,stage,disabled};
  useEffect(()=>{++life.current;return()=>{++life.current}},[file.keepId,handle,version]);
  const available=['projects.keptDrafts.compareChanges','projects.keptDrafts.stageChanges'].every(key=>bridge.getState().capabilities.commands.includes(key));
  if(!available||file.baseVersion<1||file.baseVersion>=version)return null;
  async function act(confirm=false){
    const initial=last.current.baseline();if(disabled||flight.current||initial==null||document.hidden||confirm&&(!plan||!chosen.length||!token))return;
    const phase=life.current,doc=bridge.getDocumentEpoch(),resume=currentResume(),selected=chosen.slice(),proof=token,oldPlan=plan;flight.current=true;spin(true);tell('');
    const current=()=>phase===life.current&&!document.hidden&&doc===bridge.getDocumentEpoch()&&resume===currentResume()&&initial===last.current.baseline();
    try{
      if(!confirm){
        setPlan(null);setToken('');pick([]);viewed([]);
        const r=await read('compareChanges',{keepId:file.keepId,sha256:file.sha256,reviewHandle:handle});
        if(!r||!current())return;const p=r.plan as Plan;
        if(r.keepId!==file.keepId||!revision(r.planHandle)||!p||p.baseVersion!==file.baseVersion||p.baseRevision!==file.baseRevisionId||p.baseSha!==file.baseSha256||
          p.draftSha!==file.sha256||p.headVersion!==version||!revision(p.headRevision)||!sha(p.headSha)||!Array.isArray(p.changes)||p.changes.length>32||
          !p.changes.every((v,i)=>v.id===i&&Object.hasOwn(labels,v.status)&&Number.isSafeInteger(v.baseLine)&&v.baseLine>=1&&[v.oldLines,v.newLines].every(n=>Number.isInteger(n)&&n>=0&&n<=760)&&
            (v.status==='ready'?typeof v.currentLine==='number'&&Number.isSafeInteger(v.currentLine)&&v.currentLine>=1:v.currentLine===null)&&typeof v.before==='string'&&typeof v.kept==='string')||
          p.changes.reduce((n,v)=>n+v.before.length+v.kept.length,0)>32768||await hash(initial)!==p.headSha)
          throw Error('未取得当前编辑基线、旧正式版本和这份保留稿的完整分段。');
        if(current()){setPlan(p);setToken(r.planHandle)}
      }else{
        setToken('');
        const r=await read('stageChanges',{keepId:file.keepId,sha256:file.sha256,planHandle:proof,chosen:selected});
        if(!r||!current()||!oldPlan)return;
        if(r.keepId!==file.keepId||r.draftSha!==file.sha256||r.headSha!==oldPlan.headSha||r.headRevision!==oldPlan.headRevision||
          typeof r.text!=='string'||r.text.length>65536||!sha(r.resultSha)||await hash(r.text)!==r.resultSha||await hash(initial)!==r.headSha)
          throw Error('返回的改动不是刚才核对的当前版本，未改变编辑区。');
        if(current()&&!last.current.stage(r.text,initial))throw Error('编辑区在核对期间已有修改，没有覆盖。');
      }
    }catch(e){if(phase===life.current)tell(e instanceof Error?e.message:'本机逐段核对没有完成。')}
    finally{flight.current=false;if(phase===life.current)spin(false)}
  }
  return <section aria-label="将保留稿中的独立改动选入当前草稿">
    <p className="pw-field-hint">正式文件已从 v{file.baseVersion} 更新为 v{version}。可以逐处挑回原稿里的更改，保留当前版本其余内容。</p>
    <Button variant="secondary" size="small" disabled={disabled||pending||baseline()==null} onClick={()=>void act()}>{plan?'重新核对分段':'对照两个正式版本和这份原稿'}</Button>
    {baseline()==null&&<p className="pw-field-hint">当前编辑区另有修改或尚未读全。先保留或处理它，再挑选分段。</p>}
    {plan&&<><p className="pw-field-hint">原稿共有 {plan.changes.length} 处改动。展开想挑回的分段，核对后勾选。没有预选，也不会改写冲突处。</p>
      {plan.changes.map(c=><div key={c.id}><p>{c.status==='ready'?<CheckboxField isSelected={chosen.includes(c.id)} isDisabled={pending||!token||disabled||!opened.includes(c.id)}
        onChange={yes=>pick(v=>yes?[...v.filter(i=>i!==c.id),c.id]:v.filter(i=>i!==c.id))}>选入当前第 {c.currentLine} 行的改动</CheckboxField>:
        <small>原第 {c.baseLine} 行 · {labels[c.status]}</small>}</p>
        <details onToggle={e=>{if(e.currentTarget.open)viewed(ids=>ids.includes(c.id)?ids:[...ids,c.id])}}><summary>查看原第 {c.baseLine} 行起的改动 · {c.oldLines} 行变为 {c.newLines} 行</summary><InlineDiff before={c.before} after={c.kept} versionLabel={`原 v${file.baseVersion} → 手机保留稿`} beforeLabel="原正式文字" afterLabel="保留稿中的更改" readOnly/></details></div>)}
      {!!chosen.length&&<Button variant="secondary" size="small" disabled={!token||disabled||pending||baseline()==null} onClick={()=>void act(true)}>将所选 {chosen.length} 处放入当前草稿</Button>}
    </>}{error&&<p role="alert">{error}</p>}
  </section>;
}
