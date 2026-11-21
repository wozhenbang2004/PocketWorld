import {useEffect,useRef,useSyncExternalStore,type ReactNode} from 'react';
import {bridge} from './bridge';
import {Button,IconButton} from './forms-library';
import {Drawer} from './shell-library';
import {IconClose} from './icons';
import {registerOverlayBack} from './overlay-back';
import {draftQuoteView,sameQuoteOwner,storedDraftQuoteCurrent,type DraftQuotePin,type QuoteDraftOwner,type SelectedDraftQuote,type StoredDraftQuoteRead} from './composer-quote-review';

export type ComposerQuoteSelection={
  owner:QuoteDraftOwner; pin:DraftQuotePin; documentEpoch:number; sourceRevision:number;
  receipt:string; confirmedAtOpen:boolean; trigger:HTMLButtonElement;
  caret?:{input:HTMLTextAreaElement; length:number; revision:number; start:number; end:number; direction:'forward'|'backward'|'none'; top:number};
};
export type StoredQuoteReview={selection:ComposerQuoteSelection;request:StoredDraftQuoteRead;state:'reading'|'unavailable'|'verified';quote?:SelectedDraftQuote};
const notes={
  not_loaded:'当前对话还没读到这条原消息。这里只核对已经显示的记录，不会自动翻阅历史。',
  unverified:'目前不能核对这一版原话。引用未改变，也没有用其他消息替代。',
  changed:'原草稿、引用或来源状态已变化，未显示此前的原话。',
};

/** Keep the editor mounted. Close normally to return focus to the invoking row,
 * or explicitly resume the same editor and its still-existing selection. */
export function ComposerQuoteReview({selection,stored,retireStored,onClose}:{selection:ComposerQuoteSelection|null;stored:StoredQuoteReview|null;
  retireStored:(selection:ComposerQuoteSelection)=>void;onClose:()=>void}) {
  const native=useSyncExternalStore(bridge.subscribe,bridge.getState,bridge.getState);
  const landing=useRef<{selection:ComposerQuoteSelection;edit:boolean}|null>(null);
  const visible=!!selection;
  useEffect(()=>selection?registerOverlayBack(()=>{
    landing.current={selection,edit:false};onClose();
  }):undefined,[selection,onClose]);
  useEffect(()=>{
    if(!visible)return;
    const hide=()=>{if(document.hidden)onClose()};
    document.addEventListener('visibilitychange',hide);window.addEventListener('pagehide',onClose);
    return()=>{document.removeEventListener('visibilitychange',hide);window.removeEventListener('pagehide',onClose)};
  },[visible,onClose]);
  const sameWindow=selection&&!document.hidden&&bridge.getDocumentEpoch()===selection.documentEpoch&&native.state.traceSourceRevision===selection.sourceRevision;
  const current=sameWindow?draftQuoteView(native,selection.owner,selection.pin,selection.receipt):{kind:'changed' as const};
  const showing=stored&&stored.selection===selection?stored:null;
  const exactStored=!!showing&&!!sameWindow&&storedDraftQuoteCurrent(native,showing.request);
  useEffect(()=>{
    if(showing&&!exactStored)retireStored(showing.selection);
  },[showing,exactStored,retireStored]);
  const quote=current.kind==='selected'&&selection?.confirmedAtOpen?current:
    exactStored&&showing?.state==='verified'?showing.quote:undefined;
  let content: ReactNode;
  if(quote&&selection)content=<>
    <p className="pw-muted">{quote.author} · 原消息第 {selection.pin.revision} 版 · 第 {selection.pin.offset+1} 至 {selection.pin.offset+selection.pin.chars} 位</p>
    <blockquote className="pw-composer-context-quote" dir="auto">{quote.text}</blockquote>
  </>;
  else content=<p role="status">{exactStored&&showing?.state==='reading'?'正在核对草稿里已附的这段原话…':
    exactStored&&showing?.state==='unavailable'?'暂时无法核对这段已附原话。你仍可只取消这条草稿引用。':
    notes[current.kind==='selected'?'unverified':current.kind]}</p>;
  const close=(edit=false)=>{
    if(selection)landing.current={selection,edit};
    onClose();
  };
  return <Drawer.Root open={visible} onOpenChange={open=>{if(!open)close()}} shouldScaleBackground={false}>
    <Drawer.Portal><Drawer.Overlay className="pw-overlay"/><Drawer.Content className="pw-drawer" onCloseAutoFocus={event=>{
      if(selection)return;
      const back=landing.current;landing.current=null;
      if(!back)return;
      event.preventDefault();
      const origin=back.selection;
      if(document.hidden||origin.documentEpoch!==bridge.getDocumentEpoch()||!sameQuoteOwner(bridge.getState(),origin.owner))return;
      const caret=back.edit?origin.caret:undefined;
      if(caret&&caret.input.isConnected&&!caret.input.disabled&&!caret.input.closest('[inert],[hidden]')){
        caret.input.focus({preventScroll:true});
        if(bridge.getState().composer?.revision===caret.revision&&caret.input.value.length===caret.length){caret.input.setSelectionRange(caret.start,caret.end,caret.direction);caret.input.scrollTop=caret.top}
      }else if(origin.trigger.isConnected&&!origin.trigger.disabled&&!origin.trigger.closest('[inert],[hidden]'))origin.trigger.focus({preventScroll:true});
    }}>
      <Drawer.Handle aria-label="调整引用阅读位置"/>
      <header className="pw-drawer-header"><div><Drawer.Title>这份草稿关联的原话</Drawer.Title>
        <Drawer.Description>这里只核对选段，不改草稿，不会发送问题。</Drawer.Description></div>
        <IconButton icon={IconClose} aria-label="关闭原文查看" onClick={()=>close()}/>
      </header>
      <div className="pw-drawer-body">{selection&&content}</div>
      <footer className="pw-drawer-actions"><Button variant="secondary" onClick={()=>close(true)}>回到草稿</Button></footer>
    </Drawer.Content></Drawer.Portal>
  </Drawer.Root>;
}
