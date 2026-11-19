import { useEffect, useState } from 'react';
import { Button, SearchField } from './forms-library';

// Typing stays here. A native lookup begins only on submission and never reads documents or a partner.
export function PendingGoalFinder({applied,busy,find,shelf}:{applied:string;busy:boolean;shelf:string;find:(text:string)=>void}){
  const [draft,setDraft]=useState(applied);
  useEffect(()=>setDraft(applied),[applied]);
  return <form className="pw-form-actions" role="search" aria-label={shelf+'的原目标'}
    onSubmit={event=>{event.preventDefault();if(!busy&&draft.trim())find(draft.trim());}}>
    <SearchField aria-label={'在这部手机的「'+shelf+'」原目标中查找'} placeholder="记得目标里的哪几个字？" value={draft}
      isDisabled={busy} onChange={value=>setDraft(Array.from(value).slice(0,64).join(''))}/>
    <Button type="submit" variant="secondary" disabled={busy||!draft.trim()}>查找目标</Button>
    {applied&&<Button type="button" variant="ghost" disabled={busy} onClick={()=>find('')}>清除关键词</Button>}
  </form>;
}
