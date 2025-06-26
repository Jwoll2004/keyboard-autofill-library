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
    private var fieldContentAtFocus = ""
    private var fieldContentCache = mutableMapOf<String, String>()

    // ============================================
    // Main Integration Points

    fun onFieldFocused(editorInfo: EditorInfo?) {
        Log.d("SuggestionDebug", "=== onFieldFocused called ===")
        Log.d("SuggestionDebug", "EditorInfo: ${editorInfo?.let { "package=${it.packageName}, fieldId=${it.fieldId}, hint='${it.hintText}'" } ?: "null"}")

        if (editorInfo == null) {
            Log.d("SuggestionDebug", "EditorInfo is null - hiding suggestions")
            suggestionBarUI.hideSuggestionBar()
            return
        }

        val newFieldHash = generateFieldHash(editorInfo)
        Log.d("SuggestionDebug", "New field hash: $newFieldHash")
        Log.d("SuggestionDebug", "Last processed hash: $lastProcessedFieldHash")

        // Only process if this is actually a different field OR if we haven't processed any field yet
        if (newFieldHash == lastProcessedFieldHash && lastProcessedFieldHash.isNotEmpty()) {
            Log.d("SuggestionDebug", "Same field as last time - skipping duplicate processing")
            return
        }

        // Save previous field data before switching (if we have valid previous data)
        if (lastProcessedFieldHash.isNotEmpty() && previousFieldType != FormDataManager.FieldType.UNKNOWN) {
            Log.d("SuggestionDebug", "Saving previous field before switching")
            savePreviousFieldIfCompleted()
        }

        // Update current field info - ALWAYS detect field type, even for first field
        val detectedFieldType = formDataManager.detectFieldType(editorInfo)
        currentFieldType = detectedFieldType
        currentFieldHash = newFieldHash
        lastProcessedFieldHash = newFieldHash

        Log.d("SuggestionDebug", "Field focused - type: $currentFieldType, hash: $currentFieldHash")
        Log.d("SuggestionDebug", "Previous field type: $previousFieldType")

        // Get current field content immediately
        lastFieldContent = getCurrentFieldContent()
        fieldContentAtFocus = lastFieldContent
        fieldContentCache[currentFieldHash] = lastFieldContent
        Log.d("SuggestionDebug", "Cached initial content for field: '$lastFieldContent'")
        Log.d("SuggestionDebug", "Current field content on focus: '$lastFieldContent'")

        when (currentFieldType) {
            FormDataManager.FieldType.UNKNOWN -> {
                suggestionBarUI.hideSuggestionBar()
                Log.d("SuggestionDebug", "Unknown field type - hiding suggestions")
            }
            else -> {
                showSuggestionsForField(currentFieldType)
            }
        }

        // Update tracking for next field change
        previousFieldType = currentFieldType
    }

    fun onFieldChanged() {
        val newContent = getCurrentFieldContent()
        if (newContent != lastFieldContent) {
            lastFieldContent = newContent

            // Update cache with latest content
            if (currentFieldHash.isNotEmpty()) {
                fieldContentCache[currentFieldHash] = newContent
            }

            Log.d("SuggestionDebug", "Field content updated and cached: '$lastFieldContent'")

            // Update suggestions based on current typing
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
        // Get content from cache first, then fallback to lastFieldContent
        var contentToSave = ""

        if (lastProcessedFieldHash.isNotEmpty()) {
            contentToSave = fieldContentCache[lastProcessedFieldHash]?.trim() ?: ""
        }

        if (contentToSave.isBlank()) {
            contentToSave = lastFieldContent.trim()
        }

        Log.d("SuggestionDebug", "=== savePreviousFieldIfCompleted ===")
        Log.d("SuggestionDebug", "Previous field type: $previousFieldType")
        Log.d("SuggestionDebug", "Content to save: '$contentToSave'")

        if (previousFieldType != FormDataManager.FieldType.UNKNOWN &&
            contentToSave.isNotBlank() &&
            contentToSave.length >= 2) {

            Log.d("SuggestionDebug", "✓ Saving completed field - type: $previousFieldType, content: '$contentToSave'")
            formDataManager.learnFromInput(previousFieldType, contentToSave)

            // Clear cache for processed field
            fieldContentCache.remove(lastProcessedFieldHash)
        } else {
            Log.d("SuggestionDebug", "✗ Not saving field - invalid conditions")
        }

        // Update tracking
        previousFieldType = currentFieldType
    }

    private fun saveCurrentField() {
        // Try multiple methods to get content, with fallbacks
        var contentToSave = getCurrentFieldContent().trim()

        // Fallback 1: Use cached content if current retrieval fails
        if (contentToSave.isBlank() && currentFieldHash.isNotEmpty()) {
            contentToSave = fieldContentCache[currentFieldHash]?.trim() ?: ""
            Log.d("SuggestionDebug", "Using cached content: '$contentToSave'")
        }

        // Fallback 2: Use last known field content
        if (contentToSave.isBlank()) {
            contentToSave = lastFieldContent.trim()
            Log.d("SuggestionDebug", "Using lastFieldContent: '$contentToSave'")
        }

        Log.d("SuggestionDebug", "=== saveCurrentField ===")
        Log.d("SuggestionDebug", "Current field type: $currentFieldType")
        Log.d("SuggestionDebug", "Content to save: '$contentToSave'")

        if (currentFieldType != FormDataManager.FieldType.UNKNOWN &&
            contentToSave.isNotBlank() &&
            contentToSave.length >= 2) {

            Log.d("SuggestionDebug", "✓ Saving current field - type: $currentFieldType, content: '$contentToSave'")
            formDataManager.learnFromInput(currentFieldType, contentToSave)

            // Clear cache for this field after successful save
            fieldContentCache.remove(currentFieldHash)
        } else {
            Log.d("SuggestionDebug", "✗ Not saving current field - invalid conditions")
        }
    }

    private fun generateFieldHash(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return ""

        // Create a more comprehensive hash to uniquely identify fields
        val packageName = editorInfo.packageName ?: "unknown"
        val fieldId = editorInfo.fieldId
        val inputType = editorInfo.inputType
        val hintText = editorInfo.hintText?.toString() ?: ""

        val hash = "${packageName}_${fieldId}_${inputType}_${hintText.hashCode()}"
        Log.d("SuggestionDebug", "Generated field hash: $hash")
        return hash
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
            // Get text before and after cursor to reconstruct full field content
            val textBefore = ic.getTextBeforeCursor(5000, 0) ?: ""
            val selectedText = ic.getSelectedText(0) ?: ""
            val textAfter = ic.getTextAfterCursor(5000, 0) ?: ""
            val fullText = textBefore.toString() + selectedText.toString() + textAfter.toString()

            Log.d("SuggestionDebug", "Field content - before: '$textBefore', selected: '$selectedText', after: '$textAfter'")
            Log.d("SuggestionDebug", "Full reconstructed content: '$fullText'")

            return fullText.trim()
        } catch (e: Exception) {
            Log.e("SuggestionDebug", "Error getting field content", e)
            return lastFieldContent
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