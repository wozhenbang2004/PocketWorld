import { Button, IconButton } from './forms-library';
import { IconArrowDown, IconClose, IconReturn } from './icons';
import './conversation-reading-nav.css';

type Props = { showLatest: boolean; unread: boolean; canReturn: boolean; oneWay: boolean; notice: string;
  viewLatest: () => void; returnToPlace: () => void; dismissReturn: () => void };

/** The existing composer-edge reading affordance, not a second toolbar. */
export function ConversationReadingNav({ showLatest, unread, canReturn, oneWay, notice, viewLatest, returnToPlace, dismissReturn }: Props) {
  if (!showLatest && !canReturn && !notice) return null;
  return <div className="pw-new-replies pw-reading-nav" role="group" aria-label="对话阅读位置" data-notice={!!notice || undefined}>
    {notice && <span className="pw-reading-notice" role="status">{notice}</span>}
    {(canReturn || showLatest || oneWay) && <Button variant="ghost" size="small" className="pw-reading-jump"
      leadingIcon={canReturn ? IconReturn : IconArrowDown} onClick={canReturn ? returnToPlace : viewLatest}>
      {canReturn ? '回到刚才读的位置' : oneWay ? '只查看最新' : unread ? '有新回复 · 查看最新' : '查看最新'}
    </Button>}
    {(canReturn || !!notice) && <IconButton size="small" className="pw-reading-dismiss" icon={IconClose}
      aria-label={canReturn ? '收起返回入口，留在当前位置' : '关闭阅读提示'} onClick={dismissReturn}/>}
  </div>;
}
