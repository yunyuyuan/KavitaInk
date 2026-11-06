package com.manus.kavitaink.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.manus.kavitaink.R
import com.manus.kavitaink.core.Kind
import com.manus.kavitaink.core.LocalChapter
import com.manus.kavitaink.ui.BaseActivity
import com.manus.kavitaink.ui.MATCH
import com.manus.kavitaink.ui.WRAP
import com.manus.kavitaink.ui.button
import com.manus.kavitaink.ui.dp
import com.manus.kavitaink.ui.hbox
import com.manus.kavitaink.ui.hline
import com.manus.kavitaink.ui.label
import com.manus.kavitaink.ui.lp
import com.manus.kavitaink.ui.vbox
import java.io.File
import kotlin.math.abs

object Readers {
    fun open(ctx: Context, c: LocalChapter) {
        val cls = if (c.kind == Kind.EPUB) EpubReaderActivity::class.java else ImageReaderActivity::class.java
        ctx.startActivity(Intent(ctx, cls).putExtra("dir", c.dir.absolutePath))
    }
}

/**
 * 阅读器公共逻辑：
 *  - 点击分区 / 滑动 / 音量键 / 实体翻页键翻页；中间点击或长按呼出菜单
 *  - 菜单与提示都是静态覆盖层，没有任何动画
 *  - 可选“每 N 页黑屏闪一下”做全刷，消除墨水屏残影
 *  - 读到章节末尾时，再翻一页进入下一个已下载的章节
 */
abstract class ReaderActivity : BaseActivity() {

    protected lateinit var chapter: LocalChapter
    protected lateinit var frame: FrameLayout
    protected lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var flash: View
    private lateinit var menu: LinearLayout
    private lateinit var menuRows: LinearLayout
    private lateinit var menuInfo: TextView

    private var turns = 0
    private var endArmed = false
    private var startArmed = false
    private var siblings: List<LocalChapter>? = null

    protected open val rtl: Boolean get() = false
    protected open val infoGravity: Int get() = Gravity.BOTTOM or Gravity.END

