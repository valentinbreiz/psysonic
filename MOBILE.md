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
`minSdkVersion`, the MediaSession foreground service under
`app/src/main/java/dev/psysonic/player/media/`), `gen/android` is committed
rather than regenerated — do **not** delete it and re-run `tauri android init`
without re-applying those changes.

## Audio lifecycle on Android

rodio + cpal + Symphonia compile for Android and play audio, but that alone is
nowhere near a usable player: without further work the OS freezes the whole
process shortly after the app is backgrounded (Android 11+ cached-app freezer —
the audio callback thread stops mid-buffer), every headphone/Bluetooth event
kills the AAudio stream silently, other apps can't take audio focus from us and
we don't yield to them, and there are no lockscreen/notification controls. The
pieces below close those gaps. The Rust engine itself is unchanged — everything
here is lifecycle plumbing around it.

### Architecture

The desktop already has a complete OS-media-controls contract:

- frontend → Rust: `mpris_set_metadata` / `mpris_set_playback` commands
  (pushed by `mprisSync.ts` on track change, play/pause, and a ≤1.5 s position
  tick), handled by souvlaki (MPRIS/SMTC) on desktop;
- Rust → frontend: `media:play`, `media:pause`, `media:next`, `media:prev`,
  `media:seek-absolute`, … events, consumed by `useMediaAndWindowBridge`.

Android reuses that contract unchanged and swaps souvlaki for a Kotlin
`MediaSession`:

```
mprisSync.ts ──mpris_set_*──▶ media_session_android.rs ──JNI──▶ MediaBridge.kt
                                                                    │
                                              MediaControlService (foreground)
                                              MediaSession, notification,
                                              audio focus, noisy receiver,
                                              route watcher
                                                                    │
useMediaAndWindowBridge ◀──media:* events── nativeMediaEvent ◀──────┘
```

- `src-tauri/src/media_session_android.rs` — JNI in both directions. Outgoing
  calls resolve the `MediaBridge` class once through the activity's
  classloader (`FindClass` from native threads can't see app classes).
  Incoming events arrive through the exported
  `Java_dev_psysonic_player_media_MediaBridge_nativeMediaEvent` symbol — the
  JVM finds it in the already-loaded Rust cdylib when Kotlin declares the
  matching `external fun`, no `RegisterNatives` needed.
- `gen/android/…/media/MediaBridge.kt` — static bridge + last-pushed state
  (the service start intent races the first push).
- `gen/android/…/media/MediaControlService.kt` — foreground service
  (`mediaPlayback` type) owning the platform `MediaSession` (no androidx.media
  dependency), the MediaStyle notification, audio focus, the becoming-noisy
  receiver, the output-route watcher, and a partial wakelock + wifi lock while
  playing.

### Background playback

The foreground service's real job is keeping the process out of the cached-app
freezer. Everything else already works when the process stays alive: the audio
pipeline is native threads, and track advance is *event-driven* JS — gapless
successors are swapped inside Rust (`audio:track_switched`), and end-of-queue
handling reacts to `audio:ended`. A backgrounded WebView throttles **timers**,
but Tauri **events** still run immediately, so the queue logic keeps working
with the screen off. The service starts on the first play (while the app is
foreground, so `startForegroundService` is allowed) and drops out of the
foreground state on pause, leaving a swipeable notification.

`onStartCommand` returns `START_NOT_STICKY` deliberately: if the process dies,
the Rust engine and the queue died with it, and a resurrected empty service
could not resume anything (see Recovery below).

### Audio focus, interruptions, headphones

| Event | Behaviour |
| --- | --- |
| focus `LOSS` (another player started) | pause, stay paused |
| focus `LOSS_TRANSIENT` (call, assistant, navigation prompt) | pause, resume on `GAIN` |
| focus `LOSS_TRANSIENT_CAN_DUCK` | nothing — API 26+ ducks `USAGE_MEDIA` streams automatically |
| focus request denied on play (active call) | the play is reverted with an explicit pause |
| `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged, BT dropped) | explicit pause — never let the OS re-route to the speaker |
| output device added/removed (`AudioDeviceCallback`) | `route-changed` → Rust reopens the output stream |

Pause events are explicit `media:pause`, never toggle — same reasoning as the
desktop macOS route-change handling (#1094): a toggle would *resume* paused
playback on the new device.

The route-change handling matters more than it looks: AAudio invalidates the
active stream on **every** route change (plug headphones in while playing on
the speaker → silence, not a switch). `route-changed` lands in
`reopen_output_stream` — the exact path a desktop device switch takes — which
reopens CPAL on the new default route and replays the current track at
position via `try_resume_after_device_change`, falling back to the frontend
`audio:device-changed` restart when the source isn't cached. Removals are
debounced 500 ms so the becoming-noisy pause lands first and the reopen does
not seamlessly resume onto the speaker.

### Media controls

Lockscreen / Quick Settings / notification / hardware buttons all drive the
`MediaSession` callbacks, which forward to the same `media:*` events desktop
media keys produce. Position and duration come from the ≤1.5 s
`mpris_set_playback` tick, so the system seekbar tracks and `onSeekTo` works
from the lockscreen. Covers arrive as `file://` paths into the app-private
cover cache (800 px WebP — `BitmapFactory` decodes WebP natively) with an
https fallback for uncached tracks. Android 13+ renders the controls straight
from the `PlaybackState` actions; the `addAction` buttons on the notification
only serve pre-13 devices. The POST_NOTIFICATIONS runtime permission (13+) is
requested on the first play, not at startup; if denied, playback and the
Quick-Settings media panel still work — only the shade notification is hidden.

### Recovery after suspend / process death

- **Suspended (normal backgrounding)**: nothing to recover — the foreground
  service prevents the freezer, playback continues.
- **Paused in background, process eventually killed**: the queue and current
  track are restored on next launch from the persisted player store, and the
  position from the server play queue (Subsonic `savePlayQueue`): a 15 s
  heartbeat pushes it while playing, `pause()` flushes it, and — mobile-only —
  `visibilitychange → hidden` flushes it the moment the app is backgrounded
  (`mobileLifecycleSync.ts`), since Android has no orderly exit path like the
  desktop close handler. Worst-case loss is ~15 s into a track (heartbeat
  cadence), typical loss ~0.
- **App swiped from recents while playing**: `onTaskRemoved` pauses and stops
  the service cleanly instead of leaving a zombie notification — the WebView
  that drives the queue is gone, so playback could not advance past the
  current track anyway. Playing on with the app "closed" would require moving
  queue advance into Rust; that's a deliberate non-goal for the PoC.

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

- **Playback after swipe-away**: closing the app from recents stops playback
  (see Recovery above). True detached playback needs queue advance in Rust.
- **Media resumption**: the post-reboot / post-kill "resume" chip in the Quick
  Settings media carousel needs a `MediaBrowserService`; not implemented.
- **iOS audio lifecycle**: `mpris_*` are still no-ops there — needs
  AVAudioSession category/interruption handling + MPNowPlayingInfoCenter +
  MPRemoteCommandCenter, and background-audio entitlements.
- **Storage paths**: library index / Hot Cache land in the app-private dir; sizes
  and eviction defaults were tuned for desktop disks.
- **UI**: the responsive/mobile layouts exist but were designed for narrow desktop
  windows, not touch — expect rough edges (hover menus, drag interactions).
- **iOS**: untested; needs a macOS host and an Apple developer account.
- **CI / release**: no Android build in CI yet, APK is debug-signed only.
