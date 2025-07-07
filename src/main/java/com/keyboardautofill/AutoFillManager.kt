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
        val suggestionBarView = try {
            val resourceId = inputMethodService.resources.getIdentifier(
                "suggestion_bar", "id", inputMethodService.packageName
            )
            if (resourceId != 0) {
                rootView.findViewById<RecyclerView>(resourceId)
            } else {
                Log.e("SuggestionDebug", "suggestion_bar resource ID not found")
                null
            }
        } catch (e: Exception) {
            Log.e("SuggestionDebug", "Error finding suggestion_bar RecyclerView", e)
            null
        }
        
        if (suggestionBarView != null) {
            Log.d("SuggestionDebug", "suggestion_bar RecyclerView found successfully")
            SuggestionBarUI(inputMethodService, suggestionBarView) { suggestion ->
                handleSuggestionSelected(suggestion)
            }
        } else {
            Log.w("SuggestionDebug", "suggestion_bar RecyclerView not found in layout")
            SuggestionBarUI(inputMethodService, null) { suggestion ->
                handleSuggestionSelected(suggestion)
            }
        }
    }

    private var currentFieldType: FormDataManager.FieldType = FormDataManager.FieldType.UNKNOWN
    private var lastFieldContent = ""
    private var currentFieldHash = ""
    private var lastProcessedFieldHash = ""
    
    // Single save tracking variable
    private var lastSaveTime = 0L
    private var lastSaveContent = ""
    private var lastSaveFieldType: FormDataManager.FieldType = FormDataManager.FieldType.UNKNOWN
    
    // Content backup for reliable saves during transitions
    private var preservedContentForSave = ""
    private var preservedFieldTypeForSave: FormDataManager.FieldType = FormDataManager.FieldType.UNKNOWN

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

        // Get fresh field content for stale state detection
        val newFieldContent = getCurrentFieldContent()
        
        // Detect content mismatch indicating stale session
        val isStaleState = newFieldContent.isEmpty() && lastFieldContent.isNotEmpty()
        
        if (isStaleState) {
            Log.d("SuggestionDebug", "STALE STATE DETECTED: field='$newFieldContent', stale_last='$lastFieldContent'")
            Log.d("SuggestionDebug", "RESETTING stale state variables")
            
            // Reset stale state variables
            lastFieldContent = ""
            lastProcessedFieldHash = ""
            currentFieldHash = ""
        }
        
        // Skip if exact same field, unless we detected stale state
        val shouldSkipDueToHash = (newFieldHash == lastProcessedFieldHash) && !isStaleState
        
        if (shouldSkipDueToHash) {
            Log.d("SuggestionDebug", "Same field hash - skipping")
            return
        }

        // Save previous field content before switching using preserved backup
        if (lastProcessedFieldHash.isNotEmpty() && 
            preservedContentForSave.length >= 2 && 
            preservedFieldTypeForSave != FormDataManager.FieldType.UNKNOWN) {
            
            val currentTime = System.currentTimeMillis()
            
            // Skip if same content was saved recently
            val timeSinceLastSave = currentTime - lastSaveTime
            val isSameContent = (lastSaveContent == preservedContentForSave && lastSaveFieldType == preservedFieldTypeForSave)
            val shouldSkipSave = isSameContent && timeSinceLastSave < 10000  // 10 seconds
            
            if (shouldSkipSave) {
                Log.d("SuggestionDebug", "SKIPPED duplicate field switch save - same content saved ${timeSinceLastSave}ms ago")
                
                // Clear preserved content since we're not saving
                preservedContentForSave = ""
                preservedFieldTypeForSave = FormDataManager.FieldType.UNKNOWN
                Log.d("SuggestionDebug", "🗑Cleared preserved content (duplicate protection)")
            } else {
                Log.d("SuggestionDebug", "IMMEDIATE SAVE on field switch: $preservedFieldTypeForSave = '$preservedContentForSave'")
                formDataManager.saveImmediately(preservedFieldTypeForSave, preservedContentForSave, false)
                
                // Track save to prevent duplicates
                lastSaveTime = currentTime
                lastSaveContent = preservedContentForSave
                lastSaveFieldType = preservedFieldTypeForSave
                
                // Clear preserved content after successful save
                preservedContentForSave = ""
                preservedFieldTypeForSave = FormDataManager.FieldType.UNKNOWN
                Log.d("SuggestionDebug", "🗑Cleared preserved content after save")
            }
        } else {
            Log.d("SuggestionDebug", "⚠No preserved content to save (content='$preservedContentForSave', type=$preservedFieldTypeForSave)")
        }

        // Update tracking 
        currentFieldType = detectedFieldType
        currentFieldHash = newFieldHash
        lastProcessedFieldHash = newFieldHash
        
        // Update field content tracking (already retrieved for stale state detection)
        lastFieldContent = newFieldContent
        Log.d("SuggestionDebug", "Field: $currentFieldType, content: '$lastFieldContent'")

        // Show suggestions for form fields, hide for non-form fields
        if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
            // FORCE CLEAR: Clear any stale UI state on field focus
            suggestionBarUI.clearSuggestions()
            showSuggestionsForField(currentFieldType)
        } else {
            suggestionBarUI.hideSuggestionBar()
            Log.d("SuggestionDebug", "Hidden suggestion bar for unknown field type")
        }
    }

    fun onFieldChanged() {
        // This is called during typing - update our tracking AND refresh suggestions
        val newContent = getCurrentFieldContent()
        Log.d("SuggestionDebug", "onFieldChanged: newContent='$newContent', lastFieldContent='$lastFieldContent'")
        
        if (newContent != lastFieldContent) {
            // Always update lastFieldContent to maintain accurate state
            lastFieldContent = newContent
            Log.d("SuggestionDebug", "Field content updated: '$lastFieldContent'")
            
            // PRESERVE CONTENT: backup for saves only if content is meaningful
            if (newContent.trim().length >= 2 && currentFieldType != FormDataManager.FieldType.UNKNOWN) {
                preservedContentForSave = newContent.trim()
                preservedFieldTypeForSave = currentFieldType
                Log.d("SuggestionDebug", "Preserved for save: $preservedFieldTypeForSave = '$preservedContentForSave'")
            }
            
            // Update suggestions based on current typing (prefix matching)
            if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
                showSuggestionsForField(currentFieldType)
            }
        } else {
            Log.d("SuggestionDebug", "Content unchanged: '$newContent'")
        }
    }

    fun onKeyboardHidden() {
        Log.d("SuggestionDebug", "=== onKeyboardHidden called ===")
        
        if (preservedFieldTypeForSave != FormDataManager.FieldType.UNKNOWN &&
            preservedContentForSave.length >= 2) {
            
            val currentTime = System.currentTimeMillis()
            
            val timeSinceLastSave = currentTime - lastSaveTime
            val isSameContent = (lastSaveContent == preservedContentForSave && lastSaveFieldType == preservedFieldTypeForSave)
            val shouldSkipSave = isSameContent && timeSinceLastSave < 10000  // 10 seconds
            
            if (shouldSkipSave) {
                Log.d("SuggestionDebug", "SKIPPED duplicate keyboard hidden save - same content saved ${timeSinceLastSave}ms ago")
                
                // Clear preserved content since we're not saving
                preservedContentForSave = ""
                preservedFieldTypeForSave = FormDataManager.FieldType.UNKNOWN
                Log.d("SuggestionDebug", "🗑Cleared preserved content (duplicate protection)")
            } else {
                Log.d("SuggestionDebug", "CRITICAL SAVE on keyboard hidden: $preservedFieldTypeForSave = '$preservedContentForSave'")
                formDataManager.saveImmediately(preservedFieldTypeForSave, preservedContentForSave, false)
                
                // Track save to prevent duplicates
                lastSaveTime = currentTime
                lastSaveContent = preservedContentForSave
                lastSaveFieldType = preservedFieldTypeForSave
                
                // Clear preserved content after successful save
                preservedContentForSave = ""
                preservedFieldTypeForSave = FormDataManager.FieldType.UNKNOWN
                Log.d("SuggestionDebug", "Cleared preserved content after critical save")
            }
        } else {
            Log.d("SuggestionDebug", "No preserved content to save on keyboard hidden (content='$preservedContentForSave', type=$preservedFieldTypeForSave)")
        }
    }


    // ============================================
    // Field Completion Detection
    private fun generateFieldHash(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return ""

        // hash - only field type matters
        val inputType = editorInfo.inputType
        val hintText = editorInfo.hintText?.toString()?.lowercase() ?: ""

        return "${inputType}_${hintText.hashCode()}"
    }

    private fun handleSuggestionSelected(suggestion: String) {
        Log.d("SuggestionDebug", "=== SUGGESTION SELECTED CALLBACK ===")
        Log.d("SuggestionDebug", "Field: $currentFieldType, Suggestion: '$suggestion'")

        if (currentFieldType != FormDataManager.FieldType.UNKNOWN) {
            val currentTime = System.currentTimeMillis()
            Log.d("SuggestionDebug", "DELEGATING to confirmSuggestion: $currentFieldType = '$suggestion'")
            formDataManager.confirmSuggestion(currentFieldType, suggestion)
            
            // Update tracking to reflect the new content and save
            lastFieldContent = suggestion
            lastSaveTime = currentTime
            lastSaveContent = suggestion
            lastSaveFieldType = currentFieldType
            
            // Also update preserved content since this is now the current value
            preservedContentForSave = suggestion
            preservedFieldTypeForSave = currentFieldType
            Log.d("SuggestionDebug", "Updated preserved content with suggestion: $currentFieldType = '$suggestion'")
            
            Log.d("SuggestionDebug", "Suggestion click handled via confirmSuggestion - unified counting")
        }
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
        // Get current field content for prefix matching
        val currentContent = getCurrentFieldContent().trim()
        val suggestions = formDataManager.getSuggestions(fieldType, currentContent)

        Log.d("SuggestionDebug", "showSuggestionsForField: fieldType=$fieldType, prefix='$currentContent', suggestions=${suggestions.size} items")
        Log.d("SuggestionDebug", "BACKEND: Generated ${suggestions.size} suggestions: $suggestions")
        
        suggestions.forEachIndexed { index, suggestion ->
            Log.d("SuggestionDebug", "  [$index]: '$suggestion'")
        }

        Log.d("SuggestionDebug", "UI: Sending ${suggestions.size} suggestions to adapter: $suggestions")
        
        if (suggestions.isNotEmpty()) {
            suggestionBarUI.updateSuggestions(suggestions)
            suggestionBarUI.showSuggestionBar()
            Log.d("SuggestionDebug", "✓ Suggestion bar shown with ${suggestions.size} suggestions")
        } else {
            // Hide suggestion bar - no suggestions
            suggestionBarUI.updateSuggestions(emptyList())
            Log.d("SuggestionDebug", "Hidden suggestion bar for $fieldType - no suggestions available")
        }
    }

    // ============================================
    // Public Interface

    fun getCurrentFieldType(): FormDataManager.FieldType = currentFieldType

    fun hasCurrentSuggestions(): Boolean {
        return formDataManager.hasSuggestions(currentFieldType)
    }
}