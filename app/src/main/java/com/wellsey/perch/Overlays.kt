package com.wellsey.perch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MenuEntry(
    val label: String,
    val glyph: String?,
    val icon: Drawable? = null,
    val danger: Boolean = false,
    val action: () -> Unit,
)

/**
 * The long-press menu: the home screen blurs, the icon lifts, and glass cards spring out with
 * the app's shortcuts and actions. With no [source] it's the long-press-on-empty-space menu.
 */
@SuppressLint("ViewConstructor")
class MenuOverlay(
    private val act: LauncherActivity,
    private val source: AppIconView?,
    private val anchorX: Float,
    private val anchorY: Float,
    groups: List<List<MenuEntry>>,
) : FrameLayout(act) {
    private val scrim = View(act)
    private val column = LinearLayout(act)
    private val cards = ArrayList<LinearLayout>()
    private var lifted: FloatIcon? = null
    private var liftedLabel: TextView? = null
    private var closing = false
    private var blurAnim: ValueAnimator? = null

    init {
        scrim.setBackgroundColor(0x59080A1E)
        scrim.contentDescription = "Close menu"
        scrim.setOnClickListener { dismiss(true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        for ((gi, g) in groups.withIndex()) {
            val card = buildCard(g)
            cards.add(card)
            column.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gi > 0) topMargin = 12.dp
            })
        }
        addView(column, LayoutParams(WIDTH, LayoutParams.WRAP_CONTENT))

        val app = source?.app
        if (source != null && app != null) {
            val r = source.iconRectOnScreen()
            val f = FloatIcon(act, app.icon, r.width())
            addView(f, LayoutParams(f.size, f.size))
            f.placeAt(r)
            lifted = f
            val lbl = label(act, 13f, Fonts.semibold).apply {
                text = app.label
                gravity = Gravity.CENTER
                setShadowLayer(6.dpf, 0f, 1.dpf, 0x73000000)
            }
            addView(lbl, LayoutParams(160.dp, LayoutParams.WRAP_CONTENT))
            lbl.x = r.exactCenterX() - 80.dp
            lbl.y = (r.bottom + 10.dp).toFloat()
            liftedLabel = lbl
            source.alpha = 0f
        }
    }

    private fun buildCard(items: List<MenuEntry>): LinearLayout {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.background = Glass(card, 22.dpf, 0x38FFFFFF, 0xEB2A2D45.toInt(), border = 0x52FFFFFF, highlight = 0x66FFFFFF)
        card.clipToOutline = true
        card.glassShadow(12)
        for ((i, item) in items.withIndex()) {
            if (i > 0) card.addView(View(act).apply { setBackgroundColor(0x2EFFFFFF) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            val color = if (item.danger) 0xFFFFC2BD.toInt() else Color.WHITE
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(16.dp, 0, 16.dp, 0)
            row.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x24FFFFFF))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            row.isClickable = true
            row.contentDescription = item.label
            val t = label(act, 15f, if (item.danger) Fonts.semibold else Fonts.regular, 1f, color).apply { text = item.label }
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            when {
                item.icon != null -> row.addView(ImageView(act).apply { setImageDrawable(item.icon) },
                    LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginStart = 12.dp })
                item.glyph != null -> row.addView(GlyphView(act, item.glyph, 18, color),
                    LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginStart = 12.dp })
            }
            row.setOnClickListener {
                row.buzz()
                dismiss(true)
                item.action()
            }
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp))
        }
        return card
    }

    fun show() {
        post { position(); animateIn() }
    }

    private var below = true

    private fun position() {
        val w = width
        val h = height
        column.measure(
            MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST),
        )
        val ch = column.measuredHeight
        val ins = act.insets
        val cx: Float
        var top: Float
        val src = source
        if (src != null && lifted != null) {
            val r = src.iconRectOnScreen()
            cx = r.exactCenterX()
            top = r.bottom + 10.dp + 18.dp + 16.dpf
            below = true
            if (top + ch > h - ins.bottom - 16.dp) {
                below = false
                top = r.top - 22.dp - ch.toFloat()
            }
        } else {
            cx = anchorX
            top = anchorY + 12.dp
            below = true
            if (top + ch > h - ins.bottom - 16.dp) {
                below = false
                top = anchorY - 12.dp - ch
            }
        }
        top = top.coerceAtLeast((ins.top + 8.dp).toFloat())
        val left = (cx - WIDTH / 2f).coerceIn(16.dpf, (w - 16.dp - WIDTH).toFloat())
        column.x = left
        column.y = top
        for (c in cards) {
            c.pivotX = cx - left
            c.pivotY = if (below) 0f else c.measuredHeight.toFloat()
        }
    }

    private fun animateIn() {
        if (Motion.reduced) {
            act.setStageBlur(1f)
            return
        }
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(300).start()
        blur(0f, 1f, 300)
        lifted?.let {
            it.animate().scaleX(1.12f).scaleY(1.12f).setDuration(450).setInterpolator(Ease.spring).start()
        }
        liftedLabel?.let {
            it.alpha = 0f
            it.animate().alpha(1f).translationY(6.dpf).setDuration(300).start()
        }
        for ((i, c) in cards.withIndex()) {
            c.alpha = 0f
            c.scaleX = 0.78f; c.scaleY = 0.78f
            c.translationY = if (below) 16.dpf else -16.dpf
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(60L + i * 80L).setDuration(450).setInterpolator(Ease.spring).start()
        }
    }

    private fun blur(from: Float, to: Float, ms: Long) {
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener { act.setStageBlur(it.animatedValue as Float) }
            start()
        }
    }

    fun dismiss(animate: Boolean) {
        if (closing) return
        closing = true
        val done = {
            source?.alpha = 1f
            act.setStageBlur(0f)
            act.onMenuClosed(this)
        }
        if (!animate || Motion.reduced) {
            blurAnim?.cancel()
            done()
            return
        }
        scrim.animate().alpha(0f).setDuration(200).start()
        blur(1f, 0f, 200)
        column.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(160).start()
        liftedLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        val f = lifted
        if (f != null) {
            f.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Ease.out).withEndAction { done() }.start()
        } else {
            postDelayed({ done() }, 200)
        }
    }

    companion object {
        private val WIDTH get() = 250.dp
    }
}

