import { Button } from './forms-library';
import { IconFile, IconFolder } from './icons';

type Data=Record<string,any>;
export type DirectoryFile={kind:'file';path:string;hit:number;sha256:string;binary:boolean;bytes:number};
type DirectoryChild=DirectoryFile|{kind:'directory';path:string};
export type CheckoutDirectoryPage={inventoryHandle:string;directoryHandle:string;directory:string;sourceDigest:string;start:number;next:number;total:number;rows:DirectoryChild[]};
export type DirectorySelection={path:string;page:CheckoutDirectoryPage|null};
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const handle=(v:unknown)=>typeof v==='string'&&/^[a-f0-9-]{36}$/.test(v);
const path=(v:unknown):v is string=>typeof v==='string'&&v.length<=240&&!/[\\:\u0000-\u001f]/.test(v)&&
  v.split('/').every(part=>part.length>0&&part!=='.'&&part!=='..');
const parent=(v:string)=>v.slice(0,Math.max(0,v.lastIndexOf('/')));

export function checkoutDirectoryPage(value:Data,project:string,checkout:string,files:string,directory:string,start:number,digest:string,inventory?:string):CheckoutDirectoryPage {
  if(value.format!=='pocketworld.checkout-directory.v1'||value.projectId!==project||value.checkoutId!==checkout||value.filesHandle!==files||
    value.readOnly!==true||value.executionAuthorized!==false||!hash(digest)||value.sourceDigest!==digest||!handle(value.inventoryHandle)||
    inventory!==undefined&&value.inventoryHandle!==inventory||!handle(value.directoryHandle)||value.directory!==directory||
    directory!==''&&!path(directory)||value.start!==start||!Number.isSafeInteger(value.total)||value.total<0||value.total>2000||start>value.total||
    !Array.isArray(value.rows)||value.rows.length!==Math.min(40,value.total-start)||
    value.next!==(start+value.rows.length<value.total?start+value.rows.length:-1))throw Error('目录清单不属于原副本的这一页。');
  const paths=new Set<string>();let hit=0;
  for(const item of value.rows){
    if(!path(item?.path)||parent(item.path)!==directory||paths.has(item.path)||
      (item.kind!=='directory'&&(item.kind!=='file'||item.hit!==hit++||!hash(item.sha256)||typeof item.binary!=='boolean'||
        !Number.isSafeInteger(item.bytes)||item.bytes<0||item.bytes>2097152)))throw Error('目录里有未能核对的原文件。');
    paths.add(item.path);
  }
  return value as CheckoutDirectoryPage;
}

export function ProjectCheckoutDirectory({selection,busy,activePath,visit,refresh,open}:{selection:DirectorySelection;busy:boolean;activePath?:string;
  visit:(folder:string,start:number)=>void;refresh:()=>void;open:(item:DirectoryFile)=>void}) {
  const {path:folder,page}=selection;
  return <section className="pw-form-flow" aria-label="副本目录" aria-busy={busy}>
    <header><h4>目录 · <bdi>{folder||'项目根目录'}</bdi></h4></header>
    {page?<>
      {folder&&<div className="pw-form-actions"><Button variant="ghost" size="small" disabled={busy} onClick={()=>visit(parent(folder),0)}>上一级</Button></div>}
      {page.rows.length>0?<div className="pw-checkout-file-list">{page.rows.map(item=>item.kind==='directory'?
        <Button key={item.path} leadingIcon={IconFolder} variant="ghost" disabled={busy} onClick={()=>visit(item.path,0)}><bdi>{item.path.split('/').at(-1)}</bdi></Button>:
        <Button key={item.path} leadingIcon={IconFile} aria-current={item.path===activePath?'true':undefined} variant="ghost"
          disabled={busy||item.binary||item.bytes>262144} onClick={()=>open(item)}><bdi>{item.path.split('/').at(-1)}</bdi>
          {(item.binary||item.bytes>262144)&&<small>{item.binary?'二进制':'超过文字编辑上限'}</small>}</Button>)}</div>:
        <p className="pw-field-hint" role="status">这版副本清单在这里没有可浏览的源码文件。</p>}
      {(page.start>0||page.next!==-1)&&<div className="pw-form-actions">
        {page.start>0&&<Button variant="ghost" size="small" disabled={busy} onClick={()=>visit(folder,page.start-40)}>上一页</Button>}
        {page.next!==-1&&<Button variant="secondary" size="small" disabled={busy} onClick={()=>visit(folder,page.next)}>下一页</Button>}
      </div>}
    </>:<><p className="pw-field-hint" role="status">已保留这个目录的位置。打开其他文件前，请重新核对这里的清单。</p>
      <div className="pw-form-actions"><Button variant="secondary" disabled={busy} onClick={refresh}>刷新这个目录</Button></div></>}
  </section>;
}
