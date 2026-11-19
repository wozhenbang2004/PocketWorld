/** An app-local handoff, not a connection, request, or authority to run. */
export type CloudGoal = {
  readonly id: string;
  readonly text: string;
  readonly carriesTextOnly: boolean;
};
