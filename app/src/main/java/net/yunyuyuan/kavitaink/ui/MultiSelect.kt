package net.yunyuyuan.kavitaink.ui

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import net.yunyuyuan.kavitaink.R

/**
 * 多选模式（书架、系列页共用）：
 *  - 进入后顶栏换成「已选 N 项」+「选择…」+「取消」，列表下方出现批量操作按钮
 *  - 勾选 / 取消勾选只重新绑定对应的那一行（墨水屏只刷新这一行）
 *  - 选中项按 key 记录：全选会把不在当前页的项也一并选中，翻页按钮照常可用
 *
 * keyOf 返回 null 的行不可选（例如系列页的分组标题）。
 */
class MultiSelect<T, K>(
    private val a: BaseActivity,
    private val list: ListView,
    private val adapter: () -> RowAdapter<T>,
    private val keyOf: (T) -> K?,
    /** 「选择…」菜单里除全选、反选之外的筛选项：(文案, 条件) */
    private val filters: List<Pair<String, (T) -> Boolean>> = emptyList(),
) {
    class Op(val label: Int, val run: () -> Unit)

    val selected = LinkedHashSet<K>()
    var active = false
        private set

    lateinit var head: View
        private set
    lateinit var bottom: LinearLayout
        private set
    private lateinit var normalHead: View
    private lateinit var title: TextView
    private val opButtons = ArrayList<Pair<Button, Int>>()

    /** 生成多选顶栏和底部操作栏（初始隐藏）；调用方把 head 放在 normalHead 旁边，把 bottom 放在列表下方 */
    fun build(normalHead: View, ops: List<Op>) {
        this.normalHead = normalHead
        val (h, _) = a.header("", back = false, actions = listOf(
            a.getString(R.string.sel_choose) to ::showChooser,
            a.getString(R.string.cancel) to ::exit,
        ))
        head = h
        // header() 的结构：外层 vbox → 标题栏 HeaderBar（子控件顺序 back?、title、actions）
        title = ((h as ViewGroup).getChildAt(0) as HeaderBar).title
        head.visibility = View.GONE
        bottom = a.hbox().apply {
            setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4))
            visibility = View.GONE
        }
        ops.forEachIndexed { i, op ->
            val b = a.button("") { if (selected.isNotEmpty()) op.run() }
            bottom.addView(b, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = a.dp(6) })
            opButtons += b to op.label
        }
    }

    fun isSelected(item: T): Boolean = keyOf(item)?.let { it in selected } == true

    /** 列表项标题前的勾选框；非多选模式下为空 */
    fun mark(item: T): String = when {
        !active || keyOf(item) == null -> ""
        isSelected(item) -> "☑ "
        else -> "☐ "
    }

    fun enter(first: T? = null) {
        active = true
        selected.clear()
        first?.let { keyOf(it)?.let(selected::add) }
        adapter().notifyDataSetChanged()
        changed()
    }

    fun exit() {
        if (!active) return
        active = false
        selected.clear()
        adapter().notifyDataSetChanged()
        changed()
    }

    fun toggle(pos: Int) {
        val item = adapter().items.getOrNull(pos) ?: return
        val k = keyOf(item) ?: return
        if (!selected.remove(k)) selected.add(k)
        refreshRow(pos)
        changed()
    }

    fun selectedItems(): List<T> = adapter().items.filter { isSelected(it) }

    /** 列表数据刷新后，去掉已不存在的选中项 */
    fun prune() {
        val keys = adapter().items.mapNotNull(keyOf).toSet()
        if (selected.retainAll(keys)) changed()
    }

    private fun selectWhere(pred: (T) -> Boolean) {
        selected.clear()
        adapter().items.forEach { if (pred(it)) keyOf(it)?.let(selected::add) }
        adapter().notifyDataSetChanged()
        changed()
    }

    private fun invert() {
        val flipped = adapter().items.mapNotNull(keyOf).filter { it !in selected }
        selected.clear()
        selected.addAll(flipped)
        adapter().notifyDataSetChanged()
        changed()
    }

    private fun showChooser() {
        val labels = listOf(a.getString(R.string.sel_all), a.getString(R.string.sel_invert)) + filters.map { it.first }
        AlertDialog.Builder(a)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> selectWhere { true }
                    1 -> invert()
                    else -> selectWhere(filters[which - 2].second)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 只重新绑定这一行，不刷新整个列表 */
    private fun refreshRow(pos: Int) {
        val child = list.getChildAt(pos - list.firstVisiblePosition) ?: return
        adapter().getView(pos, child, list)
    }

    private fun changed() {
        normalHead.visibility = if (active) View.GONE else View.VISIBLE
        head.visibility = if (active) View.VISIBLE else View.GONE
        bottom.visibility = if (active) View.VISIBLE else View.GONE
        val n = selected.size
        title.text = a.resources.getQuantityString(R.plurals.sel_count, n, n)
        opButtons.forEach { (b, res) ->
            b.text = a.getString(res, n)
            b.isEnabled = n > 0
            b.setTextColor(if (n > 0) 0xFF000000.toInt() else 0xFF999999.toInt())
        }
    }
}
