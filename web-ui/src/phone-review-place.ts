import {bridge} from './bridge';

export type ReviewedPlace = 'background_setup' | 'main_screen_choice';
type Pin = {composer:string;conversation:string;ownerEpoch:number;revision:number};
type Mode = 'background'|'main_screen'|'chat';
export type PhonePlaceReturn = Pin & {documentEpoch:number;phoneEpoch:number;mode:Mode;chatRevision?:number};
const number=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;

/** A private input location only. No goal, review handle or authority survives navigation. */
export function currentPhonePlaceInput():PhonePlaceReturn|null {
  const now=bridge.getState(),draft=now.phoneDraft as Record<string,any>|undefined,c=now.composer;
  const conversation=String(now.state.conversationId||'');
  if(document.hidden||c?.ready!==true||typeof c.key!=='string'||!c.key||!number(c.epoch)||c.conversationId!==conversation||
    now.state.conversationReader||now.state.chatSubmitting||now.state.phoneSubmitting||draft?.channelOrigin!==false||
    !number(draft.epoch)||!number(draft.revision)||(draft.sourceState!=='current'&&draft.sourceState!=='none')||
    draft.firstCheckOnly===true||['pending','unknown'].includes(draft.startReceipt?.status))return null;
  const mode=draft.phone===false?'chat':draft.phone===true&&(draft.mode==='background'||draft.mode==='main_screen')?draft.mode:null;
  if(!mode||mode==='chat'&&!number(c.revision))return null;
  return {composer:c.key,conversation,ownerEpoch:c.epoch,revision:draft.revision,documentEpoch:bridge.getDocumentEpoch(),
    phoneEpoch:draft.epoch,mode,...(mode==='chat'?{chatRevision:c.revision}:{})};
}

export function samePhonePlaceDraft(pin:PhonePlaceReturn){
  const now=currentPhonePlaceInput();
  return !!now&&now.composer===pin.composer&&now.conversation===pin.conversation&&now.ownerEpoch===pin.ownerEpoch&&
    now.revision===pin.revision&&now.phoneEpoch===pin.phoneEpoch&&now.mode===pin.mode&&
    now.documentEpoch===pin.documentEpoch&&now.chatRevision===pin.chatRevision;
}

export function phonePlaceReturn(review:{originalDraft?:Pin;documentEpoch?:number;goal:string;mode:string}):PhonePlaceReturn|null{
  const original=review.originalDraft,here=currentPhonePlaceInput(),draft=bridge.getState().phoneDraft as Record<string,unknown>|undefined;
  return original&&here&&review.mode==='background'&&here.mode===review.mode&&here.documentEpoch===review.documentEpoch&&
    original.composer===here.composer&&original.conversation===here.conversation&&original.ownerEpoch===here.ownerEpoch&&
    original.revision===here.revision&&typeof draft?.text==='string'&&review.goal.trim()&&draft.text.trim()===review.goal.trim()?here:null;
}
