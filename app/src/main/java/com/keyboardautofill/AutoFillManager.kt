package com.keyboardautofill

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView

/**
 * Main autofill coordination with proper field completion detection
 */
class AutofillManager(
    private val inputMethodService: InputMethodService,
    private val rootView: View
) {

    private val formDataManager = FormDataManager(inputMethodService)
    private val suggestionBarUI = run {
        val suggestionBarView = rootView.findViewById<RecyclerView>(
            rootView.resources.getIdentifier("suggestion_bar", "id", inputMethodService.packageName)
        )
        SuggestionBarUI(inputMethodService, suggestionBarView) { suggestion ->
            handleSuggestionSelected(suggestion)
        }
    }

    private var currentFieldType: FormDataManager.FieldType = FormDataManager.FieldType.UNKNOWN
    private var previousFieldType: FormDataManager.FieldType = FormDataManager.FieldType.UNKNOWN
    private var lastFieldContent = ""
    private var currentFieldHash = ""
    private var lastProcessedFieldHash = ""

    // ============================================
    // Main Integration Points

    fun onFieldFocused(editorInfo: EditorInfo?) {
        Log.d("AutofillFlow", "=== Field Focus ===")

        if (editorInfo == null) {
            suggestionBarUI.hideSuggestionBar()
            return
        }

        val newFieldHash = generateFieldHash(editorInfo)

        // Save previous field data before switching
        if (lastProcessedFieldHash.isNotEmpty() &&
            newFieldHash != lastProcessedFieldHash &&
            currentFieldType != FormDataManager.FieldType.UNKNOWN) {
            saveCurrentFieldContent()
        }

        // Update field tracking
        currentFieldType = formDataManager.detectFieldType(editorInfo)
        currentFieldHash = newFieldHash
        lastProcessedFieldHash = newFieldHash
        lastFieldContent = getCurrentFieldContent()

        Log.d("AutofillFlow", "Field: $currentFieldType, Content: '$lastFieldContent'")

        // Show suggestions for valid fields
        if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
            showSuggestionsForField(currentFieldType)
        } else {
            suggestionBarUI.hideSuggestionBar()
        }
    }

    fun onFieldChanged() {
        val newContent = getCurrentFieldContent()
        if (newContent != lastFieldContent) {
            lastFieldContent = newContent
            Log.d("AutofillFlow", "Content changed: '$lastFieldContent'")

            // Update suggestions for typing
            if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
                showSuggestionsForField(currentFieldType)
            }
        }
    }

    fun onKeyboardHidden() {
        Log.d("AutofillFlow", "=== Keyboard Hidden ===")
        saveCurrentFieldContent()
    }

    // ============================================
    // Field Completion Detection

    private fun saveCurrentFieldContent() {
        val content = lastFieldContent.trim()

        Log.d("AutofillFlow", "Saving field: $currentFieldType = '$content'")

        if (currentFieldType != FormDataManager.FieldType.UNKNOWN && content.isNotBlank()) {
            formDataManager.learnFromInput(currentFieldType, content)
            Log.d("AutofillFlow", "✓ Field content saved")
        }
    }

    private fun generateFieldHash(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return ""

        // Simple field identification
        val packageName = editorInfo.packageName ?: "unknown"
        val fieldId = editorInfo.fieldId
        val inputType = editorInfo.inputType

        return "${packageName}_${fieldId}_${inputType}"
    }

    private fun handleSuggestionSelected(suggestion: String) {
        Log.d("AutofillFlow", "=== Suggestion Selected: '$suggestion' ===")

        // Update tracking
        lastFieldContent = suggestion

        // Confirm with data manager
        formDataManager.confirmSuggestion(currentFieldType, suggestion)

        Log.d("AutofillFlow", "✓ Suggestion confirmed")
    }

    // ============================================
    // Helper fxns

    private fun getCurrentFieldContent(): String {
        val ic = inputMethodService.currentInputConnection ?: return ""

        try {
            // Get text before and after cursor to reconstruct full field content
            val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
            val textAfter = ic.getTextAfterCursor(1000, 0) ?: ""
            val fullText = textBefore.toString() + textAfter.toString()

            Log.d("SuggestionDebug", "Getting field content - before: '$textBefore', after: '$textAfter', full: '$fullText'")
            return fullText
        } catch (e: Exception) {
            Log.e("SuggestionDebug", "Error getting field content", e)
            return ""
        }
    }

    private fun showSuggestionsForField(fieldType: FormDataManager.FieldType) {
        // NEW: Get current field content for prefix matching
        val currentContent = getCurrentFieldContent().trim()
        val suggestions = formDataManager.getSuggestions(fieldType, currentContent)

        Log.d("SuggestionDebug", "Showing suggestions for $fieldType with prefix '$currentContent': ${suggestions.size} items")
        suggestions.forEachIndexed { index, suggestion ->
            Log.d("SuggestionDebug", "  [$index]: '$suggestion'")
        }

        if (suggestions.isNotEmpty()) {
            suggestionBarUI.updateSuggestions(suggestions)
            suggestionBarUI.showSuggestionBar()
            Log.d("SuggestionDebug", "✓ Suggestion bar shown with ${suggestions.size} suggestions")
        } else {
            suggestionBarUI.hideSuggestionBar()
            Log.d("SuggestionDebug", "✗ No suggestions available - hiding suggestion bar")
        }
    }

    // ============================================
    // Public Interface

    fun getCurrentFieldType(): FormDataManager.FieldType = currentFieldType

    fun hasCurrentSuggestions(): Boolean {
        return formDataManager.hasSuggestions(currentFieldType)
    }
}