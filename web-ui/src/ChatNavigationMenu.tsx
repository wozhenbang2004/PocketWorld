import {useRef,type ReactNode,type Ref} from 'react';
import { IconChevronDown, IconFile, IconFolder, IconHistory, IconMemory, IconMenu, IconReturn, IconSearch } from './icons';
import {Button,IconButton} from './forms-library';
import {Popover,PopoverTrigger} from './shell-library';
import {useNavigationGlass} from './useNavigationGlass';
import './chat-navigation.css';

type ChatNavigationMenuProps={
 title:string;
 navigationTrigger:ReactNode;
 isOpen:boolean;
 onOpenChange:(open:boolean)=>void;
 triggerRef:Ref<HTMLButtonElement>;
 submitting:boolean;
 unread:number;
 onNewConversation?:()=>void;
 onConversations:(trigger:HTMLButtonElement)=>void;
 onHome:()=>void;
 onNavigation:()=>void;
 onOrganize?:()=>void;
 onMemory?:()=>void;
 onDeliveredFiles?:(trigger:HTMLButtonElement)=>void;
 onBrowseHistory?:(trigger:HTMLButtonElement)=>void;
 onResumeHistory?:(trigger:HTMLButtonElement)=>void;
 onNotifications?:()=>void;
 onSettings:()=>void;
};

/** Back and global navigation are different visible targets. The title menu
 * belongs to the current conversation; account settings, notifications and
 * starting another chat belong to the single workspace navigator. */
export function ChatNavigationMenu({title,navigationTrigger,isOpen,onOpenChange,triggerRef,unread,onConversations,onNavigation,onOrganize,onMemory,onDeliveredFiles,onBrowseHistory,onResumeHistory}:ChatNavigationMenuProps){
 const actionsGlass=useNavigationGlass<HTMLDivElement>(), titleTrigger=useRef<HTMLButtonElement>(null);
 const choose=(action:()=>void)=>{onOpenChange(false);action()};
 const chooseAtTitle=(action:(trigger:HTMLButtonElement)=>void)=>{
  const trigger=titleTrigger.current;
  if(trigger)choose(()=>action(trigger));
 };
 const local=!!onOrganize||!!onMemory||!!onDeliveredFiles||!!onBrowseHistory||!!onResumeHistory;
 return <nav className="pw-chat-navigation" aria-label="对话导航">
  {navigationTrigger}
  <h1 className="pw-chat-navigation-title" title={title}>{local?
   <PopoverTrigger isOpen={isOpen} onOpenChange={onOpenChange}>
    <Button ref={titleTrigger} variant="ghost" className="pw-chat-object-menu" trailingIcon={IconChevronDown} aria-label={title+'，当前对话操作'}><span dir="auto">{title}</span></Button>
    <Popover aria-label="当前对话操作" placement="bottom" offset={8} className="pw-chat-glass-menu" dialogClassName="pw-chat-glass-dialog">
     <div className="pw-chat-menu-actions">
      {onDeliveredFiles&&<Button variant="ghost" leadingIcon={IconFile} onClick={()=>{const trigger=titleTrigger.current;if(trigger)choose(()=>onDeliveredFiles(trigger));}}>近期文件成果</Button>}
      {onResumeHistory&&<Button variant="ghost" leadingIcon={IconReturn} aria-haspopup="dialog" onClick={()=>chooseAtTitle(onResumeHistory)}>继续上次浏览</Button>}
      {onBrowseHistory&&<Button variant="ghost" leadingIcon={IconHistory} aria-haspopup="dialog" onClick={()=>chooseAtTitle(onBrowseHistory)}>浏览对话记录</Button>}
      {onOrganize&&<Button variant="ghost" leadingIcon={IconFolder} onClick={()=>choose(onOrganize)}>整理当前对话</Button>}
      {onMemory&&<Button variant="ghost" leadingIcon={IconMemory} onClick={()=>choose(onMemory)}>本对话的个人记忆</Button>}
     </div>
    </Popover>
   </PopoverTrigger>:<span dir="auto">{title}</span>}</h1>
  <div ref={actionsGlass} className="pw-chat-navigation-actions pw-navigation-glass" role="group" aria-label="查找与导航">
   <IconButton icon={IconSearch} className="pw-chat-glass-action" aria-label="搜索工作" onClick={event=>{const trigger=event.currentTarget;choose(()=>onConversations(trigger))}}/>
   <IconButton ref={triggerRef} icon={IconMenu} className="pw-chat-glass-action" aria-label={unread>0?`打开全局导航，${unread} 条外部消息未读`:'打开全局导航'} title="打开全局导航" aria-haspopup="dialog" data-unread={unread>0||undefined} onClick={()=>choose(onNavigation)}/>
  </div>
 </nav>;
}
