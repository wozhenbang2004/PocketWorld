import type { HostEditorFile } from './ProjectHostFileComparison';
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { Accordion, AccordionItem, Button, CheckboxField, SettingsDialog, TextAreaField, TextField } from './forms-library';
import { InlineDiff } from './shell-library';
import { IconFolder, IconSearch } from './icons';
import {checkoutEditorSelection,ProjectCheckoutTextSearch} from './ProjectCheckoutTextSearch';
import {checkoutPathPage,ProjectCheckoutPathSearch,type CheckoutPathHit,type CheckoutPathPage} from './ProjectCheckoutPathSearch';
import {checkoutDirectoryPage,checkoutFileParent,ProjectCheckoutDirectory,type DirectoryFile,type DirectorySelection} from './ProjectCheckoutDirectory';
import {checkoutGitView, ProjectCheckoutGitChanges, type CheckoutGitView} from './ProjectCheckoutGitChanges';
import { canOpenSavedStaticPreview, ProjectSavedStaticPreview, type SavedStaticEditor } from './ProjectSavedStaticPreview';
import { bridge } from './bridge';
import { ProjectSourceCheckpointScreen, ReturnedSourceCheckpointComparison } from './ProjectSourceCheckpointScreen';
import { useSourceCheckpointFiles, type CheckpointFilesOrigin } from './project-source-checkpoint-files';
import { hasCheckpointContinue, returnCheckpointFiles, type CheckpointFilesArrival } from './project-source-checkpoint-continue-files';
import { originalPythonRun, ownerPythonFiles, pythonPackageDirectory, pythonReview, savedPythonPath, type OriginalPythonRun, type PythonReview } from './owner-python-run';
import { SavedMarkdownBody } from './library/MarkdownBody';
import type { SourceMember } from './project-preview';
import './project-development.css';
import './project-workspace-manga.css';

type Data = Record<string, any>;
type SavedEdit = { id: string; filesHandle: string; path: string; beforeHash: string; savedHash: string; before: string; after: string };
type EditorPosition = { start: number; end: number; scrollTop: number; scrollLeft: number };
type SavedMarkdownFile = { fileHandle: string; path: string; sha256: string; content: string };
type MarkdownVisit = Omit<SavedMarkdownFile,'content'> & { caret: EditorPosition; drawerScroll: number };
type StaticPreviewVisit = { editor:SavedStaticEditor; file:Data; generation:number; caret:EditorPosition; drawerScroll:number };
type StaticRootObservation = Pick<SavedStaticEditor,'projectId'|'checkoutId'|'viewHandle'|'filesHandle'|'documentEpoch'|'entrySha256'> & {
  paths:readonly string[];
};
type FileFind = { fileHandle: string; query: string; result?: { text: string; at: number; line: number; wrapped: boolean } };
type FolderEditor = { fileHandle: string; resumable: boolean };
// A UI bookmark, not a reusable native save handle or a copy of the file.
export type CheckoutSavedPosition = { projectId: string; checkoutId: string; path: string; savedHash: string; caret?: EditorPosition };
export type CheckoutBuildEditorResume = Omit<CheckoutSavedPosition,'caret'> & { sourceDigest: string };
export type CheckoutPythonToolsDeparture = {position:CheckoutSavedPosition;savedHere:boolean;closeEditor:()=>Promise<boolean>};
export type CheckoutSaveForBuild = { position: CheckoutSavedPosition; filesHandle: string; id: string; closeEditor:()=>Promise<boolean> };
export type CheckoutWebEntry = {projectId:string;checkoutId:string;sourceDigest:string;root:string;path:string;sha256:string};
type PythonEditorSource = {path:string;sha256:string;content:string;clickKey:string;selection:{saveId:string}|{readRevision:number}};
type PythonPackagePicker = {source:string;directoryHandle?:string;hits:number[]};
type PythonToolsCloseGap = {position:CheckoutSavedPosition;savedHere:boolean;generation:number;projectRevision:number};
type FileRead = { gitStatusOperation?: string; saved?: SavedEdit; position?: CheckoutSavedPosition; pythonTaskId?: string; expectedPythonSha256?: string; priorSavedFileId?: string; draftOnlyListId?: string; currentOnly?: boolean; entry?: CheckoutWebEntry; adoptedSourceCurrent?:()=>boolean };
type OriginalDirectoryOpening = { current: () => boolean; finish: (reason?: string) => void; chosenSavedFile?:CheckoutSavedPosition };
type Props = { projectId: string; checkoutId: string; viewHandle: string; run: (action: string, args?: Data) => Promise<any>;
  reopenedProjectDirectory?: boolean;
  preferOfflinePagePreviewAfterSave?: boolean; // Presentation only, never file or preview authority.
  hostFile?: HostEditorFile; sourceMember?: SourceMember & { taskId: string; operationId: string };
  resumeSaved?: CheckoutSavedPosition; firstEntry?: CheckoutWebEntry; buildResume?: CheckoutBuildEditorResume; pythonResume?: OriginalPythonRun;
  pythonSetupResume?: CheckoutSavedPosition; originalDirectoryOpening?: OriginalDirectoryOpening;
  checkpointArrival?: CheckpointFilesArrival;
  checkpointHandoff?: Readonly<{
    adopt: (arrival: CheckpointFilesArrival, before: () => void) => boolean;
    closeProject: (before: () => void) => Promise<boolean>;
  }>;
  onClose: (saved?: CheckoutSavedPosition) => void;
  onOpenPythonWork?: (work: OriginalPythonRun) => void;
  onPreparePythonTools?: (source: CheckoutPythonToolsDeparture) => Promise<void>;
  preparePythonToolsLabel?: string;
  onPrepareBuild?: (saved: CheckoutSaveForBuild) => Promise<void>; prepareBuildAfterPath?: string; prepareBuildLabel?: string;
  registerBack: (handler: (() => boolean) | null) => void };
const command = 'projects.development.files.';
const windowKey = () => { const view = bridge.getState().view as Data; return [bridge.getDocumentEpoch(), view.section, view.taskId, view.resumeRevision].join('|'); };
const rows = (value: unknown): Data[] => Array.isArray(value) ? value : [];
const fileHash = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
function savedMarkdownFile(file:Data|null,text:string,project:string,checkout:string,filesHandle:string):SavedMarkdownFile|null {
  const actual = file?.latest, sha256 = actual?.expectedHash;
  if (!file || !filesHandle || file.filesHandle!==filesHandle || file.projectId!==project || file.checkoutId!==checkout ||
      typeof file.fileHandle!=='string' || !file.fileHandle || typeof file.path!=='string' || !/\.(?:md|markdown)$/i.test(file.path) ||
      file.dirty!==false || file.conflict!==false || file.saveUnknown!==false || actual?.deleted!==false ||
      actual.projectId!==project || actual.checkoutId!==checkout || actual.path!==file.path || !fileHash(sha256) || actual.sha256!==sha256 ||
      !Number.isSafeInteger(actual.bytes) || actual.bytes<0 || actual.bytes>65536 || typeof actual.content!=='string' ||
      file.baseHash!==sha256 || file.baseContent!==actual.content || file.text!==actual.content || text!==actual.content) return null;
  return {fileHandle:file.fileHandle,path:file.path,sha256,content:actual.content};
}
const sameMarkdown = (a:MarkdownVisit,b:SavedMarkdownFile|null) => !!b && a.fileHandle===b.fileHandle && a.path===b.path && a.sha256===b.sha256;
export function checkoutWebEntry(source:Data|null,projectId:string,checkoutId:string):CheckoutWebEntry|null {
  const pin=source?.firstImportedWebFile,root=source?.retainedArchiveRoot?.directory??'';
  if(!pin||source?.projectId!==projectId||source.checkoutId!==checkoutId||pin.projectId!==projectId||pin.checkoutId!==checkoutId||
    !fileHash(pin.sourceDigest)||source.sourceDigest!==pin.sourceDigest||!fileHash(pin.sha256)||typeof root!=='string'||
    root==='.'||root==='..'||/[\/\\\u0000-\u001f\u007f]/.test(root)||pin.root!==root||typeof pin.path!=='string'||pin.path.length>240)return null;
  const prefix=root?root+'/':'';
  return pin.path===prefix+'index.html'||pin.path===prefix+'package.json'?
    {projectId,checkoutId,sourceDigest:pin.sourceDigest,root,path:pin.path,sha256:pin.sha256}:null;
}
const textareaText = (value: string) => value.replace(/\r\n?/g, '\n');
function observedSave(submitted: Data, response: Data): SavedEdit | null {
  const proof = response.savedEdit, actual = response.latest;
  if (response.saveStatus !== 'observed' || response.executionAuthorized !== false || response.projectVersionWritten !== false ||
      !proof || typeof proof.id !== 'string' || !/^[a-f0-9-]{36}$/.test(proof.id) ||
      proof.path !== submitted.path || proof.beforeHash !== submitted.baseHash ||
      !(fileHash(proof.beforeHash) || proof.beforeHash === 'absent' && submitted.baseContent === '') ||
      !fileHash(proof.savedHash) || proof.savedHash === proof.beforeHash || actual?.deleted !== false ||
      actual.projectId !== submitted.projectId || actual.checkoutId !== submitted.checkoutId || actual.path !== submitted.path ||
      actual.expectedHash !== proof.savedHash || actual.content !== submitted.text || typeof actual.content !== 'string' ||
      typeof submitted.baseContent !== 'string') return null;
  return { id: proof.id, filesHandle: response.filesHandle, path: proof.path, beforeHash: proof.beforeHash,
    savedHash: proof.savedHash, before: submitted.baseContent, after: actual.content };
}
// Last unacknowledged interface text only. Native ProjectFileDraftStore remains
// the durable cache. A different restored draft always requires a visible choice.
const interfaceDrafts = new Map<string, string>();
const failureText = (error: unknown) => error instanceof Error ? error.message : String(error);

