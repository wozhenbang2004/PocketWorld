/** Native-verified links keep collaboration in the work that requested it. */
export type TeamOwnerWorkLink = {
  taskId: string;
  runId: string;
  epoch: number;
  conversationId: string;
  goal: string;
  currentRun: boolean;
  readOnly: true;
};

export type TeamButlerRequest = {
  goal?: string;
};

export type TeamRunFocus = { requestId: string; runId: string };
