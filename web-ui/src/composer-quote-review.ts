import type {PocketSnapshot} from './bridge';

type Data = Record<string, unknown>;
const object = (value: unknown): Data | undefined => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : undefined;
const rows = (value: unknown): Data[] => Array.isArray(value) ? value.map(object).filter((row):row is Data => !!row) : [];
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0 && value.length <= 2048;

export type DraftQuotePin = {
  id:string; conversation:string; revision:number; generation:string; digest:string; offset:number; chars:number;
};
export function draftQuotePin(raw:unknown):DraftQuotePin|null {
  const ref=object(raw),source=object(ref?.sourceRef);
  if(!ref||source?.kind!=='message'||source.ownerId!=='owner'||source.spaceId!=='private'||ref.unit!=='UTF-16'||
    !identifier(source.sourceId)||!identifier(ref.sourceConversationId)||!positive(source.revision)||
    !identifier(ref.generation)||ref.generation.length>80||typeof ref.bodyFingerprint!=='string'||!/^sha256:[a-f0-9]{64}$/.test(ref.bodyFingerprint)||
    typeof ref.offset!=='number'||!Number.isSafeInteger(ref.offset)||ref.offset<0||!positive(ref.chars)||ref.chars>4000||ref.offset+ref.chars>2147483647)return null;
  return {id:source.sourceId,conversation:ref.sourceConversationId,revision:source.revision,generation:ref.generation,
    digest:ref.bodyFingerprint,offset:ref.offset,chars:ref.chars};
}
export const draftQuoteKey=(pin:DraftQuotePin)=>JSON.stringify([pin.id,pin.conversation,pin.revision,pin.generation,pin.digest,pin.offset,pin.chars]);

export type QuoteDraftOwner = {key:string; epoch:number; conversation:string};
export function sameQuoteOwner(snapshot:PocketSnapshot,owner:QuoteDraftOwner) {
  const {composer,state}=snapshot,view=object(snapshot.view);
  return !!view&&!view.section&&!view.taskId&&!state.conversationReader&&!state.attachmentWorkspace?.open&&!state.chatSubmitting&&
    composer?.ready===true&&composer.key===owner.key&&composer.epoch===owner.epoch&&
    composer.conversationId===owner.conversation&&state.conversationId===owner.conversation;
}
export function loadedQuoteReceipt(snapshot:PocketSnapshot,conversation:string):string {
  const {state}=snapshot,input=object(object(state.moduleResultInputs)?.['assistant.get']);
  const recent=object(object(state.moduleResults)?.['assistant.get']);
  if(!Array.isArray(state.moduleBusy)||state.moduleBusy.includes('assistant.get')||object(state.moduleErrors)?.['assistant.get']||
    recent?.conversationId!==conversation||input?.conversationId!==conversation||!identifier(input._requestId))return '';
  return input._requestId;
}
type QuoteView = {kind:'selected';text:string;author:string}|{kind:'not_loaded'|'unverified'|'changed'};

/** Inspect only the already published original conversation and its native original locator.
 * The module receipt never supplies body text by itself, and this code performs no read.
 */
export function draftQuoteView(snapshot:PocketSnapshot,owner:QuoteDraftOwner,pin:DraftQuotePin,receipt:string):QuoteView {
  if(!sameQuoteOwner(snapshot,owner)||pin.conversation!==owner.conversation||
    rows(snapshot.composer?.sourceRefs).filter(row=>{const attached=draftQuotePin(row);return attached&&draftQuoteKey(attached)===draftQuoteKey(pin)}).length!==1)
    return {kind:'changed'};
  const visible=rows(snapshot.state.messages).filter(row=>row.id===pin.id);
  if(!visible.length)return {kind:'not_loaded'};
  if(visible.length!==1||visible[0].sourceRevision!==pin.revision)return {kind:'unverified'};
  if(!receipt||loadedQuoteReceipt(snapshot,owner.conversation)!==receipt)return {kind:'unverified'};
  const lookup=object(object(snapshot.state.moduleResults)?.['assistant.get']);
  const originals=rows(lookup?.messages).filter(row=>row.id===pin.id);
  if(originals.length!==1)return {kind:'unverified'};
  const row=originals[0],display=visible[0],locator=object(row.ownerOriginalLocator);
  if(display.quoteAvailable!==true||row.importAssignedIdentity===true||row.revision!==pin.revision||
    locator?.kind!=='owner_native_original'||locator.generation!==pin.generation||locator.conversationId!==pin.conversation||
    locator.recordId!==pin.id||locator.recordVersion!==pin.revision||locator.fingerprint!==pin.digest||
    typeof display.content!=='string'||display.content!==row.content||display.role!==row.role||!['user','assistant'].includes(String(row.role)))
    return {kind:'unverified'};
  const end=pin.offset+pin.chars,text=display.content;
  const splits=(at:number)=>at>0&&at<text.length&&/[\uD800-\uDBFF]/.test(text[at-1])&&/[\uDC00-\uDFFF]/.test(text[at]);
  if(end>text.length||splits(pin.offset)||splits(end))return {kind:'unverified'};
  return {kind:'selected',text:text.slice(pin.offset,end),author:row.role==='user'?'你的消息':'助手回复'};
}
