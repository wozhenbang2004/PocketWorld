// PW-01: actual GrokBot Web 616c1dd / BSD-3-Clause, stable React adapter.
import {useRef} from 'react';
import {GrokBotView} from './upstream/grokbot/react';
import type {GrokBotState,GrokBotShape} from './upstream/grokbot/types';
import type {AgentVisualState} from './types';
import {statusLabel,useVisible} from './core';
const states:Record<AgentVisualState,GrokBotState>={idle:'idle',thinking:'thinking',listening:'listening',speaking:'dictating',working:'working',waiting:'curious',error:'confused',stopped:'drowsy',complete:'happy'};
const theme={bodyColor:'#dddddd',eyeColor:'#242424',guideColor:'#888888',centroidColor:'#444444',badgeColor:'#555555',particleColor:'#555555'};
export function AgentAvatar({agentId,state='idle',size=32,animate=true,label,className=''}:{agentId:string;state?:AgentVisualState|string;size?:number;animate?:boolean;label?:string;className?:string}) {
 const root=useRef<HTMLSpanElement>(null);const visible=useVisible(root);
 let seed=0;for(const c of agentId)seed=(seed*31+c.charCodeAt(0))>>>0;
 const shapes:GrokBotShape[]=['blob','pebble','squircle','egg'];
 const mapped=states[state as AgentVisualState]||'idle';
 return <span ref={root} className={`pw-agent-avatar ${className}`} data-agent-id={agentId} style={{width:size,height:size}}><GrokBotView state={mapped} shape={agentId==='main'?'blob':shapes[seed%shapes.length]} size={size} autoBlink={animate&&visible} autoExpression={animate&&visible&&(size>=40||state!=='idle')} theme={theme} respectReducedMotion label={label||`${agentId==='main'?'主 Agent':agentId}，${statusLabel(state)}`}/></span>
}
