import { useRef, useState } from 'react';
import { Button } from './forms-library';
import { IconChevronRight } from './icons';
import { bridge } from './bridge';
import { ProjectPackageFiles, type PackageFileSource } from './ProjectPackageFiles';
import { TeamFinalPackageSave } from './TeamFinalPackageSave';

export type FinalPackage=PackageFileSource & {teamRunId:string;mode:'source_project';readOnly:true;fileCount:number;archiveBytes:number;sourceDigest:string;manifestIdentity:string};
export function finalPackage(value:unknown,teamId:string):FinalPackage|null {
  const p=value as Partial<FinalPackage>|undefined;
  const sha=(x:unknown)=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x);
  const nativeId=(x:unknown)=>typeof x==='string'&&/^[A-Za-z0-9_-]{1,80}$/.test(x);
  const artifactId=(x:unknown)=>typeof x==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(x);
  return p?.teamRunId===teamId&&p.mode==='source_project'&&p.readOnly===true&&artifactId(p.artifactId)&&[p.taskId,p.operationId,p.projectId,p.checkoutId,p.runId].every(nativeId)
    &&sha(p.archiveSha256)&&sha(p.sourceDigest)&&sha(p.manifestIdentity)&&typeof p.epoch==='number'&&Number.isSafeInteger(p.epoch)&&p.epoch>0
    &&typeof p.archiveBytes==='number'&&Number.isSafeInteger(p.archiveBytes)&&p.archiveBytes>0&&p.archiveBytes<=20*1024*1024
    &&typeof p.fileCount==='number'&&Number.isSafeInteger(p.fileCount)&&p.fileCount>=0&&p.fileCount<=2000&&typeof p.name==='string'?p as FinalPackage:null;
}
export function TeamFinalPackageReading({item,handle,live,disabled,exportState}:{item:FinalPackage;handle:string;live:()=>boolean;disabled:boolean;exportState:unknown}){
  const [open,setOpen]=useState(false),button=useRef<HTMLButtonElement|null>(null);
  const windowKey=()=>!document.hidden&&live()?JSON.stringify([bridge.getDocumentEpoch(),item.teamRunId,handle,item.operationId,item.artifactId,item.archiveSha256]):'';
  const close=()=>{setOpen(false);requestAnimationFrame(()=>button.current?.focus());};
  return <>{open?<ProjectPackageFiles item={item} onClose={close} team={{handle,key:windowKey}}/>:
    <Button ref={button} variant="secondary" trailingIcon={IconChevronRight} disabled={disabled}
      onClick={()=>{if(windowKey())setOpen(true);}}>查看完整项目包 · {item.fileCount} 个文件</Button>}
    <TeamFinalPackageSave item={item} handle={handle} live={live} disabled={disabled} show={open} status={exportState}/>
  </>;
}
