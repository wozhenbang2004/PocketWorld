import { useSyncExternalStore } from 'react';
import { bridge } from './bridge';

/** React rendering observes the same protocol store used by the native bridge. */
export function usePocketSnapshot() {
  return useSyncExternalStore(bridge.subscribe, bridge.getState, bridge.getState);
}
