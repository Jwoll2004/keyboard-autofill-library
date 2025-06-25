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
        console.log('AutofillDetector: Script injected');
        
        // Enhanced field detection
        function analyzeField(input, index) {
            // Get computed styles
            const styles = window.getComputedStyle(input);
            const isVisible = styles.display !== 'none' && 
                            styles.visibility !== 'hidden' && 
                            input.offsetWidth > 0;
            
            if (!isVisible) return null;
            
            // Get field boundaries
            const rect = input.getBoundingClientRect();
            
            // Enhanced label detection
            function getFieldLabel(input) {
                // Multiple strategies for label detection
                const strategies = [
                    // Strategy 1: Label with 'for' attribute
                    () => {
                        if (input.id) {
                            const label = document.querySelector(`label[for="\${'$'}{input.id}"]`);
                            if (label) return label.textContent.trim();
                        }
                    },
                    // Strategy 2: Google Forms
                    () => {
                        const googleLabel = input.closest('.AgroKb')?.parentElement?.querySelector('.M7eMe');
                        if (googleLabel) return googleLabel.textContent.trim();
                    },
                    // Strategy 3: Parent label
                    () => {
                        const parentLabel = input.closest('label');
                        if (parentLabel) {
                            const text = Array.from(parentLabel.childNodes)
                                .filter(node => node.nodeType === 3)
                                .map(node => node.textContent.trim())
                                .join(' ');
                            if (text) return text;
                        }
                    },
                    // Strategy 4: Previous sibling
                    () => {
                        let prev = input.previousElementSibling;
                        while (prev && prev.tagName !== 'LABEL') {
                            if (prev.textContent.trim()) return prev.textContent.trim();
                            prev = prev.previousElementSibling;
                        }
                        if (prev) return prev.textContent.trim();
                    },
                    // Strategy 5: Aria label
                    () => input.getAttribute('aria-label'),
                    // Strategy 6: Placeholder
                    () => input.placeholder,
                    // Strategy 7: Title
                    () => input.title
                ];
                
                for (const strategy of strategies) {
                    const label = strategy();
                    if (label && label.trim()) return label.trim();
                }
                
                return '';
            }
            
            const fieldData = {
                index: index,
                type: input.type || 'text',
                name: input.name || '',
                id: input.id || '',
                className: input.className || '',
                placeholder: input.placeholder || '',
                label: getFieldLabel(input),
                autocomplete: input.autocomplete || '',
                pattern: input.pattern || '',
                maxLength: input.maxLength || -1,
                ariaLabel: input.getAttribute('aria-label') || '',
                isRequired: input.required || input.getAttribute('aria-required') === 'true',
                isGoogleForm: !!input.closest('.freebirdFormviewerViewItemsItemItem'),
                position: {
                    top: rect.top,
                    left: rect.left,
                    width: rect.width,
                    height: rect.height
                },
                value: input.value ? '[has value]' : '[empty]'
            };
            
            return fieldData;
        }
        
        // Detect all fields
        function detectAllFields() {
            const inputs = document.querySelectorAll(
                'input[type="text"], input[type="email"], input[type="tel"], ' +
                'input[type="number"], input[type="url"], input[type="search"], ' +
                'input:not([type]), textarea'
            );
            
            console.log(`AutofillDetector: Found \${'$'}{inputs.length} potential input fields`);
            
            const fields = [];
            inputs.forEach((input, index) => {
                const fieldData = analyzeField(input, index);
                if (fieldData) {
                    fields.push(fieldData);
                }
            });
            
            // Send batch data
            if (window.$JS_INTERFACE_NAME && fields.length > 0) {
                window.$JS_INTERFACE_NAME.onBatchFieldsDetected(JSON.stringify(fields));
            }
            
            return fields;
        }
        
        // Initial detection
        setTimeout(detectAllFields, 100);
        
        // Detect on focus
        document.addEventListener('focusin', (e) => {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
                console.log('AutofillDetector: Field focused');
                const fieldData = analyzeField(e.target, -1);
                if (fieldData && window.$JS_INTERFACE_NAME) {
                    window.$JS_INTERFACE_NAME.onFieldDetected(JSON.stringify(fieldData));
                }
            }
        });
        
        // Return detection count
        return detectAllFields().length;
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