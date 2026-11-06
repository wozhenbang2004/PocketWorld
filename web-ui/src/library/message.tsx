// PW-06 / PW-08: actual Affect official MIT controls with product-state adapters.
import type {ReactNode} from 'react';
import './reaction-actions.css';
import {MessageActions as SourceMessageActions,MessageText} from './adapted/message-actions-clean';
import {ToolCallCard,ToolCallGroup,type ToolCallStatus} from './adapted/tool-call-card-clean';
export {MessageText,ToolCallGroup};
export type MessageActionsProps={text?:string;onCopy?:(text?:string)=>void|Promise<unknown>;onSpeak?:(playing:boolean)=>void;speaking?:boolean;onRegenerate?:()=>void;onFeedback?:(kind:'up'|'down'|null,reason?:string)=>void;versions?:number|string[];version?:number;onVersionChange?:(index:number)=>void;regenerating?:boolean;className?:string;quiet?:boolean};
export function MessageActions({text,onCopy,onSpeak,speaking=false,onRegenerate,onFeedback,versions,version,onVersionChange,regenerating,className,quiet=false}:MessageActionsProps){
 const actions:Array<'copy'|'regenerate'|'feedback'|'speak'>=[];
 if(text!==undefined||onCopy)actions.push('copy');if(onRegenerate)actions.push('regenerate');if(onFeedback)actions.push('feedback');if(onSpeak)actions.push('speak');
 return <SourceMessageActions text={text} actions={actions} onCopy={onCopy} onSpeak={onSpeak} speaking={speaking} onRegenerate={onRegenerate} onFeedback={onFeedback} versions={versions} version={version} onVersionChange={onVersionChange} regenerating={regenerating} className={`pw-message-actions pw-reaction-actions ${className||''}`} quiet={quiet} label="消息操作"/>
}
export function ToolCall({id,title,name,summary,status,input,args,result,durationMs,startedAt,open,onOpenChange,onRetry,error,children}:{id:string;title?:string;name?:string;summary?:ReactNode;status:string;input?:unknown;args?:unknown;result?:unknown;durationMs?:number;startedAt?:number;open?:boolean;onOpenChange?:(open:boolean)=>void;onRetry?:()=>void;error?:string;children?:ReactNode}){
 const normalized:Record<string,ToolCallStatus>={running:'running',working:'running',success:'success',completed:'success',complete:'success',error:'error',failed:'error',returned:'returned',settled:'returned',unknown:'unknown',waiting:'waiting',needs_approval:'needs_approval',waiting_approval:'needs_approval',stopped:'stopped',cancelled:'stopped'};
 const duration=typeof durationMs==='number'&&Number.isFinite(durationMs)&&durationMs>=0?durationMs:undefined;
 const recordedStart=typeof startedAt==='number'&&Number.isFinite(startedAt)&&startedAt>0?startedAt:undefined;
 return <div className="pw-tool-call" data-call-id={id}><ToolCallCard name={name||title||'工具调用'} summary={summary} status={normalized[status]||'unknown'} args={input!==undefined?input:args} result={result} duration={duration} startedAt={recordedStart} open={open} onOpenChange={onOpenChange} onRetry={onRetry} error={error}/>{children}</div>
}
