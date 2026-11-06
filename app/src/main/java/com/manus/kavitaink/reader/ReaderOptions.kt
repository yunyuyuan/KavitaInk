package com.manus.kavitaink.reader

import android.content.Context
import android.graphics.Color
import com.manus.kavitaink.Prefs
import com.manus.kavitaink.R
import com.manus.kavitaink.ui.Opt

/** 排版、双页的选项列表：阅读器菜单和设置页共用，保证两边的选项和取值完全一致 */
object ReaderOptions {
    val MARGIN_X_STEPS = listOf(8, 12, 16, 24, 32, 48)
    val MARGIN_Y_STEPS = listOf(8, 12, 18, 24, 32, 48)
    val INDENT_STEPS = listOf(0, 1, 2, 3, 4)
    val PARA_STEPS = listOf(0, 5, 10, 15)
    val LETTER_STEPS = listOf(0, 5, 10, 15)
    val GAP_STEPS = (0..64 step 4).toList()
    val DIVIDER_STEPS = (1..8).toList()

    fun <T> next(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

    private fun Context.lv(label: Int, value: String) = getString(R.string.label_value, getString(label), value)
    private fun Context.onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

    // ---------- 小说排版 ----------

    fun indentName(c: Context, v: Int): String =
        if (v == 0) c.getString(R.string.value_none) else c.resources.getQuantityString(R.plurals.indent_n, v, v)

    fun paraName(c: Context, v: Int): String = c.getString(
        when (v) {
            0 -> R.string.value_none
            5 -> R.string.value_small
            10 -> R.string.value_medium
            else -> R.string.value_large
        }
    )

    fun letterName(c: Context, v: Int): String = c.getString(
        when (v) {
            0 -> R.string.letter_normal
            5 -> R.string.letter_slightly_wide
            10 -> R.string.letter_wide
            else -> R.string.letter_extra_wide
        }
    )

    /** 小说排版；apply 在每次修改后调用（阅读器里用来立即重新排版） */
    fun layout(c: Context, p: Prefs, apply: () -> Unit): List<Opt> {
        val custom = { !p.bookStyle }
        return listOf(
            Opt({ c.lv(R.string.layout_book_style, c.onOff(p.bookStyle)) }) { p.bookStyle = !p.bookStyle; apply() },
            Opt({ c.lv(R.string.layout_indent, indentName(c, p.textIndent)) }, custom) {
                p.textIndent = next(INDENT_STEPS, p.textIndent); apply()
            },
            Opt({ c.lv(R.string.layout_para_spacing, paraName(c, p.paraSpacing)) }, custom) {
                p.paraSpacing = next(PARA_STEPS, p.paraSpacing); apply()
            },
            Opt({ c.lv(R.string.layout_justify, c.onOff(p.justify)) }, custom) { p.justify = !p.justify; apply() },
            Opt({ c.lv(R.string.layout_letter_spacing, letterName(c, p.letterSpacing)) }, custom) {
                p.letterSpacing = next(LETTER_STEPS, p.letterSpacing); apply()
            },
            Opt({ c.lv(R.string.layout_margin_x, c.getString(R.string.px_n, p.marginX)) }) {
                p.marginX = next(MARGIN_X_STEPS, p.marginX); apply()
            },
            Opt({ c.lv(R.string.layout_margin_y, c.getString(R.string.px_n, p.marginY)) }) {
                p.marginY = next(MARGIN_Y_STEPS, p.marginY); apply()
            },
        )
    }

    // ---------- 漫画双页 ----------

    fun spreadModeName(c: Context, v: Int): String = c.getString(
        when (v) {
            1 -> R.string.spread_auto
            2 -> R.string.spread_always
            else -> R.string.spread_off
        }
    )

    fun orderName(c: Context, v: Int): String = c.getString(
        when (v) {
            1 -> R.string.order_ltr
            2 -> R.string.order_rtl
            else -> R.string.order_follow
        }
    )

    fun colorName(c: Context, v: Int): String = c.getString(
        when (v) {
            1 -> R.string.color_dark_gray
            2 -> R.string.color_light_gray
            3 -> R.string.color_white
            else -> R.string.color_black
        }
    )

    fun dividerColor(v: Int): Int = when (v) {
        1 -> 0xFF555555.toInt()
        2 -> 0xFFAAAAAA.toInt()
        3 -> Color.WHITE
        else -> Color.BLACK
    }

    fun cropName(c: Context, p: Prefs): String = c.getString(
        when {
            !p.cropMargins -> R.string.off
            p.cropBlack -> R.string.crop_white_black
            else -> R.string.crop_white
        }
    )

    /** 双页模式及其子项；子项在双页关闭时显示为灰色 */
    fun spread(c: Context, p: Prefs, apply: () -> Unit): List<Opt> {
        val on = { p.spreadMode != 0 }
        val line = { p.spreadMode != 0 && p.spreadDivider }
        return listOf(
            Opt({ c.lv(R.string.spread_mode, spreadModeName(c, p.spreadMode)) }) { p.spreadMode = (p.spreadMode + 1) % 3; apply() },
            Opt({ c.lv(R.string.spread_order, orderName(c, p.spreadOrder)) }, on) { p.spreadOrder = (p.spreadOrder + 1) % 3; apply() },
            Opt({ c.lv(R.string.spread_gap, c.getString(R.string.px_n, p.spreadGap)) }, on) {
                p.spreadGap = next(GAP_STEPS, p.spreadGap); apply()
            },
            Opt({ c.lv(R.string.spread_divider, c.onOff(p.spreadDivider)) }, on) { p.spreadDivider = !p.spreadDivider; apply() },
            Opt({ c.lv(R.string.divider_width, c.getString(R.string.px_n, p.dividerWidth)) }, line) {
                p.dividerWidth = next(DIVIDER_STEPS, p.dividerWidth); apply()
            },
            Opt({ c.lv(R.string.divider_color, colorName(c, p.dividerColor)) }, line) {
                p.dividerColor = (p.dividerColor + 1) % 4; apply()
            },
            Opt({ c.lv(R.string.spread_cover, c.onOff(p.spreadCover)) }, on) { p.spreadCover = !p.spreadCover; apply() },
        )
    }
}
