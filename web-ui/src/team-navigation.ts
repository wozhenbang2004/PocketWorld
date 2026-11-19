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
  teamId?: string;
  goal?: string;
};

export type TeamRunFocus = { requestId: string; runId: string };

export type TeamDecisionOrigin = { parentTaskId: string; parentRunId: string; parentEpoch: number; conversationId: string; teamRunId: string };
export const teamDecisionEntryKey = (source: TeamDecisionOrigin) => JSON.stringify(['team-decisions',source.conversationId,source.parentTaskId,source.parentRunId,source.parentEpoch,source.teamRunId]);
