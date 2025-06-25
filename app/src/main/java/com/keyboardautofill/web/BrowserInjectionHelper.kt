package com.keyboardautofill.web

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Helper for injecting analysis into browsers using accessibility services
 * Alternative approach when WebView integration is not available
 */
class BrowserInjectionHelper(private val context: Context) {

    companion object {
        private const val TAG = "BrowserInjectionHelper"
    }

    /**
     * Analyze current browser page using accessibility services
     * This is a fallback when direct WebView access is not available
     */
    fun analyzeBrowserPage(): List<WebFormField> {
        Log.d(TAG, "=== analyzeBrowserPage ===")

        val detectedFields = mutableListOf<WebFormField>()

        // This would require accessibility service permissions
        // For now, return mock data for testing
        Log.d(TAG, "Browser page analysis would happen here")
        Log.d(TAG, "Note: Requires accessibility service integration")

        return detectedFields
    }

    /**
     * JavaScript injection template for different browsers
     */
    fun getInjectionScript(): String {
        return """
            (function() {
                console.log('Autofill: Starting browser analysis');
                
                // Enhanced field detection with multiple strategies
                function detectAllFormFields() {
                    const fields = [];
                    const inputs = document.querySelectorAll('input, textarea');
                    
                    inputs.forEach((input, index) => {
                        if (input.type === 'hidden' || input.style.display === 'none') {
                            return; // Skip hidden fields
                        }
                        
                        const fieldData = {
                            id: input.id || 'field_' + index,
                            type: input.type || 'text',
                            placeholder: input.placeholder || '',
                            name: input.name || '',
                            className: input.className || '',
                            autocomplete: input.autocomplete || '',
                            value: input.value || '',
                            label: findLabel(input),
                            isRequired: input.required || input.getAttribute('aria-required') === 'true',
                            isVisible: isElementVisible(input)
                        };
                        
                        if (fieldData.isVisible) {
                            fields.push(fieldData);
                        }
                    });
                    
                    return fields;
                }
                
                function findLabel(input) {
                    // Multiple label detection strategies
                    let label = '';
                    
                    // 1. aria-labelledby
                    const ariaLabelledBy = input.getAttribute('aria-labelledby');
                    if (ariaLabelledBy) {
                        const labelElement = document.getElementById(ariaLabelledBy);
                        if (labelElement) {
                            label = labelElement.textContent.trim();
                        }
                    }
                    
                    // 2. associated label element
                    if (!label && input.id) {
                        const labelElement = document.querySelector('label[for="' + input.id + '"]');
                        if (labelElement) {
                            label = labelElement.textContent.trim();
                        }
                    }
                    
                    // 3. parent label
                    if (!label) {
                        const parentLabel = input.closest('label');
                        if (parentLabel) {
                            label = parentLabel.textContent.replace(input.value, '').trim();
                        }
                    }
                    
                    // 4. Google Forms specific (.M7eMe class)
                    if (!label) {
                        const container = input.closest('.Qr7Oae, .freebirdFormviewerViewItemsItemItem');
                        if (container) {
                            const googleLabel = container.querySelector('.M7eMe');
                            if (googleLabel) {
                                label = googleLabel.textContent.trim();
                            }
                        }
                    }
                    
                    // 5. Previous sibling text content
                    if (!label) {
                        let sibling = input.previousElementSibling;
                        while (sibling && !label) {
                            const text = sibling.textContent.trim();
                            if (text && text.length < 100) { // Reasonable label length
                                label = text;
                                break;
                            }
                            sibling = sibling.previousElementSibling;
                        }
                    }
                    
                    // 6. aria-label as fallback
                    if (!label) {
                        label = input.getAttribute('aria-label') || '';
                    }
                    
                    return label;
                }
                
                function isElementVisible(element) {
                    const style = window.getComputedStyle(element);
                    return style.display !== 'none' && 
                           style.visibility !== 'hidden' && 
                           style.opacity !== '0' &&
                           element.offsetWidth > 0 && 
                           element.offsetHeight > 0;
                }
                
                // Detect fields and send to native code
                const allFields = detectAllFormFields();
                console.log('Autofill: Detected ' + allFields.length + ' form fields');
                
                // Focus event listener for real-time detection
                document.addEventListener('focusin', function(event) {
                    if ((event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') && 
                        event.target.type !== 'hidden') {
                        
                        const fieldData = {
                            id: event.target.id || '',
                            type: event.target.type || 'text',
                            placeholder: event.target.placeholder || '',
                            name: event.target.name || '',
                            className: event.target.className || '',
                            autocomplete: event.target.autocomplete || '',
                            label: findLabel(event.target),
                            isRequired: event.target.required
                        };
                        
                        // Send to native code if bridge exists
                        if (typeof AutofillBridge !== 'undefined') {
                            AutofillBridge.onFieldAnalyzed(JSON.stringify(fieldData));
                        } else {
                            console.log('Autofill: Field focused but no bridge available', fieldData);
                        }
                    }
                });
                
                console.log('Autofill: Field detection setup complete');
                return allFields;
            })();
        """
    }

    /**
     * Chrome-specific injection method
     */
    fun injectIntoChrome(): Boolean {
        Log.d(TAG, "Attempting Chrome injection")

        // This would use Chrome's developer tools protocol or custom tabs
        // Implementation depends on available APIs

        return false // Placeholder
    }

    /**
     * Firefox-specific injection method
     */
    fun injectIntoFirefox(): Boolean {
        Log.d(TAG, "Attempting Firefox injection")

        // Firefox WebExtension approach or custom implementation

        return false // Placeholder
    }

    data class WebFormField(
        val id: String,
        val type: String,
        val placeholder: String,
        val label: String,
        val name: String,
        val className: String,
        val isRequired: Boolean,
        val isVisible: Boolean
    )
}