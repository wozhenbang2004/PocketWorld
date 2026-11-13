import { createContext, useCallback, useContext, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react';
import { IconChevronRight, IconClose, IconModel } from './icons';
import { Button, IconButton } from './forms-library';
import { Popover } from './shell-library';
import { AIModelPicker, type AIModelPickerPresentation, type AIModelTarget } from './AIModelPicker';
import { ConversationModelControls } from './ConversationModelControls';
import { ScopedConversationReasoningPanel } from './ScopedConversationModelControls';
import { conversationSelectionReceipt, conversationSelectionRequest } from './conversation-model-selection';

type Row = Record<string, any>;
type Section = 'models' | 'reasoning';
type Run = (action: string, args?: Row) => Promise<any>;
export type ConversationModelPickerHandle = {
  open: (section?: Section, anchor?: HTMLButtonElement) => void;
  handleBack: () => boolean;
};
type PickerContext = {
  state: Row; can: (action: string) => boolean; act: Run;
  disabled: boolean; channelPhoneModel: boolean; section: Section | null;
  open: ConversationModelPickerHandle['open'];
  registerBack: (handler: (() => boolean) | null) => void;
};
const ControlsContext = createContext<PickerContext | null>(null);
const VisibilityContext = createContext(false);
const busy = (state: Row) => !!(state.chatSubmitting || state.phoneSubmitting || state.modelSaving || state.modelTesting || state.modelSelectionBusy);

/** MangaFlow CompactGlassPopover correspondence: a real trigger anchor, bounded
 * scrolling foreground, retained exit content and input gating. React Aria owns
 * placement/focus; this is deliberately not a second replica of the trigger or
 * a claimed Haze surface morph. App's children stay intact. */
export function ConversationModelPickerProvider({ ref, state, can, act, disabled, active, channelPhoneModel, onChannelModel, onManage, onOpenMedia, children }: {
  ref: Ref<ConversationModelPickerHandle>;
  state: Row; can: (action: string) => boolean; act: Run;
  disabled: boolean; active: boolean; channelPhoneModel: boolean;
  onChannelModel: () => void; onManage: () => void; onOpenMedia?: () => void; children: ReactNode;
}) {
  const [view, setView] = useState({ open: false, section: 'models' as Section, token: 0 });
  const [pending, setPending] = useState(false), [confirmed, setConfirmed] = useState(false);
  const [present, setPresent] = useState(false);
  const [presentation, setPresentation] = useState<AIModelPickerPresentation>({ query: '', filter: 'all' });
  const session = useRef({ open: false, token: 0, writing: false });
  const anchor = useRef<HTMLButtonElement>(null), body = useRef<HTMLDivElement>(null);
  const legacyBack = useRef<(() => boolean) | null>(null);
  const latest = useRef({ state, disabled, active, channelPhoneModel, onChannelModel, onManage, onOpenMedia, can, act });
  latest.current = { state, disabled, active, channelPhoneModel, onChannelModel, onManage, onOpenMedia, can, act };
  const owner = useRef(state.conversationModel?.scopeKey);

  const finish = useCallback(() => {
    session.current.open = false; session.current.token++; session.current.writing = false;
    // Keep this presentation/key until Aria actually removes the exiting body.
    setView(previous => previous.open ? { ...previous, open: false } : previous);
    setPending(false); setConfirmed(false);
  }, []);
  const close = useCallback(() => {
    if (!session.current.writing && !latest.current.state.modelSelectionBusy) finish();
  }, [finish]);
  const open = useCallback((section: Section = 'models', trigger?: HTMLButtonElement) => {
    const current = latest.current;
    if (current.channelPhoneModel) { current.onChannelModel(); return; }
    if (!current.active || current.disabled || busy(current.state) || session.current.open || session.current.writing) return;
    const button = trigger || document.querySelector<HTMLButtonElement>(section === 'models' ? '.pw-conversation-model-chip' : '.pw-conversation-reasoning-chip');
    if (!button?.isConnected) return;
    anchor.current = button;
    if (document.activeElement !== button) button.focus({ preventScroll: true });
    const token = ++session.current.token; session.current.open = true;
    setConfirmed(false); setView({ open: true, section, token });
  }, []);
  const registerBack = useCallback((handler: (() => boolean) | null) => { legacyBack.current = handler; }, []);
  useImperativeHandle(ref, () => ({
    open,
    handleBack: () => {
      if (legacyBack.current?.()) return true;
      if (!session.current.open) return false;
      close(); return true;
    },
  }), [open, close]);

  useLayoutEffect(() => {
    if (owner.current !== state.conversationModel?.scopeKey) {
      finish(); setPresentation({ query: '', filter: 'all' });
    } else if (!active && session.current.open) finish();
    owner.current = state.conversationModel?.scopeKey;
  }, [state.conversationModel?.scopeKey, active, finish]);
  useLayoutEffect(() => {
    if (view.open && !session.current.writing && !pending && !state.modelSelectionBusy && (confirmed || channelPhoneModel)) finish();
  }, [view.open, pending, confirmed, state.modelSelectionBusy, channelPhoneModel, finish]);

  const currentWindow = () => session.current.open && session.current.token === view.token;
  const selectionPending = (value: boolean) => {
    if (!currentWindow()) return;
    session.current.writing = value; setPending(value);
  };
  const selected = () => { if (currentWindow()) setConfirmed(true); };
  const selectTarget = async (target: AIModelTarget) => {
    if (!currentWindow()) throw new Error('原模型窗口已关闭，请重新打开。');
    const current = latest.current, request = conversationSelectionRequest(target, current.state);
    if (!current.can(request.action)) throw new Error('当前应用尚未接通此对话的模型选择，请升级后再试。');
    const receipt = await current.act(request.action, request.args);
    conversationSelectionReceipt(receipt, request.scopeKey, request.previousRevision);
    return receipt;
  };
  const manage = () => {
    if (!currentWindow() || session.current.writing || latest.current.state.modelSelectionBusy) return;
    // The caller captures its workspace/focus origin before leaving this layer.
    latest.current.onManage(); finish();
  };
  const openMedia = () => {
    if (!currentWindow() || session.current.writing || latest.current.state.modelSelectionBusy) return;
    latest.current.onOpenMedia?.(); finish();
  };
  const controls = useMemo<PickerContext>(() => ({
    state, can, act, disabled: disabled || busy(state) || pending, channelPhoneModel,
    section: view.open ? view.section : null, open, registerBack,
  }), [state, can, act, disabled, pending, channelPhoneModel, view.open, view.section, open, registerBack]);

  return <ControlsContext.Provider value={controls}><VisibilityContext.Provider value={view.open || present}>
    {children}
    <Popover triggerRef={anchor} isOpen={view.open} onOpenChange={value => { if (!value) close(); }}
      placement="top start" offset={8} shouldFlip containerPadding={16} maxHeight={view.section === 'models' ? 560 : 480}
      scrollRef={body} isKeyboardDismissDisabled={pending || !!state.modelSelectionBusy} motionProfile="quick"
      aria-label={view.section === 'models' ? '选择此对话的模型' : '选择此对话的推理强度'}
      className={`pw-conversation-picker-popover pw-material-panel ${view.section === 'models' ? 'pw-conversation-model-popover' : 'pw-conversation-reasoning-popover'}`}
      dialogClassName="pw-conversation-picker-dialog">
      <PickerPresence onChange={setPresent}>
        <header className="pw-conversation-picker-header" inert={!view.open}>
          <h2>{view.section === 'models' ? '选择模型' : '推理强度'}</h2>
          <IconButton icon={IconClose} aria-label={view.section === 'models' ? '关闭模型选择' : '关闭推理设置'} onClick={close} disabled={pending || !!state.modelSelectionBusy} />
        </header>
        <div ref={body} className="pw-conversation-picker-body" inert={!view.open}>
          {view.section === 'models'
            ? <AIModelPicker key={`models:${view.token}`} state={state} can={can} act={act} scope="conversation" active={view.open}
                onSelectTarget={selectTarget} onSelectionPending={selectionPending} onSelected={selected}
                presentationState={presentation} onPresentationChange={setPresentation} onManage={manage} onOpenMedia={onOpenMedia ? openMedia : undefined} />
            : can('models.conversation.review') && <ScopedConversationReasoningPanel key={`reasoning:${view.token}`} state={state} can={can} act={act}
                active={view.open} onSelectionPending={selectionPending} onSelected={selected} />}
        </div>
        {state.chatBusy && <p className="pw-model-selection-running">当前任务继续使用启动时的模型。</p>}
      </PickerPresence>
    </Popover>
  </VisibilityContext.Provider></ControlsContext.Provider>;
}

function PickerPresence({ onChange, children }: { onChange: (present: boolean) => void; children: ReactNode }) {
  useLayoutEffect(() => { onChange(true); return () => onChange(false); }, [onChange]);
  return children;
}

export function ConversationModelPickerControls() {
  const value = useContext(ControlsContext)!;
  return value.channelPhoneModel
    ? <div className="pw-conversation-model-controls" aria-label="渠道手机任务模型"><Button variant="ghost" className="pw-conversation-model-chip" disabled aria-label={`渠道默认模型：${value.state.modelName || '未选择'}`} title={`渠道默认模型：${value.state.modelName || '未选择'}`}><IconModel size={19} aria-hidden="true" /><span className="pw-conversation-model-chip-name">渠道默认 · {value.state.modelName || '未选择模型'}</span></Button></div>
    : <ConversationModelControls state={value.state} can={value.can} act={value.act} onOpenModel={value.open} pickerSection={value.section} disabled={value.disabled} registerBackHandler={value.registerBack} />;
}

export function ConversationModelStartButton() {
  const value = useContext(ControlsContext)!;
  return <Button variant="primary" className="pw-start-model" leadingIcon={IconModel} trailingIcon={IconChevronRight}
    disabled={value.disabled} aria-haspopup="dialog" aria-expanded={value.section === 'models'} onClick={() => value.open('models')}>
    {value.channelPhoneModel ? '连接渠道默认模型' : '选择模型'}
  </Button>;
}

/** Native preview consumers update without sending local picker state through App. */
export function ConversationModelPickerVisibility({ children }: { children: (visible: boolean) => ReactNode }) {
  return children(useContext(VisibilityContext));
}
