/** True when running inside the Android WebView (Tauri mobile). Checked before
 * IS_LINUX because Android reports `navigator.platform` as "Linux armv8l". */
export const IS_ANDROID = navigator.userAgent.includes('Android');
/** True when running on iOS (WKWebView on iPhone/iPad). */
export const IS_IOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
/** True on any Tauri mobile platform — used to skip desktop-only integrations
 * (self-updater, tray, global shortcuts, window state). */
export const IS_MOBILE_PLATFORM = IS_ANDROID || IS_IOS;
/** True when running on Linux (WebKitGTK). Used to show the custom title bar. */
export const IS_LINUX = navigator.platform.toLowerCase().includes('linux') && !IS_ANDROID;
/** True when running on macOS (WKWebView). */
export const IS_MACOS = navigator.platform.toLowerCase().includes('mac') && !IS_IOS;
/** True when running on Windows (WebView2). */
export const IS_WINDOWS = navigator.platform.toLowerCase().includes('win');
