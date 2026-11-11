import { useRef } from 'react';
import { GrokBotView } from './library/upstream/grokbot/react';
import { useVisible } from './library/core';
import { teamAvatarShape } from './team-identity';

type Member = Record<string, any>;
const theme = { bodyColor: '#dddddd', eyeColor: '#242424', guideColor: '#888888', centroidColor: '#444444', badgeColor: '#555555', particleColor: '#555555' };

/** PW-01: the adopted GrokBot 616c1dd source, with persisted member shape instead of slot order. */
export function TeamMemberAvatar({ member, size = 32 }: { member: Member; size?: number }) {
  const root = useRef<HTMLSpanElement>(null), visible = useVisible(root);
  return <span ref={root} className="pw-agent-avatar pw-team-avatar" style={{ width: size, height: size }} aria-hidden="true">
    <GrokBotView state="idle" shape={teamAvatarShape(member)} size={size} autoBlink={visible} autoExpression={false} theme={theme} respectReducedMotion label="" />
  </span>;
}

export function TeamMemberIdentity({ member, name, detail, size = 32 }: { member: Member; name?: string; detail?: string; size?: number }) {
  return <span className="pw-team-identity"><TeamMemberAvatar member={member} size={size} /><span className="pw-team-identity-copy"><span>{name || member.name || '未命名成员'}</span>{detail && <small>{detail}</small>}</span></span>;
}
