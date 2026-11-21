import { useEffect, useRef, useState } from 'react';
import { bridge } from './bridge';
import { Button } from './forms-library';
import { ownerPythonRead, pythonReadResult, type OriginalPythonRun, type PythonExit, type PythonRead } from './owner-python-run';

const endText = (exit: PythonExit) => {
  if (exit.timedOut) return '超过 60 秒，运行已结束';
  if (exit.cancelRequested) return '本次运行已停止';
  if (exit.exitKind === 'no_child') return '没有启动 Python 进程';
  if (exit.exitKind === 'signal') return `Python 进程已终止（信号 ${exit.value}）`;
  return exit.value === 0 ? '脚本运行完成' : `脚本退出（退出码 ${exit.value}）`;
};

export function OwnerPythonRun({work, released, notStarted, canRead, live, reopen}: {
  work: OriginalPythonRun; released?: PythonExit; notStarted: boolean; canRead: boolean;
  live: () => boolean; reopen?: () => void;
}) {
  const [read, setRead] = useState<PythonRead>(), [pending, setPending] = useState(false), [error, setError] = useState('');
  const alive = useRef(false), entered = useRef(false), current = useRef(live);
  current.current = live;
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[]);
  const readOutput = async () => {
    if (!canRead || entered.current || !current.current()) return;
    entered.current=true;setPending(true);setError('');
    const {taskId,runId,operationId,epoch}=work;
    try {
      const value = await bridge.command(ownerPythonRead,{taskId,runId,operationId,epoch});
      if (!alive.current || !current.current()) return;
      const reply = pythonReadResult(value,work);
      if (!reply) throw Error('没有核对到本次运行的原输出，未打开其他结果。');
      setRead(reply);
    } catch (failure) {
      if (alive.current && current.current()) setError(failure instanceof Error?failure.message:'暂时读不到本次输出，可以重新读取。');
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
      <p>{preparationFailed?'这部手机':'在这部手机运行'} · 保存版本 <code>{work.sha256.slice(0,8)}</code></p>
      <strong role="status">{preparationFailed?'没有启动 Python':exit?endText(exit):'尚未核对到本次运行结束'}</strong>
      {preparationFailed && <p>未能准备这份脚本的运行副本。原脚本没有被这次操作改写。</p>}
    </div>
    <div className="pw-action-row">
      {canRead && !preparationFailed && <Button variant={output?'secondary':undefined} disabled={pending} onClick={()=>void readOutput()}>
        {pending?'正在读取…':exit?'读取本次输出':'查看运行进度'}
      </Button>}
      {reopen && <Button variant="secondary" disabled={pending} onClick={()=>{if(current.current())reopen()}}>返回原脚本</Button>}
    </div>
    {error && <p role="alert">{error}</p>}
    {!preparationFailed && read?.state==='settled' && !output && <p role="status">已核对结束状态。当前没有可读取的原输出。</p>}
    {output && <div className="pw-work-current-progress">
      <h4>程序输出</h4>
      {output.stdout?<pre className="pw-checkout-review-diff" aria-label="程序输出">{output.stdout}</pre>:<p>没有程序输出。</p>}
      <h4>错误输出</h4>
      {output.stderr?<pre className="pw-checkout-review-diff" aria-label="错误输出">{output.stderr}</pre>:<p>没有错误输出。</p>}
      {output.truncated && <p>只显示已保留的部分输出。</p>}
    </div>}
  </section>;
}
