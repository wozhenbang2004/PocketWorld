import {isPrivateLocalPhone} from './PhonePauseInstruction';

type Row=Record<string,any>;
type Original={taskId:string;runId:string;epoch:number};
const methods={structure:'页面结构',local_text:'本机识别文字',local_geometry:'本机画面几何信息',frame:'画面'} as const;
const labels={
  not_allowed:'收尾阶段没有读屏。',not_entered:'本轮没有启用手机观察。',other_surface:'收尾时已切出手机工作面，没有重读最后一屏。',
  read_failed:'原执行尝试收尾读取，但没读到结果页面。',unconfirmed:'返回记录不足以核对原终屏。',
  changed:'读取时页面仍在变化，没有确认稳定结果。',empty:'原副屏当时没有前台应用。',
} as const;
/** Historical facts only: never use this as current-screen state or permission to refresh a device. */
export function finalScreenLine(row:Row|null|undefined,id:Original):string|null{
  if(!row||!id.taskId||!id.runId||!Number.isSafeInteger(id.epoch)||id.epoch<1||row.format!=='pocketworld.phone-final-page.v1'||row.taskId!==id.taskId||
     row.runId!==id.runId||row.epoch!==id.epoch||row.businessResultVerified!==false||row.containsScreenContent!==false)return null;
  if(row.state==='observed'&&typeof row.method==='string'&&Object.hasOwn(methods,row.method))
    return '当时收到了原屏幕的'+methods[row.method as keyof typeof methods]+'；是否达到目标仍需你核对。';
  return typeof row.state==='string'&&Object.hasOwn(labels,row.state)?labels[row.state as keyof typeof labels]:null;
}
export function TaskPhoneFinalScreen({task}:{task:Row}){
  if(!isPrivateLocalPhone(task)||!['needs_confirmation','completed'].includes(task.status))return null;
  const raw=task.raw,turn=raw?.evidence?.nativeFinalAnswer,id={taskId:task.id,runId:raw.runId,epoch:raw.coordinatorEpoch};
  if(!turn||turn.format!=='pocketworld.runner-final-answer.v1'||turn.taskId!==id.taskId||turn.runId!==id.runId||turn.coordinatorEpoch!==id.epoch||
     turn.inputBoundaryEmpty!==true||!Number.isSafeInteger(turn.messageIndex)||turn.messageIndex<0||!Number.isSafeInteger(turn.historyLength)||turn.historyLength<=turn.messageIndex)return null;
  const text=finalScreenLine(turn.phoneFinalPage,id);
  return text?<p className="pw-field-hint"><strong>收尾时的屏幕：</strong>{text}</p>:null;
}
