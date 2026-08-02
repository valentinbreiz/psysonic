package dev.psysonic.player.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.psysonic.player.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Foreground service owning the Android half of playback: the MediaSession
 * (lockscreen / Quick Settings controls, hardware media keys), the media
 * notification, audio focus, the becoming-noisy receiver and the
 * output-route watcher.
 *
 * The audio pipeline itself lives in the Rust core (rodio + cpal/AAudio, in
 * this same process) — this service never touches audio data. Its jobs are:
 *   1. keep the process out of the cached-app freezer while music plays,
 *      so both the Rust decode threads and the (event-driven) JS queue
 *      logic keep running with the app backgrounded;
 *   2. relay intents between Android and Rust in both directions.
 *
 * State flows in through [MediaBridge] (Rust pushes metadata + play state);
 * user intents flow out through [MediaBridge.nativeMediaEvent].
 */
class MediaControlService : Service() {
  private lateinit var session: MediaSession
  private lateinit var audioManager: AudioManager
  private lateinit var notificationManager: NotificationManager
  private val main = Handler(Looper.getMainLooper())

  private var focusRequest: AudioFocusRequest? = null
  private var haveFocus = false
  private var resumeOnFocusGain = false
  private var noisyRegistered = false
  private var inForeground = false

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  // Cover art for the session/notification, loaded off the main thread.
  private val coverLoader = Executors.newSingleThreadExecutor()
  private var coverBitmap: Bitmap? = null
  private var coverUrl: String? = null

