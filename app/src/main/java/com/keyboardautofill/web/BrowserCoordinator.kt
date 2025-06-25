package com.keyboardautofill.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo

class BrowserCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "BrowserCoordinator"
        private const val DETECTION_DELAY = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val webViewInjector = WebViewInjector(context)
    private var lastDetectionTime = 0L
    private var currentBrowserPackage: String? = null

    fun onWebFieldFocused(editorInfo: EditorInfo) {
        val packageName = editorInfo.packageName ?: return
        val currentTime = System.currentTimeMillis()

        // Throttle detection to avoid excessive injections
        if (currentTime - lastDetectionTime < DETECTION_DELAY) {
            return
        }

        lastDetectionTime = currentTime
        currentBrowserPackage = packageName

        Log.d(TAG, "=== BROWSER FIELD FOCUS ===")
        Log.d(TAG, "Package: $packageName")
        Log.d(TAG, "Triggering field detection...")

        // Try to get current URL from clipboard or use a test URL
        val testUrl = getTestUrlForPackage(packageName)

        handler.postDelayed({
            performFieldDetection(testUrl)
        }, 200)
    }

    private fun performFieldDetection(url: String) {
        Log.d(TAG, "Performing field detection for: $url")
        webViewInjector.injectIntoCurrentPage(url)
    }

    private fun getTestUrlForPackage(packageName: String): String {
        // For testing, return appropriate test URLs
        return when {
            packageName.contains("chrome") -> "https://www.google.com/forms/about/"
            packageName.contains("firefox") -> "https://accounts.firefox.com/"
            else -> "https://www.example.com/"
        }
    }

    fun cleanup() {
        webViewInjector.cleanup()
    }
}