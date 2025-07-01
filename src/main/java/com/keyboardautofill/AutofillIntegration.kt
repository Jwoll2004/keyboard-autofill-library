package com.keyboardautofill

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * Main API for integrating autofill functionality into any custom keyboard
 *
 * Usage:
 * 1. Initialize in onCreateInputView()
 * 2. Call onFieldFocused() in onStartInput()
 * 3. Call onFieldChanged() in onKey() after processing input
 * 4. Call onKeyboardHidden() in onFinishInput()
 */
class AutofillIntegration private constructor(
    private val inputMethodService: InputMethodService,
    private val keyboardRootView: View
) {

    private var autofillManager: AutofillManager? = null
    private var isInitialized = false

    companion object {
        /**
         * Create autofill integration instance
         * Call this in your InputMethodService.onCreateInputView()
         *
         * @param inputMethodService Your InputMethodService instance
         * @param keyboardRootView The root view of your keyboard layout
         * @return AutofillIntegration instance
         */
        @JvmStatic
        fun create(
            inputMethodService: InputMethodService,
            keyboardRootView: View
        ): AutofillIntegration {
            return AutofillIntegration(inputMethodService, keyboardRootView)
        }
    }

    /**
     * Initialize the autofill system
     * Call this after your keyboard view is fully created
     */
    fun initialize(): AutofillIntegration {
        if (!isInitialized) {
            autofillManager = AutofillManager(inputMethodService, keyboardRootView)
            isInitialized = true
        }
        return this
    }

    /**
     * Notify when user focuses on a new input field
     * Call this in your onStartInput() or onStartInputView()
     *
     * @param editorInfo EditorInfo from onStartInput()
     */
    fun onFieldFocused(editorInfo: EditorInfo?) {
        autofillManager?.onFieldFocused(editorInfo)
    }

    /**
     * Notify when field content changes (user typing)
     * Call this in your onKey() method after processing the key
     */
    fun onFieldChanged() {
        autofillManager?.onFieldChanged()
    }

    /**
     * Notify when keyboard is hidden/input session ends
     * Call this in onFinishInput() and onFinishInputView()
     */
    fun onKeyboardHidden() {
        autofillManager?.onKeyboardHidden()
    }

    /**
     * Get current field type for debugging
     */
    fun getCurrentFieldType(): String {
        return autofillManager?.getCurrentFieldType()?.name ?: "UNKNOWN"
    }

    /**
     * Check if current field has suggestions available
     */
    fun hasCurrentSuggestions(): Boolean {
        return autofillManager?.hasCurrentSuggestions() ?: false
    }
}