  /** Headset unplugged / Bluetooth audio dropped: pause instead of letting
   *  the OS re-route playback to the speaker. Explicit pause, not toggle —
   *  same reasoning as the desktop macOS route-change handling (#1094). */
  private val noisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        MediaBridge.nativeMediaEvent("pause", 0.0)
      }
    }
  }

  /**
   * Output devices appearing/disappearing (wired headset, Bluetooth, USB
   * DAC). AAudio disconnects the active stream on every route change, so the
   * Rust core must reopen its output on the new route ("route-changed" ends
   * up in `reopen_output_stream`, same path as a desktop device switch).
   * Debounced: a Bluetooth connect fires several callbacks back-to-back, and
   * on removals the becoming-noisy pause needs time to land first so the
   * reopen does not seamlessly resume onto the speaker.
   */
  private val routeChange = Runnable { MediaBridge.nativeMediaEvent("route-changed", 0.0) }
  private val deviceCallback = object : AudioDeviceCallback() {
    private var initialCallback = true

    private fun isExternalSink(devices: Array<out AudioDeviceInfo>?): Boolean =
      devices?.any {
        it.isSink &&
          it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
          it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
          it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
          it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX
      } == true

    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
      // Registration replays the current device list; skip that first burst.
      if (initialCallback) { initialCallback = false; return }
      if (!isExternalSink(added)) return
      main.removeCallbacks(routeChange)
      main.postDelayed(routeChange, 300)
    }

    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
      if (!isExternalSink(removed)) return
      main.removeCallbacks(routeChange)
      main.postDelayed(routeChange, 500)
    }
  }

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        // Another app took over for good — pause and stay paused.
        haveFocus = false
        resumeOnFocusGain = false
        MediaBridge.nativeMediaEvent("pause", 0.0)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        // Call / navigation prompt / assistant: pause, resume when it ends.
        resumeOnFocusGain = MediaBridge.playing
        MediaBridge.nativeMediaEvent("pause", 0.0)
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        haveFocus = true
        if (resumeOnFocusGain) {
          resumeOnFocusGain = false
          MediaBridge.nativeMediaEvent("play", 0.0)
        }
      }
      // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: since API 26 the system ducks
      // USAGE_MEDIA streams by itself — nothing to do.
    }
  }

  override fun onCreate() {
    super.onCreate()
    MediaBridge.service = this
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    notificationManager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
        setShowBadge(false)
      }
    )

    session = MediaSession(this, "psysonic")
    session.setCallback(object : MediaSession.Callback() {
      override fun onPlay() = MediaBridge.nativeMediaEvent("play", 0.0)
      override fun onPause() = MediaBridge.nativeMediaEvent("pause", 0.0)
      override fun onSkipToNext() = MediaBridge.nativeMediaEvent("next", 0.0)
      override fun onSkipToPrevious() = MediaBridge.nativeMediaEvent("prev", 0.0)
      override fun onSeekTo(pos: Long) = MediaBridge.nativeMediaEvent("seek", pos / 1000.0)
      override fun onStop() {
        MediaBridge.nativeMediaEvent("pause", 0.0)
        dismiss()
      }
    })
    packageManager.getLaunchIntentForPackage(packageName)?.let {
      session.setSessionActivity(
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
      )
    }
    session.isActive = true

    audioManager.registerAudioDeviceCallback(deviceCallback, main)

    syncSessionMetadata()
    syncSessionPlaybackState()
    loadCoverIfChanged()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      // Notification buttons (pre-13 devices; 13+ renders controls straight
      // from the PlaybackState actions and calls the session callback).
      ACTION_PLAY -> MediaBridge.nativeMediaEvent("play", 0.0)
      ACTION_PAUSE -> MediaBridge.nativeMediaEvent("pause", 0.0)
      ACTION_NEXT -> MediaBridge.nativeMediaEvent("next", 0.0)
      ACTION_PREV -> MediaBridge.nativeMediaEvent("prev", 0.0)
      ACTION_DISMISS -> {
        // Notification swiped away (only possible while paused/detached).
        dismiss()
        return START_NOT_STICKY
      }
      else -> {
        // Plain start from MediaBridge. startForegroundService() demands a
        // startForeground() call even if playback flipped to paused while
        // the service was starting; applyPlaybackState() detaches again
        // right after in that case.
        enterForeground()
        applyPlaybackState(transition = true)
      }
    }
    // If the process dies the Rust engine dies with it — a restarted empty
    // service could not resume anything. Recovery goes through reopening
    // the app (persisted queue), not through a sticky service.
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  /** Rust pushed new track metadata. Main thread. */
  fun onMetaChanged() {
    syncSessionMetadata()
    loadCoverIfChanged()
    if (inForeground || MediaBridge.playing) notify(buildNotification())
  }

  /** Rust pushed a play/pause flip or a position tick. Main thread. */
  fun onPlaybackChanged(transition: Boolean) {
    applyPlaybackState(transition)
  }

  private fun applyPlaybackState(transition: Boolean) {
    syncSessionPlaybackState()
    if (!transition) return // position tick only — session state is enough

    if (MediaBridge.playing) {
      if (!haveFocus && !requestFocus()) {
        // Focus denied (active call, …): revert the play.
        MediaBridge.nativeMediaEvent("pause", 0.0)
        return
      }
      registerNoisy()
      acquireLocks()
      enterForeground()
      notify(buildNotification())
    } else {
      unregisterNoisy()
      releaseLocks()
      // Keep the notification but let it be swiped away; focus is kept so a
      // quick resume does not have to re-arbitrate.
      if (inForeground) {
        stopForeground(STOP_FOREGROUND_DETACH)
        inForeground = false
      }
      notify(buildNotification())
    }
  }

  // ── MediaSession state ────────────────────────────────────────────────────

  private fun syncSessionMetadata() {
    val meta = MediaBridge.meta
    val b = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
      .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.album)
    if (meta.durationMs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, meta.durationMs)
    coverBitmap?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
    session.setMetadata(b.build())
  }

  private fun syncSessionPlaybackState() {
    val playing = MediaBridge.playing
    session.setPlaybackState(
      PlaybackState.Builder()
        .setActions(
          PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        )
        .setState(
          if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
          MediaBridge.positionMs,
          if (playing) 1f else 0f,
        )
        .build()
    )
  }

  // ── Notification ──────────────────────────────────────────────────────────

  private fun buildNotification(): Notification {
    val meta = MediaBridge.meta
    val playing = MediaBridge.playing
    val b = Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_psysonic)
      .setContentTitle(meta.title ?: "Psysonic")
      .setContentText(meta.artist ?: "")
      .setSubText(meta.album)
      .setLargeIcon(coverBitmap)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setDeleteIntent(servicePending(ACTION_DISMISS))
      .setStyle(
        Notification.MediaStyle()
          .setMediaSession(session.sessionToken)
          .setShowActionsInCompactView(0, 1, 2)
      )
    packageManager.getLaunchIntentForPackage(packageName)?.let {
      b.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE))
    }
    b.addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
    if (playing) {
      b.addAction(action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE))
    } else {
      b.addAction(action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY))
    }
    b.addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
    return b.build()
  }

  private fun action(icon: Int, title: String, act: String): Notification.Action =
    Notification.Action.Builder(
      android.graphics.drawable.Icon.createWithResource(this, icon),
      title,
      servicePending(act),
    ).build()

  private fun servicePending(act: String): PendingIntent =
    PendingIntent.getService(
      this,
      act.hashCode(),
      Intent(this, MediaControlService::class.java).setAction(act),
      PendingIntent.FLAG_IMMUTABLE,
    )

  private fun enterForeground() {
    if (inForeground) return
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(NOTIF_ID, notification)
    }
    inForeground = true
  }

  private fun notify(notification: Notification) {
    try {
      notificationManager.notify(NOTIF_ID, notification)
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS denied — playback still works, controls stay
      // reachable through the Quick Settings media panel.
      Log.w(TAG, "notification blocked: $e")
    }
  }

  // ── Cover art ─────────────────────────────────────────────────────────────

  /**
   * Covers arrive as file:// paths into the app-private cover cache
   * (800px WebP — BitmapFactory decodes WebP natively) or, for uncached
   * tracks, as an https URL on the music server.
   */
  private fun loadCoverIfChanged() {
    val url = MediaBridge.meta.coverUrl
    if (url == coverUrl) return
    coverUrl = url
    coverBitmap = null
    if (url == null) { syncSessionMetadata(); return }
    coverLoader.execute {
      val bmp = try {
        if (url.startsWith("file://")) {
          BitmapFactory.decodeFile(Uri.parse(url).path)
        } else {
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.connectTimeout = 5000
          conn.readTimeout = 10000
          conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }
      } catch (e: Exception) {
        Log.w(TAG, "cover load failed: $e")
        null
      }
      main.post {
        // A newer track may have superseded this load.
        if (coverUrl != url) return@post
        coverBitmap = bmp
        syncSessionMetadata()
        if (inForeground || MediaBridge.playing) notify(buildNotification())
      }
    }
  }

  // ── Audio focus / noisy / locks ───────────────────────────────────────────

  private fun requestFocus(): Boolean {
    val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build()
      )
      .setOnAudioFocusChangeListener(focusListener, main)
      .build()
      .also { focusRequest = it }
    haveFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    return haveFocus
  }

  private fun abandonFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    haveFocus = false
    resumeOnFocusGain = false
  }

  private fun registerNoisy() {
    if (noisyRegistered) return
    registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    noisyRegistered = true
  }

  private fun unregisterNoisy() {
    if (!noisyRegistered) return
    unregisterReceiver(noisyReceiver)
    noisyRegistered = false
  }

  /**
   * The audio HAL holds its own wakelock while a stream renders, but the
   * decode-ahead and network streaming run on ordinary threads — hold a
   * partial wakelock + wifi lock while playing so long screen-off sessions
   * cannot starve them.
   */
  private fun acquireLocks() {
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "psysonic:playback").apply {
        setReferenceCounted(false)
      }
      @Suppress("DEPRECATION")
      val wifiMode = if (Build.VERSION.SDK_INT >= 29) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
      }
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      wifiLock = wm.createWifiLock(wifiMode, "psysonic:playback").apply {
        setReferenceCounted(false)
      }
    }
    wakeLock?.acquire()
    wifiLock?.acquire()
  }

  private fun releaseLocks() {
    if (wakeLock?.isHeld == true) wakeLock?.release()
    if (wifiLock?.isHeld == true) wifiLock?.release()
  }

  // ── Teardown ──────────────────────────────────────────────────────────────

  /** Full stop: session gone, focus released, service stopped. */
  private fun dismiss() {
    if (inForeground) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      inForeground = false
    }
    notificationManager.cancel(NOTIF_ID)
    stopSelf()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // App swiped from recents: the activity (and the WebView driving the
    // queue) is going away — stop cleanly instead of leaving a zombie
    // notification for a player that cannot advance tracks anymore.
    MediaBridge.nativeMediaEvent("pause", 0.0)
    dismiss()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    MediaBridge.service = null
    main.removeCallbacks(routeChange)
    audioManager.unregisterAudioDeviceCallback(deviceCallback)
    unregisterNoisy()
    abandonFocus()
    releaseLocks()
    coverLoader.shutdown()
    session.isActive = false
    session.release()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "PsysonicMedia"
    private const val CHANNEL_ID = "playback"
    private const val NOTIF_ID = 1
    private const val ACTION_PLAY = "dev.psysonic.player.media.PLAY"
    private const val ACTION_PAUSE = "dev.psysonic.player.media.PAUSE"
    private const val ACTION_NEXT = "dev.psysonic.player.media.NEXT"
    private const val ACTION_PREV = "dev.psysonic.player.media.PREV"
    private const val ACTION_DISMISS = "dev.psysonic.player.media.DISMISS"
  }
}
