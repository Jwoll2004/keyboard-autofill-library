package com.keyboardautofill.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class WebFormDetector(private val context: Context) {

    companion object {
        private const val TAG = "WebFormDetector"
        private const val JS_INTERFACE_NAME = "AutofillBridge"
    }

    // JavaScript to inject for form field detection
    private val formDetectionScript = """
        (function() {
            // Helper to get field label
            function getFieldLabel(input) {
                // Check for associated label
                if (input.id) {
                    const label = document.querySelector('label[for="' + input.id + '"]');
                    if (label) return label.textContent.trim();
                }
                
                // Check for Google Forms specific structure
                const googleFormLabel = input.closest('.AgroKb')?.parentElement?.querySelector('.M7eMe');
                if (googleFormLabel) return googleFormLabel.textContent.trim();
                
                // Check for parent label
                const parentLabel = input.closest('label');
                if (parentLabel) return parentLabel.textContent.trim();
                
                // Check for aria-label
                if (input.getAttribute('aria-label')) return input.getAttribute('aria-label');
                
                // Check for placeholder
                if (input.placeholder) return input.placeholder;
                
                return '';
            }
            
            // Analyze single field
            function analyzeField(input) {
                const fieldData = {
                    type: input.type || 'text',
                    name: input.name || '',
                    id: input.id || '',
                    className: input.className || '',
                    placeholder: input.placeholder || '',
                    label: getFieldLabel(input),
                    autocomplete: input.autocomplete || '',
                    ariaLabel: input.getAttribute('aria-label') || '',
                    isRequired: input.required || false,
                    isGoogleForm: !!input.closest('.freebirdFormviewerViewItemsItemItem')
                };
                
                // Log to console for debugging
                console.log('WebForm Field Detected:', fieldData);
                
                // Send to Android
                if (window.$JS_INTERFACE_NAME) {
                    window.$JS_INTERFACE_NAME.onFieldDetected(JSON.stringify(fieldData));
                }
            }
            
            // Find all input fields
            function detectAllFields() {
                const inputs = document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], input[type="number"], input[type="url"], input:not([type]), textarea');
                
                console.log('WebForm Detection: Found ' + inputs.length + ' input fields');
                
                inputs.forEach((input, index) => {
                    setTimeout(() => analyzeField(input), index * 50); // Stagger to avoid blocking
                });
            }
            
            // Detect focused field
            function detectFocusedField() {
                const activeElement = document.activeElement;
                if (activeElement && (activeElement.tagName === 'INPUT' || activeElement.tagName === 'TEXTAREA')) {
                    analyzeField(activeElement);
                }
            }
            
            // Run detection
            detectAllFields();
            
            // Monitor for dynamic fields
            const observer = new MutationObserver((mutations) => {
                let shouldRedetect = false;
                mutations.forEach((mutation) => {
                    if (mutation.addedNodes.length > 0) {
                        mutation.addedNodes.forEach((node) => {
                            if (node.querySelector && node.querySelector('input, textarea')) {
                                shouldRedetect = true;
                            }
                        });
                    }
                });
                if (shouldRedetect) {
                    setTimeout(detectAllFields, 500);
                }
            });
            
            observer.observe(document.body, { childList: true, subtree: true });
            
            // Expose function for on-demand detection
            window.detectWebFormFields = detectFocusedField;
        })();
    """.trimIndent()

    private val handler = Handler(Looper.getMainLooper())
    private val detectedFields = mutableMapOf<String, String>()

    private fun storeFieldDetection(fieldType: String, fieldData: JSONObject) {
        val key = "${fieldData.optString("id")}_${fieldData.optString("name")}"
        detectedFields[key] = fieldType
    }

    // JavaScript interface for receiving data from web page
    inner class AutofillJavaScriptInterface {
        @JavascriptInterface
        fun onFieldDetected(fieldDataJson: String) {
            handler.post {
                try {
                    val fieldData = JSONObject(fieldDataJson)
                    val fieldType = determineFieldType(fieldData)

                    Log.d(TAG, "╔══════════════════════════════════════")
                    Log.d(TAG, "║ WEB FORM FIELD DETECTED")
                    Log.d(TAG, "╠══════════════════════════════════════")
                    Log.d(TAG, "║ Type: ${fieldData.optString("type")}")
                    Log.d(TAG, "║ Name: ${fieldData.optString("name")}")
                    Log.d(TAG, "║ ID: ${fieldData.optString("id")}")
                    Log.d(TAG, "║ Label: ${fieldData.optString("label")}")
                    Log.d(TAG, "║ Placeholder: ${fieldData.optString("placeholder")}")
                    Log.d(TAG, "║ Autocomplete: ${fieldData.optString("autocomplete")}")
                    Log.d(TAG, "║ Class: ${fieldData.optString("className")}")
                    Log.d(TAG, "║ Required: ${fieldData.optBoolean("isRequired")}")
                    Log.d(TAG, "║ Google Form: ${fieldData.optBoolean("isGoogleForm")}")
                    Log.d(TAG, "╠══════════════════════════════════════")
                    Log.d(TAG, "║ DETECTED AS: $fieldType")
                    Log.d(TAG, "╚══════════════════════════════════════")

                    // Store detected field info
                    storeFieldDetection(fieldType, fieldData)

                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing field data", e)
                }
            }
        }

        @JavascriptInterface
        fun onBatchFieldsDetected(fieldsJson: String) {
            handler.post {
                try {
                    val fields = JSONArray(fieldsJson)
                    Log.d(TAG, "╔══════════════════════════════════════")
                    Log.d(TAG, "║ BATCH FIELD DETECTION")
                    Log.d(TAG, "║ Total fields found: ${fields.length()}")
                    Log.d(TAG, "╚══════════════════════════════════════")

                    for (i in 0 until fields.length()) {
                        onFieldDetected(fields.getJSONObject(i).toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing batch fields", e)
                }
            }
        }
    }

    private fun determineFieldType(fieldData: JSONObject): String {
        val type = fieldData.optString("type").lowercase()
        val name = fieldData.optString("name").lowercase()
        val id = fieldData.optString("id").lowercase()
        val label = fieldData.optString("label").lowercase()
        val placeholder = fieldData.optString("placeholder").lowercase()
        val autocomplete = fieldData.optString("autocomplete").lowercase()

        // Combined text for analysis
        val combinedText = "$name $id $label $placeholder"

        return when {
            // Email detection
            type == "email" || autocomplete == "email" ||
                    combinedText.contains("email") || combinedText.contains("e-mail") -> "EMAIL"

            // Phone detection
            type == "tel" || autocomplete.contains("tel") ||
                    combinedText.contains("phone") || combinedText.contains("mobile") -> "PHONE"

            // Name detection
            autocomplete.contains("given-name") ||
                    (combinedText.contains("first") && combinedText.contains("name")) -> "FIRST_NAME"

            autocomplete.contains("family-name") ||
                    (combinedText.contains("last") && combinedText.contains("name")) -> "LAST_NAME"

            autocomplete == "name" ||
                    (combinedText.contains("full") && combinedText.contains("name")) ||
                    (combinedText.contains("name") && !combinedText.contains("user")) -> "FULL_NAME"

            // Address detection
            autocomplete.contains("street-address") ||
                    combinedText.contains("address") -> "ADDRESS"

            autocomplete.contains("address-level2") ||
                    combinedText.contains("city") -> "CITY"

            autocomplete.contains("address-level1") ||
                    combinedText.contains("state") || combinedText.contains("province") -> "STATE"

            autocomplete.contains("postal-code") ||
                    combinedText.contains("zip") || combinedText.contains("postal") -> "ZIP"

            // Other fields
            combinedText.contains("company") || combinedText.contains("organization") -> "COMPANY"

            combinedText.contains("username") || combinedText.contains("user") -> "USERNAME"

            else -> "UNKNOWN"
        }
    }

    fun getInjectionScript(): String = formDetectionScript

    fun getJavaScriptInterface(): Any = AutofillJavaScriptInterface()

    fun getInterfaceName(): String = JS_INTERFACE_NAME
}