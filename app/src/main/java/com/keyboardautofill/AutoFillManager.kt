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
        Log.d("SuggestionDebug", "=== onFieldFocused called ===")

        if (editorInfo == null) {
            suggestionBarUI.hideSuggestionBar()
            return
        }

        val newFieldHash = generateFieldHash(editorInfo)
        val detectedFieldType = formDataManager.detectFieldType(editorInfo)

        // Skip if same field AND same type (prevents duplicate processing)
        if (newFieldHash == lastProcessedFieldHash && detectedFieldType == currentFieldType) {
            Log.d("SuggestionDebug", "Same field and type - skipping")
            return
        }

        // Save previous field only if we have meaningful data
        if (lastProcessedFieldHash.isNotEmpty() &&
            previousFieldType != FormDataManager.FieldType.UNKNOWN &&
            lastFieldContent.trim().length >= 2) {

            Log.d("SuggestionDebug", "Saving previous field: $previousFieldType = '$lastFieldContent'")
            formDataManager.learnFromInput(previousFieldType, lastFieldContent.trim())
        }

        // Update tracking
        previousFieldType = currentFieldType
        currentFieldType = detectedFieldType
        currentFieldHash = newFieldHash
        lastProcessedFieldHash = newFieldHash
        lastFieldContent = getCurrentFieldContent()

        Log.d("SuggestionDebug", "Field: $currentFieldType, content: '$lastFieldContent'")

        // Show suggestions
        if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
            showSuggestionsForField(currentFieldType)
        } else {
            suggestionBarUI.hideSuggestionBar()
        }
    }

    fun onFieldChanged() {
        // This is called during typing - update our tracking AND refresh suggestions
        val newContent = getCurrentFieldContent()
        if (newContent != lastFieldContent) {
            lastFieldContent = newContent
            Log.d("SuggestionDebug", "Field content updated: '$lastFieldContent'")

            // NEW: Update suggestions based on current typing
            if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
                showSuggestionsForField(currentFieldType)
            }
        }
    }

    fun onKeyboardHidden() {
        Log.d("SuggestionDebug", "=== onKeyboardHidden called ===")
        // User finished input session - save current field
        saveCurrentField()
    }

    // ============================================
    // Field Completion Detection

    private fun savePreviousFieldIfCompleted() {
        val contentToSave = lastFieldContent.trim()

        Log.d("SuggestionDebug", "=== savePreviousFieldIfCompleted ===")
        Log.d("SuggestionDebug", "Previous field type: $previousFieldType")
        Log.d("SuggestionDebug", "Content to save: '$contentToSave'")

        if (previousFieldType != FormDataManager.FieldType.UNKNOWN &&
            contentToSave.isNotBlank() &&
            contentToSave.length >= 2) { // Minimum 2 characters

            Log.d("SuggestionDebug", "✓ Saving completed field - type: $previousFieldType, content: '$contentToSave'")
            formDataManager.learnFromInput(previousFieldType, contentToSave)
        } else {
            Log.d("SuggestionDebug", "✗ Not saving field - invalid conditions")
        }

        // Update tracking
        previousFieldType = currentFieldType
    }

    private fun saveCurrentField() {
        val contentToSave = getCurrentFieldContent().trim()

        Log.d("SuggestionDebug", "=== saveCurrentField ===")
        Log.d("SuggestionDebug", "Current field type: $currentFieldType")
        Log.d("SuggestionDebug", "Content to save: '$contentToSave'")

        if (currentFieldType != FormDataManager.FieldType.UNKNOWN &&
            contentToSave.isNotBlank() &&
            contentToSave.length >= 2) {

            Log.d("SuggestionDebug", "✓ Saving current field - type: $currentFieldType, content: '$contentToSave'")
            formDataManager.learnFromInput(currentFieldType, contentToSave)
        } else {
            Log.d("SuggestionDebug", "✗ Not saving current field - invalid conditions")
        }
    }

    private fun generateFieldHash(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return ""

        // Simplified hash - only field type matters for suggestions
        val inputType = editorInfo.inputType
        val hintText = editorInfo.hintText?.toString()?.lowercase() ?: ""

        return "${inputType}_${hintText.hashCode()}"
    }

    private fun handleSuggestionSelected(suggestion: String) {
        Log.d("SuggestionDebug", "=== SUGGESTION SELECTED CALLBACK ===")
        Log.d("SuggestionDebug", "Field: $currentFieldType, Suggestion: '$suggestion'")

        formDataManager.confirmSuggestion(currentFieldType, suggestion)

        Log.d("SuggestionDebug", "Suggestion confirmed for ranking")
    }

    // ============================================
    // Helper fxns

    private fun getCurrentFieldContent(): String {
        val ic = inputMethodService.currentInputConnection ?: return ""

        try {
            // Get selected text first - if user selected text, that's the current content
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                return selectedText.toString()
            }

            // Get text before cursor - this is usually the field content
            val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""

            // Only get text after if cursor is in middle of field
            val textAfter = ic.getTextAfterCursor(100, 0) ?: ""

            val fullText = textBefore.toString() + textAfter.toString()
            Log.d("SuggestionDebug", "Field content: before='$textBefore', after='$textAfter', full='$fullText'")

            return fullText.trim()
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