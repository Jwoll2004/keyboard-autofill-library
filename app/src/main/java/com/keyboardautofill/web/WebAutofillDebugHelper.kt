package com.keyboardautofill.web

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo

/**
 * Debug utilities for web autofill integration
 */
object WebAutofillDebugHelper {

    private const val TAG = "WebAutofillDebug"

    fun logEditorInfo(editorInfo: EditorInfo?, context: String) {
        Log.d(TAG, "=== EDITOR INFO DEBUG: $context ===")

        if (editorInfo == null) {
            Log.d(TAG, "EditorInfo: NULL")
            return
        }

        Log.d(TAG, "Package: ${editorInfo.packageName}")
        Log.d(TAG, "Field ID: ${editorInfo.fieldId}")
        Log.d(TAG, "Hint: '${editorInfo.hintText}'")
        Log.d(TAG, "Input Type: 0x${Integer.toHexString(editorInfo.inputType)}")
        Log.d(TAG, "IME Options: 0x${Integer.toHexString(editorInfo.imeOptions)}")
        Log.d(TAG, "Action ID: ${editorInfo.actionId}")
        Log.d(TAG, "Action Label: '${editorInfo.actionLabel}'")

        // Log extras bundle
        val extras = editorInfo.extras
        if (extras != null) {
            Log.d(TAG, "Extras keys: ${extras.keySet()}")
            extras.keySet().forEach { key ->
                Log.d(TAG, "  $key: ${extras.get(key)}")
            }
        } else {
            Log.d(TAG, "Extras: NULL")
        }

        Log.d(TAG, "========================================")
    }

    fun logWebContextAnalysis(
        packageName: String?,
        isWebBrowser: Boolean,
        detectedFieldType: String,
        confidence: Float
    ) {
        Log.d(TAG, "=== WEB CONTEXT ANALYSIS ===")
        Log.d(TAG, "Package: $packageName")
        Log.d(TAG, "Is Web Browser: $isWebBrowser")
        Log.d(TAG, "Detected Field Type: $detectedFieldType")
        Log.d(TAG, "Confidence: $confidence")
        Log.d(TAG, "================================")
    }

    fun logJavaScriptInjection(scriptLength: Int, targetBrowser: String) {
        Log.d(TAG, "=== JAVASCRIPT INJECTION ===")
        Log.d(TAG, "Target Browser: $targetBrowser")
        Log.d(TAG, "Script Length: $scriptLength chars")
        Log.d(TAG, "Injection Time: ${System.currentTimeMillis()}")
        Log.d(TAG, "================================")
    }

    fun logFieldAnalysisResult(
        fieldId: String,
        fieldType: String,
        label: String,
        placeholder: String,
        detectedType: String,
        confidence: Float
    ) {
        Log.d(TAG, "=== FIELD ANALYSIS RESULT ===")
        Log.d(TAG, "Field ID: '$fieldId'")
        Log.d(TAG, "HTML Type: '$fieldType'")
        Log.d(TAG, "Label: '$label'")
        Log.d(TAG, "Placeholder: '$placeholder'")
        Log.d(TAG, "Detected Type: $detectedType")
        Log.d(TAG, "Confidence: $confidence")
        Log.d(TAG, "Analysis Time: ${System.currentTimeMillis()}")
        Log.d(TAG, "==================================")
    }

    fun logSuggestionGeneration(
        fieldType: String,
        prefix: String,
        suggestionCount: Int,
        suggestions: List<String>
    ) {
        Log.d(TAG, "=== SUGGESTION GENERATION ===")
        Log.d(TAG, "Field Type: $fieldType")
        Log.d(TAG, "Prefix: '$prefix'")
        Log.d(TAG, "Count: $suggestionCount")
        Log.d(TAG, "Suggestions: ${suggestions.take(5)}${if (suggestions.size > 5) "..." else ""}")
        Log.d(TAG, "==============================")
    }

    fun startWebSessionLogging(inputMethodService: InputMethodService) {
        Log.d(TAG, "=== WEB AUTOFILL SESSION START ===")
        Log.d(TAG, "Service: ${inputMethodService.javaClass.simpleName}")
        Log.d(TAG, "Timestamp: ${System.currentTimeMillis()}")
        Log.d(TAG, "===================================")
    }

    fun endWebSessionLogging() {
        Log.d(TAG, "=== WEB AUTOFILL SESSION END ===")
        Log.d(TAG, "Timestamp: ${System.currentTimeMillis()}")
        Log.d(TAG, "=================================")
    }

    fun logError(context: String, error: Throwable) {
        Log.e(TAG, "=== ERROR in $context ===")
        Log.e(TAG, "Error: ${error.message}")
        Log.e(TAG, "Stack trace:", error)
        Log.e(TAG, "========================")
    }

    fun createTestEditorInfo(packageName: String, hint: String, inputType: Int): EditorInfo {
        return EditorInfo().apply {
            this.packageName = packageName
            this.hintText = hint
            this.inputType = inputType
            this.fieldId = 12345
        }
    }

    fun testWebDetection() {
        Log.d(TAG, "=== TESTING WEB DETECTION ===")

        val testCases = listOf(
            Triple("com.android.chrome", "Enter your email", android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            Triple("org.mozilla.firefox", "First name", android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            Triple("com.google.android.apps.docs", "Phone number", android.text.InputType.TYPE_CLASS_PHONE),
            Triple("com.android.vending", "Search", android.text.InputType.TYPE_CLASS_TEXT)
        )

        testCases.forEach { (pkg, hint, inputType) ->
            val editorInfo = createTestEditorInfo(pkg, hint, inputType)
            Log.d(TAG, "Test case: $pkg, $hint, 0x${Integer.toHexString(inputType)}")
            logEditorInfo(editorInfo, "TEST")
        }

        Log.d(TAG, "===========================")
    }
}