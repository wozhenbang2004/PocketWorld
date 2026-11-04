/** Presentation identities only. The host remains the authority for revision adoption and saving. */
export type FileLayer = 'edit' | 'diff' | 'history';
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
