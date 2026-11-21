/** Presentation identities only. The host remains the authority for revision adoption and saving. */
export type FileLayer = 'edit' | 'diff' | 'history';
export type ContinueAdoptedFormal = { scope: string; start: () => boolean };
export type RevisionAdoption = { projectId: string; path: string; version: number; revision: number; content: string; draft: string; editRevision: number; documentEpoch: number; resumeRevision: number; navigationRevision: number };
export function fileWorkspaceKey(projectId: unknown, path: unknown): string {
  return typeof projectId === 'string' && projectId && typeof path === 'string' && path ? JSON.stringify([projectId, path]) : '';
}
export function revisionWindowMatches(original: RevisionAdoption, current: RevisionAdoption): boolean {
  return (Object.keys(original) as (keyof RevisionAdoption)[]).every(key => original[key] === current[key]);
}
export function revisionSourceSnapshot(original: RevisionAdoption, value: Record<string, any>): boolean {
  return value.projectId === original.projectId && value.filePath === original.path && value.fileReady === true &&
    value.fileVersion === original.version && value.revisionNumber === original.revision && value.revisionContent === original.content;
}
export function adoptedRevisionSnapshot(original: RevisionAdoption, value: Record<string, any>): boolean {
  return revisionSourceSnapshot(original, value) && value.fileContent === original.content;
}

export function observedAdoptedFormalText(state: Record<string, any>, path: string, version: number) {
  const source = state.adoptedFormalComparison;
  // Only the already loaded native result; never a catalog head or a draft.
  if (source?.projectVersionCommitted !== true || source.evidence !== 'import_receipt' ||
      state.fileReady !== true || state.fileLoading || state.fileSaving || source.projectId !== state.projectId ||
      state.filePath !== path || source.path !== path || source.version !== version || state.fileVersion !== version ||
      typeof source.checkoutId !== 'string' || !/^[a-f0-9-]{36}$/.test(source.checkoutId) ||
      typeof source.revisionId !== 'string' || !/^[a-f0-9-]{36}$/.test(source.revisionId) ||
      typeof source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(source.sha256) ||
      !Number.isSafeInteger(source.bytes) || source.bytes < 0 || source.bytes > 262144 ||
      typeof state.fileBaseline !== 'string' || state.fileContent !== state.fileBaseline) return;
  return {
    identity: JSON.stringify([source.projectId, source.checkoutId, path, version, source.revisionId, source.sha256, source.bytes]),
    text: state.fileBaseline as string,
  };
}
