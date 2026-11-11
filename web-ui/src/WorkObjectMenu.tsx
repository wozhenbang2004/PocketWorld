import { Button, IconButton } from './forms-library';
import { Popover, PopoverTrigger } from './shell-library';
import { IconChat, IconFile, IconFolder, IconMore, IconShieldCheck } from './icons';

type Props = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  disabled: boolean;
  conversationPending: boolean;
  scopeDisabled: boolean;
  onConversation?: () => void;
  onProject?: () => void;
  onFiles?: () => void;
  onScope?: () => void;
};

/** Adapts the retained ChatNavigationMenu composition over the actual BoardUI
 * Popover/Button sources. These entries navigate within the current work;
 * execution, decisions and stop keep their original visible controls. */
export function WorkObjectMenu({ isOpen, onOpenChange, disabled, conversationPending, scopeDisabled,
  onConversation, onProject, onFiles, onScope }: Props) {
  if (!onConversation && !onProject && !onFiles && !onScope) return null;
  const choose = (action: () => void) => { onOpenChange(false); if (!disabled) action(); };
  return <PopoverTrigger isOpen={isOpen && !disabled} onOpenChange={onOpenChange}>
    <IconButton icon={IconMore} aria-label="工作资料与来源" title="工作资料与来源" disabled={disabled} />
    <Popover aria-label="工作资料与来源" placement="bottom end" className="pw-work-object-menu" dialogClassName="pw-work-object-menu-dialog">
      {(onFiles || onScope) && <div className="pw-work-object-menu-actions">
        {onFiles && <Button variant="ghost" leadingIcon={IconFile} onClick={() => choose(onFiles)}>文件与成果</Button>}
        {onScope && <Button variant="ghost" leadingIcon={IconShieldCheck} disabled={scopeDisabled} onClick={() => choose(onScope)}>执行范围与预算</Button>}
      </div>}
      {(onConversation || onProject) && <div className="pw-work-object-menu-actions" aria-label="工作来源">
        {onConversation && <Button variant="ghost" leadingIcon={IconChat} disabled={conversationPending} onClick={() => choose(onConversation)}>{conversationPending ? '正在返回所属对话' : '回到所属对话'}</Button>}
        {onProject && <Button variant="ghost" leadingIcon={IconFolder} onClick={() => choose(onProject)}>查看所属项目</Button>}
      </div>}
    </Popover>
  </PopoverTrigger>;
}
