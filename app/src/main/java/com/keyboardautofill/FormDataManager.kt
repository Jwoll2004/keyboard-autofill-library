package com.keyboardautofill

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo

class FormDataManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("form_autofill", Context.MODE_PRIVATE)
    private val metadataPrefs: SharedPreferences = context.getSharedPreferences("suggestion_metadata", Context.MODE_PRIVATE)

    private val fieldTries: Map<FieldType, SuggestionTrie> = FieldType.values()
        .filter { it != FieldType.UNKNOWN }
        .associateWith { SuggestionTrie() }

    private val hotCache = mutableMapOf<String, List<RankedSuggestion>>()
    private val cacheAccessOrder = mutableListOf<String>()
    private val maxCacheSize = 30

    init {
        initializeTriesFromStorage()
    }

    companion object {
        private const val MAX_STORED_PER_FIELD = 15
        private const val MAX_DISPLAYED_SUGGESTIONS = 6
        private const val MIN_VALUE_LENGTH = 2
    }

    // ============================================
    // Core Data Structures

    data class SuggestionNode(
        val value: String,
        var clickCount: Int = 0,
        var useCount: Int = 0,
        var lastUsed: Long = System.currentTimeMillis()
    ) {
        fun getRankingScore(): Float {
            val clickWeight = 0.5f
            val useWeight = 0.3f
            val recencyWeight = 0.2f

            val clickScore = (clickCount * clickWeight)
            val useScore = (useCount * useWeight)
            val recencyScore = calculateRecency() * recencyWeight

            return (clickScore + useScore + recencyScore).coerceAtLeast(0.1f)
        }

        private fun calculateRecency(): Float {
            val daysSinceUsed = (System.currentTimeMillis() - lastUsed) / (24 * 60 * 60 * 1000f)
            return when {
                daysSinceUsed <= 1 -> 1.0f
                daysSinceUsed <= 7 -> 0.8f
                daysSinceUsed <= 30 -> 0.5f
                else -> 0.2f
            }
        }
    }

    data class RankedSuggestion(
        val value: String,
        val score: Float
    )

    enum class FieldType {
        FIRST_NAME, LAST_NAME, FULL_NAME,
        EMAIL, PHONE,
        ADDRESS, CITY, STATE, ZIP,
        COMPANY, USERNAME,
        UNKNOWN
    }

    // ============================================
    // CASE 1: Manual Input Learning

    fun learnFromInput(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("AutofillStorage", "=== MANUAL INPUT ===")
        Log.d("AutofillStorage", "Field: $fieldType, Value: '$cleanValue'")

        if (!isValidInput(cleanValue, fieldType)) {
            Log.d("AutofillStorage", "Invalid input - skipping")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            // CASE 2: Existing value typed manually
            existing.useCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)
            Log.d("AutofillStorage", "✓ Updated existing: useCount=${existing.useCount}, clicks=${existing.clickCount}")
        } else {
            // CASE 1: New value
            val newNode = SuggestionNode(
                value = cleanValue,
                clickCount = 0,
                useCount = 1,
                lastUsed = System.currentTimeMillis()
            )

            if (addNewSuggestion(fieldType, cleanValue, newNode)) {
                Log.d("AutofillStorage", "✓ Added new: useCount=1")
            } else {
                Log.d("AutofillStorage", "✗ Storage full - entry rejected")
            }
        }

        invalidateCache(fieldType)
    }

    // ============================================
    // CASE 3: Suggestion Confirmation

    fun confirmSuggestion(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("AutofillStorage", "=== SUGGESTION CONFIRMED ===")
        Log.d("AutofillStorage", "Field: $fieldType, Value: '$cleanValue'")

        if (cleanValue.isBlank()) {
            Log.w("AutofillStorage", "Empty confirmation value")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            existing.clickCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)
            Log.d("AutofillStorage", "✓ Confirmed: clickCount=${existing.clickCount}, useCount=${existing.useCount}")
        } else {
            // Edge case: user confirmed a suggestion that doesn't exist in metadata
            val newNode = SuggestionNode(
                value = cleanValue,
                clickCount = 1,
                useCount = 0,
                lastUsed = System.currentTimeMillis()
            )

            if (addNewSuggestion(fieldType, cleanValue, newNode)) {
                Log.d("AutofillStorage", "✓ New from confirmation: clickCount=1")
            }
        }

        invalidateCache(fieldType)
    }

    // ============================================
    // Suggestion Retrieval

    fun getSuggestions(fieldType: FieldType, partialInput: String = ""): List<String> {
        val cacheKey = "${fieldType}_${partialInput.lowercase()}"

        // Check cache
        hotCache[cacheKey]?.let { cached ->
            updateCacheAccess(cacheKey)
            return cached.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        }

        // Get matches from trie
        val trie = fieldTries[fieldType] ?: return emptyList()
        val matchingKeys = if (partialInput.isBlank()) {
            trie.getAllStorageKeys()
        } else {
            trie.findMatches(partialInput)
        }

        // Rank and return
        val rankedSuggestions = matchingKeys.mapNotNull { key ->
            getMetadata(key)?.let { metadata ->
                RankedSuggestion(metadata.value, metadata.getRankingScore())
            }
        }.sortedByDescending { it.score }

        cacheResult(cacheKey, rankedSuggestions)

        val result = rankedSuggestions.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        Log.d("AutofillStorage", "Retrieved ${result.size} suggestions for $fieldType:'$partialInput'")
        return result
    }

    // ============================================
    // Field Type Detection (Simplified)

    fun detectFieldType(editorInfo: EditorInfo?): FieldType {
        if (editorInfo == null) return FieldType.UNKNOWN

        val hint = editorInfo.hintText?.toString()?.lowercase() ?: ""
        val inputType = editorInfo.inputType

        val detected = when {
            // Hint-based detection (primary)
            hint.contains("first") && hint.contains("name") -> FieldType.FIRST_NAME
            hint.contains("last") && hint.contains("name") -> FieldType.LAST_NAME
            hint.contains("full") && hint.contains("name") -> FieldType.FULL_NAME
            hint.contains("email") -> FieldType.EMAIL
            hint.contains("phone") -> FieldType.PHONE
            hint.contains("address") -> FieldType.ADDRESS
            hint.contains("city") -> FieldType.CITY
            hint.contains("state") -> FieldType.STATE
            hint.contains("zip") || hint.contains("postal") -> FieldType.ZIP
            hint.contains("company") -> FieldType.COMPANY
            hint.contains("username") -> FieldType.USERNAME

            // Input type fallback
            else -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> FieldType.EMAIL
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldType.FULL_NAME
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> FieldType.ADDRESS
                else -> if (inputType and InputType.TYPE_CLASS_PHONE != 0) FieldType.PHONE else FieldType.UNKNOWN
            }
        }

        Log.d("AutofillStorage", "Detected: $detected (hint:'$hint')")
        return detected
    }

    fun hasSuggestions(fieldType: FieldType): Boolean {
        return getFieldSuggestions(fieldType).isNotEmpty()
    }

    // ============================================
    // Internal Storage Management

    private fun addNewSuggestion(fieldType: FieldType, value: String, node: SuggestionNode): Boolean {
        val fieldSuggestions = getFieldSuggestions(fieldType).toMutableSet()

        // Remove case-insensitive duplicates
        fieldSuggestions.removeAll { it.equals(value, ignoreCase = true) }
        fieldSuggestions.add(value)

        // Handle overflow
        if (fieldSuggestions.size > MAX_STORED_PER_FIELD) {
            val evicted = evictLowestScoring(fieldType, fieldSuggestions)
            if (evicted != null) {
                fieldSuggestions.remove(evicted)
                Log.d("AutofillStorage", "Evicted: '$evicted' to make room")
            } else {
                return false // Could not make room
            }
        }

        // Store everything
        storeMetadata(generateStorageKey(fieldType, value), node)
        storeFieldSuggestions(fieldType, fieldSuggestions)

        // Update trie
        fieldTries[fieldType]?.insert(value, generateStorageKey(fieldType, value))

        return true
    }

    private fun evictLowestScoring(fieldType: FieldType, suggestions: Set<String>): String? {
        return suggestions.minByOrNull { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            getMetadata(key)?.getRankingScore() ?: 0f
        }?.also { evicted ->
            // Clean up metadata and trie
            val key = generateStorageKey(fieldType, evicted)
            metadataPrefs.edit().remove(key).apply()
            fieldTries[fieldType]?.remove(evicted, key)
        }
    }

    private fun isValidInput(value: String, fieldType: FieldType): Boolean {
        if (value.length < MIN_VALUE_LENGTH) return false
        if (value.length > 100) return false // Reasonable max

        return when (fieldType) {
            FieldType.EMAIL -> value.contains("@") && value.contains(".")
            FieldType.PHONE -> value.any { it.isDigit() }
            else -> value.isNotBlank()
        }
    }

    private fun generateStorageKey(fieldType: FieldType, value: String): String {
        return "${fieldType.name}_${value.lowercase().hashCode()}"
    }

    private fun getMetadata(key: String): SuggestionNode? {
        val stored = metadataPrefs.getString(key, null) ?: return null
        val parts = stored.split("|")

        return try {
            if (parts.size >= 4) {
                SuggestionNode(
                    value = parts[0],
                    clickCount = parts[1].toInt(),
                    useCount = parts[2].toInt(),
                    lastUsed = parts[3].toLong()
                )
            } else null
        } catch (e: Exception) {
            Log.e("AutofillStorage", "Corrupted metadata: $stored", e)
            null
        }
    }

    private fun storeMetadata(key: String, node: SuggestionNode) {
        val serialized = "${node.value}|${node.clickCount}|${node.useCount}|${node.lastUsed}"
        metadataPrefs.edit().putString(key, serialized).apply()
    }

    private fun getFieldSuggestions(fieldType: FieldType): List<String> {
        return prefs.getStringSet(fieldType.name, emptySet())?.toList() ?: emptyList()
    }

    private fun storeFieldSuggestions(fieldType: FieldType, suggestions: Set<String>) {
        prefs.edit().putStringSet(fieldType.name, suggestions).apply()
    }

    // ============================================
    // Cache Management

    private fun cacheResult(key: String, result: List<RankedSuggestion>) {
        if (hotCache.size >= maxCacheSize) {
            val oldestKey = cacheAccessOrder.removeFirstOrNull()
            oldestKey?.let { hotCache.remove(it) }
        }

        hotCache[key] = result
        cacheAccessOrder.add(key)
    }

    private fun updateCacheAccess(key: String) {
        cacheAccessOrder.remove(key)
        cacheAccessOrder.add(key)
    }

    private fun invalidateCache(fieldType: FieldType) {
        val keysToRemove = hotCache.keys.filter { it.startsWith("${fieldType}_") }
        keysToRemove.forEach { key ->
            hotCache.remove(key)
            cacheAccessOrder.remove(key)
        }

        // Compress trie
        fieldTries[fieldType]?.compress()
    }

    private fun initializeTriesFromStorage() {
        FieldType.values().forEach { fieldType ->
            if (fieldType == FieldType.UNKNOWN) return@forEach

            val suggestions = getFieldSuggestions(fieldType)
            val trie = fieldTries[fieldType]

            suggestions.forEach { suggestion ->
                val key = generateStorageKey(fieldType, suggestion)
                trie?.insert(suggestion, key)
            }

            Log.d("AutofillStorage", "Loaded ${suggestions.size} entries for $fieldType")
        }
    }
}