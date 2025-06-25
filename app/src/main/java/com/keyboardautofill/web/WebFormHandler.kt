package com.keyboardautofill.web

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import com.keyboardautofill.FormDataManager

/**
 * Handles web form detection and field type analysis through JavaScript injection
 */
class WebFormHandler(
    private val inputMethodService: InputMethodService,
    private val formDataManager: FormDataManager
) {

    companion object {
        private const val TAG = "WebFormHandler"
        private const val JS_INTERFACE_NAME = "AutofillBridge"
    }

    private var currentActiveField: WebFormField? = null
    private var detectedFields = mutableListOf<WebFormField>()

    data class WebFormField(
        val id: String,
        val type: String,
        val placeholder: String,
        val label: String,
        val autocomplete: String,
        val name: String,
        val className: String,
        val detectedFieldType: FormDataManager.FieldType,
        val confidence: Float
    )

    // ============================================
    // Main Integration Points

    fun onWebFieldFocused(editorInfo: EditorInfo?): FormDataManager.FieldType {
        Log.d(TAG, "=== onWebFieldFocused ===")

        if (editorInfo?.packageName?.contains("browser") != true &&
            editorInfo?.packageName?.contains("chrome") != true &&
            editorInfo?.packageName?.contains("firefox") != true &&
            editorInfo?.packageName?.contains("webview") != true) {
            Log.d(TAG, "Not a web browser context: ${editorInfo?.packageName}")
            return FormDataManager.FieldType.UNKNOWN
        }

        // Inject JavaScript to analyze current focused field
        injectFieldAnalysisScript()

        // For now, return UNKNOWN until we get JS callback
        // This will be updated when JS provides field details
        return FormDataManager.FieldType.UNKNOWN
    }

    fun processFieldData(fieldData: String) {
        Log.d(TAG, "=== processFieldData ===")
        Log.d(TAG, "Raw field data: $fieldData")

        try {
            val field = parseFieldData(fieldData)
            currentActiveField = field
            detectedFields.add(field)

            Log.d(TAG, "Processed field - Type: ${field.detectedFieldType}, Confidence: ${field.confidence}")
            Log.d(TAG, "Field details - Label: '${field.label}', Placeholder: '${field.placeholder}', Name: '${field.name}'")

            // Notify the autofill system about the detected field type
            notifyFieldTypeDetected(field.detectedFieldType)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing field data", e)
        }
    }

    fun getCurrentFieldType(): FormDataManager.FieldType {
        return currentActiveField?.detectedFieldType ?: FormDataManager.FieldType.UNKNOWN
    }

    fun getSuggestionsForWebField(prefix: String = ""): List<String> {
        val fieldType = getCurrentFieldType()
        return if (fieldType != FormDataManager.FieldType.UNKNOWN) {
            formDataManager.getSuggestions(fieldType, prefix)
        } else {
            // Fallback: get suggestions from all field types with prefix matching
            getAllSuggestionsWithPrefix(prefix)
        }
    }

    // ============================================
    // JavaScript Injection

    private fun injectFieldAnalysisScript() {
        val script = """
            (function() {
                console.log('AutofillBridge: Starting field analysis');
                
                function analyzeActiveField() {
                    const activeElement = document.activeElement;
                    if (!activeElement || (activeElement.tagName !== 'INPUT' && activeElement.tagName !== 'TEXTAREA')) {
                        console.log('AutofillBridge: No active input field found');
                        return null;
                    }
                    
                    // Get basic field attributes
                    const fieldData = {
                        id: activeElement.id || '',
                        type: activeElement.type || 'text',
                        placeholder: activeElement.placeholder || '',
                        name: activeElement.name || '',
                        className: activeElement.className || '',
                        autocomplete: activeElement.autocomplete || '',
                        value: activeElement.value || ''
                    };
                    
                    // Find associated label - multiple strategies
                    let label = '';
                    
                    // Strategy 1: aria-labelledby
                    if (activeElement.getAttribute('aria-labelledby')) {
                        const labelIds = activeElement.getAttribute('aria-labelledby').split(' ');
                        const labelTexts = labelIds.map(id => {
                            const el = document.getElementById(id);
                            return el ? el.textContent.trim() : '';
                        }).filter(text => text.length > 0);
                        label = labelTexts.join(' ');
                    }
                    
                    // Strategy 2: for attribute pointing to this field
                    if (!label && fieldData.id) {
                        const labelEl = document.querySelector('label[for="' + fieldData.id + '"]');
                        if (labelEl) label = labelEl.textContent.trim();
                    }
                    
                    // Strategy 3: parent label
                    if (!label) {
                        const parentLabel = activeElement.closest('label');
                        if (parentLabel) label = parentLabel.textContent.trim();
                    }
                    
                    // Strategy 4: Google Forms specific - .M7eMe class
                    if (!label) {
                        const googleLabel = document.querySelector('.M7eMe');
                        if (googleLabel && isElementNearInput(googleLabel, activeElement)) {
                            label = googleLabel.textContent.trim();
                            console.log('AutofillBridge: Found Google Forms label: ' + label);
                        }
                    }
                    
                    // Strategy 5: Previous sibling text
                    if (!label) {
                        let sibling = activeElement.previousElementSibling;
                        while (sibling && !label) {
                            if (sibling.textContent && sibling.textContent.trim()) {
                                label = sibling.textContent.trim();
                                break;
                            }
                            sibling = sibling.previousElementSibling;
                        }
                    }
                    
                    fieldData.label = label;
                    
                    console.log('AutofillBridge: Field analysis complete:', fieldData);
                    return fieldData;
                }
                
                function isElementNearInput(labelElement, inputElement) {
                    const labelRect = labelElement.getBoundingClientRect();
                    const inputRect = inputElement.getBoundingClientRect();
                    
                    // Check if label is within reasonable distance (200px) and above the input
                    const verticalDistance = Math.abs(labelRect.bottom - inputRect.top);
                    const horizontalOverlap = !(labelRect.right < inputRect.left || labelRect.left > inputRect.right);
                    
                    return verticalDistance < 200 && horizontalOverlap;
                }
                
                const fieldInfo = analyzeActiveField();
                if (fieldInfo && typeof AutofillBridge !== 'undefined') {
                    const dataString = JSON.stringify(fieldInfo);
                    console.log('AutofillBridge: Sending field data: ' + dataString);
                    AutofillBridge.onFieldAnalyzed(dataString);
                } else if (fieldInfo) {
                    console.log('AutofillBridge: Field analyzed but bridge not available:', fieldInfo);
                } else {
                    console.log('AutofillBridge: No field to analyze');
                }
            })();
        """

        // This would be injected into the WebView
        // For now, we'll log the script for implementation reference
        Log.d(TAG, "JavaScript injection ready - script length: ${script.length}")
        Log.d(TAG, "Script would be injected into active WebView")
    }

    // ============================================
    // Field Type Detection Logic

    private fun parseFieldData(jsonData: String): WebFormField {
        // Parse JSON-like data (simplified for this implementation)
        // In real implementation, use proper JSON parsing

        val data = parseSimpleJson(jsonData)

        val id = data["id"] ?: ""
        val type = data["type"] ?: "text"
        val placeholder = data["placeholder"] ?: ""
        val label = data["label"] ?: ""
        val name = data["name"] ?: ""
        val className = data["className"] ?: ""
        val autocomplete = data["autocomplete"] ?: ""

        val (fieldType, confidence) = detectFieldType(type, placeholder, label, name, autocomplete, className)

        return WebFormField(
            id = id,
            type = type,
            placeholder = placeholder,
            label = label,
            autocomplete = autocomplete,
            name = name,
            className = className,
            detectedFieldType = fieldType,
            confidence = confidence
        )
    }

    private fun detectFieldType(
        type: String,
        placeholder: String,
        label: String,
        name: String,
        autocomplete: String,
        className: String
    ): Pair<FormDataManager.FieldType, Float> {

        val combinedText = "$label $placeholder $name $autocomplete".lowercase()

        Log.d(TAG, "Analyzing combined text: '$combinedText'")
        Log.d(TAG, "Input type: '$type'")

        // High confidence matches (90%+)
        when {
            type == "email" || combinedText.contains("email") -> {
                Log.d(TAG, "HIGH confidence EMAIL match")
                return Pair(FormDataManager.FieldType.EMAIL, 0.95f)
            }
            type == "tel" || combinedText.contains("phone") || combinedText.contains("mobile") -> {
                Log.d(TAG, "HIGH confidence PHONE match")
                return Pair(FormDataManager.FieldType.PHONE, 0.95f)
            }
            autocomplete == "given-name" || (combinedText.contains("first") && combinedText.contains("name")) -> {
                Log.d(TAG, "HIGH confidence FIRST_NAME match")
                return Pair(FormDataManager.FieldType.FIRST_NAME, 0.95f)
            }
            autocomplete == "family-name" || (combinedText.contains("last") && combinedText.contains("name")) -> {
                Log.d(TAG, "HIGH confidence LAST_NAME match")
                return Pair(FormDataManager.FieldType.LAST_NAME, 0.95f)
            }
            autocomplete == "name" || combinedText.contains("full name") || combinedText == "name" -> {
                Log.d(TAG, "HIGH confidence FULL_NAME match")
                return Pair(FormDataManager.FieldType.FULL_NAME, 0.90f)
            }
        }

        // Medium confidence matches (70-85%)
        when {
            combinedText.contains("address") -> {
                Log.d(TAG, "MEDIUM confidence ADDRESS match")
                return Pair(FormDataManager.FieldType.ADDRESS, 0.80f)
            }
            combinedText.contains("city") -> {
                Log.d(TAG, "MEDIUM confidence CITY match")
                return Pair(FormDataManager.FieldType.CITY, 0.80f)
            }
            combinedText.contains("state") || combinedText.contains("province") -> {
                Log.d(TAG, "MEDIUM confidence STATE match")
                return Pair(FormDataManager.FieldType.STATE, 0.75f)
            }
            combinedText.contains("zip") || combinedText.contains("postal") -> {
                Log.d(TAG, "MEDIUM confidence ZIP match")
                return Pair(FormDataManager.FieldType.ZIP, 0.80f)
            }
            combinedText.contains("company") || combinedText.contains("organization") -> {
                Log.d(TAG, "MEDIUM confidence COMPANY match")
                return Pair(FormDataManager.FieldType.COMPANY, 0.75f)
            }
            combinedText.contains("username") || combinedText.contains("user name") -> {
                Log.d(TAG, "MEDIUM confidence USERNAME match")
                return Pair(FormDataManager.FieldType.USERNAME, 0.75f)
            }
        }

        Log.d(TAG, "NO confident match found - returning UNKNOWN")
        return Pair(FormDataManager.FieldType.UNKNOWN, 0.0f)
    }

    // ============================================
    // Helper Functions

    private fun parseSimpleJson(jsonString: String): Map<String, String> {
        // Simplified JSON parsing - replace with proper JSON library in production
        val result = mutableMapOf<String, String>()

        try {
            val cleaned = jsonString.trim().removeSurrounding("{", "}")
            val pairs = cleaned.split(",")

            for (pair in pairs) {
                val keyValue = pair.split(":")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim().removeSurrounding("\"")
                    val value = keyValue[1].trim().removeSurrounding("\"")
                    result[key] = value
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON", e)
        }

        return result
    }

    private fun getAllSuggestionsWithPrefix(prefix: String): List<String> {
        val allSuggestions = mutableListOf<String>()

        // Get suggestions from all field types and combine them
        FormDataManager.FieldType.values().forEach { fieldType ->
            if (fieldType != FormDataManager.FieldType.UNKNOWN) {
                val suggestions = formDataManager.getSuggestions(fieldType, prefix)
                allSuggestions.addAll(suggestions)
            }
        }

        // Remove duplicates and sort by relevance (prefix match quality)
        return allSuggestions
            .distinct()
            .sortedWith(compareBy<String> {
                // Prioritize exact prefix matches
                if (it.lowercase().startsWith(prefix.lowercase())) 0 else 1
            }.thenBy { it.length })
            .take(8) // Limit to 8 suggestions for UI
    }

    private var onFieldTypeDetectedCallback: ((FormDataManager.FieldType) -> Unit)? = null

    fun setFieldTypeDetectedCallback(callback: (FormDataManager.FieldType) -> Unit) {
        onFieldTypeDetectedCallback = callback
    }

    private fun notifyFieldTypeDetected(fieldType: FormDataManager.FieldType) {
        Log.d(TAG, "=== FIELD TYPE DETECTED ===")
        Log.d(TAG, "Detected field type: $fieldType")

        // Trigger callback to update suggestions immediately
        onFieldTypeDetectedCallback?.invoke(fieldType)
    }

    fun onWebFieldCompleted(content: String) {
        val field = currentActiveField
        if (field != null && content.trim().length >= 2) {
            Log.d(TAG, "Web field completed - Type: ${field.detectedFieldType}, Content: '$content', Confidence: ${field.confidence}")

            // Learn from web input with confidence weighting
            if (field.confidence > 0.5f) { // Only learn from reasonably confident detections
                formDataManager.learnFromWebInput(field.detectedFieldType, content, field.confidence)
            }
        }
    }

    // ============================================
    // WebView JavaScript Bridge Interface

    /**
     * This would be added to WebView as a JavaScript interface
     */
    class AutofillJavaScriptInterface(private val webFormHandler: WebFormHandler) {

        @android.webkit.JavascriptInterface
        fun onFieldAnalyzed(fieldDataJson: String) {
            Log.d(TAG, "JavaScript bridge called with: $fieldDataJson")
            webFormHandler.processFieldData(fieldDataJson)
        }
    }
}