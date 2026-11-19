import {useEffect,useRef,useState} from 'react';
import {Button} from './forms-library';
type Data=Record<string,any>;
export function TeamOriginalFileReceipt({id,run,epoch,directory,disabled,recover}:{id:string;run:string;epoch:number;directory?:Data;disabled:boolean;recover:(data:Data)=>Promise<unknown>}){
  const [note,setNote]=useState(''),[busy,setBusy]=useState(''),[settled,setSettled]=useState<string[]>([]);const mounted=useRef(true),inFlight=useRef(false);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
  const rows:Data[]=directory?.taskId===id&&directory?.runId===run&&directory?.epoch===epoch&&Array.isArray(directory.rows)?directory.rows:[];
  if(!rows.length&&!note)return null;
  const check=async(row:Data)=>{
    if(inFlight.current||disabled||settled.includes(row.operationId)||!['unknown','prepared'].includes(row.state))return;
    inFlight.current=true;setBusy(row.operationId);setNote('');
    try{
      const returned=await recover({taskId:id,runId:run,epoch,operationId:row.operationId}) as Data;
      if(!mounted.current)return;
      if(!returned||returned.taskId!==id||returned.runId!==run||returned.epoch!==epoch||returned.operationId!==row.operationId||typeof returned.path!=='string'||returned.executionAuthorized!==false)throw new Error('未收到这笔原写回的确定回执，请重新读取本任务。');
      if(returned.notDispatchedConfirmed===true&&returned.receiptRestored===false&&row.state==='prepared'){setSettled(values=>[...values,row.operationId]);setNote(`已核实 ${returned.path} 只登记了准备，没有派发写回；未重试。`);return;}
      if(returned.receiptRestored!==true||!Number.isInteger(returned.version))throw new Error('未能对上这份原文件的写回版本。');
      setSettled(values=>[...values,row.operationId]);setNote(`已找回 ${returned.path} 第 ${returned.version} 版的写回回执。未改动当前文件，也没有继续任务。`);
    }catch(error){if(mounted.current)setNote(error instanceof Error?error.message:'本次未能核对原文件版本，仍保留原记录。')}
    finally{inFlight.current=false;if(mounted.current)setBusy('')}
  };
  return <section className="pw-approval-item" aria-label="核对原文件写回"><h4>核对原文件写回</h4>
    {!!rows.length&&<p className="pw-muted">只找回这笔操作实际留下的文件版本，不重新写入或启动整合。</p>}
    {rows.map(row=><div key={row.operationId}><p>{row.path||'原文件'}</p>{settled.includes(row.operationId)?<p className="pw-muted">已核对原记录</p>:['unknown','prepared'].includes(row.state)&&<Button size="small" variant="secondary" disabled={disabled||!!busy} onClick={()=>void check(row)}>{busy===row.operationId?'正在核对原记录':row.state==='prepared'?'核对是否已派发':'找回这笔写回回执'}</Button>}</div>)}
    {directory?.hasMore===true&&<p className="pw-muted">这里只列出前八笔。处理后重新读取可查看其余记录。</p>}
    {note&&<p role="status">{note}</p>}
  </section>;
}
