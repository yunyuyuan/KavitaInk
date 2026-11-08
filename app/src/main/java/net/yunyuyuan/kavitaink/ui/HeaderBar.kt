package net.yunyuyuan.kavitaink.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 顶栏：[返回] 标题 [按钮…]。
 * 放得下时排成一行（标题占剩余宽度，过长省略）；按钮太多 / 文案太长（德语、俄语等）、
 * 标题会被挤得太窄时，自动改成两行：第一行是返回 + 标题，按钮在第二行靠右排列，一行放不下再换行。
 * 子控件顺序固定为 back?、title、actions…（MultiSelect 依赖这个顺序取标题）。
 */
class HeaderBar(ctx: Context, private val back: View?, val title: TextView, private val actions: List<View>) : ViewGroup(ctx) {
    private val dens = resources.displayMetrics.density
    private val gap = (6 * dens).toInt()
    private val rowGap = (4 * dens).toInt()
    private val minTitle = (96 * dens).toInt()

    /** 每个子控件的位置（相对内容区左上角）：x、y，在 onMeasure 里算好，onLayout 直接用 */
    private val pos = HashMap<View, IntArray>()

    init {
        back?.let { addView(it) }
        addView(title)
        actions.forEach { addView(it) }
    }

    private fun exactly(n: Int) = MeasureSpec.makeMeasureSpec(max(0, n), MeasureSpec.EXACTLY)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = max(0, w - paddingLeft - paddingRight)
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val atMost = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST)
        back?.measure(atMost, unspec)
        actions.forEach { it.measure(atMost, unspec) }
        title.measure(unspec, unspec)

        val backW = back?.let { it.measuredWidth + gap } ?: 0
        val actW = actions.sumOf { it.measuredWidth + gap }
        val avail = inner - backW - actW
        // 标题需要的最小宽度：自身宽度和「内容区 40%（至少 96dp）」中较小的那个；空标题（多选栏）不占位
        val needTitle = if (title.text.isNullOrEmpty()) 0 else min(title.measuredWidth, max(inner * 2 / 5, minTitle))
        val oneRow = actions.isEmpty() || avail >= needTitle
        pos.clear()
        var height: Int
        if (oneRow) {
            title.measure(exactly(avail), unspec)
            val rowH = maxOf(back?.measuredHeight ?: 0, title.measuredHeight, actions.maxOfOrNull { it.measuredHeight } ?: 0)
            var x = 0
            back?.let { pos[it] = intArrayOf(0, (rowH - it.measuredHeight) / 2); x = backW }
            pos[title] = intArrayOf(x, (rowH - title.measuredHeight) / 2)
            var r = inner
            for (a in actions.asReversed()) {
                r -= a.measuredWidth
                pos[a] = intArrayOf(r, (rowH - a.measuredHeight) / 2)
                r -= gap
            }
            height = rowH
        } else {
            title.measure(exactly(inner - backW), unspec)
            val rowH = max(back?.measuredHeight ?: 0, title.measuredHeight)
            back?.let { pos[it] = intArrayOf(0, (rowH - it.measuredHeight) / 2) }
            pos[title] = intArrayOf(backW, (rowH - title.measuredHeight) / 2)
            // 按钮分行，每行靠右
            val lines = ArrayList<MutableList<View>>()
            var cur = ArrayList<View>()
            var used = 0
            for (a in actions) {
                val need = a.measuredWidth + if (cur.isEmpty()) 0 else gap
                if (cur.isNotEmpty() && used + need > inner) {
                    lines += cur
                    cur = ArrayList()
                    used = 0
                }
                used += if (cur.isEmpty()) a.measuredWidth else a.measuredWidth + gap
                cur += a
            }
            if (cur.isNotEmpty()) lines += cur
            var y = rowH + rowGap
            for (line in lines) {
                val lh = line.maxOf { it.measuredHeight }
                var r = inner
                for (a in line.asReversed()) {
                    r -= a.measuredWidth
                    pos[a] = intArrayOf(max(0, r), y + (lh - a.measuredHeight) / 2)
                    r -= gap
                }
                y += lh + rowGap
            }
            height = y - rowGap
        }
        val h = max(height + paddingTop + paddingBottom, minimumHeight)
        // 单行时整体垂直居中（minimumHeight 比内容高时）
        val shift = if (oneRow) (h - paddingTop - paddingBottom - height) / 2 else 0
        if (shift > 0) pos.values.forEach { it[1] += shift }
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = pos[c] ?: continue
            val x = paddingLeft + p[0]
            val y = paddingTop + p[1]
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
