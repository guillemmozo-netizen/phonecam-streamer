package com.phonecam.streamer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phonecam.streamer.R

/**
 * A Spinner replacement that expands the settings section in place instead of
 * covering the screen with a floating popup window. Tapping the row grows a
 * list of options directly below it (pushing the rest of the section down),
 * matching the same "expand inline" feel as the custom bitrate/fps rows.
 *
 * API mirrors just enough of Spinner (selectedItemPosition, setSelection,
 * a selection listener, adding items after construction) that SettingsActivity
 * only had to change widget types, not its persistence/gating logic.
 */
class ExpandableChoiceRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    fun interface OnChoiceSelectedListener {
        fun onChoiceSelected(row: ExpandableChoiceRow, position: Int)
    }

    var onChoiceSelectedListener: OnChoiceSelectedListener? = null

    private val labelView: TextView
    private val valueView: TextView
    private val caretView: ImageView
    private val optionsContainer: LinearLayout

    private val items = mutableListOf<String>()
    var selectedItemPosition: Int = 0
        private set
    val itemCount: Int get() = items.size

    private var expanded = false
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(50))
            setPadding(dp(16), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        labelView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 16f
        }

        valueView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextColor(ContextCompat.getColor(context, R.color.readout))
            textSize = 15f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }

        caretView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
            setImageResource(R.drawable.ic_caret_down)
        }

        header.addView(labelView)
        header.addView(valueView)
        header.addView(caretView)
        addView(header)

        optionsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_tertiary))
            clipToPadding = true
            visibility = View.GONE
        }
        addView(optionsContainer)

        header.setOnClickListener { toggleExpanded() }

        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.ExpandableChoiceRow)
            labelView.text = ta.getString(R.styleable.ExpandableChoiceRow_label)
            ta.recycle()
        }
    }

    /** Replaces the full option list. Selection resets to 0 unless setSelection is called after. */
    fun setItems(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        selectedItemPosition = selectedItemPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        rebuildOptionRows()
        updateValueText()
    }

    /** Matches ArrayAdapter.add() call sites that append a lens option once the device probe resolves. */
    fun addItem(item: String) {
        items.add(item)
        rebuildOptionRows()
    }

    @JvmOverloads
    fun setSelection(pos: Int, notify: Boolean = true) {
        if (pos !in items.indices) return
        selectedItemPosition = pos
        updateValueText()
        highlightSelected()
        if (notify) onChoiceSelectedListener?.onChoiceSelected(this, pos)
    }

    private fun updateValueText() {
        valueView.text = items.getOrNull(selectedItemPosition) ?: ""
    }

    private fun rebuildOptionRows() {
        optionsContainer.removeAllViews()
        items.forEachIndexed { idx, label ->
            val row = TextView(context).apply {
                text = label
                textSize = 15f
                setPadding(dp(28), dp(13), dp(16), dp(13))
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener {
                    setSelection(idx)
                    collapse()
                }
            }
            optionsContainer.addView(row)
            if (idx != items.lastIndex) {
                optionsContainer.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
                        setBackgroundColor(ContextCompat.getColor(context, R.color.separator))
                    },
                )
            }
        }
        highlightSelected()
    }

    private fun highlightSelected() {
        var childIndex = 0
        for (idx in items.indices) {
            val row = optionsContainer.getChildAt(childIndex) as? TextView
            row?.setTextColor(
                ContextCompat.getColor(context, if (idx == selectedItemPosition) R.color.accent else R.color.text_primary),
            )
            row?.setTypeface(null, if (idx == selectedItemPosition) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            childIndex += 2 // skip the divider view between rows
        }
    }

    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        expanded = true
        animateCaret(180f)
        optionsContainer.visibility = View.VISIBLE
        optionsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val targetHeight = optionsContainer.measuredHeight
        animateHeight(0, targetHeight)
    }

    private fun collapse() {
        expanded = false
        animateCaret(0f)
        val startHeight = optionsContainer.height
        animateHeight(startHeight, 0) { optionsContainer.visibility = View.GONE }
    }

    private fun animateHeight(from: Int, to: Int, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                optionsContainer.layoutParams = optionsContainer.layoutParams.apply {
                    height = anim.animatedValue as Int
                }
                optionsContainer.requestLayout()
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
            }
            start()
        }
    }

    private fun animateCaret(toRotation: Float) {
        caretView.animate().rotation(toRotation).setDuration(220).start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
