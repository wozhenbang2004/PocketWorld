/** An app-local handoff, not a connection, request, or authority to run. */
export type SavedHostSource = { document:number; conversation:string; message:string; revision:number };
type SavedHostProjection = { id?:unknown; role?:unknown; sourceRevision?:unknown; firstHostRequestLocal?:unknown; taskId?:unknown; submission?:unknown; hostGoalTaskId?:unknown };
export const localSavedHostMessage=<T extends SavedHostProjection>(row:T):row is T&{id:string;sourceRevision:1}=>
  row.role==='user'&&typeof row.id==='string'&&!!row.id&&row.sourceRevision===1&&row.firstHostRequestLocal===true&&
  !row.taskId&&!row.submission&&!row.hostGoalTaskId;
export const exactSavedHostMessage=<T extends SavedHostProjection>(rows:readonly T[],source:SavedHostSource,unsent=true)=>{
  const matches=rows.filter(row=>row.id===source.message), row=matches[0];
  return matches.length===1&&row.role==='user'&&row.sourceRevision===1&&row.sourceRevision===source.revision&&
    row.firstHostRequestLocal===true&&(!unsent||localSavedHostMessage(row))?row:undefined;
};

export type CloudGoal = {
  readonly id: string;
  readonly text: string;
  /** Ephemeral native pointer. Neither a conversation ID nor a Companion field. */
  readonly originHandle?: string;
  readonly carriesTextOnly: boolean;
  readonly savedTarget?: { readonly profileId:string; readonly profileBinding:string; readonly source:SavedHostSource; readonly resumeRevision:number };
};
