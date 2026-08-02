import { IS_MOBILE_PLATFORM } from '@/lib/util/platform';
import { flushPlayQueuePosition } from '@/features/playback/store/queueSync';

/**
 * Mobile: flush the play-queue position to the server when the app goes to
 * the background. Desktop persists on its orderly exit path (close handler),
 * but Android has no such moment — the OS may freeze or kill the process any
 * time after the activity is hidden. `visibilitychange → hidden` fires from
 * the WebView's onPause and is the last guaranteed hook, narrowing the resume
 * loss after a process death to ~0 s (the 15 s playback heartbeat covers a
 * kill that happens mid-playback later on). Returns a cleanup function.
 */
export function setupMobileLifecycleSync(): () => void {
  if (!IS_MOBILE_PLATFORM) return () => {};
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') void flushPlayQueuePosition();
  };
  document.addEventListener('visibilitychange', onVisibility);
  return () => document.removeEventListener('visibilitychange', onVisibility);
}
