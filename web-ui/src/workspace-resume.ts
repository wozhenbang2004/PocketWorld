type Data = Record<string, any>;

/** A task sheet can outlive the SSH file revision. Keep the draft tied to its original base. */
export function resumeSshFile(original: Data, draft: string, received: Data, profileId: string, conflicted = false) {
  if (received.status !== 'completed' || received.profileId !== profileId || received.path !== original.path
    || typeof received.content !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(received.version || '')) {
    throw new Error('尚未重新读到这个原文件的有效版本，草稿保留，请重新核对。');
  }
  const dirty = draft !== original.content;
  return {
    file: received,
    content: dirty ? draft : received.content,
    conflict: dirty && (conflicted || original.version !== received.version),
  };
}
