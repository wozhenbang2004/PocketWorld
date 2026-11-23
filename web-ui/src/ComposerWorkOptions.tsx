import {Button,IconButton,RadioOptions} from './forms-library';
import {Popover,PopoverHeader,PopoverTrigger} from './shell-library';
import {IconBook,IconChevronDown,IconControls,IconServer,IconShieldCheck} from './icons';

type Intent='chat'|'background'|'main_screen';
/** Existing BoardUI controls separate work scope from adding message content. */
export function ComposerWorkOptions({intent,onIntent,open,onOpenChange,phoneAvailable,showDestination=false,disabled,onPermissions,onMemory,memoryPaused,busy,sendMode,onSendMode,onCloudGoal,cloudGoalLabel='带目标去我的主机'}:{intent:Intent;onIntent:(intent:Intent)=>void;open:boolean;onOpenChange:(open:boolean)=>void;phoneAvailable:boolean;showDestination?:boolean;disabled:boolean;onPermissions:()=>void;onMemory?:()=>void;memoryPaused:boolean;busy:boolean;sendMode:string;onSendMode?:(mode:string)=>void;onCloudGoal?:()=>void;cloudGoalLabel?:string}){
 const destination=intent==='chat'?'交给管家':intent==='background'?'手机后台独立屏':'手机主屏';
 const choose=(action:()=>void)=>{onOpenChange(false);action()};
 return <PopoverTrigger isOpen={open} onOpenChange={onOpenChange}>
  {intent==='chat'&&!showDestination?<IconButton icon={IconControls} size="medium" aria-label="工作设置：交给管家" title="工作设置"/>:
   <Button variant="ghost" size="small" className="pw-input-mode-trigger" data-workspace-entry="phone-place" trailingIcon={IconChevronDown}
    aria-label={`执行位置：${destination}，打开工作设置`}>{intent==='chat'?'交给管家':intent==='background'?'独立屏':'手机主屏'}</Button>}
  <Popover aria-label="工作设置" className="pw-composer-work-options" placement="top start">
   <PopoverHeader title="工作设置"/>
   <section><RadioOptions label="执行位置" value={intent} options={[{id:'chat',label:'交给管家',disabled},{id:'background',label:'手机后台独立屏',description:'需要用屏时只用独立屏，准备不足也不会改用主屏。',disabled:!phoneAvailable||disabled},{id:'main_screen',label:'手机主屏操作',description:'需要用屏时会切换应用，占用你手中的屏幕。',disabled:!phoneAvailable||disabled}]} onChange={value=>{if(!disabled)choose(()=>onIntent(value as Intent))}}/><p>用于接下来发送的内容；正在执行的工作保持原位置。</p></section>
   <section className="pw-composer-work-settings">
    {onCloudGoal&&<Button variant="ghost" leadingIcon={IconServer} disabled={disabled} onClick={()=>choose(onCloudGoal)}>{cloudGoalLabel}</Button>}
    {onMemory&&<Button variant="ghost" leadingIcon={IconBook} onClick={()=>choose(onMemory)}>{memoryPaused?'本对话的个人记忆 · 已暂停':'本对话的个人记忆'}</Button>}
    <Button variant="ghost" leadingIcon={IconShieldCheck} onClick={()=>choose(onPermissions)}>执行权限与范围</Button>
   </section>
   {intent==='chat'&&busy&&onSendMode&&<section><RadioOptions label="这次补充何时采用" value={sendMode} options={[{id:'queue',label:'本轮结束后处理'},{id:'steer',label:'下个安全步骤加入'}]} onChange={onSendMode}/><p>已派发的动作和原授权范围保持不变。</p></section>}
  </Popover>
 </PopoverTrigger>;
}