    /** 创建阅读内容视图，并通过 frame.addView 加入 */
    protected abstract fun setup()
    protected abstract fun nextPage()
    protected abstract fun prevPage()
    protected abstract fun buildMenu(rows: LinearLayout)
    protected abstract fun progressText(): String
    protected abstract fun savePosition()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = intent.getStringExtra("dir")?.let { app.storage.readChapter(File(it)) }
        if (c == null) {
            Toast.makeText(this, "内容不存在或已被删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        chapter = c
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemUi()

        frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(frame)
        setup()

        info = label("", 12f).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = if (prefs.showPageNum) View.VISIBLE else View.GONE
        }
        frame.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, infoGravity).apply {
            bottomMargin = dp(4); rightMargin = dp(6); leftMargin = dp(6)
        })

        hint = label("", 15f).apply {
            setBackgroundResource(R.drawable.btn_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(56); leftMargin = dp(24); rightMargin = dp(24)
        })

        menu = vbox().apply {
            visibility = View.GONE
            isClickable = true
        }
        menu.addView(hline(2))
        val inner = vbox().apply { setPadding(dp(10), dp(8), dp(10), dp(10)) }
        val title = label(chapter.meta.seriesName + " · " + chapter.label, 16f, bold = true).apply { maxLines = 2 }
        menuInfo = label("", 14f).apply { setPadding(0, dp(4), 0, dp(6)) }
        menuRows = vbox()
        inner.addView(title)
        inner.addView(menuInfo)
        inner.addView(menuRows)
        menu.addView(inner)
        frame.addView(menu, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        flash = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        frame.addView(flash, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------- 菜单 ----------

    protected val menuVisible get() = ::menu.isInitialized && menu.visibility == View.VISIBLE

    protected fun showMenu() {
        hideHint()
        rebuildMenu()
        menu.visibility = View.VISIBLE
    }

    protected fun hideMenu() {
        if (::menu.isInitialized) menu.visibility = View.GONE
    }

    protected fun rebuildMenu() {
        menuInfo.text = progressText()
        menuRows.removeAllViews()
        buildMenu(menuRows)
        val prev = sibling(-1)
        val next = sibling(+1)
        menuRow(
            "◀ 上一章" to { prev?.let(::openChapter) ?: showHint("没有已下载的上一章") },
            "下一章 ▶" to { next?.let(::openChapter) ?: showHint("没有已下载的下一章") },
        )
        menuRow(
            "全刷屏幕" to { hideMenu(); flashRefresh() },
            "退出阅读" to { finish() },
        )
    }

    protected fun menuRow(vararg items: Pair<String, () -> Unit>) {
        val row = hbox()
        items.forEachIndexed { i, (name, fn) ->
            row.addView(button(name, fn), lp(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(6)
                topMargin = dp(6)
            })
        }
        menuRows.addView(row, lp(MATCH, WRAP))
    }

    // ---------- 提示 ----------

    protected fun showHint(text: String) {
        if (!::hint.isInitialized) return
        hint.text = text
        hint.visibility = View.VISIBLE
    }

    protected fun hideHint() {
        if (::hint.isInitialized && hint.visibility == View.VISIBLE) hint.visibility = View.GONE
    }

    protected fun updateInfo() {
        if (::info.isInitialized && prefs.showPageNum) info.text = progressText()
    }

    // ---------- 翻页 ----------

    private fun forward() {
        if (menuVisible) { hideMenu(); return }
        hideHint()
        nextPage()
    }

    private fun backward() {
        if (menuVisible) { hideMenu(); return }
        hideHint()
        prevPage()
    }

    /** 每次成功翻页后调用 */
    protected fun turned() {
        endArmed = false
        startArmed = false
        turns++
        val n = prefs.fullRefreshEvery
        if (n > 0 && turns % n == 0) flashRefresh()
        updateInfo()
        savePosition()
    }

    protected fun flashRefresh() {
        flash.visibility = View.VISIBLE
        flash.postDelayed({ flash.visibility = View.GONE }, 120)
    }

    protected fun reachedEnd() {
        val next = sibling(+1)
        when {
            next == null -> showHint("已经是最后一页")
            endArmed -> openChapter(next)
            else -> {
                endArmed = true
                showHint("本章已读完，再翻一页打开：\n${next.label}")
            }
        }
    }

    protected fun reachedStart() {
        val prev = sibling(-1)
        when {
            prev == null -> showHint("已经是第一页")
            startArmed -> openChapter(prev)
            else -> {
                startArmed = true
                showHint("已是本章第一页，再往前翻打开：\n${prev.label}")
            }
        }
    }

    private fun sibling(delta: Int): LocalChapter? {
        val list = siblings ?: app.storage.listChapters(chapter.serverKey, chapter.seriesId).also { siblings = it }
        val i = list.indexOfFirst { it.chapterId == chapter.chapterId }
        if (i < 0) return null
        return list.getOrNull(i + delta)
    }

    private fun openChapter(c: LocalChapter) {
        savePosition()
        Readers.open(this, c)
        finish()
    }

    // ---------- 触摸 ----------

    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val longPress = Runnable {
        longFired = true
        showMenu()
    }

    protected val touch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longFired = false
                v.postDelayed(longPress, 650)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) v.removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPress)
                if (!longFired) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (prefs.swipe && abs(dx) > dp(48) && abs(dx) > abs(dy) * 1.5f) {
                        // 左滑 = 下一页（RTL 漫画相反）
                        if ((dx < 0) != rtl) forward() else backward()
                    } else if (abs(dx) < slop * 2 && abs(dy) < slop * 2) {
                        tap(e.x, v.width)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
        }
        true
    }

    private fun tap(x: Float, w: Int) {
        if (menuVisible) { hideMenu(); return }
        val third = prefs.tapMode == 0
        val left = if (third) x < w / 3f else x < w / 2f
        val right = if (third) x > w * 2f / 3f else x >= w / 2f
        when {
            left -> if (rtl) forward() else backward()
            right -> if (rtl) backward() else forward()
            else -> showMenu()
        }
    }

    // ---------- 按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (prefs.volumeKeys) {
                if (prefs.swapVolume) backward() else forward(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (prefs.volumeKeys) {
                if (prefs.swapVolume) forward() else backward(); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> { forward(); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> { backward(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (rtl) backward() else forward(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (rtl) forward() else backward(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (menuVisible) hideMenu() else showMenu(); return true
            }
            KeyEvent.KEYCODE_BACK -> if (menuVisible) { hideMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------- 全屏 ----------

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        if (::chapter.isInitialized) savePosition()
    }
}