type CheckpointMoving = 'returning' | 'unknown' | 'leaving' | 'close_unknown';
export function ProjectCheckoutFiles({ projectId, checkoutId, viewHandle, run, reopenedProjectDirectory, preferOfflinePagePreviewAfterSave, onClose, onOpenPythonWork, onPrepareBuild, onPreparePythonTools, preparePythonToolsLabel, prepareBuildAfterPath, prepareBuildLabel, registerBack, sourceMember, hostFile, resumeSaved, firstEntry, buildResume, pythonResume, pythonSetupResume, originalDirectoryOpening, checkpointArrival, checkpointHandoff }: Props) {
  const checkpoints = useSourceCheckpointFiles();
  const [checkpointMove, setCheckpointMove] = useState<CheckpointMoving>();
  const moving = useRef<CheckpointMoving | undefined>(undefined), departed = useRef(false), arrivalUsed = useRef(false);
  const checkpointTrigger = useRef<HTMLButtonElement>(null);
  const setMoving = (value: CheckpointMoving | undefined) => { moving.current = value; setCheckpointMove(value); };
  const resume = !sourceMember && !hostFile && resumeSaved?.projectId===projectId && resumeSaved.checkoutId===checkoutId &&
    fileHash(resumeSaved.savedHash) ? resumeSaved : undefined;
  const entry = !sourceMember && !hostFile && !resume && firstEntry?.projectId===projectId && firstEntry.checkoutId===checkoutId ? firstEntry : undefined;
  const [listing, setListing] = useState<Data | null>(null), [file, setFile] = useState<Data | null>(null), [text, setText] = useState('');
  const [finding,setFinding]=useState(false),[finder,setFinder]=useState<'directory'|'path'|'text'|'git'|'changes'>('path');
  const [changes,setChanges]=useState<CheckoutPathPage|null>(null),[changeSubmitted,setChangeSubmitted]=useState(false);
  const changesHeading=useRef<HTMLHeadingElement>(null);
  const [directory,setDirectory]=useState<DirectorySelection|null>(null);
  const [folderEditor,setFolderEditor]=useState<FolderEditor|null>(null), folderEditorNow=useRef<FolderEditor|null>(null);
  const folderHeading=useRef<HTMLHeadingElement>(null);
  const holdFolderEditor=(position:FolderEditor|null)=>{folderEditorNow.current=position;setFolderEditor(position);};
  const [gitView,setGitView]=useState<CheckoutGitView|null>(null);
  const [savedEdit,setSavedEdit]=useState<SavedEdit|null>(null), [listingStale,setListingStale]=useState(false);
  const [searchVersion,setSearchVersion]=useState(0);
  const editorContainer=useRef<HTMLElement>(null), editorInput=useRef<HTMLTextAreaElement>(null);
  const [fileFind,setFileFind]=useState<FileFind|null>(null);
  const findId=useId(), findTrigger=useRef<HTMLButtonElement>(null);
  const [markdownVisit,setMarkdownVisit]=useState<MarkdownVisit|null>(null), activeMarkdown=useRef<MarkdownVisit|null>(null);
  const visibleMarkdown=useRef<MarkdownVisit|null>(null);
  const markdownTrigger=useRef<HTMLButtonElement>(null), markdownHeading=useRef<HTMLHeadingElement>(null);
  const [staticPreview,setStaticPreview]=useState<StaticPreviewVisit|null>(null);
  const activeStaticPreview=useRef<StaticPreviewVisit|null>(null), staticPreviewReturn=useRef<StaticPreviewVisit|null>(null);
  const reviewedStaticRoot=useRef<StaticRootObservation|null>(null);
  const staticPreviewClose=useRef<(()=>void)|null>(null), staticPreviewTrigger=useRef<HTMLButtonElement>(null);
  const [newPath, setNewPath] = useState(''), [busy, setBusy] = useState(false), [editing, setEditing] = useState(false), [error, setError] = useState('');
  const [treeOpen, setTreeOpen] = useState(true), [reviewed, setReviewed] = useState(false);
  const [checkingSavedSource, setCheckingSavedSource] = useState(false);
  const [pythonRunReview, setPythonRunReview] = useState<PythonReview>(), [uncertainPythonSource, setUncertainPythonSource] = useState('');
  const pythonReviewNow = useRef<PythonReview | undefined>(undefined), pythonHeading = useRef<HTMLHeadingElement>(null);
  const [pythonToolsCloseGap,showPythonToolsCloseGap]=useState<PythonToolsCloseGap|null>(null);
  const pythonCloseGap=useRef<PythonToolsCloseGap|null>(null),pythonCloseHeading=useRef<HTMLHeadingElement>(null);
  const pythonCloseHeadingId=useId(),filesError=useRef<HTMLParagraphElement>(null);
  const holdPythonCloseGap=(value:PythonToolsCloseGap|null)=>{pythonCloseGap.current=value;showPythonToolsCloseGap(value)};
  useLayoutEffect(()=>{if(pythonToolsCloseGap)pythonCloseHeading.current?.focus({preventScroll:true})},[pythonToolsCloseGap]);
  const [pythonPackagePicker,setPythonPackagePicker]=useState<PythonPackagePicker|null>(null);
  const packageHeading=useRef<HTMLHeadingElement>(null), packageTrigger=useRef<HTMLButtonElement>(null);
  const editorHeading = useRef<HTMLHeadingElement>(null);
  const [orphan, setOrphan] = useState<string | null>(null), [choice, setChoice] = useState<'keep_mine' | 'use_latest' | null>(null);
  const [adoption, setAdoption] = useState<Data | null>(null), [adoptionExpired, setAdoptionExpired] = useState(false);
  const activeAdoption = useRef<Data | null>(null), adoptionHeading = useRef<HTMLHeadingElement>(null); activeAdoption.current = adoption;
  const alive = useRef(false), generation = useRef(0), handle = useRef(''), currentFile = useRef<Data | null>(null), currentText = useRef('');
  const entered = useRef<object | null>(null), edits = useRef(0), editSequence = useRef(0), blockedEdit = useRef(false);
  const initialDirectoryRead = useRef<object | null>(null);
  const latest = useRef({ run, onClose, onOpenPythonWork, onPrepareBuild, onPreparePythonTools });
  latest.current = { run, onClose, onOpenPythonWork, onPrepareBuild, onPreparePythonTools };
  const originalWindow = useRef(windowKey());
  const attemptedBuildResume=useRef(false), attemptedPythonSetupResume=useRef(false);
  const openedBuildResume=useRef<CheckoutBuildEditorResume|null>(null);
  const recentSave = savedEdit?.filesHandle===handle.current ? savedEdit : null;
  const savedLocation=listing?.priorSavedFile;
  const priorSave=!sourceMember&&!hostFile&&!resume&&!entry&&!file&&!recentSave&&!listingStale&&(finder==='path'||finder==='directory')&&!finding&&
    listing?.readOnly===true&&listing.filesHandle===handle.current&&listing.projectId===projectId&&listing.checkoutId===checkoutId&&
    savedLocation?.format==='pocketworld.owner-checkout-file-save-position.v1'&&savedLocation.projectId===projectId&&savedLocation.checkoutId===checkoutId&&
    typeof savedLocation.path==='string'&&!!savedLocation.path&&savedLocation.path.length<=240&&fileHash(savedLocation.sha256)&&
    typeof savedLocation.readId==='string'&&/^[a-f0-9-]{36}$/.test(savedLocation.readId)?
    {projectId,checkoutId,path:savedLocation.path,savedHash:savedLocation.sha256,readId:savedLocation.readId}:null;
  const priorSaveRow=priorSave?rows(listing?.files).find(row=>row.path===priorSave.path&&row.sha256===priorSave.savedHash):null;
  const draftOnlyList=!sourceMember&&!hostFile&&!resume&&!entry&&!buildResume&&!pythonResume&&!pythonSetupResume&&
    !originalDirectoryOpening&&!checkpointArrival&&!file&&!listingStale&&!finding&&(finder==='directory'||finder==='path')&&
    listing?.readOnly===true&&listing.filesHandle===handle.current&&listing.projectId===projectId&&listing.checkoutId===checkoutId&&
    bridge.getState().capabilities.commands.includes(command+'read')?listing.draftOnlyList:undefined;
  const draftOnlyPaths:string[]=typeof draftOnlyList?.id==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(draftOnlyList.id)&&
    Array.isArray(draftOnlyList.paths)&&draftOnlyList.paths.length<=64&&
    draftOnlyList.paths.every((path:unknown)=>checkoutFileParent(path)!==undefined)&&
    new Set(draftOnlyList.paths).size===draftOnlyList.paths.length?draftOnlyList.paths:[];
  const showingSavedFile = !!recentSave && !!file && !file.dirty && !file.conflict && !file.saveUnknown &&
    orphan===null && !blockedEdit.current && file.path===recentSave.path && file.latest?.expectedHash===recentSave.savedHash && text===recentSave.after;
  const canCreateEmpty = !sourceMember && !hostFile && !!file && file.filesHandle===handle.current &&
    file.projectId===projectId && file.checkoutId===checkoutId && file.dirty===false &&
    file.conflict===false && file.saveUnknown===false && orphan===null && !blockedEdit.current &&
    file.baseHash==='absent' && file.baseContent==='' && file.text==='' && text==='' &&
    file.latest?.projectId===projectId && file.latest.checkoutId===checkoutId && file.latest.path===file.path &&
    file.latest.deleted===true && file.latest.sha256==='absent' && file.latest.expectedHash==='absent' &&
    file.latest.bytes===0 && file.latest.content==='';
  const canCheckSavedBuild = !!onPrepareBuild && (!prepareBuildAfterPath || recentSave?.path===prepareBuildAfterPath) &&
    (!buildResume||openedBuildResume.current===buildResume&&recentSave?.path===buildResume.path);
  const keyFor = (path: string) => `${projectId}|${checkoutId}|${path}`;
  const live = (sequence: number) => alive.current && generation.current === sequence && originalWindow.current === windowKey() && !document.hidden;
  const unflushedCheckpointText = () => {
    const area = editorInput.current, prefix = keyFor('');
    return blockedEdit.current || !!(area?.isConnected && area.value !== textareaText(currentText.current)) ||
      [...interfaceDrafts.keys()].some(key => key.startsWith(prefix));
  };
  // The native window also checks saved/dirty state. Reject a transfer locally
  // if the browser holds any text native has not acknowledged, including IME.
  const cleanCheckpointDeparture = (files: string, original: Data | null) => {
    return live(generation.current) && handle.current === files && currentFile.current === original && !entered.current && !edits.current &&
      !unflushedCheckpointText() && !activeAdoption.current && !activeMarkdown.current && !activeStaticPreview.current && !pythonReviewNow.current &&
      (!original || original.filesHandle === files && original.projectId === projectId && original.checkoutId === checkoutId &&
        original.dirty === false && original.conflict === false && original.saveUnknown === false && !original.draftError && !original.formalFile &&
        original.latest?.deleted === false && original.text === original.latest.content && currentText.current === original.text);
  };
  function adoptCheckpointFiles(arrival: CheckpointFilesArrival, before: () => void = () => {}) {
    if (!checkpointHandoff || !live(generation.current) || !hasCheckpointContinue() ||
        arrival.viewHandle !== viewHandle || arrival.projectId !== projectId || arrival.previousFilesHandle !== handle.current) return false;
    return checkpointHandoff.adopt(arrival, () => {
      departed.current = true; handle.current = '';
      before(); // The actual old native Files page already retired in the acknowledged swap.
    });
  }
  function leaveUnconfirmedTransfer() {
    if (!checkpointHandoff || (moving.current !== 'unknown' && moving.current !== 'close_unknown') || !live(generation.current)) return;
    const sequence = generation.current;
    setMoving('leaving');
    void checkpointHandoff.closeProject(() => { departed.current = true; handle.current = ''; }).then(closed => {
      if (!closed && live(sequence)) setMoving('close_unknown');
    }, () => { if (live(sequence)) setMoving('close_unknown'); });
  }
  function closeTerminal(saved?: CheckoutSavedPosition) {
    const sibling = checkpointArrival?.side === 'sibling' ? checkpointArrival : undefined;
    if (moving.current) return;
    const original = currentFile.current, files = handle.current;
    if (sibling && unflushedCheckpointText()) {
      setError('编辑文字还未确认保留。请先完成编辑或核对草稿，再返回。');
      return;
    }
    if (!checkpointHandoff || !sibling || sibling.filesHandle !== files || !hasCheckpointContinue() ||
        !cleanCheckpointDeparture(files, original)) {
      latest.current.onClose(saved); return;
    }
    const sequence = generation.current;
    setMoving('returning');
    void returnCheckpointFiles(sibling).then(arrival => {
      if (!live(sequence) || moving.current !== 'returning' || handle.current !== files) return;
      if (!arrival) { setMoving(undefined); latest.current.onClose(saved); return; }
      if (!cleanCheckpointDeparture(files, original) || !adoptCheckpointFiles(arrival)) throw Error();
    }).catch(() => {
      if (live(sequence) && moving.current === 'returning' && handle.current === files) setMoving('unknown');
    });
  }
  const knownMarkdown = !sourceMember && !hostFile && !adoption && !choice && orphan===null && !blockedEdit.current ?
    savedMarkdownFile(file,text,projectId,checkoutId,handle.current) : null;
  const readingMarkdown = markdownVisit && sameMarkdown(markdownVisit,knownMarkdown) ? knownMarkdown : null;
  const markdownVisible = !!readingMarkdown;
  const showMarkdown = () => {
    const area=editorInput.current;
    if (!knownMarkdown || !live(generation.current) || entered.current || edits.current || activeAdoption.current || activeMarkdown.current ||
        currentFile.current!==file || interfaceDrafts.has(keyFor(knownMarkdown.path)) || !area?.isConnected ||
        currentText.current!==knownMarkdown.content || area.value!==textareaText(knownMarkdown.content)) return;
    const {fileHandle,path,sha256}=knownMarkdown;
    const visit={fileHandle,path,sha256,drawerScroll:area.closest('.pw-settings-dialog-content')?.scrollTop??0,
      caret:{start:area.selectionStart,end:area.selectionEnd,scrollTop:area.scrollTop,scrollLeft:area.scrollLeft}};
    activeMarkdown.current=visit; setMarkdownVisit(visit);
  };
  const closeMarkdown = () => { activeMarkdown.current=null; setMarkdownVisit(null); };
  useLayoutEffect(() => {
    if (markdownVisible && live(generation.current)) {
      if (visibleMarkdown.current!==markdownVisit) markdownHeading.current?.focus();
      visibleMarkdown.current=markdownVisit;
      return;
    }
    const visit=visibleMarkdown.current, area=editorInput.current;
    visibleMarkdown.current=null;
    if (markdownVisit) closeMarkdown();
    if (!visit || !knownMarkdown || !sameMarkdown(visit,knownMarkdown) || !live(generation.current) ||
        currentFile.current!==file || !area?.isConnected || area.value!==textareaText(knownMarkdown.content)) return;
    markdownTrigger.current?.focus({preventScroll:true});
    area.setSelectionRange(visit.caret.start,visit.caret.end);
    area.scrollTop=visit.caret.scrollTop; area.scrollLeft=visit.caret.scrollLeft;
    const drawer=area.closest('.pw-settings-dialog-content'); if(drawer)drawer.scrollTop=visit.drawerScroll;
  }, [markdownVisit,markdownVisible]);
  const canFindInFile = !sourceMember && !hostFile && !!file?.fileHandle;
  const localFind = canFindInFile && fileFind?.fileHandle===file?.fileHandle ? fileFind : null;
  const localMatch = localFind?.result?.text===text ? localFind?.result : undefined;
  const findBlocked = busy || !!adoption || !!choice;
  const closeFileFind = () => { setFileFind(null); findTrigger.current?.focus({ preventScroll: true }); };
  const findInFile = () => {
    const area=editorInput.current, query=localFind?.query, buffer=currentText.current;
    if (!localFind || !query || !area?.isConnected || !alive.current || document.hidden || entered.current ||
        activeAdoption.current || choice || currentFile.current?.fileHandle!==localFind.fileHandle ||
        area.value!==textareaText(buffer)) return;
    const previous=localFind.result, from=previous?.text===buffer && previous.at>=0 ? previous.at+query.length : 0;
    let at=area.value.indexOf(query,from);
    const wrapped=at<0 && from>0;
    if (wrapped) at=area.value.indexOf(query);
    const line=at<0 ? 0 : area.value.slice(0,at).split('\n').length;
    if (at>=0) { area.focus(); area.setSelectionRange(at,at+query.length); }
    setFileFind({ ...localFind, result:{ text:buffer,at,line,wrapped } });
  };
  const apply = (value: Data, body = true) => {
    if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('回执不属于这个原私人副本。');
    const next = { ...(currentFile.current?.fileHandle === value.fileHandle ? currentFile.current : {}), ...value };
    if (body) for (const key of ['saveStatus', 'message', 'saveError', 'importStatus', 'importReceipt', 'importMessage', 'importError', 'projectListRefreshMessage','pythonReadRevision']) if (!(key in value)) delete next[key];
    if (!('sourceHit' in value)) delete next.sourceHit;
    if (!('formalFile' in value)) delete next.formalFile;
    if (currentFile.current?.fileHandle!==value.fileHandle) {
      setFileFind(null);
      setPythonPackagePicker(null);
      if(folderEditorNow.current)holdFolderEditor(null);
    }
    currentFile.current = next; setFile(next);
    if (body && typeof value.text === 'string') {
      const retained = interfaceDrafts.get(keyFor(value.path));
      const unmatched = retained !== undefined && retained !== value.text;
      setOrphan(unmatched ? retained! : null);
      currentText.current = unmatched ? retained! : value.text; setText(currentText.current);
      editSequence.current = value.editSequence || 0; blockedEdit.current = false;
    }
    // Acknowledged native text is already in the shared DraftStore. Its separate
    // durable flag governs cold-process recovery; this map only holds UI text
    // the native owner has not acknowledged, so closed files cannot fill it up.
    if (next.editSequence === editSequence.current && interfaceDrafts.get(keyFor(next.path)) === currentText.current && next.text === currentText.current) interfaceDrafts.delete(keyFor(next.path));
  };
  const invoke = async (action: string, args: Data = {}, accept?: (value: Data) => void, opening?: OriginalDirectoryOpening, adoptedSourceCurrent?:()=>boolean) => {
    const inScope=()=> (!opening||opening.current())&&(!adoptedSourceCurrent||adoptedSourceCurrent());
    if (checkpoints.occupied() || moving.current || entered.current || edits.current || activeStaticPreview.current || document.hidden || !inScope()) return;
    const heldEditor=['read','readFound','readTextHit'].includes(action)?folderEditorNow.current:null;
    const sequence = generation.current, token = {}; entered.current = token; setBusy(true); setError('');
    if (opening) initialDirectoryRead.current = token;
    const current = () => live(sequence) && inScope();
    try {
      // The exact source-preview list is read-only. A declined visit must not toast on the restored page.
      const runHere = opening || adoptedSourceCurrent ? bridge.command : latest.current.run;
      const value = await runHere(command + action, { viewHandle, ...(action === 'open' || action === 'openSource' || action === 'openHostChange' ? { checkoutId } : { filesHandle: handle.current }), ...args });
      if (current() && entered.current === token) {
        if (opening && (value?.readOnly!==true || typeof value.filesHandle!=='string')) throw new Error('没有核对到原手机副本的只读文件清单。');
        accept?.(value); return value;
      }
    } catch (failure) {
      if(current()){
        if(opening){opening.finish('未能确认原副本的文件清单。没有打开文件编辑页，可重新选择同一副本。');return;}
        // The native file read can replace its single open editor before Web
        // receives (and validates) the reply. Only a definite no-send keeps A.
        const notSent=failure instanceof Error&&'code' in failure&&failure.code==='not_sent';
        if(!notSent&&heldEditor?.resumable&&folderEditorNow.current===heldEditor&&entered.current===token)
          holdFolderEditor({...heldEditor,resumable:false});
        setError(failureText(failure));
      }
    }
    finally {
      if (initialDirectoryRead.current === token) initialDirectoryRead.current = null;
      if (entered.current === token) { entered.current = null; if (alive.current && inScope()) setBusy(false); }
    }
  };
  const readDirectoryPage = (value: Data, path: string) => value.readOnly===true&&value.directoryPage?
    checkoutDirectoryPage(value.directoryPage,projectId,checkoutId,value.filesHandle,path,0,value.sourceDigest):null;
  const retireListChoices = () => setListing(previous=>previous?.priorSavedFile||previous?.draftOnlyList?
    {...previous,priorSavedFile:undefined,draftOnlyList:undefined}:previous);
  const invalidateFileInventory = () => {
    setListingStale(true);setFinding(false);setSearchVersion(epoch=>epoch+1);
    setDirectory(previous=>previous?{path:previous.path,page:null}:null);
  };
  const readList = (more = false) => {
    const folder=more?undefined:directory?.path;
    if (!entered.current && !edits.current && live(generation.current)) retireListChoices();
    return invoke('list', { afterPath: more ? listing?.nextPath || '' : '', sourceDigest: more ? listing?.sourceDigest || '' : '',
      ...(folder!==undefined?{refreshDirectory:folder}:{}) }, value => {
      if (value.projectId !== projectId || value.checkoutId !== checkoutId || value.filesHandle !== handle.current) throw new Error('文件列表不属于原副本。');
      if(!more){
        const page=readDirectoryPage(value,folder??'');
        if(folder!==undefined&&!page)throw Error('没有核对到这个目录，未打开其他位置。');
        setDirectory(page?{path:page.directory,page}:null);
      }
      const {directoryPage:_,...list}=value;
      setListing(previous => ({ ...list, files: more ? [...rows(previous?.files), ...rows(value.files)] : rows(value.files) }));
      setListingStale(false);
    });
  };
  const visitDirectory = async (path: string, start: number) => {
    const prior=directory?.page, sequence=generation.current;
    if(!prior||finder!=='directory'||entered.current||edits.current||!live(sequence))return;
    const result=await invoke('list',{browseDirectory:{directoryHandle:prior.directoryHandle,directory:path,start}},value=>{
      const page=checkoutDirectoryPage(value,projectId,checkoutId,handle.current,path,start,prior.sourceDigest,prior.inventoryHandle);
      setDirectory({path,page});
    });
    if(!result&&live(sequence))setDirectory(previous=>previous?.page===prior?{path:previous.path,page:null}:previous);
  };
  const openMatchedFile = (searchHandle:string,hit:{path:string;sha256:string;hit:number}) =>
    invoke('readFound',{searchHandle,hit:hit.hit},value=>{
      if(value.path!==hit.path||value.matchedFileSha256!==hit.sha256||value.filesHandle!==handle.current||value.projectId!==projectId||value.checkoutId!==checkoutId)
        throw Error('不能核对命中的原文件，未打开其他路径。');
      setChoice(null);setAdoption(null);apply(value);setTreeOpen(false);
      requestAnimationFrame(()=>{editorHeading.current?.scrollIntoView({block:'nearest'});editorHeading.current?.focus({preventScroll:true})});
    });
  const openDirectoryFile = async (hit:DirectoryFile) => {
    const page=directory?.page, sequence=generation.current;
    if(!page||finder!=='directory'||!page.rows.includes(hit)||entered.current||edits.current||!live(sequence))return;
    const opened=await openMatchedFile(page.directoryHandle,hit);
    if(!opened&&live(sequence))setDirectory(previous=>previous?.page===page?{path:previous.path,page:null}:previous);
  };
  const readFile = (path: string, { gitStatusOperation, saved, position, pythonTaskId, expectedPythonSha256, priorSavedFileId, draftOnlyListId, currentOnly, entry:sourceEntry, adoptedSourceCurrent }: FileRead = {}) => {
    if (sourceMember || hostFile) {
      if (path !== (sourceMember || hostFile)!.path || entered.current || edits.current) return;
      setReviewed(false); currentFile.current = null; setFile(null);
    }
    return invoke('read', { path, ...(gitStatusOperation?{gitStatusOperation}:{}), ...(saved?{savedEditId:saved.id}:{}),
      ...(priorSavedFileId?{priorSavedFileId}:{}), ...(draftOnlyListId?{draftOnlyListId}:{}), ...(pythonTaskId?{pythonTaskId}:{}),
      ...(expectedPythonSha256?{expectedPythonSha256}:{}) }, value => {
      if ((sourceMember || hostFile || gitStatusOperation || saved || position || currentOnly || sourceEntry) && (value.path !== path || value.latest?.deleted !== false ||
          !fileHash(value.latest?.expectedHash) || typeof value.latest.content !== 'string'))
        throw new Error('没有取得原副本这个文件的当前内容，未打开替代文件。');
      if (saved && (value.savedEditId !== saved.id || value.latest.expectedHash !== saved.savedHash || value.latest.content !== saved.after))
        throw new Error('未核对到这次保存的原文件内容，未打开其他版本。');
      if ((position || currentOnly || sourceEntry) && (value.latest.projectId !== projectId || value.latest.checkoutId !== checkoutId || value.latest.path !== path))
        throw new Error('读取内容不属于这份副本的这个路径。');
      if (priorSavedFileId && (value.priorSavedFileId !== priorSavedFileId || !position))
        throw new Error('未核对到先前确认保存的版本，没有打开其他版本。');
      if (draftOnlyListId && (value.draftOnlyListId!==draftOnlyListId||value.path!==path||value.dirty!==true||
          typeof value.text!=='string'||value.latest?.path!==path||value.latest.projectId!==projectId||
          value.latest.checkoutId!==checkoutId||typeof value.latest.deleted!=='boolean'))
        throw Error('没有核对到这份未保存草稿，未打开替代文件。');
      if (pythonTaskId && value.pythonTaskId!==pythonTaskId)
        throw Error('未核对到本次运行保存的原脚本，没有打开其他文件。');
      if (expectedPythonSha256 && (value.matchedPythonSetupSha256!==expectedPythonSha256 ||
          value.baseHash!==expectedPythonSha256 || value.dirty!==false || value.conflict!==false ||
          value.saveUnknown!==false || value.text!==value.latest?.content || interfaceDrafts.has(keyFor(path))))
        throw Error('未核对到原脚本的干净已保存版本。只显示同一副本的文件清单。');
      if (position && value.latest.expectedHash !== position.savedHash)
        throw new Error(priorSavedFileId?'此路径已不是先前确认保存的版本，没有切换到其他版本。':'此路径当前字节不再是上次保存的版本，没有切换到其他版本。');
      if(adoptedSourceCurrent&&(value.dirty!==false||value.conflict!==false||value.saveUnknown!==false||
        value.baseHash!==position?.savedHash||value.text!==value.latest?.content||interfaceDrafts.has(keyFor(path))))
        throw Error('原副本可能有保留草稿。这里只显示原副本的文件清单，没有替换草稿。');
      if(sourceEntry && value.latest.expectedHash!==sourceEntry.sha256)
        throw new Error('该入口文件已变化。请返回原项目重新核对，没有打开其他文件。');
      setChoice(null); setAdoption(null); apply(value); setTreeOpen(false);
      if (reopenedProjectDirectory && priorSavedFileId && priorSave?.readId===priorSavedFileId &&
          finder==='directory' && directory?.path==='' && directory.page?.start===0 &&
          directory.page.sourceDigest===listing?.sourceDigest && bridge.getState().state.projectId===projectId) {
        // The acknowledged file names its parent, not its siblings. Back must
        // read that directory afresh; do not carry the root page's row handles.
        const folder=checkoutFileParent(value.latest.path);
        if (folder) setDirectory(current=>current===directory?{path:folder,page:null}:current);
      }
      const sequence = generation.current, caret = position?.caret;
      requestAnimationFrame(() => {
        if (!live(sequence) || currentFile.current?.fileHandle !== value.fileHandle) return;
        editorHeading.current?.scrollIntoView({ block: 'nearest' });
        const area = editorContainer.current?.querySelector('textarea');
        if (caret && area && value.dirty===false && !value.conflict && !value.saveUnknown &&
          value.text===value.latest.content && currentText.current===value.text && !interfaceDrafts.has(keyFor(path)) &&
          area.value===textareaText(value.text) && caret.start>=0 && caret.start<=caret.end && caret.end<=area.value.length) {
          area.focus({ preventScroll: true }); area.setSelectionRange(caret.start, caret.end);
          area.scrollTop=caret.scrollTop; area.scrollLeft=caret.scrollLeft;
        } else editorHeading.current?.focus({ preventScroll: true });
      });
    },undefined,adoptedSourceCurrent);
  };
  const presentOrdinaryListing = (value: Data) => {
    let page: DirectorySelection['page'] = null;
    try { page = readDirectoryPage(value, ''); } catch { /* Keep the actual ordinary list. */ }
    const capable = ['list', 'readFound'].every(a => bridge.getState().capabilities.commands.includes(command + a));
    if (page && capable) { setDirectory({ path: page.directory, page }); setFinder('directory'); }
    else { setDirectory(null); setFinder('path'); }
  };
  const openFiles = async (initial = false) => {
    if (checkpointArrival) return; // A prepared stock listing must never dispatch a second open.
    let checkedEntry = entry;
    const resumeBuild=!attemptedBuildResume.current&&!entered.current&&!edits.current&&live(generation.current)&&
      !sourceMember&&!hostFile&&!resume&&!entry&&
      buildResume?.projectId===projectId&&buildResume.checkoutId===checkoutId?buildResume:undefined;
    const resumeSetup=!attemptedPythonSetupResume.current&&!entered.current&&!edits.current&&live(generation.current)&&
      !sourceMember&&!hostFile&&!resume&&!entry&&!buildResume&&!pythonResume&&
      pythonSetupResume?.projectId===projectId&&pythonSetupResume.checkoutId===checkoutId&&
      savedPythonPath(pythonSetupResume.path)&&fileHash(pythonSetupResume.savedHash)?pythonSetupResume:undefined;
    let resumeReadId:string|undefined;
    if(resumeBuild)attemptedBuildResume.current=true;
    if(resumeSetup)attemptedPythonSetupResume.current=true;
    const pin = sourceMember && { taskId: sourceMember.taskId, operationId: sourceMember.operationId, artifactId: sourceMember.artifactId,
      artifactSha256: sourceMember.artifactSha256, path: sourceMember.path, fileSha256: sourceMember.fileSha256 };
    const opening = initial && !pin && !hostFile && !resume && !entry && !buildResume && !pythonResume && !pythonSetupResume ? originalDirectoryOpening : undefined;
    const chosen=opening?.chosenSavedFile;
    const adopted=chosen?.projectId===projectId&&chosen.checkoutId===checkoutId&&chosen.path.length>0&&chosen.path.length<=240&&fileHash(chosen.savedHash)?chosen:undefined;
    const priorTarget=resumeBuild||adopted;
    const result = await invoke(hostFile ? 'openHostChange' : pin ? 'openSource' : 'open', hostFile ? { hostFile } : pin ? { sourceMember: pin } : {}, value => {
      if (value.projectId !== projectId || value.checkoutId !== checkoutId || !value.filesHandle) throw new Error('没有取得原私人副本文件窗口。');
      if (hostFile && (value.executionAuthorized !== false || !value.hostFile || !Object.entries(hostFile).every(([k, v]) => value.hostFile[k] === v)))
        throw new Error('没有取得这份本机版本的原文件范围。');
      if (pin && (value.executionAuthorized !== false || !value.savedMember || !Object.entries(pin).every(([k, v]) => v === value.savedMember[k]) ||
          value.savedMember.projectId !== projectId || value.savedMember.checkoutId !== checkoutId || value.savedMember.bytes !== sourceMember!.bytes ||
          typeof value.savedMember.text !== 'string' || new TextEncoder().encode(value.savedMember.text).length !== sourceMember!.bytes)) throw new Error('原任务保存的文件与所选副本不符。');
      if(entry && value.readOnly!==true){
        void latest.current.run(command+'close',{viewHandle,filesHandle:value.filesHandle}).catch(()=>{});
        throw new Error('未核对到原副本的文件列表，未读取替代文件。');
      }
      if(entry && value.sourceDigest!==entry.sourceDigest){
        checkedEntry=undefined;
        setError('入口文件所属版本已变化，只显示同一副本的文件列表。');
      }
      if(!pin&&!hostFile) presentOrdinaryListing(value);
      const {directoryPage:_,...list}=value;
      if(priorTarget){
        const saved=list.priorSavedFile;
        if(list.readOnly===true&&(!resumeBuild||list.sourceDigest===resumeBuild.sourceDigest)&&
          saved?.format==='pocketworld.owner-checkout-file-save-position.v1'&&saved.projectId===projectId&&saved.checkoutId===checkoutId&&
          saved.path===priorTarget.path&&saved.sha256===priorTarget.savedHash&&
          typeof saved.readId==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(saved.readId)){
          resumeReadId=saved.readId;
          delete list.priorSavedFile; // The exact list token is consumed by the native read, never reused by a fallback.
        }else setError(adopted?'未核对到采纳时保存的原文件。这里只显示同一副本的文件清单，未打开其他文件。':
          '未核对到本轮构建前保存的文件。这里只显示同一副本的文件清单，未打开其他文件。');
      }
      handle.current = value.filesHandle; setListing(list);
    }, opening);
    if(priorTarget&&resumeReadId&&result&&handle.current===result.filesHandle&&live(generation.current)){
      if(adopted&&interfaceDrafts.has(keyFor(adopted.path))){setError('原副本有未确认的编辑。这里只显示同一副本的文件清单，没有替换草稿。');return;}
      const opened=await readFile(priorTarget.path,{position:priorTarget,priorSavedFileId:resumeReadId,adoptedSourceCurrent:adopted?opening?.current:undefined});
      if(resumeBuild&&opened&&live(generation.current)&&currentFile.current?.fileHandle===opened.fileHandle)openedBuildResume.current=resumeBuild;
      return;
    }
    if(resumeSetup){
      // Use only the native Python return check, even if the ordinary list also
      // contains a prior save bookmark. No new read when unsent editor text exists.
      if(result?.readOnly===true && result.projectId===projectId && result.checkoutId===checkoutId &&
        handle.current===result.filesHandle && live(generation.current)){
        if(interfaceDrafts.has(keyFor(resumeSetup.path)))setError('原脚本有保留的未保存文字。没有打开或覆盖草稿。');
        else await readFile(resumeSetup.path,{position:resumeSetup,expectedPythonSha256:resumeSetup.savedHash});
      }
      return;
    }
    if (pythonResume && result && !pin && !hostFile && !resume && !checkedEntry &&
        pythonResume.projectId===projectId && pythonResume.checkoutId===checkoutId &&
        handle.current===result.filesHandle && live(generation.current)) {
      await readFile(pythonResume.path,{position:{projectId,checkoutId,path:pythonResume.path,savedHash:pythonResume.sha256},
        pythonTaskId:pythonResume.taskId});
      return;
    }
    const target = pin || hostFile || resume || checkedEntry;
    if (result && target && handle.current === result.filesHandle && live(generation.current))
      await readFile(target.path, { position: resume, entry: checkedEntry });
  };
  const edit = (value: string) => {
    const original = currentFile.current;
    if (!original || moving.current || folderEditorNow.current || sourceMember && !reviewed || entered.current || orphan !== null || activeAdoption.current || pythonReviewNow.current || activeStaticPreview.current || document.hidden) return;
    const key = keyFor(original.path);
    if (finder==='changes') return;
    if (value.length > 262144 || new TextEncoder().encode(value).length > 262144) { setError('编辑上限为 256 KiB，已有文字保留。'); return; }
    if (!interfaceDrafts.has(key) && interfaceDrafts.size >= 32) { setError('有 32 份界面文字尚未确认缓存，请先处理已有草稿。'); return; }
    interfaceDrafts.set(key, value); currentText.current = value; setText(value);
    const sequence = ++editSequence.current, epoch = generation.current, fileHandle = original.fileHandle;
    edits.current++; setEditing(true); setError('');
    void latest.current.run(command + 'edit', { viewHandle, filesHandle: handle.current, fileHandle, editSequence: sequence, text: value }).then(result => {
      if (!live(epoch) || currentFile.current?.fileHandle !== fileHandle || sequence !== editSequence.current || result.editSequence !== sequence) return;
      blockedEdit.current = false;
      apply({ ...result, text: value }, false);
    }).catch(failure => { if (live(epoch) && currentFile.current?.fileHandle === fileHandle && sequence === editSequence.current) { blockedEdit.current = true; setError(`这次文字尚未写入手机草稿缓存，界面文字仍保留：${failureText(failure)}`); } })
      .finally(() => { edits.current--; if (alive.current) setEditing(edits.current > 0); });
  };
  const dismissAdoption = () => { if (entered.current || edits.current) return; const selected = activeAdoption.current; activeAdoption.current = null; setAdoption(null); if (selected) void latest.current.run(command + 'dismissImport', { viewHandle, filesHandle: handle.current, importHandle: selected.importHandle }).catch(() => {}); };
  const hasChangeReview = !sourceMember&&!hostFile&&listing?.readOnly===true&&listing.projectId===projectId&&
    listing.checkoutId===checkoutId&&listing.filesHandle===handle.current&&
    ['find','findNext','readFound','reviewImport','import','dismissImport'].every(a=>bridge.getState().capabilities.commands.includes(command+a));
  const cleanChangeSource = (value:Data,sha?:string) => {
    const actual=value.latest;
    return value.filesHandle===handle.current&&value.projectId===projectId&&value.checkoutId===checkoutId&&
      typeof value.fileHandle==='string'&&!!value.fileHandle&&Number.isSafeInteger(value.revision)&&value.revision>=0&&value.dirty===false&&value.conflict===false&&value.saveUnknown===false&&!value.draftError&&
      actual?.deleted===false&&actual.projectId===projectId&&actual.checkoutId===checkoutId&&actual.path===value.path&&
      fileHash(value.baseHash)&&value.baseHash===actual.expectedHash&&value.baseHash===actual.sha256&&
      (!sha||sha===value.baseHash)&&typeof actual.content==='string'&&value.baseContent===actual.content&&value.text===actual.content&&
      currentText.current===value.text&&!interfaceDrafts.has(keyFor(value.path));
  };
  const companionReview=(source:Data)=>{
    const root=reviewedStaticRoot.current, saved=recentSave;
    return root&&saved&&root.projectId===projectId&&root.checkoutId===checkoutId&&root.viewHandle===viewHandle&&
      root.filesHandle===handle.current&&root.documentEpoch===bridge.getDocumentEpoch()&&
      saved.path===source.path&&/\.(css|js|mjs)$/i.test(saved.path)&&root.paths.includes(saved.path)&&
      source.baseHash===saved.savedHash&&source.text===saved.after?{root,saved}:null;
  };
  const staticCompanion=file&&companionReview(file);
  const staticEligible=!sourceMember&&!hostFile&&finder!=='changes'&&!!file&&
    (!!staticCompanion||file.path==='index.html'&&file.canPreviewSavedStatic===true)&&
    !folderEditorNow.current&&!localFind&&!choice&&!adoption&&!pythonRunReview&&
    !markdownVisit&&orphan===null&&!blockedEdit.current&&cleanChangeSource(file)&&
    Number.isSafeInteger(file.editSequence)&&file.editSequence>=0&&file.editSequence===editSequence.current&&canOpenSavedStaticPreview();
  const sameStaticEditor=(visit:StaticPreviewVisit)=>{
    const area=editorInput.current, source=visit.file, pin=visit.editor, companion=pin.companion;
    const sameCompanion=companion&&companionReview(source);
    const sameSource=companion?!!sameCompanion&&sameCompanion.root.entrySha256===pin.entrySha256&&
      sameCompanion.root.paths===companion.reviewedPaths&&sameCompanion.saved.id===companion.savedEditId&&
      source.path===companion.path&&sameCompanion.saved.savedHash===companion.sha256:
      source.canPreviewSavedStatic===true&&source.path==='index.html'&&currentText.current===pin.html;
    return sameSource&&live(visit.generation)&&pin.projectId===projectId&&pin.checkoutId===checkoutId&&pin.viewHandle===viewHandle&&
      bridge.getDocumentEpoch()===pin.documentEpoch&&handle.current===pin.filesHandle&&currentFile.current===source&&
      source.fileHandle===pin.fileHandle&&source.revision===pin.revision&&source.editSequence===pin.editSequence&&editSequence.current===pin.editSequence&&
      !entered.current&&!edits.current&&!blockedEdit.current&&!folderEditorNow.current&&!activeAdoption.current&&
      cleanChangeSource(source,companion?.sha256??pin.entrySha256)&&area?.isConnected===true&&area.value===textareaText(currentText.current);
  };
  const showStaticPreview=()=>{
    const area=editorInput.current, sequence=generation.current;
    if(!file||!staticEligible||!area?.isConnected||activeStaticPreview.current||activeMarkdown.current||
      area.value!==textareaText(currentText.current)||currentFile.current!==file||!live(sequence)||entered.current||edits.current)return;
    const asset=companionReview(file);
    const listedPaths=!asset&&!listingStale&&listing?.readOnly===true&&listing.filesHandle===handle.current&&
      listing.projectId===projectId&&listing.checkoutId===checkoutId?rows(listing.files).flatMap(item=>
        typeof item?.path==='string'&&item.change!=='deleted'&&fileHash(item.sha256)?[item.path]:[]):[];
    const visit:StaticPreviewVisit={file,generation:sequence,editor:{projectId,checkoutId,viewHandle,filesHandle:handle.current,
      fileHandle:file.fileHandle,revision:file.revision,editSequence:file.editSequence,entrySha256:asset?.root.entrySha256??file.baseHash,
      documentEpoch:bridge.getDocumentEpoch(),html:asset?'':currentText.current,listedPaths,
      ...(asset?{companion:{path:asset.saved.path,sha256:asset.saved.savedHash,savedEditId:asset.saved.id,reviewedPaths:asset.root.paths}}:{})},
      caret:{start:area.selectionStart,end:area.selectionEnd,scrollTop:area.scrollTop,scrollLeft:area.scrollLeft},
      drawerScroll:area.closest('.pw-settings-dialog-content')?.scrollTop??0};
    if(!sameStaticEditor(visit))return;
    activeStaticPreview.current=visit;setStaticPreview(visit);
  };
  const rememberStaticRoot=(visit:StaticPreviewVisit,paths:readonly string[])=>{
    if(activeStaticPreview.current!==visit||visit.editor.companion||!sameStaticEditor(visit))return;
    const {projectId,checkoutId,viewHandle,filesHandle,documentEpoch,entrySha256}=visit.editor;
    reviewedStaticRoot.current={projectId,checkoutId,viewHandle,filesHandle,documentEpoch,entrySha256,paths:[...paths]};
  };
  const leaveStaticPreview=(visit:StaticPreviewVisit)=>{
    if(activeStaticPreview.current!==visit)return;
    activeStaticPreview.current=null;staticPreviewClose.current=null;
    staticPreviewReturn.current=sameStaticEditor(visit)?visit:null;
    setStaticPreview(null);
  };
  const backFromStaticPreview=()=>{
    const visit=activeStaticPreview.current;
    if(!visit)return false;
    if(staticPreviewClose.current)staticPreviewClose.current();else leaveStaticPreview(visit);
    return true;
  };
  const showingStatic=!!staticPreview&&sameStaticEditor(staticPreview);
  useLayoutEffect(()=>{
    if(staticPreview){if(!showingStatic)leaveStaticPreview(staticPreview);return;}
    const visit=staticPreviewReturn.current,area=editorInput.current;
    staticPreviewReturn.current=null;
    if(!visit||!area||!sameStaticEditor(visit))return;
    const target=staticPreviewTrigger.current;
    (target?.isConnected&&!target.disabled?target:area).focus({preventScroll:true});
    area.setSelectionRange(visit.caret.start,visit.caret.end);
    area.scrollTop=visit.caret.scrollTop;area.scrollLeft=visit.caret.scrollLeft;
    const drawer=area.closest('.pw-settings-dialog-content');if(drawer)drawer.scrollTop=visit.drawerScroll;
  },[staticPreview,showingStatic]);
  const canEnterChanges = hasChangeReview&&orphan===null&&!blockedEdit.current&&!choice&&!adoption&&!pythonRunReview&&
    !activeMarkdown.current&&(!file||currentFile.current===file&&cleanChangeSource(file));
  const readChangedPage = (start=0,previous?:CheckoutPathPage) => {
    if (!hasChangeReview||entered.current||edits.current||!live(generation.current)) return;
    if (!previous) setChanges(null);
    void invoke(previous?'findNext':'find',previous?{searchHandle:previous.searchHandle,start}:{query:'',filter:'review_changes'},value=>{
      const page=checkoutPathPage(value,projectId,checkoutId,handle.current,'','review_changes',start,previous);
      if(page.files.some(hit=>hit.change==='unchanged'))throw Error('没有取得该副本的原改动列表。');
      setChanges(page);
    });
  };
  const clearChangeEditor = () => {
    currentFile.current=null;setFile(null);setOrphan(null);setFileFind(null);holdFolderEditor(null);
  };
  const enterChanges = () => {
    if (!canEnterChanges||entered.current||edits.current||!live(generation.current)||currentFile.current!==file||
        activeAdoption.current||pythonReviewNow.current||activeMarkdown.current) return;
    // The ordinary editor is clean. Retain its verified Save receipt, not an editor handle
    // which a later file read could replace before its acknowledgement.
    clearChangeEditor();setChangeSubmitted(false);setFinding(false);setFinder('changes');setTreeOpen(true);
    readChangedPage();
  };
  const reviewImport = (source:Data,sha?:string) => invoke('reviewImport',{fileHandle:source.fileHandle},value=>{
    if(value.projectId!==projectId||value.checkoutId!==checkoutId||value.fileHandle!==source.fileHandle||
        value.path!==source.path||value.filesHandle!==handle.current||!value.importHandle)
      throw Error('采纳审阅不属于原文件。');
    if(sha&&(currentFile.current!==source||!cleanChangeSource(source,sha)||
        value.format!=='pocketworld.local-text-file-review.v1'||value.expectedHash!==sha||value.content!==source.latest.content||
        typeof value.importHandle!=='string'||!/^([a-f0-9]{8})(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.importHandle)||
        !Number.isSafeInteger(value.expiresAt)||value.expiresAt<=Date.now()||
        value.deleted!==false||value.executionAuthorized!==false||value.projectVersionWritten!==false||
        !Number.isInteger(value.expectedVersion)||value.expectedVersion<0||
        !(value.targetDeleted===true&&value.expectedTargetHash==='absent'||value.targetDeleted===false&&fileHash(value.expectedTargetHash))||
        typeof value.diff!=='string'||value.diff.length>65536||typeof value.same!=='boolean'||typeof value.importable!=='boolean'||typeof value.truncated!=='boolean'))
      throw Error('未核对到该已保存文件与当前正式版本，没有准备采纳。');
    activeAdoption.current=value;setAdoption(value);
  });
  const openChangedFile = async (hit:CheckoutPathHit) => {
    const page=changes,sequence=generation.current,filesHandle=handle.current;
    if(finder!=='changes'||!page?.files.includes(hit)||hit.change==='deleted'||hit.binary||hit.bytes>262144||
        currentFile.current||entered.current||edits.current||!live(sequence))return;
    // Native can open the hit even if the reply is lost. Its earlier cold
    // save locator is usable only with no file opened in this same window.
    retireListChoices();
    const opened=await invoke('readFound',{searchHandle:page.searchHandle,hit:hit.hit},value=>{
      if(value.path!==hit.path||value.matchedFileSha256!==hit.sha256||value.latest?.expectedHash!==hit.sha256||
          value.filesHandle!==filesHandle||value.projectId!==projectId||value.checkoutId!==checkoutId)
        throw Error('副本中的所选文件已变化，请重读改动，没有打开替代文件。');
      apply(value);setTreeOpen(false);
    });
    const source=currentFile.current as Data|null;
    if(!opened||!source||source.fileHandle!==opened.fileHandle||filesHandle!==handle.current||!live(sequence)||entered.current||edits.current)return;
    if(!cleanChangeSource(source,hit.sha256)){
      // A preexisting retained draft belongs to this editor, never to the import.
      setFinder(directory?'directory':'path');setChanges(null);
      setError('这条改动还不能采纳。已打开同一文件；请先处理这里的草稿或保存状态。');
      requestAnimationFrame(()=>{if(live(sequence)&&currentFile.current===source)editorHeading.current?.focus()});
      return;
    }
    await reviewImport(source,hit.sha256);
  };
  const confirmImport = (shown:Data) => {
    const selected=activeAdoption.current,fromChanges=finder==='changes';
    if (!selected||selected!==shown||Number(selected.expiresAt)<=Date.now()||entered.current||edits.current||
        !live(generation.current)||!selected.importable||selected.truncated||
        fromChanges&&(!file||currentFile.current!==file||file.fileHandle!==selected.fileHandle||!cleanChangeSource(file,selected.expectedHash)))return;
    activeAdoption.current=null;setAdoption(null);
    if(fromChanges){
      // Adoption can change the baseline without changing the copy's source
      // digest. A lost receipt cannot justify any pre-adoption change flags.
      setChangeSubmitted(true);setChanges(null);invalidateFileInventory();
    }
    void invoke('import',{importHandle:selected.importHandle},value=>{
      if(fromChanges){
        const receipt=value.importReceipt;
        if(value.fileHandle!==selected.fileHandle||value.path!==selected.path||
            !(value.importStatus==='unknown'&&receipt==null||value.importStatus==='observed'&&receipt?.format==='pocketworld.local-text-file-import.v1'&&
              receipt.projectId===projectId&&receipt.checkoutId===checkoutId&&receipt.path===selected.path&&
              receipt.importedHash===selected.expectedHash&&receipt.replayAllowed===false&&
              receipt.projectVersionCommitted===!selected.same&&receipt.version===selected.expectedVersion+(selected.same?0:1)&&
              typeof receipt.checkoutBaselineUpdated==='boolean'))
          throw Error('没有取得所选文件的完整采纳回执。请重新核对，不自动重发。');
      }
      apply(value);
    });
  };
  const backFromChanges = () => {
    if (finder!=='changes') return false;
    if (entered.current||edits.current||!live(generation.current)) return true;
    if (file) {
      dismissAdoption();clearChangeEditor();setChangeSubmitted(false);setTreeOpen(true);setError('');
    }else{setFinder(directory?'directory':'path');setChanges(null);setError('');}
    return true;
  };
  const observedPythonRead:PythonEditorSource|undefined = !hostFile && !sourceMember && file && orphan===null && !blockedEdit.current &&
    file.filesHandle===handle.current && file.projectId===projectId && file.checkoutId===checkoutId &&
    file.dirty===false && file.conflict===false && file.saveUnknown===false &&
    Number.isSafeInteger(file.pythonReadRevision) && file.pythonReadRevision===file.revision &&
    savedPythonPath(file.path) && file.latest?.path===file.path && file.latest?.projectId===projectId &&
    file.latest?.checkoutId===checkoutId && file.latest?.deleted===false && fileHash(file.baseHash) &&
    file.latest?.sha256===file.baseHash && file.latest?.expectedHash===file.baseHash &&
    typeof file.latest?.content==='string' && file.baseContent===file.latest.content && file.text===file.latest.content && text===file.latest.content
    ? {path:file.path,sha256:file.baseHash,content:text,clickKey:file.fileHandle+':'+file.revision,selection:{readRevision:file.pythonReadRevision}} : undefined;
  const pythonSource:PythonEditorSource|undefined=showingSavedFile && recentSave && savedPythonPath(recentSave.path)
    ? {path:recentSave.path,sha256:recentSave.savedHash,content:recentSave.after,clickKey:recentSave.id,selection:{saveId:recentSave.id}}
    : observedPythonRead;
  const canReviewPython = !hostFile && !sourceMember && !!onOpenPythonWork && !!file && !!pythonSource &&
    uncertainPythonSource!==pythonSource.clickKey &&
    ['reviewPython','confirmPython','dismissPython'].every(action=>bridge.getState().capabilities.commands.includes(ownerPythonFiles+action));
  const canPreparePythonTools = !!onPreparePythonTools && !!pythonSource && canReviewPython &&
    bridge.getState().capabilities.commands.includes(command+'close');
  const pythonFolder=pythonSource?pythonSource.path.slice(0,Math.max(0,pythonSource.path.lastIndexOf('/'))):undefined;
  const canReviewPythonDirectory = canReviewPython && !canPreparePythonTools && !!pythonSource &&
    (finder==='directory' && directory?.path===pythonFolder || rows(listing?.files).some(row=>
      row.binary===false && row.change!=='deleted' && savedPythonPath(row.path) && row.path!==pythonSource.path &&
      row.path.slice(0,Math.max(0,row.path.lastIndexOf('/')))===pythonFolder));
  const directoryPage=directory?.page;
  const listedForPackage=canReviewPython&&!canPreparePythonTools&&pythonSource&&!listingStale&&finder==='directory'&&
    listing?.ownerPythonPackage===true&&listing.filesHandle===handle.current&&listing.projectId===projectId&&listing.checkoutId===checkoutId&&
    directoryPage&&directoryPage.sourceDigest===listing.sourceDigest?directoryPage:null;
  const packageDirectory=listedForPackage&&pythonSource&&
    pythonPackageDirectory(pythonSource.path,listedForPackage.directory+'/__init__.py')===listedForPackage.directory?
    listedForPackage.directory:undefined;
  const packageFolders=listedForPackage&&pythonSource&&listedForPackage.directory===pythonFolder?
    listedForPackage.rows.filter(item=>item.kind==='directory'&&pythonPackageDirectory(pythonSource.path,item.path+'/__init__.py')===item.path):[];
  const packageFiles=listedForPackage&&pythonSource&&packageDirectory?
    listedForPackage.rows.filter((item):item is DirectoryFile=>item.kind==='file'&&!item.binary&&item.bytes<=262144&&
      pythonPackageDirectory(pythonSource.path,item.path)===packageDirectory):[];
  const choosingPackage=pythonPackagePicker?.source===pythonSource?.clickKey?pythonPackagePicker:null;
  const pickedPackageFiles=choosingPackage?.directoryHandle===listedForPackage?.directoryHandle?
    packageFiles.filter(item=>choosingPackage?.hits.includes(item.hit)):[];
  const packageBytes=(typeof file?.latest?.bytes==='number'?file.latest.bytes:Infinity)+pickedPackageFiles.reduce((n,row)=>n+row.bytes,0);
  const packageReady=!!packageDirectory&&pickedPackageFiles.length<=7&&packageBytes<=262144&&
    pickedPackageFiles.some(item=>item.path===packageDirectory+'/__init__.py');
  const exactPythonEditor = () => canReviewPython && !!file && !!pythonSource && live(generation.current) &&
    currentFile.current===file && file.filesHandle===handle.current && currentText.current===pythonSource.content &&
    editorInput.current?.value===textareaText(pythonSource.content) && !entered.current && !edits.current &&
    !activeAdoption.current && !activeMarkdown.current && !choice && !file.draftError;
  const openPythonPackage=()=>{
    if(!exactPythonEditor()||!pythonSource||folderEditorNow.current||(!packageDirectory&&!packageFolders.length))return;
    setPythonPackagePicker({source:pythonSource.clickKey,hits:[]});
    requestAnimationFrame(()=>packageHeading.current?.focus());
  };
  const closePythonPackage=()=>{
    if(!choosingPackage)return false;
    if(!entered.current&&!edits.current){
      setPythonPackagePicker(null);
      requestAnimationFrame(()=>(packageTrigger.current??editorHeading.current)?.focus({preventScroll:true}));
    }
    return true;
  };
  const readPythonReview = (mode?:'directory'|'package') => {
    if (!exactPythonEditor() || !file || !pythonSource || pythonReviewNow.current) return;
    if (mode==='directory' && !canReviewPythonDirectory) return;
    if (mode==='package' && (!listedForPackage||!choosingPackage||!packageReady)) return;
    const packageSelection=mode==='package'&&listedForPackage?
      {directoryHandle:listedForPackage.directoryHandle,hits:pickedPackageFiles.map(item=>item.hit)}:undefined;
    void invoke('reviewPython', {fileHandle:file.fileHandle,...pythonSource.selection,...(mode?{sourceMode:mode}:{}),
      ...(packageSelection?{packageSelection}:{})}, value=>{
      const review=pythonReview(value,{projectId,checkoutId,path:pythonSource.path,sha256:pythonSource.sha256});
      if (!review || !!review.programFiles!==!!mode || !!review.packageDirectory!==(mode==='package')||
        (mode==='package'&&(review.packageDirectory!==packageDirectory||review.programFiles?.length!==pickedPackageFiles.length+1||
          !pickedPackageFiles.every(picked=>review.programFiles?.some(row=>row.path===picked.path&&row.sha256===picked.sha256&&row.bytes===picked.bytes)))))
        throw Error('未核对到当前保存的原 Python 源码，没有运行。');
      if(mode==='package')setPythonPackagePicker(null);
      pythonReviewNow.current=review;setPythonRunReview(review);
      requestAnimationFrame(()=>pythonHeading.current?.focus());
    });
  };
  const dismissPythonReview = () => {
    const review=pythonReviewNow.current;
    if (!review || !file || entered.current || edits.current) return;
    pythonReviewNow.current=undefined;setPythonRunReview(undefined);
    void invoke('dismissPython', {fileHandle:file.fileHandle,reviewHandle:review.reviewHandle});
  };
  const confirmSavedPython = () => {
    const review=pythonReviewNow.current;
    if (!review || !file || !pythonSource || !exactPythonEditor() ||
        review.path!==pythonSource.path || review.sha256!==pythonSource.sha256) return;
    pythonReviewNow.current=undefined;setPythonRunReview(undefined);
    // The native single-use receipt could be lost after the run was admitted.
    setUncertainPythonSource(pythonSource.clickKey);
    void invoke('confirmPython', {fileHandle:file.fileHandle,reviewHandle:review.reviewHandle}, value=>{
      const work=originalPythonRun(value,review);
      if (!work) throw Error('未收到本次原工作回执。请到「我的工作」核对，不会重复提交。');
      latest.current.onOpenPythonWork?.(work);
    });
  };
  const canRecheckPythonClose = (gap:PythonToolsCloseGap) => {
    const liveState=bridge.getState(),view=liveState.view as Data|undefined,state=liveState.state;
    return pythonCloseGap.current===gap&&live(gap.generation)&&liveState.host?.ready===true&&
      view?.section===null&&view.taskId===null&&state.projectId===projectId&&
      Number.isSafeInteger(gap.projectRevision)&&state.requestedProjectRevision===gap.projectRevision&&
      !state.filePath&&!state.fileLoading&&!state.fileSaving&&!state.conversationReader&&!state.queuedInputsPage&&
      !state.attachmentWorkspace?.open&&!state.chatAttachmentPickerOpen&&!state.localSourceIntake?.window&&!state.projectGitImport?.open&&
      gap.position.projectId===projectId&&gap.position.checkoutId===checkoutId&&!sourceMember&&!hostFile&&
      !checkpointArrival&&!originalDirectoryOpening&&savedPythonPath(gap.position.path)&&fileHash(gap.position.savedHash)&&
      !interfaceDrafts.has(keyFor(gap.position.path))&&['open','read'].every(action=>liveState.capabilities.commands.includes(command+action));
  };
  const recheckPythonClose = async (gap:PythonToolsCloseGap) => {
    if(!canRecheckPythonClose(gap)||entered.current||edits.current||handle.current)return;
    const current=()=>canRecheckPythonClose(gap);
    // An explicit read, not a second close. Only the original copy is eligible;
    // an actual no-longer-matching source leaves its newly checked list, not an editor.
    const list=await invoke('open',{},value=>{
      if(value?.readOnly!==true||value.projectId!==projectId||value.checkoutId!==checkoutId||
        typeof value.filesHandle!=='string'||!value.filesHandle)throw Error('尚未核对到原副本的文件清单。');
      handle.current=value.filesHandle;
      clearChangeEditor();setSavedEdit(null);setListingStale(false);setTreeOpen(true);
      const {directoryPage:_,...fresh}=value;
      presentOrdinaryListing(value);setListing(fresh);
    },undefined,current);
    if(!list||!current()||handle.current!==list.filesHandle)return;
    const file=await readFile(gap.position.path,{position:gap.position,expectedPythonSha256:gap.position.savedHash,adoptedSourceCurrent:current});
    if(!current()||handle.current!==list.filesHandle)return;
    holdPythonCloseGap(null);
    if(!file){
      setError(message=>message||'尚未核对到原脚本。这里只显示同一副本的文件清单。');
      requestAnimationFrame(()=>{if(live(gap.generation)&&handle.current===list.filesHandle&&!currentFile.current)filesError.current?.focus({preventScroll:true})});
    }
  };
  const leavePythonCloseGap = () => {
    const gap=pythonCloseGap.current;
    if(!gap||!live(gap.generation)||entered.current||edits.current)return;
    holdPythonCloseGap(null);latest.current.onClose(gap.savedHere?gap.position:undefined);
  };
  const preparePythonTools = () => {
    const prepare=latest.current.onPreparePythonTools, source=pythonSource;
    if(!prepare||!source||!file||!canPreparePythonTools||!exactPythonEditor()||pythonReviewNow.current||pythonCloseGap.current)return;
    const sequence=generation.current, filesHandle=handle.current, token={};
    const position={projectId,checkoutId,path:source.path,savedHash:source.sha256},savedHere='saveId' in source.selection;
    const projectRevision=bridge.getState().state.requestedProjectRevision;
    entered.current=token;setBusy(true);setError('');
    const held=()=>live(sequence)&&entered.current===token&&currentFile.current===file&&currentText.current===source.content;
    const closeEditor=async()=>{
      if(!held()||handle.current!==filesHandle||interfaceDrafts.has(keyFor(source.path))||
        editorInput.current?.value!==textareaText(source.content))throw Error('原脚本编辑器已变化，没有打开工具准备。');
      handle.current=''; // Unmount must not replay a close whose reply still has not arrived.
      try{
        const receipt=await bridge.command(command+'close',{viewHandle,filesHandle});
        if(!held()||handle.current)return false;
        if(receipt?.closed===true&&receipt.taskStopped===false)return true;
      }catch(failure){
        if(!held()||handle.current)return false;
        if(failure instanceof Error&&'code' in failure&&failure.code==='not_sent'){
          handle.current=filesHandle;
          throw Error('关闭原编辑器的请求未发送，工具准备没有打开。可以继续查看原脚本。');
        }
      }
      holdPythonCloseGap({position,savedHere,generation:sequence,projectRevision});
      return false;
    };
    void prepare({position,savedHere,closeEditor})
      .catch(failure=>{if(live(sequence)&&!pythonCloseGap.current)setError(failureText(failure))})
      .finally(()=>{if(entered.current===token){entered.current=null;if(alive.current)setBusy(false)}});
  };
  const close = (next?: 'prepare-build') => {
    if (entered.current || edits.current) {
      // Both Android Back and the dialog's Close may leave its first list, never an open editor or a later operation.
      if (!next && entered.current && entered.current===initialDirectoryRead.current && !edits.current &&
          !handle.current && !currentFile.current && live(generation.current) && originalDirectoryOpening?.current())
        latest.current.onClose();
      return;
    }
    if (pythonReviewNow.current) { dismissPythonReview(); return; }
    if (activeAdoption.current) { dismissAdoption(); return; }
    if (choice) { setChoice(null); return; }
    const sameOpenFile = currentFile.current === file;
    const fromSavedFile = sameOpenFile && showingSavedFile;
    // Reading a different file does not replace the last verified save. It
    // cannot supply that file's caret or the current-editor proof for a build.
    const fromAnotherFile = !next && sameOpenFile && !!file && file.path !== recentSave?.path &&
      file.latest?.deleted === false && file.dirty === false && file.conflict === false && file.saveUnknown === false &&
      orphan === null && !blockedEdit.current;
    if (!recentSave || !live(generation.current) || (!fromSavedFile && !fromAnotherFile)) {
      if (!next) closeTerminal();
      return;
    }
    const area = fromSavedFile && folderEditorNow.current?.resumable!==false ? editorContainer.current?.querySelector('textarea') : null;
    const caret = area?.value===textareaText(recentSave.after) ? {
      start: area.selectionStart, end: area.selectionEnd, scrollTop: area.scrollTop, scrollLeft: area.scrollLeft,
    } : undefined;
    const position = { projectId, checkoutId, path: recentSave.path, savedHash: recentSave.savedHash, caret };
    if (!next) { closeTerminal(position); return; }
    const prepare = latest.current.onPrepareBuild;
    if (!prepare || !canCheckSavedBuild) return;
    // Keep the ordinary editor and its exact receipt alive until the read-only
    // source comparison is back. Failure stays here instead of hiding the edit.
    const sequence = generation.current, token = {};
    entered.current = token; setBusy(true); setCheckingSavedSource(true); setError('');
    const closeEditor=async()=>{
      if(!live(sequence)||entered.current!==token||handle.current!==recentSave.filesHandle||
        currentFile.current!==file||currentText.current!==recentSave.after)
        throw Error('原编辑器或本次保存已变化，没有打开构建审阅。');
      // Relinquish only this visit's handle before sending; cleanup must not
      // repeat a close whose acknowledgement was lost.
      handle.current='';
      const receipt=await bridge.command(command+'close',{viewHandle,filesHandle:recentSave.filesHandle}).catch(()=>null);
      return live(sequence)&&entered.current===token&&receipt?.closed===true&&receipt.taskStopped===false;
    };
    void prepare({ position, filesHandle: recentSave.filesHandle, id: recentSave.id, closeEditor })
      .catch(failure => { if (live(sequence)) setError(failureText(failure)); })
      .finally(() => { if (entered.current === token) { entered.current = null; if (alive.current) { setBusy(false); setCheckingSavedSource(false); } } });
  };
  const browsingFolder=!!folderEditor && folderEditor.fileHandle===file?.fileHandle;
  const activeFilePath=browsingFolder&&folderEditor?.resumable===false ? undefined : file?.path;
  // A folder return belongs only to the ordinary editor and its actual selected
  // directory. Leave uncertain/conflicting or unretained edits in the old flow.
  const editorDirectory=!sourceMember&&!hostFile&&finder==='directory'&&directory&&file&&
    checkoutFileParent(file.path)===directory.path&&
    listing?.readOnly===true&&listing.projectId===projectId&&listing.checkoutId===checkoutId&&listing.filesHandle===handle.current&&
    file.projectId===projectId&&file.checkoutId===checkoutId&&file.filesHandle===handle.current&&file.latest?.deleted===false&&
    file.conflict===false&&file.saveUnknown===false&&orphan===null&&!blockedEdit.current&&!file.draftError&&
    (file.dirty===false||file.dirty===true&&file.draftDurable===true)?directory:null;
  const browseFromEditor = () => {
    const sequence=generation.current;
    if (!editorDirectory||!file||folderEditorNow.current||localFind||choice||activeMarkdown.current||
        activeAdoption.current||pythonReviewNow.current||!live(sequence)||entered.current||edits.current||
        currentFile.current!==file||currentText.current!==file.text||interfaceDrafts.has(keyFor(file.path))) return false;
    holdFolderEditor({fileHandle:file.fileHandle,resumable:true});setTreeOpen(true);
    // A verified Save retires the previous native directory generation. This
    // explicit return uses the same existing refresh, never reuses those hits.
    if(!editorDirectory.page)void readList();
    return true;
  };
  const resumeFolderEditor = () => {
    const sequence=generation.current, selected=folderEditorNow.current;
    if(!selected?.resumable||!live(sequence)||entered.current||edits.current||currentFile.current?.fileHandle!==selected.fileHandle)return;
    holdFolderEditor(null);setTreeOpen(false);
    requestAnimationFrame(()=>{
      if(live(sequence)&&!folderEditorNow.current&&currentFile.current?.fileHandle===selected.fileHandle){
        editorHeading.current?.scrollIntoView({block:'nearest'});
        editorHeading.current?.focus({preventScroll:true});
      }
    });
  };
  useLayoutEffect(() => {
    if(browsingFolder&&live(generation.current)){
      folderHeading.current?.scrollIntoView({block:'start'});
      folderHeading.current?.focus({preventScroll:true});
    }
  },[browsingFolder]);
  useLayoutEffect(() => {
    if(finder==='changes'&&!file&&live(generation.current)){
      changesHeading.current?.scrollIntoView({block:'start'});
      changesHeading.current?.focus({preventScroll:true});
    }
  },[finder,!!file]);
  const closeCurrent = useRef(close);
  closeCurrent.current = () => {
    if (pythonCloseGap.current) { leavePythonCloseGap(); return; }
    if (moving.current) { leaveUnconfirmedTransfer(); return; }
    if (checkpoints.back()) return;
    if (backFromStaticPreview()) return;
    if (closePythonPackage()) return;
    if (activeMarkdown.current) closeMarkdown();
    else if (backFromChanges()) return;
    else if (localFind && !entered.current && !edits.current && !activeAdoption.current && !choice) closeFileFind();
    else if(!browseFromEditor())close();
  };
  useEffect(() => {
    alive.current = true; originalWindow.current = windowKey();
    const retire = () => {
      const skip = departed.current || !!moving.current;
      const checkpointClosed = skip ? undefined : checkpoints.beforeFilesExit(), epoch = bridge.getDocumentEpoch();
      generation.current++; entered.current = null; initialDirectoryRead.current = null; pythonReviewNow.current=undefined; reviewedStaticRoot.current=null; const old = handle.current; handle.current = '';
      if (old && !skip) {
        const release = () => { void latest.current.run(command + 'close', { viewHandle, filesHandle: old }).catch(() => {}); };
        if (checkpointClosed) void checkpointClosed.then(closed => { if (closed && bridge.getDocumentEpoch() === epoch) release(); });
        else release();
      }
    };
    const hidden = () => { if (document.hidden || originalWindow.current !== windowKey()) { retire(); latest.current.onClose(); } };
    const unsubscribe = bridge.subscribe(hidden); document.addEventListener('visibilitychange', hidden); window.addEventListener('pagehide', hidden);
    if (!checkpointArrival) void openFiles(true);
    else if (!arrivalUsed.current) {
      arrivalUsed.current = true;
      const arrival = checkpointArrival, sequence = generation.current;
      if (!hasCheckpointContinue() || arrival.viewHandle !== viewHandle || arrival.projectId !== projectId || arrival.checkoutId !== checkoutId ||
          arrival.listing.filesHandle !== arrival.filesHandle || !live(sequence)) setMoving('unknown');
      else {
        const { directoryPage: _, ...list } = arrival.listing;
        handle.current = arrival.filesHandle; presentOrdinaryListing(arrival.listing); setListing(list);
        if (arrival.side === 'original') {
          if (arrival.resume) {
            const current = () => live(sequence) && handle.current === arrival.filesHandle && currentFile.current === null &&
              !entered.current && !edits.current;
            if (!checkpoints.resumeComparison({ projectId, checkoutId, viewHandle, filesHandle: arrival.filesHandle, current },
                arrival, () => checkpointTrigger.current)) setMoving('unknown');
          } else if (arrival.fromComparison) setError('已返回原副本的文件。原来的两版比较暂时无法核对。');
        }
      }
    }
    return () => { alive.current = false; retire(); unsubscribe(); document.removeEventListener('visibilitychange', hidden); window.removeEventListener('pagehide', hidden); };
  }, [projectId, checkoutId, viewHandle]);
  useEffect(() => {
    registerBack(() => { closeCurrent.current(); return true; });
    return () => registerBack(null);
  }, [registerBack]);
  useEffect(() => {
    if (checkpoints.active || finder==='changes' || browsingFolder || markdownVisit || staticPreview || adoption || !file || file.draftDurable || file.draftError || busy || editing || blockedEdit.current || orphan !== null || document.hidden) return;
    const timer = window.setTimeout(() => {
      if (!folderEditorNow.current&&!activeMarkdown.current) void invoke('cache', { fileHandle: file.fileHandle, retry: false }, value => apply(value, false));
    }, 600);
    return () => window.clearTimeout(timer);
  }, [file, busy, editing, orphan, adoption, markdownVisit, staticPreview, browsingFolder, finder, checkpoints.active]);
  useEffect(() => {
    setAdoptionExpired(false); if (!adoption) return; adoptionHeading.current?.focus();
    const remaining = Number(adoption.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) { setAdoptionExpired(true); return; }
    const timer = window.setTimeout(() => setAdoptionExpired(true), remaining); return () => window.clearTimeout(timer);
  }, [adoption]);
  const saveFile = (createEmpty = false) => {
    const submitted = currentFile.current;
    if (!submitted) return;
    if(createEmpty&&(!canCreateEmpty||submitted!==file||currentText.current!==''||
      !editorInput.current?.isConnected||editorInput.current.value!==''))return;
    // Once the original Save is requested its native directory can retire even
    // if the response is lost. Do not show an old page on the editor return.
    if(!sourceMember&&!hostFile&&finder==='directory'&&directory?.page&&
      !entered.current&&!edits.current&&live(generation.current))
      setDirectory(previous=>previous===directory?{path:directory.path,page:null}:previous);
    void invoke('save', {fileHandle:submitted.fileHandle,revision:submitted.revision,editSequence:submitted.editSequence,
      ...(createEmpty?{createEmpty:true}:{})},value=>{
      if (value.fileHandle!==submitted.fileHandle || value.path!==submitted.path) throw new Error('不属于这次保存的文件回执，草稿仍保留。');
      const known = observedSave(submitted,value);
      apply(value);
      if (value.saveStatus==='observed' && interfaceDrafts.get(keyFor(submitted.path))===value.text) interfaceDrafts.delete(keyFor(submitted.path));
      if (!sourceMember && !hostFile && (value.saveStatus==='observed' || value.saveStatus==='unknown')) {
        if (known) setSavedEdit(known);
        // Inventory, path and text hits may refer to the pre-save tree. A
        // new list/search is explicit and never executes Git in the background.
        invalidateFileInventory();
        setGitView(last=>last?.pendingOperation ? {pendingOperation:last.pendingOperation} : null);
      }
    });
  };
  const reopenedVersion = resume && file?.path===resume.path && file?.latest?.expectedHash===resume.savedHash ? resume : undefined;
  const editState = orphan!==null || blockedEdit.current ? '界面文字尚未核对' : file?.saveUnknown ? '保存结果待核对' :
    editing ? '正在保留编辑文字，未写入副本' : file?.dirty ?
    file.draftDurable===true && !file.draftError ? '手机草稿已保留，未写入副本' : '草稿仍在当前会话，尚未确认写入手机' : '没有未保存修改';
  const locked = busy || editing || !!adoption || !!pythonRunReview || !!choosingPackage, nativeText = file?.text || '';
  const offlinePreviewFirst = preferOfflinePagePreviewAfterSave && showingSavedFile && staticEligible &&
    file?.path==='index.html' && file.canPreviewSavedStatic===true;
  const staticPreviewAction=staticEligible&&<Button ref={staticPreviewTrigger} variant={offlinePreviewFirst?'primary':'secondary'}
    disabled={locked} onClick={showStaticPreview}>{staticCompanion?'预览网页（带上本次保存）':'预览已保存网页'}</Button>;
  const canDraftBeforeSave = !sourceMember && !hostFile && !!recentSave && !!file && showingSavedFile &&
    !locked && !choice && !file.draftError && fileHash(recentSave.beforeHash) && recentSave.before!==recentSave.after &&
    file.dirty===false && file.conflict===false && file.saveUnknown===false &&
    file.projectId===projectId && file.checkoutId===checkoutId && file.filesHandle===recentSave.filesHandle &&
    file.baseHash===recentSave.savedHash && file.baseContent===recentSave.after && file.text===recentSave.after &&
    file.latest?.deleted===false && file.latest.projectId===projectId && file.latest.checkoutId===checkoutId &&
    file.latest.path===recentSave.path && file.latest.content===recentSave.after && !interfaceDrafts.has(keyFor(file.path));
  const draftBeforeSave = () => {
    const area = editorInput.current;
    if (!canDraftBeforeSave || !recentSave || !file || !live(generation.current) || entered.current || edits.current ||
        activeAdoption.current || blockedEdit.current || handle.current!==recentSave.filesHandle || currentFile.current!==file ||
        file.editSequence!==editSequence.current || interfaceDrafts.has(keyFor(file.path)) || currentText.current!==recentSave.after ||
        !area?.isConnected || area.value!==textareaText(recentSave.after)) return;
    edit(recentSave.before); // The current A1 baseline is unchanged; only Save can write the A0 draft.
  };
  const canFindText=['findText','findTextNext','readTextHit'].every(a=>bridge.getState().capabilities.commands.includes(command+a));
  const sourceHit=file?.sourceHit,anchorable=!!(sourceHit&&file&&file.path===sourceHit.path&&file.latest?.expectedHash===sourceHit.sha256&&
    !file.dirty&&!file.saveUnknown&&!file.conflict&&orphan===null&&typeof file.latest?.content==='string'&&text===file.latest.content);
  const savedSource=typeof file?.latest?.content==='string'&&sourceHit&&file.latest.expectedHash===sourceHit.sha256?file.latest.content as string:null;
  const savedExcerpt=!anchorable&&sourceHit&&savedSource!==null&&savedSource.slice(sourceHit.start,sourceHit.end)===sourceHit.query?
    savedSource.slice(Math.max(savedSource.lastIndexOf('\n',sourceHit.start-1)+1,sourceHit.start-70),sourceHit.start)+sourceHit.query+
      savedSource.slice(sourceHit.end,sourceHit.end+100).split('\n',1)[0]:null;
  const checkpointSibling = checkpointArrival?.side === 'sibling';
  const fromCheckpointComparison = checkpointArrival?.side === 'sibling' && checkpointArrival.original.before !== null;
  const returnsToCheckpoint = checkpointSibling && hasCheckpointContinue() && cleanCheckpointDeparture(handle.current, currentFile.current);
  const backLabel = sourceMember ? '返回原工作' : hostFile ? '返回本机项目' : returnsToCheckpoint ?
    fromCheckpointComparison ? '返回原来的两版比较' : '返回原副本的文件' :
    originalDirectoryOpening && !originalDirectoryOpening.chosenSavedFile ? '返回项目预览' : '返回项目工作区';
  const formalLink = (pin: Data | undefined, evidence: 'import_receipt' | 'equal_content') => {
    if (!file || !pin || pin.evidence !== evidence || pin.projectId !== projectId || pin.checkoutId !== checkoutId || pin.path !== file.path ||
        typeof pin.handle !== 'string' || !pin.handle || !Number.isInteger(pin.version) || pin.version < 1 || !/^[a-f0-9-]{36}$/.test(pin.revisionId) ||
        !/^[a-f0-9]{64}$/.test(pin.sha256) || !Number.isInteger(pin.bytes) || pin.bytes < 0 || pin.bytes > 262144) return null;
    return <div className="pw-form-actions"><Button variant="secondary" disabled={busy || editing || orphan !== null || blockedEdit.current || !!file.dirty || !!file.saveUnknown}
      onClick={() => { if (evidence === 'equal_content') { activeAdoption.current = null; setAdoption(null); } void invoke('openFormal', { fileHandle: file.fileHandle, formalHandle: pin.handle }); }}>
      {evidence === 'equal_content' ? '查看内容相同的正式版本' : '打开已核对的正式文件'} v{pin.version}
    </Button></div>;
  };
  const adoptionPanel=adoption&&<section className="pw-checkout-adoption" aria-label="采纳一个文件">
    <h3 ref={adoptionHeading} tabIndex={-1}>只采纳 <bdi>{adoption.path}</bdi></h3>
    <p>{finder==='changes' && adoption.targetDeleted ? '正式项目当前没有此文件' : `正式项目 v${adoption.expectedVersion}`} → 这份已保存的私人副本。{adoption.deleted ? '这会在正式项目中登记此文件删除，可从原版本历史核对。' : '不会采纳其他文件或尚未保存的草稿。'}</p>
    <p>{adoption.message}</p>
    {adoption.diff ? <pre className="pw-checkout-review-diff" aria-label="正式版本与副本差异">{adoption.diff}</pre> : <p>{adoption.same ? '正式内容与副本已经相同。' : '文字相同，但文件存在或删除状态不同，请核对上方说明。'}</p>}
    {adoption.truncated && <p role="alert">差异超出完整展示范围，不能采纳。请缩小改动后重新比较。</p>}
    {adoption.same && formalLink(adoption.formalFile, 'equal_content')}
    {adoption.same && adoption.baselineNeedsRefresh && <p>本次只登记已一致的副本基线，不创建新的正式版本。</p>}
    <p className="pw-muted">{finder==='changes'?'确认时仍核对所选副本文件和正式版本。没有回执时须重新核对，不会自动重试。':'确认前仍核对副本 SHA 和正式版本。不是跨两处存储的原子事务；确认后离开页面不等于撤销已写入的版本。回执不明时重新比较，不会自动重试。'}</p>
    {adoptionExpired && <p role="status">这次审阅已过期，尚未采纳。返回后重新比较。</p>}
    <div className="pw-form-actions">
      <Button variant="secondary" disabled={busy || editing} onClick={finder==='changes'?backFromChanges:dismissAdoption}>
        {finder==='changes'?'返回改动列表':'返回文件'}
      </Button>
      <Button disabled={busy || editing || adoptionExpired || !adoption.importable || adoption.truncated} onClick={()=>confirmImport(adoption)}>
        {adoption.same ? '只登记已一致的基线' : adoption.deleted ? '采纳此文件删除' : '采纳这个文件'}
      </Button>
    </div>
  </section>;
  const changesBackLabel=file?'返回改动列表':'返回选文件';
  const checkpointOrigin = (): CheckpointFilesOrigin | undefined => {
    const sequence = generation.current, filesHandle = handle.current, original = currentFile.current;
    if (checkpointArrival?.side === 'sibling' || moving.current || sourceMember || hostFile || originalDirectoryOpening || finder === 'changes' || finder === 'git' || finding || browsingFolder || localFind ||
        choice || adoption || pythonRunReview || choosingPackage || markdownVisible || showingStatic || locked || orphan !== null ||
        listing?.readOnly !== true || listing.filesHandle !== filesHandle || listing.projectId !== projectId || listing.checkoutId !== checkoutId || original !== file) return;
    const current = () => live(sequence) && handle.current === filesHandle && currentFile.current === original && !entered.current &&
      !edits.current && !activeStaticPreview.current && !activeMarkdown.current && !activeAdoption.current && !pythonReviewNow.current &&
      !blockedEdit.current && (!original || original.filesHandle === filesHandle && original.projectId === projectId && original.checkoutId === checkoutId &&
        original.latest?.deleted === false && original.dirty === false && original.conflict === false && original.saveUnknown === false &&
        !original.draftError && !original.formalFile && currentText.current === original.text && !interfaceDrafts.has(keyFor(original.path)));
    return current() ? { projectId, checkoutId, viewHandle, filesHandle, current, handoff: checkpointHandoff ? {
      clean: () => !moving.current && cleanCheckpointDeparture(filesHandle, original),
      adopt: adoptCheckpointFiles, uncertain: () => setMoving('unknown'),
    } : undefined } : undefined;
  };
  const checkpointEntry = checkpoints.available && !checkpoints.active ? checkpointOrigin() : undefined;
  return <SettingsDialog isOpen title={checkpointMove || checkpoints.active?'这份私人副本':showingStatic?'预览已保存网页':readingMarkdown ? 'Markdown 预览' : finder==='changes' ? '核对副本改动' : sourceMember ? '原工作副本' : hostFile ? '已核对的本机文件' : checkpointArrival?.side==='sibling'?'新私人副本的文件':'这份私人副本的文件'}
    onClose={() => {if(pythonCloseGap.current||checkpointSibling||moving.current){closeCurrent.current();return;}if(checkpoints.back()||backFromStaticPreview()||closePythonPackage())return;if(activeMarkdown.current)closeMarkdown();else if(!backFromChanges()&&!browseFromEditor())close();}}
    closeLabel={checkpointMove||pythonToolsCloseGap?'返回原项目':checkpoints.active?'返回原文件或上一级':showingStatic||readingMarkdown||choosingPackage?'返回编辑':finder==='changes'?changesBackLabel:editorDirectory&&!browsingFolder&&!localFind&&!choice&&!adoption&&!pythonRunReview?'返回所选目录':backLabel}>
    {checkpointMove && <section className="pw-form-flow">
      <p role="status">{checkpointMove === 'returning' ? fromCheckpointComparison ? '正在核对并返回原来的两版比较…' : '正在核对并返回原副本的文件…' : checkpointMove === 'leaving' ?
        '正在关闭本次副本窗口…' : checkpointMove === 'close_unknown' ? '尚未确认关闭原窗口。可再关闭同一窗口，不会再次创建。' :
          '没有确认切换到哪个文件窗口。可关闭窗口返回原项目，不会再次创建或改开其他副本。'}</p>
      {(checkpointMove === 'unknown' || checkpointMove === 'close_unknown') && <Button variant="secondary" onClick={leaveUnconfirmedTransfer}>返回原项目</Button>}
    </section>}
    {!checkpointMove && checkpoints.active && (checkpoints.returnedComparison ?
      <ReturnedSourceCheckpointComparison comparison={checkpoints.returnedComparison} onExit={() => checkpoints.back()} /> :
      checkpoints.visit ?
      <ProjectSourceCheckpointScreen visit={checkpoints.visit} registerBack={checkpoints.registerBack} onExit={() => checkpoints.back()} /> :
      <section className="pw-form-flow"><h3>源码版本</h3>
        <p role="status">{checkpoints.notice || (checkpoints.closing ? '正在返回原文件…' : '正在核对这份私人副本…')}</p>
        <Button variant="secondary" onClick={() => checkpoints.back()}>返回原文件</Button>
      </section>)}
    {showingStatic&&staticPreview&&<ProjectSavedStaticPreview editor={staticPreview.editor}
      current={()=>activeStaticPreview.current===staticPreview&&sameStaticEditor(staticPreview)}
      onLeave={()=>leaveStaticPreview(staticPreview)} onVerifiedRoot={paths=>rememberStaticRoot(staticPreview,paths)}
      registerBack={closer=>{staticPreviewClose.current=closer}} />}
    {readingMarkdown && <section className="pw-checkout-files">
      <header><h3 ref={markdownHeading} tabIndex={-1} dir="auto">{readingMarkdown.path}</h3>
        <small>已核对保存版本 · <code>{readingMarkdown.sha256.slice(0,8)}</code> · 不加载图片，不打开链接</small></header>
      <SavedMarkdownBody text={readingMarkdown.content} />
      <Button variant="secondary" onClick={closeMarkdown}>返回编辑</Button>
    </section>}
    {pythonToolsCloseGap&&<section className="pw-form-flow" aria-busy={busy} aria-labelledby={pythonCloseHeadingId}>
      <h3 id={pythonCloseHeadingId} ref={pythonCloseHeading} tabIndex={-1}>核对原脚本</h3>
      <p role="status">没有打开工具准备，也没有运行脚本。尚未核对原编辑器是否关闭；不会重新写入 <bdi>{pythonToolsCloseGap.position.path}</bdi>。</p>
      {error&&<p role="alert">{error}</p>}
      <div className="pw-form-actions">
        {canRecheckPythonClose(pythonToolsCloseGap)&&<Button disabled={busy} onClick={()=>void recheckPythonClose(pythonToolsCloseGap)}>
          {busy?'正在核对原脚本…':'重新核对原脚本'}</Button>}
        <Button variant="secondary" disabled={busy} onClick={leavePythonCloseGap}>返回原项目</Button>
      </div>
    </section>}
    <div hidden={markdownVisible||showingStatic||checkpoints.active||!!checkpointMove||!!pythonToolsCloseGap} inert={checkpoints.active||!!checkpointMove||!!pythonToolsCloseGap||undefined}><div className="pw-checkout-files">
    {finder!=='changes'&&<><p className="pw-muted">{sourceMember ? '保存只改这个副本。原任务的固定包不会改变，也不会重新运行。' : hostFile ? '这是刚核对的手机副本。已有草稿会单独提示；保存只修改手机文件，采纳到本机正式项目仍须另行审阅，不会连回或改写主机。' : '这里是 AI 实际工作的副本，不是“项目文件”中的正式版本。保存只改副本；另行比较并采纳才会导回正式项目，不会运行代码或放开热修改；已有进程和未知回执仍会阻止不安全的保存。'}</p>
    <small className="pw-checkout-identity">{projectId} / {checkoutId}</small></>}
    {error && <p role="alert" ref={filesError} tabIndex={-1}>{error}</p>}
    {checkpoints.refusedFilesHandle === handle.current && <p role="status">这次未能打开源码版本。仍在原文件窗口，没有改动文件。</p>}
    {(checkpointEntry || checkpoints.active) && <Button ref={checkpointTrigger} size="small" variant="ghost" disabled={checkpoints.active} onClick={event => {
      const original = checkpointOrigin(); if (original) checkpoints.open(original, event.currentTarget);
    }}>源码版本</Button>}
    {finder!=='changes'&&hasChangeReview&&<div className="pw-form-actions">
      <Button size="small" variant="secondary" disabled={locked||!canEnterChanges} onClick={enterChanges}>核对已保存的改动</Button>
    </div>}
    {finder==='changes' ? <>
      {!file ? <section className="pw-form-flow" aria-busy={busy} aria-label="已保存的副本改动">
        <h3 ref={changesHeading} tabIndex={-1}>已保存的改动</h3>
        <p className="pw-field-hint">本次读取对照这份副本的源码基线，不含未保存草稿。点新增或修改的文件可与正式项目比较；缺失项只列出，不会在这里删除正式文件。</p>
        {!changes&&(busy?<p role="status">正在读取这份副本的改动…</p>:
          !error&&<p className="pw-field-hint">改动列表需要重新读取。</p>)}
        {changes&&<><p role="status">{changes.total?<>{changes.total} 个改动
          {changes.removedCount?<>（副本中缺失 {changes.removedCount} 个）</>:null}
          {' · 第 '}{changes.start+1} 至 {changes.start+changes.files.length} 项
        </>:'这份副本没有相对基线的已保存改动。'}</p>
          <div className="pw-checkout-file-list">{changes.files.map(hit=>hit.change==='deleted'?
            <p key={hit.path} className="pw-reading-note"><bdi>{hit.path}</bdi><br/><small>副本中缺失 · 仅供核对</small></p>:
            <Button key={hit.path} variant="ghost" disabled={locked||hit.binary||hit.bytes>262144} onClick={()=>void openChangedFile(hit)}>
              <span dir="auto">{hit.path}</span><small>{hit.change==='added'?'新增':'修改'}{hit.binary?' · 二进制，只列信息':hit.bytes>262144?' · 超过编辑上限，只列信息':''}</small>
            </Button>)}</div>
          <div className="pw-form-actions">
            {changes.start>0&&<Button size="small" variant="ghost" disabled={locked} onClick={()=>readChangedPage(changes.start-40,changes)}>上一页</Button>}
            {changes.next!==-1&&<Button size="small" variant="ghost" disabled={locked} onClick={()=>readChangedPage(changes.next,changes)}>下一页</Button>}
          </div></>}
        <div className="pw-form-actions"><Button size="small" variant="secondary" disabled={locked} onClick={()=>readChangedPage()}>重新读取改动</Button></div>
      </section> : adoptionPanel || <section className="pw-checkout-adoption" aria-label="所选文件的采纳结果" aria-busy={busy}>
        <h3 tabIndex={-1} ref={adoptionHeading}><bdi>{file.path}</bdi></h3>
        {busy ? <p role="status">{changeSubmitted?'正在核对采纳回执…':'正在与当前正式版本比较…'}</p> :
          changeSubmitted ? <><p role="status">{file.importReceipt?.message || file.importMessage ||
              '没有取得完整的采纳回执。返回后重新核对这个文件，不会自动重发。'} {file.projectListRefreshMessage || ''}</p>
            {file.importStatus==='observed'&&formalLink(file.formalFile,'import_receipt')}</> : <p>未取得所选文件的正式差异，没有写入正式项目。</p>}
      </section>}
    </> : !listing ? <Button variant="secondary" disabled={busy} onClick={() => { if(entry&&error)close();else void openFiles(); }}>
      {busy?'正在核对原副本…':entry&&error?'返回项目重新核对入口':'重新读取副本文件'}
    </Button> : <>
      {resume && !file && !busy && <div className="pw-form-actions">
        <Button variant="secondary" disabled={locked} onClick={()=>void readFile(resume.path,{position:resume})}>重新核对原保存版本</Button>
        <Button variant="ghost" disabled={locked} onClick={()=>void readFile(resume.path,{currentOnly:true})}>读取这一路径的当前内容</Button>
      </div>}
      {browsingFolder&&<div className="pw-form-actions"><h3 ref={folderHeading} tabIndex={-1}>选择要编辑的文件</h3>
        {folderEditor?.resumable ? <Button variant="ghost" disabled={locked} onClick={resumeFolderEditor}>继续编辑 <bdi>{file?.path.split('/').at(-1)}</bdi></Button> :
          <p role="status">未核对刚才打开的文件。请从同一副本重新选择。</p>}
      </div>}
      {draftOnlyPaths.length>0&&<section className="pw-form-flow" aria-label="继续未保存草稿">
        <h3>继续未保存草稿</h3>
        <p className="pw-field-hint">这些草稿还未写入副本，不在已保存的目录中。</p>
        <div className="pw-checkout-file-list">{draftOnlyPaths.map(path=><Button key={path} variant="secondary" disabled={locked}
          onClick={()=>{
            if(!bridge.getState().state.filePath&&!currentFile.current&&!entered.current&&!edits.current&&
                live(generation.current)&&handle.current===listing.filesHandle)
              void readFile(path,{draftOnlyListId:draftOnlyList.id});
          }}><bdi>{path}</bdi></Button>)}</div>
      </section>}
      {sourceMember || hostFile ? !file && <div><p dir="auto">{(sourceMember || hostFile)!.path}</p><Button variant="secondary" disabled={locked} onClick={() => void readFile((sourceMember || hostFile)!.path)}>{busy ? '正在读取同一文件…' : '重读原副本中的这个文件'}</Button></div> : <Accordion expandedKeys={new Set(treeOpen ? ['checkout-tree'] : [])} onExpandedChange={keys => setTreeOpen(Array.from(keys).includes('checkout-tree'))}><AccordionItem id="checkout-tree" title={file ? '切换文件或新建' : '选择要编辑的文件'} description={listingStale?'完整清单待重新核对':`${listing.fileCount} 个实际源码文件；另列保留草稿`}>
        {recentSave && (!browsingFolder||recentSave.path!==file?.path) && <div className="pw-checkout-file-list" aria-label="最近一次已核对的保存">
          <Button variant="secondary" disabled={locked} onClick={()=>void readFile(recentSave.path,{saved:recentSave})}>
            <span dir="auto">{recentSave.path}</span><small>最近保存 · {recentSave.savedHash.slice(0,8)}</small>
          </Button>
        </div>}
        {priorSave && <div className="pw-checkout-file-list" aria-label="先前确认的保存位置">
          <Button variant="secondary" disabled={locked} onClick={()=>{
            if(entered.current||edits.current||!live(generation.current)||currentFile.current||handle.current!==listing.filesHandle)return;
            // A new read, not the earlier location, decides what can open. If
            // it fails, leave the same ordinary list and don't suggest it again.
            setListing(current=>current===listing?{...current,priorSavedFile:undefined}:current);
            void readFile(priorSave.path,{position:priorSave,priorSavedFileId:priorSave.readId});
          }}>
            <span dir="auto">{priorSave.path}</span><small>先前确认保存 · {priorSave.savedHash.slice(0,8)}{priorSaveRow?.hasDraft===true?' · 有保留草稿':''}</small>
          </Button>
        </div>}
        {listing.draftError && <p role="alert">{listing.draftError}</p>}
        {(directory||canFindText||listing.gitAvailable===true)&&<div role="group" aria-label="查看副本文件" className="pw-form-actions">{(['directory','path','text','git'] as const)
          .filter(v=>v==='directory'&&directory||v==='path'||v==='text'&&canFindText||v==='git'&&listing.gitAvailable===true).map(v=><Button key={v}
          size="small" variant={finder===v?'secondary':'ghost'} aria-pressed={finder===v} disabled={locked} onClick={()=>{if(v!==finder){setFinding(false);setFinder(v)}}}>
          {v==='directory'?'目录':v==='path'?'文件名':v==='text'?'代码文字':'Git 变更'}</Button>)}</div>}
        {finder==='directory'&&directory&&<div><ProjectCheckoutDirectory selection={directory} busy={locked}
          activePath={activeFilePath} visit={(path,start)=>void visitDirectory(path,start)} refresh={()=>void readList()}
          open={hit=>void openDirectoryFile(hit)} /></div>}
        {finder==='git'&&listing.gitAvailable===true&&<ProjectCheckoutGitChanges value={gitView} busy={locked}
          inspect={request=>void invoke('list',{gitRead:request},v=>setGitView(checkoutGitView(v,projectId,checkoutId,handle.current)))}
          open={(path,op)=>void readFile(path,{gitStatusOperation:op})}/>}
        {finder==='text'&&canFindText&&<ProjectCheckoutTextSearch key={searchVersion} project={projectId} checkout={checkoutId} filesHandle={handle.current} busy={locked} visible={setFinding} invoke={invoke}
          open={(textHandle,hit,row,query)=>void invoke('readTextHit',{textHandle,hit},v=>{
            const a=v.sourceHit;
            if(!a||v.path!==row.path||a.path!==row.path||a.sha256!==row.sha256||a.line!==row.line||a.column!==row.column||a.query!==query||
              v.latest?.expectedHash!==row.sha256||v.latest?.deleted!==false||typeof v.latest.content!=='string'||!Number.isSafeInteger(a.start)||!Number.isSafeInteger(a.end)||
              a.start<0||a.end>v.latest.content.length||a.end-a.start!==query.length||v.latest.content.slice(a.start,a.end)!==query)throw Error('不能核对这行代码的原版，未定位到替代文件。');
            setChoice(null);setAdoption(null);apply(v);setTreeOpen(false);requestAnimationFrame(()=>{editorHeading.current?.scrollIntoView({block:'nearest'});editorHeading.current?.focus({preventScroll:true})});
          })}/>}
        {finder==='path'&&['find','findNext','readFound'].every(a=>bridge.getState().capabilities.commands.includes(command+a))&&<ProjectCheckoutPathSearch key={searchVersion} project={projectId} checkout={checkoutId}
          filesHandle={handle.current} busy={locked} visible={setFinding} invoke={invoke} open={(searchHandle,hit)=>void openMatchedFile(searchHandle,hit)}/>}
        {finder!=='git'&&finder!=='directory'&&!finding&&<>        <div className="pw-form-actions"><Button variant="ghost" disabled={locked} onClick={() => void readList()}>重新读取文件列表</Button></div>
        {listingStale ? <p className="pw-field-hint" role="status">旧列表不再显示，可重读完整清单。{recentSave && ' 也可直接核对上方已保存的文件。'}</p> : <>
        <div className="pw-checkout-file-list">{rows(listing.files).filter(row=>row!==priorSaveRow&&
          !(row.change==='draft_only'&&draftOnlyPaths.includes(row.path))).map(row => <Button key={row.path} aria-current={activeFilePath === row.path ? 'true' : undefined} variant="ghost" disabled={locked || row.binary === true} onClick={() => void readFile(row.path)}><span>{row.path}</span><small>{row.binary ? '二进制' : row.hasDraft ? '有保留草稿' : row.change === 'deleted' ? '副本已删除' : row.change === 'added' ? '副本新增' : row.change === 'modified' ? '副本已修改' : '与原基线一致'}</small></Button>)}</div>
        {!rows(listing.files).length && <p>副本中还没有文件。可以在下方填写相对路径创建，或返回目标让 AI 开始。</p>}
        {listing.nextPath && <Button variant="secondary" disabled={locked} onClick={() => void readList(true)}>读取下一页文件</Button>}</>}</>}
        {finder!=='git'&&<><TextField label="相对文件路径" value={newPath} onChange={setNewPath} isDisabled={locked} hint="例如 index.html 或 src/main.ts；不操作运行缓存和 Git 内部目录。" />
        <Button variant="secondary" disabled={locked || !newPath.trim()} onClick={() => void readFile(newPath.trim())}>打开或准备新文件草稿</Button></>}
      </AccordionItem></Accordion>}
      {file && <div hidden={browsingFolder}><section ref={editorContainer} className="pw-checkout-editor"><header><h3 ref={editorHeading} tabIndex={-1}>{file.path}</h3><small>{file.baseHash === 'absent' ? '文件尚未存在' : '来自此副本的实际文件'} · {editState}</small>
        {reopenedVersion && <p className="pw-field-hint">已从同一副本重读保存版本 <code>{reopenedVersion.savedHash.slice(0,10)}</code>。{(file.dirty||orphan!==null)&&'编辑框仍保留未保存文字，不套用原光标位置。'}</p>}
        {editorDirectory&&!localFind&&!choice&&!adoption&&!pythonRunReview&&<Button variant="ghost" size="small" leadingIcon={IconFolder} disabled={locked}
          onClick={browseFromEditor}>返回所选目录</Button>}
      </header>
        {sourceMember && !reviewed ? <div>
          <p role="status">{file.latest.expectedHash === sourceMember.fileSha256 ? '刚读取的副本与任务当时保存的内容一致。' : '副本在交付后已改变。编辑的是当前副本，不会用旧包覆盖它。'}
            {(file.dirty || orphan !== null) && ' 此文件另有未保存草稿，进入编辑后可核对。'}</p>
          {file.latest.expectedHash !== sourceMember.fileSha256 && <Accordion><AccordionItem id="source-to-current" title="比较固定版本与当前副本">
            <InlineDiff before={listing.savedMember.text} after={file.latest.content} versionLabel="任务交付时 → 当前副本" beforeLabel="任务固定版本" afterLabel="刚读取的副本" />
          </AccordionItem></Accordion>}
          <Button disabled={locked} onClick={() => setReviewed(true)}>编辑当前副本</Button>
        </div> : <>
        {orphan !== null && <div role="alert"><p>上次界面文字尚未确认缓存，与手机恢复的草稿不同。下面保留的是界面文字，选择前不会替换任一份。</p><InlineDiff before={nativeText} after={orphan} versionLabel="手机恢复的草稿" /><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => { const retained = orphan; blockedEdit.current = true; setOrphan(null); currentText.current = retained; setText(retained); /* explicit cache edit, never a source write */ const sequence = ++editSequence.current; void invoke('edit', { fileHandle: file.fileHandle, editSequence: sequence, text: retained }, value => { blockedEdit.current = false; apply({ ...value, text: retained }, false); }); }}>保留界面文字为草稿</Button><Button variant="secondary" disabled={locked} onClick={() => { interfaceDrafts.delete(keyFor(file.path)); setOrphan(null); currentText.current = nativeText; setText(nativeText); }}>采用已恢复的手机草稿</Button></div></div>}
        {sourceHit&&<div><p role="status">已核对已保存文件第 {sourceHit.line} 行、第 {sourceHit.column} 列的「{sourceHit.query}」。
          {(!anchorable)&&' 当前显示保留的草稿，不把已保存版本的行号套用到草稿。'}</p>
          {savedExcerpt!==null&&<p className="pw-field-hint">已保存的行片段：<code dir="auto">{savedExcerpt}</code></p>}
          {anchorable&&<Button size="small" variant="secondary" disabled={locked} onClick={()=>{
            const area=editorContainer.current?.querySelector('textarea'),range=area&&checkoutEditorSelection(file.latest.content,sourceHit,area.value);
            if(!area||!range){setError('编辑框和已读取的版本不一致，没有替你移动光标。');return}
            area.focus();area.setSelectionRange(...range);
          }}>在编辑框选中这一处</Button>}</div>}
        {canFindInFile && <>
          <div className="pw-form-actions"><Button ref={findTrigger} variant="ghost" size="small" leadingIcon={IconSearch}
            disabled={findBlocked} aria-expanded={!!localFind} aria-controls={localFind ? findId : undefined}
            onClick={()=>localFind ? closeFileFind() : setFileFind({fileHandle:file.fileHandle,query:''})}>
            {localFind?'收起查找':'在此文件查找'}
          </Button>{knownMarkdown && <Button ref={markdownTrigger} variant="ghost" size="small" disabled={locked}
            onClick={showMarkdown}>预览保存的 Markdown</Button>}</div>
          {localFind && <section id={findId} aria-label="在此文件查找">
            <TextField label="查找当前编辑框中的文字" value={localFind.query} maxLength={128} autoFocus
              isDisabled={findBlocked} inputDir="auto" hint="区分大小写，包含尚未保存的文字。"
              onChange={query=>setFileFind({fileHandle:localFind.fileHandle,query})}
              onKeyDown={e=>{if(e.key==='Enter'&&!e.nativeEvent.isComposing&&e.nativeEvent.keyCode!==229){e.preventDefault();findInFile()}}}/>
            <div className="pw-form-actions"><Button variant="secondary" size="small" disabled={findBlocked||!localFind.query}
              onClick={findInFile}>{localMatch&&localMatch.at>=0?'下一处':'查找'}</Button></div>
            {localMatch&&<p className="pw-field-hint" role="status">{localMatch.at<0?'当前编辑框没有匹配。':
              `${localMatch.wrapped?'已回到开头 · ':''}第 ${localMatch.line} 行`}</p>}
          </section>}
        </>}
        <TextAreaField ref={editorInput} label="副本文件内容" value={text} onChange={edit} isReadOnly={busy || !!adoption || !!pythonRunReview || !!choosingPackage || orphan !== null} className="pw-code-editor" />
        {(file.conflict || file.saveUnknown) && <p role="status">{file.saveUnknown ? '保存结果待核对，请读取同一文件。' : '副本文件与保留草稿的基线不同。请比较后决定，未自动覆盖。'}</p>}
        <Accordion>{(recentSave?.path!==file.path || file.dirty || file.conflict || orphan!==null) &&
          <AccordionItem id="checkout-file-diff" title="比较保留的修改"><InlineDiff before={file.baseContent || ''} after={text} versionLabel="草稿原基线" />{file.latest && file.conflict && <><h4>当前副本与保留草稿</h4><InlineDiff before={file.latest.content || ''} after={text} versionLabel="刚读取的副本" /></>}</AccordionItem>}
          {recentSave && recentSave.path===file.path && <AccordionItem id="checkout-last-save" title="比较最近一次已核对的保存" description={recentSave.savedHash.slice(0,8)}>
            <p className="pw-field-hint">本次文件窗口只保留这一份对照，不含之后的未保存输入，也不是正式版本历史。</p>
            <InlineDiff before={recentSave.before} after={recentSave.after} versionLabel="保存前 → 已写入副本"
              beforeLabel={recentSave.beforeHash==='absent'?'保存前（路径不存在）':'保存前'} afterLabel="本次写入" readOnly />
            {canDraftBeforeSave && <div className="pw-form-actions">
              <Button variant="secondary" size="small" onClick={draftBeforeSave}>将保存前文字放回草稿</Button>
              <p className="pw-field-hint">只改草稿。另点「保存到这个副本」才会写入文件。</p>
            </div>}
          </AccordionItem>}
        </Accordion>
        {choice ? <div role="region" aria-label="处理文件冲突"><p>{choice === 'keep_mine' ? '把当前副本作为新基线，保留我的草稿文字？下一次保存才会改写文件。' : '放弃这份草稿，采用刚读取的副本内容？不会写入文件。'}</p><div className="pw-form-actions"><Button variant="secondary" disabled={locked} onClick={() => setChoice(null)}>返回</Button><Button disabled={locked} onClick={() => void invoke('resolve', { fileHandle: file.fileHandle, revision: file.revision, choice }, value => { interfaceDrafts.delete(keyFor(file.path)); setChoice(null); apply(value); })}>确认这个选择</Button></div></div> : <div className="pw-form-actions">
          <Button variant="secondary" disabled={locked || orphan !== null} onClick={() => void readFile(file.path)}>读取同一文件核对</Button>
          {file.conflict && <><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('keep_mine')}>保留我的修改…</Button><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => setChoice('use_latest')}>采用当前副本…</Button></>}
          {canReviewPython && !pythonRunReview && (canPreparePythonTools
            ? <Button disabled={locked} onClick={preparePythonTools}>{preparePythonToolsLabel || '查看手机工具准备'}</Button>
            : <Button disabled={locked} onClick={()=>readPythonReview()}>运行已保存的脚本…</Button>)}
          {canReviewPythonDirectory && !pythonRunReview &&
            <Button variant="ghost" disabled={locked} onClick={()=>readPythonReview('directory')}>核对同目录 Python 程序…</Button>}
          {(packageDirectory||packageFolders.length>0)&&!pythonRunReview&&
            <Button ref={packageTrigger} variant="ghost" disabled={locked} onClick={openPythonPackage}>选择 Python 包源码…</Button>}
          {offlinePreviewFirst&&staticPreviewAction}
          {showingSavedFile ? <>
            <Button variant={canReviewPython?'ghost':offlinePreviewFirst?'secondary':undefined} disabled={locked} onClick={() => close(!canReviewPython && canCheckSavedBuild ? 'prepare-build' : undefined)}>
            {checkingSavedSource ? '正在核对这次保存…' : !canReviewPython && canCheckSavedBuild ? prepareBuildLabel||'接下来：核对构建条件' :
              returnsToCheckpoint ? (fromCheckpointComparison ? '已保存，返回原比较' : '已保存，返回原副本的文件') : '已保存，回到项目工作区'}
          </Button></> : !canReviewPython&&!pythonRunReview&&
            <Button disabled={locked || orphan !== null || blockedEdit.current || (!file.dirty && !canCreateEmpty) || file.conflict || file.saveUnknown}
              onClick={()=>saveFile(canCreateEmpty)}>{canCreateEmpty?'创建空文件':'保存到这个副本'}</Button>}
          {!offlinePreviewFirst&&staticPreviewAction}
        </div>}
        {choosingPackage&&pythonSource&&<section className="pw-checkout-adoption" aria-label="选择 Python 包源码">
          <h3 ref={packageHeading} tabIndex={-1}>入口：<bdi>{pythonSource.path}</bdi></h3>
          <p>只带入所选包里的已保存 .py 文件，包括 __init__.py。入口和所选源码合计最多 8 个文件、256 KiB。</p>
          {!packageDirectory?packageFolders.map(folder=><Button key={folder.path} variant="ghost" leadingIcon={IconFolder}
            disabled={busy||editing} onClick={()=>void visitDirectory(folder.path,0)}><bdi>{folder.path}</bdi></Button>):<>
            <h4><bdi>{packageDirectory}</bdi></h4>
            {packageFiles.map(row=>{
              const selected=pickedPackageFiles.includes(row);
              return <CheckboxField key={row.path} isSelected={selected} isDisabled={busy||editing||!selected&&
                (pickedPackageFiles.length>=7||packageBytes+row.bytes>262144)} onChange={checked=>{
                  if(!listedForPackage||entered.current||edits.current)return;
                  setPythonPackagePicker(previous=>{
                    if(!previous||previous!==choosingPackage)return previous;
                    const hits=previous.directoryHandle===listedForPackage.directoryHandle?previous.hits:[];
                    return {...previous,directoryHandle:listedForPackage.directoryHandle,
                      hits:checked?[...hits.filter(i=>i!==row.hit),row.hit]:hits.filter(i=>i!==row.hit)};
                  });
                }}><bdi>{row.path}</bdi></CheckboxField>;
            })}
            {!packageFiles.some(row=>row.path===packageDirectory+'/__init__.py')&&
              <p role="status">这页没有可选的 __init__.py，没有自动查找或读取其他文件。</p>}
            {pythonFolder!==undefined&&<Button variant="ghost" disabled={busy||editing}
              onClick={()=>void visitDirectory(pythonFolder,0)}>选择别的包</Button>}
          </>}
          <div className="pw-form-actions">
            <Button variant="secondary" disabled={busy||editing} onClick={()=>closePythonPackage()}>返回编辑</Button>
            <Button disabled={busy||editing||!packageReady} onClick={()=>readPythonReview('package')}>核对所选源码</Button>
          </div>
        </section>}
        {pythonRunReview && <section className="pw-checkout-adoption" aria-label="核对本机运行脚本">
          <h3 ref={pythonHeading} tabIndex={-1}>{pythonRunReview.packageDirectory?'Python 包入口：':pythonRunReview.programFiles?'同目录 Python 程序：':'在这部手机运行 '}<bdi>{pythonRunReview.path}</bdi>？</h3>
          {pythonRunReview.programFiles ? <>
            <p>{pythonRunReview.packageDirectory?'只带入下面已核对的入口与一个包的源码，保留包内路径，不带入其他文件或依赖。':
              '以选中的文件为入口。只带入以下同目录、已保存的 .py 文件，不带入子目录、数据文件或依赖。'}</p>
            <ul>{pythonRunReview.programFiles.map(input=><li key={input.path}><bdi>{pythonRunReview.packageDirectory?input.path:input.path.split('/').at(-1)}</bdi> · <code>{input.sha256.slice(0,8)}</code>{input.path===pythonRunReview.path?' · 入口':''}</li>)}</ul>
            <p>最多运行 60 秒，不需要模型。</p>
          </> : <p>仅使用这次已保存的脚本 <code>{pythonRunReview.sha256.slice(0,8)}</code>，最多运行 60 秒，不需要模型或安装依赖。</p>}
          <p>脚本可能联网，不保证限制其访问范围。只运行你信任的脚本。这里不挂载原副本，也不会导回运行生成的文件。</p>
          <div className="pw-form-actions"><Button variant="secondary" disabled={busy || editing} onClick={dismissPythonReview}>返回编辑</Button>
            <Button disabled={busy || editing || !canReviewPython} onClick={confirmSavedPython}>在本机运行</Button></div>
        </section>}
        {pythonSource && uncertainPythonSource===pythonSource.clickKey && <p role="status">已提交运行确认。若未进入原工作，请到「我的工作」核对。</p>}
        <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null || blockedEdit.current || file.dirty || file.saveUnknown}
          onClick={()=>void reviewImport(file)}>比较并采纳到正式项目…</Button></div>
        {adoptionPanel}
        {file.importStatus && <p role="status">{file.importReceipt?.message || file.importMessage} {file.importError || ''} {file.projectListRefreshMessage || ''}</p>}
        {!adoption && file.importStatus === 'observed' && formalLink(file.formalFile, 'import_receipt')}
        {file.message && <p role="status">{file.saveStatus==='observed' && recentSave?.path===file.path && !showingSavedFile && '上次保存：'}{file.message} {file.saveError || ''}</p>}
        {file.draftError && <p role="alert">{file.draftError}</p>}
        {(file.draftError || blockedEdit.current) && <div className="pw-form-actions"><Button variant="secondary" disabled={locked || orphan !== null} onClick={() => { if (blockedEdit.current) edit(currentText.current); else void invoke('cache', { fileHandle: file.fileHandle, retry: true }, value => apply(value, false)); }}>重试保留草稿</Button></div>}
        </>}
      </section></div>}
    </>}
    {!(finder==='changes'&&adoption)&&<Button variant="secondary" disabled={busy || editing} onClick={() => {
      if(checkpointSibling)closeCurrent.current();else if(!backFromChanges())close();
    }}>
      {finder==='changes'?changesBackLabel:adoption || choice ? '返回文件' : backLabel}
    </Button>}
  </div></div></SettingsDialog>;
}
