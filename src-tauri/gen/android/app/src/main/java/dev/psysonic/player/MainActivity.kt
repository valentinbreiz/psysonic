package dev.psysonic.player

import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.psysonic.player.media.MediaBridge

class MainActivity : TauriActivity() {
  /**
   * Latest system-bar + display-cutout insets in CSS px ("top right bottom
   * left"). The webview pulls this at startup through [SafeAreaBridge] —
   * Android's WebView never fills env(safe-area-inset-*) for edge-to-edge
   * activities, so the web layout cannot learn these values on its own.
   */
  @Volatile private var safeAreaCssPx = "0 0 0 0"

  inner class SafeAreaBridge {
    @JavascriptInterface
    fun insets(): String = safeAreaCssPx
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Every bundled theme is dark: force light bar icons instead of letting
    // enableEdgeToEdge() follow the system light/dark setting.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    // Media stack (MediaSession foreground service) needs a context for
    // startForegroundService and an activity for the notification permission.
    MediaBridge.init(this)
  }

  override fun onWebViewCreate(webView: WebView) {
    webView.addJavascriptInterface(SafeAreaBridge(), "AndroidSafeArea")
    ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      val density = view.resources.displayMetrics.density
      safeAreaCssPx =
        "${bars.top / density} ${bars.right / density} " +
        "${bars.bottom / density} ${bars.left / density}"
      // Push covers rotation / bar visibility changes after the page is up;
      // the JavascriptInterface pull covers (re)loads that happen later.
      (view as WebView).evaluateJavascript(
        "window.__PSY_SAFE_AREA__ && window.__PSY_SAFE_AREA__('$safeAreaCssPx')",
        null,
      )
      insets
    }
    ViewCompat.requestApplyInsets(webView)
  }
}
