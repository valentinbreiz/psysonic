import { IS_ANDROID } from './platform';

/**
 * Android WebView never fills `env(safe-area-inset-*)` for edge-to-edge
 * activities (enforced since targetSdk 35), so the status bar and gesture
 * area would overlap the layout. MainActivity.kt measures the real insets
 * and hands them over two ways: a `window.AndroidSafeArea` JavascriptInterface
 * the page pulls at startup, and a `window.__PSY_SAFE_AREA__` push on every
 * change (rotation, bar visibility). Both feed the `--safe-area-*` CSS
 * variables consumed by styles/layout/android-safe-area.css.
 */

type AndroidSafeAreaBridge = {
  /** "top right bottom left" in CSS px, e.g. "38.4 0 24 0". */
  insets(): string;
};

declare global {
  interface Window {
    AndroidSafeArea?: AndroidSafeAreaBridge;
    __PSY_SAFE_AREA__?: (insets: string) => void;
  }
}

const SIDES = ['top', 'right', 'bottom', 'left'] as const;

function applySafeAreaInsets(raw: string): void {
  const values = raw.trim().split(/\s+/).map(Number);
  if (values.length !== 4 || values.some(v => !Number.isFinite(v) || v < 0)) return;
  const style = document.documentElement.style;
  SIDES.forEach((side, i) => {
    style.setProperty(`--safe-area-${side}`, `${Math.round(values[i] * 100) / 100}px`);
  });
}

export function initAndroidSafeArea(): void {
  if (!IS_ANDROID) return;
  window.__PSY_SAFE_AREA__ = applySafeAreaInsets;
  try {
    const seed = window.AndroidSafeArea?.insets();
    if (seed) applySafeAreaInsets(seed);
  } catch {
    // Bridge missing (webview outside MainActivity) — variables stay 0px.
  }
}
