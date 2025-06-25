package com.keyboardautofill.web

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.webkit.WebView

class WebViewMonitor(private val context: Context) {

    companion object {
        private const val TAG = "WebViewMonitor"
    }

    private val webFormDetector = WebFormDetector(context)
    private var lastDetectedPackage: String? = null
    private var isWebViewContext = false

    fun checkForWebContext(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false

        val packageName = editorInfo.packageName
        val inputType = editorInfo.inputType

        // Check if this is a web browser or WebView
        isWebViewContext = packageName != null && (
                packageName.contains("chrome") ||
                        packageName.contains("browser") ||
                        packageName.contains("webview") ||
                        packageName.contains("firefox") ||
                        packageName.contains("opera") ||
                        packageName.contains("edge") ||
                        packageName.contains("samsung") && packageName.contains("browser")
                )

        if (isWebViewContext && packageName != lastDetectedPackage) {
            Log.d(TAG, "=== WEB CONTEXT DETECTED ===")
            Log.d(TAG, "Package: $packageName")
            Log.d(TAG, "Input Type: $inputType")
            Log.d(TAG, "Field ID: ${editorInfo.fieldId}")
            Log.d(TAG, "Hint: ${editorInfo.hintText}")
            Log.d(TAG, "===========================")

            lastDetectedPackage = packageName

            // Log injection script for debugging
            Log.d(TAG, "Would inject script here for web form detection")
            // In a real implementation, we'd coordinate with browser/WebView here
        }

        return isWebViewContext
    }

    fun getDetectionScript(): String = webFormDetector.getInjectionScript()
}