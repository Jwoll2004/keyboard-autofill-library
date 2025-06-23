package com.keyboardautofill

import android.util.Log

/**
 * Memory-efficient trie for fast prefix matching
 * Designed for mobile constraints with compression
 */
class SuggestionTrie {

    private val root = TrieNode()

    data class TrieNode(
        val children: MutableMap<Char, TrieNode> = mutableMapOf(),
        val storageKeys: MutableSet<String> = mutableSetOf(),
        var isCompressed: Boolean = false
    )

    /**
     * Insert a value with its storage key for later metadata lookup
     */
    fun insert(value: String, storageKey: String) {
        val cleanValue = value.lowercase().trim()
        if (cleanValue.isBlank()) return

        var current = root

        for (char in cleanValue) {
            current = current.children.getOrPut(char) { TrieNode() }
        }

        current.storageKeys.add(storageKey)
        Log.d("SuggestionDebug", "Trie: Inserted '$value' with key '$storageKey'")
    }

    /**
     * Find all storage keys that match the given prefix
     */
    fun findMatches(prefix: String): List<String> {
        if (prefix.isBlank()) return getAllStorageKeys()

        val cleanPrefix = prefix.lowercase().trim()
        var current = root

        // Navigate to prefix end
        for (char in cleanPrefix) {
            current = current.children[char] ?: return emptyList()
        }

        // Collect all storage keys from this point down
        val matches = mutableListOf<String>()
        collectAllKeys(current, matches)

        Log.d("SuggestionDebug", "Trie: Found ${matches.size} matches for prefix '$prefix'")
        return matches
    }

    /**
     * Get all suggestions (for empty prefix)
     */
    fun getAllStorageKeys(): List<String> {
        val allKeys = mutableListOf<String>()
        collectAllKeys(root, allKeys)
        return allKeys
    }

    /**
     * Remove a value from the trie
     */
    fun remove(value: String, storageKey: String) {
        val cleanValue = value.lowercase().trim()
        if (cleanValue.isBlank()) return

        removeRecursive(root, cleanValue, 0, storageKey)
    }

    /**
     * Compress empty branches to save memory
     */
    fun compress() {
        compressNode(root)
        Log.d("SuggestionDebug", "Trie: Compression completed")
    }

    // ============================================
    // Internal Helper Methods

    private fun collectAllKeys(node: TrieNode, collector: MutableList<String>) {
        // Add all keys from current node
        collector.addAll(node.storageKeys)

        // Recursively collect from children
        for (child in node.children.values) {
            collectAllKeys(child, collector)
        }
    }

    private fun removeRecursive(node: TrieNode, value: String, index: Int, storageKey: String): Boolean {
        if (index == value.length) {
            // Reached end - remove the storage key
            node.storageKeys.remove(storageKey)
            // Return true if this node has no more data and can be removed
            return node.storageKeys.isEmpty() && node.children.isEmpty()
        }

        val char = value[index]
        val child = node.children[char] ?: return false

        val shouldRemoveChild = removeRecursive(child, value, index + 1, storageKey)

        if (shouldRemoveChild) {
            node.children.remove(char)
        }

        // Return true if this node can be removed (no data, no children)
        return node.storageKeys.isEmpty() && node.children.isEmpty()
    }

    private fun compressNode(node: TrieNode): Boolean {
        if (node.isCompressed) return false

        // Recursively compress children first
        val childrenToRemove = mutableListOf<Char>()
        for ((char, child) in node.children) {
            if (compressNode(child)) {
                childrenToRemove.add(char)
            }
        }

        // Remove empty children
        childrenToRemove.forEach { node.children.remove(it) }

        // Mark as compressed
        node.isCompressed = true

        // Return true if this node is now empty and can be removed
        return node.storageKeys.isEmpty() && node.children.isEmpty()
    }
}