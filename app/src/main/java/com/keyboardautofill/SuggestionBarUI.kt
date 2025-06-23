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
    private val suggestionBarView: RecyclerView,
    private val onSuggestionSelected: ((String) -> Unit)? = null
) {

    private var suggestionBar: RecyclerView? = null
    private var suggestionAdapter: SuggestionAdapter? = null

    init {
        setupSuggestionBar()
    }

    // ============================================
    // Suggestion Bar Setup

    private fun setupSuggestionBar() {
        suggestionAdapter = SuggestionAdapter { suggestion ->
            onSuggestionClicked(suggestion)
        }

        suggestionBarView.adapter = suggestionAdapter
        suggestionBarView.layoutManager = LinearLayoutManager(
            inputMethodService,
            LinearLayoutManager.HORIZONTAL,
            false
        )

        suggestionBarView.visibility = View.GONE
        Log.d("SuggestionDebug", "Suggestion bar setup completed")
    }

    // ============================================
    // Public Interface

    fun updateSuggestions(suggestions: List<String>) {
        suggestionAdapter?.updateSuggestions(suggestions)
    }

    fun showSuggestionBar() {
        suggestionBarView.visibility = View.VISIBLE
    }

    fun hideSuggestionBar() {
        suggestionBarView.visibility = View.GONE
    }

    // ============================================
    // User Interaction

    private fun onSuggestionClicked(suggestion: String) {
        Log.d("SuggestionDebug", "=== SUGGESTION UI CLICK ===")
        Log.d("SuggestionDebug", "User clicked: '$suggestion'")

        val inputConnection: InputConnection? = inputMethodService.currentInputConnection
        inputConnection?.let { ic ->
            ic.beginBatchEdit()

            val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
            val textAfter = ic.getTextAfterCursor(1000, 0) ?: ""

            Log.d("SuggestionDebug", "Replacing field content with suggestion")
            ic.deleteSurroundingText(textBefore.length, textAfter.length)
            ic.commitText(suggestion, 1)
            ic.endBatchEdit()

            hideSuggestionBar()

            Log.d("SuggestionDebug", "Applied suggestion to field: '$suggestion'")
        }

        // CRITICAL: Report the selection for ranking
        Log.d("SuggestionDebug", "Calling onSuggestionSelected callback")
        onSuggestionSelected?.invoke(suggestion)
        Log.d("SuggestionDebug", "=== SUGGESTION CLICK COMPLETE ===")
    }

    // ============================================
    // Adapter Implementation (remains the same)

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
            holder.bind(suggestion, onSuggestionClick)
        }

        override fun getItemCount(): Int = suggestions.size

        fun updateSuggestions(newSuggestions: List<String>) {
            suggestions.clear()
            suggestions.addAll(newSuggestions)
            notifyDataSetChanged()
        }
    }

    private inner class SuggestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView as TextView

        fun bind(suggestion: String, onSuggestionClick: (String) -> Unit) {
            val resources = itemView.context.resources

            textView.apply {
                text = suggestion
                setTextColor(0xFFFFFFFF.toInt())
                textSize = resources.getDimension(R.dimen.suggestion_text_size) / resources.displayMetrics.scaledDensity

                val hPadding = resources.getDimensionPixelSize(R.dimen.suggestion_padding_horizontal)
                val vPadding = resources.getDimensionPixelSize(R.dimen.suggestion_padding_vertical)
                setPadding(hPadding, vPadding, hPadding, vPadding)

                setBackgroundResource(R.drawable.suggestion_badge_selector)

                val margin = resources.getDimensionPixelSize(R.dimen.suggestion_margin)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
            }

            itemView.setOnClickListener {
                onSuggestionClick(suggestion)
            }
        }
    }
}