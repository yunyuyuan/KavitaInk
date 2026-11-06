package com.manus.kavitaink.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.manus.kavitaink.R

/** 一个可点击的设置项：label 每次刷新时重新计算；enabled 为 false 时显示为灰色且不可点 */
class Opt(val label: () -> String, val enabled: () -> Boolean = { true }, val onClick: () -> Unit)

/**
 * 设置项列表对话框：点一项就执行并原地刷新所有文字（不关闭对话框，没有动画）。
 * 用于阅读器里的排版设置等需要“改了立即看到效果”的场景。
 */
fun Activity.optionsDialog(title: String, opts: List<Opt>, onDismiss: (() -> Unit)? = null) {
    val ctx = this
    val adapter = object : BaseAdapter() {
        override fun getCount() = opts.size
        override fun getItem(position: Int): Any = opts[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = opts[position].enabled()
        override fun areAllItemsEnabled() = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: ctx.label("", 17f).apply {
                minHeight = ctx.dp(52)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
            }
            val o = opts[position]
            tv.text = o.label()
            tv.setTextColor(if (o.enabled()) Color.BLACK else 0xFF888888.toInt())
            return tv
        }
    }
    val lv = einkList()
    lv.adapter = adapter
    lv.setOnItemClickListener { _, _, pos, _ ->
        val o = opts[pos]
        if (o.enabled()) {
            o.onClick()
            adapter.notifyDataSetChanged()
        }
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(lv)
        .setPositiveButton(R.string.done, null)
        .setOnDismissListener { onDismiss?.invoke() }
        .show()
}
