/* PW-22: controlled adaptation of library Affect Files/Folder v3.
 * Original workspace.tsx is retained. Type art is a type illustration, never file content.
 * Optional controlled view/toolbar preserves this source file list inside one product view popover.
 * All seed folders/documents/people are removed; every entry comes from current project state.
 */
import {useEffect,useLayoutEffect,useMemo,useRef,useState} from 'react';
import {motion} from 'motion/react';
import {useMotionAllowed} from '../library/core';
import {motionTransition} from '../library/motion-tokens';
import {IconButton,SearchField} from '../forms-library';
import './workspace-files.css';
import {ContextMenuTrigger,ContextMenu,ContextMenuItem} from './adapted/boardui/components/base/context-menu/context-menu';
import { IconCopy, IconFile, IconGrid, IconList, IconMore } from '../icons';
export function FileArt({type}:{type:string}){return <div className={`au-sc-file-art au-sc-art-${type.toLowerCase()}`}><div className="au-sc-art-back"/><div className="au-sc-art-paper">{type==='XLS'?<div className="au-sc-art-cells">{Array.from({length:18},(_,i)=><i key={i}/>)}</div>:<>{type==='PDF'&&<div className="au-sc-art-image"/>}{Array.from({length:type==='PDF'?4:6},(_,i)=><i key={i}/>)}</>}<b>{type}</b></div></div>}

