/** An app-local handoff, not a connection, request, or authority to run. */
export type CloudGoal = {
  readonly id: string;
  readonly text: string;
  /** Ephemeral native pointer. Neither a conversation ID nor a Companion field. */
  readonly originHandle?: string;
  readonly carriesTextOnly: boolean;
  readonly savedTarget?: { readonly profileId:string; readonly profileBinding:string };
};
