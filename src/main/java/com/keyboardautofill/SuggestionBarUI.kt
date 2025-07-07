package com.keyboardautofill

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


/**
 * Enhanced suggestion display with proper callback handling
 */
class SuggestionBarUI(
    private val inputMethodService: InputMethodService,
    private val suggestionBarView: RecyclerView?,
    private val onSuggestionSelected: ((String) -> Unit)? = null
) {

    private var suggestionBar: RecyclerView? = null
    private var suggestionAdapter: SuggestionAdapter? = null

    init {
        if (suggestionBarView != null) {
            setupSuggestionBar()
        } else {
            Log.w("SuggestionDebug", "SuggestionBarUI initialized with null RecyclerView - will be non-functional")
        }
    }

    // ============================================
    // Suggestion Bar Setup

    private fun setupSuggestionBar() {
        suggestionAdapter = SuggestionAdapter { suggestion ->
            onSuggestionClicked(suggestion)
        }

        suggestionBarView?.apply {
            // FORCE FRESH STATE: Ensure no preserved state from previous sessions
            adapter = null
            adapter = suggestionAdapter
            layoutManager = LinearLayoutManager(
                inputMethodService,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            
            // PREVENT STATE RESTORATION: Disable any automatic state preservation
            layoutManager?.isItemPrefetchEnabled = false
            
            // Establish touch handling once during setup
            establishTouchHandling()
            
            visibility = View.GONE
        }
        
        Log.d("SuggestionDebug", "Suggestion bar setup completed - adapter initialized with 0 suggestions")
    }

    // ============================================
    // Touch Handling

    private fun establishTouchHandling() {
        suggestionBarView?.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            Log.d("SuggestionDebug", "Touch handling established for RecyclerView")
        }
    }

    // ============================================
    // Public Interface

    fun updateSuggestions(suggestions: List<String>) {
        suggestionAdapter?.updateSuggestions(suggestions)
        
        // BEAUTIFUL UI: Hide suggestion bar when no suggestions available
        if (suggestions.isEmpty()) {
            hideSuggestionBar()
            Log.d("SuggestionDebug", "Auto-hiding suggestion bar - no suggestions available")
        }
    }

    fun clearSuggestions() {
        Log.d("SuggestionDebug", "=== FORCE CLEARING SUGGESTIONS ===")
        
        // MULTI-LAYER CLEAR: Ensure complete state reset
        suggestionAdapter?.updateSuggestions(emptyList())
        suggestionBarView?.adapter?.notifyDataSetChanged()
        
        // FORCE RECYCLER REFRESH: Clear any cached views
        suggestionBarView?.recycledViewPool?.clear()
        
        Log.d("SuggestionDebug", "Force cleared all suggestions from adapter with full refresh")
        hideSuggestionBar()
    }

    fun showSuggestionBar() {
        suggestionBarView?.visibility = View.VISIBLE
        Log.d("SuggestionDebug", "Suggestion bar shown - touch state: clickable=${suggestionBarView?.isClickable}, focusable=${suggestionBarView?.isFocusable}")
    }

    fun hideSuggestionBar() {
        suggestionBarView?.visibility = View.GONE
    }

    // ============================================
    // User Interaction

    private fun onSuggestionClicked(suggestion: String) {
        Log.d("SuggestionDebug", "=== SUGGESTION CLICKED ===")
        Log.d("SuggestionDebug", "Selected: '$suggestion'")

        val inputConnection: InputConnection? = inputMethodService.currentInputConnection
        inputConnection?.let { ic ->
            ic.beginBatchEdit()

            try {
                // Clear entire field content and replace with suggestion
                val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
                val textAfter = ic.getTextAfterCursor(1000, 0) ?: ""

                if (textBefore.isNotEmpty() || textAfter.isNotEmpty()) {
                    ic.deleteSurroundingText(textBefore.length, textAfter.length)
                }

                ic.commitText(suggestion, 1)
                Log.d("SuggestionDebug", "Replaced field content with: '$suggestion'")

            } catch (e: Exception) {
                Log.e("SuggestionDebug", "Error applying suggestion", e)
            } finally {
                ic.endBatchEdit()
            }

        }

        // Report selection for ranking
        onSuggestionSelected?.invoke(suggestion)
        Log.d("SuggestionDebug", "=== SUGGESTION CLICK COMPLETE ===")
    }

    // ============================================
    // Adapter

    private inner class SuggestionAdapter(
        private val onSuggestionClick: (String) -> Unit
    ) : RecyclerView.Adapter<SuggestionViewHolder>() {

        private val suggestions = mutableListOf<String>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return SuggestionViewHolder(textView)
        }

        override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
            val suggestion = suggestions[position]
            Log.d("SuggestionDebug", "ADAPTER BIND: position=$position, suggestion='$suggestion', totalSuggestions=${suggestions.size}")
            holder.bind(suggestion, onSuggestionClick)
        }

        override fun getItemCount(): Int = suggestions.size

        fun updateSuggestions(newSuggestions: List<String>) {
            Log.d("SuggestionDebug", "=== ADAPTER UPDATE REQUEST ===")
            Log.d("SuggestionDebug", "Current: ${suggestions.toList()}")
            Log.d("SuggestionDebug", "New: $newSuggestions")
            
            // Use content comparison instead of reference comparison
            val contentEquals = suggestions.size == newSuggestions.size && 
                               suggestions.zip(newSuggestions).all { it.first == it.second }
            Log.d("SuggestionDebug", "Content equal check: $contentEquals")
            
            // Avoid unnecessary updates if suggestions haven't changed
            if (contentEquals) {
                Log.d("SuggestionDebug", "Suggestions content unchanged - skipping adapter update")
                return
            }
            
            Log.d("SuggestionDebug", "Updating adapter: ${suggestions.size} → ${newSuggestions.size} suggestions")
            
            // Use precise update methods instead of disruptive notifyDataSetChanged()
            val oldSize = suggestions.size
            suggestions.clear()
            suggestions.addAll(newSuggestions)
            
            if (oldSize == 0) {
                // First time showing suggestions - insert all
                notifyItemRangeInserted(0, suggestions.size)
            } else if (suggestions.size == 0) {
                // All suggestions removed
                notifyItemRangeRemoved(0, oldSize)
            } else {
                // Content changed - use range change to preserve view hierarchy
                val minSize = minOf(oldSize, suggestions.size)
                val maxSize = maxOf(oldSize, suggestions.size)
                
                // Update existing items
                if (minSize > 0) {
                    notifyItemRangeChanged(0, minSize)
                }
                
                // Handle size differences
                if (suggestions.size > oldSize) {
                    // Added items
                    notifyItemRangeInserted(oldSize, suggestions.size - oldSize)
                } else if (suggestions.size < oldSize) {
                    // Removed items
                    notifyItemRangeRemoved(suggestions.size, oldSize - suggestions.size)
                }
            }
            
            Log.d("SuggestionDebug", "Adapter updated with ${suggestions.size} suggestions using precise notifications")
            
            // For critical updates force full refresh
            if ((oldSize == 0 && suggestions.size > 0) || (oldSize > 0 && suggestions.size == 0)) {
                Log.d("SuggestionDebug", " CRITICAL UPDATE DETECTED - notifyDataSetChanged()")
                notifyDataSetChanged()
            }
        }
    }

    private inner class SuggestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView as TextView

        fun bind(suggestion: String, onSuggestionClick: (String) -> Unit) {
            // Only update the text content - avoid resetting layout properties unnecessarily
            textView.text = suggestion
            Log.d("SuggestionDebug", "VIEWHOLDER BIND: position=${adapterPosition}, suggestion='$suggestion'")
            
            // Set click listener - the color selector handles visual feedback automatically
            itemView.setOnClickListener {
                Log.d("SuggestionDebug", "ViewHolder click detected for: '$suggestion'")
                onSuggestionClick(suggestion)
            }
        }
        
        init {
            // Set up view properties once during ViewHolder creation
            val resources = itemView.context.resources
            
            textView.apply {
                // Use Bobble's color scheme with state-aware text colors
                setTextColor(resources.getColorStateList(R.color.suggestion_text_selector, null))
                textSize = resources.getDimension(R.dimen.suggestion_text_size) / resources.displayMetrics.scaledDensity
                
                // Modern typography settings for beautiful text
                letterSpacing = 0.02f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                
                val hPadding = resources.getDimensionPixelSize(R.dimen.suggestion_padding_horizontal)
                val vPadding = resources.getDimensionPixelSize(R.dimen.suggestion_padding_vertical)
                setPadding(hPadding, vPadding, hPadding, vPadding)

                setBackgroundResource(R.drawable.suggestion_badge_selector)
                
                // Add elevation for modern Material Design look with subtle shadow
                elevation = 4f
                
                val margin = resources.getDimensionPixelSize(R.dimen.suggestion_margin)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
            }

            // Establish touch handling once during ViewHolder creation
            itemView.apply {
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = false
            }
        }
    }
}