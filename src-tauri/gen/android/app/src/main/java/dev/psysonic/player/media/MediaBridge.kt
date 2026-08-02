package dev.psysonic.player.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Bridge between the Rust core and the Android media stack.
 *
 * Rust -> Android: the `mpris_set_metadata` / `mpris_set_playback` Tauri
 * commands land here through JNI (`media_session_android.rs`), from whatever
 * worker thread the command runs on; every entry point hops to the main
 * looper before touching the service.
 *
 * Android -> Rust: [MediaControlService] forwards MediaSession callbacks,
 * audio-focus changes, headphone and output-route events to
 * [nativeMediaEvent]; the Rust side re-emits them as the same `media:*`
 * Tauri events the desktop souvlaki bridge produces, so the frontend needs
 * no platform-specific handling.
 */
object MediaBridge {
  /**
   * Snapshot of the last pushed track metadata. The service reads it when it
   * starts (the service start intent races the first metadata push) and on
   * every [updateMetadata].
   */
  class Meta(
    @JvmField val title: String?,
    @JvmField val artist: String?,
    @JvmField val album: String?,
    @JvmField val coverUrl: String?,
    @JvmField val durationMs: Long,
  )

  private val main = Handler(Looper.getMainLooper())
  private var appContext: Context? = null
  private var activityRef: WeakReference<Activity>? = null

  @Volatile @JvmStatic var service: MediaControlService? = null
  @Volatile @JvmStatic var meta = Meta(null, null, null, null, 0)
  @Volatile @JvmStatic var playing = false
  @Volatile @JvmStatic var positionMs = 0L

  /**
   * Implemented in Rust
   * (`Java_dev_psysonic_player_media_MediaBridge_nativeMediaEvent`).
   * Events: play, pause, next, prev, stop, seek (value = seconds),
   * route-changed.
   */
  @JvmStatic external fun nativeMediaEvent(event: String, value: Double)

  /** Called once from MainActivity.onCreate. */
  fun init(activity: Activity) {
    appContext = activity.applicationContext
    activityRef = WeakReference(activity)
  }

  /** Called from Rust on track change (any thread). */
  @JvmStatic
  fun updateMetadata(
    title: String?,
    artist: String?,
    album: String?,
    coverUrl: String?,
    durationSecs: Double,
  ) {
    meta = Meta(
      title, artist, album, coverUrl,
      if (durationSecs > 0) (durationSecs * 1000).toLong() else 0,
    )
    main.post { service?.onMetaChanged() }
  }

  /** Called from Rust on play/pause flips and ~1.5 s position ticks (any thread). */
  @JvmStatic
  fun updatePlayback(isPlaying: Boolean, positionSecs: Double) {
    val transition = playing != isPlaying
    playing = isPlaying
    if (positionSecs >= 0) positionMs = (positionSecs * 1000).toLong()
    main.post {
      val svc = service
      when {
        svc != null -> svc.onPlaybackChanged(transition)
        // First play: bring the service (and with it the MediaSession,
        // notification, audio focus) up. Always called while the app is
        // foreground, so startForegroundService is allowed.
        isPlaying -> startService()
        // No service and not playing: nothing to show.
      }
    }
  }

  private fun startService() {
    val ctx = appContext ?: return
    requestNotificationPermission()
    ctx.startForegroundService(Intent(ctx, MediaControlService::class.java))
  }

  /**
   * Android 13+ gates notifications behind a runtime permission. Ask on the
   * first play instead of at app start — by then the user clearly wants
   * playback. If denied, the foreground service still runs (playback keeps
   * working in the background); only the notification stays hidden.
   */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val act = activityRef?.get() ?: return
    if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) return
    act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x50AF)
  }
}
