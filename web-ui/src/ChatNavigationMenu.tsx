import type {ReactNode,Ref} from 'react';
import { IconFolder, IconGrid, IconMore, IconNewChat, IconNotification, IconSearch, IconSettings, IconSparkles } from './icons';
import {Button,IconButton} from './forms-library';
import {Popover,PopoverTrigger} from './shell-library';
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
 onNotifications?:()=>void;
 onSettings:()=>void;
};

/** The retained BoardUI/React Aria popover owns focus, placement and dismissal.
 * This only relocates navigation; actions still use the existing host paths. */
export function ChatNavigationMenu({title,navigationTrigger,isOpen,onOpenChange,triggerRef,submitting,unread,onNewConversation,onConversations,onHome,onNavigation,onOrganize,onMemory,onNotifications,onSettings}:ChatNavigationMenuProps){
 const choose=(action:()=>void)=>{onOpenChange(false);action()};
 return <nav className="pw-chat-navigation" aria-label="对话操作">
  {navigationTrigger}
  <h1 className="pw-chat-navigation-title" title={title}>{title}</h1>
  <div className="pw-chat-navigation-actions pw-navigation-glass" role="group" aria-label="查找与对话菜单">
   <IconButton icon={IconSearch} className="pw-chat-glass-action" aria-label="搜索工作" onClick={event=>{const trigger=event.currentTarget;choose(()=>onConversations(trigger))}}/>
   <PopoverTrigger isOpen={isOpen} onOpenChange={onOpenChange}>
   <IconButton ref={triggerRef} icon={IconMore} className="pw-chat-glass-action" aria-label={unread>0?`对话菜单，${unread} 条未读通知`:'对话菜单'} title="对话菜单" data-unread={unread>0||undefined}/>
   <Popover aria-label="对话菜单" placement="bottom end" offset={8} className="pw-chat-glass-menu" dialogClassName="pw-chat-glass-dialog">
    <div className="pw-chat-menu-actions">
     <Button variant="ghost" leadingIcon={IconSparkles} onClick={()=>choose(onHome)}>回到管家</Button>
     {onNewConversation&&<Button variant="ghost" leadingIcon={IconNewChat} disabled={submitting} onClick={()=>choose(onNewConversation)}>新对话</Button>}
     {onOrganize&&<Button variant="ghost" leadingIcon={IconFolder} onClick={()=>choose(onOrganize)}>整理当前对话</Button>}
     {onMemory&&<Button variant="ghost" leadingIcon={IconSettings} onClick={()=>choose(onMemory)}>本对话的个人记忆</Button>}
    </div>
    <div className="pw-chat-menu-actions">
     <Button variant="ghost" leadingIcon={IconGrid} onClick={()=>choose(onNavigation)}>工作区与导航</Button>
     {onNotifications&&<Button variant="ghost" leadingIcon={IconNotification} onClick={()=>choose(onNotifications)}><span>通知</span>{unread>0&&<small className="pw-chat-menu-unread">{unread>99?'99+':unread}</small>}</Button>}
     <Button variant="ghost" leadingIcon={IconSettings} onClick={()=>choose(onSettings)}>设置</Button>
    </div>
   </Popover>
   </PopoverTrigger>
  </div>
 </nav>;
}
