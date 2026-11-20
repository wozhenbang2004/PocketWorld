import { Button, IconButton } from './forms-library';
import { IconArrowDown, IconClose, IconReturn } from './icons';
import './conversation-reading-nav.css';

type Props = { showLatest: boolean; unread: boolean; canReturn: boolean; oneWay: boolean; notice: string;
  viewLatest: () => void; returnToPlace: () => void; dismissReturn: () => void };

/** The existing composer-edge reading affordance, not a second toolbar. */
export function ConversationReadingNav({ showLatest, unread, canReturn, oneWay, notice, viewLatest, returnToPlace, dismissReturn }: Props) {
  if (!showLatest && !canReturn && !notice) return null;
  const latest = oneWay || (showLatest && (!canReturn || unread));
  const returnLabel = '回到刚才读的位置';
  return <div className="pw-new-replies pw-reading-nav" role="group" aria-label="对话阅读位置" data-notice={!!notice || undefined}>
    {notice && <span className="pw-reading-notice" role="status">{notice}</span>}
    {latest && <Button key="latest" variant="ghost" size="small" className="pw-reading-jump" leadingIcon={IconArrowDown} onClick={viewLatest}>
      {oneWay ? '只查看最新' : unread ? '有新回复 · 查看最新' : '查看最新'}
    </Button>}
    {canReturn && <Button key="return" variant="ghost" size="small" className={latest ? 'pw-reading-return' : 'pw-reading-jump'}
      leadingIcon={IconReturn} iconOnly={latest} aria-label={returnLabel} title={returnLabel} onClick={returnToPlace}>
      {returnLabel}
    </Button>}
    {(canReturn || !!notice) && <IconButton size="small" className="pw-reading-dismiss" icon={IconClose}
      aria-label={canReturn ? '收起返回入口，留在当前位置' : '关闭阅读提示'} onClick={dismissReturn}/>}
  </div>;
}
