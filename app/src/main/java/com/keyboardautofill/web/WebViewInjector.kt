package com.keyboardautofill.web

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject

class WebViewInjector(private val context: Context) {

    companion object {
        private const val TAG = "WebViewInjector"
        private const val INJECTION_DELAY = 500L
    }

    private var overlayWebView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val webFormDetector = WebFormDetector(context)
    private var lastInjectedUrl: String? = null

    fun injectIntoCurrentPage(url: String) {
        Log.d(TAG, "Starting injection for URL: $url")

        handler.post {
            try {
                setupOverlayWebView()
                overlayWebView?.loadUrl(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject", e)
            }
        }
    }

    private fun setupOverlayWebView() {
        if (overlayWebView != null) return

        overlayWebView = WebView(context).apply {
            // Configure WebView
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                userAgentString = "$userAgentString AutofillDetector/1.0"
            }

            // Add JavaScript interface
            addJavascriptInterface(
                webFormDetector.getJavaScriptInterface(),
                webFormDetector.getInterfaceName()
            )

            // Set WebViewClient to inject script after page loads
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page loaded: $url")

                    // Inject detection script
                    handler.postDelayed({
                        injectDetectionScript()
                    }, INJECTION_DELAY)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            // Set WebChromeClient for console logs
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "WebConsole: ${consoleMessage?.message()}")
                    return true
                }
            }
        }

        // Create invisible overlay
        createInvisibleOverlay()
    }

    private fun createInvisibleOverlay() {
        val params = WindowManager.LayoutParams().apply {
            width = 1
            height = 1
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.addView(overlayWebView, params)
            Log.d(TAG, "Invisible WebView overlay created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay", e)
        }
    }

    private fun injectDetectionScript() {
        val script = webFormDetector.getInjectionScript()

        overlayWebView?.evaluateJavascript(script) { result ->
            Log.d(TAG, "Script injection result: $result")
        }

        // Also inject a script to get current page info
        overlayWebView?.evaluateJavascript("""
            (function() {
                const pageInfo = {
                    url: window.location.href,
                    title: document.title,
                    formCount: document.querySelectorAll('form').length,
                    inputCount: document.querySelectorAll('input, textarea').length
                };
                return JSON.stringify(pageInfo);
            })();
        """.trimIndent()) { result ->
            try {
                val pageInfo = JSONObject(result.trim('"'))
                Log.d(TAG, "=== PAGE INFO ===")
                Log.d(TAG, "URL: ${pageInfo.optString("url")}")
                Log.d(TAG, "Title: ${pageInfo.optString("title")}")
                Log.d(TAG, "Forms: ${pageInfo.optInt("formCount")}")
                Log.d(TAG, "Input fields: ${pageInfo.optInt("inputCount")}")
                Log.d(TAG, "=================")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse page info", e)
            }
        }
    }

    fun cleanup() {
        handler.post {
            try {
                overlayWebView?.let { webView ->
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager.removeView(webView)
                    webView.destroy()
                }
                overlayWebView = null
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error", e)
            }
        }
    }
}