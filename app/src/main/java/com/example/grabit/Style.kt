package com.example.grabit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

object Style {

    private val CYAN = 0xFF00E5FF.toInt()
    private val CARD = 0xFF14141C.toInt()
    private val LINE = 0xFF1E2A33.toInt()
    private val FIELD = 0xFF0F1117.toInt()
    private val GRAY = 0xFF667788.toInt()
    private val DARK_LINE = 0xFF334155.toInt()
    private val BTN_FILL = 0xFF0F1A1F.toInt()

    fun accent(ctx: Context): Int {
        return when (Prefs.theme(ctx)) {
            1 -> 0xFF00FF9C.toInt()
            2 -> 0xFFFF2BD6.toInt()
            else -> CYAN
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    private fun box(ctx: Context, fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setColor(fill)
        g.setStroke(dp(ctx, strokeDp), stroke)
        return g
    }

    fun apply(root: View) {
        val ctx = root.context
        walk(root, ctx, accent(ctx))
    }

    private fun walk(v: View, ctx: Context, acc: Int) {
        val bg = v.background
        if (bg is ColorDrawable && bg.color == CARD) {
            v.background = box(ctx, CARD, LINE, 1, 14)
        }

        if (v is CompoundButton) {
            v.buttonTintList = ColorStateList.valueOf(acc)
        } else if (v is Button) {
            styleButton(v, ctx, acc)
        } else if (v is EditText) {
            v.background = box(ctx, FIELD, acc, 1, 12)
            val p = dp(ctx, 12)
            v.setPadding(p, p, p, p)
        } else if (v is ProgressBar) {
            v.progressTintList = ColorStateList.valueOf(acc)
            v.indeterminateTintList = ColorStateList.valueOf(acc)
            v.progressBackgroundTintList = ColorStateList.valueOf(LINE)
        } else if (v is TextView) {
            if (v.currentTextColor == CYAN) v.setTextColor(acc)
        }

        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), ctx, acc)
            }
        }
    }

    private fun styleButton(b: Button, ctx: Context, acc: Int) {
        val off = box(ctx, CARD, DARK_LINE, 2, 12)
        val down = box(ctx, alpha(acc, 0x66), acc, 2, 12)
        val norm = box(ctx, BTN_FILL, acc, 2, 12)

        val states = StateListDrawable()
        states.addState(intArrayOf(-android.R.attr.state_enabled), off)
        states.addState(intArrayOf(android.R.attr.state_pressed), down)
        states.addState(intArrayOf(), norm)

        b.background = InsetDrawable(states, dp(ctx, 2))
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(GRAY, acc)
            )
        )
        b.isAllCaps = false
        b.stateListAnimator = null
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.minHeight = dp(ctx, 44)
        b.minWidth = 0
    }
}