package com.wellsey.perch

import android.view.HapticFeedbackConstants
import android.view.View

/** One feel per action, so each one is recognisable without looking. */
object Haptics {
    /** A light tick: a page snapping, a slot opening up while dragging. */
    fun tick(v: View) = v.buzz(HapticFeedbackConstants.CLOCK_TICK)

    /** A tap that opens something: launching an app or a folder. */
    fun tap(v: View) = v.buzz(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Picking something up: long-press, edit mode. */
    fun lift(v: View) = v.buzz(HapticFeedbackConstants.LONG_PRESS)

    /** A firmer thump: an icon landing in a folder, a folder being made. */
    fun thump(v: View) = v.buzz(HapticFeedbackConstants.CONFIRM)

    /** Settling: dropping an icon into place. */
    fun drop(v: View) = v.buzz(HapticFeedbackConstants.GESTURE_END)

    /** A sheet starting to move: the app drawer or widget panel. */
    fun sheet(v: View) = v.buzz(HapticFeedbackConstants.GESTURE_START)

    /** Something you can't do. */
    fun reject(v: View) = v.buzz(HapticFeedbackConstants.REJECT)
}
