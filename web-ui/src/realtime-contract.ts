type Data = Record<string, any>;
const object = (value: any): Data => value && typeof value === 'object' && !Array.isArray(value) ? value : {};

/** Receipts and pushed native events share one monotonic original-session snapshot. */
export function mergeRealtimeSnapshot(previous: Data, incoming: Data): Data {
  return Number.isSafeInteger(incoming.snapshotRevision) && incoming.snapshotRevision >= 0 &&
    (!Number.isSafeInteger(previous.snapshotRevision) || incoming.snapshotRevision >= previous.snapshotRevision) ? incoming : previous;
}

/** Presentation checks only; the native owner repeats identity and revision checks on use. */
export function realtimeReviewCurrent(snapshot: Data, review: Data | null, now = Date.now()): boolean {
  if (!review?.reviewHandle || !review.command || !Number.isFinite(review.expiresAt) || review.expiresAt <= now) return false;
  const details = object(review.details), invitation = object(snapshot.invitation), task = object(snapshot.voiceTask);
  if (review.command === 'acceptInvitation') return snapshot.invitationState === 'awaiting_owner' && details.id === invitation.id && invitation.expiresAt > now &&
    (!details.microphone || snapshot.microphonePermission === true) && (!details.camera || snapshot.cameraPermission === true);
  if (['configure', 'configureNaturalVoice', 'configureEndpointProvider', 'clearNaturalVoice', 'clearEndpointProvider'].includes(review.command)) return details.configurationRevision === (object(snapshot.configuration).revision ?? 0);
  if (['mute', 'setCamera'].includes(review.command)) return details.sessionId === snapshot.sessionId && object(snapshot.session).state === 'connected';
  if (['startVoiceTask', 'readVoiceTask', 'cancelVoiceTask'].includes(review.command)) return details.bindingId === task.bindingId && details.viewToken === (review.command === 'cancelVoiceTask' ? task.cancelToken : task.viewToken);
  if (['commitTurn', 'commitVoiceTask'].includes(review.command)) {
    const transcript = object(snapshot.transcription), turns: Data[] = Array.isArray(transcript.turns) ? transcript.turns : [];
    return details.sessionId === snapshot.sessionId && details.expiresAt > now && transcript.commitAvailable === true && transcript.reviewReady !== false &&
      turns.some(turn => turn.id === details.turnId && turn.revision === details.revision && turn.text === details.text && turn.isFinal === true && turn.canReview === true);
  }
  return review.command === 'startVoiceTurn';
}

export function realtimeStage(value: string): string {
  return ({ idle: '尚未通话', connecting: '正在连接', connected: '已连接', reconnecting: '正在恢复连接', disconnecting: '正在结束', disconnected: '已断开', failed: '连接未成功', stopped: '已停止', recording: '正在听写', listening: '正在听写', recognizing: '正在识别', thinking: '正在生成回复', speaking: '正在朗读', completed: '本轮已结束', cancelled: '已停止', error: '本轮未完成' } as Data)[value] || value || '尚未读取';
}
