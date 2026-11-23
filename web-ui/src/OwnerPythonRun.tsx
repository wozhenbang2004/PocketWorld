import { useCallback, useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { ownerPythonRead, ownerPythonSaveText, pythonCompleteTextRead, pythonOutputSaveResult, pythonReadResult,
  type OriginalPythonRun, type PythonExit, type PythonOutputFile, type PythonOutputSave, type PythonRead } from './owner-python-run';

const endText = (exit: PythonExit) => {
  if (exit.timedOut) return '超过 60 秒，运行已结束';
  if (exit.cancelRequested) return '本次运行已停止';
  if (exit.exitKind === 'no_child') return '没有启动 Python 进程';
  if (exit.exitKind === 'signal') return `Python 进程已终止（信号 ${exit.value}）`;
  return exit.value === 0 ? '脚本运行完成' : `脚本退出（退出码 ${exit.value}）`;
};

export function OwnerPythonRun({work, released, notStarted, canRead, canSaveText, live, reopen, openOutput}: {
  work: OriginalPythonRun; released?: PythonExit; notStarted: boolean; canRead: boolean;
  canSaveText:boolean; live: () => boolean; reopen?: () => void;
  openOutput?:(file:PythonOutputFile,trigger:HTMLButtonElement)=>void;
}) {
  const [read, setRead] = useState<PythonRead>(), [pending, setPending] = useState(false), [error, setError] = useState('');
  const [textSave,setTextSave]=useState<PythonOutputSave>();
  const alive = useRef(false), entered = useRef(false), submitted = useRef(false), current = useRef(live);
  const openedOutput = useRef(false);
  current.current = live;
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  const readOutput = useCallback(async () => {
    if (!canRead || entered.current || !current.current()) return;
    entered.current=true;setPending(true);setError('');
    const {taskId,runId,operationId,epoch}=work;
    try {
      const value = await bridge.command(ownerPythonRead,{taskId,runId,operationId,epoch});
      if (!alive.current || !current.current()) return;
      const reply = pythonReadResult(value,work);
      if (!reply) throw Error('没有核对到本次运行的原输出，未打开其他结果。');
      setRead(reply);
      const fresh=reply.state==='settled'?reply.textOutputSave:undefined;
      setTextSave(previous=>submitted.current&&(!fresh||fresh.state==='ready')?previous:fresh);
    } catch (failure) {
      if (alive.current && current.current()) setError(failure instanceof Error?failure.message:'暂时读不到本次输出，可以重新读取。');
    } finally {
      entered.current=false;
      if (alive.current) setPending(false);
    }
  },[canRead,work]);
  // Work is already open. Once its native exit confirms the original process ended,
  // show its local result in place, without another tap or any polling/re-execution.
  const completion = released && released.exitKind !== 'no_child' && !notStarted;
  useEffect(()=>{
    if(!completion || !canRead || pending || entered.current || openedOutput.current || !current.current())return;
    openedOutput.current=true;
    if(read?.state!=='settled')void readOutput();
  },[completion,canRead,pending,read,readOutput]);
  const completeText=read?.state==='settled'?read.completeText:undefined;
  const reviewCompleteText = async () => {
    if (!completeText || textSave || !canRead || !canSaveText || entered.current || submitted.current || !current.current()) return;
    entered.current=true;setPending(true);setError('');
    const {taskId,runId,operationId,epoch}=work;
    try {
      const value=await bridge.command(ownerPythonRead,{taskId,runId,operationId,epoch,completeText});
      if (!alive.current || !current.current()) return;
      const reply=pythonCompleteTextRead(value,work,completeText);
      if (!reply) throw Error('没有核对到这次完整输出，没有保存片段。');
      setTextSave(reply);
    } catch (failure) {
      if (alive.current && current.current()) setError(failure instanceof Error?failure.message:'没有核对到这次完整输出。');
    } finally {
      entered.current=false;
      if (alive.current) setPending(false);
    }
  };
  const saveText = async () => {
    const ready=textSave;
    if (ready?.state!=='ready' || !canSaveText || submitted.current || entered.current || !current.current()) return;
    submitted.current=true;entered.current=true;setPending(true);setError('');setTextSave({state:'pending'});
    const {taskId,runId,operationId,epoch}=work;
    try {
      const reply=await bridge.command(ownerPythonSaveText,{taskId,runId,operationId,epoch,outputHandle:ready.handle});
      if (!alive.current || !current.current()) return;
      const saved=pythonOutputSaveResult(reply,work,ready);
      if (!saved) throw Error('保存结果待核对。请核对这次保存，不会重复写入。');
      setTextSave(saved);
    } catch (failure) {
      if (alive.current && current.current()) setError(failure instanceof Error?failure.message:'保存结果待核对。');
    } finally {
      entered.current=false;
      if (alive.current) setPending(false);
    }
  };
  const exit = released ?? (read?.state==='settled'?read.exit:undefined);
  const preparationFailed = notStarted || read?.state==='not_started';
  const output = !preparationFailed && read?.state==='settled'?read.output:undefined;
  return <section className="pw-task-overview pw-task-review" aria-label="本机 Python 运行">
    <div className="pw-work-current-progress">
      <h3><bdi>{work.path}</bdi></h3>
      <p>{preparationFailed?'这部手机':'在这部手机运行'} · {work.programFiles?
        <>{work.packageDirectory?<>所选包 <bdi>{work.packageDirectory}</bdi> · </>:null}
          {work.programFiles.length} 份{work.packageDirectory?'':'同目录 '}.py 文件 · 源码版本 <code>{work.scriptInputDigest.slice(0,8)}</code></>:
        <>保存版本 <code>{work.sha256.slice(0,8)}</code></>}</p>
      <strong role="status">{preparationFailed?'没有启动 Python':exit?endText(exit):'尚未核对到本次运行结束'}</strong>
      {preparationFailed && <p>未能准备这份脚本的运行副本。原脚本没有被这次操作改写。</p>}
    </div>
    <div className="pw-action-row">
      {canRead && !preparationFailed && !pending &&
        (!output || textSave?.state==='pending' || textSave?.state==='unavailable' || !!error) &&
        <Button variant={output?'secondary':undefined} onClick={()=>void readOutput()}>
          {textSave?.state==='pending'?'核对这次保存':exit?'重新核对本次输出':'查看运行进度'}
        </Button>}
      {!preparationFailed && textSave?.state==='saved' && openOutput && <Button variant="secondary" disabled={pending}
        onClick={event=>{if(current.current())openOutput(textSave.file,event.currentTarget)}}>打开已保存的 TXT</Button>}
      {reopen && <Button variant="secondary" disabled={pending} onClick={()=>{if(current.current())reopen()}}>{work.programFiles?'返回程序入口':'返回原脚本'}</Button>}
    </div>
    {pending && <p role="status">{textSave?.state==='pending'?'正在保存或核对这份 TXT…':exit?'正在打开本次原输出…':'正在核对运行进度…'}</p>}
    {error && <p role="alert">{error}</p>}
    {!preparationFailed && textSave?.state==='pending' && <p role="status">这次保存尚待核对，不会再写一份。</p>}
    {!preparationFailed && textSave?.state==='unavailable' && <p role="status">这次原保存暂不可读取。</p>}
    {!preparationFailed && textSave?.state==='saved' && <p role="status">输出已保存在私人文件库。</p>}
    {!preparationFailed && read?.state==='settled' && !output && textSave?.state!=='saved' && <p role="status">已核对结束状态。当前没有可读取的原输出。</p>}
    {output && <div className="pw-work-current-progress">
      <h4>程序输出</h4>
      {output.stdout?<pre className="pw-checkout-review-diff" aria-label="程序输出">{output.stdout}</pre>:<p>没有程序输出。</p>}
      <h4>错误输出</h4>
      {output.stderr?<pre className="pw-checkout-review-diff" aria-label="错误输出">{output.stderr}</pre>:<p>没有错误输出。</p>}
      {output.truncated && <p>{completeText?'这里只预览部分输出。':'只显示已保留的部分输出。'}</p>}
      {completeText && !textSave && canRead && canSaveText && <Button variant="secondary" disabled={pending}
        onClick={()=>void reviewCompleteText()}>核对完整输出</Button>}
      {textSave?.state==='ready' && canSaveText && <Button variant="secondary" disabled={pending}
        onClick={()=>void saveText()}>{completeText?'保存完整输出为 TXT':'保存本次输出为 TXT'}</Button>}
    </div>}
  </section>;
}
