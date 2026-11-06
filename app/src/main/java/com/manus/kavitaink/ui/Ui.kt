package com.manus.kavitaink.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.vbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Color.WHITE)
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(s: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, color: Int = Color.BLACK): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(s: String, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.hline(thicknessDp: Int = 1): View = View(this).apply {
    setBackgroundColor(Color.BLACK)
    layoutParams = lp(MATCH, max(1, dp(thicknessDp)))
}

/** 墨水屏列表：无按压高亮、无过度滚动、无渐隐边缘 */
fun Context.einkList(): ListView = ListView(this).apply {
    divider = ColorDrawable(Color.BLACK)
    dividerHeight = 1
    selector = ColorDrawable(Color.TRANSPARENT)
    cacheColorHint = Color.WHITE
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalFadingEdgeEnabled = false
    // 已有整屏翻页按钮，不显示滚动条（滚动条的显示/淡出都会引起额外的局部刷新）
    isVerticalScrollBarEnabled = false
    isScrollbarFadingEnabled = false
    isSmoothScrollbarEnabled = false
    isFastScrollEnabled = false
    setBackgroundColor(Color.WHITE)
}

object Pager {
    /** 整屏翻页：直接跳到下一屏第一项，无滚动动画 */
    fun down(lv: ListView) {
        val count = lv.count
        if (count == 0) return
        val first = lv.firstVisiblePosition
        val last = lv.lastVisiblePosition
        val lastChild = lv.getChildAt(lv.childCount - 1)
        val lastFullyVisible = lastChild != null && lastChild.bottom <= lv.height - lv.paddingBottom
        var target = if (lastFullyVisible) last + 1 else last
        if (target <= first) target = first + 1
        if (target >= count) return
        lv.setSelectionFromTop(min(target, count - 1), 0)
    }

    fun up(lv: ListView) {
        val first = lv.firstVisiblePosition
        if (first <= 0) {
            lv.setSelectionFromTop(0, 0)
            return
        }
        val visible = max(1, lv.lastVisiblePosition - first)
        lv.setSelectionFromTop(max(0, first - visible), 0)
    }
}

/** 底部翻页条 */
fun Context.pagerBar(lv: ListView): View {
    val bar = hbox()
    bar.setPadding(dp(6), dp(4), dp(6), dp(4))
    val up = button(getString(com.manus.kavitaink.R.string.list_prev_page)) { Pager.up(lv) }
    val down = button(getString(com.manus.kavitaink.R.string.list_next_page)) { Pager.down(lv) }
    bar.addView(up, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
    bar.addView(down, lp(0, WRAP, 1f))
    val wrap = vbox()
    wrap.addView(hline(2))
    wrap.addView(bar)
    return wrap
}

class RowAdapter<T>(
    var items: List<T>,
    private val create: () -> View,
    private val bind: (View, T, Int) -> Unit,
) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): Any? = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: create()
        bind(v, items[position], position)
        return v
    }

    fun update(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }
}

/** 标准两行列表项，可选封面 */
class TwoLineRow(ctx: Context, withCover: Boolean) {
    val root: LinearLayout = ctx.hbox()
    val cover: ImageView?
    val title: TextView
    val sub: TextView

    init {
        root.minimumHeight = ctx.dp(60)
        root.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        cover = if (withCover) ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        } else null
        cover?.let { root.addView(it, lp(ctx.dp(52), ctx.dp(72)).apply { rightMargin = ctx.dp(12) }) }
        val texts = ctx.vbox()
        title = ctx.label("", 17f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        sub = ctx.label("", 13f, color = 0xFF333333.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.setBackgroundColor(Color.TRANSPARENT)
        texts.addView(title)
        texts.addView(sub)
        root.addView(texts, lp(0, WRAP, 1f))
        root.tag = this
    }

    companion object {
        fun of(v: View) = v.tag as TwoLineRow
    }
}