/** A glass card with a message and one or two buttons (set as default, turn on double-tap to lock…). */
@SuppressLint("ViewConstructor")
class PromptCard(
    act: LauncherActivity,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: (() -> Unit)?,
    private val onGone: (PromptCard) -> Unit,
) : LinearLayout(act) {
    init {
        orientation = VERTICAL
        background = Glass(this, 26.dpf, 0x40FFFFFF, 0xEB2A2D45.toInt(), border = 0x52FFFFFF, highlight = 0x73FFFFFF)
        glassShadow(14)
        setPadding(20.dp, 18.dp, 20.dp, 16.dp)
        isClickable = true
        addView(label(act, 17f, Fonts.semibold).apply { text = title; isSingleLine = false; maxLines = 2 })
        addView(TextView(act).apply {
            text = body
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(Color.WHITE)
            alpha = 0.85f
            setLineSpacing(2.dpf, 1f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        val buttons = LinearLayout(act)
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.END
        if (secondary != null) {
            buttons.addView(button(act, secondary, false) { dismiss(); onSecondary?.invoke() })
        }
        buttons.addView(button(act, primary, true) { dismiss(); onPrimary() },
            LayoutParams(LayoutParams.WRAP_CONTENT, 40.dp).apply { marginStart = 10.dp })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    private fun button(act: LauncherActivity, text: String, primary: Boolean, click: () -> Unit): TextView =
        label(act, 14f, Fonts.semibold, 1f, if (primary) 0xFF1B1446.toInt() else Color.WHITE).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            minHeight = 40.dp
            background = GradientDrawable().apply {
                cornerRadius = 20.dpf
                setColor(if (primary) Color.WHITE else 0x2EFFFFFF)
            }
            isClickable = true
            springPress(0.95f)
            setOnClickListener { click() }
        }

    fun show() {
        if (Motion.reduced) return
        alpha = 0f
        translationY = 40.dpf
        animate().alpha(1f).translationY(0f).setDuration(500).setInterpolator(Ease.spring).start()
    }

    fun dismiss() {
        if (Motion.reduced) {
            onGone(this); return
        }
        animate().alpha(0f).translationY(30.dpf).setDuration(220).withEndAction { onGone(this) }.start()
    }
}

/** A glass bottom sheet with a title and rows, over a dim scrim. */
@SuppressLint("ViewConstructor")
class GlassSheet(context: android.content.Context, title: String, private val bottomInset: Int, private val onGone: (GlassSheet) -> Unit) :
    FrameLayout(context) {
    private val scrim = View(context)
    private val card = LinearLayout(context)
    val body = LinearLayout(context)
    private var closing = false

    init {
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.orientation = LinearLayout.VERTICAL
        card.background = Glass(card, 28.dpf, 0x40FFFFFF, 0xF2262A40.toInt(), border = 0x52FFFFFF, highlight = 0x73FFFFFF)
        card.clipToOutline = true
        card.glassShadow(16)
        card.isClickable = true
        card.addView(label(context, 17f, Fonts.semibold).apply {
            text = title
            setPadding(20.dp, 20.dp, 20.dp, 12.dp)
        })
        body.orientation = LinearLayout.VERTICAL
        val scroll = android.widget.ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body)
        val maxH = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
        card.addView(object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.setPadding(0, 0, 0, 10.dp)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(12.dp, 0, 12.dp, bottomInset + 12.dp)
        })
    }

    fun row(glyph: String?, icon: Drawable?, title: String, subtitle: String?, danger: Boolean = false, click: () -> Unit) {
        if (body.childCount > 0) body.addView(View(context).apply { setBackgroundColor(0x24FFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 20.dp })
        val r = LinearLayout(context)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(20.dp, 8.dp, 18.dp, 8.dp)
        r.minimumHeight = 54.dp
        r.isClickable = true
        r.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x1FFFFFFF))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        val color = if (danger) 0xFFFFC2BD.toInt() else Color.WHITE
        if (icon != null) r.addView(ImageView(context).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 14.dp })
        else if (glyph != null) r.addView(GlyphView(context, glyph, 20, color), LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginEnd = 14.dp })
        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(context, 16f, Fonts.regular, 1f, color).apply { text = title })
        if (!subtitle.isNullOrEmpty()) texts.addView(label(context, 13f, Fonts.regular, 0.7f).apply { text = subtitle },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.setOnClickListener {
            r.buzz()
            close()
            click()
        }
        body.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    fun message(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            alpha = 0.8f
            setLineSpacing(2.dpf, 1f)
            setPadding(20.dp, 0, 20.dp, 12.dp)
        })
    }

    fun show() {
        if (Motion.reduced) return
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(250).start()
        card.translationY = 500.dpf
        card.animate().translationY(0f).setDuration(450).setInterpolator(Ease.sheet).start()
    }

    fun close() {
        if (closing) return
        closing = true
        if (Motion.reduced) {
            onGone(this); return
        }
        animate().alpha(0f).setDuration(180).withEndAction { onGone(this) }.start()
    }
}
