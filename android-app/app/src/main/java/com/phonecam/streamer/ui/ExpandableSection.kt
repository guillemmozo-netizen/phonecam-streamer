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
 * A "see more" header that grows arbitrary content open beneath it.
 *
 * The animation is [ExpandableChoiceRow]'s, deliberately: that row already
 * established how disclosure feels in this app (220ms height, matching caret
 * rotation, content pushing the rest of the page down rather than floating
 * over it), and a second, subtly different expansion elsewhere in the same
 * app reads as a bug. What differs is only what it holds — that one owns a
 * fixed list of choices and the selection, this one takes any child view, so
 * the diagnostics screen can put a whole rendered JSON section inside it.
 *
 * Children added in XML or via [addContent] land in the collapsible area, not
 * beside the header.
 */
class ExpandableSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val headerView: LinearLayout
    private val titleView: TextView
    private val caretView: ImageView
    private val contentContainer: LinearLayout

    private var animator: ValueAnimator? = null
    var isExpanded: Boolean = false
        private set

    /** Fired after a toggle, with the new state — used to lazily fill content. */
    var onToggle: ((expanded: Boolean) -> Unit)? = null

    var title: CharSequence
        get() = titleView.text
        set(value) { titleView.text = value }

    init {
        orientation = VERTICAL

        headerView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
            setPadding(dp(2), 0, dp(2), 0)
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { toggle() }
        }

        titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        caretView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(R.drawable.ic_caret_down)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.accent),
            )
        }

        headerView.addView(titleView)
        headerView.addView(caretView)
        addView(headerView)

        contentContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
        }
        addView(contentContainer)
    }

    /**
     * XML children after the first two (header, container) are moved into the
     * collapsible area — otherwise anything declared in a layout file would
     * sit permanently visible below the header, which is the opposite of the
     * point.
     */
    override fun onFinishInflate() {
        super.onFinishInflate()
        while (childCount > 2) {
            val child = getChildAt(2)
            removeView(child)
            contentContainer.addView(child)
        }
    }

    fun addContent(view: View) = contentContainer.addView(view)

    fun clearContent() = contentContainer.removeAllViews()

    fun toggle() {
        if (isExpanded) collapse() else expand()
        onToggle?.invoke(isExpanded)
    }

    fun expand() {
        isExpanded = true
        caretView.animate().rotation(180f).setDuration(DURATION).start()
        contentContainer.visibility = View.VISIBLE
        contentContainer.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        animateHeight(contentContainer.height, contentContainer.measuredHeight) {
            // Back to WRAP_CONTENT once open: a fixed height measured at
            // expand time goes stale the moment the content reflows (a long
            // value wrapping to two lines, a rotation), and the tail gets
            // clipped with no way to scroll to it.
            contentContainer.layoutParams = contentContainer.layoutParams.apply {
                height = LayoutParams.WRAP_CONTENT
            }
            contentContainer.requestLayout()
        }
    }

    fun collapse() {
        isExpanded = false
        caretView.animate().rotation(0f).setDuration(DURATION).start()
        animateHeight(contentContainer.height, 0) {
            contentContainer.visibility = View.GONE
        }
    }

    private fun animateHeight(from: Int, to: Int, onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofInt(from, to).apply {
            duration = DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                contentContainer.layoutParams = contentContainer.layoutParams.apply {
                    height = anim.animatedValue as Int
                }
                contentContainer.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val DURATION = 220L
    }
}
