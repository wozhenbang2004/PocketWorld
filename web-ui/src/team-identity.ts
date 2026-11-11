type Member = Record<string, any>;
export const teamAvatarShapes = ['blob', 'pebble', 'squircle', 'egg'] as const;
export type TeamAvatarShape = typeof teamAvatarShapes[number];

/** Presentation only: a name or avatar never grants a member an identity or authority. */
export function teamAvatarShape(member: Member): TeamAvatarShape {
  if (teamAvatarShapes.includes(member.avatarShape)) return member.avatarShape;
  let seed = 0;
  for (const character of String(member.id || member.memberId || 'team')) seed = (seed * 31 + character.charCodeAt(0)) >>> 0;
  return teamAvatarShapes[seed % teamAvatarShapes.length];
}

export function teamMemberLabel(member: Member, roster: Member[]): string {
  const name = String(member.name || '未命名成员');
  return roster.filter(row => row.name === member.name).length > 1 ? `${name} · ${member.id}` : name;
}

/** Historical messages use their captured identity; user/parent messages are not the recipient. */
export function teamMessageIdentity(message: Member, roster: Member[]): Member | null {
  if (message.role === 'user' || message.role === 'parent_model') return null;
  if (message.memberSnapshot?.id) return message.memberSnapshot;
  if (message.name && message.memberId) return { id: message.memberId, name: message.name, avatarShape: message.avatarShape };
  return roster.find(member => member.id === message.memberId) || null;
}

export function teamMessageAuthor(message: Member, roster: Member[]): string {
  if (message.role === 'user') return '你';
  if (message.role === 'parent_model') return '主助手';
  return teamMessageIdentity(message, roster)?.name || '团队';
}
