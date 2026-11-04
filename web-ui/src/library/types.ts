import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type AgentVisualState = 'idle'|'thinking'|'listening'|'speaking'|'working'|'waiting'|'error'|'stopped'|'complete';
export type ExecutionState = 'pending'|'running'|'complete'|'success'|'returned'|'unknown'|'waiting'|'needs_approval'|'error'|'stopped';
export type SourceReference = {id:string; title?:string; name?:string; excerpt?:string; version?:string|number; url?:string; [key:string]:unknown};
export type Attachment = {id:string; name:string; size?:number; status?:string; [key:string]:unknown};
export type ModelOption = {id:string; name:string; description?:string; disabled?:boolean; reason?:string; [key:string]:unknown};
export type Step = {id:string; title?:string; label?:string; status?:ExecutionState|string; state?:ExecutionState|string; statusLabel?:string; durationMs?:number; children?:Step[]; tasks?:Step[]};
export type PressButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {label?:string; icon?:ReactNode; compact?:boolean};
export type ChatComposerProps = {
 value:string; onChange:(text:string)=>void; onSend:()=>void; onStop?:()=>void;
 busy?:boolean; submitting?:boolean; disabled?:boolean; placeholder?:string;
 attachments?:Attachment[]; onRemoveAttachment?:(id:string)=>void; onAttach?:()=>void;
 onVoice?:()=>void; listening?:boolean; voiceStatus?:string; onDiscardVoice?:()=>void;
 modelName?:string; onSelectModel?:()=>void; toolbar?:ReactNode; children?:ReactNode;
 status?:ReactNode; label?:string; composerKey?:string; className?:string;
};
export type DataPanelProps = {data?:any; title?:string; onAction?:(action:string,item?:any)=>void; className?:string};