export type ProjectFile={path:string;version?:number;[key:string]:unknown};
export function FilesBrowser({files,selectedPath,onOpen,onCopyPath,label='项目文件',identity='files',view:controlledView,onViewChange,showToolbar=true}:{files:ProjectFile[];selectedPath?:string|null;onOpen:(file:ProjectFile)=>void;onCopyPath?:(path:string)=>void;label?:string;identity?:string;view?:'List'|'Grid';onViewChange?:(view:'List'|'Grid')=>void;showToolbar?:boolean}){
 const[localView,setLocalView]=useState<'List'|'Grid'>('List'),[query,setQuery]=useState('');
 const root=useRef<HTMLElement>(null);
 const allowed=useMotionAllowed(),[settleEpoch,setSettleEpoch]=useState(0),[settling,setSettling]=useState(false);
 const previous=useRef<{identity:string;allowed:boolean;settleEpoch:number}|null>(null),playing=useRef(new Set<string>());
 const view=controlledView??localView;const setView=(next:'List'|'Grid')=>{if(controlledView===undefined)setLocalView(next);onViewChange?.(next)};
 const shown=useMemo(()=>files.filter(f=>f.path.toLocaleLowerCase().includes(query.toLocaleLowerCase())),[files,query]);
 const scope=JSON.stringify([identity,query.toLocaleLowerCase()]);
 const baseline=previous.current,boundary=!!baseline&&(baseline.identity!==scope||baseline.allowed!==allowed||baseline.settleEpoch!==settleEpoch);
 const canAnimate=allowed&&!settling&&!!baseline&&!boundary;
 const instant=!allowed||settling||boundary;
 // Versions and selection are not geometry changes. Both views keep the same
 // path-keyed controls, so layout needs only view / list structure / baseline.
 const dependency=JSON.stringify([scope,view,shown.map(file=>file.path),allowed,settleEpoch]);
 const transition={layout:canAnimate?motionTransition.layout:motionTransition.instant,y:allowed?motionTransition.feedback:motionTransition.instant};
 const trackLayout=(id:string)=>({onLayoutAnimationStart:()=>{if(canAnimate)playing.current.add(id)},onLayoutAnimationComplete:()=>playing.current.delete(id)});
 useLayoutEffect(()=>{
  previous.current={identity:scope,allowed,settleEpoch};
  if(!boundary)return;
  playing.current.clear();setSettling(true);
  let frame=requestAnimationFrame(()=>{frame=requestAnimationFrame(()=>setSettling(false))});
  return()=>cancelAnimationFrame(frame);
 },[scope,allowed,settleEpoch]);
 useEffect(()=>{
  const container=root.current;if(!container)return;
  const settle=()=>{if(!playing.current.size)return;playing.current.clear();setSettleEpoch(value=>value+1)};
  const onScroll=(event:Event)=>{const target=event.target;if(target===document||target instanceof Node&&(container.contains(target)||target.contains(container)))settle()};
  document.addEventListener('scroll',onScroll,true);window.addEventListener('resize',settle);
  return()=>{document.removeEventListener('scroll',onScroll,true);window.removeEventListener('resize',settle);playing.current.clear()};
 },[]);
 return <section ref={root} className="au-sc-files pw-files" aria-label={label} data-view={view} data-motion-instant={instant||undefined}>
  {showToolbar&&<div className="pw-files-toolbar"><SearchField value={query} onChange={setQuery} placeholder="搜索当前项目文件" aria-label="搜索当前项目文件"/><div className="pw-view-buttons" role="group" aria-label="文件显示方式"><button type="button" aria-label="列表" aria-pressed={view==='List'} onClick={()=>setView('List')}><IconList size={18}/></button><button type="button" aria-label="网格" aria-pressed={view==='Grid'} onClick={()=>setView('Grid')}><IconGrid size={18}/></button></div></div>}
  <div className={view==='Grid'?'au-sc-file-grid':'pw-file-list'}>
   {shown.map(file=><ContextMenuTrigger key={JSON.stringify([identity,file.path])}>{({isOpen,open})=><>
    <motion.div layout initial={false} layoutDependency={dependency} transition={transition}
     className={`pw-file-object ${view==='Grid'?'au-sc-file-card':''}`} data-file-layout="" data-open={isOpen||undefined}
     aria-current={selectedPath===file.path?'true':undefined} style={{borderRadius:view==='Grid'?16:12,boxShadow:view==='Grid'?'0 2px 10px #00000005':'none'}}
     whileHover={allowed&&view==='Grid'?{y:-3}:{y:0}}
     {...trackLayout(`${file.path}:shell`)}>
     <motion.button layout="position" initial={false} layoutDependency={dependency} transition={transition} data-file-layout=""
      {...trackLayout(`${file.path}:control`)}
      type="button" className="pw-file-open" data-file-path={file.path} aria-current={selectedPath===file.path?'true':undefined}
      aria-label={`打开文件：${file.path}`} onClick={()=>onOpen(file)}>
      <motion.span layout="position" initial={false} layoutDependency={dependency} transition={transition} data-file-layout=""
       {...trackLayout(`${file.path}:art`)}
       className={view==='Grid'?'au-sc-file-preview':'pw-file-miniature'} aria-hidden="true"><FileArt type={(file.path.split('.').pop()||'FILE').toUpperCase().slice(0,5)}/></motion.span>
      <motion.span layout="position" initial={false} layoutDependency={dependency} transition={transition} data-file-layout="" className="pw-file-copy" {...trackLayout(`${file.path}:copy`)}>
       <b>{file.path.split('/').pop()||file.path}</b>
       <small hidden={view==='List'&&!file.path.includes('/')}>{file.path.includes('/')?file.path.slice(0,file.path.lastIndexOf('/')):'项目根目录'}</small>
       {Number.isFinite(file.version)&&<small className="pw-file-card-version" hidden={view==='List'}>版本 {file.version}</small>}
      </motion.span>
     </motion.button>
     <motion.span layout="position" initial={false} layoutDependency={dependency} transition={transition} data-file-layout="" className="pw-file-menu" {...trackLayout(`${file.path}:menu`)}>
      <IconButton icon={IconMore} size="small" aria-label={`${file.path} 的操作`} onClick={event=>open(event.currentTarget)}/>
     </motion.span>
    </motion.div>
    <ContextMenu aria-label={`${file.path} 的操作`} onAction={key=>{if(key==='open')onOpen(file);else if(key==='copy')onCopyPath?.(file.path)}}>
     <ContextMenuItem id="open" icon={IconFile}>打开文件</ContextMenuItem>{onCopyPath&&<ContextMenuItem id="copy" icon={IconCopy}>复制文件路径</ContextMenuItem>}
    </ContextMenu>
   </>}</ContextMenuTrigger>)}
  </div>
  {!shown.length&&<p className="pw-empty-small">{query?'当前项目没有匹配的文件。':'这个项目还没有文件。'}</p>}
 </section>
}
