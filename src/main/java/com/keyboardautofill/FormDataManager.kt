package com.keyboardautofill

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo

class FormDataManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("form_autofill", Context.MODE_PRIVATE)
    private val metadataPrefs: SharedPreferences = context.getSharedPreferences("suggestion_metadata", Context.MODE_PRIVATE)

    private val fieldTries = mutableMapOf<FieldType, SuggestionTrie>()

    private fun getOrCreateTrie(fieldType: FieldType): SuggestionTrie {
        return fieldTries.getOrPut(fieldType) { 
            SuggestionTrie().also { trie ->
                loadTrieFromStorage(fieldType, trie)
            }
        }
    }

    private fun loadTrieFromStorage(fieldType: FieldType, trie: SuggestionTrie) {
        val suggestions = getFieldSuggestions(fieldType)
        suggestions.forEach { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            trie.insert(suggestion, key)
        }
        Log.d("SuggestionDebug", "Lazy loaded ${suggestions.size} suggestions into $fieldType trie")
    }

    private val hotCache = mutableMapOf<String, List<RankedSuggestion>>()
    private val cacheAccessOrder = mutableListOf<String>()
    private val maxCacheSize = 50


    companion object {
        private const val MAX_STORED_PER_FIELD = 10
        private const val MAX_DISPLAYED_SUGGESTIONS = 8
        private const val DECAY_INTERVAL_DAYS = 30
        private const val DECAY_FACTOR = 0.9f
        
        // Text length validation constants
        private const val MAX_NAME_LENGTH = 50
        private const val MAX_EMAIL_LENGTH = 254
        private const val MAX_PHONE_LENGTH = 20
        private const val MAX_ADDRESS_LENGTH = 200
        private const val MAX_CITY_LENGTH = 50
        private const val MAX_STATE_LENGTH = 30
        private const val MAX_ZIP_LENGTH = 10
        private const val MAX_COMPANY_LENGTH = 100
        private const val MAX_USERNAME_LENGTH = 30
        private const val MIN_INPUT_LENGTH = 2
    }

    // ============================================
    // Data Structures

    data class SuggestionNode(
        val value: String,
        var clickCount: Int = 0,
        var useCount: Int = 1,
        var lastUsed: Long = System.currentTimeMillis()
    ) {
        fun getRankingScore(): Float {
            val confirmationBonus = clickCount * 0.4f
            val frequencyScore = useCount * 0.35f
            val recencyScore = calculateRecency() * 0.25f
            return (confirmationBonus + frequencyScore + recencyScore).coerceAtLeast(0.1f)
        }

        fun calculateRecency(): Float {
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

        // for debugging
        fun getDetailedScore(): String {
            val conf = clickCount * 0.4f
            val freq = useCount * 0.35f
            val rec = calculateRecency() * 0.25f
            return "conf=$conf, freq=$freq, rec=$rec, total=${getRankingScore()}"
        }
    }

    data class RankedSuggestion(
        val value: String,
        val score: Float,
        val lastUsed: Long = 0L
    )

    enum class FieldType {
        FIRST_NAME, LAST_NAME, FULL_NAME,
        EMAIL, PHONE,
        ADDRESS, CITY, STATE, ZIP,
        COMPANY, USERNAME,
        UNKNOWN
    }

    // ============================================
    // Storage

    fun learnFromInput(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("SuggestionDebug", "=== LEARN FROM INPUT ===")
        Log.d("SuggestionDebug", "Field: $fieldType, Value: '$cleanValue'")

        val currentCount = getFieldSuggestions(fieldType).size
        Log.d("EdgeDebug", "Field $fieldType: current_entries=$currentCount, max_allowed=$MAX_STORED_PER_FIELD")

        if (!isValidInputLength(fieldType, cleanValue)) {
            Log.d("SuggestionDebug", "Input validation failed for $fieldType: '$cleanValue'")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            val oldScore = existing.getRankingScore()
            val wasRecent = (System.currentTimeMillis() - existing.lastUsed) < (24 * 60 * 60 * 1000) // 1 day

            existing.useCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)

            val newScore = existing.getRankingScore()
            Log.d("SuggestionDebug", "Updated: useCount=${existing.useCount}")
            Log.d("SuggestionDebug", "Recency: ${if (wasRecent) "was recent" else "got recency boost"}")
            Log.d("SuggestionDebug", "Score: $oldScore → $newScore")
        } else {
            val newNode = SuggestionNode(cleanValue, clickCount = 0, useCount = 1)
            storeMetadata(key, newNode)
            addToFieldList(fieldType, cleanValue)
            Log.d("SuggestionDebug", "New entry: useCount=1, fresh recency=1.0")
        }

        clearCacheForFieldType(fieldType)
        logCacheState("after_learn_${fieldType}")
        val finalCount = getFieldSuggestions(fieldType).size
        Log.d("EdgeDebug", "Field $fieldType: final_entries=$finalCount after learning '$cleanValue'")
    }

    fun saveImmediately(fieldType: FieldType, value: String, isFromSuggestionClick: Boolean = false) {
        val cleanValue = value.trim()
        Log.d("FormAutofillIntegration", "=== IMMEDIATE SAVE ===")
        Log.d("FormAutofillIntegration", "Field: $fieldType, Value: '$cleanValue', FromClick: $isFromSuggestionClick")

        if (!isValidInputLength(fieldType, cleanValue)) {
            Log.d("FormAutofillIntegration", "Validation failed - skipping save")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            val oldScore = existing.getRankingScore()
            existing.useCount++
            if (isFromSuggestionClick) {
                existing.clickCount++
            }
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)

            val newScore = existing.getRankingScore()
            Log.d("FormAutofillIntegration", "UPDATED: useCount=${existing.useCount}, clickCount=${existing.clickCount}")
            Log.d("FormAutofillIntegration", "Score: $oldScore → $newScore")
        } else {
            val newNode = SuggestionNode(
                cleanValue, 
                clickCount = if (isFromSuggestionClick) 1 else 0, 
                useCount = 1
            )
            storeMetadata(key, newNode)
            addToFieldList(fieldType, cleanValue)
            Log.d("FormAutofillIntegration", "NEW ENTRY: useCount=1, clickCount=${newNode.clickCount}")
        }

        clearCacheForFieldType(fieldType)
        Log.d("FormAutofillIntegration", "IMMEDIATE SAVE COMPLETE")
    }

    fun confirmSuggestion(fieldType: FieldType, value: String) {
        val cleanValue = value.trim()
        Log.d("SuggestionDebug", "=== CONFIRM SUGGESTION ===")
        Log.d("SuggestionDebug", "Field: $fieldType, Value: '$cleanValue'")

        if (!isValidInputLength(fieldType, cleanValue)) {
            Log.d("SuggestionDebug", "Suggestion validation failed for $fieldType: '$cleanValue'")
            return
        }

        val key = generateStorageKey(fieldType, cleanValue)
        val existing = getMetadata(key)

        if (existing != null) {
            val oldScore = existing.getRankingScore()
            existing.clickCount++
            existing.useCount++
            existing.lastUsed = System.currentTimeMillis()
            storeMetadata(key, existing)

            val newScore = existing.getRankingScore()
            Log.d("SuggestionDebug", "UNIFIED SAVE: clickCount=${existing.clickCount}, useCount=${existing.useCount}")
            Log.d("SuggestionDebug", "Score: $oldScore → $newScore (click + use + recency)")
        } else {
            val newNode = SuggestionNode(cleanValue, clickCount = 1, useCount = 1)
            storeMetadata(key, newNode)
            addToFieldList(fieldType, cleanValue)
            Log.d("SuggestionDebug", "NEW UNIFIED ENTRY: clickCount=1, useCount=1")
        }

        clearCacheForFieldType(fieldType)
        logCacheState("after_confirm_${fieldType}")
    }

    // ============================================
    //  Retrieval with Ranking
    fun getSuggestions(fieldType: FieldType, partialInput: String = ""): List<String> {
        val cacheKey = "${fieldType}_${partialInput}"

        val cached = hotCache[cacheKey]
        if (cached != null) {
            updateCacheAccess(cacheKey)
            Log.d("CacheDebug", "CACHE HIT: key='$cacheKey'")
            return cached.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        }
        Log.d("CacheDebug", "CACHE MISS: key='$cacheKey', reason= not_found")

        val trie = getOrCreateTrie(fieldType)

        val matchingKeys = if (partialInput.isBlank()) {
            trie.getAllStorageKeys()
        } else {
            trie.findMatches(partialInput)
        }

        // Get metadata and rank
        val rankedSuggestions = matchingKeys.mapNotNull { key ->
            val metadata = getMetadata(key)
            if (metadata != null) {
                val score = metadata.getRankingScore()
                Log.d("SuggestionDebug", "Suggestion '${metadata.value}': score=$score, useCount = ${metadata.useCount}, clickCount = ${metadata.clickCount}, lastUsed=${metadata.lastUsed}")
                RankedSuggestion(metadata.value, score, metadata.lastUsed) // Add timestamp
            } else null
        }.sortedWith(compareByDescending<RankedSuggestion> { it.score }
            .thenByDescending { it.lastUsed } // Newer entries first when scores tied
            .thenBy { it.value }) // Alphabetical as final tiebreaker

        // Cache result
        cacheResult(cacheKey, rankedSuggestions)

        val displayList = rankedSuggestions.map { it.value }.take(MAX_DISPLAYED_SUGGESTIONS)
        Log.d("SuggestionDebug", "Final ranking order: ${displayList.joinToString()}")

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
                val inputClass = inputType and InputType.TYPE_MASK_CLASS
                when {
                    inputVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> FieldType.EMAIL
                    inputVariation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldType.FULL_NAME
                    inputVariation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> FieldType.ADDRESS
                    inputClass == InputType.TYPE_CLASS_PHONE -> FieldType.PHONE
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
                    SuggestionNode(
                        value = parts[0],
                        clickCount = 0,
                        useCount = parts[1].toInt(),
                        lastUsed = parts[2].toLong()
                    )
                }
                4 -> {
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

    // Add this new method to FormDataManager class:
    private fun logDetailedScoring(fieldType: FieldType, entries: Set<String>, context: String) {
        Log.d("EdgeDebug", "=== DETAILED SCORING: $context ===")
        val scoredEntries = entries.map { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)
            val score = metadata?.getRankingScore() ?: 0f
            Triple(suggestion, score, metadata?.getDetailedScore() ?: "no_metadata")
        }.sortedByDescending { it.second }

        scoredEntries.forEachIndexed { index, (suggestion, score, details) ->
            Log.d("EdgeDebug", "[$index] '$suggestion': score=$score ($details)")
        }
        Log.d("EdgeDebug", "Lowest scoring: '${scoredEntries.lastOrNull()?.first}' (${scoredEntries.lastOrNull()?.second})")
    }

    private fun addToFieldList(fieldType: FieldType, value: String) {
        val listKey = fieldType.name
        val existing = prefs.getStringSet(listKey, mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        // Remove case-insensitive duplicates
        existing.removeAll { it.lowercase() == value.lowercase() }
        existing.add(value)

        // Only apply decay and eviction if we're at capacity
        if (existing.size > MAX_STORED_PER_FIELD) {

            applyAgeDecay(fieldType)
            Log.d("EdgeDebug", "Age decay applied to all entries")

            // Get scoring BEFORE adding new entry
            logDetailedScoring(fieldType, existing.filter { it != value }.toSet(), "before_eviction")

            val lowestScoringEntry = findLowestScoringEntry(fieldType, existing.filter { it != value }.toSet())
            if (lowestScoringEntry != null) {
                val evictedKey = generateStorageKey(fieldType, lowestScoringEntry)
                val evictedMetadata = getMetadata(evictedKey)
                Log.d("EdgeDebug", "EVICTED: '$lowestScoringEntry' (score=${evictedMetadata?.getRankingScore()}, lastUsed=${evictedMetadata?.lastUsed})")

                existing.remove(lowestScoringEntry)
                removeMetadata(fieldType, lowestScoringEntry)
                Log.d("EdgeDebug", "Final count: ${existing.size} entries")
                Log.d("EdgeDebug", "=== EVICTION PROCESS COMPLETE ===")
            }
        }

        prefs.edit().putStringSet(listKey, existing).apply()

        // Add to trie
        val trie = getOrCreateTrie(fieldType)
        val storageKey = generateStorageKey(fieldType, value)
        trie.insert(value, storageKey)

        // Replace the log at end of method:
        Log.d("EdgeDebug", "=== FIELD STORAGE UPDATE ===")
        Log.d("EdgeDebug", "Field: $fieldType, added: '$value'")
        Log.d("EdgeDebug", "Before: ${existing.size - 1} entries, After: ${existing.size} entries")
        Log.d("EdgeDebug", "Capacity check: ${existing.size}/$MAX_STORED_PER_FIELD")
        if (existing.size > MAX_STORED_PER_FIELD) {
            Log.d("EdgeDebug", "OVER CAPACITY - eviction will be triggered")
        }
    }

    private fun removeMetadata(fieldType: FieldType, value: String) {
        val key = generateStorageKey(fieldType, value)
        metadataPrefs.edit().remove(key).apply()

        // NEW: Remove from trie
        val trie = getOrCreateTrie(fieldType)
        trie.remove(value, key)
    }

    private fun applyAgeDecay(fieldType: FieldType) {
        val allSuggestions = getFieldSuggestions(fieldType)
        val currentTime = System.currentTimeMillis()
        Log.d("EdgeDebug", "=== AGE DECAY ANALYSIS ===")
        Log.d("EdgeDebug", "Field: $fieldType, checking ${allSuggestions.size} entries")

        allSuggestions.forEach { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)

            if (metadata != null) {
                val daysSinceLastUsed = (currentTime - metadata.lastUsed) / (1000 * 60 * 60 * 24)

                // Apply decay if entry is old and hasn't been decayed recently
                if (daysSinceLastUsed >= DECAY_INTERVAL_DAYS) {
                    val oldUseCount = metadata.useCount
                    val oldClickCount = metadata.clickCount
                    metadata.useCount = (metadata.useCount * DECAY_FACTOR).toInt().coerceAtLeast(1)
                    metadata.clickCount = (metadata.clickCount * DECAY_FACTOR).toInt()
                    storeMetadata(key, metadata)
                    Log.d("EdgeDebug", "AGED: '$suggestion' - uses: $oldUseCount→${metadata.useCount}, clicks: $oldClickCount→${metadata.clickCount}")
                } else {
                    Log.d("EdgeDebug", "FRESH: '$suggestion' (${daysSinceLastUsed.toInt()} days old)")
                }
            }
        }
    }

    private fun findLowestScoringEntry(fieldType: FieldType, entries: Set<String>): String? {
        return entries.minWithOrNull(compareBy<String> { suggestion ->
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)
            metadata?.getRankingScore() ?: 0f
        }.thenBy { suggestion ->
            // Evict oldest entry for tie break
            val key = generateStorageKey(fieldType, suggestion)
            val metadata = getMetadata(key)
            metadata?.lastUsed ?: 0L
        }.thenBy { it }) // Alphabetical  final fallback
    }

    private fun getFieldSuggestions(fieldType: FieldType): List<String> {
        val listKey = fieldType.name
        return prefs.getStringSet(listKey, emptySet())?.toList() ?: emptyList()
    }

    // ============================================
    // Cache Management

    private fun cacheResult(key: String, result: List<RankedSuggestion>) {
        Log.d("CacheDebug", "=== CACHE STORE ===")
        Log.d("CacheDebug", "Storing key='$key', results=${result.size}, current_cache_size=${hotCache.size}/$maxCacheSize")

        if (hotCache.size >= maxCacheSize) {
            val oldestKey = cacheAccessOrder.removeFirstOrNull()
            oldestKey?.let {
                hotCache.remove(it)
                Log.d("CacheDebug", "EVICTED oldest: '$it' (cache was full)")
            }
        }

        hotCache[key] = result
        cacheAccessOrder.add(key)

        Log.d("CacheDebug", "STORED: cache_size=${hotCache.size}, access_order_size=${cacheAccessOrder.size}")
        Log.d("CacheDebug", "Recent access order: ${cacheAccessOrder.takeLast(3)}")
    }

    private fun updateCacheAccess(key: String) {
        val wasPresent = cacheAccessOrder.remove(key)
        cacheAccessOrder.add(key)

        Log.d("CacheDebug", "ACCESS UPDATE: key='$key', was_present=$wasPresent")
        Log.d("CacheDebug", "New access order (last 3): ${cacheAccessOrder.takeLast(3)}")
    }

    private fun clearCacheForFieldType(fieldType: FieldType) {
        Log.d("CacheDebug", "=== CACHE CLEAR ===")
        Log.d("CacheDebug", "Clearing cache for fieldType: $fieldType")
        Log.d("CacheDebug", "Before clear: cache_size=${hotCache.size}, access_order_size=${cacheAccessOrder.size}")

        val keysToRemove = hotCache.keys.filter { it.startsWith("${fieldType}_") }
        Log.d("CacheDebug", "Keys to remove: $keysToRemove")

        keysToRemove.forEach { key ->
            hotCache.remove(key)
            cacheAccessOrder.remove(key)
        }

        Log.d("CacheDebug", "After clear: cache_size=${hotCache.size}, access_order_size=${cacheAccessOrder.size}")
        Log.d("CacheDebug", "Remaining keys: ${hotCache.keys}")

        // Only compress trie if significant changes occurred
        if (keysToRemove.isNotEmpty()) {
            val trie = getOrCreateTrie(fieldType)
            trie.compress()
            Log.d("CacheDebug", "Cleared ${keysToRemove.size} cache entries for $fieldType + compressed trie")
        }
    }

    // debugging cache state
    private fun logCacheState(context: String) {
        Log.d("CacheDebug", "=== CACHE STATE: $context ===")
        Log.d("CacheDebug", "Cache size: ${hotCache.size}/$maxCacheSize")
        Log.d("CacheDebug", "Access order size: ${cacheAccessOrder.size}")
        Log.d("CacheDebug", "Cache keys: ${hotCache.keys}")
        Log.d("CacheDebug", "Access order: $cacheAccessOrder")
    }

    // ============================================
    // Trie utils

    // ============================================
    // Data validations
    private fun isValidInputLength(fieldType: FieldType, value: String): Boolean {
        if (value.isBlank() || value.length < MIN_INPUT_LENGTH) {
            return false
        }

        // Basic content quality validation
        if (!isValidContent(fieldType, value)) {
            return false
        }

        val maxLength = when (fieldType) {
            FieldType.FIRST_NAME, FieldType.LAST_NAME -> MAX_NAME_LENGTH
            FieldType.FULL_NAME -> MAX_NAME_LENGTH * 2
            FieldType.EMAIL -> MAX_EMAIL_LENGTH
            FieldType.PHONE -> MAX_PHONE_LENGTH
            FieldType.ADDRESS -> MAX_ADDRESS_LENGTH
            FieldType.CITY -> MAX_CITY_LENGTH
            FieldType.STATE -> MAX_STATE_LENGTH
            FieldType.ZIP -> MAX_ZIP_LENGTH
            FieldType.COMPANY -> MAX_COMPANY_LENGTH
            FieldType.USERNAME -> MAX_USERNAME_LENGTH
            FieldType.UNKNOWN -> return false
        }

        if (value.length > maxLength) {
            Log.w("FormDataValidation", "Input too long for $fieldType: ${value.length} > $maxLength chars")
            return false
        }

        return true
    }

    private fun isValidContent(fieldType: FieldType, value: String): Boolean {
        // Skip validation for very short inputs that are likely partial typing
        if (value.length < 3) return true

        // Check for obvious corrupted data patterns
        if (hasRepeatingCharacters(value) || hasRandomCharacterPattern(value)) {
            Log.w("FormDataValidation", "Suspicious character pattern in $fieldType: '$value'")
            return false
        }

        return when (fieldType) {
            FieldType.EMAIL -> isValidEmailPattern(value)
            FieldType.PHONE -> isValidPhonePattern(value)
            FieldType.ZIP -> isValidZipPattern(value)
            FieldType.FIRST_NAME, FieldType.LAST_NAME, FieldType.FULL_NAME -> isValidNameContent(value)
            FieldType.CITY, FieldType.STATE -> isValidLocationContent(value)
            else -> true // Other fields use generic validation only
        }
    }

    private fun hasRepeatingCharacters(value: String): Boolean {
        // Check for excessive repeating characters (like "aaaaaaa" or "1111111")
        val threshold = if (value.length < 10) 4 else 6
        var currentChar = value[0]
        var count = 1
        
        for (i in 1 until value.length) {
            if (value[i] == currentChar) {
                count++
                if (count >= threshold) return true
            } else {
                currentChar = value[i]
                count = 1
            }
        }
        return false
    }

    private fun hasRandomCharacterPattern(value: String): Boolean {
        // Check for keyboard mashing patterns or random character sequences
        if (value.length < 8) return false
        
        // Count different character types
        var letters = 0
        var digits = 0
        var symbols = 0
        
        value.forEach { char ->
            when {
                char.isLetter() -> letters++
                char.isDigit() -> digits++
                else -> symbols++
            }
        }
        
        val totalChars = value.length
        // If more than 30% are symbols, likely random input
        return symbols > (totalChars * 0.3)
    }

    private fun isValidEmailPattern(value: String): Boolean {
        // Basic email structure check (not comprehensive validation)
        return value.contains("@") && 
               value.indexOf("@") > 0 && 
               value.indexOf("@") < value.length - 1 &&
               value.count { it == '@' } == 1 &&
               !value.startsWith("@") &&
               !value.endsWith("@")
    }

    private fun isValidPhonePattern(value: String): Boolean {
        // Remove common phone formatting characters
        val cleaned = value.replace(Regex("[\\s\\-\\(\\)\\+\\.]"), "")
        
        // Should be mostly digits with reasonable length
        val digitCount = cleaned.count { it.isDigit() }
        return digitCount >= 7 && digitCount <= 15 && // International phone number range
               digitCount > (cleaned.length * 0.7) // At least 70% digits
    }

    private fun isValidZipPattern(value: String): Boolean {
        // ZIP codes should be mostly numeric or alphanumeric
        val alphanumericCount = value.count { it.isLetterOrDigit() }
        return alphanumericCount > (value.length * 0.8) // At least 80% alphanumeric
    }

    private fun isValidNameContent(value: String): Boolean {
        // Names should be mostly letters with some spaces, hyphens, apostrophes
        val validChars = value.count { it.isLetter() || it == ' ' || it == '-' || it == '\'' || it == '.' }
        val letterCount = value.count { it.isLetter() }
        
        return letterCount >= 2 && // At least 2 letters
               validChars > (value.length * 0.8) && // 80% valid name characters
               !value.all { it.isDigit() } // Not all numbers
    }

    private fun isValidLocationContent(value: String): Boolean {
        // Cities/states should be mostly letters with spaces, periods, hyphens
        val validChars = value.count { it.isLetter() || it == ' ' || it == '.' || it == '-' }
        val letterCount = value.count { it.isLetter() }
        
        return letterCount >= 2 && // At least 2 letters
               validChars > (value.length * 0.7) && // 70% valid location characters
               !value.all { it.isDigit() } // Not all numbers
    }
}