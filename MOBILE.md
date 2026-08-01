# Psysonic on mobile (Android PoC)

Status: **proof of concept** — the app compiles for Android and runs as a debug APK.
Tracking issue: [#1374](https://github.com/Psychotoxical/psysonic/issues/1374).

The Rust core (rodio + symphonia audio engine, library index, sync, Orbit over the
Subsonic API) is shared with desktop unchanged. Desktop-only integrations are gated
behind Tauri's `cfg(desktop)` / `cfg(mobile)` flags:

| Area | Mobile behaviour |
| --- | --- |
| System tray (`tray_runtime`, `sync::tray`) | stubbed no-ops (`src/tray_runtime_mobile.rs`, `sync/tray_mobile.rs`) |
| Global shortcuts, window-state, updater, process, single-instance plugins | not compiled in (target-gated in `Cargo.toml`) |
| souvlaki OS media controls (MPRIS/SMTC) | not compiled in — MediaSession integration is a follow-up |
| Mini player window | commands are no-ops (single-activity platform) |
| Self-update prompt (frontend) | skipped via `IS_MOBILE_PLATFORM` |

Webview capabilities for mobile live in `src-tauri/capabilities/mobile.json`
(`default.json` stays desktop-only via its `platforms` list).

Two integration quirks worth knowing (both handled in the android block at the
top of `run()`):

- Nothing in the Tauri stack (tao/wry) fills the `ndk-context` statics that
  cpal's AAudio host reads to reach the Java `AudioManager` — without help the
  app aborts at startup with *"android context was not initialized"*. `run()`
  bridges tao's JNI context into `ndk-context` before the audio engine starts.
- reqwest's rustls backend verifies certificates through
  `rustls-platform-verifier`, which on Android calls into the system trust
  store via Kotlin classes (`org.rustls.platformverifier.*`). Those classes
  ship as an AAR inside the `rustls-platform-verifier-android` crate; the
  Gradle project in `gen/android` resolves that crate's bundled maven repo via
  `cargo metadata` and depends on it, and `run()` hands the verifier the JVM +
  activity context once at startup. Without either half, every Rust-side HTTPS
  request (cover art, audio streaming, scrobbling) fails with *"Expect
  rustls-platform-verifier to be initialized"* — while login still works,
  because the webview's `fetch` uses the system WebView network stack.

Because the Gradle project now carries real configuration (the verifier AAR,
`minSdkVersion`, and later a MediaSession foreground service), `gen/android`
is committed rather than regenerated — do **not** delete it and re-run
`tauri android init` without re-applying those changes.

## Building the Android app

Prerequisites: Android SDK + NDK, JDK 17+, Rust Android targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_HOME="$HOME/Android/Sdk"
export NDK_HOME="$ANDROID_HOME/ndk/<version>"
# opusic-sys builds libopus with CMake, which looks for these two —
# NDK_HOME/ANDROID_HOME alone are not enough:
export ANDROID_NDK_ROOT="$NDK_HOME"
export ANDROID_NDK="$NDK_HOME"
```

The Gradle project in `src-tauri/gen/android` is committed (see above), so no
init step is needed. Build (or `npx tauri android dev` with a device/emulator
attached):

```sh
npx tauri android build --apk --target aarch64 --debug
```

The APK lands in `src-tauri/gen/android/app/build/outputs/apk/`.

## Known gaps (next steps)

- **Background playback**: needs an Android foreground service + MediaSession
  (notification with play/pause/next, audio focus, headset events). Until then,
  playback stops when the app is backgrounded long enough for the OS to suspend it.
- **Media controls**: the `mpris_*` commands are no-ops on mobile; wire them to
  MediaSession / MPNowPlayingInfoCenter.
- **Storage paths**: library index / Hot Cache land in the app-private dir; sizes
  and eviction defaults were tuned for desktop disks.
- **UI**: the responsive/mobile layouts exist but were designed for narrow desktop
  windows, not touch — expect rough edges (hover menus, drag interactions).
- **iOS**: untested; needs a macOS host and an Apple developer account.
- **CI / release**: no Android build in CI yet, APK is debug-signed only.
