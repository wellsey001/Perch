package com.wellsey.perch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Rect
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.min

/** An open folder: a frosted panel that grows out of the folder icon, with its name and apps. */
@SuppressLint("ViewConstructor")
class FolderOverlay(
    private val act: LauncherActivity,
    val folder: Folder,
    private val from: Rect,
    apps: Map<String, AppInfo>,
    rename: Boolean,
) : FrameLayout(act) {
    private val scrim = View(act)
    private val panel = LinearLayout(act)
    private val title = EditText(act)
    val grid = AppGrid(act, act, Place.FOLDER)
    private var closing = false
    private var blurAnim: ValueAnimator? = null
    private val panelW: Int

    init {
        scrim.setBackgroundColor(0x4D080A1E)
        scrim.contentDescription = "Close folder"
        scrim.setOnClickListener { close(true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val dm = act.resources.displayMetrics
        panelW = min(dm.widthPixels - 40.dp, 380.dp)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = Glass(panel, 34.dpf, 0x38FFFFFF, 0xEB2A2D45.toInt(), border = 0x52FFFFFF, highlight = 0x73FFFFFF, depth = 2)
        panel.glassShadow(16)
        panel.isClickable = true
        panel.setPadding(14.dp, 18.dp, 14.dp, 14.dp)

        title.setText(folder.name)
        title.background = null
        title.gravity = Gravity.CENTER
        title.textSize = 22f
        title.typeface = Fonts.semibold
        title.setTextColor(0xFFFFFFFF.toInt())
        title.setHintTextColor(0x80FFFFFF.toInt())
        title.hint = "Folder name"
        title.isSingleLine = true
        title.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        title.imeOptions = EditorInfo.IME_ACTION_DONE
        title.setOnEditorActionListener { _, _, _ ->
            title.windowInsetsController?.hide(WindowInsets.Type.ime())
            title.clearFocus()
            true
        }
        panel.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp).apply { bottomMargin = 8.dp })

        val n = folder.items.size
        grid.columns = if (n > 9) 4 else 3
        grid.cellHeight = 100.dp
        grid.maxIcon = 60.dp
        grid.bind(folder.items, apps)
        val rows = ceil(n / grid.columns.toFloat()).toInt().coerceAtLeast(1)
        val h = min(rows * 100.dp, (dm.heightPixels * 0.55f).toInt())
        val scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))

        addView(panel, LayoutParams(panelW, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        if (rename) post {
            title.requestFocus()
            title.selectAll()
            title.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    fun show() {
        post {
            if (Motion.reduced) {
                act.setStageBlur(1.6f); return@post
            }
            // Grow out of the folder icon.
            val loc = IntArray(2)
            panel.getLocationOnScreen(loc)
            val s = from.width().toFloat() / panel.width
            panel.pivotX = 0f
            panel.pivotY = 0f
            panel.scaleX = s
            panel.scaleY = s
            panel.translationX = (from.left - loc[0]).toFloat()
            panel.translationY = (from.top - loc[1]).toFloat()
            panel.alpha = 0.4f
            panel.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(460).setInterpolator(Ease.spring).start()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(300).start()
            blur(0f, 1.6f, 300)
            grid.popIn(140, 30)
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

    fun close(animate: Boolean) {
        if (closing) return
        closing = true
        title.windowInsetsController?.hide(WindowInsets.Type.ime())
        act.renameFolder(folder, title.text.toString())
        val done = {
            act.setStageBlur(0f)
            act.onFolderClosed(this)
        }
        if (!animate || Motion.reduced || panel.width == 0) {
            blurAnim?.cancel()
            done()
            return
        }
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        val s = from.width().toFloat() / panel.width
        scrim.animate().alpha(0f).setDuration(220).start()
        blur(1.6f, 0f, 220)
        panel.animate().scaleX(s).scaleY(s)
            .translationX(panel.translationX + from.left - loc[0])
            .translationY(panel.translationY + from.top - loc[1])
            .alpha(0f).setDuration(240).setInterpolator(Ease.out)
            .withEndAction { done() }.start()
    }
}
