package com.keyboardautofill.web

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.keyboardautofill.AutofillManager
import com.keyboardautofill.FormDataManager

/**
 * Integration layer between web form detection and existing autofill system
 */
class WebAutofillIntegration(
    private val inputMethodService: InputMethodService,
    private val autofillManager: AutofillManager,
    private val formDataManager: FormDataManager,
    private val rootView: View
) {

    private val webFormHandler = WebFormHandler(inputMethodService, formDataManager)
    private var isWebContext = false
    private var currentWebFieldType = FormDataManager.FieldType.UNKNOWN

    companion object {
        private const val TAG = "WebAutofillIntegration"

        // Known browser package names
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.brave.browser",
            "com.android.browser",
            "com.sec.android.app.sbrowser" // Samsung Browser
        )
    }

    init {
        // Set up callback for immediate suggestion updates
        webFormHandler.setFieldTypeDetectedCallback { fieldType ->
            currentWebFieldType = fieldType
            Log.d(TAG, "Field type callback received: $fieldType")

            // Trigger suggestion refresh in AutofillManager
            triggerSuggestionRefresh()
        }
    }

    private fun triggerSuggestionRefresh() {
        // This would ideally call back to AutofillManager to refresh suggestions
        // For now, just log the event
        Log.d(TAG, "Triggering suggestion refresh for field type: $currentWebFieldType")
    }

    fun onWebFieldCompleted(content: String) {
        if (isWebContext) {
            webFormHandler.onWebFieldCompleted(content)
        }
    }

    // ============================================
    // Main Integration Points

    fun onFieldFocused(editorInfo: EditorInfo?): FormDataManager.FieldType {
        Log.d(TAG, "=== onFieldFocused (Web Integration) ===")

        isWebContext = detectWebContext(editorInfo)
        Log.d(TAG, "Web context detected: $isWebContext")

        return if (isWebContext) {
            handleWebFieldFocus(editorInfo)
        } else {
            // Delegate to normal autofill detection
            formDataManager.detectFieldType(editorInfo)
        }
    }

    fun onFieldChanged() {
        if (isWebContext) {
            Log.d(TAG, "Web field content changed")
            // Web fields change events are handled differently
            // We might need to re-analyze the field or update suggestions
        }
    }

    fun getSuggestions(prefix: String = ""): List<String> {
        return if (isWebContext) {
            Log.d(TAG, "Getting web suggestions for prefix: '$prefix'")
            webFormHandler.getSuggestionsForWebField(prefix)
        } else {
            emptyList() // Delegate to normal autofill
        }
    }

    fun getCurrentFieldType(): FormDataManager.FieldType {
        return if (isWebContext) {
            currentWebFieldType
        } else {
            FormDataManager.FieldType.UNKNOWN
        }
    }

    fun isInWebContext(): Boolean = isWebContext

    // ============================================
    // Web Context Detection

    private fun detectWebContext(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false

        val packageName = editorInfo.packageName
        Log.d(TAG, "Checking package: $packageName")

        // Direct browser package check
        val isBrowser = BROWSER_PACKAGES.any { packageName.contains(it) }

        // Additional context clues
        val hasWebviewHints = editorInfo.hintText?.toString()?.contains("http") == true ||
                editorInfo.extras?.containsKey("webkit") == true

        val isWebContext = isBrowser || hasWebviewHints

        Log.d(TAG, "Web context analysis - Browser: $isBrowser, WebView hints: $hasWebviewHints, Final: $isWebContext")

        return isWebContext
    }

    private fun handleWebFieldFocus(editorInfo: EditorInfo?): FormDataManager.FieldType {
        Log.d(TAG, "=== handleWebFieldFocus ===")

        // Use web form handler to analyze the field
        val detectedType = webFormHandler.onWebFieldFocused(editorInfo)
        currentWebFieldType = detectedType

        Log.d(TAG, "Web field type detected: $detectedType")

        // For immediate fallback, try to use EditorInfo as well
        if (detectedType == FormDataManager.FieldType.UNKNOWN) {
            val fallbackType = attemptFallbackDetection(editorInfo)
            if (fallbackType != FormDataManager.FieldType.UNKNOWN) {
                currentWebFieldType = fallbackType
                Log.d(TAG, "Using fallback detection: $fallbackType")
            }
        }

        return currentWebFieldType
    }

    private fun attemptFallbackDetection(editorInfo: EditorInfo?): FormDataManager.FieldType {
        // Try to detect using limited EditorInfo for immediate suggestions
        if (editorInfo == null) return FormDataManager.FieldType.UNKNOWN

        val inputType = editorInfo.inputType
        val hint = editorInfo.hintText?.toString()?.lowercase() ?: ""

        Log.d(TAG, "Fallback detection - inputType: 0x${Integer.toHexString(inputType)}, hint: '$hint'")

        return when {
            inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != 0 -> {
                Log.d(TAG, "Fallback: EMAIL from input type")
                FormDataManager.FieldType.EMAIL
            }
            inputType and android.text.InputType.TYPE_CLASS_PHONE != 0 -> {
                Log.d(TAG, "Fallback: PHONE from input type")
                FormDataManager.FieldType.PHONE
            }
            hint.contains("email") -> {
                Log.d(TAG, "Fallback: EMAIL from hint")
                FormDataManager.FieldType.EMAIL
            }
            hint.contains("phone") -> {
                Log.d(TAG, "Fallback: PHONE from hint")
                FormDataManager.FieldType.PHONE
            }
            hint.contains("name") -> {
                Log.d(TAG, "Fallback: FULL_NAME from hint")
                FormDataManager.FieldType.FULL_NAME
            }
            else -> {
                Log.d(TAG, "Fallback: No match found")
                FormDataManager.FieldType.UNKNOWN
            }
        }
    }

    // ============================================
    // WebView Setup (for apps that use WebView)

    fun setupWebViewIntegration(webView: android.webkit.WebView) {
        Log.d(TAG, "Setting up WebView integration")

        // Enable JavaScript
        webView.settings.javaScriptEnabled = true

        // Add JavaScript interface
        val jsInterface = WebFormHandler.AutofillJavaScriptInterface(webFormHandler)
        webView.addJavascriptInterface(jsInterface, "AutofillBridge")

        // Set up page load listener to inject scripts
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
                // Inject field detection scripts when page loads
                injectGlobalScripts(view)
            }
        }

        Log.d(TAG, "WebView integration setup complete")
    }

    private fun injectGlobalScripts(webView: android.webkit.WebView?) {
        if (webView == null) return

        val globalScript = """
            (function() {
                console.log('AutofillBridge: Global scripts injected');
                
                // Set up focus event listeners for all input fields
                document.addEventListener('focusin', function(event) {
                    if (event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') {
                        console.log('AutofillBridge: Input field focused');
                        // Add a small delay to ensure field is fully focused
                        setTimeout(function() {
                            if (typeof AutofillBridge !== 'undefined') {
                                console.log('AutofillBridge: Triggering field analysis');
                                // Trigger field analysis script
                                ${getFieldAnalysisScript()}
                            }
                        }, 100);
                    }
                });
                
                console.log('AutofillBridge: Event listeners set up');
            })();
        """

        webView.evaluateJavascript(globalScript) { result ->
            Log.d(TAG, "Global script injection result: $result")
        }
    }

    private fun getFieldAnalysisScript(): String {
        // Return the same field analysis script from WebFormHandler
        // This would be extracted to avoid duplication
        return """
            (function() {
                const activeElement = document.activeElement;
                if (!activeElement || (activeElement.tagName !== 'INPUT' && activeElement.tagName !== 'TEXTAREA')) {
                    return;
                }
                
                const fieldData = {
                    id: activeElement.id || '',
                    type: activeElement.type || 'text',
                    placeholder: activeElement.placeholder || '',
                    name: activeElement.name || '',
                    className: activeElement.className || '',
                    autocomplete: activeElement.autocomplete || ''
                };
                
                // Quick label detection for immediate use
                let label = '';
                if (activeElement.getAttribute('aria-labelledby')) {
                    const labelId = activeElement.getAttribute('aria-labelledby');
                    const labelEl = document.getElementById(labelId);
                    if (labelEl) label = labelEl.textContent.trim();
                }
                
                // Google Forms specific
                const googleLabel = document.querySelector('.M7eMe');
                if (googleLabel && !label) {
                    label = googleLabel.textContent.trim();
                }
                
                fieldData.label = label;
                
                AutofillBridge.onFieldAnalyzed(JSON.stringify(fieldData));
            })();
        """
    }
}