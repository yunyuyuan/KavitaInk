package com.manus.kavitaink.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.manus.kavitaink.App
import com.manus.kavitaink.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Exec {
    val io: ExecutorService = Executors.newFixedThreadPool(2)
}

abstract class BaseActivity : Activity() {

    protected val app: App get() = App.I
    protected val prefs: Prefs get() = App.I.prefs

    /** 若设置，则音量键 / 翻页键用来整屏翻列表 */
    protected var pagedList: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    fun <T> bg(work: () -> T, done: (T?, Throwable?) -> Unit) {
        Exec.io.execute {
            val r = runCatching(work)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) done(r.getOrNull(), r.exceptionOrNull())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val lv = pagedList
        if (lv != null) {
            val vol = prefs.volumeKeys
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN -> { Pager.down(lv); return true }
                KeyEvent.KEYCODE_PAGE_UP -> { Pager.up(lv); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (vol) {
                    if (prefs.swapVolume) Pager.up(lv) else Pager.down(lv); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> if (vol) {
                    if (prefs.swapVolume) Pager.down(lv) else Pager.up(lv); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pagedList != null && prefs.volumeKeys &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 顶部标题栏：返回 + 标题 + 操作按钮。返回 (标题栏, 操作按钮列表) */
    fun header(title: String, back: Boolean, actions: List<Pair<String, () -> Unit>> = emptyList()): Pair<View, List<Button>> {
        val wrap = vbox()
        val bar = hbox()
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))
        bar.minimumHeight = dp(52)
        if (back) bar.addView(button("‹ 返回") { finish() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        val t = label(title, 20f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(if (back) 0 else dp(8), 0, dp(4), 0)
        }
        bar.addView(t, lp(0, WRAP, 1f))
        val btns = actions.map { (name, fn) ->
            button(name, fn).also { bar.addView(it, lp(WRAP, WRAP).apply { leftMargin = dp(6) }) }
        }
        wrap.addView(bar)
        wrap.addView(hline(2))
        return wrap to btns
    }

    fun statusLine(): TextView = label("", 14f, color = 0xFF222222.toInt()).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    fun confirm(title: String, msg: String, ok: String = "确定", onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    fun choose(title: String, labels: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun emptyView(text: String): TextView = label(text, 16f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(48), dp(24), dp(48))
        setBackgroundColor(Color.WHITE)
    }

    fun root(): LinearLayout = vbox().apply {
        isFocusableInTouchMode = true
    }
}
