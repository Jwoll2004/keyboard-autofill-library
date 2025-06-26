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
    private val maxCacheSize = 50

    init {
        // Load existing data into tries on startup
        initializeTriesFromStorage()
    }

    companion object {
        private const val MAX_STORED_PER_FIELD = 10
        private const val MAX_DISPLAYED_SUGGESTIONS = 8
        private const val DECAY_INTERVAL_DAYS = 30
        private const val DECAY_FACTOR = 0.9f
    }

    // ============================================
    // Core Data Structures

    data class SuggestionNode(
        val value: String,
        var clickCount: Int = 0,
        var useCount: Int = 1,
        var lastUsed: Long = System.currentTimeMillis()
    ) {
        fun getRankingScore(): Float {
            // Base scoring remains the same
            val confirmationBonus = clickCount * 0.4f
            val frequencyScore = useCount * 0.35f
            val recencyScore = calculateRecency() * 0.25f

            val totalScore = confirmationBonus + frequencyScore + recencyScore

            // Minimum viable score for new entries
            return totalScore.coerceAtLeast(0.1f)
        }

        private fun calculateRecency(): Float {
            val currentTime = System.currentTimeMillis()
            val daysSinceUsed = (currentTime - lastUsed) / (1000 * 60 * 60 * 24f)

            return when {
                daysSinceUsed <= 1 -> 1.0f      // Used today
                daysSinceUsed <= 7 -> 0.8f      // Used this week
                daysSinceUsed <= 30 -> 0.5f     // Used this month
                daysSinceUsed <= 90 -> 0.2f     // Used this quarter
                else -> 0.1f                    // Older usage
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
    // Simplified Storage Strategy

    fun learnFromInput(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("SuggestionDebug", "=== LEARN FROM INPUT ===")
        Log.d("SuggestionDebug", "Field: $fieldType, Manual entry: '$cleanValue'")

        if (cleanValue.isBlank() || cleanValue.length < 2) {
            Log.d("SuggestionDebug", "Input too short - skipping")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            existing.useCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)
            Log.d("SuggestionDebug", "✅ Updated uses: useCount=${existing.useCount}")
        } else {
            val newNode = SuggestionNode(cleanValue, clickCount = 0, useCount = 1)
            storeMetadata(key, newNode)
            addToFieldList(fieldType, cleanValue)
            Log.d("SuggestionDebug", "✅ New manual entry: useCount=1")
        }

        clearCacheForFieldType(fieldType)
    }

    fun confirmSuggestion(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("SuggestionDebug", "=== CONFIRM SUGGESTION ===")
        Log.d("SuggestionDebug", "Field: $fieldType, Value: '$cleanValue'")

        if (cleanValue.isBlank()) {
            Log.d("SuggestionDebug", "Empty value - skipping")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        Log.d("SuggestionDebug", "Storage key: $key")

        val existing = getMetadata(key)

        if (existing != null) {
            val oldClickCount = existing.clickCount
            existing.clickCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)
            Log.d("SuggestionDebug", "✅ UPDATED: clickCount ${oldClickCount} → ${existing.clickCount}, useCount=${existing.useCount}")
        } else {
            val newNode = SuggestionNode(cleanValue, clickCount = 1, useCount = 0)
            storeMetadata(key, newNode)
            addToFieldList(fieldType, cleanValue)
            Log.d("SuggestionDebug", "✅ NEW ENTRY: clickCount=1, useCount=0")
        }

        clearCacheForFieldType(fieldType)
        Log.d("SuggestionDebug", "=== CONFIRM COMPLETE ===")
    }

    // ============================================
    //  Retrieval with Ranking
    fun getSuggestions(fieldType: FieldType, partialInput: String = ""): List<String> {
        val cacheKey = "${fieldType}_${partialInput}"

        // Check cache first
        val cached = hotCache[cacheKey]
        if (cached != null) {
            updateCacheAccess(cacheKey)
            Log.d("SuggestionDebug", "Cache hit for $cacheKey")
            return cached.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        }

        // NEW: Use trie for efficient prefix matching
        val trie = fieldTries[fieldType]
        if (trie == null) {
            Log.w("SuggestionDebug", "No trie found for $fieldType")
            return emptyList()
        }

        val matchingKeys = if (partialInput.isBlank()) {
            trie.getAllStorageKeys()
        } else {
            trie.findMatches(partialInput)
        }

        Log.d("SuggestionDebug", "Trie found ${matchingKeys.size} matches for '$partialInput' in $fieldType")

        // Get metadata and rank suggestions
        val rankedSuggestions = matchingKeys.mapNotNull { key ->
            val metadata = getMetadata(key)
            if (metadata != null) {
                val score = metadata.getRankingScore()
                Log.d("SuggestionDebug", "Suggestion '${metadata.value}': clicks=${metadata.clickCount}, uses=${metadata.useCount}, score=$score")
                RankedSuggestion(metadata.value, score)
            } else {
                Log.w("SuggestionDebug", "No metadata found for key: $key")
                null
            }
        }.sortedByDescending { it.score }

        // Cache the result and return limited display
        cacheResult(cacheKey, rankedSuggestions)

        val displayList = rankedSuggestions.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        Log.d("SuggestionDebug", "Displaying top ${displayList.size} of ${rankedSuggestions.size} suggestions for $fieldType")

        return displayList
    }

    // ============================================
    // Field Type Detection

    fun detectFieldType(editorInfo: EditorInfo?): FieldType {
        if (editorInfo == null) return FieldType.UNKNOWN

        val hint = editorInfo.hintText?.toString()?.lowercase() ?: ""
        val inputType = editorInfo.inputType

        val detectedType = when {
            hint.contains("first") && hint.contains("name") -> FieldType.FIRST_NAME
            hint.contains("last") && hint.contains("name") -> FieldType.LAST_NAME
            hint.contains("full") && hint.contains("name") -> FieldType.FULL_NAME
            hint.contains("email") -> FieldType.EMAIL
            hint.contains("phone") -> FieldType.PHONE
            hint.contains("address") -> FieldType.ADDRESS
            hint.contains("city") -> FieldType.CITY
            hint.contains("state") -> FieldType.STATE
            hint.contains("zip") -> FieldType.ZIP
            hint.contains("company") -> FieldType.COMPANY
            hint.contains("username") -> FieldType.USERNAME
            else -> {
                val inputVariation = inputType and InputType.TYPE_MASK_VARIATION
                when {
                    inputVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> FieldType.EMAIL
                    inputVariation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldType.FULL_NAME
                    inputVariation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> FieldType.ADDRESS
                    inputType and InputType.TYPE_CLASS_PHONE != 0 -> FieldType.PHONE
                    else -> FieldType.UNKNOWN
                }
            }
        }

        Log.d("SuggestionDebug", "Detected field type: $detectedType for hint: '$hint'")
        return detectedType
    }

    fun hasSuggestions(fieldType: FieldType): Boolean {
        return getSuggestions(fieldType).isNotEmpty()
    }

    // ============================================
    // Internal Storage

    private fun generateStorageKey(fieldType: FieldType, value: String): String {
        return "${fieldType.name}_${value.lowercase().hashCode()}"
    }

    private fun getMetadata(key: String): SuggestionNode? {
        val stored = metadataPrefs.getString(key, null) ?: return null
        val parts = stored.split("|")

        return try {
            when (parts.size) {
                3 -> {
                    // Legacy format: value|frequency|lastUsed -> convert to new format
                    SuggestionNode(
                        value = parts[0],
                        clickCount = 0,
                        useCount = parts[1].toInt(),
                        lastUsed = parts[2].toLong()
                    )
                }
                4 -> {
                    // New format: value|clickCount|useCount|lastUsed
                    SuggestionNode(
                        value = parts[0],
                        clickCount = parts[1].toInt(),
                        useCount = parts[2].toInt(),
                        lastUsed = parts[3].toLong()
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("SuggestionDebug", "Error parsing metadata: $stored", e)
            null
        }
    }

    private fun storeMetadata(key: String, node: SuggestionNode) {
        val serialized = "${node.value}|${node.clickCount}|${node.useCount}|${node.lastUsed}"
        metadataPrefs.edit().putString(key, serialized).apply()
    }

    private fun addToFieldList(fieldType: FieldType, value: String) {
        val listKey = fieldType.name
        val existing = prefs.getStringSet(listKey, mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        // Remove duplicates (case-insensitive) - always allow the new entry
        existing.removeAll { it.lowercase() == value.lowercase() }
        existing.add(value)

        // Apply age decay to all entries before eviction check
        applyAgeDecay(fieldType)

        // If we exceed storage limit, evict the lowest scoring entry
        if (existing.size > MAX_STORED_PER_FIELD) {
            val lowestScoringEntry = findLowestScoringEntry(fieldType, existing)
            if (lowestScoringEntry != null) {
                existing.remove(lowestScoringEntry)
                removeMetadata(fieldType, lowestScoringEntry)

                // NEW: Remove from trie
                val trie = fieldTries[fieldType]
                val keyToRemove = generateStorageKey(fieldType, lowestScoringEntry)
                trie?.remove(lowestScoringEntry, keyToRemove)

                Log.d("SuggestionDebug", "Evicted lowest scoring entry: '$lowestScoringEntry'")
            }
        }

        prefs.edit().putStringSet(listKey, existing).apply()

        // NEW: Add to trie
        val trie = fieldTries[fieldType]
        val storageKey = generateStorageKey(fieldType, value)
        trie?.insert(value, storageKey)

        Log.d("SuggestionDebug", "Stored '$value' in $fieldType. Total entries: ${existing.size}")
    }

    private fun removeMetadata(fieldType: FieldType, value: String) {
        val key = generateStorageKey(fieldType, value)
        metadataPrefs.edit().remove(key).apply()

        // NEW: Remove from trie
        val trie = fieldTries[fieldType]
        trie?.remove(value, key)
    }

    private fun applyAgeDecay(fieldType: FieldType) {
        val allSuggestions = getFieldSuggestions(fieldType)
        val currentTime = System.currentTimeMillis()

        allSuggestions.forEach { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)

            if (metadata != null) {
                val daysSinceLastUsed = (currentTime - metadata.lastUsed) / (1000 * 60 * 60 * 24)

                // Apply decay if entry is old and hasn't been decayed recently
                if (daysSinceLastUsed >= DECAY_INTERVAL_DAYS) {
                    metadata.useCount = (metadata.useCount * DECAY_FACTOR).toInt().coerceAtLeast(1)
                    metadata.clickCount = (metadata.clickCount * DECAY_FACTOR).toInt()
                    storeMetadata(key, metadata)
                    Log.d("SuggestionDebug", "Applied age decay to '$suggestion': uses=${metadata.useCount}, clicks=${metadata.clickCount}")
                }
            }
        }
    }

    private fun findLowestScoringEntry(fieldType: FieldType, entries: Set<String>): String? {
        return entries.minByOrNull { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)
            metadata?.getRankingScore() ?: 0f
        }
    }

    private fun getFieldSuggestions(fieldType: FieldType): List<String> {
        val listKey = fieldType.name
        return prefs.getStringSet(listKey, emptySet())?.toList() ?: emptyList()
    }

    // ============================================
    // Cache Management

    private fun cacheResult(key: String, result: List<RankedSuggestion>) {
        if (hotCache.size >= maxCacheSize) {
            // Remove oldest cache entry
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

    private fun clearCacheForFieldType(fieldType: FieldType) {
        val keysToRemove = hotCache.keys.filter { it.startsWith("${fieldType}_") }
        keysToRemove.forEach { key ->
            hotCache.remove(key)
            cacheAccessOrder.remove(key)
        }

        // NEW: Compress trie to reclaim memory
        val trie = fieldTries[fieldType]
        trie?.compress()

        Log.d("SuggestionDebug", "Cleared cache entries for $fieldType and compressed trie")
    }

    // ============================================
    // Trie utils

    private fun initializeTriesFromStorage() {
        Log.d("SuggestionDebug", "Initializing tries from storage...")

        FieldType.values().forEach { fieldType ->
            if (fieldType == FieldType.UNKNOWN) return@forEach

            val suggestions = getFieldSuggestions(fieldType)
            val trie = fieldTries[fieldType]

            suggestions.forEach { suggestion ->
                val key = generateStorageKey(fieldType, suggestion)
                trie?.insert(suggestion, key)
            }

            Log.d("SuggestionDebug", "Loaded ${suggestions.size} suggestions into $fieldType trie")
        }
    }
}