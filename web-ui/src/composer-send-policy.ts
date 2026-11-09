/** Presentation-only payload detection; the native bridge independently revalidates the draft and attachment identities. */
export function composerHasPayload(text: string, attachmentCount: number): boolean {
  return text.trim().length > 0 || Number.isSafeInteger(attachmentCount) && attachmentCount > 0;
}
