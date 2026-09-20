package app.seb3thehacker.gearslip.car

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.seb3thehacker.gearslip.GearslipLog

/**
 * A browser inside the car UI. [content] is a URL, or raw HTML when it does not look like one.
 * The bottom bar's Back walks the page history first and only leaves the app at the start.
 */
@Composable
fun WebApp(content: String) {
    val navigator = LocalCarNavigator.current
    val darkOutside = LocalDarkOutside.current
    var web by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(web) {
        navigator.backInterceptor = {
            val view = web
            if (view != null && view.canGoBack()) {
                view.goBack()
                true
            } else {
                false
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = LoggingClient()
                if (content.startsWith("http://") || content.startsWith("https://")) {
                    loadUrl(content)
                } else {
                    loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
                }
                web = this
            }
        },
        // Pages that honour it follow the darkness outside; a first, tweakable use of the signal.
        update = { it.settings.isAlgorithmicDarkeningAllowed = darkOutside },
        onRelease = { it.destroy() },
    )
}

private class LoggingClient : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        GearslipLog.i("car web page loaded: $url")
    }

    // onPageFinished fires for error pages too, so without this a blocked load (missing
    // INTERNET permission, no network) looks like a successful one.
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.e("car web page FAILED: ${request.url} - ${error?.errorCode} ${error?.description}")
        }
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
        if (request?.isForMainFrame == true) {
            GearslipLog.w("car web page HTTP ${response?.statusCode} for ${request.url}")
        }
    }
}
