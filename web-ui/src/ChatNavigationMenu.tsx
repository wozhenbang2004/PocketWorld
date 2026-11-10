import type {ReactNode,Ref} from 'react';
import {RiChatNewLine,RiHistoryLine,RiLayoutGridLine,RiMoreLine,RiNotification3Line,RiSettings4Line,RiFolderLine} from '@remixicon/react';
import {Button,IconButton} from './forms-library';
import {Popover,PopoverHeader,PopoverTrigger} from './shell-library';
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
 onConversations:()=>void;
 onNavigation:()=>void;
 onOrganize?:()=>void;
 onNotifications?:()=>void;
 onSettings:()=>void;
};

/** The retained BoardUI/React Aria popover owns focus, placement and dismissal.
 * This only relocates navigation; actions still use the existing host paths. */
export function ChatNavigationMenu({title,navigationTrigger,isOpen,onOpenChange,triggerRef,submitting,unread,onNewConversation,onConversations,onNavigation,onOrganize,onNotifications,onSettings}:ChatNavigationMenuProps){
 const choose=(action:()=>void)=>{onOpenChange(false);action()};
 return <nav className="pw-chat-navigation" aria-label="对话操作">
  {navigationTrigger}
  <h1 className="pw-chat-navigation-title" title={title}>{title}</h1>
  <PopoverTrigger isOpen={isOpen} onOpenChange={onOpenChange}>
   <IconButton ref={triggerRef} icon={RiMoreLine} className="pw-chat-glass-trigger" aria-label={unread>0?`对话菜单，${unread} 条未读通知`:'对话菜单'} title="对话菜单" data-unread={unread>0||undefined}/>
   <Popover placement="bottom end" offset={8} className="pw-chat-glass-menu" dialogClassName="pw-chat-glass-dialog">
    <PopoverHeader title={title} className="pw-chat-menu-heading"/>
    <div className="pw-chat-menu-actions">
     {onNewConversation&&<Button variant="ghost" leadingIcon={RiChatNewLine} disabled={submitting} onClick={()=>choose(onNewConversation)}>新对话</Button>}
     <Button variant="ghost" leadingIcon={RiHistoryLine} onClick={()=>choose(onConversations)}>对话列表</Button>
     {onOrganize&&<Button variant="ghost" leadingIcon={RiFolderLine} onClick={()=>choose(onOrganize)}>整理当前对话</Button>}
    </div>
    <div className="pw-chat-menu-actions">
     <Button variant="ghost" leadingIcon={RiLayoutGridLine} onClick={()=>choose(onNavigation)}>工作区与导航</Button>
     {onNotifications&&<Button variant="ghost" leadingIcon={RiNotification3Line} onClick={()=>choose(onNotifications)}><span>通知</span>{unread>0&&<small className="pw-chat-menu-unread">{unread>99?'99+':unread}</small>}</Button>}
     <Button variant="ghost" leadingIcon={RiSettings4Line} onClick={()=>choose(onSettings)}>设置</Button>
    </div>
   </Popover>
  </PopoverTrigger>
 </nav>;
}
